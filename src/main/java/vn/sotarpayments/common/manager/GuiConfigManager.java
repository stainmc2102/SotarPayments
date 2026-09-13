package vn.sotarpayments.common.manager;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import vn.sotarpayments.SotarPayments;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the user-facing inventory layout from gui.yml. Every lookup falls back
 * to the bundled gui.yml and finally to the Java fallback supplied by the
 * caller, so a partial or malformed customization cannot crash a menu.
 */
public final class GuiConfigManager {
    private static final Pattern LANGUAGE_TOKEN = Pattern.compile("\\{lang:([^{}]+)}");
    private static final Pattern HEX_COLOR = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final List<ItemFlag> DEFAULT_FLAGS = List.of(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP
    );

    private final SotarPayments plugin;
    private final File guiFile;
    private final Set<String> reportedWarnings = ConcurrentHashMap.newKeySet();
    private volatile YamlConfiguration active = new YamlConfiguration();
    private volatile YamlConfiguration bundled = new YamlConfiguration();

    public GuiConfigManager(SotarPayments plugin) {
        this.plugin = plugin;
        this.guiFile = new File(plugin.getDataFolder(), "gui.yml");
        reload();
    }

    public void reload() {
        ensureDefaultFile();
        this.bundled = loadBundled();
        this.active = YamlConfiguration.loadConfiguration(guiFile);
        this.reportedWarnings.clear();
    }

    public int getMenuSize(String menu, int javaFallback) {
        String path = "menus." + menu + ".size";
        Integer value = readValidatedInt(path, candidate -> candidate >= 9 && candidate <= 54 && candidate % 9 == 0);
        if (value != null) return value;
        if (javaFallback >= 9 && javaFallback <= 54 && javaFallback % 9 == 0) return javaFallback;
        return 54;
    }

    public boolean getBoolean(String path, boolean javaFallback) {
        Boolean activeValue = parseBoolean(active, path, true);
        if (activeValue != null) return activeValue;
        Boolean bundledValue = parseBoolean(bundled, path, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    public int getSlot(String path, int javaFallback, int inventorySize) {
        Integer activeValue = parseInteger(active, path, true);
        if (isSlot(activeValue, inventorySize)) return activeValue;
        if (activeValue != null) warn(path, "slot " + activeValue + " nằm ngoài GUI size " + inventorySize + ".");

        Integer bundledValue = parseInteger(bundled, path, false);
        if (isSlot(bundledValue, inventorySize)) return bundledValue;
        if (isSlot(javaFallback, inventorySize)) return javaFallback;
        warn(path, "không có slot hợp lệ; item này sẽ được bỏ qua.");
        return -1;
    }

    public List<Integer> getSlots(String path, List<Integer> javaFallback, int inventorySize) {
        List<Integer> activeValues = parseSlots(active, path, inventorySize, true);
        if (activeValues != null) return activeValues;
        List<Integer> bundledValues = parseSlots(bundled, path, inventorySize, false);
        if (bundledValues != null) return bundledValues;

        LinkedHashSet<Integer> safe = new LinkedHashSet<>();
        for (Integer slot : javaFallback) {
            if (isSlot(slot, inventorySize)) safe.add(slot);
        }
        return new ArrayList<>(safe);
    }

    public String getString(String path, String javaFallback) {
        String activeValue = parseString(active, path, true);
        if (activeValue != null) return activeValue;
        String bundledValue = parseString(bundled, path, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    public String getValueDisplayName(String itemPath, String value, String javaFallback) {
        if (value == null || value.isBlank()) return javaFallback;
        String valuePath = itemPath + ".display-by-value." + value;
        String activeValue = parseString(active, valuePath, true);
        if (activeValue != null) return activeValue;
        String bundledValue = parseString(bundled, valuePath, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    public String renderText(String path, String javaFallback, Map<String, String> placeholders) {
        return render(getString(path, javaFallback), placeholders);
    }

    public List<String> renderLore(String path, List<String> javaFallback, Map<String, String> placeholders) {
        List<String> source = getStringList(path, javaFallback);
        List<String> output = new ArrayList<>(source.size());
        for (String raw : source) {
            String rendered = render(raw, placeholders);
            // An intentional empty YAML line is retained; an optional placeholder
            // such as {tax_line} is removed when it resolves to an empty value.
            if (!raw.isEmpty() && rendered.isEmpty()) continue;
            output.add(rendered);
        }
        return output;
    }

    public Material getMaterial(String path, Material javaFallback) {
        Material activeValue = parseMaterial(active, path, true);
        if (activeValue != null) return activeValue;
        Material bundledValue = parseMaterial(bundled, path, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    public Material getValueMaterial(String itemPath, String value, Material javaFallback) {
        Material base = getMaterial(itemPath + ".material", javaFallback);
        if (value == null || value.isBlank()) return base;
        String valuePath = itemPath + ".material-by-value." + value;
        Material activeValue = parseMaterial(active, valuePath, true);
        if (activeValue != null) return activeValue;
        Material bundledValue = parseMaterial(bundled, valuePath, false);
        return bundledValue == null ? base : bundledValue;
    }

    public MenuAction getAction(String path, MenuAction javaFallback) {
        MenuAction activeValue = parseAction(active, path, true);
        if (activeValue != null) return activeValue;
        MenuAction bundledValue = parseAction(bundled, path, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    public ItemStack createItem(String itemPath,
                                Material javaMaterial,
                                String javaName,
                                List<String> javaLore,
                                Map<String, String> placeholders) {
        return createItem(itemPath, null, javaMaterial, javaName, javaLore, placeholders);
    }

    public ItemStack createItem(String itemPath,
                                String valueMaterialKey,
                                Material javaMaterial,
                                String javaName,
                                List<String> javaLore,
                                Map<String, String> placeholders) {
        Material material = valueMaterialKey == null
                ? getMaterial(itemPath + ".material", javaMaterial)
                : getValueMaterial(itemPath, valueMaterialKey, javaMaterial);
        String name = renderText(itemPath + ".name", javaName, placeholders);
        List<String> lore = renderLore(itemPath + ".lore", javaLore, placeholders);
        int amount = getItemAmount(itemPath);
        Integer customModelData = getCustomModelData(itemPath);
        boolean glow = getItemBoolean(itemPath, "glow", false);
        Set<ItemFlag> flags = getItemFlags(itemPath, DEFAULT_FLAGS);

        // Support skull/player head items with head-owner property
        if (material == Material.PLAYER_HEAD || material == Material.PLAYER_WALL_HEAD) {
            String headOwner = getString(itemPath + ".head-owner", "");
            if (headOwner != null && !headOwner.isBlank()) {
                String renderedOwner = renderText(itemPath + ".head-owner", "", placeholders);
                if (!renderedOwner.isBlank()) {
                    return GUIUtils.skullItem(renderedOwner, name, lore, amount, customModelData, glow, flags);
                }
            }
        }

        return GUIUtils.item(material, amount, name, lore, customModelData, glow, flags);
    }

    public void warnLayout(String key, String message) {
        warn("layout." + key, message);
    }

    private String render(String template, Map<String, String> placeholders) {
        String result = template == null ? "" : template;
        Matcher languageMatcher = LANGUAGE_TOKEN.matcher(result);
        StringBuffer languageBuffer = new StringBuffer();
        while (languageMatcher.find()) {
            String translated = plugin.tr(languageMatcher.group(1).trim());
            languageMatcher.appendReplacement(languageBuffer, Matcher.quoteReplacement(translated));
        }
        languageMatcher.appendTail(languageBuffer);
        result = languageBuffer.toString();

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String replacement = entry.getValue() == null ? "" : entry.getValue();
                result = result.replace("{" + entry.getKey() + "}", replacement);
            }
        }
        return colorize(result);
    }

    private String colorize(String input) {
        Matcher matcher = HEX_COLOR.matcher(input == null ? "" : input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char character : hex.toCharArray()) {
                replacement.append('§').append(character);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private List<String> getStringList(String path, List<String> javaFallback) {
        List<String> activeValue = parseStringList(active, path, true);
        if (activeValue != null) return activeValue;
        List<String> bundledValue = parseStringList(bundled, path, false);
        return bundledValue == null ? new ArrayList<>(javaFallback) : bundledValue;
    }

    private Integer getCustomModelData(String itemPath) {
        Integer activeValue = parseItemInteger(active, itemPath, "custom-model-data", true);
        if (activeValue != null) return activeValue > 0 ? activeValue : null;
        Integer bundledValue = parseItemInteger(bundled, itemPath, "custom-model-data", false);
        return bundledValue != null && bundledValue > 0 ? bundledValue : null;
    }

    private int getItemAmount(String itemPath) {
        Integer activeValue = parseItemInteger(active, itemPath, "amount", true);
        if (activeValue != null) {
            if (activeValue >= 1 && activeValue <= 64) return activeValue;
            warn(itemPath + ".amount", "amount phải từ 1 đến 64; dùng cấu hình mặc định.");
        }
        Integer bundledValue = parseItemInteger(bundled, itemPath, "amount", false);
        return bundledValue != null && bundledValue >= 1 && bundledValue <= 64 ? bundledValue : 1;
    }

    private boolean getItemBoolean(String itemPath, String property, boolean javaFallback) {
        Boolean activeValue = parseItemBoolean(active, itemPath, property, true);
        if (activeValue != null) return activeValue;
        Boolean bundledValue = parseItemBoolean(bundled, itemPath, property, false);
        return bundledValue == null ? javaFallback : bundledValue;
    }

    private Set<ItemFlag> getItemFlags(String itemPath, List<ItemFlag> javaFallback) {
        Set<ItemFlag> activeValue = parseInheritedItemFlags(active, itemPath, true);
        if (activeValue != null) return activeValue;
        Set<ItemFlag> bundledValue = parseInheritedItemFlags(bundled, itemPath, false);
        return bundledValue == null ? new LinkedHashSet<>(javaFallback) : bundledValue;
    }

    private Integer parseItemInteger(YamlConfiguration source,
                                     String itemPath,
                                     String property,
                                     boolean report) {
        String directPath = itemPath + "." + property;
        Integer direct = parseInteger(source, directPath, report);
        if (direct != null) return direct;
        return parseInteger(source, "defaults.item." + property, report);
    }

    private Boolean parseItemBoolean(YamlConfiguration source,
                                     String itemPath,
                                     String property,
                                     boolean report) {
        String directPath = itemPath + "." + property;
        Boolean direct = parseBoolean(source, directPath, report);
        if (direct != null) return direct;
        return parseBoolean(source, "defaults.item." + property, report);
    }

    private Set<ItemFlag> parseInheritedItemFlags(YamlConfiguration source,
                                                  String itemPath,
                                                  boolean report) {
        String directPath = itemPath + ".flags";
        Set<ItemFlag> direct = parseItemFlags(source, directPath, report);
        if (direct != null) return direct;
        return parseItemFlags(source, "defaults.item.flags", report);
    }

    private Integer readValidatedInt(String path, java.util.function.IntPredicate validator) {
        Integer activeValue = parseInteger(active, path, true);
        if (activeValue != null) {
            if (validator.test(activeValue)) return activeValue;
            warn(path, "giá trị " + activeValue + " không hợp lệ; dùng cấu hình mặc định.");
        }
        Integer bundledValue = parseInteger(bundled, path, false);
        return bundledValue != null && validator.test(bundledValue) ? bundledValue : null;
    }

    private List<Integer> parseSlots(YamlConfiguration source, String path, int inventorySize, boolean report) {
        if (!source.contains(path)) return null;
        Object raw = source.get(path);
        if (!(raw instanceof List<?> list)) {
            if (report) warn(path, "phải là danh sách slot; dùng cấu hình mặc định.");
            return null;
        }

        LinkedHashSet<Integer> output = new LinkedHashSet<>();
        boolean hadInvalid = false;
        for (Object entry : list) {
            Integer slot = parseIntegerValue(entry);
            if (!isSlot(slot, inventorySize)) {
                hadInvalid = true;
                continue;
            }
            if (!output.add(slot)) hadInvalid = true;
        }
        if (hadInvalid && report) {
            warn(path, "có slot sai, trùng hoặc nằm ngoài GUI size " + inventorySize + "; các slot đó đã bị bỏ qua.");
        }
        if (!list.isEmpty() && output.isEmpty()) return null;
        return new ArrayList<>(output);
    }

    private Set<ItemFlag> parseItemFlags(YamlConfiguration source, String path, boolean report) {
        if (!source.contains(path)) return null;
        Object raw = source.get(path);
        if (!(raw instanceof List<?> list)) {
            if (report) warn(path, "phải là danh sách ItemFlag; dùng cấu hình mặc định.");
            return null;
        }
        LinkedHashSet<ItemFlag> output = new LinkedHashSet<>();
        boolean invalid = false;
        for (Object entry : list) {
            try {
                output.add(ItemFlag.valueOf(String.valueOf(entry).trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                invalid = true;
            }
        }
        if (invalid && report) warn(path, "có ItemFlag không hợp lệ; flag đó đã bị bỏ qua.");
        if (!list.isEmpty() && output.isEmpty()) return null;
        return output;
    }

    private List<String> parseStringList(YamlConfiguration source, String path, boolean report) {
        if (!source.contains(path)) return null;
        Object raw = source.get(path);
        if (!(raw instanceof List<?> list)) {
            if (report) warn(path, "phải là danh sách chuỗi; dùng cấu hình mặc định.");
            return null;
        }
        List<String> output = new ArrayList<>(list.size());
        for (Object entry : list) output.add(entry == null ? "" : String.valueOf(entry));
        return output;
    }

    private Material parseMaterial(YamlConfiguration source, String path, boolean report) {
        String materialName = parseString(source, path, report);
        if (materialName == null) return null;
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null || material == Material.AIR) {
            if (report) warn(path, "material '" + materialName + "' không hợp lệ; dùng cấu hình mặc định.");
            return null;
        }
        return material;
    }

    private MenuAction parseAction(YamlConfiguration source, String path, boolean report) {
        String actionName = parseString(source, path, report);
        if (actionName == null) return null;
        MenuAction action = MenuAction.parse(actionName);
        if (action == null && report) {
            warn(path, "action '" + actionName + "' không thuộc whitelist; dùng cấu hình mặc định.");
        }
        return action;
    }

    private String parseString(YamlConfiguration source, String path, boolean report) {
        if (!source.contains(path)) return null;
        Object value = source.get(path);
        if (value instanceof String string) return string;
        if (report) warn(path, "phải là chuỗi; dùng cấu hình mặc định.");
        return null;
    }

    private Boolean parseBoolean(YamlConfiguration source, String path, boolean report) {
        if (!source.contains(path)) return null;
        Object value = source.get(path);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) {
            if (string.equalsIgnoreCase("true")) return true;
            if (string.equalsIgnoreCase("false")) return false;
        }
        if (report) warn(path, "phải là true/false; dùng cấu hình mặc định.");
        return null;
    }

    private Integer parseInteger(YamlConfiguration source, String path, boolean report) {
        if (!source.contains(path)) return null;
        Integer parsed = parseIntegerValue(source.get(path));
        if (parsed == null && report) warn(path, "phải là số nguyên; dùng cấu hình mặc định.");
        return parsed;
    }

    private Integer parseIntegerValue(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean isSlot(Integer slot, int inventorySize) {
        return slot != null && slot >= 0 && slot < inventorySize;
    }

    private void ensureDefaultFile() {
        if (guiFile.exists()) return;
        try {
            plugin.saveResource("gui.yml", false);
        } catch (IllegalArgumentException exception) {
            plugin.logWarning("Không thể tạo gui.yml mặc định: " + exception.getMessage(), exception);
        }
    }

    private YamlConfiguration loadBundled() {
        try (InputStream input = plugin.getResource("gui.yml")) {
            if (input == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.logWarning("Không thể đọc gui.yml mặc định: " + exception.getMessage(), exception);
            return new YamlConfiguration();
        }
    }

    private void warn(String key, String message) {
        if (reportedWarnings.add(key + "|" + message)) {
            plugin.logWarning("[gui.yml] " + key + ": " + message);
        }
    }
}
