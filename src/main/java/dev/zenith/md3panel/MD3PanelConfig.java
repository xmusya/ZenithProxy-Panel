package dev.zenith.md3panel;

import java.util.ArrayList;
import java.util.List;

public class MD3PanelConfig {
    public boolean enabled = true;
    /**
     * HTTP port for the web panel.
     */
    public int port = 8090;
    /**
     * Allow panel commands to run commands that require account owner.
     */
    public boolean commandsAccountOwnerPerms = true;
    /**
     * Number of console log lines retained for the log viewer.
     */
    public int logRetentionEntries = 300;
    /**
     * UI customization (theme, tabs, widgets, quick actions).
     * Managed by the in-site "Customize" tab.
     */
    public UiSettings ui = new UiSettings();

    public static class UiSettings {
        /**
         * system | light | dark
         */
        public String themeMode = "system";
        /**
         * Accent color as #rrggbb, or "" for the default MD3 palette.
         */
        public String accent = "";
        /**
         * Visible nav tabs in display order.
         */
        public List<String> tabOrder = new ArrayList<>(List.of(
            "dash", "commands", "modules", "config", "logs"
        ));
        /**
         * Tabs hidden from the nav (subset of tabOrder).
         */
        public List<String> hiddenTabs = new ArrayList<>();
        /**
         * Hidden dashboard widgets.
         */
        public List<String> hiddenWidgets = new ArrayList<>();
        /**
         * Dashboard quick action buttons.
         */
        public List<QuickAction> quickActions = new ArrayList<>(List.of(
            new QuickAction("status", "status", "tonal"),
            new QuickAction("queueStatus", "queueStatus", "tonal"),
            new QuickAction("reconnect", "reconnect", "filled"),
            new QuickAction("disconnect", "disconnect", "danger"),
            new QuickAction("tickrate", "tps", "tonal")
        ));
    }

    public static class QuickAction {
        public String label;
        public String command;
        /**
         * filled | tonal | outlined | danger
         */
        public String style;

        public QuickAction() { }

        public QuickAction(final String label, final String command, final String style) {
            this.label = label;
            this.command = command;
            this.style = style;
        }
    }
}
