package vn.sotarpayments.common.lang;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import vn.sotarpayments.SotarPayments;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LanguageManager {
    public static final String DEFAULT_LANGUAGE = "vi";
    public static final List<String> SUPPORTED_LANGUAGES = List.of(
            "vi", "en", "es", "fr", "de", "pt", "ru", "zh", "ja", "ko",
            "th", "id", "ms", "tl", "hi", "ar", "tr", "pl"
    );

    private final SotarPayments plugin;
    private YamlConfiguration activeLanguage;
    private YamlConfiguration activeBundledFallback;
    private YamlConfiguration vietnameseFallback;
    private String languageCode = DEFAULT_LANGUAGE;

    public LanguageManager(SotarPayments plugin) {
        this.plugin = plugin;
        saveDefaultLanguageFiles();
        reload();
    }

    public void saveDefaultLanguageFiles() {
        File folder = new File(plugin.getDataFolder(), "languages");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.logWarning("Cannot create languages folder.");
        }
        for (String code : SUPPORTED_LANGUAGES) {
            String resourcePath = "languages/" + code + ".yml";
            File file = new File(folder, code + ".yml");
            if (!file.exists()) {
                try {
                    plugin.saveResource(resourcePath, false);
                } catch (IllegalArgumentException ignored) {
                    plugin.logWarning("Missing bundled language resource: " + resourcePath);
                }
            }
        }
    }

    public void reload() {
        String configured = plugin.config().getString("language", DEFAULT_LANGUAGE);
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_LANGUAGE;
        }
        configured = configured.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(configured)) {
            plugin.logWarning("Unsupported language '" + configured + "'. Falling back to '" + DEFAULT_LANGUAGE + "'.");
            configured = DEFAULT_LANGUAGE;
        }
        this.languageCode = configured;
        this.activeLanguage = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "languages/" + configured + ".yml"));
        this.activeBundledFallback = loadBundledLanguage(configured);
        this.vietnameseFallback = loadBundledLanguage(DEFAULT_LANGUAGE);
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String text(String key, Object... replacements) {
        String raw = lookupString(key);
        if (raw == null) {
            raw = key;
        }
        return color(applyPlaceholders(raw, replacements));
    }

    public List<String> list(String key, Object... replacements) {
        List<String> rawList = lookupStringList(key);
        List<String> output = new ArrayList<>();
        for (String raw : rawList) {
            output.add(color(applyPlaceholders(raw, replacements)));
        }
        return output;
    }

    private String lookupString(String key) {
        String value = activeLanguage.getString(key);
        if (value == null && activeBundledFallback != null) value = activeBundledFallback.getString(key);
        if (value == null && vietnameseFallback != null) value = vietnameseFallback.getString(key);
        if (value == null && !DEFAULT_LANGUAGE.equals(languageCode)) {
            YamlConfiguration diskVi = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "languages/vi.yml"));
            value = diskVi.getString(key);
        }
        if (value != null && value.contains("{prefix}")) {
            String prefix = lookupString("prefix");
            if (prefix == null || prefix.equals(value)) prefix = "&8[&bSotarPayments&8] ";
            value = value.replace("{prefix}", prefix);
        }
        return value;
    }

    private List<String> lookupStringList(String key) {
        List<String> value = readList(activeLanguage, key);
        if (value.isEmpty() && activeBundledFallback != null) value = readList(activeBundledFallback, key);
        if (value.isEmpty() && vietnameseFallback != null) value = readList(vietnameseFallback, key);
        return value;
    }

    private List<String> readList(YamlConfiguration config, String key) {
        if (config == null) return List.of();
        if (config.isList(key)) return config.getStringList(key);
        if (config.isString(key)) return List.of(config.getString(key, ""));
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section != null) {
            List<String> values = new ArrayList<>();
            for (String child : section.getKeys(false)) {
                values.add(config.getString(key + "." + child, ""));
            }
            return values;
        }
        return List.of();
    }

    private YamlConfiguration loadBundledLanguage(String code) {
        String resourcePath = "languages/" + code + ".yml";
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.logWarning("Cannot load bundled language resource " + resourcePath + ": " + e.getMessage(), e);
            return new YamlConfiguration();
        }
    }

    private String applyPlaceholders(String message, Object... replacements) {
        if (message == null) return "";
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            values.put(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        String result = message;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }
}
