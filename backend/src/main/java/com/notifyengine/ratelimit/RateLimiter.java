package com.notifyengine.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${rate-limit.requests-per-minute:100}")
    private int maxRequestsPerMinute;

    public boolean isAllowed(String apiKey) {
        try {
            String key = "ratelimit:" + apiKey;
            long currentTime = Instant.now().toEpochMilli();
            long windowStart = currentTime - 60000;

            // Remove old entries
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

            // Count current entries
            Long requestCount = redisTemplate.opsForZSet().zCard(key);

            if (requestCount != null && requestCount >= maxRequestsPerMinute) {
                return false;
            }

            // Add current request with unique member value to handle concurrent requests properly
            String memberValue = currentTime + ":" + java.util.UUID.randomUUID().toString();
            redisTemplate.opsForZSet().add(key, memberValue, currentTime);
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);

            return true;
        } catch (Exception e) {
            log.warn("RateLimiter Redis check failed (fallback to allow): {}", e.getMessage());
            return true;
        }
    }
}
