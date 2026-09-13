package vn.sotarpayments.napcard.manager;

import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napcard.utils.CardProviderNames;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CardRateManager {

    private final SotarPayments plugin;
    private final Map<String, Double> cachedRates = new ConcurrentHashMap<>();
    private volatile long lastFetchTime = 0;

    public CardRateManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public double getDiscountRate(String telco, int amount) {
        String telcoKey = buildRateKey(telco, amount);
        String amountKey = String.valueOf(amount);

        if (!cachedRates.isEmpty()) {
            if (cachedRates.containsKey(telcoKey)) {
                return cachedRates.get(telcoKey);
            }
            if (cachedRates.containsKey(amountKey)) {
                return cachedRates.get(amountKey);
            }
        }

        return getConfigFallback(telco, amount);
    }

    public void fetchRatesAsync() {
        plugin.logDebug("[CardRateManager] Using built-in fallback rates; API fetch is skipped.");
    }

    public boolean fetchRatesSync() {
        plugin.logDebug("[CardRateManager] Using built-in fallback rates; API fetch is skipped.");
        return false;
    }

    private String buildRateKey(String telco, int amount) {
        return normalizeTelco(telco) + "_" + amount;
    }

    private String normalizeTelco(String telco) {
        String normalized = CardProviderNames.normalizeRateKey(telco);
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private double getConfigFallback(String telco, int amount) {
        boolean taxEnabled = getBooleanCompat("napthe.taxes.enabled", "card2k.taxes.enabled", true);
        if (!taxEnabled) return 0.0;

        String normalizedTelco = normalizeTelco(telco);

        Double telcoRate = getDoubleCompat(
                "napthe.taxes.rates." + normalizedTelco + "." + amount,
                "card2k.taxes.rates." + normalizedTelco + "." + amount);
        if (telcoRate != null) {
            return telcoRate;
        }

        Double amountRate = getDoubleCompat(
                "napthe.taxes.rates." + amount,
                "card2k.taxes.rates." + amount);
        if (amountRate != null) {
            return amountRate;
        }

        return getBuiltInRate(normalizedTelco, amount);
    }

    private boolean getBooleanCompat(String primaryPath, String legacyPath, boolean fallback) {
        if (plugin.config().contains(primaryPath)) {
            return plugin.config().getBoolean(primaryPath, fallback);
        }
        return plugin.config().getBoolean(legacyPath, fallback);
    }

    private Double getDoubleCompat(String primaryPath, String legacyPath) {
        if (plugin.config().contains(primaryPath)) {
            return plugin.config().getDouble(primaryPath, 0.0);
        }
        if (plugin.config().contains(legacyPath)) {
            return plugin.config().getDouble(legacyPath, 0.0);
        }
        return null;
    }

    private double getBuiltInRate(String telco, int amount) {
        return switch (telco) {
            case "VIETTEL" -> getViettelRate(amount);
            case "VINAPHONE" -> getVinaphoneRate(amount);
            case "MOBIFONE" -> getMobifoneRate(amount);
            case "GARENA" -> getGarenaRate(amount);
            case "ZING" -> getZingRate(amount);
            case "GATE" -> getGateRate(amount);
            case "VCOIN" -> getVcoinRate(amount);
            case "SCOIN" -> getScoinRate(amount);
            default -> 0.0;
        };
    }

    private double getViettelRate(int amount) {
        return switch (amount) {
            case 10000 -> 20.8;
            case 20000 -> 21.0;
            case 30000 -> 21.1;
            case 50000 -> 17.0;
            case 100000 -> 17.0;
            case 200000 -> 17.0;
            case 300000 -> 17.0;
            case 500000 -> 16.5;
            case 1000000 -> 16.5;
            default -> 0.0;
        };
    }

    private double getVinaphoneRate(int amount) {
        return switch (amount) {
            case 10000 -> 14.7;
            case 20000 -> 14.0;
            case 30000 -> 14.0;
            case 50000 -> 10.6;
            case 100000 -> 9.6;
            case 200000 -> 9.6;
            case 300000 -> 9.6;
            case 500000 -> 9.6;
            default -> 0.0;
        };
    }

    private double getMobifoneRate(int amount) {
        return switch (amount) {
            case 10000 -> 21.0;
            case 20000 -> 21.0;
            case 30000 -> 21.0;
            case 50000 -> 21.0;
            case 100000 -> 17.8;
            case 200000 -> 17.0;
            case 300000 -> 17.0;
            case 500000 -> 17.0;
            default -> 0.0;
        };
    }

    private double getGarenaRate(int amount) {
        return switch (amount) {
            case 5000 -> 17.5;
            case 10000 -> 15.8;
            case 20000 -> 15.8;
            case 50000 -> 15.8;
            case 100000 -> 15.8;
            case 200000 -> 15.8;
            case 500000 -> 15.8;
            default -> 0.0;
        };
    }

    private double getZingRate(int amount) {
        return switch (amount) {
            case 10000 -> 12.7;
            case 20000 -> 12.7;
            case 50000 -> 12.7;
            case 100000 -> 12.7;
            case 200000 -> 12.7;
            case 500000 -> 12.7;
            case 1000000 -> 12.7;
            default -> 0.0;
        };
    }

    private double getGateRate(int amount) {
        return switch (amount) {
            case 10000 -> 12.5;
            case 20000 -> 12.5;
            case 50000 -> 12.5;
            case 100000 -> 12.5;
            case 200000 -> 12.5;
            case 300000 -> 20.0;
            case 500000 -> 12.5;
            case 1000000 -> 12.5;
            case 2000000 -> 17.5;
            case 5000000 -> 12.5;
            default -> 0.0;
        };
    }

    private double getVcoinRate(int amount) {
        return switch (amount) {
            case 10000 -> 13.5;
            case 20000 -> 13.5;
            case 50000 -> 13.5;
            case 100000 -> 13.5;
            case 200000 -> 13.5;
            case 300000 -> 13.5;
            case 500000 -> 13.5;
            case 1000000 -> 13.5;
            case 2000000 -> 15.0;
            case 5000000 -> 15.5;
            default -> 0.0;
        };
    }

    private double getScoinRate(int amount) {
        return switch (amount) {
            case 10000 -> 34.8;
            case 20000 -> 34.8;
            case 50000 -> 32.2;
            case 100000 -> 32.2;
            case 200000 -> 32.2;
            case 300000 -> 34.8;
            case 500000 -> 32.2;
            case 1000000 -> 32.2;
            case 2000000 -> 32.2;
            case 5000000 -> 32.2;
            default -> 0.0;
        };
    }

    public boolean hasCachedRates() {
        return !cachedRates.isEmpty();
    }

    public String getLastFetchInfo() {
        if (lastFetchTime == 0) return "Built-in fallback";
        long secondsAgo = (System.currentTimeMillis() - lastFetchTime) / 1000;
        if (secondsAgo < 60) return secondsAgo + "s trước (" + cachedRates.size() + " mức)";
        if (secondsAgo < 3600) return (secondsAgo / 60) + "m trước (" + cachedRates.size() + " mức)";
        return (secondsAgo / 3600) + "h trước (" + cachedRates.size() + " mức)";
    }

    public Map<String, Double> getCachedRates() {
        return Map.copyOf(cachedRates);
    }

    public void invalidateCache() {
        cachedRates.clear();
        lastFetchTime = 0;
    }
}