package vn.sotarpayments.common.manager;

import java.util.Locale;

/**
 * Whitelisted actions that may be attached to a SotarPayments inventory slot.
 */
public enum MenuAction {
    NONE,
    CLOSE,
    OPEN_CARD_PROVIDER,
    OPEN_BANK_MENU,
    OPEN_CARD_MENU,
    OPEN_HISTORY,
    OPEN_MILESTONE,
    OPEN_TOP_NAP,
    SELECT_CARD_PROVIDER,
    START_CARD_PAYMENT,
    START_BANK_PAYMENT;

    public static MenuAction parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
