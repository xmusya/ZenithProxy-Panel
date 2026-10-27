package dev.zenith.md3panel.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.md3panel.MD3PanelPlugin;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.md3panel.MD3PanelPlugin.PLUGIN_CONFIG;

public class MD3PanelCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("md3Panel")
            .category(CommandCategory.CORE)
            .description("""
                Material Design 3 web panel for ZenithProxy.
                No auth required - local network use only.
                Restart required for port/enabled changes.
                """)
            .usageLines(
                "status",
                "on/off",
                "port <port>",
                "accountOwnerCmds on/off",
                "logRetention <lines>"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("md3Panel").requires(Command::validateAccountOwner)
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("MD3 Panel " + toggleStrCaps(PLUGIN_CONFIG.enabled))
                    .description("Restart required for this change to take effect.")
                    .color(com.zenith.util.Color.fromInt(0xF57C00));
            }))
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed()
                    .title("MD3 Panel Status")
                    .addField("Enabled", toggleStr(PLUGIN_CONFIG.enabled))
                    .addField("Server", MD3PanelPlugin.SERVER != null ? "Running" : "Stopped")
                    .addField("URL", "http://localhost:" + PLUGIN_CONFIG.port, false)
                    .addField("Account Owner Commands", toggleStr(PLUGIN_CONFIG.commandsAccountOwnerPerms))
                    .addField("Log Retention", PLUGIN_CONFIG.logRetentionEntries + " lines")
                    .primaryColor();
            }))
            .then(literal("port").then(argument("port", integer(1, 65535)).executes(c -> {
                PLUGIN_CONFIG.port = getInteger(c, "port");
                c.getSource().getEmbed()
                    .title("Port Set: " + PLUGIN_CONFIG.port)
                    .description("Restart required for the new port to take effect.")
                    .color(com.zenith.util.Color.fromInt(0xF57C00));
            })))
            .then(literal("accountOwnerCmds").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.commandsAccountOwnerPerms = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Account Owner Commands " + toggleStrCaps(PLUGIN_CONFIG.commandsAccountOwnerPerms));
            })))
            .then(literal("logRetention").then(argument("lines", integer(50, 10000)).executes(c -> {
                PLUGIN_CONFIG.logRetentionEntries = getInteger(c, "lines");
                c.getSource().getEmbed()
                    .title("Log Retention Set: " + PLUGIN_CONFIG.logRetentionEntries + " lines")
                    .description("Restart required to apply.")
                    .color(com.zenith.util.Color.fromInt(0xF57C00));
            })));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed
            .addField("MD3 Panel", toggleStr(PLUGIN_CONFIG.enabled))
            .addField("Server", MD3PanelPlugin.SERVER != null ? "Running" : "Stopped")
            .addField("Port", PLUGIN_CONFIG.port)
            .primaryColor();
    }
}
