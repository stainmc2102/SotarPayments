package vn.sotarpayments.napbank.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.manager.GUIUtils;
import vn.sotarpayments.common.manager.MenuHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class TopCommand implements CommandExecutor, TabCompleter {
    private static final int FIRST_PAGE_SIZE = 30;
    private static final int FULL_PAGE_SIZE = 45;
    private static final int FIRST_TOP_ONE_SLOT = 4;
    private static final int FIRST_TOP_TWO_SLOT = 12;
    private static final int FIRST_TOP_THREE_SLOT = 14;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int PAGE_INFO_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;

    private static final int[] FIRST_PAGE_LIST_SLOTS = buildRange(18, 44);
    private static final int[] FULL_PAGE_LIST_SLOTS = buildRange(0, 44);

    private final SotarPayments plugin;

    public TopCommand(SotarPayments plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.tr("general.player-only"));
            return true;
        }

        ParsedTopRequest request = parseRequest(args);
        if (request == null) {
            player.sendMessage(plugin.tr("top.usage"));
            return true;
        }

        openTopMenu(player, request.mode(), request.page());
        return true;
    }

    public void openTopMenu(Player player, String mode, int page) {
        String safeMode = normalizeMode(mode);
        int safePage = Math.max(1, page);
        String title = getTitle(safeMode, safePage);

        MenuHolder holder = new MenuHolder("topnap");
        holder.setData("mode", safeMode);
        holder.setData("page", String.valueOf(safePage));
        Inventory inv = Bukkit.createInventory(holder, 54, title);

        int offset = getOffset(safePage);
        int pageSize = safePage == 1 ? FIRST_PAGE_SIZE : FULL_PAGE_SIZE;
        List<Map.Entry<String, Long>> topList = plugin.getDatabaseManager().getTopList(safeMode, pageSize + 1, offset);
        boolean hasNextPage = topList.size() > pageSize;
        if (hasNextPage) {
            topList = new ArrayList<>(topList.subList(0, pageSize));
        }

        if (topList.isEmpty()) {
            inv.setItem(22, GUIUtils.item(
                    Material.BARRIER,
                    plugin.tr("gui.no-data"),
                    plugin.tr("gui.no-data-lore")
            ));
            addNavigation(inv, holder, safeMode, safePage, hasNextPage);
            player.openInventory(inv);
            return;
        }

        if (safePage == 1) {
            renderFirstPage(inv, topList, safeMode, safePage);
        } else {
            renderFullPage(inv, topList, safeMode, safePage, offset);
        }

        addNavigation(inv, holder, safeMode, safePage, hasNextPage);
        player.openInventory(inv);
    }

    private void renderFirstPage(Inventory inv, List<Map.Entry<String, Long>> topList, String mode, int page) {
        if (!topList.isEmpty()) {
            inv.setItem(FIRST_TOP_ONE_SLOT, createRankItem(topList.get(0), 1, mode, page, true));
        }
        if (topList.size() >= 2) {
            inv.setItem(FIRST_TOP_TWO_SLOT, createRankItem(topList.get(1), 2, mode, page, true));
        }
        if (topList.size() >= 3) {
            inv.setItem(FIRST_TOP_THREE_SLOT, createRankItem(topList.get(2), 3, mode, page, true));
        }

        for (int index = 3; index < topList.size(); index++) {
            int slotIndex = index - 3;
            if (slotIndex >= FIRST_PAGE_LIST_SLOTS.length) break;
            inv.setItem(FIRST_PAGE_LIST_SLOTS[slotIndex], createRankItem(topList.get(index), index + 1, mode, page, false));
        }
    }

    private void renderFullPage(Inventory inv, List<Map.Entry<String, Long>> topList, String mode, int page, int offset) {
        for (int index = 0; index < topList.size() && index < FULL_PAGE_LIST_SLOTS.length; index++) {
            inv.setItem(FULL_PAGE_LIST_SLOTS[index], createRankItem(topList.get(index), offset + index + 1, mode, page, false));
        }
    }

    private void addNavigation(Inventory inv, MenuHolder holder, String mode, int page, boolean hasNextPage) {
        inv.setItem(PAGE_INFO_SLOT, GUIUtils.item(
                Material.COMPASS,
                plugin.tr("top.page-info"),
                plugin.tr("top.viewing", "mode", getModeName(mode)),
                plugin.tr("top.page", "page", page),
                plugin.tr("top.includes"),
                plugin.tr("top.command")
        ));

        if (page > 1) {
            holder.setSlotValue(PREVIOUS_PAGE_SLOT, "previous");
            inv.setItem(PREVIOUS_PAGE_SLOT, GUIUtils.item(
                    Material.ARROW,
                    plugin.tr("top.previous-page"),
                    plugin.tr("top.go-page", "page", page - 1)
            ));
        }

        if (hasNextPage) {
            holder.setSlotValue(NEXT_PAGE_SLOT, "next");
            inv.setItem(NEXT_PAGE_SLOT, GUIUtils.item(
                    Material.SPECTRAL_ARROW,
                    plugin.tr("top.next-page"),
                    plugin.tr("top.go-page", "page", page + 1)
            ));
        }
    }

    private ItemStack createRankItem(Map.Entry<String, Long> entry, int rank, String mode, int page, boolean featured) {
        String playerName = entry.getKey() == null || entry.getKey().isBlank()
                ? plugin.trPlain("top.unknown-player")
                : entry.getKey();
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            try {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
                skullMeta.setOwningPlayer(offlinePlayer);
            } catch (Exception ignored) {
            }
            meta = skullMeta;
        }

        if (meta != null) {
            meta.setDisplayName(getRankTitle(rank, playerName));
            List<String> lore = new ArrayList<>();
            if (featured) {
                lore.add(plugin.tr("top.podium", "rank", rank));
            }
            lore.add(plugin.tr("top.player", "player", playerName));
            lore.add(plugin.tr("top.rank-position", "rank", rank));
            lore.add(plugin.tr("top.total", "amount", GUIUtils.formatMoney(entry.getValue())));
            lore.add(plugin.tr("top.viewing", "mode", getModeName(mode)));
            lore.add(plugin.tr("top.page", "page", page));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getRankTitle(int rank, String playerName) {
        return switch (rank) {
            case 1 -> plugin.tr("top.rank-title-top1", "player", playerName);
            case 2 -> plugin.tr("top.rank-title-top2", "player", playerName);
            case 3 -> plugin.tr("top.rank-title-top3", "player", playerName);
            default -> plugin.tr("top.rank-title", "rank", rank, "player", playerName);
        };
    }

    private String getTitle(String mode, int page) {
        String baseTitle = switch (mode) {
            case "week" -> plugin.tr("gui.top-week");
            case "month" -> plugin.tr("gui.top-month");
            default -> plugin.tr("gui.top-all");
        };
        return baseTitle + plugin.tr("top.title-page-suffix", "page", page);
    }

    private String getModeName(String mode) {
        return switch (mode) {
            case "week" -> plugin.tr("top.mode-week");
            case "month" -> plugin.tr("top.mode-month");
            default -> plugin.tr("top.mode-all");
        };
    }

    private ParsedTopRequest parseRequest(String[] args) {
        String mode = "all";
        int page = 1;

        if (args.length >= 1 && !args[0].isBlank()) {
            if (isPositiveInteger(args[0])) {
                page = parsePage(args[0]);
            } else {
                mode = normalizeMode(args[0]);
                if (!isValidMode(mode)) return null;
            }
        }

        if (args.length >= 2 && !args[1].isBlank()) {
            if (!isPositiveInteger(args[1])) return null;
            page = parsePage(args[1]);
        }

        return new ParsedTopRequest(mode, page);
    }

    private int getOffset(int page) {
        if (page <= 1) return 0;
        return FIRST_PAGE_SIZE + ((page - 2) * FULL_PAGE_SIZE);
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return "all";
        return mode.toLowerCase(Locale.ROOT);
    }

    private boolean isValidMode(String mode) {
        return mode.equals("all") || mode.equals("week") || mode.equals("month");
    }

    private boolean isPositiveInteger(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private int parsePage(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int[] buildRange(int startInclusive, int endInclusive) {
        int[] slots = new int[endInclusive - startInclusive + 1];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = startInclusive + i;
        }
        return slots;
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("all", "week", "month"), args[0]);
        }
        if (args.length == 2) {
            return filter(List.of("1", "2", "3"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> suggestions, String token) {
        String prefix = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(value -> value.startsWith(prefix))
                .collect(Collectors.toList());
    }

    private record ParsedTopRequest(String mode, int page) {}
}
