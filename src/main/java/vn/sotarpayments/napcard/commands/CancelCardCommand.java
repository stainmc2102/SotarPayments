package vn.sotarpayments.napcard.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;

public class CancelCardCommand implements CommandExecutor {
    private final SotarPayments plugin;

    public CancelCardCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }

        if (plugin.getCardSessionManager().get(player.getUniqueId()) != null) {
            plugin.getCardSessionManager().stop(player.getUniqueId());
            player.sendMessage(plugin.tr("card.canceled"));
        }
        return true;
    }
}
