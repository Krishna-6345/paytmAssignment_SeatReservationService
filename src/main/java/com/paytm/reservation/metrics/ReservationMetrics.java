package com.paytm.reservation.metrics;

import com.paytm.reservation.domain.SeatStatus;
import com.paytm.reservation.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter confirmedCounter;
    private final ConcurrentMap<String, Counter> declinedCounters = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry meterRegistry, SeatRepository seatRepository) {
        this.meterRegistry = meterRegistry;
        this.confirmedCounter = Counter.builder("reservations_confirmed_total")
                .description("Total number of successfully confirmed seat reservations")
                .register(meterRegistry);

        // Pre-register standard decline reason counters
        getOrCreateDeclinedCounter("seat_taken");
        getOrCreateDeclinedCounter("per_user_limit");
        getOrCreateDeclinedCounter("idempotent_replay");

        // Gauge for available seats
        Gauge.builder("seats_available", seatRepository,
                        repo -> repo.countByStatus(SeatStatus.AVAILABLE))
                .description("Current count of available seats across shows")
                .register(meterRegistry);
    }

    public void incrementConfirmed() {
        confirmedCounter.increment();
    }

    public void incrementDeclined(String reason) {
        getOrCreateDeclinedCounter(reason).increment();
    }

    private Counter getOrCreateDeclinedCounter(String reason) {
        return declinedCounters.computeIfAbsent(reason, r ->
                Counter.builder("reservations_declined_total")
                        .tag("reason", r)
                        .description("Total number of declined seat reservations by reason")
                        .register(meterRegistry)
        );
    }
}
