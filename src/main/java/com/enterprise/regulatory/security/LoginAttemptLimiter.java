package com.enterprise.regulatory.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles password guessing on the token endpoint: a client (IP address) that fails
 * {@link #MAX_FAILURES} logins within {@link #WINDOW} is refused until that window ends.
 *
 * <p>Failures are counted per client rather than per username on purpose: a per-username
 * limit would let anyone lock a known user out by sending wrong passwords for them.
 * State is in memory, so it is per instance and resets on restart.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    /**
     * Bounds memory use when failures arrive from very many addresses.
     */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Seconds until the client may try again, or 0 if it is not currently blocked.
     */
    public long retryAfterSeconds(String client) {
        Window window = windows.get(client);
        Instant now = clock.instant();
        if (window == null || window.hasEnded(now) || window.failures() < MAX_FAILURES) {
            return 0;
        }
        // Round up, so a client is never told to retry before the window has ended.
        return Math.max(1, (Duration.between(now, window.end()).toMillis() + 999) / 1000);
    }

    public void recordFailure(String client) {
        Instant now = clock.instant();
        if (windows.size() >= MAX_TRACKED_CLIENTS && !windows.containsKey(client)) {
            windows.values().removeIf(window -> window.hasEnded(now));
            if (windows.size() >= MAX_TRACKED_CLIENTS) {
                return;
            }
        }
        windows.compute(client, (key, window) -> window == null || window.hasEnded(now)
                                                     ? new Window(now.plus(WINDOW), 1)
                                                     : new Window(window.end(), window.failures() + 1));
    }

    private record Window(Instant end, int failures) {
        boolean hasEnded(Instant now) {
            return !now.isBefore(end);
        }
    }
}
