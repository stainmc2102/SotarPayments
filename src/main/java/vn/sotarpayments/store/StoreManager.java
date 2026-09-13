package vn.sotarpayments.store;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.scheduler.PlatformScheduler.ScheduledTask;
import vn.sotarpayments.napbank.manager.BankPaymentManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class StoreManager {
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String PRODUCT_SELECT_ID = "kp_store_product";
    private static final String CHECKOUT_MODAL_PREFIX = "kp_store_checkout:";
    private static final String CANCEL_BUTTON_PREFIX = "kp_store_cancel:";
    private static final String RERUN_BUTTON_PREFIX = "kp_store_rerun:";
    private static final String RATING_BUTTON_PREFIX = "kp_store_rate:";
    private static final int DISCORD_SELECT_LIMIT = 25;
    private static final int STOREFRONT_PREVIEW_LIMIT = 18;
    private static final long JDA_SHUTDOWN_TIMEOUT_SECONDS = 8L;
    private static final long JDA_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final long LIFECYCLE_SHUTDOWN_TIMEOUT_SECONDS = 15L;

    private final SotarPayments plugin;
    private final File storeFile;
    private final File ordersFile;
    private final Object ordersFileLock = new Object();
    private final ConcurrentHashMap<String, StoreOrder> orders = new ConcurrentHashMap<>();
    private final StoreServerListener serverListener;
    private final AtomicLong botGeneration = new AtomicLong();
    private final ExecutorService botLifecycleExecutor;

    private volatile YamlConfiguration storeConfig;
    private volatile List<StoreItem> products = List.of();
    private volatile DiscordBotSession botSession;
    private volatile DiscordBotSession startingBotSession;
    private volatile boolean enabled;
    private volatile boolean botReady;
    private volatile boolean lifecycleClosed;
    private volatile ScheduledTask waitingOrderRetryTask;

    public StoreManager(SotarPayments plugin) {
        this.plugin = plugin;
        this.storeFile = new File(plugin.getDataFolder(), "store.yml");
        this.ordersFile = new File(plugin.getDataFolder(), "store-orders.yml");
        this.serverListener = new StoreServerListener();
        this.botLifecycleExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SotarPayments-Discord-Lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        ensureStoreFile();
        reloadConfigOnly();
        loadStoredOrders();
        reconcileStoredOrdersOnStartup();
        plugin.getServer().getPluginManager().registerEvents(this.serverListener, plugin);
    }

    public synchronized void reload() {
        if (lifecycleClosed) {
            plugin.logWarning("Discord Store cannot reload because its lifecycle has already been closed.");
            return;
        }
        long generation = botGeneration.incrementAndGet();
        this.enabled = false;
        this.botReady = false;
        pauseOpenPaymentOrders();
        stopWaitingOrderRetryTask();

        ensureStoreFile();
        reloadConfigOnly();

        this.enabled = storeConfig.getBoolean("settings.enabled", false);
        if (!enabled) {
            queueBotReplacement(generation, null, null, false);
            plugin.logDebug("Discord Store is disabled in store.yml.");
            return;
        }

        String token = getToken();
        String activity = storeConfig.getString("settings.activity", "AUTO BUY | SotarPayments");
        if (token.isBlank() || token.contains("DAN_TOKEN_BOT")) {
            queueBotReplacement(generation, null, null, false);
            plugin.logWarning("Discord Store is enabled but settings.bot-token is empty or still a placeholder.");
            return;
        }

        queueBotReplacement(generation, token, activity, true);
    }

    public void shutdown() {
        synchronized (this) {
            if (lifecycleClosed) return;
            lifecycleClosed = true;
            botGeneration.incrementAndGet();
            this.enabled = false;
            this.botReady = false;
        }
        pauseOpenPaymentOrders();
        stopWaitingOrderRetryTask();
        HandlerList.unregisterAll(serverListener);

        Future<?> stopFuture = null;
        try {
            stopFuture = botLifecycleExecutor.submit(this::closeCurrentBotSession);
            stopFuture.get(LIFECYCLE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            closeCurrentBotSession();
        } catch (TimeoutException e) {
            plugin.logWarning("Timed out while waiting for the Discord Store bot to stop; forcing lifecycle executor shutdown.");
            if (stopFuture != null) stopFuture.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (stopFuture != null) stopFuture.cancel(true);
        } catch (ExecutionException e) {
            plugin.logWarning("Discord Store shutdown failed: " + rootCauseMessage(e), e.getCause());
        } finally {
            botLifecycleExecutor.shutdownNow();
            try {
                if (!botLifecycleExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                    plugin.logWarning("Discord Store lifecycle thread did not terminate within the safety timeout.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Covers a rare timeout where the queued stop task never began. The generation
            // guard prevents an in-flight startup task from publishing another live session.
            closeCurrentBotSession();
        }
    }

    private void pauseOpenPaymentOrders() {
        for (StoreOrder order : orders.values()) {
            synchronized (order) {
                if (order.status != OrderStatus.PENDING) continue;
            }
            order.cancelTask();
            plugin.getBankPaymentManager().cancelExternalTransaction(order.payment.orderCode());
        }
    }

    private void queueBotReplacement(long generation, String token, String activity, boolean startRequested) {
        try {
            botLifecycleExecutor.execute(() -> replaceBotSession(generation, token, activity, startRequested));
        } catch (RejectedExecutionException e) {
            if (!lifecycleClosed) {
                plugin.logWarning("Cannot queue Discord Store lifecycle update: executor is unavailable.", e);
            }
        }
    }

    private void replaceBotSession(long generation, String token, String activity, boolean startRequested) {
        closeCurrentBotSession();
        if (!startRequested || !isRequestedBotGeneration(generation)) return;

        DiscordBotSession session = new DiscordBotSession(generation);
        JDA newJda = null;
        boolean attached = false;
        boolean accepted = false;
        try {
            synchronized (this) {
                if (!isRequestedBotGeneration(generation)) return;
                startingBotSession = session;
            }

            newJda = JDABuilder.createDefault(token)
                    .setActivity(Activity.watching(activity))
                    .setEnableShutdownHook(false)
                    .setCallbackPool(session.callbackPool, true)
                    .setEventPool(session.eventPool, true)
                    .setGatewayPool(session.gatewayPool, true)
                    .setRateLimitScheduler(session.rateLimitScheduler, true)
                    .setRateLimitElastic(session.rateLimitElastic, true)
                    .addEventListeners(session.listener)
                    .build();

            synchronized (this) {
                if (startingBotSession == session) {
                    startingBotSession = null;
                    session.jda.set(newJda);
                    attached = true;
                }
                if (attached && isRequestedBotGeneration(generation)) {
                    this.botSession = session;
                    accepted = true;
                }
            }

            if (accepted) {
                plugin.logInfo("Discord Store bot is starting. Use /taokenhbanhang after the bot becomes ready.");
            }
        } catch (Exception e) {
            synchronized (this) {
                if (startingBotSession == session) {
                    startingBotSession = null;
                }
                if (botGeneration.get() == generation) {
                    if (botSession == session) botSession = null;
                    this.botReady = false;
                }
            }
            if (isRequestedBotGeneration(generation)) {
                plugin.logWarning("Cannot start Discord Store bot: " + e.getMessage(), e);
            }
        } finally {
            if (!accepted || botSession != session) {
                if (newJda != null && !attached) {
                    closeJdaInstance(newJda, session.listener);
                }
                closeBotSession(session);
            }
        }
    }

    private boolean isRequestedBotGeneration(long generation) {
        return !lifecycleClosed && enabled && botGeneration.get() == generation;
    }

    private JDA currentJda() {
        DiscordBotSession current = botSession;
        return current == null ? null : current.jda.get();
    }

    private void closeCurrentBotSession() {
        DiscordBotSession current;
        DiscordBotSession starting;
        synchronized (this) {
            current = this.botSession;
            starting = this.startingBotSession;
            this.botReady = false;
            this.botSession = null;
            this.startingBotSession = null;
        }
        closeBotSession(current);
        if (starting != current) {
            closeBotSession(starting);
        }
    }

    private void closeBotSession(DiscordBotSession session) {
        if (session == null) return;
        JDA current = session.jda.getAndSet(null);
        closeJdaInstance(current, session.listener);
        shutdownJdaExecutors(session);
    }

    private void closeJdaInstance(JDA current, StoreDiscordListener listener) {
        if (current == null) return;

        try {
            if (listener != null) current.removeEventListener(listener);
        } catch (Exception e) {
            plugin.logDebug("Cannot detach Discord Store listener during shutdown: " + e.getMessage(), e);
        }
        try {
            current.setAutoReconnect(false);
        } catch (Exception ignored) {
        }
        try {
            current.cancelRequests();
        } catch (Exception ignored) {
        }
        try {
            current.shutdown();
            if (!current.awaitShutdown(JDA_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                plugin.logWarning("Discord Store JDA graceful shutdown timed out after "
                        + JDA_SHUTDOWN_TIMEOUT_SECONDS + " seconds; forcing the remaining connection closed.");
                current.shutdownNow();
                if (!current.awaitShutdown(Math.max(2L, JDA_SHUTDOWN_TIMEOUT_SECONDS / 2L), TimeUnit.SECONDS)) {
                    plugin.logWarning("Discord Store JDA did not fully stop after the forced shutdown timeout.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                current.shutdownNow();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            plugin.logDebug("Discord Store JDA shutdown reported an error: " + e.getMessage(), e);
        } finally {
            closeJdaHttpClient(current);
        }
    }

    private void shutdownJdaExecutors(DiscordBotSession session) {
        if (!session.executorsClosed.compareAndSet(false, true)) return;

        List<ExecutorService> executors = session.executors();
        for (ExecutorService executor : executors) {
            try {
                executor.shutdownNow();
            } catch (Exception e) {
                plugin.logDebug("Cannot stop a Discord Store executor: " + e.getMessage(), e);
            }
        }

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(JDA_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
        boolean interrupted = false;
        List<String> stillRunning = new ArrayList<>();
        for (int index = 0; index < executors.size(); index++) {
            ExecutorService executor = executors.get(index);
            if (executor.isTerminated()) continue;

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                stillRunning.add(session.executorNames.get(index));
                continue;
            }
            try {
                if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    stillRunning.add(session.executorNames.get(index));
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }

        stillRunning.clear();
        for (int index = 0; index < executors.size(); index++) {
            if (!executors.get(index).isTerminated()) {
                stillRunning.add(session.executorNames.get(index));
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (!stillRunning.isEmpty()) {
            plugin.logWarning("Discord Store executors did not fully stop within "
                    + JDA_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS + " seconds: "
                    + String.join(", ", stillRunning) + ".");
        }
    }

    private void closeJdaHttpClient(JDA current) {
        okhttp3.OkHttpClient httpClient = null;
        try {
            httpClient = current.getHttpClient();
            httpClient.dispatcher().cancelAll();
            httpClient.dispatcher().executorService().shutdownNow();
            if (!httpClient.dispatcher().executorService().awaitTermination(2L, TimeUnit.SECONDS)) {
                plugin.logWarning("Discord Store HTTP dispatcher did not stop within the safety timeout.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.logDebug("Discord Store HTTP cleanup reported an error: " + e.getMessage(), e);
        } finally {
            if (httpClient != null) httpClient.connectionPool().evictAll();
        }
    }

    private void queueReadyEvent(JDA candidate, long generation) {
        try {
            botLifecycleExecutor.execute(() -> handleReady(candidate, generation));
        } catch (RejectedExecutionException ignored) {
            // Plugin shutdown owns and closes the session; never block a JDA callback thread.
        }
    }

    private void handleReady(JDA candidate, long generation) {
        synchronized (this) {
            if (!isCurrentBotSession(candidate, generation)) return;
            botReady = true;
        }
        registerStoreSlashCommands(candidate, generation);
        startWaitingOrderRetryTask();
        resumeRecoveredOrders(candidate, generation);
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public boolean publishStorefront(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return publishStorefront(null, message -> runSenderMessage(sender, message));
    }

    private boolean publishStorefront(String expectedGuildId, Consumer<String> resultHandler) {
        Objects.requireNonNull(resultHandler, "resultHandler");
        if (!enabled) {
            resultHandler.accept(plugin.tr("store.disabled"));
            return false;
        }
        if (products.isEmpty()) {
            resultHandler.accept(plugin.tr("store.no-products"));
            return false;
        }

        JDA current = currentJda();
        if (current == null || !botReady) {
            resultHandler.accept(plugin.tr("store.bot-not-ready"));
            return false;
        }

        String channelId = storeConfig.getString("settings.storefront-channel-id", "").trim();
        if (channelId.isBlank() || channelId.contains("DAN_ID")) {
            resultHandler.accept(plugin.tr("store.channel-missing"));
            return false;
        }
        if (!isValidDiscordId(channelId)) {
            resultHandler.accept(plugin.tr("store.channel-not-found", "channel", channelId));
            return false;
        }

        GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            resultHandler.accept(plugin.tr("store.channel-not-found", "channel", channelId));
            return false;
        }
        if (expectedGuildId != null && !expectedGuildId.equals(channel.getGuild().getId())) {
            resultHandler.accept(plugin.tr("store.publish-failed", "reason",
                    "Kênh bán hàng không thuộc máy chủ Discord đang gọi lệnh"));
            return false;
        }
        Member selfMember = channel.getGuild().getSelfMember();
        Permission sendPermission = channel instanceof ThreadChannel
                ? Permission.MESSAGE_SEND_IN_THREADS
                : Permission.MESSAGE_SEND;
        if (!selfMember.hasPermission(channel,
                Permission.VIEW_CHANNEL,
                sendPermission,
                Permission.MESSAGE_EMBED_LINKS)) {
            resultHandler.accept(plugin.tr("store.publish-failed", "reason",
                    "Bot thiếu quyền View Channel, " + sendPermission.getName()
                            + " hoặc Embed Links trong #" + channel.getName()));
            return false;
        }

        MessageEmbed embed = buildStorefrontEmbed();
        StringSelectMenu menu = buildProductMenu();
        channel.sendMessageEmbeds(embed)
                .setComponents(ActionRow.of(menu))
                .queue(message -> resultHandler.accept(plugin.tr("store.publish-success", "channel", channel.getName())),
                        failure -> resultHandler.accept(plugin.tr("store.publish-failed", "reason", failure.getMessage())));
        return true;
    }

    public boolean reloadFromCommand(CommandSender sender) {
        reload();
        sender.sendMessage(plugin.tr("store.reload-success"));
        return true;
    }

    public void sendStatus(CommandSender sender) {
        JDA current = currentJda();
        String bot = current == null ? plugin.tr("general.disabled") : current.getStatus().name();
        sender.sendMessage(plugin.tr("store.status",
                "enabled", enabled ? plugin.tr("general.enabled") : plugin.tr("general.disabled"),
                "bot", bot,
                "categories", "0",
                "products", String.valueOf(countProducts()),
                "pending", String.valueOf(countActiveOrders())));
    }

    public int getOrderTimeoutSeconds() {
        return Math.max(60, storeConfig.getInt("settings.order-timeout-seconds", 600));
    }

    public int getPollEverySeconds() {
        return Math.max(5, storeConfig.getInt("settings.poll-every-seconds", 10));
    }

    public int getWaitingPlayerRetrySeconds() {
        return Math.max(5, storeConfig.getInt("settings.waiting-player-retry-seconds", 10));
    }

    private void ensureStoreFile() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.logWarning("Cannot create plugin data folder for store.yml.");
        }
        if (!storeFile.exists()) {
            try {
                plugin.saveResource("store.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.logWarning("Bundled store.yml is missing from plugin resources.", e);
            }
        }
    }

    private void reloadConfigOnly() {
        this.storeConfig = YamlConfiguration.loadConfiguration(storeFile);
        this.products = loadProducts();
    }

    private List<StoreItem> loadProducts() {
        List<StoreItem> loaded = new ArrayList<>();
        ConfigurationSection productSection = storeConfig.getConfigurationSection("products");
        if (productSection != null) {
            loaded.addAll(loadProductsFromSection(productSection, ""));
        }

        if (loaded.isEmpty()) {
            ConfigurationSection legacyCategories = storeConfig.getConfigurationSection("categories");
            if (legacyCategories != null) {
                plugin.logWarning("store.yml is using legacy categories.*.products format. Please migrate to the new top-level products: section.");
                for (String categoryKey : legacyCategories.getKeys(false)) {
                    ConfigurationSection categorySection = legacyCategories.getConfigurationSection(categoryKey);
                    if (categorySection == null || !categorySection.getBoolean("enabled", true)) continue;
                    if (!isSafeKey(categoryKey)) {
                        plugin.logWarning("Skipped Discord Store legacy category with unsafe key: " + categoryKey);
                        continue;
                    }
                    ConfigurationSection productsSection = categorySection.getConfigurationSection("products");
                    if (productsSection != null) {
                        loaded.addAll(loadProductsFromSection(productsSection, categoryKey + "__"));
                    }
                }
            }
        }

        List<StoreItem> unique = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (StoreItem item : loaded) {
            if (ids.contains(item.id())) {
                plugin.logWarning("Skipped duplicate Discord Store product id: " + item.id());
                continue;
            }
            ids.add(item.id());
            unique.add(item);
        }

        unique.sort(Comparator.comparing(StoreItem::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(unique);
    }

    private List<StoreItem> loadProductsFromSection(ConfigurationSection section, String idPrefix) {
        if (section == null) return List.of();
        List<StoreItem> loaded = new ArrayList<>();
        String prefix = idPrefix == null ? "" : idPrefix;
        for (String itemKey : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(itemKey);
            if (itemSection == null || !itemSection.getBoolean("enabled", true)) continue;
            if (!isSafeKey(itemKey)) {
                plugin.logWarning("Skipped Discord Store product with unsafe key: " + itemKey);
                continue;
            }

            String itemId = prefix + itemKey;
            if (!isSafeProductId(itemId)) {
                plugin.logWarning("Skipped Discord Store product because generated id is too long or unsafe: " + itemId);
                continue;
            }

            long price = Math.max(0L, itemSection.getLong("price", 0L));
            List<String> commands = itemSection.getStringList("commands");
            StoreItem item = new StoreItem(
                    itemId,
                    itemKey,
                    limit(itemSection.getString("name", itemKey), 90),
                    price,
                    limit(itemSection.getString("detail", ""), 900),
                    itemSection.getBoolean("require-online", true),
                    List.copyOf(commands)
            );
            loaded.add(item);
        }
        return loaded;
    }

    private MessageEmbed buildStorefrontEmbed() {
        EmbedBuilder builder = baseEmbed("settings.storefront-embed", "🛒 AUTO BUY | SotarStore", 5814783);
        List<String> description = storeConfig.getStringList("settings.storefront-embed.description");
        if (description.isEmpty()) {
            description = List.of(
                    "Bot mua hàng tự động 24/7",
                    "Thanh toán xong bot sẽ giao hàng khi người chơi đang online.",
                    "Chọn mặt hàng bên dưới để bắt đầu."
            );
        }

        StringBuilder body = new StringBuilder();
        for (String line : description) {
            body.append(line).append('\n');
        }
        body.append('\n').append("**Tổng mặt hàng:** `").append(countProducts()).append('`');
        builder.setDescription(body.toString());

        StringBuilder preview = new StringBuilder();
        int shown = 0;
        for (StoreItem item : products) {
            if (shown >= STOREFRONT_PREVIEW_LIMIT) break;
            String line = "• **" + item.name() + "** — `" + plugin.formatMoney(item.price()) + " " + plugin.trPlain("general.currency") + "`";
            String detail = item.detail().isBlank() ? "" : "\n  ↳ " + item.detail();
            if (preview.length() + line.length() + detail.length() + 2 > 1000) break;
            preview.append(line).append(detail).append('\n');
            shown++;
        }
        if (shown < products.size()) {
            preview.append("... và ").append(products.size() - shown).append(" mặt hàng khác trong menu chọn.");
        }
        if (!preview.isEmpty()) {
            builder.addField("Mặt hàng", preview.toString(), false);
        }
        return builder.build();
    }

    private MessageEmbed buildOrderEmbed(StoreOrder order) {
        EmbedBuilder builder = baseEmbed("settings.order-embed", "🧾 Đơn hàng #" + order.orderId, 65280);
        String title = storeConfig.getString("settings.order-embed.title", "🧾 Đơn hàng #{order_id}")
                .replace("{order_id}", order.orderId)
                .replace("{item}", order.item.name());
        builder.setTitle(title);
        builder.setDescription("Quét mã QR bên dưới hoặc chuyển khoản theo thông tin. Bot sẽ tự kiểm tra thanh toán và chỉ giao hàng khi người chơi đang online trong máy chủ.");
        addOrderFields(builder, order);
        if (!order.payment.qrImageUrl().isBlank()) {
            builder.setImage(order.payment.qrImageUrl());
        }
        String footer = storeConfig.getString("settings.order-embed.footer", "Vui lòng chuyển đúng số tiền và đúng nội dung.");
        builder.setFooter(footer);
        return builder.build();
    }

    private MessageEmbed buildDeliveryRunningEmbed(StoreOrder order) {
        EmbedBuilder builder = baseEmbed("settings.order-embed", "🔄 Đang giao hàng", 5814783);
        builder.setTitle("🔄 Đang kiểm tra và giao đơn #" + order.orderId);
        builder.setDescription("Hệ thống đang kiểm tra người chơi trong máy chủ và chạy lệnh giao hàng. Vui lòng chờ trong giây lát.");
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildWaitingPlayerEmbed(StoreOrder order) {
        EmbedBuilder builder = baseEmbed("settings.waiting-embed", "⏳ Đơn hàng đang treo", 0xF1C40F);
        builder.setTitle("⏳ Đơn hàng đang treo #" + order.orderId);
        builder.setDescription("Thanh toán đã thành công nhưng người chơi `" + order.playerName + "` chưa online trong cụm máy chủ.\n\n"
                + "Vui lòng vào đúng máy chủ bằng tên nhân vật này. Hệ thống sẽ tự thử giao lại khi thấy người chơi online; "
                + "bạn cũng có thể nhấn **Chạy lại đơn** để kiểm tra ngay. "
                + "Hệ thống chỉ duyệt đơn khi lệnh giao hàng chạy thành công.");
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildSuccessEmbed(StoreOrder order) {
        EmbedBuilder builder = baseEmbed("settings.success-embed", "✅ Đơn hàng đã giao", 65280);
        if (order.ratingStars > 0) {
            builder.setDescription("Đơn hàng đã thanh toán và hệ thống đã giao hàng thành công.\n\n"
                    + "Cảm ơn bạn đã đánh giá đơn hàng: **" + formatStars(order.ratingStars) + "** (`" + order.ratingStars + "/5`).");
        } else {
            builder.setDescription("Đơn hàng đã thanh toán và hệ thống đã giao hàng thành công.\n\n"
                    + "Bạn có thể đánh giá trải nghiệm mua hàng bằng các nút sao bên dưới.");
        }
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildCanceledEmbed(StoreOrder order) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Đơn hàng đã hủy")
                .setColor(0x95A5A6)
                .setTimestamp(Instant.now())
                .setDescription(order.lastMessage == null || order.lastMessage.isBlank()
                        ? "Bạn đã hủy đơn hàng này. Nếu đã chuyển khoản, vui lòng liên hệ staff để được đối soát."
                        : order.lastMessage);
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildExpiredEmbed(StoreOrder order) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Đơn hàng đã hết hạn")
                .setColor(0xE67E22)
                .setTimestamp(Instant.now())
                .setDescription("Hệ thống chưa ghi nhận thanh toán trong thời gian quy định.");
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildErrorEmbed(StoreOrder order, String reason) {
        EmbedBuilder builder = baseEmbed("settings.error-embed", "⚠️ Đơn hàng cần staff xử lý", 15158332);
        builder.setDescription(reason);
        addOrderFields(builder, order);
        return builder.build();
    }

    private MessageEmbed buildAdminOrderEmbed(StoreOrder order) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("🔎 Kiểm tra đơn #" + order.orderId)
                .setColor(statusColor(order.status))
                .setTimestamp(Instant.now())
                .setDescription(order.lastMessage == null || order.lastMessage.isBlank()
                        ? "Thông tin đơn Auto Buy trong SotarPayments."
                        : order.lastMessage);
        addOrderFields(builder, order);
        builder.addField("Người mua Discord", "<@" + order.discordUserId + "> (`" + order.discordUserId + "`)", false);
        builder.addField("Thời gian", "Tạo: " + discordTimestamp(order.createdAtMillis)
                + "\nThanh toán: " + discordTimestamp(order.paidAtMillis)
                + "\nHoàn tất: " + discordTimestamp(order.completedAtMillis), false);
        if (order.ratingStars > 0) {
            builder.addField("Đánh giá", formatStars(order.ratingStars) + " (`" + order.ratingStars + "/5`) - " + discordTimestamp(order.ratedAtMillis), false);
        }
        return builder.build();
    }

    private MessageEmbed buildLogEmbed(StoreOrder order, String title, int color, String description) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(title)
                .setColor(color)
                .setTimestamp(Instant.now())
                .setDescription(description);
        addOrderFields(builder, order);
        return builder.build();
    }

    private EmbedBuilder baseEmbed(String path, String defaultTitle, int defaultColor) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(storeConfig.getString(path + ".title", defaultTitle))
                .setColor(storeConfig.getInt(path + ".color", defaultColor))
                .setTimestamp(Instant.now());
        String footer = storeConfig.getString(path + ".footer", "");
        if (!footer.isBlank()) {
            builder.setFooter(footer);
        }
        return builder;
    }

    private void addOrderFields(EmbedBuilder builder, StoreOrder order) {
        builder.addField("Mặt hàng", order.item.name(), true);
        builder.addField("Người chơi", "`" + order.playerName + "`", true);
        builder.addField("Số tiền", "`" + plugin.formatMoney(order.item.price()) + " " + plugin.trPlain("general.currency") + "`", true);
        builder.addField("Mã đơn", "`" + order.orderId + "`", true);
        builder.addField("Mã thanh toán", "`" + order.payment.orderCode() + "`", true);
        builder.addField("Trạng thái", "`" + displayStatus(order.status) + "`", true);
        builder.addField("Nội dung CK", "`" + order.payment.description() + "`", true);
        builder.addField("Provider", "`" + order.payment.provider() + "`", true);
        if (!order.payment.bankName().isBlank() || !order.payment.accountNumber().isBlank()) {
            String value = "Ngân hàng: `" + emptyAsDash(order.payment.bankName()) + "`\n"
                    + "STK: `" + emptyAsDash(order.payment.accountNumber()) + "`\n"
                    + "Chủ TK: `" + emptyAsDash(order.payment.accountName()) + "`";
            builder.addField("Thông tin nhận tiền", value, false);
        }
        if (!order.item.detail().isBlank()) {
            builder.addField("Chi tiết", order.item.detail(), false);
        }
    }

    private StringSelectMenu buildProductMenu() {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PRODUCT_SELECT_ID)
                .setPlaceholder(storeConfig.getString("settings.storefront-embed.select-placeholder", "Chọn mặt hàng..."));
        for (StoreItem item : products.stream().limit(DISCORD_SELECT_LIMIT).toList()) {
            String description = plugin.formatMoney(item.price()) + " " + plugin.trPlain("general.currency");
            if (!item.detail().isBlank()) {
                description += " • " + item.detail();
            }
            menu.addOptions(SelectOption.of(limit(item.name(), 100), item.id()).withDescription(limit(description, 100)));
        }
        return menu.build();
    }

    private MessageEmbed buildCurrentOrderEmbed(StoreOrder order) {
        return switch (order.status) {
            case PENDING -> buildOrderEmbed(order);
            case PAID, DELIVERING -> buildDeliveryRunningEmbed(order);
            case WAITING_PLAYER -> buildWaitingPlayerEmbed(order);
            case DELIVERED -> buildSuccessEmbed(order);
            case EXPIRED -> buildExpiredEmbed(order);
            case ERROR -> buildErrorEmbed(order, order.lastMessage == null || order.lastMessage.isBlank()
                    ? "Đơn hàng cần staff kiểm tra."
                    : order.lastMessage);
            case CANCELED -> buildCanceledEmbed(order);
        };
    }

    private List<ActionRow> buildOrderComponents(StoreOrder order) {
        if (order == null) return List.of();
        return switch (order.status) {
            case PENDING -> List.of(ActionRow.of(Button.danger(CANCEL_BUTTON_PREFIX + order.orderId, "Hủy đơn")));
            case WAITING_PLAYER -> List.of(ActionRow.of(Button.primary(RERUN_BUTTON_PREFIX + order.orderId, "🔁 Chạy lại đơn")));
            case DELIVERED -> order.ratingStars <= 0 ? buildRatingRows(order) : List.of();
            default -> List.of();
        };
    }

    private List<ActionRow> buildRatingRows(StoreOrder order) {
        return List.of(ActionRow.of(
                Button.secondary(RATING_BUTTON_PREFIX + order.orderId + ":1", "1 ★"),
                Button.secondary(RATING_BUTTON_PREFIX + order.orderId + ":2", "2 ★"),
                Button.secondary(RATING_BUTTON_PREFIX + order.orderId + ":3", "3 ★"),
                Button.secondary(RATING_BUTTON_PREFIX + order.orderId + ":4", "4 ★"),
                Button.success(RATING_BUTTON_PREFIX + order.orderId + ":5", "5 ★")
        ));
    }

    private void createOrder(InteractionHook hook, User buyer, StoreItem item, String playerName) {
        String discordUserId = buyer == null ? "" : buyer.getId();
        String discordName = buyer == null ? "Unknown" : buyer.getName();
        if (item.price() <= 0) {
            hook.editOriginal("Mặt hàng này chưa có giá hợp lệ. Vui lòng báo staff.").queue();
            return;
        }
        if (item.commands().isEmpty()) {
            hook.editOriginal("Mặt hàng này chưa cấu hình lệnh giao hàng. Vui lòng báo staff.").queue();
            sendErrorLog(null, "Sản phẩm chưa có commands: " + item.key());
            return;
        }

        plugin.getBankPaymentManager().createPaymentOrder(playerName, item.price(), new BankPaymentManager.PaymentOrderCallback() {
            @Override
            public void onSuccess(BankPaymentManager.PaymentOrder paymentOrder) {
                String orderId = createOrderId();
                StoreOrder order = new StoreOrder(orderId, discordUserId, discordName, playerName, item, paymentOrder, hook, System.currentTimeMillis());
                orders.put(order.orderId.toUpperCase(Locale.ROOT), order);
                saveOrders();

                startOrderPaymentPolling(order);

                hook.editOriginalEmbeds(buildOrderEmbed(order))
                        .setComponents(buildOrderComponents(order))
                        .queue(null, failure -> plugin.logDebug("Cannot edit Discord order message: " + failure.getMessage(), failure));
                sendBuyerOrderMessage(order, buyer);
            }

            @Override
            public void onFailure(String message) {
                hook.editOriginal("Không thể tạo QR thanh toán: " + plugin.stripColor(message)).queue();
                sendErrorLog(null, "Không thể tạo QR cho đơn Discord của " + discordName + ": " + plugin.stripColor(message));
            }
        });
    }

    private void sendBuyerOrderMessage(StoreOrder order, User buyer) {
        if (order == null || buyer == null) return;
        buyer.openPrivateChannel().queue(channel ->
                channel.sendMessageEmbeds(buildCurrentOrderEmbed(order))
                        .setComponents(buildOrderComponents(order))
                        .queue(message -> {
                            order.orderMessageId = message.getId();
                            saveOrders();
                            plugin.logDebug("Sent Discord Store order " + order.orderId + " to buyer DM.");
                        }, failure -> {
                            order.lastMessage = appendStatusNote(order.lastMessage,
                                    "Không thể gửi đơn qua DM. Người mua cần mở tin nhắn riêng với bot hoặc xem đơn tạm trong Discord.");
                            saveOrders();
                            plugin.logDebug("Cannot send Discord Store order DM for " + order.orderId + ": " + failure.getMessage(), failure);
                        }),
                failure -> {
                    order.lastMessage = appendStatusNote(order.lastMessage,
                            "Không thể mở DM với người mua. Người mua cần bật tin nhắn riêng từ server Discord.");
                    saveOrders();
                    plugin.logDebug("Cannot open buyer DM for Discord Store order " + order.orderId + ": " + failure.getMessage(), failure);
                });
    }

    private void startOrderPaymentPolling(StoreOrder order) {
        if (order == null) return;
        synchronized (order) {
            if (order.status != OrderStatus.PENDING) return;
        }

        plugin.getBankPaymentManager().restoreExternalTransaction(
                order.payment.orderCode(),
                order.payment.provider(),
                order.payment.description(),
                order.createdAtMillis
        );
        ScheduledTask task = plugin.getBankPaymentManager().startExternalPolling(
                order.discordUserId + ":" + order.orderId,
                order.payment.orderCode(),
                order.payment.amount(),
                new BankPaymentManager.ExternalPaymentCallback() {
                    @Override
                    public boolean onPaid() {
                        return handlePaidOrder(order);
                    }

                    @Override
                    public void onExpired() {
                        handleExpiredOrder(order);
                    }
                }
        );
        order.attachPaymentTask(task);
    }

    private boolean claimPendingTransition(StoreOrder order,
                                           OrderStatus targetStatus,
                                           String message,
                                           long transitionTimeMillis) {
        if (order == null) return false;
        if (targetStatus != OrderStatus.PAID
                && targetStatus != OrderStatus.CANCELED
                && targetStatus != OrderStatus.EXPIRED) {
            throw new IllegalArgumentException("Unsupported pending-order transition: " + targetStatus);
        }
        synchronized (order) {
            boolean paidWonCancellationRace = targetStatus == OrderStatus.PAID
                    && order.status == OrderStatus.CANCELED;
            if (order.status != OrderStatus.PENDING && !paidWonCancellationRace) return false;
            order.lastMessage = Objects.toString(message, "");
            if (targetStatus == OrderStatus.PAID) {
                order.paidAtMillis = transitionTimeMillis;
                order.completedAtMillis = 0L;
            } else {
                order.completedAtMillis = transitionTimeMillis;
            }
            order.status = targetStatus;
            return true;
        }
    }

    private long claimDeliveryAttempt(StoreOrder order) {
        synchronized (order) {
            if (order.status != OrderStatus.PAID && order.status != OrderStatus.WAITING_PLAYER) {
                return 0L;
            }
            order.lastMessage = "Đang kiểm tra người chơi và chạy lệnh giao hàng.";
            order.deliveryAttempt++;
            if (order.deliveryAttempt <= 0L) order.deliveryAttempt = 1L;
            order.status = OrderStatus.DELIVERING;
            return order.deliveryAttempt;
        }
    }

    private boolean isCurrentDeliveryAttempt(StoreOrder order, long deliveryAttempt) {
        synchronized (order) {
            return order.status == OrderStatus.DELIVERING && order.deliveryAttempt == deliveryAttempt;
        }
    }

    private boolean completeDeliveryAttempt(StoreOrder order, long deliveryAttempt) {
        synchronized (order) {
            if (order.status != OrderStatus.DELIVERING || order.deliveryAttempt != deliveryAttempt) return false;
            order.completedAtMillis = System.currentTimeMillis();
            order.lastMessage = "Đơn hàng đã giao tự động thành công.";
            order.status = OrderStatus.DELIVERED;
            return true;
        }
    }

    private boolean handlePaidOrder(StoreOrder order) {
        if (!claimPendingTransition(order, OrderStatus.PAID,
                "Thanh toán đã được xác nhận. Hệ thống đang chuẩn bị giao hàng.",
                System.currentTimeMillis())) {
            return false;
        }
        try {
            order.cancelTask();
            saveOrders();
            if (enabled && botReady) {
                executeDelivery(order);
            }
        } catch (Exception exception) {
            plugin.logWarning("Paid Discord Store order " + order.orderId
                    + " was accepted but could not start delivery immediately; it remains PAID for recovery.", exception);
        }
        return true;
    }

    private void executeDelivery(StoreOrder order) {
        if (order == null) return;
        long deliveryAttempt = claimDeliveryAttempt(order);
        if (deliveryAttempt <= 0L) return;

        saveOrders();
        editOrderMessage(order, buildDeliveryRunningEmbed(order), buildOrderComponents(order));

        if (!order.item.requireOnline()) {
            plugin.getPlatformScheduler().runGlobal(() -> runDeliveryCommands(order, deliveryAttempt));
            return;
        }

        plugin.getPlatformScheduler().runGlobal(() -> {
            if (!isCurrentDeliveryAttempt(order, deliveryAttempt)) return;
            Player target = Bukkit.getPlayerExact(order.playerName);
            if (target == null) {
                waitForPlayer(order, deliveryAttempt);
                return;
            }
            Runnable retired = () -> plugin.getPlatformScheduler().runGlobal(
                    () -> waitForPlayer(order, deliveryAttempt));
            boolean scheduled = plugin.getPlatformScheduler().runPlayer(target, () -> {
                if (!isCurrentDeliveryAttempt(order, deliveryAttempt)) return;
                if (!target.isOnline()) {
                    retired.run();
                    return;
                }
                plugin.getPlatformScheduler().runGlobal(() -> runDeliveryCommands(order, deliveryAttempt));
            }, retired);
            if (!scheduled) {
                retired.run();
            }
        });
    }

    private void runDeliveryCommands(StoreOrder order, long deliveryAttempt) {
        if (!isCurrentDeliveryAttempt(order, deliveryAttempt)) return;
        try {
            List<String> failedCommands = new ArrayList<>();
            for (String template : order.item.commands()) {
                String command = applyPlaceholders(template, order);
                boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!result) {
                    failedCommands.add(command);
                }
            }

            if (!failedCommands.isEmpty()) {
                failDeliveryAttempt(order, deliveryAttempt,
                        "Một hoặc nhiều lệnh giao hàng trả về thất bại: `" + String.join("`, `", failedCommands) + "`");
                return;
            }

            if (!completeDeliveryAttempt(order, deliveryAttempt)) return;
            saveOrders();
            editOrderMessage(order, buildSuccessEmbed(order), buildOrderComponents(order));
            sendPurchaseLog(order);
            plugin.getLogManager().payment("STORE_SUCCESS: " + order.discordName + " bought " + order.item.name()
                    + " for player " + order.playerName + ", amount " + order.item.price() + " " + plugin.trPlain("general.currency") + ".");
        } catch (Exception e) {
            if (failDeliveryAttempt(order, deliveryAttempt, "Lỗi khi giao hàng: " + e.getMessage())) {
                plugin.logWarning("Discord Store delivery failed for order " + order.orderId, e);
            }
        }
    }

    private void waitForPlayer(StoreOrder order, long deliveryAttempt) {
        boolean shouldLog;
        synchronized (order) {
            if (order.status != OrderStatus.DELIVERING || order.deliveryAttempt != deliveryAttempt) return;
            order.lastMessage = "Thanh toán đã thành công nhưng người chơi chưa online. Đơn đang treo và sẽ tự giao khi người chơi online.";
            shouldLog = !order.waitingPlayerLogged;
            if (shouldLog) order.waitingPlayerLogged = true;
            order.status = OrderStatus.WAITING_PLAYER;
        }
        saveOrders();
        editOrderMessage(order, buildWaitingPlayerEmbed(order), buildOrderComponents(order));
        if (shouldLog) {
            sendWaitingPlayerLog(order);
        }
        plugin.getLogManager().payment("STORE_WAITING_PLAYER: order " + order.orderId + " paid but player " + order.playerName + " is offline.");
    }

    private void handleExpiredOrder(StoreOrder order) {
        if (!claimPendingTransition(order, OrderStatus.EXPIRED,
                "Đơn hàng hết hạn vì chưa ghi nhận thanh toán.",
                System.currentTimeMillis())) {
            return;
        }
        order.cancelTask();
        saveOrders();
        editOrderMessage(order, buildExpiredEmbed(order), List.of());
    }

    private boolean failDeliveryAttempt(StoreOrder order, long deliveryAttempt, String reason) {
        synchronized (order) {
            if (order.status != OrderStatus.DELIVERING || order.deliveryAttempt != deliveryAttempt) return false;
            order.completedAtMillis = System.currentTimeMillis();
            order.lastMessage = reason;
            order.status = OrderStatus.ERROR;
        }
        order.cancelTask();
        saveOrders();
        editOrderMessage(order, buildErrorEmbed(order, reason), List.of());
        sendErrorLog(order, reason);
        return true;
    }

    private void editOrderMessage(StoreOrder order, MessageEmbed embed, List<ActionRow> rows) {
        if (order == null) return;
        List<ActionRow> safeRows = rows == null ? List.of() : rows;
        editBuyerOrderMessage(order, embed, safeRows);
        editInteractionOrderMessage(order, embed, safeRows);
    }

    private void editInteractionOrderMessage(StoreOrder order, MessageEmbed embed, List<ActionRow> rows) {
        if (order == null || order.hook == null) return;
        order.hook.editOriginalEmbeds(embed)
                .setComponents(rows)
                .queue(null, failure -> plugin.logDebug("Cannot update Discord interaction order message: " + failure.getMessage(), failure));
    }

    private void editBuyerOrderMessage(StoreOrder order, MessageEmbed embed, List<ActionRow> rows) {
        if (order == null || order.orderMessageId == null || order.orderMessageId.isBlank()) return;
        if (order.discordUserId == null || order.discordUserId.isBlank()) return;
        if (!isValidDiscordId(order.discordUserId) || !isValidDiscordId(order.orderMessageId)) return;
        JDA current = currentJda();
        if (current == null) return;

        current.retrieveUserById(order.discordUserId).queue(user ->
                user.openPrivateChannel().queue(channel ->
                        channel.retrieveMessageById(order.orderMessageId).queue(message ->
                                        message.editMessageEmbeds(embed)
                                                .setComponents(rows)
                                                .queue(null, failure -> plugin.logDebug("Cannot edit Discord Store buyer message: " + failure.getMessage(), failure)),
                                failure -> plugin.logDebug("Cannot retrieve Discord Store buyer message " + order.orderMessageId + ": " + failure.getMessage(), failure)),
                        failure -> plugin.logDebug("Cannot open buyer DM for editing order " + order.orderId + ": " + failure.getMessage(), failure)),
                failure -> plugin.logDebug("Cannot retrieve Discord buyer for editing order " + order.orderId + ": " + failure.getMessage(), failure));
    }

    private void sendPurchaseLog(StoreOrder order) {
        String channelId = storeConfig.getString("settings.purchase-log-channel-id", "");
        sendLog(channelId, buildLogEmbed(order, "✅ Đơn hàng đã giao", 65280,
                "<@" + order.discordUserId + "> đã thanh toán và hệ thống đã giao hàng tự động."));
        sendAuditLog("Đơn hàng đã giao", "Mã đơn: `" + order.orderId + "`\nNgười mua: <@"
            + order.discordUserId + ">\nMặt hàng: **" + order.item.name() + "**", 65280);
    }

    private void sendFeedbackLog(StoreOrder order) {
        String channelId = storeConfig.getString("settings.feedback-channel-id", "");
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("⭐ Feedback đơn hàng")
                .setColor(0xF1C40F)
                .setTimestamp(Instant.now());
        builder.addField("Mặt hàng", order.item.name(), true);
        builder.addField("Số tiền", "`" + plugin.formatMoney(order.item.price()) + " " + plugin.trPlain("general.currency") + "`", true);
        builder.addField("Mã đơn", "`" + order.orderId + "`", true);
        builder.addField("Số sao", formatStars(order.ratingStars) + " (`" + order.ratingStars + "/5`)", false);
        sendLog(channelId, builder.build());
        sendAuditLog("Đã nhận feedback", "Mã đơn: `" + order.orderId + "`\nSố sao: `"
            + order.ratingStars + "/5`", 0xF1C40F);
    }

    private void sendWaitingPlayerLog(StoreOrder order) {
        String channelId = storeConfig.getString("settings.error-log-channel-id", "");
        MessageEmbed embed = buildLogEmbed(order, "⏳ Đơn hàng đang treo", 0xF1C40F,
                "<@" + order.discordUserId + "> đã thanh toán nhưng người chơi chưa online. "
                        + "Hệ thống sẽ tự giao lại khi người chơi online; có thể nhấn **Chạy lại đơn** để kiểm tra ngay.");
        sendLog(channelId, embed, List.of(ActionRow.of(Button.primary(RERUN_BUTTON_PREFIX + order.orderId, "🔁 Chạy lại đơn"))));
        sendAuditLog("Đơn chờ người chơi online", "Mã đơn: `" + order.orderId + "`\nNgười chơi: `"
            + order.playerName + "`", 0xF1C40F);
    }

    private void sendErrorLog(StoreOrder order, String reason) {
        String channelId = storeConfig.getString("settings.error-log-channel-id", "");
        MessageEmbed embed;
        if (order == null) {
            embed = new EmbedBuilder()
                    .setTitle("⚠️ Store cần xử lý")
                    .setColor(15158332)
                    .setTimestamp(Instant.now())
                    .setDescription(reason)
                    .build();
        } else {
            embed = buildLogEmbed(order, "⚠️ Đơn hàng cần xử lý", 15158332, reason + "\nNgười mua: <@" + order.discordUserId + ">");
        }
        sendLog(channelId, embed);
        sendAuditLog("Lỗi Discord Store", limitAudit(reason), 15158332);
    }

    private void sendAuditLog(String title, String description, int color) {
        String channelId = storeConfig.getString("settings.audit-log-channel-id", "");
        if (channelId == null || channelId.isBlank() || channelId.contains("DAN_ID")) return;
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("📋 " + limitAudit(title))
                .setDescription(limitAudit(description))
                .setColor(color)
                .setTimestamp(Instant.now())
                .build();
        sendLog(channelId, embed);
    }

    private String limitAudit(String value) {
        if (value == null || value.isBlank()) return "-";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 900 ? clean : clean.substring(0, 897) + "...";
    }

    private void sendLog(String channelId, MessageEmbed embed) {
        sendLog(channelId, embed, List.of());
    }

    private void sendLog(String channelId, MessageEmbed embed, List<ActionRow> rows) {
        JDA current = currentJda();
        if (current == null || channelId == null || channelId.isBlank() || channelId.contains("DAN_ID")) return;
        if (!isValidDiscordId(channelId)) return;
        GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) return;
        List<ActionRow> safeRows = rows == null ? List.of() : rows;
        channel.sendMessageEmbeds(embed)
                .setComponents(safeRows)
                .queue(null, failure -> plugin.logDebug("Cannot send Discord Store log: " + failure.getMessage(), failure));
    }

    private void startWaitingOrderRetryTask() {
        stopWaitingOrderRetryTask();
        int seconds = getWaitingPlayerRetrySeconds();
        long ticks = seconds * 20L;
        waitingOrderRetryTask = plugin.getPlatformScheduler().runTimerAsync(
                () -> plugin.getPlatformScheduler().runGlobal(() -> retryWaitingOrders(null)),
                ticks,
                ticks
        );
        plugin.getPlatformScheduler().runGlobal(() -> retryWaitingOrders(null));
    }

    private void resumeRecoveredOrders(JDA current, long generation) {
        plugin.getPlatformScheduler().runGlobal(() -> {
            if (!isCurrentBotSession(current, generation)) return;
            for (StoreOrder order : orders.values()) {
                OrderStatus status;
                synchronized (order) {
                    status = order.status;
                }
                if (status == OrderStatus.PENDING) {
                    startOrderPaymentPolling(order);
                } else if (status == OrderStatus.PAID) {
                    executeDelivery(order);
                }
            }
        });
    }

    private void stopWaitingOrderRetryTask() {
        ScheduledTask task = waitingOrderRetryTask;
        waitingOrderRetryTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void retryWaitingOrders(String onlyPlayerName) {
        if (!enabled || !botReady) return;
        String targetName = onlyPlayerName == null ? "" : onlyPlayerName.trim();
        for (StoreOrder order : orders.values()) {
            if (order.status != OrderStatus.WAITING_PLAYER) continue;
            if (!targetName.isBlank() && !order.playerName.equalsIgnoreCase(targetName)) continue;
            Player target = Bukkit.getPlayerExact(order.playerName);
            if (target == null) continue;
            plugin.getPlatformScheduler().runPlayer(target, () -> {
                if (target.isOnline() && order.status == OrderStatus.WAITING_PLAYER) {
                    executeDelivery(order);
                }
            });
        }
    }

    private StoreItem findItem(String itemId) {
        for (StoreItem item : products) {
            if (item.id().equals(itemId)) return item;
        }
        return null;
    }

    private StoreOrder findOrderByCode(String code) {
        if (code == null || code.isBlank()) return null;
        String raw = code.trim();
        StoreOrder direct = orders.get(raw.toUpperCase(Locale.ROOT));
        if (direct != null) return direct;

        String normalized = normalizeLookup(raw);
        for (StoreOrder order : orders.values()) {
            if (order.orderId.equalsIgnoreCase(raw)) return order;
            if (String.valueOf(order.payment.orderCode()).equals(raw)) return order;
            String description = normalizeLookup(order.payment.description());
            if (!normalized.isBlank() && (description.equals(normalized) || description.contains(normalized))) {
                return order;
            }
        }
        return null;
    }

    private String applyPlaceholders(String template, StoreOrder order) {
        String safeDiscordName = sanitizeCommandArgument(order.discordName);
        return Objects.toString(template, "")
                .replace("%player%", order.playerName)
                .replace("{player}", order.playerName)
                .replace("%buyer%", safeDiscordName)
                .replace("%discord_user%", safeDiscordName)
                .replace("%discord_id%", order.discordUserId)
                .replace("%item%", order.item.key())
                .replace("%item_name%", order.item.name())
                .replace("%price%", String.valueOf(order.item.price()))
                .replace("%order_id%", order.orderId)
                .replace("%payment_order%", String.valueOf(order.payment.orderCode()));
    }

    private String sanitizeCommandArgument(String value) {
        if (value == null || value.isBlank()) return "discord_user";
        StringBuilder safe = new StringBuilder(Math.min(32, value.length()));
        for (int offset = 0; offset < value.length() && safe.length() < 32; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-' || codePoint == '.') {
                safe.appendCodePoint(codePoint);
            } else if (safe.length() > 0 && safe.charAt(safe.length() - 1) != '_') {
                safe.append('_');
            }
        }
        String result = safe.toString();
        return result.isBlank() ? "discord_user" : result;
    }

    private String getToken() {
        String environmentVariable = storeConfig.getString("settings.bot-token-environment-variable", "").trim();
        if (environmentVariable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            String environmentToken = System.getenv(environmentVariable);
            if (environmentToken != null && !environmentToken.isBlank()) {
                return environmentToken.trim();
            }
        }
        return storeConfig.getString("settings.bot-token", "").trim();
    }

    private int countProducts() {
        return products.size();
    }

    private long countActiveOrders() {
        return orders.values().stream().filter(order -> isActiveStatus(order.status)).count();
    }

    private boolean isActiveStatus(OrderStatus status) {
        return status == OrderStatus.PENDING || status == OrderStatus.PAID
                || status == OrderStatus.DELIVERING || status == OrderStatus.WAITING_PLAYER;
    }

    private boolean isSafeKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,40}");
    }

    private boolean isSafeProductId(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,90}");
    }

    private String createOrderId() {
        String orderId;
        do {
            orderId = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT)
                    + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        } while (orders.containsKey(orderId.toUpperCase(Locale.ROOT)));
        return orderId;
    }

    private String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String appendStatusNote(String current, String note) {
        if (note == null || note.isBlank()) return current == null ? "" : current;
        if (current == null || current.isBlank()) return note;
        if (current.contains(note)) return current;
        return current + " " + note;
    }

    private String formatStars(int stars) {
        int safeStars = Math.max(0, Math.min(5, stars));
        if (safeStars <= 0) return "Chưa đánh giá";
        StringBuilder builder = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) {
            builder.append(i <= safeStars ? '★' : '☆');
        }
        return builder.toString();
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String normalizeLookup(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String displayStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> "Chờ thanh toán";
            case PAID -> "Đã thanh toán";
            case DELIVERING -> "Đang giao hàng";
            case WAITING_PLAYER -> "Treo đơn - chờ người chơi online";
            case DELIVERED -> "Đã giao thành công";
            case EXPIRED -> "Đã hết hạn";
            case ERROR -> "Cần staff xử lý";
            case CANCELED -> "Đã hủy";
        };
    }

    private int statusColor(OrderStatus status) {
        return switch (status) {
            case DELIVERED -> 0x2ECC71;
            case WAITING_PLAYER, DELIVERING, PAID -> 0xF1C40F;
            case ERROR -> 0xE74C3C;
            case EXPIRED -> 0xE67E22;
            case CANCELED -> 0x95A5A6;
            case PENDING -> 0x3498DB;
        };
    }

    private String discordTimestamp(long millis) {
        return millis <= 0L ? "-" : "<t:" + (millis / 1000L) + ":F>";
    }

    private boolean canManageStore(Member member) {
        if (member == null) return false;
        if (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)) {
            return true;
        }
        List<String> roleIds = storeConfig.getStringList("settings.admin-role-ids");
        if (roleIds.isEmpty()) return false;
        return member.getRoles().stream().anyMatch(role -> roleIds.contains(role.getId()));
    }

    private boolean matchesStatusFilter(OrderStatus status, String filter) {
        return switch (filter) {
            case "pending", "cho", "chothanhtoan" -> status == OrderStatus.PENDING;
            case "paid", "dathanhtoan" -> status == OrderStatus.PAID;
            case "delivered", "dagiao", "success" -> status == OrderStatus.DELIVERED;
            case "waiting", "treo", "cho nguoi choi" -> status == OrderStatus.WAITING_PLAYER;
            case "error", "loi" -> status == OrderStatus.ERROR;
            case "expired", "hethan" -> status == OrderStatus.EXPIRED;
            case "canceled", "huy" -> status == OrderStatus.CANCELED;
            case "delivering", "danggiao" -> status == OrderStatus.DELIVERING;
            default -> false;
        };
    }

    private boolean isStoreCommandGuild(Guild interactionGuild) {
        if (interactionGuild == null) return false;

        String configuredGuildId = storeConfig.getString("settings.admin-command-guild-id", "").trim();
        if (!configuredGuildId.isBlank() && !configuredGuildId.contains("DAN_ID")) {
            if (!isValidDiscordId(configuredGuildId) || !configuredGuildId.equals(interactionGuild.getId())) {
                return false;
            }
            JDA current = currentJda();
            if (current == null) return false;
            Guild storefrontGuild = resolveConfiguredStorefrontGuild(current);
            return storefrontGuild == null || storefrontGuild.getId().equals(interactionGuild.getId());
        }

        JDA current = currentJda();
        if (current == null) return false;
        String storefrontChannelId = storeConfig.getString("settings.storefront-channel-id", "").trim();
        if (isValidDiscordId(storefrontChannelId)) {
            GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, storefrontChannelId);
            if (channel != null) {
                return channel.getGuild().getId().equals(interactionGuild.getId());
            }
        }

        List<Guild> connectedGuilds = current.getGuilds();
        return connectedGuilds.size() == 1 && connectedGuilds.get(0).getId().equals(interactionGuild.getId());
    }

    private boolean isValidDiscordId(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Long.parseUnsignedLong(value.trim()) > 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean canInteractWithOrder(ButtonInteractionEvent event, StoreOrder order) {
        return order.discordUserId.equals(event.getUser().getId()) || canManageStore(event.getMember());
    }

    private boolean isCurrentBotSession(JDA candidate, long generation) {
        DiscordBotSession current = botSession;
        return enabled
                && botGeneration.get() == generation
                && current != null
                && current.generation == generation
                && candidate == current.jda.get();
    }

    private void registerStoreSlashCommands(JDA current, long generation) {
        if (!isCurrentBotSession(current, generation)) return;
        SlashCommandData adminCommand = Commands.slash("sotar-admin", "Công cụ quản trị SotarPayments")
                .setGuildOnly(true)
                .addSubcommands(new SubcommandData("kiemtramadon", "Kiểm tra mã đơn Auto Buy")
                        .addOption(OptionType.STRING, "ma-don", "Mã đơn, mã thanh toán hoặc nội dung chuyển khoản", true))
                .addSubcommands(new SubcommandData("xemdonhang", "Xem danh sách đơn hàng gần đây")
                        .addOption(OptionType.STRING, "trang-thai", "Lọc theo trạng thái: pending, paid, delivered, waiting, error, expired, canceled", false))
                .addSubcommands(new SubcommandData("timdonhang", "Tìm đơn hàng theo tên người chơi")
                        .addOption(OptionType.STRING, "ten-nguoi-choi", "Tên nhân vật trong game", true));
        SlashCommandData publishCommand = Commands.slash("taokenhbanhang", "Đăng bảng chọn sản phẩm Auto Buy vào kênh bán hàng")
                .setGuildOnly(true);

        String guildId = storeConfig.getString("settings.admin-command-guild-id", "").trim();
        if (!guildId.isBlank() && !guildId.contains("DAN_ID")) {
            if (!isValidDiscordId(guildId)) {
                plugin.logWarning("Cannot register Discord slash commands: settings.admin-command-guild-id is invalid.");
                return;
            }
            Guild guild = current.getGuildById(guildId);
            if (guild == null) {
                plugin.logWarning("Cannot register Discord slash commands in guild " + guildId + ": bot is not in that guild.");
                return;
            }
            Guild storefrontGuild = resolveConfiguredStorefrontGuild(current);
            if (storefrontGuild != null && !storefrontGuild.getId().equals(guild.getId())) {
                plugin.logWarning("Cannot register Discord slash commands: settings.admin-command-guild-id and settings.storefront-channel-id belong to different guilds.");
                return;
            }
            cleanupManagedCommandsOutsideGuild(current, guild, generation);
            upsertGuildCommand(current, guild, adminCommand, "/sotar-admin kiemtramadon", generation);
            upsertGuildCommand(current, guild, publishCommand, "/taokenhbanhang", generation);
            return;
        }

        Guild inferredGuild = inferStoreGuild(current);
        if (inferredGuild != null) {
            cleanupManagedCommandsOutsideGuild(current, inferredGuild, generation);
            upsertGuildCommand(current, inferredGuild, adminCommand, "/sotar-admin kiemtramadon", generation);
            upsertGuildCommand(current, inferredGuild, publishCommand, "/taokenhbanhang", generation);
            return;
        }

        plugin.logWarning("Cannot determine a safe Discord guild for slash commands. Set settings.admin-command-guild-id in store.yml.");
    }

    private Guild inferStoreGuild(JDA current) {
        Guild storefrontGuild = resolveConfiguredStorefrontGuild(current);
        if (storefrontGuild != null) return storefrontGuild;
        List<Guild> connectedGuilds = current.getGuilds();
        return connectedGuilds.size() == 1 ? connectedGuilds.get(0) : null;
    }

    private Guild resolveConfiguredStorefrontGuild(JDA current) {
        String storefrontChannelId = storeConfig.getString("settings.storefront-channel-id", "").trim();
        if (!isValidDiscordId(storefrontChannelId)) return null;
        GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, storefrontChannelId);
        return channel == null ? null : channel.getGuild();
    }

    private void cleanupManagedCommandsOutsideGuild(JDA current, Guild targetGuild, long generation) {
        current.retrieveCommands().queue(
                commands -> {
                    if (!isCurrentBotSession(current, generation)) return;
                    commands.stream()
                            .filter(command -> isManagedCommandName(command.getName()))
                            .forEach(command -> command.delete().queue(null,
                                    failure -> {
                                        if (isCurrentBotSession(current, generation)) {
                                            plugin.logDebug("Cannot remove stale global Discord command /" + command.getName() + ": " + failure.getMessage(), failure);
                                        }
                                    }));
                },
                failure -> {
                    if (isCurrentBotSession(current, generation)) {
                        plugin.logDebug("Cannot inspect global Discord commands for cleanup: " + failure.getMessage(), failure);
                    }
                });

        for (Guild guild : current.getGuilds()) {
            if (guild.getId().equals(targetGuild.getId())) continue;
            guild.retrieveCommands().queue(
                    commands -> {
                        if (!isCurrentBotSession(current, generation)) return;
                        commands.stream()
                                .filter(command -> isManagedCommandName(command.getName()))
                                .forEach(command -> command.delete().queue(null,
                                        failure -> {
                                            if (isCurrentBotSession(current, generation)) {
                                                plugin.logDebug("Cannot remove stale Discord command /" + command.getName()
                                                        + " from guild " + guild.getId() + ": " + failure.getMessage(), failure);
                                            }
                                        }));
                    },
                    failure -> {
                        if (isCurrentBotSession(current, generation)) {
                            plugin.logDebug("Cannot inspect Discord commands in guild " + guild.getId() + " for cleanup: " + failure.getMessage(), failure);
                        }
                    });
        }
    }

    private boolean isManagedCommandName(String name) {
        return "sotar-admin".equals(name) || "taokenhbanhang".equals(name);
    }

    private void upsertGuildCommand(JDA current,
                                    Guild guild,
                                    SlashCommandData command,
                                    String displayName,
                                    long generation) {
        if (!isCurrentBotSession(current, generation)) return;
        guild.upsertCommand(command).queue(
                success -> {
                    if (isCurrentBotSession(current, generation)) {
                        plugin.logInfo("Registered Discord command " + displayName + " in guild " + guild.getName() + ".");
                    }
                },
                failure -> {
                    if (isCurrentBotSession(current, generation)) {
                        plugin.logWarning("Cannot register Discord command " + displayName + ": " + failure.getMessage(), failure);
                    }
                });
    }

    private void runSenderMessage(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            plugin.getPlatformScheduler().runPlayer(player, () -> player.sendMessage(message));
            return;
        }
        plugin.getPlatformScheduler().runGlobal(() -> sender.sendMessage(message));
    }

    private void loadStoredOrders() {
        if (!ordersFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(ordersFile);
        ConfigurationSection section = data.getConfigurationSection("orders");
        if (section == null) return;
        for (String orderId : section.getKeys(false)) {
            ConfigurationSection orderSection = section.getConfigurationSection(orderId);
            if (orderSection == null) continue;
            try {
                StoreOrder order = readStoredOrder(orderId, orderSection);
                if (order != null) {
                    orders.put(order.orderId.toUpperCase(Locale.ROOT), order);
                }
            } catch (Exception e) {
                plugin.logWarning("Cannot load stored Discord Store order " + orderId + ": " + e.getMessage(), e);
            }
        }
    }

    private void reconcileStoredOrdersOnStartup() {
        int recoveredPending = 0;
        int recoveredPaid = 0;
        int blockedDeliveries = 0;
        long now = System.currentTimeMillis();
        for (StoreOrder order : orders.values()) {
            synchronized (order) {
                if (order.status == OrderStatus.PENDING) {
                    recoveredPending++;
                    continue;
                }
                if (order.status == OrderStatus.PAID) {
                    recoveredPaid++;
                    continue;
                }
                if (order.status != OrderStatus.DELIVERING) continue;
                order.completedAtMillis = now;
                order.lastMessage = "Plugin đã dừng khi đơn đang giao. Staff cần kiểm tra thực tế và xử lý thủ công để tránh giao trùng.";
                order.status = OrderStatus.ERROR;
                blockedDeliveries++;
            }
        }
        if (blockedDeliveries > 0) {
            saveOrders();
            plugin.logWarning("Recovered " + blockedDeliveries
                    + " Discord Store order(s) left in DELIVERING state as ERROR; manual review is required to prevent duplicate delivery.");
        }
        if (recoveredPaid > 0) {
            plugin.logInfo("Recovered " + recoveredPaid
                    + " paid Discord Store order(s); delivery will resume after the Discord bot is ready.");
        }
        if (recoveredPending > 0) {
            plugin.logInfo("Recovered " + recoveredPending
                    + " pending Discord Store order(s); payment checks will resume using their original timeout.");
        }
    }

    private StoreOrder readStoredOrder(String orderId, ConfigurationSection section) {
        String statusName = section.getString("status", OrderStatus.ERROR.name());
        OrderStatus status;
        try {
            status = OrderStatus.valueOf(statusName);
        } catch (Exception ignored) {
            status = OrderStatus.ERROR;
        }

        StoreItem item = new StoreItem(
                section.getString("item.id", section.getString("item.key", "unknown")),
                section.getString("item.key", "unknown"),
                section.getString("item.name", "Unknown item"),
                section.getLong("item.price", 0L),
                section.getString("item.detail", ""),
                section.getBoolean("item.require-online", true),
                List.copyOf(section.getStringList("item.commands"))
        );

        BankPaymentManager.PaymentOrder payment = new BankPaymentManager.PaymentOrder(
                section.getString("payment.provider", ""),
                section.getLong("payment.order-code", 0L),
                section.getLong("payment.amount", item.price()),
                section.getString("player", ""),
                section.getString("payment.bank-code", ""),
                section.getString("payment.bank-name", ""),
                section.getString("payment.account-number", ""),
                section.getString("payment.account-name", ""),
                section.getString("payment.description", ""),
                section.getString("payment.qr-code", ""),
                section.getString("payment.qr-image-url", ""),
                section.getString("payment.checkout-url", "")
        );

        StoreOrder order = new StoreOrder(
                orderId,
                section.getString("discord-user-id", ""),
                section.getString("discord-name", ""),
                section.getString("player", ""),
                item,
                payment,
                null,
                section.getLong("created-at", System.currentTimeMillis())
        );
        order.status = status;
        order.paidAtMillis = section.getLong("paid-at", 0L);
        order.completedAtMillis = section.getLong("completed-at", 0L);
        order.lastMessage = section.getString("last-message", "");
        order.waitingPlayerLogged = section.getBoolean("waiting-player-logged", false);
        order.orderMessageId = section.getString("discord-order-message-id", "");
        order.ratingStars = section.getInt("rating.stars", 0);
        order.ratedAtMillis = section.getLong("rating.rated-at", 0L);
        return order;
    }

    private void saveOrders() {
        synchronized (ordersFileLock) {
            trimOrderHistory();
            YamlConfiguration data = new YamlConfiguration();
            for (StoreOrder order : orders.values().stream()
                    .sorted(Comparator.comparingLong(StoreOrder::createdAtMillis).reversed())
                    .toList()) {
                writeStoredOrder(data, order);
            }
            try {
                File temporaryFile = new File(ordersFile.getParentFile(), ordersFile.getName() + ".tmp");
                data.save(temporaryFile);
                try {
                    Files.move(temporaryFile.toPath(), ordersFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporaryFile.toPath(), ordersFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.logWarning("Cannot save store-orders.yml: " + e.getMessage(), e);
            }
        }
    }

    private void writeStoredOrder(YamlConfiguration data, StoreOrder order) {
        synchronized (order) {
            String path = "orders." + order.orderId;
            data.set(path + ".status", order.status.name());
            data.set(path + ".discord-user-id", order.discordUserId);
            data.set(path + ".discord-name", order.discordName);
            data.set(path + ".player", order.playerName);
            data.set(path + ".created-at", order.createdAtMillis);
            data.set(path + ".paid-at", order.paidAtMillis);
            data.set(path + ".completed-at", order.completedAtMillis);
            data.set(path + ".last-message", order.lastMessage);
            data.set(path + ".waiting-player-logged", order.waitingPlayerLogged);
            data.set(path + ".discord-order-message-id", order.orderMessageId);
            data.set(path + ".rating.stars", order.ratingStars);
            data.set(path + ".rating.rated-at", order.ratedAtMillis);

            data.set(path + ".item.id", order.item.id());
            data.set(path + ".item.key", order.item.key());
            data.set(path + ".item.name", order.item.name());
            data.set(path + ".item.price", order.item.price());
            data.set(path + ".item.detail", order.item.detail());
            data.set(path + ".item.require-online", order.item.requireOnline());
            data.set(path + ".item.commands", order.item.commands());

            data.set(path + ".payment.provider", order.payment.provider());
            data.set(path + ".payment.order-code", order.payment.orderCode());
            data.set(path + ".payment.amount", order.payment.amount());
            data.set(path + ".payment.bank-code", order.payment.bankCode());
            data.set(path + ".payment.bank-name", order.payment.bankName());
            data.set(path + ".payment.account-number", order.payment.accountNumber());
            data.set(path + ".payment.account-name", order.payment.accountName());
            data.set(path + ".payment.description", order.payment.description());
            data.set(path + ".payment.qr-code", order.payment.qrCode());
            data.set(path + ".payment.qr-image-url", order.payment.qrImageUrl());
            data.set(path + ".payment.checkout-url", order.payment.checkoutUrl());
        }
    }

    private void trimOrderHistory() {
        int limit = Math.max(50, storeConfig.getInt("settings.order-history-limit", 500));
        if (orders.size() <= limit) return;
        List<StoreOrder> removable = orders.values().stream()
                .filter(order -> !isActiveStatus(order.status))
                .sorted(Comparator.comparingLong(StoreOrder::createdAtMillis))
                .collect(Collectors.toCollection(ArrayList::new));
        while (orders.size() > limit && !removable.isEmpty()) {
            StoreOrder oldest = removable.remove(0);
            orders.remove(oldest.orderId.toUpperCase(Locale.ROOT));
        }
    }

    private final class DiscordBotSession {
        private final long generation;
        private final StoreDiscordListener listener;
        private final ExecutorService callbackPool;
        private final ExecutorService eventPool;
        private final ScheduledExecutorService gatewayPool;
        private final ScheduledExecutorService rateLimitScheduler;
        private final ExecutorService rateLimitElastic;
        private final List<String> executorNames;
        private final AtomicReference<JDA> jda = new AtomicReference<>();
        private final AtomicBoolean executorsClosed = new AtomicBoolean(false);

        private DiscordBotSession(long generation) {
            this.generation = generation;
            this.listener = new StoreDiscordListener(generation);
            this.callbackPool = Executors.newFixedThreadPool(2,
                    new NamedDaemonThreadFactory("SotarPayments-Discord-Callback-g" + generation));
            this.eventPool = Executors.newSingleThreadExecutor(
                    new NamedDaemonThreadFactory("SotarPayments-Discord-Event-g" + generation));
            this.gatewayPool = Executors.newSingleThreadScheduledExecutor(
                    new NamedDaemonThreadFactory("SotarPayments-Discord-Gateway-g" + generation));
            this.rateLimitScheduler = Executors.newScheduledThreadPool(2,
                    new NamedDaemonThreadFactory("SotarPayments-Discord-RateLimit-g" + generation));
            this.rateLimitElastic = Executors.newCachedThreadPool(
                    new NamedDaemonThreadFactory("SotarPayments-Discord-RateLimitElastic-g" + generation));
            this.executorNames = List.of("callback", "event", "gateway", "rate-limit", "rate-limit-elastic");
        }

        private List<ExecutorService> executors() {
            return List.of(callbackPool, eventPool, gatewayPool, rateLimitScheduler, rateLimitElastic);
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private final class StoreServerListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (!enabled || event.getPlayer() == null) return;
            String playerName = event.getPlayer().getName();
            plugin.getPlatformScheduler().runGlobal(() -> retryWaitingOrders(playerName));
        }
    }

    private final class StoreDiscordListener extends ListenerAdapter {
        private final long generation;

        private StoreDiscordListener(long generation) {
            this.generation = generation;
        }

        private boolean isCurrentSession(JDA candidate) {
            return isCurrentBotSession(candidate, generation);
        }

        @Override
        public void onReady(ReadyEvent event) {
            // Serialize readiness behind JDABuilder.build() and session assignment. This also
            // keeps shutdown/awaitShutdown away from JDA's own callback threads.
            queueReadyEvent(event.getJDA(), generation);
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if (!isCurrentSession(event.getJDA())) return;
            if (!"sotar-admin".equals(event.getName()) && !"taokenhbanhang".equals(event.getName())) return;
            if (!isStoreCommandGuild(event.getGuild())) {
                event.reply("Lệnh này chỉ dùng được trong máy chủ Discord đã cấu hình cho SotarPayments.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
            if (!canManageStore(event.getMember())) {
                event.reply("Bạn không có quyền quản lý cửa hàng SotarPayments.")
                        .setEphemeral(true)
                        .queue();
                return;
            }

            if ("taokenhbanhang".equals(event.getName())) {
                String interactionGuildId = event.getGuild().getId();
                sendAuditLog("Staff dang storefront", "Discord user: `" + event.getUser().getId() + "`", 5814783);
                event.deferReply(true).queue(hook -> publishStorefront(interactionGuildId, message ->
                                hook.editOriginal(plugin.stripColor(message)).queue(
                                        ignored -> { },
                                        failure -> plugin.logDebug("Cannot reply to Discord /taokenhbanhang: " + failure.getMessage(), failure))),
                        failure -> plugin.logWarning("Cannot defer Discord /taokenhbanhang response: " + failure.getMessage(), failure));
                return;
            }

            if (!"kiemtramadon".equals(event.getSubcommandName())
                    && !"xemdonhang".equals(event.getSubcommandName())
                    && !"timdonhang".equals(event.getSubcommandName())) {
                event.reply("Subcommand không hợp lệ.").setEphemeral(true).queue();
                return;
            }

            if ("kiemtramadon".equals(event.getSubcommandName())) {
                String code = event.getOption("ma-don") == null ? "" : event.getOption("ma-don").getAsString();
                sendAuditLog("Staff tra cuu don", "Discord user: `" + event.getUser().getId() + "`\nMa tra cuu: `"
                        + limitAudit(code) + "`", 5814783);
                StoreOrder order = findOrderByCode(code);
                if (order == null) {
                    event.reply("Không tìm thấy đơn với mã `" + limit(code, 80) + "`. Hãy kiểm tra mã đơn, mã thanh toán hoặc nội dung chuyển khoản.")
                            .setEphemeral(true)
                            .queue();
                    return;
                }
                event.replyEmbeds(buildAdminOrderEmbed(order)).setEphemeral(true).queue();
                return;
            }

            if ("xemdonhang".equals(event.getSubcommandName())) {
                String statusFilter = event.getOption("trang-thai") == null ? "" : event.getOption("trang-thai").getAsString().trim().toLowerCase(Locale.ROOT);
                sendAuditLog("Staff xem danh sach don", "Discord user: `" + event.getUser().getId() + "`\nBo loc: `"
                        + limitAudit(statusFilter.isBlank() ? "all" : statusFilter) + "`", 5814783);
                List<StoreOrder> filtered = orders.values().stream()
                        .filter(order -> statusFilter.isEmpty() || matchesStatusFilter(order.status, statusFilter))
                        .sorted(Comparator.comparingLong(StoreOrder::createdAtMillis).reversed())
                        .limit(10)
                        .toList();

                if (filtered.isEmpty()) {
                    event.reply("Không có đơn hàng nào" + (statusFilter.isEmpty() ? "" : " với trạng thái `" + statusFilter + "`") + ".")
                            .setEphemeral(true).queue();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("**Danh sách đơn hàng gần đây");
                if (!statusFilter.isEmpty()) sb.append(" (").append(statusFilter).append(")");
                sb.append(":**\n\n");
                for (StoreOrder o : filtered) {
                    sb.append("`").append(o.orderId).append("` — ")
                            .append("**").append(o.item.name()).append("** — ")
                            .append(plugin.formatMoney(o.item.price())).append(" ").append(plugin.trPlain("general.currency"))
                            .append(" — ").append(displayStatus(o.status))
                            .append(" — ").append(discordTimestamp(o.createdAtMillis))
                            .append("\n");
                }
                event.reply(sb.toString()).setEphemeral(true).queue();
                return;
            }

            if ("timdonhang".equals(event.getSubcommandName())) {
                String playerName = event.getOption("ten-nguoi-choi") == null ? "" : event.getOption("ten-nguoi-choi").getAsString().trim();
                sendAuditLog("Staff tim don theo nguoi choi", "Discord user: `" + event.getUser().getId() + "`\nNguoi choi: `"
                        + limitAudit(playerName) + "`", 5814783);
                if (playerName.isBlank()) {
                    event.reply("Vui lòng nhập tên người chơi.").setEphemeral(true).queue();
                    return;
                }

                List<StoreOrder> playerOrders = orders.values().stream()
                        .filter(order -> order.playerName.equalsIgnoreCase(playerName))
                        .sorted(Comparator.comparingLong(StoreOrder::createdAtMillis).reversed())
                        .limit(10)
                        .toList();

                if (playerOrders.isEmpty()) {
                    event.reply("Không tìm thấy đơn hàng nào cho người chơi `" + limit(playerName, 32) + "`.")
                            .setEphemeral(true).queue();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("**Đơn hàng của `").append(limit(playerName, 32)).append("`:**\n\n");
                for (StoreOrder o : playerOrders) {
                    sb.append("`").append(o.orderId).append("` — ")
                            .append("**").append(o.item.name()).append("** — ")
                            .append(plugin.formatMoney(o.item.price())).append(" ").append(plugin.trPlain("general.currency"))
                            .append(" — ").append(displayStatus(o.status))
                            .append(" — ").append(discordTimestamp(o.createdAtMillis))
                            .append("\n");
                }
                event.reply(sb.toString()).setEphemeral(true).queue();
            }
        }

        @Override
        public void onStringSelectInteraction(StringSelectInteractionEvent event) {
            if (!isCurrentSession(event.getJDA())) return;
            if (!PRODUCT_SELECT_ID.equals(event.getComponentId())) return;
            handleProductSelect(event);
        }

        @Override
        public void onModalInteraction(ModalInteractionEvent event) {
            if (!isCurrentSession(event.getJDA())) return;
            String modalId = event.getModalId();
            if (!modalId.startsWith(CHECKOUT_MODAL_PREFIX)) return;

            String itemId = modalId.substring(CHECKOUT_MODAL_PREFIX.length());
            StoreItem item = findItem(itemId);
            if (item == null) {
                event.reply("Sản phẩm không còn tồn tại hoặc đã bị tắt.").setEphemeral(true).queue();
                return;
            }

            String playerName = event.getValue("player") == null ? "" : event.getValue("player").getAsString().trim();
            if (!PLAYER_NAME_PATTERN.matcher(playerName).matches()) {
                event.reply("Tên người chơi không hợp lệ. Tên chỉ gồm chữ, số, dấu gạch dưới và dài 3-16 ký tự.").setEphemeral(true).queue();
                return;
            }

            User buyer = event.getUser();
                sendAuditLog("Bat dau checkout", "Discord user: `" + buyer.getId() + "`\nMat hang: **" + item.name()
                    + "**\nNguoi choi: `" + playerName + "`", 5814783);
            event.deferReply(true).queue(hook -> createOrder(hook, buyer, item, playerName));
        }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            if (!isCurrentSession(event.getJDA())) return;
            String componentId = event.getComponentId();
            if (componentId.startsWith(CANCEL_BUTTON_PREFIX)) {
                handleCancelButton(event, componentId.substring(CANCEL_BUTTON_PREFIX.length()));
                return;
            }
            if (componentId.startsWith(RERUN_BUTTON_PREFIX)) {
                handleRerunButton(event, componentId.substring(RERUN_BUTTON_PREFIX.length()));
                return;
            }
            if (componentId.startsWith(RATING_BUTTON_PREFIX)) {
                handleRatingButton(event, componentId.substring(RATING_BUTTON_PREFIX.length()));
            }
        }

        private void handleProductSelect(StringSelectInteractionEvent event) {
            if (event.getValues().isEmpty()) {
                event.reply("Vui lòng chọn một mặt hàng.").setEphemeral(true).queue();
                return;
            }
            StoreItem item = findItem(event.getValues().get(0));
            if (item == null) {
                event.reply("Sản phẩm không còn tồn tại hoặc đã bị tắt.").setEphemeral(true).queue();
                return;
            }
            sendAuditLog("Chon san pham", "Discord user: `" + event.getUser().getId() + "`\nMat hang: **"
                    + item.name() + "**", 5814783);

            TextInput playerInput = TextInput.create("player", "Tên người chơi", TextInputStyle.SHORT)
                    .setPlaceholder("Ví dụ: Steve")
                    .setMinLength(3)
                    .setMaxLength(16)
                    .setRequired(true)
                    .build();
            Modal modal = Modal.create(CHECKOUT_MODAL_PREFIX + item.id(), limit("Mua " + item.name(), 45))
                    .addActionRow(playerInput)
                    .build();
            event.replyModal(modal).queue();
        }

        private void handleCancelButton(ButtonInteractionEvent event, String orderId) {
            StoreOrder order = orders.get(orderId.toUpperCase(Locale.ROOT));
            if (order == null) {
                event.reply("Đơn hàng này không còn ở trạng thái chờ thanh toán.").setEphemeral(true).queue();
                return;
            }
            if (!canInteractWithOrder(event, order)) {
                event.reply("Bạn không có quyền thao tác đơn hàng này.").setEphemeral(true).queue();
                return;
            }

            if (!claimPendingTransition(order, OrderStatus.CANCELED,
                    "Đơn hàng đã được hủy trước khi thanh toán.",
                    System.currentTimeMillis())) {
                event.reply("Đơn hàng này không còn ở trạng thái chờ thanh toán.").setEphemeral(true).queue();
                return;
            }
            order.cancelTask();
            plugin.getBankPaymentManager().cancelExternalTransaction(order.payment.orderCode());
            saveOrders();

            synchronized (order) {
                if (order.status != OrderStatus.CANCELED) {
                    MessageEmbed currentEmbed = buildCurrentOrderEmbed(order);
                    List<ActionRow> currentRows = buildOrderComponents(order);
                    event.editMessageEmbeds(currentEmbed)
                            .setComponents(currentRows)
                            .queue(null, failure -> plugin.logDebug("Cannot refresh Discord order after payment won cancellation race: "
                                    + failure.getMessage(), failure));
                    editOrderMessage(order, currentEmbed, currentRows);
                    return;
                }
                MessageEmbed canceledEmbed = buildCanceledEmbed(order);
                event.editMessageEmbeds(canceledEmbed)
                        .setComponents(Collections.emptyList())
                        .queue(null, failure -> plugin.logDebug("Cannot cancel Discord order message: " + failure.getMessage(), failure));
                editOrderMessage(order, canceledEmbed, List.of());
                sendAuditLog("Don da huy", "Ma don: `" + order.orderId + "`\nDiscord user: `"
                    + event.getUser().getId() + "`", 15158332);
                return;
            }
        }

        private void handleRerunButton(ButtonInteractionEvent event, String orderId) {
            StoreOrder order = orders.get(orderId.toUpperCase(Locale.ROOT));
            if (order == null) {
                event.reply("Không tìm thấy đơn hàng này trong bộ nhớ SotarPayments.").setEphemeral(true).queue();
                return;
            }
            if (!canInteractWithOrder(event, order)) {
                event.reply("Bạn không có quyền chạy lại đơn hàng này.").setEphemeral(true).queue();
                return;
            }
            if (order.status == OrderStatus.DELIVERED) {
                event.reply("Đơn hàng này đã giao thành công trước đó, không thể chạy lại để tránh phát trùng vật phẩm.").setEphemeral(true).queue();
                return;
            }
            if (order.status != OrderStatus.WAITING_PLAYER) {
                event.reply("Đơn hàng đang ở trạng thái `" + displayStatus(order.status) + "`, chưa thể chạy lại.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue(hook -> {
                String changedStatus = null;
                synchronized (order) {
                    if (order.status != OrderStatus.WAITING_PLAYER) {
                        changedStatus = displayStatus(order.status);
                    } else {
                        order.lastMessage = "Đã bật tự chạy lại. Đơn sẽ được giao khi người chơi online trong máy chủ.";
                    }
                }
                if (changedStatus != null) {
                    hook.editOriginal("Đơn `" + order.orderId + "` đã chuyển sang trạng thái `"
                            + changedStatus + "`; yêu cầu chạy lại không còn cần thiết.").queue();
                    return;
                }
                hook.editOriginal("Đã nhận yêu cầu chạy lại đơn `" + order.orderId + "`. Nếu người chơi chưa online, hệ thống sẽ tiếp tục tự kiểm tra và giao ngay khi người chơi vào máy chủ.").queue();
                saveOrders();
                editOrderMessage(order, buildWaitingPlayerEmbed(order), buildOrderComponents(order));
                sendAuditLog("Yeu cau chay lai don", "Ma don: `" + order.orderId + "`\nDiscord user: `"
                    + event.getUser().getId() + "`", 0xF1C40F);
                plugin.getPlatformScheduler().runGlobal(() -> retryWaitingOrders(order.playerName));
            });
        }

        private void handleRatingButton(ButtonInteractionEvent event, String payload) {
            int separator = payload.lastIndexOf(':');
            if (separator <= 0 || separator >= payload.length() - 1) {
                event.reply("Nút đánh giá không hợp lệ.").setEphemeral(true).queue();
                return;
            }

            String orderId = payload.substring(0, separator);
            int stars;
            try {
                stars = Integer.parseInt(payload.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                event.reply("Số sao đánh giá không hợp lệ.").setEphemeral(true).queue();
                return;
            }

            StoreOrder order = orders.get(orderId.toUpperCase(Locale.ROOT));
            if (order == null) {
                event.reply("Không tìm thấy đơn hàng này trong bộ nhớ SotarPayments.").setEphemeral(true).queue();
                return;
            }
            if (!order.discordUserId.equals(event.getUser().getId())) {
                event.reply("Chỉ người mua đơn hàng mới có thể đánh giá đơn này.").setEphemeral(true).queue();
                return;
            }
            if (order.status != OrderStatus.DELIVERED) {
                event.reply("Đơn hàng chưa hoàn tất nên chưa thể đánh giá.").setEphemeral(true).queue();
                return;
            }
            if (order.ratingStars > 0) {
                event.reply("Bạn đã đánh giá đơn hàng này trước đó: " + formatStars(order.ratingStars) + " (`" + order.ratingStars + "/5`).").setEphemeral(true).queue();
                return;
            }
            if (stars < 1 || stars > 5) {
                event.reply("Vui lòng chọn đánh giá từ 1 đến 5 sao.").setEphemeral(true).queue();
                return;
            }

            order.ratingStars = stars;
            order.ratedAtMillis = System.currentTimeMillis();
            Message interactionMessage = event.getMessage();
            if ((order.orderMessageId == null || order.orderMessageId.isBlank()) && interactionMessage != null) {
                order.orderMessageId = interactionMessage.getId();
            }
            saveOrders();
            sendFeedbackLog(order);
                sendAuditLog("Da danh gia don", "Ma don: `" + order.orderId + "`\nDiscord user: `"
                    + event.getUser().getId() + "`\nSo sao: `" + stars + "/5`", 0xF1C40F);

            MessageEmbed ratedEmbed = buildSuccessEmbed(order);
            event.editMessageEmbeds(ratedEmbed)
                    .setComponents(Collections.emptyList())
                    .queue(null, failure -> plugin.logDebug("Cannot update rated Discord Store message: " + failure.getMessage(), failure));
            editOrderMessage(order, ratedEmbed, List.of());
        }
    }

    private enum OrderStatus {
        PENDING,
        PAID,
        DELIVERING,
        WAITING_PLAYER,
        DELIVERED,
        EXPIRED,
        ERROR,
        CANCELED
    }

    public record StoreItem(String id,
                            String key,
                            String name,
                            long price,
                            String detail,
                            boolean requireOnline,
                            List<String> commands) {}

    private static final class StoreOrder {
        private final String orderId;
        private final String discordUserId;
        private final String discordName;
        private final String playerName;
        private final StoreItem item;
        private final BankPaymentManager.PaymentOrder payment;
        private final InteractionHook hook;
        private final long createdAtMillis;
        private final AtomicReference<ScheduledTask> task = new AtomicReference<>();
        private volatile OrderStatus status = OrderStatus.PENDING;
        private long deliveryAttempt;
        private volatile long paidAtMillis;
        private volatile long completedAtMillis;
        private volatile String lastMessage = "";
        private volatile boolean waitingPlayerLogged;
        private volatile String orderMessageId = "";
        private volatile int ratingStars;
        private volatile long ratedAtMillis;

        private StoreOrder(String orderId,
                           String discordUserId,
                           String discordName,
                           String playerName,
                           StoreItem item,
                           BankPaymentManager.PaymentOrder payment,
                           InteractionHook hook,
                           long createdAtMillis) {
            this.orderId = orderId;
            this.discordUserId = discordUserId;
            this.discordName = discordName;
            this.playerName = playerName;
            this.item = item;
            this.payment = payment;
            this.hook = hook;
            this.createdAtMillis = createdAtMillis;
        }

        private long createdAtMillis() {
            return createdAtMillis;
        }

        private void attachPaymentTask(ScheduledTask scheduledTask) {
            if (scheduledTask == null) return;
            ScheduledTask taskToCancel = scheduledTask;
            synchronized (this) {
                if (status == OrderStatus.PENDING) {
                    taskToCancel = task.getAndSet(scheduledTask);
                }
            }
            if (taskToCancel != null) taskToCancel.cancel();
        }

        private void cancelTask() {
            ScheduledTask scheduledTask = task.getAndSet(null);
            if (scheduledTask != null) {
                scheduledTask.cancel();
            }
        }
    }
}
