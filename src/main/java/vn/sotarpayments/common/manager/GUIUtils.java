package vn.sotarpayments.common.manager;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class GUIUtils {
    private GUIUtils() {}

    public static ItemStack item(Material material, String name, String... lore) {
        return item(material, 1, name, new ArrayList<>(Arrays.asList(lore)), null, false, List.of(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP
        ));
    }

    public static ItemStack item(Material material,
                                 int amount,
                                 String name,
                                 List<String> lore,
                                 Integer customModelData,
                                 boolean glow,
                                 Collection<ItemFlag> flags) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(new ArrayList<>(lore));
            if (customModelData != null && customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }
            if (flags != null && !flags.isEmpty()) {
                meta.addItemFlags(flags.toArray(new ItemFlag[0]));
            }
            if (glow) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }

        if (glow) {
            Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (enchantment != null) item.addUnsafeEnchantment(enchantment, 1);
        }

        return item;
    }

    /**
     * Creates a skull item with a player owner. Works with PLAYER_HEAD material.
     *
     * @param ownerName the player name whose head to display
     * @param name      display name
     * @param lore      item lore
     * @return the skull ItemStack
     */
    public static ItemStack skullItem(String ownerName, String name, List<String> lore) {
        return skullItem(ownerName, name, lore, 1, null, false, List.of(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP
        ));
    }

    /**
     * Creates a skull item with full customization options.
     */
    public static ItemStack skullItem(String ownerName,
                                      String name,
                                      List<String> lore,
                                      int amount,
                                      Integer customModelData,
                                      boolean glow,
                                      Collection<ItemFlag> flags) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, Math.max(1, Math.min(64, amount)));
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta != null) {
            if (ownerName != null && !ownerName.isBlank()) {
                meta.setOwner(ownerName);
            }
            meta.setDisplayName(name);
            meta.setLore(new ArrayList<>(lore));
            if (customModelData != null && customModelData > 0) {
                meta.setCustomModelData(customModelData);
            }
            if (flags != null && !flags.isEmpty()) {
                meta.addItemFlags(flags.toArray(new ItemFlag[0]));
            }
            if (glow) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }

        if (glow) {
            Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (enchantment != null) item.addUnsafeEnchantment(enchantment, 1);
        }

        return item;
    }

    public static ItemStack emptyPane() {
        return item(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    public static void fillBorder(Inventory inv, ItemStack item) {
        int size = inv.getSize();

        for (int i = 0; i < 9; i++) {
            inv.setItem(i, item);
        }

        for (int i = size - 9; i < size; i++) {
            inv.setItem(i, item);
        }

        int rows = size / 9;
        for (int row = 1; row < rows - 1; row++) {
            inv.setItem(row * 9, item);
            inv.setItem(row * 9 + 8, item);
        }
    }

    public static String formatMoney(long amount) {
        return String.format("%,d", amount);
    }

    public static List<String> lore(String... lines) {
        return new ArrayList<>(Arrays.asList(lines));
    }
}
