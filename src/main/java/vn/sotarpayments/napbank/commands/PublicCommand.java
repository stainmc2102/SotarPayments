package vn.sotarpayments.napbank.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import vn.sotarpayments.SotarPayments;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class PublicCommand implements CommandExecutor, TabCompleter {
    private final SotarPayments plugin;

    public PublicCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && isAdminKeyword(args[0])) {
            sender.sendMessage(plugin.tr("admin.command-moved"));
            return true;
        }

        for (String line : plugin.trList("admin.help")) {
            sender.sendMessage(line);
        }
        plugin.sendSupportLink(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help"), args[0]);
        }
        return List.of();
    }

    private boolean isAdminKeyword(String value) {
        if (value == null) return false;
        String sub = value.toLowerCase(Locale.ROOT);
        return sub.equals("reload")
                || sub.equals("status")
                || sub.equals("napthucong")
                || sub.equals("manual")
                || sub.equals("reset")
                || sub.equals("lichsunap")
                || sub.equals("history")
                || sub.equals("mocnap")
                || sub.equals("milestone")
                || sub.equals("gui")
                || sub.equals("menu")
                || sub.equals("dashboard");
    }

    private List<String> filter(List<String> suggestions, String token) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(s -> s.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
