package jieqi.ai;

import java.util.concurrent.TimeUnit;

/**
 * Per-move thinking budget for an agent.
 */
public final class TimeBudget {

    private final long limitNanos;
    private final long deadlineNanos;

    private TimeBudget(long limitNanos, long deadlineNanos) {
        this.limitNanos = limitNanos;
        this.deadlineNanos = deadlineNanos;
    }

    public static TimeBudget ofMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must be >= 0");
        }
        long limit = TimeUnit.MILLISECONDS.toNanos(millis);
        long now = System.nanoTime();
        long deadline = Long.MAX_VALUE - now < limit ? Long.MAX_VALUE : now + limit;
        return new TimeBudget(limit, deadline);
    }

    public static TimeBudget unlimited() {
        return new TimeBudget(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public boolean expired() {
        return remainingNanos() == 0;
    }

    public long remainingMillis() {
        long remaining = remainingNanos();
        return remaining == Long.MAX_VALUE ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(remaining);
    }

    public long remainingNanos() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    public long limitMillis() {
        return limitNanos == Long.MAX_VALUE ? Long.MAX_VALUE : TimeUnit.NANOSECONDS.toMillis(limitNanos);
    }
}
