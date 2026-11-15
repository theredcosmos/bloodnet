package com.bloodnet.inventory.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows {@code limit} calls per client per minute. In memory, so each app instance counts on its
 * own; good enough to stop a script hammering the public page. A shared limit across instances
 * would need Redis or nginx's limit_req.
 */
public class FixedWindowRateLimiter {

    private record Window(long minute, int count) {
    }

    private final int limit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit) {
        this.limit = limit;
    }

    public boolean tryAcquire(String client, long epochMillis) {
        long minute = epochMillis / 60_000;
        Window w = windows.compute(client, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (windows.size() > 50_000) {
            // drop finished windows so the map can't grow without bound
            windows.values().removeIf(x -> x.minute() < minute);
        }
        return w.count() <= limit;
    }
}
