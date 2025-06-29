package com.example.mcpchat.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
public class AiSummaryCache {

    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private final Duration ttl = Duration.ofMinutes(5);
    private final Map<String, Instant> timestamps = new ConcurrentHashMap<>();

    public Map<String, Object> get(String userId, Function<String, Map<String, Object>> apiCall) {
        if (cache.containsKey(userId) &&
                Instant.now().isBefore(timestamps.get(userId).plus(ttl))) {
            return cache.get(userId);
        }

        Map<String, Object> user = apiCall.apply(userId);
        cache.put(userId, user);
        timestamps.put(userId, Instant.now());
        return user;
    }

    public void evict(String userId) {
        cache.remove(userId);
        timestamps.remove(userId);
    }
}
