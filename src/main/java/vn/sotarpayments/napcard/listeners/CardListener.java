package vn.sotarpayments.napcard.listeners;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napcard.manager.CardSessionManager;

public class CardListener implements Listener {
    private final SotarPayments plugin;

    public CardListener(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        CardSessionManager.Session session = plugin.getCardSessionManager().get(player.getUniqueId());
        if (session == null || session.step == CardSessionManager.Step.CONFIRM) return;

        event.setCancelled(true);
        String message = event.getMessage();

        if (message.equalsIgnoreCase("huy") || message.equalsIgnoreCase("cancel")) {
            plugin.getCardSessionManager().stop(player.getUniqueId());
            plugin.getPlatformScheduler().runPlayer(player, () -> player.sendMessage(plugin.tr("card.process-canceled")));
            return;
        }

        if (session.step == CardSessionManager.Step.SERIAL) {
            session.serial = message;
            session.step = CardSessionManager.Step.CODE;
            plugin.getPlatformScheduler().runPlayer(player, () -> player.sendMessage(plugin.tr("card.serial-received")));
        } else if (session.step == CardSessionManager.Step.CODE) {
            session.pin = message;
            session.step = CardSessionManager.Step.CONFIRM;
            plugin.getPlatformScheduler().runPlayer(player, () -> showConfirmation(player, session));
        }
    }

    public void showConfirmation(Player player, CardSessionManager.Session session) {
        player.sendMessage("");
        player.sendMessage(plugin.tr("card.confirm-header"));
        player.sendMessage(" §8- " + plugin.tr("card.confirm-telco", "telco", session.telco));
        player.sendMessage(" §8- " + plugin.tr("card.confirm-amount", "amount", plugin.formatMoney(session.amount)));
        boolean maskSensitive = plugin.config().getBoolean("napthe.security.mask-sensitive-confirmation", true);
        String serial = maskSensitive ? maskSerial(session.serial) : session.serial;
        String pin = maskSensitive ? maskPin(session.pin) : session.pin;
        player.sendMessage(" §8- " + plugin.tr("card.confirm-serial", "serial", serial));
        player.sendMessage(" §8- " + plugin.tr("card.confirm-code", "code", pin));
        player.sendMessage("");

        TextComponent confirm = new TextComponent(plugin.tr("card.confirm-button"));
        confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/confirmcard"));
        confirm.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(plugin.tr("card.confirm-hover"))));

        TextComponent space = new TextComponent("   ");

        TextComponent cancel = new TextComponent(plugin.tr("card.cancel-button"));
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cancelcard"));
        cancel.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(plugin.tr("card.cancel-hover"))));

        player.spigot().sendMessage(confirm, space, cancel);
        player.sendMessage(plugin.tr("card.confirm-note"));
        player.sendMessage("");
    }

    private String maskSerial(String value) {
        if (value == null || value.isBlank()) return "****";
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return "*".repeat(trimmed.length());
        if (trimmed.length() <= 8) {
            return trimmed.substring(0, 2) + "*".repeat(trimmed.length() - 4)
                    + trimmed.substring(trimmed.length() - 2);
        }
        return trimmed.substring(0, 4) + "*".repeat(trimmed.length() - 8)
                + trimmed.substring(trimmed.length() - 4);
    }

    private String maskPin(String value) {
        if (value == null || value.isBlank()) return "****";
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "*".repeat(Math.max(0, trimmed.length() - visible))
                + trimmed.substring(trimmed.length() - visible);
    }
}
