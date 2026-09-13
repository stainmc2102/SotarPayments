package vn.sotarpayments.common.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import vn.sotarpayments.SotarPayments;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class PlatformScheduler {
    private final Plugin plugin;
    private final boolean folia;
    private final Object lifecycleMonitor = new Object();
    private final Set<ScheduledTask> scheduledTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptingTasks = new AtomicBoolean(true);
    private int runningTasks;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    public boolean isFolia() {
        return folia;
    }

    public boolean isLeaf() {
        return folia && detectLeaf();
    }

    public String getPlatformName() {
        if (folia) {
            if (detectLeaf()) return "Leaf";
            return "Folia";
        }
        try {
            String serverName = Bukkit.getName();
            return serverName == null || serverName.isBlank() ? "Bukkit/Paper" : serverName;
        } catch (Throwable ignored) {
            return "Bukkit/Paper";
        }
    }

    public boolean runGlobal(Runnable runnable) {
        Runnable guarded = guard(runnable);
        if (guarded == null) return false;
        if (folia) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
                execute.invoke(scheduler, plugin, guarded);
                return true;
            } catch (Throwable throwable) {
                logWarning("Folia global scheduler failed; task was skipped to protect region thread safety.", throwable);
                return false;
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, guarded);
            return true;
        } catch (Throwable throwable) {
            logWarning("Bukkit global scheduler failed; task was not accepted.", throwable);
            return false;
        }
    }


    public ScheduledTask runGlobalLater(Runnable runnable, long delayTicks) {
        Runnable guarded = guard(runnable);
        if (guarded == null) return () -> {};
        long safeDelayTicks = Math.max(0L, delayTicks);
        if (folia) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Object task = runDelayed.invoke(
                        scheduler,
                        plugin,
                        (Consumer<Object>) scheduledTask -> guarded.run(),
                        Math.max(1L, safeDelayTicks)
                );
                return track(new ReflectionScheduledTask(task));
            } catch (Throwable throwable) {
                logWarning("Folia delayed global scheduler failed; task was skipped to protect region thread safety.", throwable);
                return () -> {};
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, guarded, safeDelayTicks);
        return track(task::cancel);
    }

    public boolean runPlayer(Player player, Runnable runnable) {
        return runPlayer(player, runnable, null);
    }

    public boolean runPlayer(Player player, Runnable runnable, Runnable retired) {
        if (runnable == null) return false;
        if (player == null) {
            return runGlobal(runnable);
        }
        Runnable guarded = guard(runnable);
        if (guarded == null) return false;
        Runnable guardedRetired = retired == null ? null : guard(retired);
        if (folia) {
            try {
                Object entityScheduler = player.getClass().getMethod("getScheduler").invoke(player);
                Method execute = entityScheduler.getClass().getMethod(
                        "execute", Plugin.class, Runnable.class, Runnable.class, long.class
                );
                Object result = execute.invoke(entityScheduler, plugin, guarded, guardedRetired, 1L);
                return !(result instanceof Boolean scheduled) || scheduled;
            } catch (Throwable throwable) {
                logWarning("Folia player scheduler failed; entity task was skipped instead of running on the wrong region.", throwable);
                return false;
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, guarded);
            return true;
        } catch (Throwable throwable) {
            logWarning("Bukkit player scheduler failed; task was not accepted.", throwable);
            return false;
        }
    }

    public boolean runAsync(Runnable runnable) {
        Runnable guarded = guard(runnable);
        if (guarded == null) return false;
        if (folia) {
            try {
                Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                Method runNow = scheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
                runNow.invoke(scheduler, plugin, (Consumer<Object>) task -> guarded.run());
                return true;
            } catch (Throwable throwable) {
                logWarning("Folia async scheduler failed; asynchronous task was skipped.", throwable);
                return false;
            }
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, guarded);
            return true;
        } catch (Throwable throwable) {
            logWarning("Bukkit async scheduler failed; task was not accepted.", throwable);
            return false;
        }
    }

    public ScheduledTask runTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        Runnable guarded = guard(runnable);
        if (guarded == null) return () -> {};
        long safeDelayTicks = Math.max(0L, delayTicks);
        long safePeriodTicks = Math.max(1L, periodTicks);
        if (folia) {
            try {
                Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                Method runAtFixedRate = scheduler.getClass().getMethod(
                        "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class
                );
                long delayMillis = Math.max(1L, safeDelayTicks * 50L);
                long periodMillis = Math.max(50L, safePeriodTicks * 50L);
                Object task = runAtFixedRate.invoke(
                        scheduler,
                        plugin,
                        (Consumer<Object>) scheduledTask -> guarded.run(),
                        delayMillis,
                        periodMillis,
                        TimeUnit.MILLISECONDS
                );
                return track(new ReflectionScheduledTask(task));
            } catch (Throwable throwable) {
                logWarning("Folia async timer failed; timer was not started.", throwable);
                return () -> {};
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, guarded, safeDelayTicks, safePeriodTicks);
        return track(task::cancel);
    }

    /**
     * Stops accepting work and cancels every timer owned by this scheduler. Running
     * jobs are allowed to finish and can be drained with {@link #awaitQuiescence(long)}.
     */
    public void beginShutdown() {
        if (!acceptingTasks.compareAndSet(true, false)) return;

        for (ScheduledTask task : scheduledTasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
        scheduledTasks.clear();

        if (folia) {
            cancelFoliaTasks("getGlobalRegionScheduler");
            cancelFoliaTasks("getAsyncScheduler");
        } else {
            try {
                Bukkit.getScheduler().cancelTasks(plugin);
            } catch (Throwable throwable) {
                logWarning("Could not cancel all Bukkit tasks during shutdown.", throwable);
            }
        }
    }

    /**
     * Waits for already-running jobs without ever waiting indefinitely.
     *
     * @return true when every tracked job finished before the timeout
     */
    public boolean awaitQuiescence(long timeoutMillis) {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (lifecycleMonitor) {
            while (runningTasks > 0 && remainingNanos > 0L) {
                long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                try {
                    lifecycleMonitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return runningTasks == 0;
        }
    }

    public void shutdownAndAwait(long timeoutMillis) {
        beginShutdown();
        if (!awaitQuiescence(timeoutMillis)) {
            logWarning("Timed out while waiting for background jobs to finish during shutdown.", null);
        }
    }

    private Runnable guard(Runnable runnable) {
        if (runnable == null || !acceptingTasks.get()) return null;
        return () -> {
            synchronized (lifecycleMonitor) {
                if (!acceptingTasks.get()) return;
                runningTasks++;
            }
            try {
                runnable.run();
            } finally {
                synchronized (lifecycleMonitor) {
                    runningTasks--;
                    if (runningTasks == 0) lifecycleMonitor.notifyAll();
                }
            }
        };
    }

    private ScheduledTask track(ScheduledTask task) {
        if (task == null) return () -> {};
        if (!acceptingTasks.get()) {
            task.cancel();
            return () -> {};
        }
        scheduledTasks.add(task);
        return () -> {
            scheduledTasks.remove(task);
            task.cancel();
        };
    }

    private void cancelFoliaTasks(String getterName) {
        try {
            Object scheduler = Bukkit.class.getMethod(getterName).invoke(null);
            Method cancelTasks = scheduler.getClass().getMethod("cancelTasks", Plugin.class);
            cancelTasks.invoke(scheduler, plugin);
        } catch (NoSuchMethodException ignored) {
            // Older Folia builds cancel plugin-owned tasks as part of plugin disable.
        } catch (Throwable throwable) {
            logWarning("Could not cancel Folia tasks from " + getterName + ".", throwable);
        }
    }

    private void logWarning(String message, Throwable throwable) {
        if (plugin instanceof SotarPayments sotarPayments) {
            if (throwable == null) {
                sotarPayments.logWarning(message);
            } else {
                sotarPayments.logWarning(message, throwable);
            }
            return;
        }
        if (throwable == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, throwable);
        }
    }

    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private boolean detectLeaf() {
        try {
            Class.forName("org.leavesmc.leaves.LeavesConfig");
            return true;
        } catch (ClassNotFoundException ignored) {
            try {
                Class.forName("cn.leavesmc.leaves.LeavesConfig");
                return true;
            } catch (ClassNotFoundException alsoIgnored) {
                return false;
            }
        }
    }

    public interface ScheduledTask {
        void cancel();
    }

    private static final class ReflectionScheduledTask implements ScheduledTask {
        private final Object task;

        private ReflectionScheduledTask(Object task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            if (task == null) return;
            try {
                task.getClass().getMethod("cancel").invoke(task);
            } catch (Throwable ignored) {
            }
        }
    }
}
