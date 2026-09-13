package vn.sotarpayments.common.manager;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;
import vn.sotarpayments.napcard.api.CardChargingService;
import vn.sotarpayments.napcard.utils.CardProviderNames;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PaymentGUIManager {
    public static final String MENU_CARD_PROVIDER = "napthe_provider";
    public static final String MENU_CARD_AMOUNT = "napthe_amount";
    public static final String MENU_BANK_AMOUNT = "bank_amount";

    private static final List<Integer> DEFAULT_TELCO_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            20, 21, 22, 23, 24,
            29, 30, 31, 32, 33
    );

    private static final List<Integer> DEFAULT_AMOUNT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    );

    private static final List<String> DEFAULT_TELCOS = List.of(
            "VIETTEL", "MOBIFONE", "VINAPHONE", "ZING", "GARENA", "GATE", "VCOIN", "SCOIN"
    );

    private static final List<Integer> DEFAULT_CARD_AMOUNTS = List.of(
            10000, 20000, 30000, 50000, 100000, 200000, 300000, 500000, 1000000, 2000000, 5000000
    );

    private static final List<Long> DEFAULT_BANK_AMOUNTS = List.of(
            10000L, 20000L, 30000L, 50000L, 100000L, 200000L, 300000L, 500000L, 1000000L, 2000000L, 5000000L
    );

    private final SotarPayments plugin;

    public PaymentGUIManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public void openCardProviderMenu(Player player) {
        final String menu = "card-provider";
        final String root = "menus." + menu;
        GuiConfigManager gui = plugin.getGuiConfigManager();
        CardChargingService service = plugin.getCardChargingService();
        String providerName = service.getProviderDisplayName();
        List<String> telcos = getCardTelcos();
        List<Integer> amounts = getCardAmounts();

        Map<String, String> base = basePlaceholders(player);
        base.put("provider", providerName);
        base.put("min_amount", amounts.isEmpty() ? "-" : plugin.formatMoney(amounts.stream().mapToInt(Integer::intValue).min().orElse(0)));
        base.put("max_amount", amounts.isEmpty() ? "-" : plugin.formatMoney(amounts.stream().mapToInt(Integer::intValue).max().orElse(0)));

        int size = gui.getMenuSize(menu, 45);
        MenuHolder holder = new MenuHolder(MENU_CARD_PROVIDER);
        Inventory inv = Bukkit.createInventory(holder, size,
                gui.renderText(root + ".title", "{lang:gui.card-provider-title}", base));
        renderDecorations(inv, menu, base);

        Set<Integer> occupied = new HashSet<>();
        placeItem(inv, holder, occupied, root + ".items.info", 4,
                Material.NETHER_STAR,
                "{lang:gui.card-provider-info-title}",
                List.of("{lang:gui.card-provider-info-provider}", "{lang:gui.card-provider-info-step}", "", "{lang:gui.card-provider-info-hint}"),
                base, MenuAction.NONE, null, null);
        placeItem(inv, holder, occupied, root + ".items.close", 40,
                Material.BARRIER,
                "{lang:gui.close-name}",
                List.of("{lang:gui.close-lore}"),
                base, MenuAction.CLOSE, null, null);

        String itemPath = root + ".dynamic.providers";
        if (gui.getBoolean(itemPath + ".enabled", true)) {
            List<Integer> slots = gui.getSlots(itemPath + ".slots", DEFAULT_TELCO_SLOTS, size);
            int valueIndex = 0;
            for (int slot : slots) {
                if (valueIndex >= telcos.size()) break;
                if (occupied.contains(slot)) {
                    gui.warnLayout(menu + ".slot." + slot,
                            "slot " + slot + " của providers trùng static item và đã bị bỏ qua.");
                    continue;
                }

                String telco = normalizeTelco(telcos.get(valueIndex++));
                Map<String, String> placeholders = new LinkedHashMap<>(base);
                placeholders.put("telco", gui.getValueDisplayName(itemPath, telco, formatTelcoName(telco)));
                ItemStack item = gui.createItem(itemPath, telco, getTelcoMaterial(telco),
                        "{lang:gui.card-telco-name}",
                        List.of("{lang:gui.card-telco-provider}", "&7Mệnh giá: &a{min_amount} - {max_amount} VNĐ", "", "{lang:gui.click-select}"),
                        placeholders);
                inv.setItem(slot, item);
                occupied.add(slot);
                holder.bindAction(slot, gui.getAction(itemPath + ".action", MenuAction.SELECT_CARD_PROVIDER), telco);
            }
            warnUnrendered(menu, "providers", telcos.size(), valueIndex);
        }
        player.openInventory(inv);
    }

    public void openCardAmountMenu(Player player, String telco) {
        final String menu = "card-amount";
        final String root = "menus." + menu;
        GuiConfigManager gui = plugin.getGuiConfigManager();
        String normalizedTelco = normalizeTelco(telco);
        CardChargingService service = plugin.getCardChargingService();
        String providerName = service.getProviderDisplayName();
        List<Integer> amounts = getCardAmounts();
        boolean cardPromotionActive = plugin.isPromotionActive(PaymentChannel.CARD);
        int cardPromotionPercent = plugin.getPromotionPercent(PaymentChannel.CARD);

        Map<String, String> base = basePlaceholders(player);
        base.put("provider", providerName);
        base.put("telco", gui.getValueDisplayName(root + ".items.info", normalizedTelco, formatTelcoName(normalizedTelco)));
        base.put("bonus", String.valueOf(cardPromotionPercent));

        int size = gui.getMenuSize(menu, 54);
        MenuHolder holder = new MenuHolder(MENU_CARD_AMOUNT);
        holder.setData("telco", normalizedTelco);
        Inventory inv = Bukkit.createInventory(holder, size,
                gui.renderText(root + ".title", "{lang:gui.card-amount-title}", base));
        renderDecorations(inv, menu, base);

        Set<Integer> occupied = new HashSet<>();
        placeItem(inv, holder, occupied, root + ".items.info", 4,
                getTelcoMaterial(normalizedTelco),
                "{lang:gui.card-amount-info-title}",
                List.of("{lang:gui.card-provider-info-provider}", "{lang:gui.card-amount-info-step}", "", "{lang:gui.card-amount-info-hint}"),
                base, MenuAction.NONE, normalizedTelco, null);
        placeItem(inv, holder, occupied, root + ".items.back", 45,
                Material.ARROW, "{lang:gui.back-name}", List.of("{lang:gui.back-lore}"),
                base, MenuAction.OPEN_CARD_PROVIDER, null, null);
        placeItem(inv, holder, occupied, root + ".items.help", 49,
                Material.BOOK, "{lang:gui.card-help-title}",
                List.of("{lang:gui.card-help-line-1}", "{lang:gui.card-help-line-2}", "{lang:gui.card-help-line-3}"),
                base, MenuAction.NONE, null, null);
        placeItem(inv, holder, occupied, root + ".items.close", 53,
                Material.BARRIER, "{lang:gui.close-name}", List.of("{lang:gui.close-lore}"),
                base, MenuAction.CLOSE, null, null);

        String itemPath = root + ".dynamic.amounts";
        if (gui.getBoolean(itemPath + ".enabled", true)) {
            List<Integer> slots = gui.getSlots(itemPath + ".slots", DEFAULT_AMOUNT_SLOTS, size);
            int valueIndex = 0;
            for (int slot : slots) {
                if (valueIndex >= amounts.size()) break;
                if (occupied.contains(slot)) {
                    gui.warnLayout(menu + ".slot." + slot,
                            "slot " + slot + " của amounts trùng static item và đã bị bỏ qua.");
                    continue;
                }

                int amount = amounts.get(valueIndex++);
                double discount = plugin.isCardTaxesEnabled()
                        ? plugin.getCardRateManager().getDiscountRate(normalizedTelco, amount)
                        : 0.0D;
                int netAmount = (int) (amount * (1.0D - (discount / 100.0D)));
                int basePoints = netAmount / plugin.getCardRewardRatio();
                int points = plugin.applyPromotionToPoints(basePoints, PaymentChannel.CARD);

                Map<String, String> placeholders = new LinkedHashMap<>(base);
                placeholders.put("raw_amount", String.valueOf(amount));
                placeholders.put("amount", plugin.formatMoney(amount));
                placeholders.put("tax", formatPercent(discount));
                placeholders.put("net_amount", plugin.formatMoney(netAmount));
                placeholders.put("points", plugin.formatMoney(points));
                placeholders.put("tax_line", plugin.isCardTaxesEnabled()
                        ? plugin.tr("gui.card-amount-lore-tax", "tax", formatPercent(discount)) : "");
                placeholders.put("receive_line", plugin.tr("gui.card-amount-lore-receive", "amount", plugin.formatMoney(netAmount)));
                placeholders.put("promotion_line", cardPromotionActive
                        ? plugin.tr("gui.card-amount-lore-promotion", "bonus", cardPromotionPercent) : "");

                ItemStack item = gui.createItem(itemPath, Material.GOLD_INGOT,
                        "{lang:gui.amount-item-name}",
                        List.of("{lang:gui.card-amount-lore-amount}", "{tax_line}",
                                "{receive_line}", "{lang:gui.card-amount-lore-points}",
                                "{promotion_line}", "", "{lang:gui.click-select}"),
                        placeholders);
                inv.setItem(slot, item);
                occupied.add(slot);
                holder.bindAction(slot, gui.getAction(itemPath + ".action", MenuAction.START_CARD_PAYMENT), String.valueOf(amount));
            }
            warnUnrendered(menu, "amounts", amounts.size(), valueIndex);
        }
        player.openInventory(inv);
    }

    public void openBankAmountMenu(Player player) {
        final String menu = "bank-amount";
        final String root = "menus." + menu;
        GuiConfigManager gui = plugin.getGuiConfigManager();
        List<Long> amounts = getBankAmounts();
        long minAmount = getBankMinAmount();
        long maxAmount = plugin.config().getLong("napbank.max-amount", 0L);
        boolean bankPromotionActive = plugin.isPromotionActive(PaymentChannel.BANK);
        int bankPromotionPercent = plugin.getPromotionPercent(PaymentChannel.BANK);

        Map<String, String> base = basePlaceholders(player);
        base.put("provider", getBankProviderDisplayName());
        base.put("min_amount", plugin.formatMoney(minAmount));
        base.put("max_amount", maxAmount > 0 ? plugin.formatMoney(maxAmount) : plugin.trPlain("general.no-limit"));
        base.put("min_line", plugin.tr("gui.bank-info-min", "amount", plugin.formatMoney(minAmount)));
        base.put("max_line", plugin.tr("gui.bank-info-max", "amount",
                maxAmount > 0 ? plugin.formatMoney(maxAmount) : plugin.trPlain("general.no-limit")));
        base.put("bonus", String.valueOf(bankPromotionPercent));

        int size = gui.getMenuSize(menu, 54);
        MenuHolder holder = new MenuHolder(MENU_BANK_AMOUNT);
        Inventory inv = Bukkit.createInventory(holder, size,
                gui.renderText(root + ".title", "{lang:gui.bank-amount-title}", base));
        renderDecorations(inv, menu, base);

        Set<Integer> occupied = new HashSet<>();
        placeItem(inv, holder, occupied, root + ".items.info", 4,
                Material.EMERALD, "{lang:gui.bank-info-title}",
                List.of("{lang:gui.bank-info-provider}", "{min_line}", "{max_line}", "", "{lang:gui.bank-info-hint}"),
                base, MenuAction.NONE, null, null);
        placeItem(inv, holder, occupied, root + ".items.help", 49,
                Material.MAP, "{lang:gui.bank-help-title}",
                List.of("{lang:gui.bank-help-line-1}", "{lang:gui.bank-help-line-2}", "{lang:gui.bank-help-line-3}"),
                base, MenuAction.NONE, null, null);
        placeItem(inv, holder, occupied, root + ".items.close", 53,
                Material.BARRIER, "{lang:gui.close-name}", List.of("{lang:gui.close-lore}"),
                base, MenuAction.CLOSE, null, null);

        if (amounts.isEmpty()) {
            placeItem(inv, holder, occupied, root + ".items.no-data", 22,
                    Material.BARRIER, "{lang:gui.no-data}", List.of("{lang:gui.bank-no-amounts}"),
                    base, MenuAction.NONE, null, null);
        } else {
            String itemPath = root + ".dynamic.amounts";
            if (gui.getBoolean(itemPath + ".enabled", true)) {
                List<Integer> slots = gui.getSlots(itemPath + ".slots", DEFAULT_AMOUNT_SLOTS, size);
                int valueIndex = 0;
                for (int slot : slots) {
                    if (valueIndex >= amounts.size()) break;
                    if (occupied.contains(slot)) {
                        gui.warnLayout(menu + ".slot." + slot,
                                "slot " + slot + " của amounts trùng static item và đã bị bỏ qua.");
                        continue;
                    }

                    long amount = amounts.get(valueIndex++);
                    int points = plugin.calculateFinalPoints(amount, PaymentChannel.BANK);
                    Map<String, String> placeholders = new LinkedHashMap<>(base);
                    placeholders.put("raw_amount", String.valueOf(amount));
                    placeholders.put("amount", plugin.formatMoney(amount));
                    placeholders.put("points", plugin.formatMoney(points));
                    placeholders.put("promotion_line", bankPromotionActive
                            ? plugin.tr("gui.bank-amount-lore-promotion", "bonus", bankPromotionPercent) : "");

                    ItemStack item = gui.createItem(itemPath, Material.EMERALD,
                            "{lang:gui.amount-item-name}",
                            List.of("{lang:gui.bank-amount-lore-amount}", "{lang:gui.bank-amount-lore-points}",
                                    "{promotion_line}", "", "{lang:gui.click-select}"),
                            placeholders);
                    inv.setItem(slot, item);
                    occupied.add(slot);
                    holder.bindAction(slot, gui.getAction(itemPath + ".action", MenuAction.START_BANK_PAYMENT), String.valueOf(amount));
                }
                warnUnrendered(menu, "amounts", amounts.size(), valueIndex);
            }
        }
        player.openInventory(inv);
    }

    public List<String> getCardTelcos() {
        CardChargingService service = plugin.getCardChargingService();
        String provider = service.getProvider();
        List<String> configured = getConfiguredStringList("napthe.gui.providers." + provider + ".telcos");
        if (configured.isEmpty()) configured = getConfiguredStringList("napthe.gui.telcos");
        if (configured.isEmpty()) configured = DEFAULT_TELCOS;

        Set<String> unique = new LinkedHashSet<>();
        for (String telco : configured) {
            String normalized = normalizeTelco(telco);
            if (!normalized.isBlank()) unique.add(normalized);
        }
        unique.add("VIETTEL");
        unique.add("MOBIFONE");
        return new ArrayList<>(unique);
    }

    public List<Integer> getCardAmounts() {
        return getConfiguredIntList("napthe.gui.amounts", DEFAULT_CARD_AMOUNTS);
    }

    public List<Long> getBankAmounts() {
        long minAmount = getBankMinAmount();
        long maxAmount = plugin.config().getLong("napbank.max-amount", 0L);
        List<Long> configured = getConfiguredLongList("napbank.gui.amounts", DEFAULT_BANK_AMOUNTS);
        List<Long> filtered = new ArrayList<>();
        for (long amount : configured) {
            if (amount < minAmount) continue;
            if (maxAmount > 0 && amount > maxAmount) continue;
            filtered.add(amount);
        }
        return filtered;
    }

    public boolean isCardTelcoAllowed(String telco) {
        return getCardTelcos().contains(normalizeTelco(telco));
    }

    public boolean isCardAmountAllowed(int amount) {
        return getCardAmounts().contains(amount);
    }

    private void renderDecorations(Inventory inv, String menu, Map<String, String> placeholders) {
        GuiConfigManager gui = plugin.getGuiConfigManager();
        String root = "menus." + menu + ".decorations";
        if (gui.getBoolean(root + ".border.enabled", true)) {
            ItemStack border = gui.createItem(root + ".border", Material.BLACK_STAINED_GLASS_PANE,
                    " ", List.of(), placeholders);
            GUIUtils.fillBorder(inv, border);
        }
        if (gui.getBoolean(root + ".accents.enabled", true)) {
            List<Integer> cornerDefaults = List.of(0, 8, inv.getSize() - 9, inv.getSize() - 1);
            List<Integer> slots = gui.getSlots(root + ".accents.slots", cornerDefaults, inv.getSize());
            ItemStack accent = gui.createItem(root + ".accents", Material.CYAN_STAINED_GLASS_PANE,
                    " ", List.of(), placeholders);
            for (int slot : slots) inv.setItem(slot, accent);
        }
    }

    private void placeItem(Inventory inv,
                           MenuHolder holder,
                           Set<Integer> occupied,
                           String itemPath,
                           int fallbackSlot,
                           Material fallbackMaterial,
                           String fallbackName,
                           List<String> fallbackLore,
                           Map<String, String> placeholders,
                           MenuAction fallbackAction,
                           String valueMaterialKey,
                           String payload) {
        GuiConfigManager gui = plugin.getGuiConfigManager();
        if (!gui.getBoolean(itemPath + ".enabled", true)) return;
        int slot = gui.getSlot(itemPath + ".slot", fallbackSlot, inv.getSize());
        if (slot < 0) return;
        if (!occupied.add(slot)) {
            gui.warnLayout(itemPath + ".slot." + slot,
                    "slot " + slot + " bị trùng static item; item sau đã bị bỏ qua.");
            return;
        }
        ItemStack item = valueMaterialKey == null
                ? gui.createItem(itemPath, fallbackMaterial, fallbackName, fallbackLore, placeholders)
                : gui.createItem(itemPath, valueMaterialKey, fallbackMaterial, fallbackName, fallbackLore, placeholders);
        inv.setItem(slot, item);
        holder.bindAction(slot, gui.getAction(itemPath + ".action", fallbackAction), payload);
    }

    private Map<String, String> basePlaceholders(Player player) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", player.getName());
        return placeholders;
    }

    private void warnUnrendered(String menu, String type, int totalValues, int renderedValues) {
        if (renderedValues >= totalValues) return;
        plugin.getGuiConfigManager().warnLayout(menu + "." + type + ".capacity",
                "chỉ hiển thị " + renderedValues + "/" + totalValues + " mục vì không đủ slot hợp lệ.");
    }

    private Material getTelcoMaterial(String telco) {
        return switch (normalizeTelco(telco)) {
            case "VIETTEL" -> Material.RED_CONCRETE;
            case "MOBIFONE" -> Material.BLUE_CONCRETE;
            case "VINAPHONE" -> Material.GREEN_CONCRETE;
            case "VIETNAMOBILE", "VNMOBI" -> Material.YELLOW_CONCRETE;
            case "ZING" -> Material.ORANGE_CONCRETE;
            case "GARENA" -> Material.FIRE_CHARGE;
            case "GATE" -> Material.GOLD_BLOCK;
            case "VCOIN" -> Material.PURPLE_CONCRETE;
            case "SCOIN" -> Material.AMETHYST_SHARD;
            default -> Material.PAPER;
        };
    }

    private String formatTelcoName(String telco) {
        return CardProviderNames.displayName(telco);
    }

    private String normalizeTelco(String telco) {
        return CardProviderNames.normalize(telco);
    }

    private String formatPercent(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((int) Math.rint(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private long getBankMinAmount() {
        return Math.max(1L, plugin.config().getLong("napbank.min-amount", 2000L));
    }

    private String getBankProviderDisplayName() {
        String provider = plugin.getBankProviderName();
        return provider.equalsIgnoreCase("sepay") ? "SePay" : "PayOS";
    }

    private List<String> getConfiguredStringList(String path) {
        List<String> output = new ArrayList<>();
        List<?> raw = plugin.config().getList(path);
        if (raw == null) return output;
        for (Object value : raw) {
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) output.add(text);
        }
        return output;
    }

    private List<Integer> getConfiguredIntList(String path, List<Integer> fallback) {
        Set<Integer> unique = new LinkedHashSet<>();
        List<?> raw = plugin.config().getList(path);
        if (raw != null) {
            for (Object value : raw) {
                Integer parsed = parseInt(value);
                if (parsed != null && parsed > 0) unique.add(parsed);
            }
        }
        if (unique.isEmpty()) unique.addAll(fallback);
        return new ArrayList<>(unique);
    }

    private List<Long> getConfiguredLongList(String path, List<Long> fallback) {
        Set<Long> unique = new LinkedHashSet<>();
        List<?> raw = plugin.config().getList(path);
        if (raw != null) {
            for (Object value : raw) {
                Long parsed = parseLong(value);
                if (parsed != null && parsed > 0) unique.add(parsed);
            }
        }
        if (unique.isEmpty()) unique.addAll(fallback);
        return new ArrayList<>(unique);
    }

    private Integer parseInt(Object value) {
        Long parsed = parseLong(value);
        if (parsed == null || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) return null;
        return parsed.intValue();
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException ignored) {
                return null;
            }
        }
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
