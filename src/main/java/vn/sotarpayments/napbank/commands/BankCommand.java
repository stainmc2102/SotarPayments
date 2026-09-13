package vn.sotarpayments.napbank.commands;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napbank.manager.BankPaymentManager;
import vn.sotarpayments.napbank.renderer.PaymentRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

public class BankCommand implements CommandExecutor, TabCompleter {
    private final SotarPayments plugin;
    private final BankPaymentManager manager;

    public BankCommand(SotarPayments plugin) {
        this.plugin = plugin;
        this.manager = plugin.getBankPaymentManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }
        if (!plugin.ensureFeatureAvailable(player)) return true;

        if (args.length == 0 || (args.length > 0 && args[0].equalsIgnoreCase("gui"))) {
            plugin.getModernPaymentInterfaceManager().openBankAmount(player);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            manager.cancelPolling(player);
            player.sendMessage(plugin.tr("bank.cancel-success"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(plugin.tr("bank.usage"));
            return true;
        }

        long amountRequested;
        try {
            amountRequested = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.tr("bank.invalid-amount"));
            return true;
        }

        int minAmount = Math.max(1, plugin.config().getInt("napbank.min-amount", 2000));
        long maxAmount = plugin.config().getLong("napbank.max-amount", 0L);
        if (amountRequested < minAmount) {
            player.sendMessage(plugin.tr("bank.min-amount", "amount", plugin.formatMoney(minAmount)));
            return true;
        }
        if (maxAmount > 0 && amountRequested > maxAmount) {
            player.sendMessage(plugin.tr("bank.max-amount", "amount", plugin.formatMoney(maxAmount)));
            return true;
        }

        player.sendMessage(plugin.tr("bank.creating"));

        manager.createTransaction(player, amountRequested, new BankPaymentManager.TransactionCallback() {
            @Override
            public void onSuccess(JsonObject data, long orderCode) {
                plugin.getPlatformScheduler().runPlayer(player, () -> openPayment(player, data, orderCode));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("gui");
            suggestions.add("cancel");
            for (Long amount : plugin.getPaymentGuiManager().getBankAmounts()) {
                suggestions.add(String.valueOf(amount));
            }
            String typed = args[0].toLowerCase(Locale.ROOT);
            return suggestions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(typed))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void openPayment(Player player, JsonObject data, long orderCode) {
        String bin = getJsonString(data, "bin", "");
        String bankName = getJsonString(data, "bankName", "");
        if (bankName.isBlank()) bankName = getBankName(bin);
        String accountNumber = getJsonString(data, "accountNumber", "");
        String accountName = getJsonString(data, "accountName", "");
        long amount = data.has("amount") && !data.get("amount").isJsonNull() ? data.get("amount").getAsLong() : 0L;
        String description = getJsonString(data, "description", "");
        String qrUrl = getJsonString(data, "qrCode", getJsonString(data, "qrImageUrl", ""));

        player.sendMessage("");
        player.sendMessage(plugin.tr("bank.transfer-header"));
        player.sendMessage(plugin.tr("bank.transfer-separator"));
        sendSuggestLine(player, plugin.tr("bank.label-bank"), bankName, bankName);
        sendSuggestLine(player, plugin.tr("bank.label-account-number"), accountNumber, accountNumber);
        sendSuggestLine(player, plugin.tr("bank.label-account-name"), accountName, accountName);
        sendSuggestLine(player, plugin.tr("bank.label-amount"), plugin.formatMoney(amount) + " " + plugin.trPlain("general.currency"), String.valueOf(amount));
        sendSuggestLine(player, plugin.tr("bank.label-content"), "§e" + description, description);
        player.sendMessage(plugin.tr("bank.transfer-separator"));

        TextComponent cancelButton = new TextComponent(plugin.tr("bank.cancel-button"));
        cancelButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bank cancel"));
        cancelButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(plugin.tr("bank.cancel-hover")).create()));

        player.sendMessage(plugin.tr("bank.qr-ready"));
        player.sendMessage(plugin.tr("bank.qr-auto-clean"));
        player.spigot().sendMessage(cancelButton);
        player.sendMessage("");

        MapView view = Bukkit.createMap(player.getWorld());
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(new PaymentRenderer(qrUrl));

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName(plugin.tr("bank.map-name", "ordercode", orderCode, "bank", bankName));
            List<String> lore = plugin.trList("bank.map-lore",
                    "bank", bankName,
                    "account_number", accountNumber,
                    "account_name", accountName,
                    "amount", plugin.formatMoney(amount),
                    "description", description);
            meta.setLore(lore);
            map.setItemMeta(meta);
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(map);
        if (!leftovers.isEmpty()) {
            for (ItemStack item : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.sendMessage(plugin.tr("bank.inventory-full"));
        }

        manager.startPolling(player, orderCode, amount, view.getId());
    }

    private void sendSuggestLine(Player player, String label, String displayValue, String suggestValue) {
        TextComponent line = new TextComponent(" §f" + label + ": §e" + displayValue + " §7[click]");
        line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestValue));
        line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(plugin.tr("general.click-copy")).create()));
        player.spigot().sendMessage(line);
    }

    private String getJsonString(JsonObject data, String key, String fallback) {
        if (data == null || !data.has(key) || data.get(key).isJsonNull()) return fallback;
        try {
            return data.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String getBankName(String bin) {
        return switch (bin) {
            case "970436" -> "Vietcombank";
            case "970415" -> "VietinBank";
            case "970418" -> "BIDV";
            case "970405" -> "Agribank";
            case "970422" -> "MB Bank";
            case "970407" -> "Techcombank";
            case "970432" -> "VPBank";
            case "970423" -> "TPBank";
            default -> plugin.tr("bank.fallback-bank-name", "bin", bin);
        };
    }
}
