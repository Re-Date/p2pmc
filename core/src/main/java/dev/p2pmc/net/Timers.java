package dev.p2pmc.net;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One shared daemon timer thread for retransmission ticks, punch probes and keepalives. Tasks must not block. */
final class Timers {
    private static final System.Logger LOG = System.getLogger("p2pmc");
    private static final ScheduledThreadPoolExecutor EXEC = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "p2pmc-timer");
        t.setDaemon(true);
        return t;
    });

    static {
        EXEC.setRemoveOnCancelPolicy(true);
    }

    private Timers() {
    }

    /** Runs {@code task} immediately and then every {@code periodMillis}. */
    static ScheduledFuture<?> everyNow(long periodMillis, Runnable task) {
        return EXEC.scheduleWithFixedDelay(guard(task), 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Runs {@code task} every {@code periodMillis}, first run after one period. */
    static ScheduledFuture<?> every(long periodMillis, Runnable task) {
        return EXEC.scheduleWithFixedDelay(guard(task), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** A throwing task would silently cancel its own schedule, so every task is wrapped. */
    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "timer task failed", t);
            }
        };
    }
}
