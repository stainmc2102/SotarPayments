package vn.sotarpayments.napbank.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;
import vn.sotarpayments.common.economy.EconomyManager;
import vn.sotarpayments.common.scheduler.PlatformScheduler.ScheduledTask;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BankPaymentManager {
    private static final String PROVIDER_PAYOS = "payos";
    private static final String PROVIDER_SEPAY = "sepay";
    private static final String PAYOS_PAYMENT_REQUESTS_API = "https://api-merchant.payos.vn/v2/payment-requests";
    private static final String SEPAY_TRANSACTIONS_API = "https://my.sepay.vn/userapi/transactions/list";
    private static final String QUICK_CHART_QR_API = "https://quickchart.io/qr";

    private final SotarPayments plugin;
    private final CloseableHttpClient httpClient;
    private final Object callbackLock = new Object();
    private final Set<HttpUriRequestBase> activeHttpRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PollingSession> activeTasks = new ConcurrentHashMap<>();
    private final Map<Long, TransactionState> pendingStates = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // BỘ NHỚ ĐỆM BẢO MẬT: Lưu trữ ID giao dịch SePay đã xử lý để chống replay attack.
    private final Set<String> processedSePayTransactions = ConcurrentHashMap.newKeySet();

    public BankPaymentManager(SotarPayments plugin) {
        this.plugin = plugin;
        this.httpClient = createHttpClient();
    }

    private record TransactionState(String provider, String description, long createdAtMillis) {}

    private record PaymentMatch(boolean paid, String transactionId) {
        private static final PaymentMatch UNPAID = new PaymentMatch(false, "");

        private PaymentMatch {
            transactionId = transactionId == null ? "" : transactionId;
        }
    }

    private static final class PollingSession {
        private final UUID playerId;
        private final String playerName;
        private final long orderCode;
        private final long amount;
        private final int mapId;
        private final TransactionState state;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
        private final AtomicBoolean rewardStarted = new AtomicBoolean(false);
        private final AtomicReference<PaymentMatch> confirmedPayment = new AtomicReference<>();
        private final AtomicReference<ScheduledTask> task = new AtomicReference<>();
        private final AtomicInteger secondsLeft;

        private PollingSession(Player player, long orderCode, long amount, int mapId,
                               TransactionState state, int timeoutSeconds) {
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
            this.orderCode = orderCode;
            this.amount = amount;
            this.mapId = mapId;
            this.state = state;
            this.secondsLeft = new AtomicInteger(timeoutSeconds);
        }
    }

    public record PaymentOrder(String provider,
                               long orderCode,
                               long amount,
                               String payerName,
                               String bankCode,
                               String bankName,
                               String accountNumber,
                               String accountName,
                               String description,
                               String qrCode,
                               String qrImageUrl,
                               String checkoutUrl) {
        public JsonObject toJsonObject() {
            JsonObject data = new JsonObject();
            data.addProperty("provider", safe(provider));
            data.addProperty("orderCode", orderCode);
            data.addProperty("amount", amount);
            data.addProperty("payerName", safe(payerName));
            data.addProperty("bin", safe(bankCode));
            data.addProperty("bankName", safe(bankName));
            data.addProperty("accountNumber", safe(accountNumber));
            data.addProperty("accountName", safe(accountName));
            data.addProperty("description", safe(description));
            data.addProperty("qrCode", safe(qrCode));
            data.addProperty("qrImageUrl", safe(qrImageUrl));
            data.addProperty("checkoutUrl", safe(checkoutUrl));
            data.addProperty("qrImage", true);
            return data;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    public void createTransaction(Player player, long amount, TransactionCallback callback) {
        createPaymentOrder(player.getName(), amount, new PaymentOrderCallback() {
            @Override
            public void onSuccess(PaymentOrder order) {
                callback.onSuccess(order.toJsonObject(), order.orderCode());
            }

            @Override
            public void onFailure(String message) {
                sendCreateTransactionError(player, message);
            }
        });
    }

    public void createPaymentOrder(String payerName, long amount, PaymentOrderCallback callback) {
        if (shuttingDown.get()) {
            return;
        }
        String safePayerName = sanitizePayerName(payerName);
        if (PROVIDER_SEPAY.equals(getProvider())) {
            createSePayPaymentOrder(safePayerName, amount, callback);
        } else {
            createPayOSPaymentOrder(safePayerName, amount, callback);
        }
    }

    private void createPayOSPaymentOrder(String payerName, long amount, PaymentOrderCallback callback) {
        if (!hasPayOSCredentials()) {
            plugin.logWarning("PayOS createTransaction aborted: missing client-id/api-key/checksum-key in providers/payos.yml");
            notifyFailure(callback, plugin.tr("bank.payos-missing"));
            return;
        }

        long orderCode = buildOrderCode();
        String description = buildPaymentDescription("payos.payment-format", payerName, orderCode, "{playername} THANH TOAN", 25);
        pendingStates.put(orderCode, new TransactionState(PROVIDER_PAYOS, description, System.currentTimeMillis()));

        boolean scheduled = plugin.getPlatformScheduler().runAsync(() -> {
            try {
                HttpPost post = new HttpPost(PAYOS_PAYMENT_REQUESTS_API);
                JsonObject body = new JsonObject();
                body.addProperty("orderCode", orderCode);
                body.addProperty("amount", amount);
                body.addProperty("description", description);
                body.addProperty("cancelUrl", "https://google.com");
                body.addProperty("returnUrl", "https://google.com");
                body.addProperty("signature", generatePayOSSignature(amount, "https://google.com", description, orderCode, "https://google.com"));
                post.setHeader("x-client-id", getPayOSClientId());
                post.setHeader("x-api-key", getPayOSApiKey());
                post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

                executeRequest(post, response -> {
                    String res = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    JsonObject json = JsonParser.parseString(res).getAsJsonObject();
                    String code = getJsonString(json, "code", "unknown");
                    if ("00".equals(code)) {
                        JsonObject data = json.has("data") && json.get("data").isJsonObject()
                                ? json.getAsJsonObject("data") : new JsonObject();
                        notifySuccess(callback, buildPayOSOrder(payerName, amount, orderCode, description, data));
                        return null;
                    }

                    String message = getJsonString(json, "desc", getJsonString(json, "message", "PayOS rejected the transaction"));
                    pendingStates.remove(orderCode);
                    if (!shuttingDown.get()) {
                        plugin.logWarning("PayOS createTransaction failed for " + payerName
                                + " code=" + code + " message=" + message);
                        plugin.logDebug("PayOS createTransaction raw response: " + res);
                        notifyFailure(callback, plugin.tr("bank.create-provider-failed", "message", message, "code", code));
                    }
                    return null;
                });
            } catch (Exception e) {
                pendingStates.remove(orderCode);
                if (!shuttingDown.get()) {
                    plugin.logWarning("PayOS createTransaction crashed for " + payerName, e);
                    notifyFailure(callback, plugin.tr("bank.create-failed"));
                }
            }
        });
        if (!scheduled) {
            pendingStates.remove(orderCode);
            notifyFailure(callback, plugin.tr("bank.create-failed"));
        }
    }

    private PaymentOrder buildPayOSOrder(String payerName, long amount, long orderCode, String fallbackDescription, JsonObject data) {
        String description = getJsonString(data, "description", fallbackDescription);
        String qrCode = getJsonString(data, "qrCode", getJsonString(data, "qr_code", ""));
        String checkoutUrl = getJsonString(data, "checkoutUrl", getJsonString(data, "checkout_url", ""));
        String bankCode = getJsonString(data, "bin", getJsonString(data, "bankCode", ""));
        String bankName = getJsonString(data, "bankName", getJsonString(data, "bank", "PayOS"));
        String accountNumber = getJsonString(data, "accountNumber", getJsonString(data, "account_number", ""));
        String accountName = getJsonString(data, "accountName", getJsonString(data, "account_name", ""));
        String qrSource = !qrCode.isBlank() ? qrCode : checkoutUrl;
        return new PaymentOrder(PROVIDER_PAYOS, orderCode, amount, payerName, bankCode, bankName,
                accountNumber, accountName, description, qrSource, buildQrImageUrl(qrSource), checkoutUrl);
    }

    private void createSePayPaymentOrder(String payerName, long amount, PaymentOrderCallback callback) {
        if (!hasSePayCredentials()) {
            plugin.logWarning("SePay createTransaction aborted: missing api-token/bank-code/account-number/account-name in providers/sepay.yml");
            notifyFailure(callback, plugin.tr("bank.sepay-missing"));
            return;
        }

        long orderCode = buildOrderCode();
        String description = buildPaymentDescription("sepay.payment-format", payerName, orderCode, "KP{ordercode}", 50);
        pendingStates.put(orderCode, new TransactionState(PROVIDER_SEPAY, description, System.currentTimeMillis()));

        String qrUrl = buildSePayQrImageUrl(amount, description);
        PaymentOrder order = new PaymentOrder(
                PROVIDER_SEPAY,
                orderCode,
                amount,
                payerName,
                getSePayBankCode(),
                getSePayBankName(),
                getSePayAccountNumber(),
                getSePayAccountName(),
                description,
                qrUrl,
                forceVietQrOnlyUrl(qrUrl),
                ""
        );
        notifySuccess(callback, order);
    }

    public void startPolling(Player player, long orderCode, long amount, int mapId) {
        if (shuttingDown.get()) return;
        cancelPolling(player);
        TransactionState state = pendingStates.getOrDefault(orderCode,
                new TransactionState(getProvider(), String.valueOf(orderCode), System.currentTimeMillis()));
        int timeoutSeconds = Math.max(60, plugin.config().getInt("napbank.timeout-seconds", 600));
        int pollEverySeconds = Math.max(5, plugin.config().getInt("napbank.poll-every-seconds", 10));
        PollingSession session = new PollingSession(player, orderCode, amount, mapId, state, timeoutSeconds);

        Runnable polling = () -> pollPlayerPayment(player, session, pollEverySeconds);

        ScheduledTask task = plugin.getPlatformScheduler().runTimerAsync(polling, 1L, 20L);
        session.task.set(task);
        activeTasks.put(session.playerId, session);
    }

    private void pollPlayerPayment(Player player, PollingSession session, int pollEverySeconds) {
        if (shuttingDown.get()) {
            return;
        }
        if (session.confirmedPayment.get() != null) {
            scheduleConfirmedReward(player, session);
            return;
        }
        if (session.terminal.get()) {
            return;
        }

        int remaining = session.secondsLeft.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (remaining <= 0) {
            // The request started at 00:01 is authoritative. Do not emit a timeout while it is still running.
            if (!session.requestInFlight.get()) {
                expirePlayerPayment(player, session);
            }
            return;
        }

        int minutes = remaining / 60;
        int seconds = remaining % 60;
        String timer = String.format("%02d:%02d", minutes, seconds);
        plugin.getPlatformScheduler().runPlayer(player, () -> {
            if (!shuttingDown.get() && !session.terminal.get() && player.isOnline()) {
                plugin.sendActionBar(player, plugin.tr("bank.actionbar-scanning",
                        "time", timer,
                        "amount", plugin.formatMoney(session.amount)));
            }
        });

        int next = remaining - 1;
        if (next % pollEverySeconds == 0 && session.requestInFlight.compareAndSet(false, true)) {
            boolean accepted = plugin.getPlatformScheduler().runAsync(() -> {
                try {
                    if (shuttingDown.get()) return;
                    PaymentMatch match = queryPayment(session.orderCode, session.amount, session.state);
                    if (!shuttingDown.get() && match.paid()) {
                        markPaymentSuccess(player, session, match);
                    }
                } catch (Exception e) {
                    if (!shuttingDown.get()) {
                        plugin.logDebug(session.state.provider() + " payment check skipped: " + e.getMessage(), e);
                    }
                } finally {
                    session.requestInFlight.set(false);
                }
            });
            if (!accepted) {
                session.requestInFlight.set(false);
            }
        }
    }

    public ScheduledTask startExternalPolling(String ownerKey, long orderCode, long amount, ExternalPaymentCallback callback) {
        if (shuttingDown.get()) return () -> {};
        TransactionState state = pendingStates.getOrDefault(orderCode,
                new TransactionState(getProvider(), String.valueOf(orderCode), System.currentTimeMillis()));
        int timeoutSeconds = Math.max(60, plugin.getStoreManager() == null
                ? plugin.config().getInt("napbank.timeout-seconds", 600)
                : plugin.getStoreManager().getOrderTimeoutSeconds());
        int pollEverySeconds = Math.max(5, plugin.getStoreManager() == null
                ? plugin.config().getInt("napbank.poll-every-seconds", 10)
                : plugin.getStoreManager().getPollEverySeconds());
        long now = System.currentTimeMillis();
        long createdAt = state.createdAtMillis() > 0L && state.createdAtMillis() <= now
                ? state.createdAtMillis() : now;
        long timeoutMillis = timeoutSeconds * 1000L;
        long expiresAt = createdAt > Long.MAX_VALUE - timeoutMillis
                ? Long.MAX_VALUE : createdAt + timeoutMillis;
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean requestInFlight = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        AtomicReference<ScheduledTask> taskReference = new AtomicReference<>();

        Runnable completeCancellation = () -> {
            if (completed.compareAndSet(false, true)) {
                removePendingState(orderCode, state);
                ScheduledTask current = taskReference.get();
                if (current != null) current.cancel();
            }
        };

        Runnable polling = () -> {
            ScheduledTask self = taskReference.get();
            if (shuttingDown.get()) {
                if (self != null) self.cancel();
                return;
            }
            if (completed.get()) {
                if (self != null) self.cancel();
                return;
            }
            if (cancelRequested.get()) {
                if (!requestInFlight.get()) completeCancellation.run();
                return;
            }

            if (System.currentTimeMillis() > expiresAt) {
                if (requestInFlight.get()) {
                    return;
                }
                if (completed.compareAndSet(false, true)) {
                    removePendingState(orderCode, state);
                    if (self != null) self.cancel();
                    notifyExpired(callback);
                }
                return;
            }

            if (!requestInFlight.compareAndSet(false, true)) {
                return;
            }
            try {
                PaymentMatch match = queryPayment(orderCode, amount, state);
                if (!shuttingDown.get() && match.paid() && claimPaymentMatch(match, completed)) {
                    removePendingState(orderCode, state);
                    if (self != null) self.cancel();
                    boolean accepted = false;
                    try {
                        accepted = notifyPaid(callback);
                    } finally {
                        if (!accepted) {
                            if (!match.transactionId().isBlank()) {
                                processedSePayTransactions.remove(match.transactionId());
                            }
                            if (!shuttingDown.get()) {
                                plugin.logSevere("Confirmed external payment " + orderCode + " for " + ownerKey
                                        + " was rejected by its owner state; manual reconciliation is required.");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!shuttingDown.get()) {
                    plugin.logDebug("External bank payment polling skipped for " + ownerKey + ": " + e.getMessage(), e);
                }
            } finally {
                requestInFlight.set(false);
                if (cancelRequested.get()) completeCancellation.run();
            }
        };

        ScheduledTask task = plugin.getPlatformScheduler().runTimerAsync(polling, 20L, pollEverySeconds * 20L);
        taskReference.set(task);
        return () -> {
            cancelRequested.set(true);
            if (!requestInFlight.get()) completeCancellation.run();
        };
    }

    public void cancelExternalTransaction(long orderCode) {
        pendingStates.remove(orderCode);
    }

    public void restoreExternalTransaction(long orderCode, String provider, String description, long createdAtMillis) {
        if (shuttingDown.get() || orderCode <= 0L) return;
        String normalizedProvider = PROVIDER_SEPAY.equalsIgnoreCase(provider) ? PROVIDER_SEPAY : PROVIDER_PAYOS;
        String safeDescription = description == null || description.isBlank()
                ? String.valueOf(orderCode) : description;
        long safeCreatedAt = createdAtMillis > 0L ? createdAtMillis : System.currentTimeMillis();
        pendingStates.putIfAbsent(orderCode,
                new TransactionState(normalizedProvider, safeDescription, safeCreatedAt));
    }

    private PaymentMatch queryPayment(long orderCode, long amount, TransactionState state) throws Exception {
        if (shuttingDown.get()) return PaymentMatch.UNPAID;
        if (PROVIDER_SEPAY.equals(state.provider())) {
            return findSePayPayment(amount, state);
        }
        return new PaymentMatch(isPayOSPaid(orderCode), "");
    }

    private boolean isPayOSPaid(long orderCode) throws Exception {
        HttpGet get = new HttpGet(PAYOS_PAYMENT_REQUESTS_API + "/" + orderCode);
        get.setHeader("x-client-id", getPayOSClientId());
        get.setHeader("x-api-key", getPayOSApiKey());

        return executeRequest(get, response -> {
            String res = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(res).getAsJsonObject();
            JsonObject data = json.has("data") && json.get("data").isJsonObject()
                    ? json.getAsJsonObject("data") : null;
            return data != null && "PAID".equalsIgnoreCase(getJsonString(data, "status", ""));
        });
    }

    private PaymentMatch findSePayPayment(long amount, TransactionState state) throws Exception {
        int limit = Math.max(1, Math.min(100, plugin.config().getInt("sepay.transaction-lookup-limit", 20)));
        String minDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(Math.max(0, state.createdAtMillis() - 60000L)));
        String url = SEPAY_TRANSACTIONS_API
                + "?account_number=" + enc(getSePayAccountNumber())
                + "&amount_in=" + amount
                + "&limit=" + limit
                + "&transaction_date_min=" + enc(minDate);

        HttpGet get = new HttpGet(url);
        get.setHeader("Content-Type", "application/json");
        get.setHeader("Authorization", "Bearer " + getSePayApiToken());

        return executeRequest(get, response -> {
            String res = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(res).getAsJsonObject();
            if (json.has("transactions") && json.get("transactions").isJsonArray()) {
                JsonArray transactions = json.getAsJsonArray("transactions");
                for (JsonElement element : transactions) {
                    if (!element.isJsonObject()) continue;
                    JsonObject transaction = element.getAsJsonObject();
                    String transactionId = getJsonString(transaction, "id", getJsonString(transaction, "reference_number", ""));
                    if (!transactionId.isEmpty() && processedSePayTransactions.contains(transactionId)) {
                        continue;
                    }

                    if (isMatchingSePayTransaction(transaction, amount, state.description())) {
                        return new PaymentMatch(true, transactionId);
                    }
                }
            } else if (json.has("status") && json.get("status").getAsInt() != 200) {
                if (!shuttingDown.get()) {
                    plugin.logWarning("SePay transaction lookup failed. Enable logging.debug for raw response.");
                    plugin.logDebug("SePay transaction lookup raw response: " + res);
                }
            }
            return PaymentMatch.UNPAID;
        });
    }

    private boolean isMatchingSePayTransaction(JsonObject transaction, long amount, String description) {
        long amountIn = getMoneyLong(transaction, "amount_in", getMoneyLong(transaction, "transferAmount", 0L));
        if (amountIn != amount) return false;

        String target = normalizeTransferContent(description);
        String content = normalizeTransferContent(getJsonString(transaction, "transaction_content", ""));
        String code = normalizeTransferContent(getJsonString(transaction, "code", ""));
        String contentAlt = normalizeTransferContent(getJsonString(transaction, "content", ""));
        return !target.isBlank() && (content.contains(target) || code.contains(target) || contentAlt.contains(target));
    }

    private boolean claimPaymentMatch(PaymentMatch match, AtomicBoolean terminal) {
        boolean claimedTransaction = false;
        if (!match.transactionId().isBlank()) {
            claimedTransaction = processedSePayTransactions.add(match.transactionId());
            if (!claimedTransaction) {
                return false;
            }
        }

        if (terminal.compareAndSet(false, true)) {
            return true;
        }

        if (claimedTransaction) {
            processedSePayTransactions.remove(match.transactionId());
        }
        return false;
    }

    private void markPaymentSuccess(Player player, PollingSession session, PaymentMatch match) {
        if (shuttingDown.get()) {
            return;
        }
        if (!claimPaymentMatch(match, session.terminal)) {
            return;
        }
        session.confirmedPayment.set(match);
        scheduleConfirmedReward(player, session);
    }

    private void scheduleConfirmedReward(Player player, PollingSession session) {
        if (shuttingDown.get() || session.confirmedPayment.get() == null
                || !session.rewardStarted.compareAndSet(false, true)) {
            return;
        }

        boolean scheduled = plugin.getPlatformScheduler().runGlobal(() -> {
            if (shuttingDown.get()) return;
            boolean rewardDelivered = false;
            try {
                rewardDelivered = executeSuccess(session.playerName, session.amount, session.orderCode, session.state);
            } catch (Throwable throwable) {
                plugin.logSevere("CRITICAL: Unexpected error while settling paid bank order "
                        + session.orderCode + " for " + session.playerName + ".", throwable);
                sendFailureWebhook(session.playerName, session.amount, "reward-settlement-error:" + session.orderCode);
            } finally {
                finishSession(session);
                notifyPlayerSettlement(player, session, rewardDelivered);
            }
        });

        if (!scheduled && !shuttingDown.get()) {
            session.rewardStarted.set(false);
            plugin.logWarning("Paid bank order " + session.orderCode + " for " + session.playerName
                    + " is waiting for the global scheduler; SotarPayments will retry automatically.");
        }
    }

    private boolean executeSuccess(String playerName, long amount, long orderCode, TransactionState state) {
        int finalPoints = plugin.calculateFinalPoints(amount, PaymentChannel.BANK);
        plugin.getLogManager().log("BANK_SUCCESS: " + playerName + " topup " + amount + " " + plugin.trPlain("general.currency") + ".");

        EconomyManager.DeliveryResult delivery = plugin.getEconomyManager().deliverBankOrManual(
                playerName, finalPoints, amount, PaymentChannel.BANK);
        boolean rewardDelivered = delivery.success();
        if (!rewardDelivered) {
            plugin.logSevere("Reward delivery failed for paid bank order " + orderCode + " via "
                    + delivery.provider() + ": " + delivery.failureReason());
            sendFailureWebhook(playerName, amount, "reward-delivery-failed:" + orderCode);
        } else {
            plugin.broadcast(plugin.tr("bank.success-broadcast", "player", playerName, "amount", plugin.formatMoney(amount)));
        }
        plugin.processSuccessPayment(playerName, amount, PaymentChannel.BANK, state.provider(),
                "Order: " + orderCode + " | Content: " + state.description()
                        + " | Economy: " + delivery.provider()
                        + " | Reward: " + (rewardDelivered ? "DELIVERED" : "FAILED"));
        return rewardDelivered;
    }

    private void notifyPlayerSettlement(Player player, PollingSession session, boolean rewardDelivered) {
        plugin.getPlatformScheduler().runPlayer(player, () -> {
            if (shuttingDown.get() || !player.isOnline()) return;
            removeQRMapNow(player, session.mapId);
            if (!rewardDelivered) {
                player.sendMessage(plugin.tr("bank.reward-failed", "order", session.orderCode));
                return;
            }
            int finalPoints = plugin.calculateFinalPoints(session.amount, PaymentChannel.BANK);
            int bonusPoints = plugin.calculateBonusPoints(session.amount, PaymentChannel.BANK);
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(plugin.tr("bank.success-actionbar")));
            player.sendMessage(plugin.tr("bank.success-points", "points", plugin.formatMoney(finalPoints)));
            if (bonusPoints > 0) {
                player.sendMessage(plugin.tr("promotion.bonus-channel",
                        "bonus", plugin.getPromotionPercent(PaymentChannel.BANK),
                        "type", plugin.trPlain(PaymentChannel.BANK.languageKey())));
            }
            plugin.spawnSuccessFirework(player.getLocation());
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        });
    }

    private void expirePlayerPayment(Player player, PollingSession session) {
        if (shuttingDown.get()) return;
        if (!session.terminal.compareAndSet(false, true)) {
            return;
        }
        finishSession(session);
        plugin.getPlatformScheduler().runPlayer(player, () -> {
            if (!shuttingDown.get() && player.isOnline()) {
                player.sendMessage(plugin.tr("bank.expired"));
                removeQRMapNow(player, session.mapId);
            }
        });
        sendFailureWebhook(session.playerName, session.amount, "timeout");
    }

    private void finishSession(PollingSession session) {
        removePendingState(session.orderCode, session.state);
        activeTasks.remove(session.playerId, session);
        cancelTask(session);
    }

    private void cancelTask(PollingSession session) {
        ScheduledTask task = session.task.get();
        if (task != null) {
            task.cancel();
        }
    }

    private void removePendingState(long orderCode, TransactionState expectedState) {
        pendingStates.computeIfPresent(orderCode,
                (ignored, currentState) -> currentState == expectedState ? null : currentState);
    }

    private void sendFailureWebhook(String playerName, long amount, String reason) {
        if (!shuttingDown.get() && plugin.getTransactionWebhookManager() != null) {
            plugin.getTransactionWebhookManager().sendFailure(PaymentChannel.BANK, playerName, amount, reason);
        }
    }

    public void cancelPolling(Player player) {
        PollingSession session = activeTasks.get(player.getUniqueId());
        if (session != null && session.terminal.compareAndSet(false, true)) {
            finishSession(session);
            removeAnyQRMap(player);
        }
    }

    /** Stops polling and rejects new network work while the plugin is unloading. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;

        for (HttpUriRequestBase request : activeHttpRequests) {
            request.cancel();
        }
        httpClient.close(CloseMode.IMMEDIATE);
        activeHttpRequests.clear();

        // Let callbacks that started before the shutdown signal finish. New callbacks observe
        // shuttingDown while holding the same monitor and are therefore suppressed.
        synchronized (callbackLock) {
            // Lifecycle barrier only.
        }

        for (PollingSession session : activeTasks.values()) {
            session.terminal.set(true);
            cancelTask(session);
        }
        activeTasks.clear();
        pendingStates.clear();
        processedSePayTransactions.clear();
    }

    private void removeQRMap(Player player, int mapId) {
        plugin.getPlatformScheduler().runPlayer(player, () -> removeQRMapNow(player, mapId));
    }

    private void removeQRMapNow(Player player, int mapId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.FILLED_MAP) {
                MapMeta meta = (MapMeta) item.getItemMeta();
                if (meta != null && meta.hasMapView() && meta.getMapView().getId() == mapId) {
                    player.getInventory().remove(item);
                    break;
                }
            }
        }
    }

    private void removeAnyQRMap(Player player) {
        plugin.getPlatformScheduler().runPlayer(player, () -> {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == Material.FILLED_MAP) player.getInventory().remove(item);
            }
        });
    }

    private void sendPlayerMessage(Player player, String message) {
        if (shuttingDown.get()) return;
        plugin.getPlatformScheduler().runPlayer(player, () -> {
            if (!shuttingDown.get()) player.sendMessage(message);
        });
    }

    private void sendCreateTransactionError(Player player, String message) {
        sendPlayerMessage(player, message);
    }

    private long buildOrderCode() {
        long now = System.currentTimeMillis();
        return Math.abs(now % 9000000000L) + 1000000000L;
    }

    private String buildPaymentDescription(String configPath, String payerName, long orderCode, String defaultFormat, int maxLength) {
        String format = plugin.config().getString(configPath, defaultFormat);
        String raw = format
                .replace("{playername}", payerName)
                .replace("{player}", payerName)
                .replace("{ordercode}", String.valueOf(orderCode))
                .replace("{code}", String.valueOf(orderCode));
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        String code = String.valueOf(orderCode);
        if (normalized.isBlank()) {
            normalized = "KP" + code;
        }

        // Giữ mã đơn trong nội dung chuyển khoản để tránh giao dịch không khớp khi format quá dài.
        if (!normalized.contains(code) && maxLength >= code.length() + 2) {
            normalized = "KP " + code;
        }

        if (normalized.length() > maxLength) {
            if (normalized.contains(code) && maxLength >= code.length() + 2) {
                int roomForPrefix = Math.max(0, maxLength - code.length() - 1);
                String prefix = normalized.substring(0, Math.min(roomForPrefix, normalized.indexOf(code))).trim();
                normalized = (prefix.isBlank() ? "KP" : prefix) + " " + code;
                if (normalized.length() > maxLength) {
                    normalized = normalized.substring(normalized.length() - maxLength).trim();
                }
            } else {
                normalized = normalized.substring(0, maxLength).trim();
            }
        }
        return normalized;
    }

    private String buildSePayQrImageUrl(long amount, String description) {
        String template = "compact2";
        String base = "https://img.vietqr.io/image/"
                + encPath(getSePayBankCode())
                + "-" + encPath(getSePayAccountNumber())
                + "-" + template
                + ".png";

        return base
                + "?amount=" + amount
                + "&addInfo=" + enc(description)
                + "&accountName=" + enc(getSePayAccountName());
    }

    private String buildQrImageUrl(String qrContentOrUrl) {
        String value = qrContentOrUrl == null ? "" : qrContentOrUrl.trim();
        if (value.isBlank()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("http://") || lower.startsWith("https://"))
                && (lower.endsWith(".png") || lower.contains(".png?") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"))) {
            return forceVietQrOnlyUrl(value);
        }
        return QUICK_CHART_QR_API + "?size=512&margin=2&text=" + enc(value);
    }

    private String forceVietQrOnlyUrl(String imageUrl) {
        if (imageUrl == null) return "";
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        if (!lower.contains("img.vietqr.io/image/") || !lower.contains(".png")) {
            return imageUrl;
        }

        int pngIndex = lower.indexOf(".png");
        int lastDashBeforePng = imageUrl.lastIndexOf('-', pngIndex);
        if (lastDashBeforePng < 0) {
            return imageUrl;
        }

        return imageUrl.substring(0, lastDashBeforePng + 1) + "qr_only" + imageUrl.substring(pngIndex);
    }

    private String generatePayOSSignature(long amount, String cancelUrl, String description, long orderCode, String returnUrl) throws Exception {
        String data = "amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl;
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(getPayOSChecksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private CloseableHttpClient createHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    private <T> T executeRequest(HttpUriRequestBase request,
                                 HttpClientResponseHandler<? extends T> responseHandler) throws IOException {
        if (shuttingDown.get()) {
            throw shutdownException();
        }

        activeHttpRequests.add(request);
        if (shuttingDown.get()) {
            activeHttpRequests.remove(request);
            request.cancel();
            throw shutdownException();
        }

        try {
            return httpClient.execute(request, responseHandler);
        } finally {
            activeHttpRequests.remove(request);
        }
    }

    private InterruptedIOException shutdownException() {
        return new InterruptedIOException("Bank payment HTTP client is shutting down");
    }

    private void notifySuccess(PaymentOrderCallback callback, PaymentOrder order) {
        synchronized (callbackLock) {
            if (!shuttingDown.get()) callback.onSuccess(order);
        }
    }

    private void notifyFailure(PaymentOrderCallback callback, String message) {
        synchronized (callbackLock) {
            if (!shuttingDown.get()) callback.onFailure(message);
        }
    }

    private boolean notifyPaid(ExternalPaymentCallback callback) {
        synchronized (callbackLock) {
            return !shuttingDown.get() && callback.onPaid();
        }
    }

    private void notifyExpired(ExternalPaymentCallback callback) {
        synchronized (callbackLock) {
            if (!shuttingDown.get()) callback.onExpired();
        }
    }

    private String getProvider() {
        String provider = plugin.getBankProviderName();
        return PROVIDER_SEPAY.equals(provider) ? PROVIDER_SEPAY : PROVIDER_PAYOS;
    }

    private String getPayOSClientId() { return plugin.config().getString("payos.client-id", ""); }
    private String getPayOSApiKey() { return plugin.config().getString("payos.api-key", ""); }
    private String getPayOSChecksumKey() { return plugin.config().getString("payos.checksum-key", ""); }

    private boolean hasPayOSCredentials() {
        return !getPayOSClientId().isBlank() && !getPayOSApiKey().isBlank() && !getPayOSChecksumKey().isBlank();
    }

    private String getSePayApiToken() { return plugin.config().getString("sepay.api-token", ""); }
    private String getSePayBankCode() { return plugin.config().getString("sepay.bank-code", ""); }
    private String getSePayBankName() { return plugin.config().getString("sepay.bank-name", getSePayBankCode()); }
    private String getSePayAccountNumber() { return plugin.config().getString("sepay.account-number", ""); }
    private String getSePayAccountName() { return plugin.config().getString("sepay.account-name", ""); }

    private boolean hasSePayCredentials() {
        return !getSePayApiToken().isBlank()
                && !getSePayBankCode().isBlank()
                && !getSePayAccountNumber().isBlank()
                && !getSePayAccountName().isBlank();
    }

    private String getJsonString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private long getMoneyLong(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return new BigDecimal(object.get(key).getAsString()).longValueExact();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String normalizeTransferContent(String value) {
        if (value == null) return "";
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.replaceAll("[^a-zA-Z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String sanitizePayerName(String value) {
        if (value == null || value.isBlank()) return "Player";
        String normalized = value.trim().replaceAll("[^a-zA-Z0-9_ ]", "").trim();
        return normalized.isBlank() ? "Player" : normalized;
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encPath(String value) {
        return enc(value).replace("+", "%20");
    }

    public interface TransactionCallback {
        void onSuccess(JsonObject data, long orderCode);
    }

    public interface PaymentOrderCallback {
        void onSuccess(PaymentOrder order);
        void onFailure(String message);
    }

    public interface ExternalPaymentCallback {
        /**
         * @return true only when the owner atomically accepted the paid state.
         */
        boolean onPaid();
        void onExpired();
    }
}
