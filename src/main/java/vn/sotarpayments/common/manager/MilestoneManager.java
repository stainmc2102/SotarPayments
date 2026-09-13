package vn.sotarpayments.common.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.scheduler.PlatformScheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MilestoneManager {
    public static final String PERSONAL_MILESTONES_PATH = "milestones";
    public static final String SERVER_MILESTONES_ROOT = "server-milestones";
    public static final String SERVER_MILESTONES_PATH = SERVER_MILESTONES_ROOT + ".milestones";
    public static final String SERVER_MILESTONES_ANTI_CLONE_PATH = SERVER_MILESTONES_ROOT + ".anti-clone";
    public static final String MINIMUM_PERSONAL_DONATED_KEY = "minimum-personal-donated";
    public static final String DISPLAY_REWARDS_KEY = "display-rewards";
    private static final long DEFAULT_SERVER_MILESTONE_MIN_PERSONAL_DONATED = 20_000L;
    private static final long BOSS_BAR_SHUTDOWN_TIMEOUT_SECONDS = 2L;

    private final SotarPayments plugin;
    private final AtomicLong cachedServerTotal = new AtomicLong(0L);
    private final AtomicLong bossBarGeneration = new AtomicLong();
    private final Set<String> hiddenBossBarPlayers = ConcurrentHashMap.newKeySet();
    private volatile BossBar bossBar;
    private ScheduledTask bossBarTask;

    public record ServerMilestoneClaimState(long serverTotal, long personalDonated,
                                            long minimumPersonalDonated, boolean reached,
                                            boolean claimed, boolean antiCloneProtected) {
        public boolean blockedByAntiClone() {
            return reached && !claimed && antiCloneProtected
                    && personalDonated < minimumPersonalDonated;
        }

        public boolean claimable() {
            return reached && !claimed && !blockedByAntiClone();
        }

        public long remainingPersonalDonated() {
            return Math.max(0L, minimumPersonalDonated - personalDonated);
        }
    }

    public MilestoneManager(SotarPayments plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stopBossBarTask();
        ensureDefaultServerMilestonesConfig();
        reloadBossBarPreferences();
        refreshServerTotalCache();
        synchronizeReachedServerMilestones(false);
        startBossBarTask();
    }

    public void shutdown() {
        stopBossBarTask();
        if (plugin.getPlatformScheduler().isFolia()) {
            retireBossBarOnGlobal(true);
            return;
        }

        // Paper/Leaf run plugin disable on their single primary thread. Removing every
        // viewer directly here avoids queuing work which the server is about to cancel.
        BossBar retired = detachBossBar();
        if (retired != null) {
            synchronized (retired) {
                retired.removeAll();
            }
        }
    }

    public void handleSuccessfulPayment(Player player, long amount) {
        if (amount > 0L) {
            cachedServerTotal.addAndGet(amount);
        } else {
            refreshServerTotalCache();
        }

        if (player != null) {
            check(player);
        }

        synchronizeReachedServerMilestones(true);
        requestBossBarUpdate();
    }

    public void check(Player player) {
        ConfigurationSection section = plugin.config().getConfigurationSection(PERSONAL_MILESTONES_PATH);
        if (section == null) return;

        long total = plugin.getDatabaseManager().getTotalDonated(player.getName());

        for (Long target : getPersonalMilestones()) {
            if (total < target) {
                continue;
            }

            grantPersonalMilestone(player, target, true, total);
        }
    }

    public boolean claimPersonalMilestone(Player player, long milestone) {
        long total = plugin.getDatabaseManager().getTotalDonated(player.getName());
        return grantPersonalMilestone(player, milestone, false, total);
    }

    public boolean claimServerMilestone(Player player, long milestone) {
        if (!isServerMilestonesEnabled()) {
            player.sendMessage(plugin.tr("server-milestone.disabled"));
            return false;
        }

        ConfigurationSection section = plugin.config().getConfigurationSection(SERVER_MILESTONES_PATH);
        if (section == null) {
            player.sendMessage(plugin.tr("server-milestone.no-config"));
            return false;
        }

        String milestoneKey = String.valueOf(milestone);
        ServerMilestoneClaimState claimState = getServerMilestoneClaimState(player, milestone);

        if (!claimState.reached()) {
            player.sendMessage(plugin.tr("server-milestone.not-reached"));
            return false;
        }

        if (claimState.claimed()) {
            player.sendMessage(plugin.tr("server-milestone.already-claimed"));
            return false;
        }

        if (claimState.blockedByAntiClone()) {
            player.sendMessage(plugin.tr("server-milestone.need-personal-topup",
                    "required", GUIUtils.formatMoney(claimState.minimumPersonalDonated()),
                    "current", GUIUtils.formatMoney(claimState.personalDonated()),
                    "remaining", GUIUtils.formatMoney(claimState.remainingPersonalDonated())));
            return false;
        }

        List<String> rewards = section.getStringList(milestoneKey + ".rewards");
        if (rewards.isEmpty()) {
            player.sendMessage(plugin.tr("server-milestone.reward-missing"));
            return false;
        }

        DatabaseManager.ClaimResult reservation = plugin.getDatabaseManager()
                .reserveServerMilestoneClaim(player.getName(), milestoneKey);
        if (reservation == DatabaseManager.ClaimResult.ALREADY_CLAIMED) {
            player.sendMessage(plugin.tr("server-milestone.already-claimed"));
            return false;
        }
        if (reservation == DatabaseManager.ClaimResult.DATABASE_ERROR) {
            player.sendMessage(plugin.tr("general.feature-unavailable"));
            return false;
        }

        for (String command : rewards) {
            plugin.dispatchConsoleCommand(applyRewardPlaceholders(command, player, milestone));
        }

        player.sendMessage(plugin.tr("server-milestone.claimed", "amount", GUIUtils.formatMoney(milestone)));
        return true;
    }

    public ServerMilestoneClaimState getServerMilestoneClaimState(Player player, long milestone) {
        long personalDonated = player == null ? 0L : plugin.getDatabaseManager().getTotalDonatedIgnoreCase(player.getName());
        return getServerMilestoneClaimState(player, milestone, personalDonated);
    }

    public ServerMilestoneClaimState getServerMilestoneClaimState(Player player, long milestone, long personalDonated) {
        String milestoneKey = String.valueOf(milestone);
        long serverTotal = getCachedServerTotal();
        boolean reached = serverTotal >= milestone;
        boolean claimed = player != null && plugin.getDatabaseManager().hasClaimedServerMilestone(player.getName(), milestoneKey);
        boolean milestoneOverride = hasServerMilestonePersonalRequirementOverride(milestone);
        long minimumPersonalDonated = getServerMilestoneMinimumPersonalDonated(milestone);
        boolean antiCloneProtected = isServerMilestonePersonalRequirementProtected(
                player,
                milestoneKey,
                minimumPersonalDonated,
                milestoneOverride
        );

        return new ServerMilestoneClaimState(
                serverTotal,
                Math.max(0L, personalDonated),
                minimumPersonalDonated,
                reached,
                claimed,
                antiCloneProtected
        );
    }

    public boolean isServerMilestoneAntiCloneEnabled() {
        return plugin.config().getBoolean(SERVER_MILESTONES_ANTI_CLONE_PATH + ".enabled", true)
                && getServerMilestoneMinimumPersonalDonated() > 0L;
    }

    public boolean isServerMilestoneAntiCloneNewPlayerOnly() {
        return plugin.config().getBoolean(SERVER_MILESTONES_ANTI_CLONE_PATH + ".new-player-only", true);
    }

    public long getServerMilestoneMinimumPersonalDonated() {
        return Math.max(0L, plugin.config().getLong(
                SERVER_MILESTONES_ANTI_CLONE_PATH + ".minimum-personal-donated",
                DEFAULT_SERVER_MILESTONE_MIN_PERSONAL_DONATED
        ));
    }

    public long getServerMilestoneMinimumPersonalDonated(long milestone) {
        String overridePath = getServerMilestonePersonalRequirementPath(milestone);
        if (plugin.config().contains(overridePath)) {
            return Math.max(0L, plugin.config().getLong(overridePath, 0L));
        }
        return getServerMilestoneMinimumPersonalDonated();
    }

    public boolean hasServerMilestonePersonalRequirementOverride(long milestone) {
        return plugin.config().contains(getServerMilestonePersonalRequirementPath(milestone));
    }

    public void setServerMilestonePersonalRequirement(long milestone, Long minimumPersonalDonated) {
        if (!isServerMilestoneConfigured(milestone)) {
            throw new IllegalArgumentException("Server milestone is not configured: " + milestone);
        }

        String path = getServerMilestonePersonalRequirementPath(milestone);
        plugin.config().set(path, minimumPersonalDonated == null ? null : Math.max(0L, minimumPersonalDonated));
        plugin.getConfigurationManager().saveMilestones();
        reload();
    }

    private String getServerMilestonePersonalRequirementPath(long milestone) {
        return SERVER_MILESTONES_PATH + "." + milestone + "." + MINIMUM_PERSONAL_DONATED_KEY;
    }

    private boolean isServerMilestonePersonalRequirementProtected(Player player, String milestoneKey,
                                                                    long minimumPersonalDonated,
                                                                    boolean milestoneOverride) {
        // A per-milestone value is an explicit claim condition for every player.
        // Without an override, preserve the legacy anti-clone/new-player-only behavior.
        if (milestoneOverride) {
            return minimumPersonalDonated > 0L;
        }

        if (!isServerMilestoneAntiCloneEnabled() || minimumPersonalDonated <= 0L) {
            return false;
        }

        if (!isServerMilestoneAntiCloneNewPlayerOnly()) {
            return true;
        }

        if (player == null) {
            return true;
        }

        long firstPlayedAt = Math.max(0L, player.getFirstPlayed());
        long reachedAt = plugin.getDatabaseManager().getReachedServerMilestoneAt(milestoneKey);
        if (firstPlayedAt <= 0L || reachedAt <= 0L) {
            return true;
        }

        return firstPlayedAt > reachedAt;
    }

    private boolean grantPersonalMilestone(Player player, long milestone, boolean automatic, long total) {
        ConfigurationSection section = plugin.config().getConfigurationSection(PERSONAL_MILESTONES_PATH);
        if (section == null) return false;

        String milestoneKey = String.valueOf(milestone);

        if (total < milestone) {
            if (!automatic) player.sendMessage(plugin.tr("milestone.not-reached"));
            return false;
        }

        List<String> rewards = section.getStringList(milestoneKey + ".rewards");
        if (rewards.isEmpty() && !automatic) {
            player.sendMessage(plugin.tr("milestone.reward-missing"));
            return false;
        }

        DatabaseManager.ClaimResult reservation = plugin.getDatabaseManager()
                .reserveMilestoneClaim(player.getName(), milestoneKey);
        if (reservation == DatabaseManager.ClaimResult.ALREADY_CLAIMED) {
            if (!automatic) player.sendMessage(plugin.tr("milestone.already-claimed"));
            return false;
        }
        if (reservation == DatabaseManager.ClaimResult.DATABASE_ERROR) {
            if (!automatic) player.sendMessage(plugin.tr("general.feature-unavailable"));
            return false;
        }

        for (String command : rewards) {
            plugin.dispatchConsoleCommand(applyRewardPlaceholders(command, player, milestone));
        }

        if (automatic) {
            player.sendMessage(plugin.tr("milestone.reached", "amount", GUIUtils.formatMoney(milestone)));
        } else {
            player.sendMessage(plugin.tr("milestone.claimed", "amount", GUIUtils.formatMoney(milestone)));
        }
        return true;
    }

    public List<Long> getPersonalMilestones() {
        return getMilestones(PERSONAL_MILESTONES_PATH);
    }

    public List<Long> getServerMilestones() {
        return getMilestones(SERVER_MILESTONES_PATH);
    }

    public boolean isServerMilestoneConfigured(long milestone) {
        ConfigurationSection section = plugin.config().getConfigurationSection(SERVER_MILESTONES_PATH);
        return section != null && section.isConfigurationSection(String.valueOf(milestone));
    }

    public long getCachedServerTotal() {
        return Math.max(0L, cachedServerTotal.get());
    }

    public long refreshServerTotalCache() {
        long total = Math.max(0L, plugin.getDatabaseManager().getServerTotalDonated());
        cachedServerTotal.set(total);
        return total;
    }

    public void resetServerMilestoneRuntimeData() {
        cachedServerTotal.set(0L);
        plugin.getDatabaseManager().resetServerMilestoneClaims();
        plugin.getDatabaseManager().resetReachedServerMilestones();
        requestBossBarUpdate();
    }

    public long getActiveServerMilestoneTarget() {
        return Math.max(0L, plugin.config().getLong(SERVER_MILESTONES_ROOT + ".active-target", 0L));
    }

    public void setActiveServerMilestoneTarget(long target) {
        plugin.config().set(SERVER_MILESTONES_ROOT + ".active-target", Math.max(0L, target));
        plugin.getConfigurationManager().saveMilestones();
        reload();
    }

    public long resolveBossBarTarget(long total) {
        long activeTarget = getActiveServerMilestoneTarget();
        if (activeTarget > 0L) {
            return activeTarget;
        }

        List<Long> milestones = getServerMilestones();
        if (milestones.isEmpty()) {
            return 0L;
        }

        for (Long milestone : milestones) {
            if (total < milestone) {
                return milestone;
            }
        }
        return milestones.get(milestones.size() - 1);
    }

    public boolean isServerMilestonesEnabled() {
        return plugin.config().getBoolean(SERVER_MILESTONES_ROOT + ".enabled", true);
    }

    public boolean isBossBarGloballyEnabled() {
        return plugin.config().getBoolean(SERVER_MILESTONES_ROOT + ".bossbar.enabled", true);
    }

    public void setBossBarGloballyEnabled(boolean enabled) {
        plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.enabled", enabled);
        plugin.getConfigurationManager().saveMilestones();
        reload();
    }

    public boolean isBossBarVisibleFor(Player player) {
        return player != null && isBossBarVisibleFor(player.getName());
    }

    public boolean isBossBarVisibleFor(String playerName) {
        String key = normalizePlayerKey(playerName);
        return !key.isBlank() && !hiddenBossBarPlayers.contains(key);
    }

    public void setBossBarVisibleFor(Player player, boolean visible) {
        if (player == null) return;
        setBossBarVisibleFor(player.getName(), visible);
    }

    public void setBossBarVisibleFor(String playerName, boolean visible) {
        String key = normalizePlayerKey(playerName);
        if (key.isBlank()) return;

        if (visible) {
            hiddenBossBarPlayers.remove(key);
        } else {
            hiddenBossBarPlayers.add(key);
        }

        plugin.getPlatformScheduler().runAsync(() -> plugin.getDatabaseManager().setBossBarEnabled(key, visible));
        requestBossBarUpdate();
    }

    public boolean toggleBossBarVisibility(Player player) {
        boolean visible = !isBossBarVisibleFor(player);
        setBossBarVisibleFor(player, visible);
        return visible;
    }

    private void reloadBossBarPreferences() {
        hiddenBossBarPlayers.clear();
        hiddenBossBarPlayers.addAll(plugin.getDatabaseManager().getDisabledBossBarPlayers());
    }

    private String normalizePlayerKey(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureDefaultServerMilestonesConfig() {
        boolean changed = false;

        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".enabled")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".enabled", true);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".active-target")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".active-target", 1000000L);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ANTI_CLONE_PATH + ".enabled")) {
            plugin.config().set(SERVER_MILESTONES_ANTI_CLONE_PATH + ".enabled", true);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ANTI_CLONE_PATH + ".minimum-personal-donated")) {
            plugin.config().set(SERVER_MILESTONES_ANTI_CLONE_PATH + ".minimum-personal-donated", DEFAULT_SERVER_MILESTONE_MIN_PERSONAL_DONATED);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ANTI_CLONE_PATH + ".new-player-only")) {
            plugin.config().set(SERVER_MILESTONES_ANTI_CLONE_PATH + ".new-player-only", true);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.enabled")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.enabled", true);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.color")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.color", "BLUE");
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.style")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.style", "SEGMENTED_10");
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.update-interval-ticks")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.update-interval-ticks", 100L);
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.progress-title")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.progress-title",
                    "&bMốc nạp server &8» &f{current}&7/&a{target} VNĐ &8(&e{percent}%&8)");
            changed = true;
        }
        if (!plugin.config().contains(SERVER_MILESTONES_ROOT + ".bossbar.reached-title")) {
            plugin.config().set(SERVER_MILESTONES_ROOT + ".bossbar.reached-title",
                    "&aMáy chủ đã đạt mốc nạp &e{target} VNĐ&a. &eDùng /mocnap server để nhận.");
            changed = true;
        }

        if (!plugin.config().isConfigurationSection(SERVER_MILESTONES_PATH)) {
            setDefaultServerMilestone(1000000L, List.of(
                    "give %player% diamond 5"
            ));
            setDefaultServerMilestone(2000000L, List.of(
                    "give %player% diamond 10",
                    "eco give %player% 100000"
            ));
            setDefaultServerMilestone(3000000L, List.of(
                    "give %player% diamond 15",
                    "eco give %player% 200000"
            ));
            setDefaultServerMilestone(4000000L, List.of(
                    "give %player% diamond 20",
                    "eco give %player% 300000"
            ));
            setDefaultServerMilestone(5000000L, List.of(
                    "give %player% diamond 32",
                    "eco give %player% 500000"
            ));
            changed = true;
        }

        if (changed) {
            plugin.getConfigurationManager().saveMilestones();
        }
    }

    private void setDefaultServerMilestone(long milestone, List<String> rewards) {
        plugin.config().set(SERVER_MILESTONES_PATH + "." + milestone + ".rewards", rewards);
    }

    private List<Long> getMilestones(String path) {
        List<Long> milestones = new ArrayList<>();
        ConfigurationSection section = plugin.config().getConfigurationSection(path);
        if (section == null) return milestones;

        for (String key : section.getKeys(false)) {
            try {
                milestones.add(Long.parseLong(key));
            } catch (NumberFormatException e) {
                plugin.logWarning("[MilestoneManager] Invalid milestone in milestones.yml at " + path + ": " + key);
            }
        }

        milestones.sort(Comparator.naturalOrder());
        return milestones;
    }

    private void synchronizeReachedServerMilestones(boolean announce) {
        if (!isServerMilestonesEnabled()) return;

        long total = getCachedServerTotal();
        for (Long milestone : getServerMilestones()) {
            if (total < milestone) {
                break;
            }

            String milestoneKey = String.valueOf(milestone);
            if (plugin.getDatabaseManager().hasReachedServerMilestone(milestoneKey)) {
                continue;
            }

            plugin.getDatabaseManager().saveReachedServerMilestone(milestoneKey);
            if (announce) {
                plugin.broadcast(plugin.tr("server-milestone.reached-broadcast", "amount", GUIUtils.formatMoney(milestone)));
            }
        }
    }

    private void startBossBarTask() {
        if (!isServerMilestonesEnabled() || !isBossBarGloballyEnabled()) {
            removeBossBar();
            return;
        }

        long interval = Math.max(20L, plugin.config().getLong(SERVER_MILESTONES_ROOT + ".bossbar.update-interval-ticks", 100L));
        bossBarTask = plugin.getPlatformScheduler().runTimerAsync(this::requestBossBarUpdate, 20L, interval);
        requestBossBarUpdate();
    }

    private void stopBossBarTask() {
        if (bossBarTask != null) {
            bossBarTask.cancel();
            bossBarTask = null;
        }
    }

    private void requestBossBarUpdate() {
        plugin.getPlatformScheduler().runGlobal(this::updateBossBar);
    }

    private void updateBossBar() {
        if (!isServerMilestonesEnabled() || !isBossBarGloballyEnabled()) {
            retireBossBarOnGlobal(false);
            return;
        }

        long total = getCachedServerTotal();
        long target = resolveBossBarTarget(total);
        if (target <= 0L) {
            retireBossBarOnGlobal(false);
            return;
        }

        double progress = Math.max(0.0D, Math.min(1.0D, total / (double) target));
        boolean reached = total >= target;

        BossBar activeBar = bossBar;
        long generation = bossBarGeneration.get();
        if (activeBar == null) {
            activeBar = Bukkit.createBossBar("", parseBossBarColor(), parseBossBarStyle());
            bossBar = activeBar;
            generation = bossBarGeneration.incrementAndGet();
        }

        synchronized (activeBar) {
            activeBar.setColor(parseBossBarColor());
            activeBar.setStyle(parseBossBarStyle());
            activeBar.setTitle(formatBossBarTitle(total, target, progress, reached));
            activeBar.setProgress(progress);
        }

        reconcileBossBarViewers(activeBar, generation);
    }

    private void reconcileBossBarViewers(BossBar expectedBar, long expectedGeneration) {
        Set<Player> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        candidates.addAll(Bukkit.getOnlinePlayers());
        synchronized (expectedBar) {
            candidates.addAll(new ArrayList<>(expectedBar.getPlayers()));
        }

        for (Player player : candidates) {
            Runnable retired = () -> removeBossBarViewer(expectedBar, player);
            plugin.getPlatformScheduler().runPlayer(player, () -> {
                synchronized (expectedBar) {
                    if (bossBar != expectedBar || bossBarGeneration.get() != expectedGeneration) {
                        expectedBar.removePlayer(player);
                        return;
                    }

                    if (player.isOnline() && isBossBarVisibleFor(player.getName())) {
                        expectedBar.addPlayer(player);
                    } else {
                        expectedBar.removePlayer(player);
                    }
                }
            }, retired);
        }
    }

    private void removeBossBarViewer(BossBar bar, Player player) {
        synchronized (bar) {
            bar.removePlayer(player);
        }
    }

    private BossBar detachBossBar() {
        BossBar retired = bossBar;
        if (retired != null) {
            bossBar = null;
            bossBarGeneration.incrementAndGet();
        }
        return retired;
    }

    private void retireBossBarOnGlobal(boolean awaitViewerCleanup) {
        BossBar retired = detachBossBar();
        if (retired == null) return;

        List<Player> viewers;
        synchronized (retired) {
            viewers = new ArrayList<>(retired.getPlayers());
        }
        if (viewers.isEmpty()) return;

        CountDownLatch completion = awaitViewerCleanup ? new CountDownLatch(viewers.size()) : null;
        AtomicInteger rejected = awaitViewerCleanup ? new AtomicInteger() : null;
        for (Player player : viewers) {
            AtomicBoolean completed = new AtomicBoolean(false);
            Runnable remover = () -> {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    removeBossBarViewer(retired, player);
                } finally {
                    if (completion != null) completion.countDown();
                }
            };

            boolean scheduled = plugin.getPlatformScheduler().runPlayer(player, remover, remover);
            if (!scheduled && completed.compareAndSet(false, true)) {
                if (rejected != null) rejected.incrementAndGet();
                if (completion != null) completion.countDown();
            }
        }

        if (completion == null) return;

        boolean drained = false;
        try {
            drained = completion.await(BOSS_BAR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!drained || rejected.get() > 0) {
            plugin.logWarning("BossBar viewer cleanup did not fully finish before shutdown; "
                    + "the server will discard the remaining viewers while disconnecting players.");
        }
    }

    private void removeBossBar() {
        plugin.getPlatformScheduler().runGlobal(() -> retireBossBarOnGlobal(false));
    }

    private String formatBossBarTitle(long total, long target, double progress, boolean reached) {
        String path = reached
                ? SERVER_MILESTONES_ROOT + ".bossbar.reached-title"
                : SERVER_MILESTONES_ROOT + ".bossbar.progress-title";
        String fallback = reached
                ? "&aMáy chủ đã đạt mốc nạp &e{target} VNĐ&a. &eDùng /mocnap server để nhận."
                : "&bMốc nạp server &8» &f{current}&7/&a{target} VNĐ";
        String title = normalizeMilestoneCommandHints(plugin.config().getString(path, fallback));
        int percent = (int) Math.floor(progress * 100.0D);
        return ChatColor.translateAlternateColorCodes('&', title
                .replace("{current}", GUIUtils.formatMoney(total))
                .replace("{total}", GUIUtils.formatMoney(total))
                .replace("{target}", GUIUtils.formatMoney(target))
                .replace("{amount}", GUIUtils.formatMoney(target))
                .replace("{percent}", String.valueOf(percent)));
    }

    private String normalizeMilestoneCommandHints(String message) {
        if (message == null || message.isBlank()) return message;
        return message.replace("/mocnap rewards", "/mocnap server");
    }

    private BarColor parseBossBarColor() {
        String raw = plugin.config().getString(SERVER_MILESTONES_ROOT + ".bossbar.color", "BLUE");
        try {
            return BarColor.valueOf(raw == null ? "BLUE" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.BLUE;
        }
    }

    private BarStyle parseBossBarStyle() {
        String raw = plugin.config().getString(SERVER_MILESTONES_ROOT + ".bossbar.style", "SEGMENTED_10");
        try {
            return BarStyle.valueOf(raw == null ? "SEGMENTED_10" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarStyle.SEGMENTED_10;
        }
    }

    private String applyRewardPlaceholders(String command, Player player, long milestone) {
        String playerName = player == null ? "" : player.getName();
        String total = String.valueOf(getCachedServerTotal());
        String formattedTotal = GUIUtils.formatMoney(getCachedServerTotal());
        String formattedMilestone = GUIUtils.formatMoney(milestone);
        return command
                .replace("%player%", playerName)
                .replace("{player}", playerName)
                .replace("%amount%", String.valueOf(milestone))
                .replace("%target%", String.valueOf(milestone))
                .replace("%milestone%", String.valueOf(milestone))
                .replace("%server_total%", total)
                .replace("%total%", total)
                .replace("%amount_formatted%", formattedMilestone)
                .replace("%target_formatted%", formattedMilestone)
                .replace("%server_total_formatted%", formattedTotal)
                .replace("%total_formatted%", formattedTotal);
    }
}
