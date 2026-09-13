package vn.sotarpayments.napcard.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class NapTheCommand implements CommandExecutor, TabCompleter {
    private final SotarPayments plugin;

    public NapTheCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }
        if (!plugin.ensureFeatureAvailable(player)) return true;

        if (args.length == 0 || (args.length >= 1 && args[0].equalsIgnoreCase("gui"))) {
            plugin.getModernPaymentInterfaceManager().openCardProvider(player);
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.tr("card.usage"));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.tr("card.amount-number"));
            return true;
        }

        if (args.length >= 4) {
            plugin.getCardFlowManager().submitCredentials(player, args[0], amount, args[2], args[3]);
            return true;
        }

        plugin.getCardFlowManager().startChatInput(player, args[0], amount);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("gui");
            suggestions.addAll(plugin.getPaymentGuiManager().getCardTelcos());
            return suggestions.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("gui")) {
            return plugin.getPaymentGuiManager().getCardAmounts().stream()
                    .map(String::valueOf)
                    .filter(s -> s.startsWith(args[1]))
                    .collect(Collectors.toList());
        }
        if (args.length == 3) return List.of("<serial>");
        if (args.length == 4) return List.of("<code>");
        return new ArrayList<>();
    }

}
