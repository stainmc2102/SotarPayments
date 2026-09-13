package vn.sotarpayments.napcard.api;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napcard.models.CardRequest;
import vn.sotarpayments.napcard.utils.HashUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CardChargingService {
    private static final String PROVIDER_CARD2K = "card2k";
    private static final String PROVIDER_GACHTHEFAST = "gachthefast";
    private static final String PROVIDER_THESIEURE = "thesieure";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 8L;
    private static final long GRACEFUL_EXECUTOR_TIMEOUT_SECONDS = 5L;
    private static final long FORCED_EXECUTOR_TIMEOUT_SECONDS = 2L;

    private final SotarPayments plugin;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
    private int activeCallbacks;

    public CardChargingService(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public String getProvider() {
        String provider = plugin.getCardProviderName();
        if (PROVIDER_THESIEURE.equals(provider)) {
            return PROVIDER_THESIEURE;
        }
        if (PROVIDER_GACHTHEFAST.equals(provider)) {
            return PROVIDER_GACHTHEFAST;
        }
        return PROVIDER_CARD2K;
    }

    public String getProviderDisplayName() {
        return switch (getProvider()) {
            case PROVIDER_GACHTHEFAST -> "GachTheFast";
            case PROVIDER_THESIEURE -> "Thesieure";
            default -> "Card2K";
        };
    }

    public boolean hasCredentials() {
        ProviderConfig config = getProviderConfig();
        return !config.partnerId().isBlank() && !config.partnerKey().isBlank() && !config.domain().isBlank();
    }

    public void sendRequest(CardRequest card, String command, Callback callback) {
        Objects.requireNonNull(callback, "callback");
        if (shutdown.get()) return;

        ProviderConfig config = getProviderConfig();
        String sign = HashUtils.md5(config.partnerKey() + card.getCode() + card.getSerial());
        if (sign == null) {
            deliverFailure(callback, null, new IOException("Cannot create card signature"));
            return;
        }

        try {
            Request request;
            if ("GET".equalsIgnoreCase(config.method())) {
                HttpUrl.Builder urlBuilder = HttpUrl.parse(config.url()).newBuilder()
                        .addQueryParameter("telco", card.getTelco())
                        .addQueryParameter("code", card.getCode())
                        .addQueryParameter("serial", card.getSerial())
                        .addQueryParameter("amount", String.valueOf(card.getAmount()))
                        .addQueryParameter("request_id", card.getRequestId())
                        .addQueryParameter("partner_id", config.partnerId())
                        .addQueryParameter("sign", sign)
                        .addQueryParameter("command", command);

                request = new Request.Builder()
                        .url(urlBuilder.build())
                        .get()
                        .addHeader("Accept", "application/json")
                        .build();
            } else {
                RequestBody body = new FormBody.Builder()
                        .add("telco", card.getTelco())
                        .add("code", card.getCode())
                        .add("serial", card.getSerial())
                        .add("amount", String.valueOf(card.getAmount()))
                        .add("request_id", card.getRequestId())
                        .add("partner_id", config.partnerId())
                        .add("sign", sign)
                        .add("command", command)
                        .build();

                request = new Request.Builder()
                        .url(config.url())
                        .post(body)
                        .addHeader("Accept", "application/json")
                        .build();
            }

            synchronized (lifecycleLock) {
                if (shutdown.get()) return;
                client.newCall(request).enqueue(new GuardedCallback(callback));
            }
        } catch (Exception e) {
            deliverFailure(callback, null, new IOException("Cannot build card request", e));
        }
    }

    /**
     * Stops all card-provider I/O owned by this plugin instance. This method is safe to call
     * more than once and must run before the plugin classloader is released.
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (!shutdown.compareAndSet(false, true)) return;
            // Holding the same lock used by sendRequest guarantees that no call can be
            // enqueued after cancelAll has taken its snapshot.
            client.dispatcher().cancelAll();
        }

        ExecutorService dispatcherExecutor = client.dispatcher().executorService();
        dispatcherExecutor.shutdown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        boolean dispatcherTerminated = false;
        boolean callbacksDrained = false;
        boolean interrupted = false;
        try {
            dispatcherTerminated = awaitExecutor(
                    dispatcherExecutor,
                    deadline,
                    TimeUnit.SECONDS.toNanos(GRACEFUL_EXECUTOR_TIMEOUT_SECONDS)
            );
            if (!dispatcherTerminated) {
                dispatcherExecutor.shutdownNow();
                dispatcherTerminated = awaitExecutor(
                        dispatcherExecutor,
                        deadline,
                        TimeUnit.SECONDS.toNanos(FORCED_EXECUTOR_TIMEOUT_SECONDS)
                );
            }
            callbacksDrained = awaitActiveCallbacks(deadline);
        } catch (InterruptedException e) {
            dispatcherExecutor.shutdownNow();
            interrupted = true;
        } finally {
            // A running call can return its connection after cancellation, so eviction
            // belongs after every graceful or forced drain attempt.
            client.connectionPool().evictAll();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!dispatcherTerminated || !callbacksDrained) {
            plugin.logWarning("Card-provider HTTP workers did not fully stop within the "
                    + SHUTDOWN_TIMEOUT_SECONDS + " second safety timeout.");
        }
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    private void deliverFailure(Callback callback, Call call, IOException exception) {
        if (!beginCallback()) return;
        try {
            callback.onFailure(call, exception);
        } finally {
            endCallback();
        }
    }

    private boolean beginCallback() {
        synchronized (lifecycleLock) {
            if (shutdown.get()) return false;
            activeCallbacks++;
            return true;
        }
    }

    private void endCallback() {
        synchronized (lifecycleLock) {
            activeCallbacks--;
            if (activeCallbacks == 0) {
                lifecycleLock.notifyAll();
            }
        }
    }

    private boolean awaitActiveCallbacks(long deadline) throws InterruptedException {
        synchronized (lifecycleLock) {
            while (activeCallbacks > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
                lifecycleLock.wait(millis, nanos);
            }
            return true;
        }
    }

    private boolean awaitExecutor(ExecutorService executor, long overallDeadline, long phaseBudgetNanos)
            throws InterruptedException {
        long remaining = overallDeadline - System.nanoTime();
        if (remaining <= 0L) return executor.isTerminated();
        long waitNanos = Math.min(remaining, Math.max(0L, phaseBudgetNanos));
        return waitNanos > 0L
                ? executor.awaitTermination(waitNanos, TimeUnit.NANOSECONDS)
                : executor.isTerminated();
    }

    private final class GuardedCallback implements Callback {
        private final Callback delegate;

        private GuardedCallback(Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onFailure(Call call, IOException exception) {
            deliverFailure(delegate, call, exception);
        }

        @Override
        public void onResponse(Call call, okhttp3.Response response) throws IOException {
            if (!beginCallback()) {
                response.close();
                return;
            }
            try (response) {
                delegate.onResponse(call, response);
            } finally {
                endCallback();
            }
        }
    }

    private ProviderConfig getProviderConfig() {
        String provider = getProvider();
        String basePath = switch (provider) {
            case PROVIDER_GACHTHEFAST -> "gachthefast";
            case PROVIDER_THESIEURE -> "thesieure";
            default -> "card2k";
        };
        String defaultDomain = switch (provider) {
            case PROVIDER_GACHTHEFAST -> "gachthefast.com";
            case PROVIDER_THESIEURE -> "thesieure.com";
            default -> "card2k.com";
        };
        String defaultMethod = PROVIDER_GACHTHEFAST.equals(provider) ? "GET" : "POST";

        String domain = plugin.config().getString(basePath + ".api.domain", defaultDomain);
        String endpoint = plugin.config().getString(basePath + ".api.endpoint", "/chargingws/v2");
        String method = plugin.config().getString(basePath + ".api.method", defaultMethod);
        String partnerId = plugin.config().getString(basePath + ".api.partner_id", "");
        String partnerKey = plugin.config().getString(basePath + ".api.partner_key", "");

        return new ProviderConfig(partnerId, partnerKey, domain, endpoint, method, buildUrl(domain, endpoint));
    }

    private String buildUrl(String domain, String endpoint) {
        String base = domain == null ? "" : domain.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = endpoint == null || endpoint.isBlank() ? "/chargingws/v2" : endpoint.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private record ProviderConfig(String partnerId, String partnerKey, String domain, String endpoint, String method, String url) {}
}
