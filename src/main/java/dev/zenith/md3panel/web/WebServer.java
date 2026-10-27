package dev.zenith.md3panel.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zenith.Globals;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.md3panel.MD3PanelConfig;
import dev.zenith.md3panel.MD3PanelPlugin;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.zenith.Globals.COMMAND;
import static com.zenith.Globals.GSON;
import static dev.zenith.md3panel.MD3PanelPlugin.LOG;
import static dev.zenith.md3panel.MD3PanelPlugin.PLUGIN_CONFIG;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.post;
import static io.javalin.apibuilder.ApiBuilder.ws;

public class WebServer {
    private static final String ANSI_REGEX = "\u001B\\[[;\\d]*m";
    private static final List<String> KNOWN_TABS = List.of(
        "dash", "commands", "modules", "config", "logs");
    private static final List<String> KNOWN_WIDGETS = List.of(
        "conn", "player", "uptime", "modules", "position", "queue", "actions");
    private static final List<String> KNOWN_STYLES = List.of(
        "filled", "tonal", "outlined", "danger");

    private final Javalin server;
    private final Map<String, WsContext> wsClients = new ConcurrentHashMap<>();
    private final LogBuffer logBuffer;

    public WebServer(final int port, final int logRetention) {
        this.logBuffer = new LogBuffer(logRetention);
        this.logBuffer.setSink(this::broadcastLog);

        this.server = Javalin.create(config -> {
            var threadPool = new ExecutorThreadPool();
            threadPool.setDaemon(true);
            threadPool.setName("ZenithProxy-MD3Panel-%d");
            config.jetty.threadPool = threadPool;
            config.http.defaultContentType = "application/json";
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.routes.exception(Exception.class, (e, ctx) -> {
                LOG.error("MD3 Panel endpoint error: {}", ctx.path(), e);
                json(ctx.status(500), Map.of("error", String.valueOf(e.getMessage())));
            });
            config.routes.apiBuilder(this::registerRestEndpoints);
        });

        try {
            server.start(port);
            LOG.info("MD3 Panel listening on http://localhost:{}", port);
        } catch (final Exception e) {
            LOG.error("Failed to start MD3 Panel on port {}", port, e);
        }
    }

    public void shutdown() {
        if (server != null) server.stop();
    }

    private void registerRestEndpoints() {
        // live log stream
        ws("/api/logs/live", wsc -> {
            wsc.onConnect(ctx -> {
                ctx.session.setIdleTimeout(Duration.ZERO);
                wsClients.put(ctx.sessionId(), ctx);
            });
            wsc.onClose(ctx -> wsClients.remove(ctx.sessionId()));
            wsc.onError(ctx -> wsClients.remove(ctx.sessionId()));
            wsc.onMessage(this::handleLogStreamMessage);
        });

        // The SPA — index.html handles routing client-side
        get("/", ctx -> {
            try (var in = WebServer.class.getResourceAsStream("/web/index.html")) {
                if (in == null) {
                    ctx.status(404).result("index.html missing from plugin jar");
                    return;
                }
                ctx.contentType("text/html")
                    .result(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        });

        // ===== Status =====
        get("/api/status", ctx -> json(ctx, buildStatus()));

        // ===== Console logs =====
        get("/api/logs", ctx -> {
            long from = parseLong(ctx.queryParam("from"), 0L);
            json(ctx, logBuffer.snapshot(from));
        });

        // ===== Modules =====
        get("/api/modules", ctx -> json(ctx, Map.of("modules", buildModuleList())));
        post("/api/modules/{name}", ctx -> {
            String name = ctx.pathParam("name");
            String state = Optional.ofNullable(ctx.queryParam("state"))
                .map(String::toLowerCase)
                .filter(s -> s.equals("on") || s.equals("off"))
                .orElse(null);
            if (state == null) {
                json(ctx.status(400), Map.of("error", "state must be 'on' or 'off'"));
                return;
            }
            var module = Globals.MODULE.getModules().stream()
                .filter(m -> m.getClass().getSimpleName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
            if (module == null) {
                json(ctx.status(404), Map.of("error", "unknown module: " + name));
                return;
            }
            var cmd = findModuleToggleCommand(name);
            if (cmd == null) {
                json(ctx.status(400), Map.of(
                    "ok", false,
                    "error", "no toggle command found for module " + name
                ));
                return;
            }
            String output = executeCommand(cmd.commandUsage().getName() + " " + state);
            json(ctx, Map.of(
                "ok", isOkOutput(output),
                "output", output,
                "modules", buildModuleList()
            ));
        });

        // ===== Commands =====
        get("/api/commands", ctx -> {
            var commands = COMMAND.getCommands().stream()
                .map(cmd -> {
                    CommandUsage usage = cmd.commandUsage();
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", usage.getName());
                    m.put("description", usage.getDescription());
                    m.put("category", usage.getCategory().name());
                    m.put("usage", usage.getUsageLines());
                    m.put("aliases", usage.getAliases());
                    return m;
                })
                .toList();
            json(ctx, Map.of("commands", commands));
        });
        post("/api/commands/run", ctx -> {
            JsonObject body = parseBody(ctx);
            String cmd = body != null && body.has("command") && body.get("command").isJsonPrimitive()
                ? body.get("command").getAsString()
                : "";
            if (cmd.isBlank()) {
                json(ctx.status(400), Map.of("error", "command is required"));
                return;
            }
            String output = executeCommand(cmd);
            json(ctx, Map.of(
                "ok", isOkOutput(output),
                "output", output
            ));
        });
        get("/api/commands/suggest", ctx -> {
            String input = Optional.ofNullable(ctx.queryParam("input")).orElse("");
            json(ctx, Map.of("suggestions", suggest(input)));
        });

        // ===== Configs =====
        get("/api/configs", this::handleConfigList);
        post("/api/configs/{id}/update", this::handleConfigUpdate);
        post("/api/configs/save", ctx -> {
            com.zenith.Globals.saveConfigAsync();
            json(ctx, Map.of("ok", true));
        });

        // ===== UI customization =====
        get("/api/panel/ui", ctx -> json(ctx, PLUGIN_CONFIG.ui));
        post("/api/panel/ui", ctx -> {
            JsonObject body = parseBody(ctx);
            if (body == null) {
                json(ctx.status(400), Map.of("error", "invalid JSON body"));
                return;
            }
            try {
                var incoming = GSON.fromJson(body, MD3PanelConfig.UiSettings.class);
                PLUGIN_CONFIG.ui = sanitizeUi(incoming);
                com.zenith.Globals.saveConfigAsync();
                json(ctx, PLUGIN_CONFIG.ui);
            } catch (final Exception e) {
                json(ctx.status(400), Map.of("error", String.valueOf(e.getMessage())));
            }
        });
    }

    // ===================== Status =====================

    private Map<String, Object> buildStatus() {
        var playerCache = com.zenith.Globals.CACHE.getPlayerCache();
        var proxy = com.zenith.Proxy.getInstance();
        var map = new HashMap<String, Object>();
        map.put("connected", proxy.isConnected());
        map.put("activePlayer", proxy.hasActivePlayer());
        map.put("queue", proxy.isInQueue());
        map.put("queuePosition", proxy.getQueuePosition());
        map.put("prio", proxy.isPrio());
        map.put("username", com.zenith.Globals.CONFIG.authentication.username);
        map.put("uptime", proxy.getOnlineTimeString());
        if (playerCache.getThePlayer() != null) {
            map.put("health", playerCache.getThePlayer().getHealth());
            map.put("pos", Map.of(
                "x", round(playerCache.getX()),
                "y", round(playerCache.getY()),
                "z", round(playerCache.getZ())
            ));
            var dim = com.zenith.Globals.CACHE.getChunkCache().getCurrentDimension();
            map.put("dimension", dim != null ? dim.name() : "unknown");
            map.put("gamemode", String.valueOf(playerCache.getGameMode()));
        }
        var modules = com.zenith.Globals.MODULE.getModules();
        map.put("modules", Map.of(
            "total", modules.size(),
            "enabled", modules.stream().filter(m -> m.isEnabled()).count()
        ));
        map.put("tps", com.zenith.Globals.TPS.getTPS());
        map.put("version", MD3PanelPlugin.VERSION);
        return map;
    }

    // ===================== Modules =====================

    private List<Map<String, Object>> buildModuleList() {
        var toggleCommands = COMMAND.getCommands(CommandCategory.MODULE).stream()
            .map(c -> c.commandUsage().getName().toLowerCase(Locale.ROOT))
            .toList();
        return com.zenith.Globals.MODULE.getModules().stream()
            .map(m -> {
                String simpleName = m.getClass().getSimpleName();
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", simpleName);
                entry.put("enabled", m.isEnabled());
                entry.put("toggleable", matchesToggleCommand(simpleName, toggleCommands));
                return entry;
            })
            .toList();
    }

    /**
     * A module's toggle command has the same name as the module class in most cases
     * (antiAFK <-> AntiAFK). Some differ slightly (replay <-> ReplayMod), so also
     * accept one side being a prefix of the other.
     */
    private static boolean matchesToggleCommand(final String moduleSimpleName, final List<String> commandNames) {
        String norm = moduleSimpleName.toLowerCase(Locale.ROOT);
        return commandNames.stream().anyMatch(cmd ->
            norm.equals(cmd) || norm.startsWith(cmd) || cmd.startsWith(norm));
    }

    private Command findModuleToggleCommand(final String moduleSimpleName) {
        String norm = moduleSimpleName.toLowerCase(Locale.ROOT);
        return COMMAND.getCommands(CommandCategory.MODULE).stream()
            .filter(c -> {
                String cmd = c.commandUsage().getName().toLowerCase(Locale.ROOT);
                return norm.equals(cmd) || norm.startsWith(cmd) || cmd.startsWith(norm);
            })
            // prefer an exact match (spectator vs spectatorEntity, ...)
            .min(Comparator.comparingInt(c -> {
                String cmd = c.commandUsage().getName().toLowerCase(Locale.ROOT);
                return norm.equals(cmd) ? 0 : 1;
            }))
            .orElse(null);
    }

    // ===================== Command execution =====================

    /**
     * Executes a command as the panel source and captures the embed/multi-line output.
     */
    public static String executeCommand(final String command) {
        try {
            CommandContext ctx = CommandContext.create(command, PanelCommandSource.INSTANCE);
            COMMAND.execute(ctx);
            Embed embed = ctx.getEmbed();
            var sb = new StringBuilder();
            if (embed != null) {
                if (embed.isTitlePresent()) sb.append(embed.title());
                if (embed.isDescriptionPresent()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(embed.description());
                }
                for (Embed.Field field : embed.fields()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(field.name()).append(": ").append(field.value());
                }
            }
            if (ctx.getMultiLineOutput() != null) {
                for (String line : ctx.getMultiLineOutput()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            return stripAnsi(sb.toString());
        } catch (final Throwable t) {
            LOG.error("Command execution failed: {}", command, t);
            return "Error: " + t.getMessage();
        }
    }

    private static boolean isOkOutput(final String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return !lower.startsWith("error:")
            && !lower.contains("not authorized")
            && !lower.contains("unknown command");
    }

    private static List<String> suggest(final String input) {
        try {
            return COMMAND.suggestions(input, PanelCommandSource.INSTANCE)
                .join().getList().stream()
                .map(Object::toString)
                .toList();
        } catch (final Throwable t) {
            return List.of();
        }
    }

    // ===================== Configs =====================

    private void handleConfigList(final Context ctx) {
        JsonArray arr = new JsonArray();
        // core config
        JsonObject core = new JsonObject();
        core.addProperty("id", "core");
        core.addProperty("file", com.zenith.Globals.CONFIG_FILE.getName());
        core.add("config", GSON.toJsonTree(com.zenith.Globals.CONFIG));
        arr.add(core);
        // plugin configs
        for (var instance : com.zenith.Globals.PLUGIN_MANAGER.getAllPluginConfigs()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", stripExtension(instance.file().getName()));
            o.addProperty("file", instance.file().getName());
            o.add("config", GSON.toJsonTree(instance.instance()));
            arr.add(o);
        }
        json(ctx, arr);
    }

    /**
     * Applies a {"path": "a.b.c", "value": ...} patch to a config object via reflection.
     * ZenithProxy configs use final reference fields, so we mutate leaves in place
     * instead of reassigning parents.
     */
    private void handleConfigUpdate(final Context ctx) {
        String id = ctx.pathParam("id");
        JsonObject body = parseBody(ctx);
        if (body == null || !body.has("path") || !body.has("value")) {
            json(ctx.status(400), Map.of("error", "path and value are required"));
            return;
        }
        String path = body.get("path").getAsString();
        JsonElement value = body.get("value");

        Object targetConfig;
        if (id.equals("core")) {
            targetConfig = com.zenith.Globals.CONFIG;
        } else {
            targetConfig = com.zenith.Globals.PLUGIN_MANAGER.getAllPluginConfigs().stream()
                .filter(i -> stripExtension(i.file().getName()).equals(id))
                .map(com.zenith.plugin.PluginManager.ConfigInstance::instance)
                .findFirst()
                .orElse(null);
        }
        if (targetConfig == null) {
            json(ctx.status(404), Map.of("error", "unknown config: " + id));
            return;
        }

        try {
            applyPath(targetConfig, path.split("\\."), value);
        } catch (final IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            json(ctx.status(400), Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
            return;
        } catch (final Exception e) {
            LOG.error("Config update failed for {} at {}", id, path, e);
            json(ctx.status(500), Map.of("error", e.toString()));
            return;
        }

        // return the updated config tree
        json(ctx, GSON.toJsonTree(targetConfig));
    }

    private static void applyPath(final Object root, final String[] segments, final JsonElement value)
        throws IllegalAccessException {
        Object current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            var field = findField(current.getClass(), segments[i]);
            if (field == null) {
                throw new IllegalArgumentException("unknown path segment: " + segments[i]);
            }
            field.setAccessible(true);
            current = field.get(current);
            if (current == null) {
                throw new IllegalArgumentException("path segment is null: " + segments[i]);
            }
        }
        var leaf = findField(current.getClass(), segments[segments.length - 1]);
        if (leaf == null) {
            throw new IllegalArgumentException("unknown path segment: " + segments[segments.length - 1]);
        }
        leaf.setAccessible(true);
        Object converted = convert(value, leaf.getType(), segments[segments.length - 1]);
        leaf.set(current, converted);
    }

    private static java.lang.reflect.Field findField(final Class<?> clazz, final String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (final NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object convert(final JsonElement value, final Class<?> type, final String fieldName) {
        if (value.isJsonNull()) {
            if (type.isPrimitive()) throw new IllegalArgumentException("cannot set " + fieldName + " to null");
            return null;
        }
        if (type == String.class) {
            return value.isJsonPrimitive() ? value.getAsString() : value.toString();
        }
        if (type == boolean.class || type == Boolean.class) {
            if (!value.isJsonPrimitive()) throw new IllegalArgumentException(fieldName + " expects a boolean");
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            String s = value.getAsString();
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on")) return true;
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off")) return false;
            throw new IllegalArgumentException(fieldName + " expects a boolean");
        }
        if (type == int.class || type == Integer.class) return value.getAsInt();
        if (type == long.class || type == Long.class) return value.getAsLong();
        if (type == double.class || type == Double.class) return value.getAsDouble();
        if (type == float.class || type == Float.class) return value.getAsFloat();
        if (type == short.class || type == Short.class) return value.getAsShort();
        if (type == byte.class || type == Byte.class) return value.getAsByte();
        if (type.isEnum()) {
            String s = value.getAsString();
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(s)
                    || ((Enum<?>) constant).toString().equalsIgnoreCase(s)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("invalid value for " + fieldName + ": " + s);
        }
        throw new IllegalArgumentException(
            "field " + fieldName + " of type " + type.getSimpleName()
                + " cannot be edited directly; edit its sub-fields");
    }

    // ===================== Log streaming =====================

    private void handleLogStreamMessage(final WsMessageContext ctx) {
        // log streaming is push-only; messages are ignored
    }

    /**
     * Called by the LogBuffer on each console log event to fan out to WS clients.
     */
    private void broadcastLog(final long index, final String ansi) {
        if (wsClients.isEmpty()) return;
        var msg = new JsonObject();
        msg.addProperty("type", "log");
        msg.addProperty("index", index);
        msg.addProperty("ansi", ansi);
        msg.addProperty("plain", stripAnsi(ansi));
        String json;
        try {
            json = GSON.toJson(msg);
        } catch (final Exception e) {
            return;
        }
        for (WsContext ws : wsClients.values()) {
            try {
                ws.send(json);
            } catch (final Exception ignored) {
            }
        }
    }

    // ===================== UI customization =====================

    /**
     * Validates and normalizes incoming UI settings so the client can never
     * store unknown tab/widget ids, broken styles, or oversized lists.
     */
    private static MD3PanelConfig.UiSettings sanitizeUi(final MD3PanelConfig.UiSettings in) {
        var out = new MD3PanelConfig.UiSettings();
        if (in == null) return out;

        out.themeMode = "light".equals(in.themeMode) || "dark".equals(in.themeMode)
            ? in.themeMode : "system";
        out.accent = in.accent != null && in.accent.matches("#[0-9a-fA-F]{6}")
            ? in.accent : "";

        var order = new ArrayList<String>();
        if (in.tabOrder != null) {
            for (String tab : in.tabOrder) {
                if (KNOWN_TABS.contains(tab) && !order.contains(tab)) order.add(tab);
            }
        }
        for (String tab : KNOWN_TABS) {
            if (!order.contains(tab)) order.add(tab);
        }
        out.tabOrder = order;
        out.hiddenTabs = sanitizeSubset(in.hiddenTabs, order);
        out.hiddenWidgets = sanitizeSubset(in.hiddenWidgets, KNOWN_WIDGETS);

        out.quickActions = new ArrayList<>();
        if (in.quickActions != null) {
            for (var action : in.quickActions) {
                if (out.quickActions.size() >= 30) break;
                if (action == null || action.label == null || action.command == null) continue;
                String label = action.label.trim();
                String command = action.command.trim();
                if (label.isEmpty() || command.isEmpty()) continue;
                String style = KNOWN_STYLES.contains(action.style) ? action.style : "tonal";
                out.quickActions.add(new MD3PanelConfig.QuickAction(
                    label.substring(0, Math.min(40, label.length())),
                    command.substring(0, Math.min(120, command.length())),
                    style
                ));
            }
        }
        return out;
    }

    private static ArrayList<String> sanitizeSubset(final List<String> input, final List<String> allowed) {
        var out = new ArrayList<String>();
        if (input != null) {
            for (String item : input) {
                if (allowed.contains(item) && !out.contains(item)) out.add(item);
            }
        }
        return out;
    }

    // ===================== Helpers =====================

    private static Context json(final Context ctx, final Object object) {
        return jsonEl(ctx, GSON.toJsonTree(object));
    }

    private static Context jsonEl(final Context ctx, final JsonElement element) {
        return ctx.contentType("application/json").result(GSON.toJson(element));
    }

    private static JsonObject parseBody(final Context ctx) {
        try {
            String body = ctx.body();
            if (body == null || body.isBlank()) return null;
            return GSON.fromJson(body, JsonObject.class);
        } catch (final Exception e) {
            return null;
        }
    }

    private static String stripAnsi(final String s) {
        return s == null ? null : s.replaceAll(ANSI_REGEX, "");
    }

    private static String stripExtension(final String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static long parseLong(final String s, final long def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Long.parseLong(s);
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    private static double round(final double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
