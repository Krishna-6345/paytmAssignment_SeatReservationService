package com.paytm.reservation.controller;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthAndMetricsController {

    private static final Logger log = LoggerFactory.getLogger(HealthAndMetricsController.class);

    private final JdbcTemplate jdbcTemplate;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    public HealthAndMetricsController(JdbcTemplate jdbcTemplate,
                                      PrometheusMeterRegistry prometheusMeterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "service", "Seat Reservation Service",
                "status", "UP",
                "docs", "Check README.md or available endpoints below",
                "endpoints", Map.of(
                        "health_live", "/health/live",
                        "health_ready", "/health/ready",
                        "metrics", "/metrics",
                        "shows", "/shows",
                        "reserve", "POST /shows/{showId}/reserve",
                        "cancel", "POST /reservations/{reservationId}/cancel"
                )
        ));
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> liveness() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        try {
            // Checks DB, fails closed
            Integer testQuery = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (testQuery != null && testQuery == 1) {
                return ResponseEntity.ok(Map.of(
                        "status", "UP",
                        "database", "UP"
                ));
            } else {
                log.warn("Database readiness check failed: unexpected query result: {}", testQuery);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "status", "DOWN",
                        "database", "DOWN"
                ));
            }
        } catch (Exception ex) {
            log.error("Database readiness check failed closed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "database", "DOWN",
                    "error", ex.getMessage()
            ));
        }
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> metrics() {
        String scrape = prometheusMeterRegistry.scrape();
        return ResponseEntity.ok(scrape);
    }
}
