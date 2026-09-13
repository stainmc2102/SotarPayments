package vn.sotarpayments.napcard.manager;

import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napcard.utils.CardProviderNames;

import java.util.Objects;

/**
 * Coordinates all card-entry flows so commands, inventory menus, and native
 * dialogs share one validation and session lifecycle.
 */
public final class CardFlowManager {
    private final SotarPayments plugin;

    public CardFlowManager(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public boolean startChatInput(Player player, String rawTelco, int amount) {
        CardSelection selection = validateSelection(player, rawTelco, amount);
        if (selection == null) return false;

        CardSessionManager.Session session = new CardSessionManager.Session(selection.telco(), selection.amount());
        plugin.getCardSessionManager().start(player.getUniqueId(), session);
        player.sendMessage("");
        player.sendMessage(plugin.tr("card.start",
                "telco", selection.telco(),
                "amount", plugin.formatMoney(selection.amount())));
        player.sendMessage(plugin.tr("card.enter-serial"));
        player.sendMessage("");
        return true;
    }

    public boolean submitCredentials(Player player,
                                     String rawTelco,
                                     int amount,
                                     String serial,
                                     String pin) {
        CardSelection selection = validateSelection(player, rawTelco, amount);
        if (selection == null) return false;
        if (serial == null || serial.isBlank() || pin == null || pin.isBlank()) {
            player.sendMessage(plugin.tr("dialog.card-input-invalid"));
            return false;
        }

        CardSessionManager.Session session = new CardSessionManager.Session(selection.telco(), selection.amount());
        session.serial = serial.trim();
        session.pin = pin.trim();
        session.step = CardSessionManager.Step.CONFIRM;
        plugin.getCardSessionManager().start(player.getUniqueId(), session);
        plugin.getCardListener().showConfirmation(player, session);
        return true;
    }

    public String normalizeTelco(String telco) {
        return CardProviderNames.normalize(telco);
    }

    private CardSelection validateSelection(Player player, String rawTelco, int amount) {
        if (player == null) return null;

        String telco = normalizeTelco(rawTelco);
        if (telco.isBlank()) {
            player.sendMessage(plugin.tr("card.invalid-telco", "telco", rawTelco == null ? "" : rawTelco));
            return null;
        }
        if (amount <= 0) {
            player.sendMessage(plugin.tr("card.invalid-denomination", "amount", plugin.formatMoney(amount)));
            return null;
        }

        boolean restrictToGuiOptions = plugin.config().getBoolean(
                "napthe.validation.restrict-to-gui-options", false);
        if (restrictToGuiOptions && !plugin.getPaymentGuiManager().isCardTelcoAllowed(telco)) {
            player.sendMessage(plugin.tr("card.invalid-telco", "telco", telco));
            return null;
        }
        if (restrictToGuiOptions && !plugin.getPaymentGuiManager().isCardAmountAllowed(amount)) {
            player.sendMessage(plugin.tr("card.invalid-denomination", "amount", plugin.formatMoney(amount)));
            return null;
        }
        return new CardSelection(telco, amount);
    }

    private record CardSelection(String telco, int amount) {
    }
}
