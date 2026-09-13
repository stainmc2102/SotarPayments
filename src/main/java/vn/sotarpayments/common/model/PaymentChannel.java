package vn.sotarpayments.common.model;

import java.util.Locale;

public enum PaymentChannel {
    BANK("bank", "napbank.promotion", "payment-type.bank"),
    CARD("card", "napthe.promotion", "payment-type.card"),
    MANUAL("manual", "napbank.promotion", "payment-type.manual"),
    LEGACY("legacy", "promotion", "payment-type.legacy");

    private final String storageKey;
    private final String promotionPath;
    private final String languageKey;

    PaymentChannel(String storageKey, String promotionPath, String languageKey) {
        this.storageKey = storageKey;
        this.promotionPath = promotionPath;
        this.languageKey = languageKey;
    }

    public String storageKey() {
        return storageKey;
    }

    public String promotionPath() {
        return promotionPath;
    }

    public String languageKey() {
        return languageKey;
    }

    public static PaymentChannel fromStored(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }

        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "BANK", "NAPBANK", "BANKING", "PAYOS", "SEPAY" -> BANK;
            case "CARD", "NAPTHE", "THE", "THECAO", "CARD2K", "GACHTHEFAST" -> CARD;
            case "MANUAL", "NAPTHUCONG", "ADMIN" -> MANUAL;
            default -> LEGACY;
        };
    }
}
