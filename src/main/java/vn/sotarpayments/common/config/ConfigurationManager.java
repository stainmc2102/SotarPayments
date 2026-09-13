package vn.sotarpayments.common.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import vn.sotarpayments.SotarPayments;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Owns SotarPayments' split configuration files and exposes one merged, read-only
 * view to the rest of the plugin. More specific files override config.yml while
 * old monolithic installations are migrated without deleting legacy values.
 */
public final class ConfigurationManager {
    private static final List<ConfigFile> FILES = List.of(
            new ConfigFile("config.yml", List.of()),
            new ConfigFile("database.yml", List.of("database")),
            new ConfigFile("payments.yml", List.of(
                    "napbank", "napthe", "promotion", "reward-command", "conversion-rate", "economy")),
            new ConfigFile("discord.yml", List.of("discord-webhook")),
            new ConfigFile("milestones.yml", List.of("milestones", "server-milestones")),
            new ConfigFile("providers/payos.yml", List.of("payos")),
            new ConfigFile("providers/sepay.yml", List.of("sepay")),
            new ConfigFile("providers/card2k.yml", List.of("card2k")),
            new ConfigFile("providers/gachthefast.yml", List.of("gachthefast")),
            new ConfigFile("providers/thesieure.yml", List.of("thesieure"))
    );

    private final SotarPayments plugin;
    private final Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
    private volatile YamlConfiguration merged = new YamlConfiguration();

    public ConfigurationManager(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized void load() {
        FileConfiguration legacyMain = plugin.getConfig();
        loaded.clear();

        for (ConfigFile definition : FILES) {
            File file = new File(plugin.getDataFolder(), definition.path());
            boolean existed = file.isFile();
            ensureResource(definition.path(), file);

            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            if (!existed && !definition.legacyRoots().isEmpty()) {
                boolean migrated = migrateLegacyRoots(legacyMain, configuration, definition.legacyRoots());
                if ("payments.yml".equals(definition.path())) {
                    preserveLegacyRewardResolution(legacyMain, configuration);
                }
                if (migrated) {
                    save(configuration, file, "migrated configuration " + definition.path());
                    plugin.getLogger().info("Migrated legacy config.yml values into " + definition.path() + ".");
                }
            }
            loaded.put(definition.path(), configuration);
        }

        rebuildMerged();
    }

    public synchronized void reload() {
        load();
    }

    public FileConfiguration config() {
        return merged;
    }

    public synchronized void saveMilestones() {
        YamlConfiguration milestones = loaded.get("milestones.yml");
        if (milestones == null) {
            throw new IllegalStateException("milestones.yml is not loaded.");
        }

        copyRoot(merged, milestones, "milestones");
        copyRoot(merged, milestones, "server-milestones");
        save(milestones, new File(plugin.getDataFolder(), "milestones.yml"), "milestones.yml");
        rebuildMerged();
    }

    public synchronized FileConfiguration file(String relativePath) {
        YamlConfiguration configuration = loaded.get(relativePath);
        if (configuration == null) {
            throw new IllegalArgumentException("Unknown SotarPayments configuration file: " + relativePath);
        }
        return configuration;
    }

    private void ensureResource(String resourcePath, File destination) {
        if (destination.isFile()) return;

        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create configuration directory: " + parent);
        }
        plugin.saveResource(resourcePath, false);
    }

    private boolean migrateLegacyRoots(FileConfiguration legacy,
                                       YamlConfiguration target,
                                       List<String> roots) {
        boolean changed = false;
        for (String root : roots) {
            if (!legacy.contains(root)) continue;
            copyRoot(legacy, target, root);
            changed = true;
        }
        return changed;
    }

    /**
     * The bundled 1.4 configuration contains the new multi-command list. When
     * migrating an older monolithic config, that default must not hide a
     * customized singular/card-specific reward command copied from the old
     * file. Removing only the bundled list lets EconomyManager follow its
     * legacy fallback chain without rewriting or deleting the user's values.
     */
    private void preserveLegacyRewardResolution(FileConfiguration legacy,
                                                YamlConfiguration target) {
        if (legacy.contains("economy.reward-commands")) return;
        boolean hasLegacyReward = legacy.contains("economy.command")
                || legacy.contains("reward-command")
                || legacy.contains("napthe.rewards.commands")
                || legacy.contains("card2k.rewards.commands");
        if (hasLegacyReward) {
            target.set("economy.reward-commands", null);
        }
    }

    private void copyRoot(ConfigurationSection source, ConfigurationSection target, String root) {
        target.set(root, null);
        Object scalar = source.get(root);
        ConfigurationSection sourceSection = source.getConfigurationSection(root);
        if (sourceSection == null) {
            if (scalar != null) target.set(root, scalar);
            return;
        }

        ConfigurationSection targetSection = target.createSection(root);
        copySection(sourceSection, targetSection);
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copySection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    private void rebuildMerged() {
        YamlConfiguration next = new YamlConfiguration();
        for (ConfigFile definition : FILES) {
            YamlConfiguration configuration = loaded.get(definition.path());
            if (configuration == null) continue;
            for (Map.Entry<String, Object> entry : configuration.getValues(true).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection) continue;
                next.set(entry.getKey(), entry.getValue());
            }
        }
        merged = next;
    }

    private void save(YamlConfiguration configuration, File file, String description) {
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Cannot save " + description + ".", exception);
            throw new IllegalStateException("Cannot save " + description + ".", exception);
        }
    }

    private record ConfigFile(String path, List<String> legacyRoots) {
        private ConfigFile {
            path = Objects.requireNonNull(path, "path");
            legacyRoots = List.copyOf(legacyRoots);
        }
    }
}
