package vn.sotarpayments.napbank.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.manager.DatabaseManager.TransactionRecord;
import vn.sotarpayments.common.manager.GUIUtils;
import vn.sotarpayments.common.manager.MenuHolder;
import vn.sotarpayments.common.model.PaymentChannel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class HistoryCommand implements CommandExecutor {
    private static final int HISTORY_LIMIT = 45;
    private static final int[] HISTORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final SotarPayments plugin;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public HistoryCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }

        if (args.length > 0 && player.hasPermission("sotarpayments.admin")) {
            player.sendMessage(plugin.tr("history.admin-use-admin-command"));
            return true;
        }

        openHistory(player, player.getName(), false);
        return true;
    }

    public void openHistory(Player viewer, String targetName, boolean adminView) {
        String safeTarget = normalizeTarget(targetName, viewer.getName());
        String title = adminView
                ? plugin.tr("gui.history-admin-title", "player", safeTarget)
                : plugin.tr("gui.history-title");
        Inventory inv = Bukkit.createInventory(new MenuHolder("history"), 54, title);
        GUIUtils.fillBorder(inv, GUIUtils.emptyPane());
        paintAccentCorners(inv);

        List<TransactionRecord> history = plugin.getDatabaseManager().getPlayerHistoryDetailed(safeTarget, HISTORY_LIMIT);
        long totalDonated = plugin.getDatabaseManager().getTotalDonatedIgnoreCase(safeTarget);
        long totalTransactions = plugin.getDatabaseManager().getPlayerTransactionCount(safeTarget);

        if (history.isEmpty()) {
            inv.setItem(22, GUIUtils.item(
                    Material.PAPER,
                    plugin.tr("history.empty"),
                    adminView
                            ? plugin.tr("history.admin-empty-lore", "player", safeTarget)
                            : plugin.tr("history.empty-lore")
            ));
            inv.setItem(49, buildSummaryItem(safeTarget, totalDonated, totalTransactions, history.size(), adminView));
            viewer.openInventory(inv);
            return;
        }

        for (int i = 0; i < history.size() && i < HISTORY_SLOTS.length; i++) {
            TransactionRecord record = history.get(i);
            List<String> lore = new ArrayList<>();
            if (adminView) {
                lore.add(plugin.tr("history.player", "player", record.player()));
            }
            lore.add(plugin.tr("history.type", "type", getChannelName(record.channel())));
            lore.add(plugin.tr("history.time", "time", formatTime(record.time())));
            lore.add(plugin.tr("history.amount", "amount", GUIUtils.formatMoney(record.amount())));
            if (!isBlank(record.provider())) {
                lore.add(plugin.tr("history.provider", "provider", record.provider()));
            }
            if (!isBlank(record.detail())) {
                lore.add(plugin.tr("history.detail", "detail", record.detail()));
            }
            lore.add(plugin.tr("history.transaction-id", "id", record.id()));

            inv.setItem(HISTORY_SLOTS[i], GUIUtils.item(
                    getChannelMaterial(record.channel()),
                    plugin.tr("history.transaction", "index", i + 1),
                    lore.toArray(new String[0])
            ));
        }

        inv.setItem(49, buildSummaryItem(safeTarget, totalDonated, totalTransactions,
                Math.min(history.size(), HISTORY_SLOTS.length), adminView));

        viewer.openInventory(inv);
    }

    public void sendHistoryText(CommandSender sender, String targetName) {
        String safeTarget = normalizeTarget(targetName, targetName);
        List<TransactionRecord> history = plugin.getDatabaseManager().getPlayerHistoryDetailed(safeTarget, HISTORY_LIMIT);
        long totalDonated = plugin.getDatabaseManager().getTotalDonatedIgnoreCase(safeTarget);
        long totalTransactions = plugin.getDatabaseManager().getPlayerTransactionCount(safeTarget);

        sender.sendMessage(plugin.tr("history.admin-text-header", "player", safeTarget));
        sender.sendMessage(plugin.tr("history.total", "amount", GUIUtils.formatMoney(totalDonated)));
        sender.sendMessage(plugin.tr("history.total-count", "count", totalTransactions));
        if (history.isEmpty()) {
            sender.sendMessage(plugin.tr("history.admin-empty-lore", "player", safeTarget));
            return;
        }

        int index = 1;
        for (TransactionRecord record : history) {
            sender.sendMessage(plugin.tr(
                    "history.admin-text-line",
                    "index", index,
                    "time", formatTime(record.time()),
                    "type", getChannelName(record.channel()),
                    "amount", GUIUtils.formatMoney(record.amount()),
                    "provider", isBlank(record.provider()) ? "-" : record.provider(),
                    "detail", isBlank(record.detail()) ? "-" : record.detail()
            ));
            index++;
        }
    }

    private org.bukkit.inventory.ItemStack buildSummaryItem(String targetName, long totalDonated, long totalTransactions,
                                                             int displayedTransactions, boolean adminView) {
        List<String> lore = new ArrayList<>();
        if (adminView) {
            lore.add(plugin.tr("history.player", "player", targetName));
        }
        lore.add(plugin.tr("history.total", "amount", GUIUtils.formatMoney(totalDonated)));
        lore.add(plugin.tr("history.total-count", "count", totalTransactions));
        lore.add(plugin.tr("history.count", "count", displayedTransactions));
        lore.add(plugin.tr("history.limit-note", "limit", HISTORY_LIMIT));
        return GUIUtils.item(
                Material.CLOCK,
                plugin.tr("history.summary"),
                lore.toArray(new String[0])
        );
    }

    private void paintAccentCorners(Inventory inv) {
        inv.setItem(0, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(8, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(inv.getSize() - 9, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(inv.getSize() - 1, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
    }

    private String normalizeTarget(String targetName, String fallback) {
        if (targetName == null || targetName.isBlank()) return fallback == null ? "" : fallback;
        return targetName.trim();
    }

    private String formatTime(long millis) {
        return dateFormat.format(new Date(millis));
    }

    private String getChannelName(PaymentChannel channel) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        return plugin.trPlain(safeChannel.languageKey());
    }

    private Material getChannelMaterial(PaymentChannel channel) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        return switch (safeChannel) {
            case BANK -> Material.EMERALD;
            case CARD -> Material.MAP;
            case MANUAL -> Material.WRITABLE_BOOK;
            case LEGACY -> Material.PAPER;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
