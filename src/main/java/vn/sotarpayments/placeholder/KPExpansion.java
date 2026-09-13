package vn.sotarpayments.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.manager.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class KPExpansion extends PlaceholderExpansion {
    private static final String NOT_AVAILABLE = "N/A";
    private static final String ZERO = "0";

    private static final long TOP_CACHE_MILLIS = 3000L;
    private static final long AMOUNT_CACHE_MILLIS = 3000L;
    private static final int DEFAULT_TOP_CACHE_LIMIT = 10;
    private static final int MAX_AMOUNT_CACHE_SIZE = 2048;

    private static final Set<String> RAW_NUMBER_MODIFIERS = Set.of(
            "raw",
            "number",
            "plain",
            "unformatted",
            "noformat"
    );

    private final SotarPayments plugin;
    private final String identifier;

    private final Object topCacheLock = new Object();
    private final Object amountCacheLock = new Object();

    private final Map<Long, TopCache> topCache = new ConcurrentHashMap<>();
    private final Map<String, AmountCache> amountCache = new ConcurrentHashMap<>();

    public KPExpansion(SotarPayments plugin) {
        this(plugin, "kp");
    }

    public KPExpansion(SotarPayments plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = normalizeIdentifier(identifier);
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    private String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "kp";
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isBlank() ? "kp" : normalized;
    }

    @Override
    public String getAuthor() {
        return "DuyDuong";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    /*
     * Placeholder chinh thuc theo PDF:
     *
     * Tong nap ca nhan:
     * %kp_donate_total%
     * %kp_donate_total_today%
     * %kp_donate_total_week%
     * %kp_donate_total_month%
     * %kp_donate_total_year%
     *
     * Tong server:
     * %kp_donate_server_total%
     *
     * Moc nap server:
     * %kp_mocnapserver%
     * %kp_mocnapserver_total%
     * %kp_mocnapserver_progress_1000000%
     * %kp_mocnapserver_percent_1000000%
     *
     * Top nap:
     * %kp_donate_total_top_player_1%
     * %kp_donate_total_top_amount_1%
     * %kp_donate_total_top_player_1_today%
     * %kp_donate_total_top_amount_1_today%
     * %kp_donate_total_top_player_1_week%
     * %kp_donate_total_top_amount_1_week%
     * %kp_donate_total_top_player_1_month%
     * %kp_donate_total_top_amount_1_month%
     * %kp_donate_total_top_player_1_year%
     * %kp_donate_total_top_amount_1_year%
     *
     * Alias giu tuong thich nguoc:
     * %sotarpayments_...%
     * %kp_player_total%, %kp_total%
     * %kp_server_total%, %kp_total_server%
     * %kp_top_player_1%, %kp_top_amount_1%
     * %kp_top_1_player%, %kp_top_1_amount%
     * %kp_transactions%, %kp_transaction_count%
     *
     * Modifier so khong format:
     * _raw, _number, _plain, _unformatted, _noformat
     */

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return resolvePlaceholder(getPlayerName(player), params);
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return resolvePlaceholder(player == null ? null : player.getName(), params);
    }

    public void clearCaches() {
        synchronized (topCacheLock) {
            topCache.clear();
        }
        synchronized (amountCacheLock) {
            amountCache.clear();
        }
    }

    private String resolvePlaceholder(String playerName, String params) {
        if (params == null || params.isBlank()) {
            return null;
        }

        String query = normalizeQuery(params);
        if (query.isBlank()) {
            return null;
        }

        String lookupQuery = stripTrailingFormatModifiers(query);
        if (lookupQuery.isBlank()) {
            return null;
        }

        try {
            if (isTransactionCountPlaceholder(lookupQuery)) {
                long count = getCachedAmount("transactions", () -> plugin.getDatabaseManager().getTransactionCount());
                return formatAmount(count, isRawNumber(query));
            }

            if (isMocNapServerPlaceholder(lookupQuery)) {
                return resolveMocNapServerPlaceholder(query, lookupQuery);
            }

            long startTime = getStartTime(lookupQuery);

            /*
             * Luon check TOP truoc donate_total/player_total.
             * Neu check total truoc, %kp_donate_total_top_player_1% se bi nhan nham
             * thanh placeholder tong nap ca nhan.
             */
            if (isTopPlaceholder(lookupQuery)) {
                return resolveTopPlaceholder(query, lookupQuery, startTime);
            }

            if (isServerTotalPlaceholder(lookupQuery)) {
                long amount = getCachedAmount("server:" + startTime, () -> {
                    if (startTime <= 0L) {
                        return plugin.getDatabaseManager().getServerTotalDonated();
                    }
                    return plugin.getDatabaseManager().getRevenueSince(startTime);
                });

                return formatAmount(amount, isRawNumber(query));
            }

            if (isPersonalTotalPlaceholder(lookupQuery)) {
                if (playerName == null || playerName.isBlank()) {
                    return ZERO;
                }

                String safeName = playerName.trim();
                String cacheKey = "player:" + safeName.toLowerCase(Locale.ROOT) + ":" + startTime;

                long amount = getCachedAmount(cacheKey, () -> {
                    if (startTime <= 0L) {
                        return plugin.getDatabaseManager().getTotalDonated(safeName);
                    }
                    return plugin.getDatabaseManager().getPersonalRevenue(safeName, startTime);
                });

                return formatAmount(amount, isRawNumber(query));
            }

            return null;
        } catch (Throwable throwable) {
            if (plugin.isDebugMode()) {
                plugin.logDebug("PlaceholderAPI resolve failed for %" + identifier + "_" + query + "%.", throwable);
            }

            /*
             * Khong de loi DB/runtime lam scoreboard hien nguyen placeholder.
             * Cac nhom da nhan dien se fallback an toan.
             */
            if (isKnownSotarPlaceholder(lookupQuery)) {
                return ZERO;
            }

            return null;
        }
    }

    private boolean isKnownSotarPlaceholder(String query) {
        return query.startsWith("donate")
                || query.startsWith("top")
                || query.startsWith("server")
                || query.startsWith("player")
                || query.startsWith("total")
                || query.startsWith("mocnapserver")
                || query.startsWith("transaction");
    }

    private String getPlayerName(OfflinePlayer player) {
        if (player == null) {
            return null;
        }

        String name = player.getName();
        if (name == null || name.isBlank()) {
            return null;
        }

        return name.trim();
    }

    private String normalizeQuery(String input) {
        String query = input.trim().toLowerCase(Locale.ROOT);

        while (query.startsWith("%")) {
            query = query.substring(1).trim();
        }

        while (query.endsWith("%")) {
            query = query.substring(0, query.length() - 1).trim();
        }

        while (query.startsWith("{") || query.startsWith("[") || query.startsWith("(")) {
            query = query.substring(1).trim();
        }

        while (query.endsWith("}") || query.endsWith("]") || query.endsWith(")")) {
            query = query.substring(0, query.length() - 1).trim();
        }

        /*
         * Chong truong hop plugin khac truyen nguyen chuoi:
         * kp_donate_total_top_player_1 thay vi chi params cua PlaceholderAPI.
         */
        if (query.startsWith("kp_")) {
            query = query.substring("kp_".length());
        } else if (query.startsWith("sotarpayments_")) {
            query = query.substring("sotarpayments_".length());
        }

        query = query.replace('-', '_');
        query = query.replace(' ', '_');
        query = query.replaceAll("[^a-z0-9_]", "_");
        query = query.replaceAll("_+", "_");

        while (query.startsWith("_")) {
            query = query.substring(1);
        }

        while (query.endsWith("_")) {
            query = query.substring(0, query.length() - 1);
        }

        return query;
    }

    private String stripTrailingFormatModifiers(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String result = query;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String modifier : RAW_NUMBER_MODIFIERS) {
                String suffix = "_" + modifier;
                if (result.equals(modifier)) {
                    return "";
                }
                if (result.endsWith(suffix)) {
                    result = result.substring(0, result.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return result;
    }

    private boolean isTransactionCountPlaceholder(String query) {
        return query.equals("transactions")
                || query.equals("transaction")
                || query.equals("transaction_count")
                || query.equals("transactions_count");
    }

    private boolean isTopPlaceholder(String query) {
        String[] tokens = tokens(query);

        return containsToken(tokens, "top")
                || query.startsWith("top_")
                || query.startsWith("donate_top_")
                || query.startsWith("donate_total_top_");
    }

    private boolean isServerTotalPlaceholder(String query) {
        if (query.equals("donate_server_total")
                || query.startsWith("donate_server_total_")
                || query.equals("server_total")
                || query.startsWith("server_total_")
                || query.equals("total_server")
                || query.startsWith("total_server_")) {
            return true;
        }

        String[] tokens = tokens(query);
        return containsToken(tokens, "server") && containsToken(tokens, "total");
    }

    private boolean isPersonalTotalPlaceholder(String query) {
        if (query.equals("donate_total")
                || query.startsWith("donate_total_")
                || query.equals("player_total")
                || query.startsWith("player_total_")
                || query.equals("total")
                || query.startsWith("total_")) {
            return true;
        }

        String[] tokens = tokens(query);
        return containsToken(tokens, "player") && containsToken(tokens, "total");
    }

    private boolean isMocNapServerPlaceholder(String query) {
        return query.equals("mocnapserver")
                || query.equals("mocnapserver_total")
                || query.startsWith("mocnapserver_");
    }

    private String resolveTopPlaceholder(String originalQuery, String lookupQuery, long startTime) {
        String[] tokens = tokens(lookupQuery);

        boolean wantsPlayer = containsAnyToken(tokens, "player", "name", "username", "user");
        boolean wantsAmount = containsAnyToken(tokens,
                "amount",
                "money",
                "value",
                "vnd",
                "coin",
                "coins",
                "point",
                "points",
                "total",
                "donate",
                "donated"
        );

        /*
         * Neu khong chi ro player/name thi mac dinh tra so tien.
         */
        if (!wantsPlayer && !wantsAmount) {
            wantsAmount = true;
        }

        int rank = findRank(tokens);
        if (rank <= 0) {
            return wantsPlayer ? NOT_AVAILABLE : ZERO;
        }

        List<Map.Entry<String, Long>> topList = getCachedTop(startTime, rank);

        if (rank > topList.size()) {
            return wantsPlayer ? NOT_AVAILABLE : ZERO;
        }

        Map.Entry<String, Long> entry = topList.get(rank - 1);
        if (entry == null) {
            return wantsPlayer ? NOT_AVAILABLE : ZERO;
        }

        if (wantsPlayer) {
            String name = entry.getKey();
            return name == null || name.isBlank() ? NOT_AVAILABLE : name;
        }

        return formatAmount(entry.getValue() == null ? 0L : entry.getValue(), isRawNumber(originalQuery));
    }

    private List<Map.Entry<String, Long>> getCachedTop(long startTime, int rank) {
        int limit = Math.max(DEFAULT_TOP_CACHE_LIMIT, rank);
        long now = System.currentTimeMillis();

        TopCache cached = topCache.get(startTime);
        if (cached != null && cached.isValid(now, limit)) {
            return cached.entries;
        }

        synchronized (topCacheLock) {
            cached = topCache.get(startTime);
            if (cached != null && cached.isValid(now, limit)) {
                return cached.entries;
            }

            Map<String, Long> topMap = plugin.getDatabaseManager().getTop(startTime, limit);
            List<Map.Entry<String, Long>> entries = new ArrayList<>();

            if (topMap != null && !topMap.isEmpty()) {
                entries.addAll(topMap.entrySet());
            }

            TopCache fresh = new TopCache(now, limit, entries);
            topCache.put(startTime, fresh);

            return fresh.entries;
        }
    }

    private String resolveMocNapServerPlaceholder(String originalQuery, String lookupQuery) {
        long total = getCachedAmount("mocnapserver", () -> plugin.getDatabaseManager().getServerTotalDonated());

        if (lookupQuery.startsWith("mocnapserver_progress_")) {
            long target = parseAmountSuffix(lookupQuery.substring("mocnapserver_progress_".length()));

            if (target <= 0L) {
                return formatAmount(total, isRawNumber(originalQuery));
            }

            long current = Math.max(0L, Math.min(total, target));
            return formatAmount(current, isRawNumber(originalQuery)) + "/" + formatAmount(target, isRawNumber(originalQuery));
        }

        if (lookupQuery.startsWith("mocnapserver_percent_")) {
            long target = parseAmountSuffix(lookupQuery.substring("mocnapserver_percent_".length()));

            if (target <= 0L) {
                return ZERO;
            }

            long percent = Math.max(0L, Math.min(100L, Math.round((total * 100.0D) / target)));
            return String.valueOf(percent);
        }

        return formatAmount(total, isRawNumber(originalQuery));
    }

    private long getCachedAmount(String key, LongSupplier loader) {
        long now = System.currentTimeMillis();

        AmountCache cached = amountCache.get(key);
        if (cached != null && cached.isValid(now)) {
            return cached.amount;
        }

        synchronized (amountCacheLock) {
            cached = amountCache.get(key);
            if (cached != null && cached.isValid(now)) {
                return cached.amount;
            }

            long amount;
            try {
                amount = Math.max(0L, loader.getAsLong());
            } catch (Throwable ignored) {
                amount = 0L;
            }

            if (amountCache.size() > MAX_AMOUNT_CACHE_SIZE) {
                amountCache.clear();
            }

            amountCache.put(key, new AmountCache(now, amount));
            return amount;
        }
    }

    private int findRank(String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return -1;
        }

        /*
         * Quet tu phai sang trai de uu tien rank gan cuoi placeholder:
         * donate_total_top_amount_1_vnd -> rank = 1
         */
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = tokens[i];

            if (token == null || token.isBlank() || isTimeToken(token) || RAW_NUMBER_MODIFIERS.contains(token)) {
                continue;
            }

            try {
                int rank = Integer.parseInt(token);
                if (rank > 0) {
                    return rank;
                }
            } catch (NumberFormatException ignored) {
            }

            /*
             * Chong truong hop nguoi dung viet dinh:
             * amount_1vnd hoac top_player_1vnd
             */
            String digits = token.replaceAll("[^0-9]", "");
            if (!digits.isBlank()) {
                try {
                    int rank = Integer.parseInt(digits);
                    if (rank > 0) {
                        return rank;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return -1;
    }

    private boolean containsToken(String[] tokens, String expected) {
        if (tokens == null || expected == null) {
            return false;
        }

        for (String token : tokens) {
            if (expected.equals(token)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsAnyToken(String[] tokens, String... expectedTokens) {
        if (tokens == null || expectedTokens == null) {
            return false;
        }

        for (String expected : expectedTokens) {
            if (containsToken(tokens, expected)) {
                return true;
            }
        }

        return false;
    }

    private String[] tokens(String query) {
        if (query == null || query.isBlank()) {
            return new String[0];
        }

        return query.split("_");
    }

    private long parseAmountSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return 0L;
        }

        /*
         * Target mốc server có thể được nhập dạng 1000000, 1_000_000,
         * 1,000,000, 1.000.000 hoặc kèm chữ tiền tệ. Chỉ lấy phần số
         * để tránh lỗi khi người dùng ghi thêm vnd/vnđ.
         */
        String digits = stripTrailingFormatModifiers(suffix).replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0L;
        }

        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private long getStartTime(String query) {
        String[] tokens = tokens(query);

        if (containsAnyToken(tokens, "today", "day", "daily")) {
            return DatabaseManager.getPeriodStartTime("today");
        }

        if (containsAnyToken(tokens, "week", "weekly")) {
            return DatabaseManager.getPeriodStartTime("week");
        }

        if (containsAnyToken(tokens, "month", "monthly")) {
            return DatabaseManager.getPeriodStartTime("month");
        }

        if (containsAnyToken(tokens, "year", "yearly")) {
            return DatabaseManager.getPeriodStartTime("year");
        }

        return 0L;
    }

    private boolean isTimeToken(String token) {
        return token != null && (token.equals("today")
                || token.equals("day")
                || token.equals("daily")
                || token.equals("week")
                || token.equals("weekly")
                || token.equals("month")
                || token.equals("monthly")
                || token.equals("year")
                || token.equals("yearly"));
    }

    private boolean isRawNumber(String query) {
        String[] tokens = tokens(query);
        return containsAnyToken(tokens, "raw", "number", "plain", "unformatted", "noformat");
    }

    private String formatAmount(long amount, boolean raw) {
        long safeAmount = Math.max(0L, amount);

        if (raw) {
            return String.valueOf(safeAmount);
        }

        return String.format(Locale.US, "%,d", safeAmount);
    }

    private static final class TopCache {
        private final long loadedAt;
        private final int limit;
        private final List<Map.Entry<String, Long>> entries;

        private TopCache(long loadedAt, int limit, List<Map.Entry<String, Long>> entries) {
            this.loadedAt = loadedAt;
            this.limit = limit;
            this.entries = entries == null ? new ArrayList<>() : entries;
        }

        private boolean isValid(long now, int requiredLimit) {
            return now - loadedAt <= TOP_CACHE_MILLIS && limit >= requiredLimit;
        }
    }

    private static final class AmountCache {
        private final long loadedAt;
        private final long amount;

        private AmountCache(long loadedAt, long amount) {
            this.loadedAt = loadedAt;
            this.amount = amount;
        }

        private boolean isValid(long now) {
            return now - loadedAt <= AMOUNT_CACHE_MILLIS;
        }
    }
}
