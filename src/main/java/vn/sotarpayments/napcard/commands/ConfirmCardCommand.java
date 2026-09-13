package vn.sotarpayments.napcard.commands;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.json.JSONObject;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.napcard.api.CardChargingService;
import vn.sotarpayments.napcard.manager.CardSessionManager;
import vn.sotarpayments.napcard.models.CardRequest;

import java.io.IOException;
import java.util.UUID;

public class ConfirmCardCommand implements CommandExecutor {
    private final SotarPayments plugin;

    public ConfirmCardCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }
        if (!plugin.ensureFeatureAvailable(player)) return true;

        CardSessionManager.Session session = plugin.getCardSessionManager().get(player.getUniqueId());
        if (session == null || session.step != CardSessionManager.Step.CONFIRM) {
            player.sendMessage(plugin.tr("card.no-session"));
            return true;
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");
        CardRequest card = new CardRequest(session.telco, session.pin, session.serial, session.amount, requestId);
        CardChargingService service = plugin.getCardChargingService();
        String providerName = service.getProviderDisplayName();

        if (!service.hasCredentials()) {
            player.sendMessage(plugin.tr("card.provider-missing", "provider", providerName));
            return true;
        }

        player.sendMessage(plugin.tr("card.sending", "provider", providerName));

        service.sendRequest(card, "charging", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                plugin.getPlatformScheduler().runPlayer(player, () ->
                        player.sendMessage(plugin.tr("card.connection-failed", "provider", providerName)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.body() == null) return;

                String raw = response.body().string();
                try {
                    JSONObject json = new JSONObject(raw);
                    int status = json.optInt("status", -1);
                    String message = json.optString("message", json.optString("msg", "Unknown"));

                    plugin.getPlatformScheduler().runPlayer(player, () -> {
                        if (status == 99) {
                            plugin.getPendingCards().put(player.getUniqueId(), card);
                        }
                        plugin.handleCardResponse(player, status, message, card);
                    });
                } catch (Exception e) {
                    plugin.logWarning("Invalid card response from " + providerName + ". Enable logging.debug for raw response.");
                    plugin.logDebug("Invalid card raw response from " + providerName + ": " + raw, e);
                    plugin.getPlatformScheduler().runPlayer(player, () ->
                            player.sendMessage(plugin.tr("card.invalid-response", "provider", providerName)));
                }
            }
        });

        plugin.getCardSessionManager().stop(player.getUniqueId());
        return true;
    }
}
