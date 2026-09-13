package vn.sotarpayments.common.manager;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;

public class AdminGUIManager {
    private final SotarPayments plugin;

    public AdminGUIManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public void openDashboard(Player player) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("admin_dashboard"), 27, plugin.tr("gui.admin-title"));

        ItemStack filler = GUIUtils.emptyPane();
        GUIUtils.fillBorder(inv, filler);
        inv.setItem(0, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(8, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(18, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(26, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));

        long todayRevenue = plugin.getDatabaseManager().getRevenue("today");
        long monthRevenue = plugin.getDatabaseManager().getRevenue("month");
        long transactionCount = plugin.getDatabaseManager().getTransactionCount();

        inv.setItem(10, GUIUtils.item(
                Material.EMERALD,
                plugin.tr("gui.revenue-today"),
                "§8Báo cáo nhanh",
                "§7" + plugin.trPlain("admin-gui.today-revenue-lore"),
                "§a" + GUIUtils.formatMoney(todayRevenue) + " " + plugin.trPlain("general.currency")
        ));

        inv.setItem(12, GUIUtils.item(
                Material.GOLD_INGOT,
                plugin.tr("gui.revenue-month"),
                "§8Báo cáo nhanh",
                "§7" + plugin.trPlain("admin-gui.month-revenue-lore"),
                "§6" + GUIUtils.formatMoney(monthRevenue) + " " + plugin.trPlain("general.currency")
        ));

        inv.setItem(14, GUIUtils.item(
                Material.WRITABLE_BOOK,
                plugin.tr("gui.transaction-stats"),
                "§8Tổng quan vận hành",
                "§7" + plugin.trPlain("admin-gui.promotion-bank") + ": " + (plugin.isPromotionActive(PaymentChannel.BANK) ? "§a" + plugin.trPlain("general.enabled") : "§c" + plugin.trPlain("general.disabled")),
                "§7" + plugin.trPlain("admin-gui.promotion-card") + ": " + (plugin.isPromotionActive(PaymentChannel.CARD) ? "§a" + plugin.trPlain("general.enabled") : "§c" + plugin.trPlain("general.disabled")),
                "§7" + plugin.trPlain("admin-gui.total-transactions") + ": §f" + GUIUtils.formatMoney(transactionCount)
        ));

        inv.setItem(16, GUIUtils.item(
                Material.CHEST,
                plugin.tr("gui.card-config"),
                "§8Cấu hình hiện tại",
                "§7Provider: §f" + plugin.getCardProviderName(),
                "§7" + plugin.trPlain("admin-gui.card-tax-mode") + ": §f" + (plugin.isCardTaxesEnabled() ? plugin.trPlain("general.enabled") : plugin.trPlain("general.disabled")),
                "§7Nguồn tỷ lệ: §f" + plugin.getCardRateManager().getLastFetchInfo()
        ));

        inv.setItem(22, GUIUtils.item(
                Material.PAPER,
                plugin.tr("gui.admin-commands"),
                "§8Lệnh quản trị nhanh",
                "§7/sotar-admin status",
                "§7/sotar-admin reload",
                "§7/sotar-admin napthucong <player> <amount>",
                "§7/sotar-admin reset topnap confirm"
        ));

        player.openInventory(inv);
    }
}
