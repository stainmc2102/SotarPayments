package vn.sotarpayments.common.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Central reward bridge. It keeps legacy command rewards fully compatible while
 * optionally depositing through PlayerPoints, Vault, or FancyEco/FancyEconomy.
 * Third-party integrations are reflective so none of them become hard depends.
 */
public final class EconomyManager {
    private static final List<String> FANCY_PLUGIN_NAMES = List.of(
            "FancyEco", "FancyEconomy", "FancyEconomyCore"
    );
    private static final List<String> CREDIT_METHOD_NAMES = List.of(
            "deposit", "depositPlayer", "addBalance", "addMoney", "give", "add", "credit"
    );

    private final SotarPayments plugin;
    private volatile Provider configuredProvider = Provider.COMMAND;
    private volatile Provider resolvedProvider = Provider.COMMAND;
    private volatile boolean fallbackToCommand = true;

    public EconomyManager(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        reload();
    }

    public synchronized void reload() {
        configuredProvider = Provider.from(plugin.getEconomyProviderName());
        fallbackToCommand = plugin.config().getBoolean("economy.fallback-to-command", true);
        resolvedProvider = resolve(configuredProvider);
        plugin.getLogger().info("Economy reward provider: " + resolvedProvider.displayName
                + (configuredProvider == Provider.AUTO ? " (auto)" : "") + ".");
    }

    public DeliveryResult deliverBankOrManual(String playerName,
                                              int points,
                                              long amount,
                                              PaymentChannel channel) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.BANK : channel;
        return deliver(playerName, points, amount, amount, safeChannel, List.of());
    }

    public DeliveryResult deliverCard(String playerName,
                                      int points,
                                      long amount,
                                      long netAmount,
                                      List<String> legacyCommands) {
        return deliver(playerName, points, amount, netAmount, PaymentChannel.CARD,
                legacyCommands == null ? List.of() : legacyCommands);
    }

    public String getResolvedProviderName() {
        return resolvedProvider.displayName;
    }

    private DeliveryResult deliver(String playerName,
                                   int points,
                                   long amount,
                                   long netAmount,
                                   PaymentChannel channel,
                                   List<String> legacyCardCommands) {
        if (playerName == null || playerName.isBlank() || points < 0) {
            return DeliveryResult.failure(resolvedProvider.displayName, "invalid reward input");
        }

        Provider active = resolvedProvider;
        if (active == Provider.COMMAND) {
            boolean success = runCommandRewards(playerName, points, amount, netAmount, channel, legacyCardCommands);
            return success
                    ? DeliveryResult.success(active.displayName)
                    : DeliveryResult.failure(active.displayName, "one or more reward commands failed");
        }

        try {
            if (deposit(active, playerName, points)) {
                boolean postCommands = runPostCommands(playerName, points, amount, netAmount, channel);
                if (!postCommands) {
                    return DeliveryResult.failure(active.displayName, "economy deposit succeeded but a post-command failed");
                }
                return DeliveryResult.success(active.displayName);
            }
        } catch (Throwable throwable) {
            plugin.logWarning(active.displayName + " reward hook failed for " + playerName + ": "
                    + rootMessage(throwable), throwable);
        }

        if (!fallbackToCommand) {
            return DeliveryResult.failure(active.displayName, "economy provider rejected the deposit");
        }

        plugin.logWarning(active.displayName + " could not deliver the reward for " + playerName
                + "; using the configured command fallback.");
        boolean fallbackSuccess = runCommandRewards(playerName, points, amount, netAmount, channel, legacyCardCommands);
        return fallbackSuccess
                ? DeliveryResult.success(active.displayName + " -> Command fallback")
                : DeliveryResult.failure(active.displayName, "provider and command fallback both failed");
    }

    private boolean deposit(Provider provider, String playerName, int points) throws Exception {
        return switch (provider) {
            case VAULT -> depositVault(playerName, points);
            case PLAYERPOINTS -> depositPlayerPoints(playerName, points);
            case FANCYECO -> depositFancyEco(playerName, points);
            case COMMAND, AUTO -> false;
        };
    }

    private boolean runLegacyCommands(String playerName,
                                      int points,
                                      long amount,
                                      long netAmount,
                                      PaymentChannel channel,
                                      List<String> legacyCardCommands) {
        List<String> commands;
        if (channel == PaymentChannel.CARD && !legacyCardCommands.isEmpty()) {
            commands = legacyCardCommands;
        } else {
            String command = plugin.config().getString("economy.command",
                    plugin.config().getString("reward-command", "p give {player} {points}"));
            commands = command == null || command.isBlank() ? List.of() : List.of(command);
        }
        if (commands.isEmpty()) {
            plugin.logWarning("No reward command is configured for " + channel.storageKey() + ".");
            return false;
        }
        return runCommands(commands, playerName, points, amount, netAmount, channel);
    }

    /**
     * New installations use an ordered list of reward commands shared by every
     * payment channel. Old card-specific and singular command keys remain the
     * exact fallback path, so existing servers do not gain duplicate rewards or
     * duplicate broadcasts after upgrading.
     */
    private boolean runCommandRewards(String playerName,
                                      int points,
                                      long amount,
                                      long netAmount,
                                      PaymentChannel channel,
                                      List<String> legacyCardCommands) {
        List<String> configuredCommands = nonBlankCommands(
                plugin.config().getStringList("economy.reward-commands"));
        if (configuredCommands.isEmpty()) {
            return runLegacyCommands(playerName, points, amount, netAmount, channel, legacyCardCommands);
        }

        if (!runCommands(configuredCommands, playerName, points, amount, netAmount, channel)) {
            return false;
        }
        return runPostCommands(playerName, points, amount, netAmount, channel);
    }

    private List<String> nonBlankCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) return List.of();
        List<String> output = new ArrayList<>(commands.size());
        for (String command : commands) {
            if (command != null && !command.isBlank()) output.add(command);
        }
        return output;
    }

    private boolean runPostCommands(String playerName,
                                    int points,
                                    long amount,
                                    long netAmount,
                                    PaymentChannel channel) {
        String key = switch (channel) {
            case CARD -> "card";
            case MANUAL -> "manual";
            default -> "bank";
        };
        return runCommands(plugin.config().getStringList("economy.post-commands." + key),
                playerName, points, amount, netAmount, channel);
    }

    private boolean runCommands(List<String> templates,
                                String playerName,
                                int points,
                                long amount,
                                long netAmount,
                                PaymentChannel channel) {
        boolean success = true;
        for (String template : templates) {
            if (template == null || template.isBlank()) continue;
            String command = applyPlaceholders(template, playerName, points, amount, netAmount, channel);
            if (command.startsWith("/")) command = command.substring(1);
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    success = false;
                    plugin.logWarning("Reward command returned false: " + command);
                }
            } catch (Throwable throwable) {
                success = false;
                plugin.logWarning("Reward command failed: " + command, throwable);
            }
        }
        return success;
    }

    private String applyPlaceholders(String template,
                                     String playerName,
                                     int points,
                                     long amount,
                                     long netAmount,
                                     PaymentChannel channel) {
        String channelName = channel.storageKey();
        return template
                .replace("{player}", playerName)
                .replace("%player%", playerName)
                .replace("{points}", String.valueOf(points))
                .replace("%points%", String.valueOf(points))
                .replace("{amount}", String.valueOf(amount))
                .replace("%amount%", String.valueOf(amount))
                .replace("{net_amount}", String.valueOf(netAmount))
                .replace("%net_amount%", String.valueOf(netAmount))
                .replace("{channel}", channelName)
                .replace("%channel%", channelName);
    }

    private Provider resolve(Provider requested) {
        if (requested != Provider.AUTO) {
            if (requested == Provider.COMMAND || isAvailable(requested)) return requested;
            plugin.logWarning(requested.displayName + " is configured but unavailable. "
                    + (fallbackToCommand ? "Command fallback will be used." : "Rewards will fail until the hook is installed."));
            return requested;
        }

        if (isAvailable(Provider.FANCYECO)) return Provider.FANCYECO;
        if (isAvailable(Provider.PLAYERPOINTS)) return Provider.PLAYERPOINTS;
        if (isAvailable(Provider.VAULT)) return Provider.VAULT;
        return Provider.COMMAND;
    }

    private boolean isAvailable(Provider provider) {
        return switch (provider) {
            case COMMAND -> true;
            case AUTO -> false;
            case VAULT -> findVaultProvider() != null;
            case PLAYERPOINTS -> findEnabledPlugin("PlayerPoints") != null;
            case FANCYECO -> findFancyPlugin() != null;
        };
    }

    private boolean depositVault(String playerName, int points) throws Exception {
        Object economy = findVaultProvider();
        if (economy == null) return false;
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);

        Method method = findCompatibleMethod(economy.getClass(), List.of("depositPlayer"), player, (double) points);
        Object result;
        if (method != null) {
            result = method.invoke(economy, adaptIdentity(method.getParameterTypes()[0], playerName),
                    adaptNumber(method.getParameterTypes()[1], points));
        } else {
            return false;
        }
        return successfulResult(result);
    }

    private Object findVaultProvider() {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy", false,
                    plugin.getClass().getClassLoader());
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean depositPlayerPoints(String playerName, int points) throws Exception {
        Plugin pointsPlugin = findEnabledPlugin("PlayerPoints");
        if (pointsPlugin == null) return false;
        Object api = invokeNoArg(pointsPlugin, "getAPI");
        if (api == null) api = pointsPlugin;
        return invokeCredit(api, playerName, points);
    }

    private boolean depositFancyEco(String playerName, int points) throws Exception {
        Plugin fancyPlugin = findFancyPlugin();
        if (fancyPlugin == null) return false;

        List<Object> candidates = new ArrayList<>();
        candidates.add(fancyPlugin);
        Object api = invokeNoArg(fancyPlugin, "getAPI");
        if (api != null && api != fancyPlugin) candidates.add(api);
        Object economy = invokeNoArg(fancyPlugin, "getEconomy");
        if (economy != null && economy != fancyPlugin && economy != api) candidates.add(economy);

        for (Object candidate : candidates) {
            if (invokeCredit(candidate, playerName, points)) return true;
        }
        return false;
    }

    private boolean invokeCredit(Object target, String playerName, int points) throws Exception {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        Method method = findCompatibleMethod(target.getClass(), CREDIT_METHOD_NAMES, offlinePlayer, points);
        if (method == null) return false;

        Object identity = adaptIdentity(method.getParameterTypes()[0], playerName);
        Object value = adaptNumber(method.getParameterTypes()[1], points);
        if (identity == null || value == null) return false;
        Object result = method.invoke(target, identity, value);
        return successfulResult(result);
    }

    private Method findCompatibleMethod(Class<?> type,
                                        List<String> names,
                                        OfflinePlayer player,
                                        Number amount) {
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 2 || !containsIgnoreCase(names, method.getName())) continue;
            if (adaptIdentity(method.getParameterTypes()[0], player.getName()) == null) continue;
            if (adaptNumber(method.getParameterTypes()[1], amount) == null) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private Object adaptIdentity(Class<?> type, String playerName) {
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return playerName;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (type.isInstance(offline)) return offline;
        UUID uuid = offline.getUniqueId();
        if (type == UUID.class) return uuid;
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null && type.isInstance(online)) return online;
        return null;
    }

    private Object adaptNumber(Class<?> type, Number amount) {
        if (type == int.class || type == Integer.class) return amount.intValue();
        if (type == long.class || type == Long.class) return amount.longValue();
        if (type == double.class || type == Double.class) return amount.doubleValue();
        if (type == float.class || type == Float.class) return amount.floatValue();
        if (type == short.class || type == Short.class) return amount.shortValue();
        if (Number.class.isAssignableFrom(type)) return amount;
        return null;
    }

    private boolean successfulResult(Object result) {
        if (result == null) return true;
        if (result instanceof Boolean bool) return bool;
        for (String methodName : List.of("transactionSuccess", "isSuccess", "success", "succeeded")) {
            try {
                Method method = result.getClass().getMethod(methodName);
                Object value = method.invoke(result);
                if (value instanceof Boolean bool) return bool;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return true;
    }

    private Plugin findFancyPlugin() {
        for (String name : FANCY_PLUGIN_NAMES) {
            Plugin plugin = findEnabledPlugin(name);
            if (plugin != null) return plugin;
        }
        return null;
    }

    private Plugin findEnabledPlugin(String name) {
        Plugin found = Bukkit.getPluginManager().getPlugin(name);
        return found != null && found.isEnabled() ? found : null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private enum Provider {
        COMMAND("Command"),
        AUTO("Auto"),
        VAULT("Vault"),
        PLAYERPOINTS("PlayerPoints"),
        FANCYECO("FancyEco");

        private final String displayName;

        Provider(String displayName) {
            this.displayName = displayName;
        }

        private static Provider from(String raw) {
            String normalized = raw == null ? "command" : raw.trim().toLowerCase(Locale.ROOT)
                    .replace("-", "").replace("_", "");
            return switch (normalized) {
                case "auto" -> AUTO;
                case "vault" -> VAULT;
                case "playerpoints", "points" -> PLAYERPOINTS;
                case "fancyeco", "fancyeconomy" -> FANCYECO;
                default -> COMMAND;
            };
        }
    }

    public record DeliveryResult(boolean success, String provider, String failureReason) {
        public static DeliveryResult success(String provider) {
            return new DeliveryResult(true, provider, "");
        }

        public static DeliveryResult failure(String provider, String reason) {
            return new DeliveryResult(false, provider, reason == null ? "unknown" : reason);
        }
    }
}
