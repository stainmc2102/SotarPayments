package vn.sotarpayments.napcard.utils;

import org.bukkit.ChatColor;

public class ChatUtils {
    public static String color(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}