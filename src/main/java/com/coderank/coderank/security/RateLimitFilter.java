package com.coderank.coderank.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user rate limit for code submissions: 10 per minute, burst-friendly.
 * v2 note: swap the in-memory map for Redis-backed buckets to share state across instances.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(10)
                        .refillGreedy(10, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        // Only limit POST /api/v1/submissions
        boolean shouldLimit = "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/submissions".equals(request.getRequestURI());

        if (shouldLimit) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            String key = (auth != null && auth.getPrincipal() != null)
                    ? auth.getPrincipal().toString()
                    : request.getRemoteAddr();

            Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
            if (!bucket.tryConsume(1)) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Try again shortly.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}