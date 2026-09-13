package vn.sotarpayments.napbank.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.manager.DatabaseMigration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final SotarPayments plugin;
    private final HistoryCommand historyCommand;
    private final MilestoneCommand milestoneCommand;

    public AdminCommand(SotarPayments plugin, HistoryCommand historyCommand, MilestoneCommand milestoneCommand) {
        this.plugin = plugin;
        this.historyCommand = historyCommand;
        this.milestoneCommand = milestoneCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAdminPermission(sender)) return true;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "menu", "dashboard" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.tr("general.player-only"));
                    return true;
                }
                plugin.getAdminGUIManager().openDashboard(player);
                return true;
            }
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage(plugin.tr("admin.reload-success"));
                return true;
            }
            case "status" -> {
                sendStatus(sender);
                return true;
            }
            case "napthucong", "manual" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.tr("admin.manual-usage"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.tr("admin.player-not-found"));
                    return true;
                }
                try {
                    long amount = Long.parseLong(args[2]);
                    plugin.processManualTopup(sender, target, amount);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.tr("general.invalid-number"));
                }
                return true;
            }
            case "lichsunap", "history" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.tr("admin.history-usage"));
                    return true;
                }
                String targetName = args[1];
                if (sender instanceof Player player) {
                    historyCommand.openHistory(player, targetName, true);
                } else {
                    historyCommand.sendHistoryText(sender, targetName);
                }
                return true;
            }
            case "mocnap", "milestone", "moc" -> {
                String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                return milestoneCommand.handleAdminCommand(sender, subArgs);
            }
            case "reset" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.tr("admin.reset-usage"));
                    return true;
                }
                if (args[1].equalsIgnoreCase("topnap")) {
                    if (!args[2].equalsIgnoreCase("confirm")) {
                        sender.sendMessage(plugin.tr("admin.reset-confirm"));
                        return true;
                    }
                    plugin.resetTopNap(sender);
                    return true;
                }
                sender.sendMessage(plugin.tr("admin.reset-invalid"));
                return true;
            }
            case "migrate-database", "migratedb" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.tr("admin.migrate-db-usage"));
                    return true;
                }
                String sourceType = args[1].toLowerCase(Locale.ROOT);
                String sourceConfig = args.length >= 3 ? args[2] : "";
                sender.sendMessage(plugin.tr("admin.migrate-db-start", "source", sourceType));
                plugin.getPlatformScheduler().runAsync(() -> {
                    DatabaseMigration.MigrationResult result = DatabaseMigration.migrate(plugin, sourceType, sourceConfig,
                            message -> plugin.getPlatformScheduler().runGlobal(() -> sender.sendMessage(
                                    plugin.tr("admin.migrate-db-progress", "message", message))));
                    plugin.getPlatformScheduler().runGlobal(() -> {
                        if (result.success()) {
                            sender.sendMessage(plugin.tr("admin.migrate-db-success", "message", result.message()));
                        } else {
                            sender.sendMessage(plugin.tr("admin.migrate-db-failed", "message", result.message()));
                        }
                    });
                });
                return true;
            }
            default -> {
                sender.sendMessage(plugin.tr("general.command-not-found"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sotarpayments.admin")) return List.of();

        if (args.length == 1) {
            return filter(List.of(
                    "help", "gui", "status", "reload", "napthucong", "lichsunap", "reset", "mocnap", "migrate-database"
            ), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("napthucong") || sub.equals("lichsunap"))) {
            return null;
        }

        if (args.length == 3 && sub.equals("napthucong")) {
            return filter(List.of("10000", "20000", "50000", "100000", "200000", "500000"), args[2]);
        }

        if (args.length == 2 && (sub.equals("migrate-database") || sub.equals("migratedb"))) {
            return filter(List.of("sqlite"), args[1]);
        }

        if (sub.equals("reset")) {
            if (args.length == 2) return filter(List.of("topnap"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("topnap")) return filter(List.of("confirm"), args[2]);
            return List.of();
        }

        if (sub.equals("mocnap") || sub.equals("milestone") || sub.equals("moc")) {
            return milestoneCommand.onAdminTabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        return List.of();
    }

    private boolean hasAdminPermission(CommandSender sender) {
        if (sender.hasPermission("sotarpayments.admin")) return true;
        sender.sendMessage(plugin.tr("general.no-permission"));
        return false;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : plugin.trList("admin.help")) {
            sender.sendMessage(line);
        }
        plugin.sendSupportLink(sender);
    }

    private void sendStatus(CommandSender sender) {
        String platform = plugin.getPlatformScheduler().getPlatformName();
        String language = plugin.getLanguageManager().getLanguageCode();

        String bankProvider = plugin.getBankProviderName();
        String cardProvider = plugin.getCardProviderName();

        int pollEvery = plugin.config().getInt("napbank.poll-every-seconds", 10);
        int timeout = plugin.config().getInt("napbank.timeout-seconds", 600);

        boolean webhook = plugin.config().getBoolean("discord-webhook.enabled", false);
        boolean placeholder = org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        for (String line : plugin.trList(
                "admin.status",
                "platform", platform,
                "language", language,
                "bank_provider", bankProvider,
                "card_provider", cardProvider,
                "poll_every", String.valueOf(pollEvery),
                "timeout", String.valueOf(timeout),
                "webhook", webhook ? plugin.tr("general.enabled") : plugin.tr("general.disabled"),
                "placeholder", placeholder ? plugin.tr("general.enabled") : plugin.tr("general.disabled")
        )) {
            sender.sendMessage(line);
        }
        plugin.sendSupportLink(sender);
    }

    private List<String> filter(List<String> suggestions, String token) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(s -> s.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
