package dev.zenith.md3panel;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import dev.zenith.md3panel.command.MD3PanelCommand;
import dev.zenith.md3panel.web.WebServer;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Material Design 3 web panel: run commands, edit config, watch logs",
    url = "https://github.com/",
    authors = {"you"},
    mcVersions = {"*"}
)
public class MD3PanelPlugin implements ZenithProxyPlugin {
    public static MD3PanelConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;
    public static WebServer SERVER;
    public static String VERSION = BuildConstants.VERSION;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, MD3PanelConfig.class);
        pluginAPI.registerCommand(new MD3PanelCommand());
        if (PLUGIN_CONFIG.enabled) {
            SERVER = new WebServer(
                PLUGIN_CONFIG.port,
                PLUGIN_CONFIG.logRetentionEntries
            );
        } else {
            LOG.info("MD3 Panel disabled in config (md3-panel.json)");
        }
    }
}
