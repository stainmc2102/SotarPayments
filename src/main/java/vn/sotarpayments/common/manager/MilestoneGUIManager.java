package vn.sotarpayments.common.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import vn.sotarpayments.SotarPayments;

import java.util.ArrayList;
import java.util.List;

public class MilestoneGUIManager {
    private static final int[] MILESTONE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final SotarPayments plugin;

    public MilestoneGUIManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public void openMilestones(Player player) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("milestones"), 54, plugin.tr("gui.milestone-title"));
        GUIUtils.fillBorder(inv, GUIUtils.emptyPane());
        paintAccentCorners(inv);

        ConfigurationSection section = plugin.config().getConfigurationSection(MilestoneManager.PERSONAL_MILESTONES_PATH);
        if (section == null) {
            inv.setItem(22, GUIUtils.item(
                    Material.BARRIER,
                    plugin.tr("gui.no-data"),
                    plugin.tr("milestone-gui.no-config")
            ));
            player.openInventory(inv);
            return;
        }

        long total = plugin.getDatabaseManager().getTotalDonated(player.getName());
        List<Long> milestones = plugin.getMilestoneManager().getPersonalMilestones();

        int index = 0;
        for (Long milestone : milestones) {
            if (index >= MILESTONE_SLOTS.length) break;

            String milestoneKey = String.valueOf(milestone);
            boolean reached = total >= milestone;
            boolean claimed = plugin.getDatabaseManager().hasClaimedMilestone(player.getName(), milestoneKey);

            Material material;
            String statusLine;

            if (claimed) {
                material = Material.MINECART;
                statusLine = plugin.tr("milestone-gui.status-claimed");
            } else if (reached) {
                material = Material.CHEST;
                statusLine = plugin.tr("milestone-gui.status-claimable");
            } else {
                material = Material.BARRIER;
                statusLine = plugin.tr("milestone-gui.status-locked");
            }

            List<String> lore = new ArrayList<>();
            lore.add(plugin.tr("milestone-gui.requirement", "amount", GUIUtils.formatMoney(milestone)));
            lore.add(plugin.tr("milestone-gui.donated", "amount", GUIUtils.formatMoney(total)));
            lore.add(statusLine);
            lore.add(" ");
            appendRewards(
                    lore,
                    section.getStringList(milestoneKey + ".rewards"),
                    section.getStringList(milestoneKey + "." + MilestoneManager.DISPLAY_REWARDS_KEY)
            );

            if (!claimed && reached) {
                lore.add(" ");
                lore.add(plugin.tr("milestone-gui.click-claim"));
            }

            inv.setItem(MILESTONE_SLOTS[index], GUIUtils.item(
                    material,
                    plugin.tr("milestone-gui.item-title",
                            "amount", GUIUtils.formatMoney(milestone),
                            "number", index + 1),
                    lore.toArray(new String[0])
            ));

            index++;
        }

        inv.setItem(49, GUIUtils.item(
                Material.PAPER,
                plugin.tr("gui.personal-info"),
                plugin.tr("milestone-gui.total-donated"),
                "§f" + GUIUtils.formatMoney(total) + " " + plugin.trPlain("general.currency")
        ));

        player.openInventory(inv);
    }

    public void openServerMilestoneRewards(Player player) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("server_milestones"), 54, plugin.tr("gui.server-milestone-title"));
        GUIUtils.fillBorder(inv, GUIUtils.emptyPane());
        paintAccentCorners(inv);

        if (!plugin.getMilestoneManager().isServerMilestonesEnabled()) {
            inv.setItem(22, GUIUtils.item(
                    Material.BARRIER,
                    plugin.tr("gui.no-data"),
                    plugin.tr("server-milestone.disabled")
            ));
            player.openInventory(inv);
            return;
        }

        ConfigurationSection section = plugin.config().getConfigurationSection(MilestoneManager.SERVER_MILESTONES_PATH);
        if (section == null) {
            inv.setItem(22, GUIUtils.item(
                    Material.BARRIER,
                    plugin.tr("gui.no-data"),
                    plugin.tr("server-milestone-gui.no-config")
            ));
            player.openInventory(inv);
            return;
        }

        long total = plugin.getMilestoneManager().getCachedServerTotal();
        long activeTarget = plugin.getMilestoneManager().resolveBossBarTarget(total);
        long personalTotal = plugin.getDatabaseManager().getTotalDonatedIgnoreCase(player.getName());
        List<Long> milestones = plugin.getMilestoneManager().getServerMilestones();

        int index = 0;
        for (Long milestone : milestones) {
            if (index >= MILESTONE_SLOTS.length) break;

            String milestoneKey = String.valueOf(milestone);
            MilestoneManager.ServerMilestoneClaimState claimState = plugin.getMilestoneManager()
                    .getServerMilestoneClaimState(player, milestone, personalTotal);
            boolean reached = claimState.reached();
            boolean claimed = claimState.claimed();

            Material material;
            String statusLine;

            if (claimed) {
                material = Material.MINECART;
                statusLine = plugin.tr("server-milestone-gui.status-claimed");
            } else if (claimState.blockedByAntiClone()) {
                material = Material.IRON_BARS;
                statusLine = plugin.tr("server-milestone-gui.status-need-personal-topup",
                        "amount", GUIUtils.formatMoney(claimState.minimumPersonalDonated()));
            } else if (reached) {
                material = Material.ENDER_CHEST;
                statusLine = plugin.tr("server-milestone-gui.status-claimable");
            } else {
                material = Material.BARRIER;
                statusLine = plugin.tr("server-milestone-gui.status-locked");
            }

            List<String> lore = new ArrayList<>();
            lore.add(plugin.tr("server-milestone-gui.requirement", "amount", GUIUtils.formatMoney(milestone)));
            lore.add(plugin.tr("server-milestone-gui.donated", "amount", GUIUtils.formatMoney(total)));
            if (activeTarget == milestone) {
                lore.add(plugin.tr("server-milestone-gui.active-target"));
            }
            if (claimState.antiCloneProtected()) {
                lore.add(plugin.tr("server-milestone-gui.personal-requirement",
                        "amount", GUIUtils.formatMoney(claimState.minimumPersonalDonated())));
                lore.add(plugin.tr("server-milestone-gui.personal-donated",
                        "amount", GUIUtils.formatMoney(claimState.personalDonated())));
                if (claimState.blockedByAntiClone()) {
                    lore.add(plugin.tr("server-milestone-gui.personal-remaining",
                            "amount", GUIUtils.formatMoney(claimState.remainingPersonalDonated())));
                }
            }
            lore.add(statusLine);
            lore.add(" ");
            appendRewards(
                    lore,
                    section.getStringList(milestoneKey + ".rewards"),
                    section.getStringList(milestoneKey + "." + MilestoneManager.DISPLAY_REWARDS_KEY)
            );

            if (claimState.claimable()) {
                lore.add(" ");
                lore.add(plugin.tr("server-milestone-gui.click-claim"));
            } else if (claimState.blockedByAntiClone()) {
                lore.add(" ");
                lore.add(plugin.tr("server-milestone-gui.click-locked-personal"));
            }

            inv.setItem(MILESTONE_SLOTS[index], GUIUtils.item(
                    material,
                    plugin.tr("server-milestone-gui.item-title",
                            "amount", GUIUtils.formatMoney(milestone),
                            "number", index + 1),
                    lore.toArray(new String[0])
            ));

            index++;
        }

        inv.setItem(49, GUIUtils.item(
                Material.PAPER,
                plugin.tr("gui.server-info"),
                plugin.tr("server-milestone-gui.total-donated"),
                "§f" + GUIUtils.formatMoney(total) + " " + plugin.trPlain("general.currency"),
                plugin.tr("server-milestone-gui.current-target", "amount", GUIUtils.formatMoney(activeTarget))
        ));

        player.openInventory(inv);
    }

    private void paintAccentCorners(Inventory inv) {
        inv.setItem(0, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(8, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(inv.getSize() - 9, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(inv.getSize() - 1, GUIUtils.item(Material.CYAN_STAINED_GLASS_PANE, " "));
    }

    private void appendRewards(List<String> lore, List<String> rewards, List<String> displayRewards) {
        if (rewards.isEmpty()) {
            lore.add(plugin.tr("milestone-gui.reward-missing"));
            return;
        }

        lore.add(plugin.tr("milestone-gui.rewards"));
        if (displayRewards != null && !displayRewards.isEmpty()) {
            for (String displayReward : displayRewards) {
                lore.add("§f- " + ChatColor.translateAlternateColorCodes('&', displayReward));
            }
            lore.add(plugin.tr("milestone-gui.reward-count", "count", displayRewards.size()));
            return;
        }

        for (String reward : rewards) {
            lore.add("§f- " + formatReward(reward));
        }
        lore.add(plugin.tr("milestone-gui.reward-count", "count", rewards.size()));
    }

    private String formatReward(String command) {
        String lower = command.toLowerCase();

        if (lower.startsWith("eco give ")) {
            String[] parts = command.split("\\s+");
            if (parts.length >= 4) {
                try {
                    long money = Long.parseLong(parts[3]);
                    return plugin.tr("milestone-gui.reward-money", "amount", GUIUtils.formatMoney(money));
                } catch (NumberFormatException ignored) {
                }
            }
            return plugin.tr("milestone-gui.reward-money-generic");
        }

        if (lower.startsWith("give ")) {
            String[] parts = command.split("\\s+");
            if (parts.length >= 4) {
                String item = parts[2].toLowerCase();
                String amount = parts[3];
                return plugin.tr("milestone-gui.reward-item", "item", item, "amount", amount);
            }
            return plugin.tr("milestone-gui.reward-item-generic");
        }

        if (lower.startsWith("broadcast ") || lower.startsWith("bc ")) {
            return plugin.tr("milestone-gui.reward-broadcast");
        }

        if (lower.startsWith("lp user ") || lower.contains("parent add")) {
            return plugin.tr("milestone-gui.reward-rank");
        }

        if (lower.startsWith("points give ")) {
            String[] parts = command.split("\\s+");
            if (parts.length >= 4) {
                return plugin.tr("milestone-gui.reward-points", "points", parts[3]);
            }
            return plugin.tr("milestone-gui.reward-points", "points", "?");
        }

        return plugin.tr("milestone-gui.reward-special");
    }
}
