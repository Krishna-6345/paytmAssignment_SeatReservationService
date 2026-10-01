package com.paytm.reservation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcAndAuthFilter extends OncePerRequestFilter {

    public static final String MDC_REQUEST_ID_KEY = "request_id";
    public static final String MDC_USER_ID_KEY = "user_id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_AUTHORIZATION = "Authorization";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. Resolve or generate Request ID for tracing
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);

        // 2. Extract Bearer token identity
        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        String userId = null;
        boolean isAdmin = false;

        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                userId = token;
                isAdmin = token.equalsIgnoreCase("admin") || token.toLowerCase().startsWith("admin-") || token.toLowerCase().endsWith("-admin");
            }
        }

        if (userId != null) {
            MDC.put(MDC_USER_ID_KEY, userId);
            UserContext.set(userId, isAdmin);
        } else {
            MDC.put(MDC_USER_ID_KEY, "anonymous");
            UserContext.clear();
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            MDC.clear();
        }
    }
}
