package vn.sotarpayments.napcard.utils;

import java.util.Locale;

/**
 * Shared card-provider name normalization used by commands, menus, dialogs,
 * rate lookup, and provider requests.
 */
public final class CardProviderNames {
    private CardProviderNames() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace(".", "")
                .toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MOBI" -> "MOBIFONE";
            case "VINA" -> "VINAPHONE";
            case "VNMOBILE" -> "VIETNAMOBILE";
            default -> normalized;
        };
    }

    public static String normalizeRateKey(String value) {
        return normalize(value);
    }

    public static String displayName(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "MOBIFONE" -> "MobiFone";
            case "VINAPHONE" -> "VinaPhone";
            case "VIETNAMOBILE", "VNMOBI" -> "Vietnamobile";
            case "VCOIN" -> "VCoin";
            case "SCOIN" -> "SCoin";
            case "" -> "Card";
            default -> normalized;
        };
    }
}
