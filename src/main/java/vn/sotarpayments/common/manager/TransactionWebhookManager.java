package vn.sotarpayments.common.manager;

import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TransactionWebhookManager {
    private static final int DEFAULT_SUCCESS_COLOR = 3066993;
    private static final int DEFAULT_FAILURE_COLOR = 15158332;
    private static final int MAX_FIELD_VALUE_LENGTH = 950;

    private final SotarPayments plugin;

    public TransactionWebhookManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public void sendSuccess(PaymentChannel channel, String playerName, long amount, String provider, String detail) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        if (!isEnabledFor(safeChannel)) return;

        plugin.getPlatformScheduler().runAsync(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                String webhookUrl = getWebhookUrl();
                if (webhookUrl.isBlank()) return;

                connection = plugin.openJsonPostConnection(webhookUrl);
                String json = buildSuccessPayload(safeChannel, playerName, amount, provider, detail);
                connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
                connection.getResponseCode();
            } catch (Exception e) {
                plugin.logDebug("Discord transaction webhook failed: " + e.getMessage(), e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public void sendFailure(PaymentChannel channel, String playerName, long amount, String reason) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.BANK : channel;
        if (!isEnabledFor(safeChannel)) return;

        plugin.getPlatformScheduler().runAsync(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                String webhookUrl = getWebhookUrl();
                if (webhookUrl.isBlank()) return;

                connection = plugin.openJsonPostConnection(webhookUrl);
                String json = buildFailurePayload(safeChannel, playerName, amount, reason);
                connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
                connection.getResponseCode();
            } catch (Exception e) {
                plugin.logDebug("Discord transaction failure webhook failed: " + e.getMessage(), e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    public boolean isEnabledFor(PaymentChannel channel) {
        if (!plugin.config().getBoolean("discord-webhook.enabled", false)) return false;
        String webhookUrl = getWebhookUrl();
        if (webhookUrl.isBlank() || webhookUrl.contains("DAN_LINK_WEBHOOK")) return false;

        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        String key = safeChannel.storageKey().toLowerCase(Locale.ROOT);
        String path = "discord-webhook.transactions." + key + ".enabled";
        return plugin.config().getBoolean(path, true);
    }

    private String buildSuccessPayload(PaymentChannel channel, String playerName, long amount, String provider, String detail) {
        String type = plugin.stripColor(plugin.tr(channel.languageKey()));
        String title = switch (channel) {
            case BANK -> plugin.stripColor(plugin.tr("bank.webhook-success-title"));
            case CARD -> plugin.stripColor(plugin.tr("card.webhook-title"));
            case MANUAL -> plugin.stripColor(plugin.tr("manual.webhook-title"));
            default -> plugin.stripColor(plugin.tr("webhook.success-title"));
        };
        String footer = switch (channel) {
            case BANK -> plugin.stripColor(plugin.tr("bank.webhook-footer"));
            case CARD -> plugin.stripColor(plugin.tr("card.webhook-footer"));
            case MANUAL -> plugin.stripColor(plugin.tr("manual.webhook-footer"));
            default -> plugin.stripColor(plugin.tr("webhook.footer"));
        };

        StringBuilder fields = new StringBuilder();
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-player")), "**" + safeDiscord(playerName) + "**", true);
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-type")), "`" + safeDiscord(type) + "`", true);
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-amount")), "`" + plugin.formatMoney(amount) + " " + plugin.trPlain("general.currency") + "`", true);
        if (provider != null && !provider.isBlank()) {
            addField(fields, plugin.stripColor(plugin.tr("webhook.field-provider")), "`" + safeDiscord(provider) + "`", true);
        }
        if (detail != null && !detail.isBlank()) {
            addField(fields, plugin.stripColor(plugin.tr("webhook.field-detail")), "`" + safeDiscord(limit(detail, MAX_FIELD_VALUE_LENGTH)) + "`", false);
        }
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-time")), currentTime(), false);

        return "{"
                + "\"username\": \"" + jsonEscape(getUsername()) + "\"," 
                + "\"avatar_url\": \"" + jsonEscape(getAvatarUrl(playerName)) + "\"," 
                + "\"embeds\": [{"
                + "\"title\": \"" + jsonEscape(title) + "\"," 
                + "\"color\": " + getColor(channel, DEFAULT_SUCCESS_COLOR) + ","
                + "\"thumbnail\": {\"url\": \"" + jsonEscape(getAvatarUrl(playerName)) + "\"},"
                + "\"fields\": [" + fields + "],"
                + "\"footer\": {\"text\": \"" + jsonEscape(footer) + "\"}"
                + "}]"
                + "}";
    }

    private String buildFailurePayload(PaymentChannel channel, String playerName, long amount, String reason) {
        String title = switch (channel) {
            case BANK -> plugin.stripColor(plugin.tr("bank.webhook-failure-title"));
            default -> plugin.stripColor(plugin.tr("webhook.failure-title"));
        };

        StringBuilder fields = new StringBuilder();
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-player")), safeDiscord(playerName), true);
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-amount")), "`" + plugin.formatMoney(amount) + " " + plugin.trPlain("general.currency") + "`", true);
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-reason")), "`" + safeDiscord(limit(reason, MAX_FIELD_VALUE_LENGTH)) + "`", false);
        addField(fields, plugin.stripColor(plugin.tr("webhook.field-time")), currentTime(), false);

        return "{"
                + "\"username\": \"" + jsonEscape(getUsername()) + "\"," 
                + "\"avatar_url\": \"" + jsonEscape(getAvatarUrl(playerName)) + "\"," 
                + "\"embeds\": [{"
                + "\"title\": \"" + jsonEscape(title) + "\"," 
                + "\"color\": " + DEFAULT_FAILURE_COLOR + ","
                + "\"fields\": [" + fields + "]"
                + "}]"
                + "}";
    }

    private void addField(StringBuilder fields, String name, String value, boolean inline) {
        if (fields.length() > 0) fields.append(',');
        fields.append('{')
                .append("\"name\": \"").append(jsonEscape(name)).append("\",")
                .append("\"value\": \"").append(jsonEscape(value == null || value.isBlank() ? "-" : value)).append("\",")
                .append("\"inline\": ").append(inline)
                .append('}');
    }

    private int getColor(PaymentChannel channel, int fallback) {
        String key = channel == null ? "legacy" : channel.storageKey().toLowerCase(Locale.ROOT);
        String path = "discord-webhook.transactions." + key + ".color";
        if (plugin.config().contains(path)) {
            return plugin.config().getInt(path, fallback);
        }
        return plugin.config().getInt("discord-webhook.color", fallback);
    }

    private String getWebhookUrl() {
        return plugin.config().getString("discord-webhook.url", "").trim();
    }

    private String getUsername() {
        return plugin.config().getString("discord-webhook.username", "SotarPayments");
    }

    private String getAvatarUrl(String playerName) {
        String template = plugin.config().getString("discord-webhook.avatar-url", "https://minotar.net/avatar/%player%/100.png");
        return template.replace("%player%", playerName == null ? "Steve" : playerName)
                .replace("{player}", playerName == null ? "Steve" : playerName)
                .replace("{playername}", playerName == null ? "Steve" : playerName);
    }

    private String currentTime() {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date());
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String safeDiscord(String value) {
        if (value == null) return "";
        return value.replace("`", "'").replace("@", "＠");
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
