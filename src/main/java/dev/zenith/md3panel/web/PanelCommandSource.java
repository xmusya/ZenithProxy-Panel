package dev.zenith.md3panel.web;

import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandOutputHelper;
import com.zenith.command.api.CommandSource;
import com.zenith.discord.Embed;

import static dev.zenith.md3panel.MD3PanelPlugin.PLUGIN_CONFIG;

public class PanelCommandSource implements CommandSource {
    public static final PanelCommandSource INSTANCE = new PanelCommandSource();

    @Override
    public String name() {
        return "MD3Panel";
    }

    @Override
    public boolean validateAccountOwner(final CommandContext ctx) {
        if (PLUGIN_CONFIG.commandsAccountOwnerPerms) {
            return true;
        } else {
            ctx.getEmbed()
                .description("The web panel is not authorized to execute this command!");
            return false;
        }
    }

    @Override
    public void logEmbed(final CommandContext commandContext, final Embed embed) {
        CommandOutputHelper.logEmbedOutputToTerminal(embed);
    }
}
