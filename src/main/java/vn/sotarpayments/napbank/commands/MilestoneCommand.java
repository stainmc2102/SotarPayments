package vn.sotarpayments.napbank.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.manager.GUIUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class MilestoneCommand implements TabExecutor {
    private final SotarPayments plugin;

    private enum MilestoneMenuType {
        PERSONAL,
        SERVER
    }

    public MilestoneCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return openMilestoneMenu(sender, MilestoneMenuType.PERSONAL);
        }

        String sub = normalize(args[0]);

        if (isPersonalMenuSubCommand(sub)) {
            if (args.length == 1) {
                return openMilestoneMenu(sender, MilestoneMenuType.PERSONAL);
            }
            if (isHelpSubCommand(args[1])) {
                sendHelp(sender);
                return true;
            }
            sendHelp(sender);
            return true;
        }

        if (isServerMenuSubCommand(sub)) {
            if (args.length == 1) {
                return openMilestoneMenu(sender, MilestoneMenuType.SERVER);
            }

            String serverSub = normalize(args[1]);
            if (isBossBarSubCommand(serverSub)) {
                return handleBossBar(sender, copyArgsFrom(args, 1));
            }
            if (isHelpSubCommand(serverSub)) {
                sendHelp(sender);
                return true;
            }

            sendHelp(sender);
            return true;
        }

        if (sub.equals("set") || (isBossBarSubCommand(sub) && args.length >= 2 && args[1].equalsIgnoreCase("global"))) {
            sender.sendMessage(plugin.tr("server-milestone.admin-command-moved"));
            return true;
        }

        if (isBossBarSubCommand(sub)) {
            return handleBossBar(sender, args);
        }

        if (isHelpSubCommand(sub)) {
            sendHelp(sender);
            return true;
        }

        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterSuggestions(List.of("canhan", "server", "bossbar", "help"), args[0]);
        }

        if (args.length == 2) {
            String first = normalize(args[0]);
            if (isBossBarSubCommand(first)) {
                return filterSuggestions(List.of("on", "off", "toggle", "status"), args[1]);
            }
            if (isServerMenuSubCommand(first)) {
                return filterSuggestions(List.of("bossbar", "help"), args[1]);
            }
            if (isPersonalMenuSubCommand(first)) {
                return filterSuggestions(List.of("help"), args[1]);
            }
        }

        if (args.length == 3 && isServerMenuSubCommand(args[0]) && isBossBarSubCommand(args[1])) {
            return filterSuggestions(List.of("on", "off", "toggle", "status"), args[2]);
        }

        return List.of();
    }

    public boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sotarpayments.admin")) {
            sender.sendMessage(plugin.tr("general.no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("set")) {
            return handleSetTarget(sender, args);
        }

        if (isRequirementSubCommand(sub)) {
            return handlePersonalRequirement(sender, args);
        }

        if (isBossBarSubCommand(sub)) {
            String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
            return handleGlobalBossBarAction(sender, action);
        }

        sender.sendMessage(plugin.tr("server-milestone.admin-usage"));
        return true;
    }

    public List<String> onAdminTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sotarpayments.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            return filterSuggestions(List.of("set", "dieukien", "bossbar", "help"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("auto");
            for (Long milestone : plugin.getMilestoneManager().getServerMilestones()) {
                suggestions.add(String.valueOf(milestone));
            }
            return filterSuggestions(suggestions, args[1]);
        }

        if (args.length == 2 && isRequirementSubCommand(args[0])) {
            List<String> suggestions = plugin.getMilestoneManager().getServerMilestones().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
            return filterSuggestions(suggestions, args[1]);
        }

        if (args.length == 3 && isRequirementSubCommand(args[0])) {
            return filterSuggestions(List.of("0", "20000", "50000", "100000", "macdinh"), args[2]);
        }

        if (args.length == 2 && isBossBarSubCommand(args[0])) {
            return filterSuggestions(List.of("on", "off", "toggle", "status"), args[1]);
        }

        return List.of();
    }

    private boolean openMilestoneMenu(CommandSender sender, MilestoneMenuType type) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }

        if (type == MilestoneMenuType.SERVER) {
            plugin.getMilestoneGuiManager().openServerMilestoneRewards(player);
        } else {
            plugin.getMilestoneGuiManager().openMilestones(player);
        }
        return true;
    }

    private boolean handleSetTarget(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sotarpayments.admin")) {
            sender.sendMessage(plugin.tr("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.tr("server-milestone.set-usage"));
            return true;
        }

        if (args[1].equalsIgnoreCase("auto")) {
            plugin.getMilestoneManager().setActiveServerMilestoneTarget(0L);
            sender.sendMessage(plugin.tr("server-milestone.set-auto"));
            return true;
        }

        long amount;
        try {
            amount = parseAmount(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.tr("general.invalid-number"));
            return true;
        }

        if (amount <= 0L) {
            sender.sendMessage(plugin.tr("admin.amount-positive"));
            return true;
        }

        plugin.getMilestoneManager().setActiveServerMilestoneTarget(amount);
        sender.sendMessage(plugin.tr("server-milestone.set-success", "amount", GUIUtils.formatMoney(amount)));

        if (!plugin.getMilestoneManager().isServerMilestoneConfigured(amount)) {
            sender.sendMessage(plugin.tr("server-milestone.set-warning-no-config", "amount", GUIUtils.formatMoney(amount)));
        }
        return true;
    }

    private boolean handlePersonalRequirement(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.tr("server-milestone.requirement-usage"));
            return true;
        }

        long milestone;
        try {
            milestone = parseAmount(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.tr("general.invalid-number"));
            return true;
        }

        if (!plugin.getMilestoneManager().isServerMilestoneConfigured(milestone)) {
            sender.sendMessage(plugin.tr("server-milestone.requirement-milestone-missing",
                    "amount", GUIUtils.formatMoney(milestone)));
            return true;
        }

        String value = normalize(args[2]);
        if (value.equals("default") || value.equals("macdinh") || value.equals("mac-dinh")
                || value.equals("inherit") || value.equals("ke-thua") || value.equals("kethua")) {
            plugin.getMilestoneManager().setServerMilestonePersonalRequirement(milestone, null);
            sender.sendMessage(plugin.tr("server-milestone.requirement-default",
                    "milestone", GUIUtils.formatMoney(milestone),
                    "required", GUIUtils.formatMoney(plugin.getMilestoneManager().getServerMilestoneMinimumPersonalDonated())));
            return true;
        }

        long minimumPersonalDonated;
        try {
            minimumPersonalDonated = parseAmount(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.tr("general.invalid-number"));
            return true;
        }

        if (minimumPersonalDonated < 0L) {
            sender.sendMessage(plugin.tr("general.invalid-number"));
            return true;
        }

        plugin.getMilestoneManager().setServerMilestonePersonalRequirement(milestone, minimumPersonalDonated);
        sender.sendMessage(plugin.tr(minimumPersonalDonated == 0L
                        ? "server-milestone.requirement-disabled"
                        : "server-milestone.requirement-success",
                "milestone", GUIUtils.formatMoney(milestone),
                "required", GUIUtils.formatMoney(minimumPersonalDonated)));
        return true;
    }

    private boolean handleBossBar(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        if (action.equals("help") || action.equals("?")) {
            sender.sendMessage(plugin.tr("server-milestone.bossbar-usage"));
            return true;
        }

        if (action.equals("status")) {
            sendPersonalBossBarStatus(player);
            return true;
        }

        boolean visible;
        if (action.equals("toggle")) {
            visible = plugin.getMilestoneManager().toggleBossBarVisibility(player);
        } else {
            Boolean parsed = parseToggleValue(action);
            if (parsed == null) {
                sender.sendMessage(plugin.tr("server-milestone.bossbar-usage"));
                return true;
            }
            visible = parsed;
            plugin.getMilestoneManager().setBossBarVisibleFor(player, visible);
        }

        sendPersonalBossBarMessage(player, visible);
        return true;
    }

    private boolean handleGlobalBossBarAction(CommandSender sender, String actionRaw) {
        if (!sender.hasPermission("sotarpayments.admin")) {
            sender.sendMessage(plugin.tr("general.no-permission"));
            return true;
        }

        String action = actionRaw == null || actionRaw.isBlank() ? "status" : actionRaw.toLowerCase(Locale.ROOT);
        if (action.equals("help") || action.equals("?")) {
            sender.sendMessage(plugin.tr("server-milestone.bossbar-global-usage"));
            return true;
        }

        if (action.equals("status")) {
            sendGlobalBossBarStatus(sender);
            return true;
        }

        boolean enabled;
        if (action.equals("toggle")) {
            enabled = !plugin.getMilestoneManager().isBossBarGloballyEnabled();
        } else {
            Boolean parsed = parseToggleValue(action);
            if (parsed == null) {
                sender.sendMessage(plugin.tr("server-milestone.bossbar-global-usage"));
                return true;
            }
            enabled = parsed;
        }

        plugin.getMilestoneManager().setBossBarGloballyEnabled(enabled);
        sender.sendMessage(plugin.tr(enabled
                ? "server-milestone.bossbar-global-enabled"
                : "server-milestone.bossbar-global-disabled"));
        if (enabled && !plugin.getMilestoneManager().isServerMilestonesEnabled()) {
            sender.sendMessage(plugin.tr("server-milestone.disabled"));
        }
        return true;
    }

    private void sendPersonalBossBarStatus(Player player) {
        boolean visible = plugin.getMilestoneManager().isBossBarVisibleFor(player);
        player.sendMessage(plugin.tr("server-milestone.bossbar-status", "status", formatStatus(visible)));
        if (!plugin.getMilestoneManager().isBossBarGloballyEnabled()) {
            player.sendMessage(plugin.tr("server-milestone.bossbar-global-status", "status", formatStatus(false)));
        }
    }

    private void sendGlobalBossBarStatus(CommandSender sender) {
        boolean enabled = plugin.getMilestoneManager().isBossBarGloballyEnabled();
        sender.sendMessage(plugin.tr("server-milestone.bossbar-global-status", "status", formatStatus(enabled)));
        if (enabled && !plugin.getMilestoneManager().isServerMilestonesEnabled()) {
            sender.sendMessage(plugin.tr("server-milestone.disabled"));
        }
    }

    private void sendPersonalBossBarMessage(Player player, boolean visible) {
        player.sendMessage(plugin.tr(visible
                ? "server-milestone.bossbar-enabled"
                : "server-milestone.bossbar-disabled"));
        if (visible && !plugin.getMilestoneManager().isBossBarGloballyEnabled()) {
            player.sendMessage(plugin.tr("server-milestone.bossbar-global-status", "status", formatStatus(false)));
        }
    }

    private String formatStatus(boolean enabled) {
        return plugin.tr(enabled ? "general.enabled" : "general.disabled");
    }

    private Boolean parseToggleValue(String input) {
        if (input == null) return null;
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true", "yes", "bat" -> true;
            case "off", "disable", "disabled", "false", "no", "tat" -> false;
            default -> null;
        };
    }

    private boolean isPersonalMenuSubCommand(String subCommand) {
        String sub = normalize(subCommand);
        return sub.equals("canhan")
                || sub.equals("ca-nhan")
                || sub.equals("ca_nhan")
                || sub.equals("personal")
                || sub.equals("me")
                || sub.equals("mine")
                || sub.equals("cn");
    }

    private boolean isServerMenuSubCommand(String subCommand) {
        String sub = normalize(subCommand);
        return sub.equals("server")
                || sub.equals("sv")
                || sub.equals("global")
                || sub.equals("toanserver")
                || sub.equals("toan-server")
                || sub.equals("toan_server")
                || sub.equals("rewards")
                || sub.equals("reward");
    }

    private boolean isBossBarSubCommand(String subCommand) {
        String sub = normalize(subCommand);
        return sub.equals("bossbar") || sub.equals("bar");
    }

    private boolean isRequirementSubCommand(String subCommand) {
        String sub = normalize(subCommand);
        return sub.equals("dieukien")
                || sub.equals("dieu-kien")
                || sub.equals("dieu_kien")
                || sub.equals("requirement")
                || sub.equals("condition");
    }

    private boolean isHelpSubCommand(String subCommand) {
        String sub = normalize(subCommand);
        return sub.equals("help") || sub.equals("?");
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private String[] copyArgsFrom(String[] args, int fromIndex) {
        if (args == null || fromIndex >= args.length) {
            return new String[0];
        }

        String[] copy = new String[args.length - fromIndex];
        System.arraycopy(args, fromIndex, copy, 0, copy.length);
        return copy;
    }

    private List<String> filterSuggestions(List<String> suggestions, String token) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(s -> s.startsWith(prefix))
                .collect(Collectors.toList());
    }

    private long parseAmount(String input) {
        String normalized = input == null ? "" : input.trim()
                .replace(",", "")
                .replace(".", "")
                .replace("_", "");
        if (normalized.isBlank()) {
            throw new NumberFormatException("blank amount");
        }
        return Long.parseLong(normalized);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.tr("server-milestone.help-header"));
        sender.sendMessage(plugin.tr("server-milestone.help-personal"));
        sender.sendMessage(plugin.tr("server-milestone.help-rewards"));
        sender.sendMessage(plugin.tr("server-milestone.help-bossbar"));
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(plugin.tr("server-milestone.admin-help-header"));
        sender.sendMessage(plugin.tr("server-milestone.help-set"));
        sender.sendMessage(plugin.tr("server-milestone.help-requirement"));
        sender.sendMessage(plugin.tr("server-milestone.help-bossbar-global"));
    }
}
