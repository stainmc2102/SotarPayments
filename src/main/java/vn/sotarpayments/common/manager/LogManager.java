package vn.sotarpayments.common.manager;

import vn.sotarpayments.SotarPayments;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class LogManager {
    private static final DateTimeFormatter TRANSACTION_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter CARD_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long DEFAULT_SPAM_WINDOW_SECONDS = 30L;
    private static final int MAX_CONSOLE_MESSAGE_LENGTH = 2000;

    private final SotarPayments plugin;
    private final ExecutorService fileExecutor;
    private final Map<String, SpamState> spamStates = new ConcurrentHashMap<>();

    private volatile File transactionLogFile;
    private volatile File cardLogFile;
    private volatile boolean debugEnabled;
    private volatile boolean consoleEnabled;
    private volatile boolean transactionConsoleEnabled;
    private volatile boolean fileEnabled;
    private volatile boolean asyncFileEnabled;
    private volatile boolean antiSpamEnabled;
    private volatile long duplicateWindowMillis;

    public LogManager(SotarPayments plugin) {
        this.plugin = plugin;
        this.fileExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SotarPayments-AsyncLogger");
            thread.setDaemon(true);
            return thread;
        });
        reloadSettings();
    }

    public void reloadSettings() {
        this.debugEnabled = plugin.config().getBoolean("logging.debug", false);
        this.consoleEnabled = plugin.config().getBoolean("logging.console.enabled", true);
        this.transactionConsoleEnabled = plugin.config().getBoolean("logging.console.transaction-events", true);
        this.antiSpamEnabled = plugin.config().getBoolean("logging.console.anti-spam", true);
        this.duplicateWindowMillis = Math.max(1L, plugin.config().getLong("logging.console.duplicate-window-seconds", DEFAULT_SPAM_WINDOW_SECONDS)) * 1000L;
        this.fileEnabled = plugin.config().getBoolean("logging.file.enabled", true);
        this.asyncFileEnabled = plugin.config().getBoolean("logging.file.async", true);
        this.transactionLogFile = resolveLogFile(plugin.config().getString("logging.file.transaction-log", "transactions.log"));
        this.cardLogFile = resolveLogFile(plugin.config().getString("logging.file.card-log", "nap_the.txt"));
        ensureLogFile(transactionLogFile);
        ensureLogFile(cardLogFile);
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void debug(String message) {
        debug(message, null);
    }

    public void debug(String message, Throwable throwable) {
        if (!debugEnabled) return;
        console(Level.INFO, "[DEBUG] " + safeConsoleMessage(message), throwable);
    }

    public void info(String message) {
        console(Level.INFO, message, null);
    }

    public void warning(String message) {
        warning(message, null);
    }

    public void warning(String message, Throwable throwable) {
        console(Level.WARNING, message, throwable);
    }

    public void severe(String message) {
        severe(message, null);
    }

    public void severe(String message, Throwable throwable) {
        console(Level.SEVERE, message, throwable);
    }

    public void console(Level level, String message, Throwable throwable) {
        Level safeLevel = level == null ? Level.INFO : level;
        if (!consoleEnabled && safeLevel.intValue() < Level.SEVERE.intValue()) {
            return;
        }

        String cleanMessage = safeConsoleMessage(message);
        String throttledMessage = applyAntiSpam(safeLevel, cleanMessage, throwable);
        if (throttledMessage == null) {
            return;
        }

        if (throwable == null) {
            plugin.getLogger().log(safeLevel, throttledMessage);
        } else {
            plugin.getLogger().log(safeLevel, throttledMessage, throwable);
        }
    }

    public void log(String type, String message) {
        String safeType = safeType(type);
        String cleanMessage = safeFileMessage(message);
        String line = "[" + TRANSACTION_TIME_FORMAT.format(LocalDateTime.now()) + "] [" + safeType + "] " + cleanMessage;

        if (transactionConsoleEnabled) {
            console(Level.INFO, "[" + safeType + "] " + cleanMessage, null);
        }
        writeLine(transactionLogFile, line);
    }

    public void log(String message) {
        log("INFO", message);
    }

    public void payment(String message) {
        log("PAYMENT", message);
    }

    public void manual(String message) {
        log("MANUAL", message);
    }

    public void card(String message) {
        log("CARD", message);
    }

    public void admin(String message) {
        log("ADMIN", message);
    }

    public void system(String message) {
        log("SYSTEM", message);
    }

    public void cardTransaction(String playerName, int amount, int netAmount, int points) {
        String line = "[" + CARD_TIME_FORMAT.format(LocalDateTime.now()) + "] Player: " + safeFileMessage(playerName)
                + " | Amount: " + amount
                + " | Net: " + netAmount
                + " | Points: " + points;
        writeLine(cardLogFile, line);
        debug("Saved card transaction log for " + safeConsoleMessage(playerName) + ".");
    }

    public void shutdown() {
        fileExecutor.shutdown();
        try {
            if (!fileExecutor.awaitTermination(3L, TimeUnit.SECONDS)) {
                fileExecutor.shutdownNow();
                if (!fileExecutor.awaitTermination(1L, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("SotarPayments log writer did not stop within the safety timeout.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fileExecutor.shutdownNow();
        }
    }

    private void writeLine(File file, String line) {
        if (!fileEnabled || file == null || line == null || line.isBlank()) {
            return;
        }

        Runnable writer = () -> {
            try {
                ensureLogFile(file);
                try (BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                    bufferedWriter.write(line);
                    bufferedWriter.newLine();
                }
            } catch (IOException e) {
                console(Level.WARNING, "Cannot write log file '" + file.getName() + "': " + e.getMessage(), null);
                debug("Cannot write log file '" + file.getAbsolutePath() + "'.", e);
            }
        };

        if (asyncFileEnabled && !fileExecutor.isShutdown()) {
            try {
                fileExecutor.execute(writer);
                return;
            } catch (RuntimeException ignored) {
                // Fallback to synchronous write if the executor is shutting down during plugin disable.
            }
        }
        writer.run();
    }

    private File resolveLogFile(String configuredName) {
        String safeName = configuredName == null || configuredName.isBlank() ? "transactions.log" : configuredName.trim();
        File file = new File(safeName);
        return file.isAbsolute() ? file : new File(plugin.getDataFolder(), safeName);
    }

    private void ensureLogFile(File file) {
        if (!fileEnabled || file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                console(Level.WARNING, "Cannot create log folder: " + parent.getAbsolutePath(), null);
                return;
            }
            if (!file.exists() && !file.createNewFile()) {
                console(Level.WARNING, "Cannot create log file: " + file.getAbsolutePath(), null);
            }
        } catch (IOException e) {
            console(Level.WARNING, "Cannot create log file '" + file.getName() + "': " + e.getMessage(), null);
            debug("Cannot create log file '" + file.getAbsolutePath() + "'.", e);
        }
    }

    private String applyAntiSpam(Level level, String message, Throwable throwable) {
        if (!antiSpamEnabled || duplicateWindowMillis <= 0L) {
            return message;
        }

        String key = level.getName() + '|' + message + '|' + (throwable == null ? "" : throwable.getClass().getName());
        SpamState state = spamStates.computeIfAbsent(key, ignored -> new SpamState());
        long now = System.currentTimeMillis();

        synchronized (state) {
            if (state.lastLoggedAt == 0L || now - state.lastLoggedAt >= duplicateWindowMillis) {
                int suppressed = state.suppressed;
                state.suppressed = 0;
                state.lastLoggedAt = now;
                if (suppressed > 0) {
                    return message + " (đã ẩn " + suppressed + " dòng lặp trong "
                            + Math.max(1L, duplicateWindowMillis / 1000L) + "s)";
                }
                return message;
            }

            state.suppressed++;
            return null;
        }
    }

    private String safeType(String type) {
        String raw = type == null || type.isBlank() ? "INFO" : type.trim().toUpperCase(Locale.ROOT);
        String clean = raw.replaceAll("[^A-Z0-9_-]", "_");
        return clean.isBlank() ? "INFO" : clean;
    }

    private String safeConsoleMessage(String message) {
        if (message == null) return "";
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() <= MAX_CONSOLE_MESSAGE_LENGTH) {
            return clean;
        }
        return clean.substring(0, MAX_CONSOLE_MESSAGE_LENGTH) + "...";
    }

    private String safeFileMessage(String message) {
        if (message == null) return "";
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static final class SpamState {
        private long lastLoggedAt;
        private int suppressed;
    }
}
