package vn.sotarpayments;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.ChatColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import vn.sotarpayments.common.config.ConfigurationManager;
import vn.sotarpayments.common.economy.EconomyManager;
import vn.sotarpayments.common.interfaceui.ModernPaymentInterfaceManager;
import vn.sotarpayments.common.lang.LanguageManager;
import vn.sotarpayments.common.manager.AdminGUIManager;
import vn.sotarpayments.common.manager.DatabaseManager;
import vn.sotarpayments.common.manager.GuiConfigManager;
import vn.sotarpayments.common.manager.LogManager;
import vn.sotarpayments.common.manager.MilestoneGUIManager;
import vn.sotarpayments.common.manager.MilestoneManager;
import vn.sotarpayments.common.manager.PaymentGUIManager;
import vn.sotarpayments.common.manager.TransactionWebhookManager;
import vn.sotarpayments.common.model.PaymentChannel;
import vn.sotarpayments.common.scheduler.PlatformScheduler;
import vn.sotarpayments.napbank.commands.AdminCommand;
import vn.sotarpayments.napbank.commands.BankCommand;
import vn.sotarpayments.napbank.commands.HistoryCommand;
import vn.sotarpayments.napbank.commands.MilestoneCommand;
import vn.sotarpayments.napbank.commands.PublicCommand;
import vn.sotarpayments.napbank.commands.TopCommand;
import vn.sotarpayments.napbank.listeners.MenuListener;
import vn.sotarpayments.napbank.manager.BankPaymentManager;
import vn.sotarpayments.napcard.commands.CancelCardCommand;
import vn.sotarpayments.napcard.commands.ConfirmCardCommand;
import vn.sotarpayments.napcard.commands.NapTheCommand;
import vn.sotarpayments.napcard.api.CardChargingService;
import vn.sotarpayments.napcard.listeners.CardListener;
import vn.sotarpayments.napcard.manager.CardFlowManager;
import vn.sotarpayments.napcard.manager.CardRateManager;
import vn.sotarpayments.napcard.manager.CardSessionManager;
import vn.sotarpayments.napcard.models.CardRequest;
import vn.sotarpayments.napcard.tasks.CheckPendingTask;
import vn.sotarpayments.store.StoreCommand;
import vn.sotarpayments.store.StoreManager;
import vn.sotarpayments.placeholder.KPExpansion;

import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class SotarPayments extends JavaPlugin {
    private static final List<String> PLACEHOLDER_IDENTIFIERS = List.of("kp", "sotarpayments");
    private static final String AUTHOR_DISCORD = "lz.dy.dg";
    private static final String DEFAULT_SUPPORT_DISCORD_URL = "https://discord.gg/jD5naBEGMA";
    private static final int BSTATS_PLUGIN_ID = 31772;

    private static SotarPayments instance;

    private ConfigurationManager configurationManager;
    private PlatformScheduler platformScheduler;
    private EconomyManager economyManager;
    private LanguageManager languageManager;
    private GuiConfigManager guiConfigManager;
    private DatabaseManager databaseManager;
    private MilestoneManager milestoneManager;
    private BankPaymentManager bankPaymentManager;
    private LogManager logManager;
    private AdminGUIManager adminGUIManager;
    private CardSessionManager cardSessionManager;
    private CardFlowManager cardFlowManager;
    private CardListener cardListener;
    private CardRateManager cardRateManager;
    private CardChargingService cardChargingService;
    private MilestoneGUIManager milestoneGuiManager;
    private PaymentGUIManager paymentGuiManager;
    private ModernPaymentInterfaceManager modernPaymentInterfaceManager;
    private TransactionWebhookManager transactionWebhookManager;
    private StoreManager storeManager;
    private Metrics metrics;
    private PlatformScheduler.ScheduledTask pendingCardCheckTask;
    private final List<KPExpansion> placeholderExpansions = new ArrayList<>();
    private static final int WEBHOOK_CONNECT_TIMEOUT_MS = 5000;
    private static final int WEBHOOK_READ_TIMEOUT_MS = 10000;

    private final Map<UUID, CardRequest> pendingCards = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.configurationManager = new ConfigurationManager(this);
        this.configurationManager.load();

        this.platformScheduler = new PlatformScheduler(this);
        this.logManager = new LogManager(this);
        this.languageManager = new LanguageManager(this);
        this.guiConfigManager = new GuiConfigManager(this);
        this.economyManager = new EconomyManager(this);

        this.databaseManager = new DatabaseManager(this);
        this.milestoneManager = new MilestoneManager(this);
        this.bankPaymentManager = new BankPaymentManager(this);
        this.adminGUIManager = new AdminGUIManager(this);
        this.cardSessionManager = new CardSessionManager();
        this.cardListener = new CardListener(this);
        this.cardFlowManager = new CardFlowManager(this);
        this.cardRateManager = new CardRateManager(this);
        this.cardChargingService = new CardChargingService(this);
        this.milestoneGuiManager = new MilestoneGUIManager(this);
        this.paymentGuiManager = new PaymentGUIManager(this);
        this.modernPaymentInterfaceManager = new ModernPaymentInterfaceManager(this);
        this.transactionWebhookManager = new TransactionWebhookManager(this);
        this.storeManager = new StoreManager(this);

        reloadPlugin();
        startMetrics();

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(this.cardListener, this);
        registerCompatibilityListeners();

        registerCommands();
        registerPlaceholderExpansion();
        platformScheduler.runGlobalLater(this::registerPlaceholderExpansion, 40L);

        this.pendingCardCheckTask = platformScheduler.runTimerAsync(new CheckPendingTask(this), 1200L, 1200L);

        printStartupBanner();
    }

    @Override
    public void onDisable() {
        try {
            safelyShutdown("pending card timer", () -> {
                if (pendingCardCheckTask != null) pendingCardCheckTask.cancel();
                pendingCardCheckTask = null;
            });

            // Stop network clients BEFORE the scheduler and class loader start unloading.
            // JDA WebSocket callback threads must finish while plugin classes are still available;
            // otherwise Leaf/Paper/Folia report "zip file closed" during shutdown.
            safelyShutdown("Discord Store", () -> {
                if (storeManager != null) storeManager.shutdown();
            });

            // Allow JDA daemon threads a brief window to observe the shutdown signal and
            // exit cleanly before the classloader starts unloading plugin classes.
            // This is critical on Leaf/Folia where async schedulers may still reference plugin classes.
            if (platformScheduler != null && platformScheduler.isFolia()) {
                try {
                    Thread.sleep(800L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            safelyShutdown("bank payment manager", () -> {
                if (bankPaymentManager != null) bankPaymentManager.shutdown();
            });
            safelyShutdown("card-provider HTTP client", () -> {
                if (cardChargingService != null) cardChargingService.shutdown();
            });

            // Folia needs its entity schedulers alive while BossBar viewers are detached.
            safelyShutdown("milestone manager", () -> {
                if (milestoneManager != null) milestoneManager.shutdown();
            });
            safelyShutdown("PlaceholderAPI expansions", this::unregisterPlaceholderExpansions);
            safelyShutdown("bStats", () -> {
                if (metrics != null) metrics.shutdown();
                metrics = null;
            });
            safelyShutdown("platform scheduler", () -> {
                if (platformScheduler != null) platformScheduler.beginShutdown();
            });
            safelyShutdown("background task drain", () -> {
                if (platformScheduler != null && !platformScheduler.awaitQuiescence(18_000L)) {
                    getLogger().warning("Some background jobs did not finish before the shutdown safety timeout.");
                }
            });
            safelyShutdown("database pool", () -> {
                if (databaseManager != null) databaseManager.close();
            });
            safelyShutdown("log writer", () -> {
                if (logManager != null) logManager.shutdown();
            });
        } finally {
            pendingCards.clear();
            instance = null;
        }
    }

    private void safelyShutdown(String component, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Could not fully stop " + component + ".", throwable);
        }
    }

    private void printStartupBanner() {
        if (!config().getBoolean("startup-banner.enabled", true)) {
            return;
        }

        String version = getDescription().getVersion();
        String author = resolvePluginAuthor();
        String serverName = platformScheduler == null ? resolveServerBrand() : platformScheduler.getPlatformName();
        String serverVersion = getServer().getBukkitVersion();

        boolean placeholderApiEnabled = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        boolean metricsEnabled = config().getBoolean("metrics.enabled", true);

        final String reset = "\u001B[0m";
        final String cyan = "\u001B[96m";
        final String blue = "\u001B[36m";
        final String yellow = "\u001B[93m";
        final String white = "\u001B[97m";
        final String gray = "\u001B[90m";
        final String green = "\u001B[92m";
        final String red = "\u001B[91m";

        getLogger().info("");
        getLogger().info(cyan + "  ____        _              ____                                     _       " + reset);
        getLogger().info(cyan + " / ___|  ___ | |_ __ _ _ __ |  _ \\ __ _ _   _ _ __ ___   ___ _ __ | |_ ___ " + reset);
        getLogger().info(blue + " \\___ \\ / _ \\| __/ _` | '__|| |_) / _` | | | | '_ ` _ \\ / _ \\ '_ \\| __/ __|" + reset);
        getLogger().info(blue + "  ___) | (_) | || (_| | |   |  __/ (_| | |_| | | | | | |  __/ | | | | |_\\__ \\" + reset);
        getLogger().info(cyan + " |____/ \\___/ \\__\\__,_|_|   |_|   \\__,_|\\__, |_| |_| |_|\\___|_| |_|\\__|___/" + reset);
        getLogger().info(cyan + "                                      |___/                              " + reset);
        getLogger().info("");
        getLogger().info("                     " + yellow + "SotarPayments" + reset);
        getLogger().info("                  " + gray + "Modern Payment Gateway" + reset);
        getLogger().info("");
        getLogger().info(yellow + " Status   " + green + "Loaded successfully" + reset);
        getLogger().info(yellow + " Version  " + white + version + reset);
        getLogger().info(yellow + " Author   " + white + author + reset);
        getLogger().info(yellow + " Author Discord " + white + AUTHOR_DISCORD + reset);
        getLogger().info(yellow + " Discord Support " + white + getSupportDiscordUrl() + reset);
        getLogger().info(yellow + " Platform " + white + serverName + " / " + serverVersion + reset);
        getLogger().info(yellow + " Database " + white
                + (databaseManager == null ? "Unavailable" : databaseManager.getBackendName()) + reset);
        getLogger().info(yellow + " Economy  " + white
                + (economyManager == null ? "Command" : economyManager.getResolvedProviderName()) + reset);
        getLogger().info(yellow + " Modules  " + white + "Bank, Card, Milestones, Promotion, Discord Store" + reset);
        getLogger().info(yellow + " Hooks    " + white + "PlaceholderAPI: "
                + statusText(placeholderApiEnabled, green, red, reset)
                + white + " | bStats: "
                + statusText(metricsEnabled, green, red, reset) + reset);
        getLogger().info(yellow + " License  " + white + "Free edition - no license required" + reset);
        getLogger().info("");
    }

    private String statusText(boolean enabled, String success, String warning, String reset) {
        return enabled
                ? success + "Enabled" + reset
                : warning + "Standby" + reset;
    }

    private String resolvePluginAuthor() {
        List<String> authors = getDescription().getAuthors();
        if (authors != null && !authors.isEmpty()) {
            return String.join(", ", authors);
        }
        return "DuyDuong";
    }

    private String resolveServerBrand() {
        try {
            String name = Bukkit.getName();
            String version = Bukkit.getVersion();
            if (name == null || name.isBlank()) {
                return version == null || version.isBlank() ? "Bukkit" : version;
            }
            return name;
        } catch (Throwable ignored) {
            return "Bukkit";
        }
    }

    private void startMetrics() {
        if (!config().getBoolean("metrics.enabled", true)) {
            logDebug("bStats metrics are disabled in config.yml.");
            return;
        }

        try {
            this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            logInfo("bStats metrics started with plugin ID " + BSTATS_PLUGIN_ID + ".");
        } catch (Throwable throwable) {
            logWarning("Could not start bStats metrics: " + throwable.getMessage());
            logDebug("bStats startup failed.", throwable);
        }
    }

    private void registerCommands() {
        HistoryCommand historyCommand = new HistoryCommand(this);
        MilestoneCommand milestoneCommand = new MilestoneCommand(this);

        if (getCommand("bank") != null) {
            BankCommand bankCommand = new BankCommand(this);
            getCommand("bank").setExecutor(bankCommand);
            getCommand("bank").setTabCompleter(bankCommand);
        }

        if (getCommand("sotar-admin") != null) {
            AdminCommand adminCommand = new AdminCommand(this, historyCommand, milestoneCommand);
            getCommand("sotar-admin").setExecutor(adminCommand);
            getCommand("sotar-admin").setTabCompleter(adminCommand);
        }

        if (getCommand("sotarpayments") != null) {
            PublicCommand publicCommand = new PublicCommand(this);
            getCommand("sotarpayments").setExecutor(publicCommand);
            getCommand("sotarpayments").setTabCompleter(publicCommand);
        }

        if (getCommand("topnap") != null) {
            TopCommand topCommand = new TopCommand(this);
            getCommand("topnap").setExecutor(topCommand);
            getCommand("topnap").setTabCompleter(topCommand);
        }

        if (getCommand("lichsunap") != null) getCommand("lichsunap").setExecutor(historyCommand);
        if (getCommand("mocnap") != null) {
            getCommand("mocnap").setExecutor(milestoneCommand);
            getCommand("mocnap").setTabCompleter(milestoneCommand);
        }

        if (getCommand("napthe") != null) {
            NapTheCommand napTheExecutor = new NapTheCommand(this);
            getCommand("napthe").setExecutor(napTheExecutor);
            getCommand("napthe").setTabCompleter(napTheExecutor);
        }

        if (getCommand("confirmcard") != null) getCommand("confirmcard").setExecutor(new ConfirmCardCommand(this));
        if (getCommand("cancelcard") != null) getCommand("cancelcard").setExecutor(new CancelCardCommand(this));

        if (getCommand("taokenhbanhang") != null) {
            StoreCommand storeCommand = new StoreCommand(this);
            getCommand("taokenhbanhang").setExecutor(storeCommand);
            getCommand("taokenhbanhang").setTabCompleter(storeCommand);
        }
    }

    private void registerCompatibilityListeners() {
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin() != null
                        && "PlaceholderAPI".equalsIgnoreCase(event.getPlugin().getName())) {
                    registerPlaceholderExpansion();
                }
                if (event.getPlugin() != null && economyManager != null) {
                    String name = event.getPlugin().getName();
                    if ("Vault".equalsIgnoreCase(name)
                            || "PlayerPoints".equalsIgnoreCase(name)
                            || "FancyEco".equalsIgnoreCase(name)
                            || "FancyEconomy".equalsIgnoreCase(name)
                            || "FancyEconomyCore".equalsIgnoreCase(name)) {
                        economyManager.reload();
                    }
                }
            }
        }, this);
    }

    private synchronized void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            logDebug("PlaceholderAPI is not enabled; SotarPayments placeholders will be registered when PlaceholderAPI starts.");
            return;
        }

        List<String> registeredIdentifiers = new ArrayList<>();
        for (String identifier : PLACEHOLDER_IDENTIFIERS) {
            if (isPlaceholderIdentifierTracked(identifier)) {
                continue;
            }

            try {
                KPExpansion expansion = new KPExpansion(this, identifier);
                if (!expansion.register()) {
                    boolean removedStaleExpansion = tryUnregisterExistingSotarExpansion(identifier);
                    if (removedStaleExpansion) {
                        expansion = new KPExpansion(this, identifier);
                    }

                    if (!removedStaleExpansion || !expansion.register()) {
                        Object existingExpansion = findPlaceholderExpansion(identifier);
                        String owner = existingExpansion == null
                                ? "unknown expansion"
                                : existingExpansion.getClass().getName();
                        logWarning("PlaceholderAPI identifier '%" + identifier + "_%' is already registered by " + owner + ". "
                                + "SotarPayments kept running. If placeholder %" + identifier + "_*% is shown as raw text, restart the server or use the other alias.");
                        continue;
                    }
                }

                placeholderExpansions.add(expansion);
                registeredIdentifiers.add("%" + identifier + "_*");
            } catch (Throwable throwable) {
                logSevere("Failed to register PlaceholderAPI expansion '%" + identifier + "_%'.", throwable);
            }
        }

        if (!registeredIdentifiers.isEmpty()) {
            logInfo("Registered PlaceholderAPI expansions: " + String.join(", ", registeredIdentifiers));
        }
    }

    private boolean isPlaceholderIdentifierTracked(String identifier) {
        for (KPExpansion expansion : placeholderExpansions) {
            if (expansion != null && expansion.getIdentifier().equalsIgnoreCase(identifier)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryUnregisterExistingSotarExpansion(String identifier) {
        Object existingExpansion = findPlaceholderExpansion(identifier);
        if (existingExpansion == null || !isSotarPlaceholderExpansion(existingExpansion)) {
            return false;
        }

        if (invokeUnregister(existingExpansion)) {
            logDebug("Removed stale SotarPayments PlaceholderAPI expansion '%" + identifier + "_%'.");
            return true;
        }

        Object manager = getPlaceholderLocalExpansionManager();
        if (manager != null && invokeManagerUnregister(manager, existingExpansion, identifier)) {
            logDebug("Removed stale SotarPayments PlaceholderAPI expansion '%" + identifier + "_%' through LocalExpansionManager.");
            return true;
        }

        return false;
    }

    private boolean isSotarPlaceholderExpansion(Object expansion) {
        if (expansion == null) {
            return false;
        }

        String className = expansion.getClass().getName();
        return className.equals(KPExpansion.class.getName()) || className.startsWith("vn.sotarpayments.");
    }

    private Object findPlaceholderExpansion(String identifier) {
        Object manager = getPlaceholderLocalExpansionManager();
        if (manager == null || identifier == null || identifier.isBlank()) {
            return null;
        }

        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("getExpansion") || method.getParameterCount() != 1) {
                continue;
            }
            if (!String.class.equals(method.getParameterTypes()[0])) {
                continue;
            }
            try {
                Object result = unwrapOptional(method.invoke(manager, identifier));
                if (result != null) {
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }

        Object expansions = invokeNoArg(manager, "getExpansions");
        if (expansions instanceof Map<?, ?> map) {
            Object direct = map.get(identifier);
            if (direct != null) {
                return unwrapOptional(direct);
            }
            for (Object value : map.values()) {
                Object unwrapped = unwrapOptional(value);
                if (identifier.equalsIgnoreCase(getExpansionIdentifier(unwrapped))) {
                    return unwrapped;
                }
            }
        } else if (expansions instanceof Collection<?> collection) {
            for (Object value : collection) {
                Object unwrapped = unwrapOptional(value);
                if (identifier.equalsIgnoreCase(getExpansionIdentifier(unwrapped))) {
                    return unwrapped;
                }
            }
        }

        return null;
    }

    private Object getPlaceholderLocalExpansionManager() {
        Plugin placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null) {
            return null;
        }

        Object manager = invokeNoArg(placeholderApi, "getLocalExpansionManager");
        if (manager != null) {
            return manager;
        }

        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object instance = invokeNoArg(papiClass, "getInstance");
            return invokeNoArg(instance, "getLocalExpansionManager");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }

        try {
            Method method = target instanceof Class<?> clazz
                    ? clazz.getMethod(methodName)
                    : target.getClass().getMethod(methodName);
            return method.invoke(target instanceof Class<?> ? null : target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private String getExpansionIdentifier(Object expansion) {
        Object identifier = invokeNoArg(expansion, "getIdentifier");
        return identifier == null ? "" : String.valueOf(identifier);
    }

    private boolean invokeUnregister(Object expansion) {
        if (expansion == null) {
            return false;
        }

        try {
            Method method = expansion.getClass().getMethod("unregister");
            Object result = method.invoke(expansion);
            return !(result instanceof Boolean bool) || bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeManagerUnregister(Object manager, Object expansion, String identifier) {
        if (manager == null) {
            return false;
        }

        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().toLowerCase(Locale.ROOT).contains("unregister") || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            Object argument = null;
            if (expansion != null && parameterType.isInstance(expansion)) {
                argument = expansion;
            } else if (String.class.equals(parameterType)) {
                argument = identifier;
            }

            if (argument == null) {
                continue;
            }

            try {
                Object result = method.invoke(manager, argument);
                return !(result instanceof Boolean bool) || bool;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private void unregisterPlaceholderExpansions() {
        if (placeholderExpansions.isEmpty()) {
            return;
        }

        for (KPExpansion expansion : new ArrayList<>(placeholderExpansions)) {
            try {
                expansion.getClass().getMethod("unregister").invoke(expansion);
            } catch (NoSuchMethodException ignored) {
                // Older PlaceholderAPI versions may not expose unregister(); the JVM will clear it on shutdown.
            } catch (Throwable throwable) {
                logDebug("Could not unregister PlaceholderAPI expansion '%" + expansion.getIdentifier() + "_%'.", throwable);
            }
        }
        placeholderExpansions.clear();
    }

    private void clearPlaceholderCaches() {
        for (KPExpansion expansion : new ArrayList<>(placeholderExpansions)) {
            try {
                expansion.clearCaches();
            } catch (Throwable throwable) {
                logDebug("Could not clear PlaceholderAPI cache for '%" + expansion.getIdentifier() + "_%'.", throwable);
            }
        }
    }

    public void processSuccessPayment(String playerName, long amount) {
        processSuccessPayment(playerName, amount, PaymentChannel.LEGACY, "", "");
    }

    public void processSuccessPayment(String playerName, long amount, PaymentChannel channel, String provider, String detail) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        getDatabaseManager().addTransaction(playerName, amount, safeChannel, provider, detail);
        clearPlaceholderCaches();

        routeSuccessfulPaymentMilestones(playerName, amount);

        getLogManager().payment(playerName + " topup " + amount + " " + trPlain("general.currency")
                + " via " + safeChannel.storageKey() + (provider == null || provider.isBlank() ? "" : " (" + provider + ")"));

        if (transactionWebhookManager != null) {
            transactionWebhookManager.sendSuccess(safeChannel, playerName, amount, provider, detail);
        }
    }

    private void routeSuccessfulPaymentMilestones(String playerName, long amount) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            if (!platformScheduler.runGlobal(() -> getMilestoneManager().handleSuccessfulPayment(null, amount))) {
                logWarning("Could not schedule milestone refresh for offline player " + playerName + ".");
            }
            return;
        }

        AtomicBoolean routed = new AtomicBoolean(false);
        Runnable retired = () -> {
            if (!routed.compareAndSet(false, true)) return;
            if (!platformScheduler.runGlobal(() -> getMilestoneManager().handleSuccessfulPayment(null, amount))) {
                logWarning("Could not schedule milestone refresh after player " + playerName + " disconnected.");
            }
        };
        boolean scheduled = platformScheduler.runPlayer(player, () -> {
            if (!routed.compareAndSet(false, true)) return;
            getMilestoneManager().handleSuccessfulPayment(player.isOnline() ? player : null, amount);
        }, retired);
        if (!scheduled) {
            retired.run();
        }
    }

    public void processManualTopup(CommandSender sender, Player target, long amount) {
        if (amount <= 0) {
            sender.sendMessage(tr("admin.amount-positive"));
            return;
        }

        String senderName = sender.getName();
        String targetName = target.getName();
        int finalPoints = calculateFinalPoints(amount, PaymentChannel.MANUAL);
        int bonusPoints = calculateBonusPoints(amount, PaymentChannel.MANUAL);

        boolean scheduled = platformScheduler.runGlobal(() -> {
            EconomyManager.DeliveryResult delivery = economyManager.deliverBankOrManual(
                    targetName, finalPoints, amount, PaymentChannel.MANUAL);
            if (!delivery.success()) {
                logSevere("Manual reward delivery failed for " + targetName + " via "
                        + delivery.provider() + ": " + delivery.failureReason());
                sendMessageSafely(sender, tr("manual.reward-failed", "player", targetName));
                platformScheduler.runPlayer(target,
                        () -> target.sendMessage(tr("general.reward-failed")));
                return;
            }

            processSuccessPayment(targetName, amount, PaymentChannel.MANUAL,
                    "admin", "Admin: " + senderName + " | Economy: " + delivery.provider());
            getLogManager().manual(senderName + " manual topup for " + targetName
                    + ", amount " + amount + " " + trPlain("general.currency") + ", points " + finalPoints
                    + ", economy " + delivery.provider() + ".");
            sendMessageSafely(sender,
                    tr("manual.sender-success", "player", targetName, "amount", formatMoney(amount)));
            sendMessageSafely(sender, tr("manual.sender-points", "points", formatMoney(finalPoints)));

            platformScheduler.runPlayer(target, () -> {
                sendActionBar(target, tr("manual.success-actionbar"));
                broadcast(tr("manual.broadcast", "player", targetName, "amount", formatMoney(amount)));
                target.sendMessage(tr("manual.target-amount", "amount", formatMoney(amount)));
                target.sendMessage(tr("manual.target-points", "points", formatMoney(finalPoints)));
                if (bonusPoints > 0) {
                    target.sendMessage(tr("promotion.bonus-channel",
                            "bonus", getPromotionPercent(PaymentChannel.MANUAL),
                            "type", trPlain(PaymentChannel.MANUAL.languageKey())));
                }
                spawnSuccessFirework(target.getLocation());
                target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            });
        });
        if (!scheduled) {
            sender.sendMessage(tr("manual.reward-failed", "player", targetName));
        }
    }

    public void handleCardResponse(Player player, int status, String message, CardRequest card) {
        switch (status) {
            case 1 -> giveCardReward(player, card);
            case 2 -> {
                player.sendMessage(tr("card.wrong-amount"));
                player.sendMessage(tr("card.wrong-amount-note"));
                logCardTransaction(player.getName() + " (WRONG_AMOUNT)", card.getAmount(), 0, 0);
            }
            case 99 -> player.sendMessage(tr("card.pending"));
            default -> player.sendMessage(tr("card.error-prefix", "message", message == null ? "Unknown" : message));
        }
    }

    public void giveCardReward(Player player, CardRequest card) {
        String playerName = player.getName();
        int amount = card.getAmount();
        double taxRate = isCardTaxesEnabled() ? cardRateManager.getDiscountRate(card.getTelco(), amount) : 0;
        int netAmount = (int) (amount * (1.0 - (taxRate / 100.0)));
        int ratio = getCardRewardRatio();
        int basePoints = netAmount / ratio;
        int bonusPoints = calculatePromotionBonusFromBasePoints(basePoints, PaymentChannel.CARD);
        int points = basePoints + bonusPoints;

        boolean scheduled = platformScheduler.runGlobal(() -> {
            EconomyManager.DeliveryResult delivery = economyManager.deliverCard(
                    playerName, points, amount, netAmount, getCardRewardCommands());
            logCardTransaction(playerName + (delivery.success() ? "" : " (REWARD_FAILED)"),
                    amount, netAmount, points);
            processSuccessPayment(playerName, amount, PaymentChannel.CARD, getCardProviderName(),
                    "Telco: " + card.getTelco() + " | Net: " + netAmount + " | Request: " + card.getRequestId()
                            + " | Economy: " + delivery.provider()
                            + " | Reward: " + (delivery.success() ? "DELIVERED" : "FAILED"));

            if (!delivery.success()) {
                logSevere("Card payment was confirmed but reward delivery failed for " + playerName
                        + " via " + delivery.provider() + ": " + delivery.failureReason());
                platformScheduler.runPlayer(player,
                        () -> player.sendMessage(tr("card.reward-failed")));
                return;
            }

            platformScheduler.runPlayer(player, () -> {
                sendActionBar(player, tr("card.success-actionbar"));
                broadcast(tr("card.success-broadcast",
                        "player", playerName, "amount", formatMoney(amount)));
                player.sendMessage(tr("card.success-received",
                        "telco", card.getTelco(), "amount", formatMoney(amount)));
                player.sendMessage(tr("card.success-points", "points", formatMoney(points)));
                if (bonusPoints > 0) {
                    player.sendMessage(tr("promotion.bonus-channel",
                            "bonus", getPromotionPercent(PaymentChannel.CARD),
                            "type", trPlain(PaymentChannel.CARD.languageKey())));
                }

                spawnSuccessFirework(player.getLocation());
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            });
        });
        if (!scheduled) {
            player.sendMessage(tr("card.reward-failed"));
        }
    }

    public void spawnSuccessFirework(Location location) {
        if (location == null || location.getWorld() == null) return;
        Firework firework = (Firework) location.getWorld().spawnEntity(location.clone().add(0, 1, 0), EntityType.FIREWORK_ROCKET);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.YELLOW, Color.BLUE, Color.AQUA)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    private void logCardTransaction(String playerName, int amount, int netAmount, int points) {
        getLogManager().cardTransaction(playerName, amount, netAmount, points);
    }

    public void resetTopNap(CommandSender sender) {
        getDatabaseManager().resetTopNap();
        clearPlaceholderCaches();
        if (milestoneManager != null) {
            milestoneManager.resetServerMilestoneRuntimeData();
        }
        sender.sendMessage(tr("milestone.reset"));
        broadcast(tr("milestone.reset-broadcast"));
        getLogManager().admin(sender.getName() + " reset topnap data.");
    }

    public boolean reloadPlugin() {
        saveDefaultConfig();
        super.reloadConfig();
        if (configurationManager != null) configurationManager.reload();
        if (logManager != null) logManager.reloadSettings();
        if (languageManager != null) languageManager.reload();
        if (guiConfigManager != null) guiConfigManager.reload();
        if (economyManager != null) economyManager.reload();
        if (modernPaymentInterfaceManager != null) modernPaymentInterfaceManager.reload();
        if (milestoneManager != null) milestoneManager.reload();

        if (cardRateManager != null && isCardTaxesEnabled()) {
            cardRateManager.invalidateCache();
        }

        if (storeManager != null) {
            storeManager.reload();
        }

        return true;
    }

    public FileConfiguration config() {
        if (configurationManager != null) {
            return configurationManager.config();
        }
        return super.getConfig();
    }

    public String getSupportDiscordUrl() {
        String configured = config().getString("support.discord-url", DEFAULT_SUPPORT_DISCORD_URL);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SUPPORT_DISCORD_URL;
        }
        return configured.trim();
    }

    public void sendSupportLink(CommandSender sender) {
        if (sender == null) return;
        sender.sendMessage(ChatColor.AQUA + "Discord Support: "
                + ChatColor.WHITE + getSupportDiscordUrl());
    }

    public ConfigurationManager getConfigurationManager() {
        return configurationManager;
    }

    public boolean ensureFeatureAvailable(CommandSender sender) {
        return true;
    }

    public boolean isCardTaxesEnabled() {
        return getBooleanCompat("napthe.taxes.enabled", "card2k.taxes.enabled", true);
    }

    public int getCardRewardRatio() {
        int ratio = getIntCompat("napthe.rewards.ratio", "card2k.rewards.ratio", 1);
        return Math.max(1, ratio);
    }

    public List<String> getCardRewardCommands() {
        List<String> commands = config().getStringList("napthe.rewards.commands");
        if (!commands.isEmpty()) return commands;
        return config().getStringList("card2k.rewards.commands");
    }

    /**
     * Returns the selected bank provider from the new, easy-to-find config.yml
     * section while retaining the old payments.yml key as a compatibility
     * fallback for upgraded installations.
     */
    public String getBankProviderName() {
        return getProviderCompat("providers.bank", "napbank.provider", "payos");
    }

    public String getCardProviderName() {
        return getProviderCompat("providers.card", "napthe.provider", "card2k");
    }

    public String getEconomyProviderName() {
        return getProviderCompat("providers.economy", "economy.provider", "command");
    }

    private String getProviderCompat(String primaryPath, String legacyPath, String fallback) {
        String provider = config().getString(primaryPath);
        if (provider == null || provider.isBlank()) {
            provider = config().getString(legacyPath, fallback);
        }
        return provider == null || provider.isBlank()
                ? fallback
                : provider.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean getBooleanCompat(String primaryPath, String legacyPath, boolean fallback) {
        if (config().contains(primaryPath)) return config().getBoolean(primaryPath, fallback);
        return config().getBoolean(legacyPath, fallback);
    }

    private int getIntCompat(String primaryPath, String legacyPath, int fallback) {
        if (config().contains(primaryPath)) return config().getInt(primaryPath, fallback);
        return config().getInt(legacyPath, fallback);
    }

    public int calculateFinalPoints(long amount) {
        return calculateFinalPoints(amount, PaymentChannel.BANK);
    }

    public int calculateFinalPoints(long amount, PaymentChannel channel) {
        int basePoints = calculateBankBasePoints(amount);
        return basePoints + calculatePromotionBonusFromBasePoints(basePoints, channel);
    }

    public int calculateBankBasePoints(long amount) {
        int rate = Math.max(0, config().getInt("conversion-rate", 1));
        long points = (amount / 1000L) * rate;
        return points > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, points);
    }

    public int calculateBonusPoints(long amount) {
        return calculateBonusPoints(amount, PaymentChannel.BANK);
    }

    public int calculateBonusPoints(long amount, PaymentChannel channel) {
        return calculatePromotionBonusFromBasePoints(calculateBankBasePoints(amount), channel);
    }

    public int calculatePromotionBonusFromBasePoints(int basePoints, PaymentChannel channel) {
        if (basePoints <= 0 || !isPromotionActive(channel)) return 0;
        int percent = getPromotionPercent(channel);
        return Math.max(0, basePoints * percent / 100);
    }

    public int applyPromotionToPoints(int basePoints, PaymentChannel channel) {
        return basePoints + calculatePromotionBonusFromBasePoints(basePoints, channel);
    }

    public boolean isPromotionActive() {
        return isPromotionActive(PaymentChannel.BANK);
    }

    public boolean isPromotionActive(PaymentChannel channel) {
        String path = resolvePromotionPath(channel);
        if (!config().getBoolean(path + ".enabled", false)) return false;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            sdf.setLenient(false);
            String rawEndDate = config().getString(path + ".end-date", "01/01/2000 00:00:00");
            return new Date().before(sdf.parse(rawEndDate));
        } catch (Exception e) {
            logWarning("Invalid " + path + ".end-date. Expected format: dd/MM/yyyy HH:mm:ss");
            return false;
        }
    }

    public int getPromotionPercent(PaymentChannel channel) {
        return Math.max(0, config().getInt(resolvePromotionPath(channel) + ".percent", 0));
    }

    private String resolvePromotionPath(PaymentChannel channel) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.BANK : channel;
        if (safeChannel == PaymentChannel.CARD) {
            return "napthe.promotion";
        }

        if (config().contains("napbank.promotion")) {
            return "napbank.promotion";
        }

        if (config().contains("promotion")) {
            return "promotion";
        }

        return safeChannel.promotionPath();
    }

    public void dispatchConsoleCommand(String command) {
        if (command == null || command.isBlank()) return;
        platformScheduler.runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    public void broadcast(String message) {
        platformScheduler.runGlobal(() -> Bukkit.broadcastMessage(message));
    }

    public void sendMessageSafely(CommandSender sender, String message) {
        if (sender == null) return;
        if (sender instanceof Player player) {
            platformScheduler.runPlayer(player, () -> player.sendMessage(message));
            return;
        }
        platformScheduler.runGlobal(() -> sender.sendMessage(message));
    }

    public void sendActionBar(Player player, String message) {
        if (player == null) return;
        platformScheduler.runPlayer(player, () -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message)));
    }

    public String tr(String key, Object... replacements) {
        if (languageManager == null) return key;
        return normalizeUserFacingCommandHints(languageManager.text(key, replacements));
    }

    public List<String> trList(String key, Object... replacements) {
        if (languageManager == null) return List.of(key);
        List<String> lines = languageManager.list(key, replacements);
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, normalizeUserFacingCommandHints(lines.get(i)));
        }
        return lines;
    }

    private String normalizeUserFacingCommandHints(String message) {
        if (message == null || message.isBlank()) return message;
        return message
                .replace("/mocnap rewards", "/mocnap server")
                .replace("/mocnap " + org.bukkit.ChatColor.COLOR_CHAR + "7",
                        "/mocnap canhan " + org.bukkit.ChatColor.COLOR_CHAR + "7");
    }

    public String trPlain(String key, Object... replacements) {
        return stripColor(tr(key, replacements));
    }

    public String stripColor(String message) {
        return org.bukkit.ChatColor.stripColor(message == null ? "" : message);
    }

    public String formatMoney(long amount) {
        return String.format("%,d", amount);
    }


    public boolean isDebugMode() {
        return logManager != null && logManager.isDebugEnabled();
    }

    public void logDebug(String message) {
        logDebug(message, null);
    }

    public void logDebug(String message, Throwable throwable) {
        if (logManager != null) {
            logManager.debug(message, throwable);
        }
    }

    public void logInfo(String message) {
        if (logManager != null) {
            logManager.info(message);
        } else {
            getLogger().info(message);
        }
    }

    public void logWarning(String message) {
        logWarning(message, null);
    }

    public void logWarning(String message, Throwable throwable) {
        if (logManager != null) {
            logManager.warning(message, throwable);
        } else if (throwable == null) {
            getLogger().warning(message);
        } else {
            getLogger().log(Level.WARNING, message, throwable);
        }
    }

    public void logSevere(String message) {
        logSevere(message, null);
    }

    public void logSevere(String message, Throwable throwable) {
        if (logManager != null) {
            logManager.severe(message, throwable);
        } else if (throwable == null) {
            getLogger().severe(message);
        } else {
            getLogger().log(Level.SEVERE, message, throwable);
        }
    }

    public java.net.HttpURLConnection openJsonPostConnection(String url) throws IOException {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(WEBHOOK_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(WEBHOOK_READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        return connection;
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    public static SotarPayments getInstance() { return instance; }
    public PlatformScheduler getPlatformScheduler() { return platformScheduler; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public GuiConfigManager getGuiConfigManager() { return guiConfigManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public MilestoneManager getMilestoneManager() { return milestoneManager; }
    public BankPaymentManager getBankPaymentManager() { return bankPaymentManager; }
    public LogManager getLogManager() { return logManager; }
    public AdminGUIManager getAdminGUIManager() { return adminGUIManager; }
    public CardSessionManager getCardSessionManager() { return cardSessionManager; }
    public CardFlowManager getCardFlowManager() { return cardFlowManager; }
    public CardListener getCardListener() { return cardListener; }
    public CardRateManager getCardRateManager() { return cardRateManager; }
    public CardChargingService getCardChargingService() { return cardChargingService; }
    public TransactionWebhookManager getTransactionWebhookManager() { return transactionWebhookManager; }
    public StoreManager getStoreManager() { return storeManager; }
    public MilestoneGUIManager getMilestoneGuiManager() { return milestoneGuiManager; }
    public PaymentGUIManager getPaymentGuiManager() { return paymentGuiManager; }
    public ModernPaymentInterfaceManager getModernPaymentInterfaceManager() { return modernPaymentInterfaceManager; }
    public Map<UUID, CardRequest> getPendingCards() { return pendingCards; }
}
