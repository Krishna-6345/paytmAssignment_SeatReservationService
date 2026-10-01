package com.paytm.reservation;

import com.paytm.reservation.dto.*;
import com.paytm.reservation.repository.SeatRepository;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SeatReservationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SeatReservationIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seat_reservation_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeatRepository seatRepository;

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private ShowDetailResponse createShow(String name, List<String> seats, long pricePaise) {
        CreateShowRequest request = new CreateShowRequest(name, seats, pricePaise);
        HttpEntity<CreateShowRequest> entity = new HttpEntity<>(request, createHeaders("admin"));
        ResponseEntity<ShowDetailResponse> response = restTemplate.postForEntity(
                getBaseUrl() + "/shows", entity, ShowDetailResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    @Test
    @Order(1)
    @DisplayName("1. 500 threads on one seat -> exactly one winner, 499 declines, 0 5xx")
    void test500ThreadsOnOneSeat_exactlyOneWinner() throws InterruptedException {
        int threadsCount = 500;
        List<String> seats = List.of("HOT-SEAT-1");
        ShowDetailResponse show = createShow("Hot Seat Show", seats, 50000L);
        UUID showId = show.id();

        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch readyLatch = new CountDownLatch(threadsCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger status201 = new AtomicInteger(0);
        AtomicInteger status409 = new AtomicInteger(0);
        AtomicInteger status5xx = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>(threadsCount);

        for (int i = 0; i < threadsCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                String userId = "user-contender-" + index;
                String idempotencyKey = "key-" + index;
                ReserveSeatsRequest reserveRequest = new ReserveSeatsRequest(List.of("HOT-SEAT-1"), idempotencyKey);
                HttpEntity<ReserveSeatsRequest> entity = new HttpEntity<>(reserveRequest, createHeaders(userId));

                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            getBaseUrl() + "/shows/" + showId + "/reserve",
                            entity,
                            String.class
                    );

                    int code = response.getStatusCode().value();
                    if (code == 201) {
                        status201.incrementAndGet();
                    } else if (code == 409) {
                        status409.incrementAndGet();
                    } else if (code >= 500) {
                        status5xx.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (Exception ex) {
                    log.error("Request error in thread {}", index, ex);
                    otherErrors.incrementAndGet();
                }
            }));
        }

        assertTrue(readyLatch.await(15, TimeUnit.SECONDS), "All threads must be ready");
        startLatch.countDown(); // FIRE ALL 500 THREADS SIMULTANEOUSLY

        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Thread execution failed: " + e.getMessage());
            }
        }
        executor.shutdown();

        log.info("500 Contention Results -> 201: {}, 409: {}, 5xx: {}, other: {}",
                status201.get(), status409.get(), status5xx.get(), otherErrors.get());

        // Assertions
        assertEquals(1, status201.get(), "Exactly one request must win the hot seat");
        assertEquals(499, status409.get(), "Exactly 499 requests must be declined with 409 Conflict");
        assertEquals(0, status5xx.get(), "There must be ZERO 5xx errors under heavy concurrency");
        assertEquals(0, otherErrors.get(), "No other unexpected status codes");

        // Verify reconciliation on show
        ShowDetailResponse verifiedShow = restTemplate.getForObject(getBaseUrl() + "/shows/" + showId, ShowDetailResponse.class);
        assertNotNull(verifiedShow);
        assertEquals(1, verifiedShow.counts().confirmed());
        assertEquals(0, verifiedShow.counts().available());
        assertEquals(1, verifiedShow.counts().total());
        assertEquals(verifiedShow.counts().total(),
                verifiedShow.counts().available() + verifiedShow.counts().held() + verifiedShow.counts().confirmed());
    }

    @Test
    @Order(2)
    @DisplayName("2. Idempotent retry: same key and body returns original reservation")
    void testIdempotentRetry_sameKeyAndBodyReturnsOriginalReservation() {
        ShowDetailResponse show = createShow("Idempotency Show", List.of("A1", "A2"), 25000L);
        UUID showId = show.id();

        String userId = "idempotent-user-1";
        String idempotencyKey = "unique-key-100";
        ReserveSeatsRequest request = new ReserveSeatsRequest(List.of("A1", "A2"), idempotencyKey);
        HttpEntity<ReserveSeatsRequest> entity = new HttpEntity<>(request, createHeaders(userId));

        // First attempt -> 201 Created
        ResponseEntity<ReservationResponse> firstResp = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + showId + "/reserve",
                entity,
                ReservationResponse.class
        );
        assertEquals(HttpStatus.CREATED, firstResp.getStatusCode());
        assertNotNull(firstResp.getBody());
        UUID originalReservationId = firstResp.getBody().reservationId();
        assertEquals("confirmed", firstResp.getBody().status());
        assertEquals(50000L, firstResp.getBody().amountPaise());

        // Second attempt with exact same key and body -> returns original reservation
        ResponseEntity<ReservationResponse> secondResp = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + showId + "/reserve",
                entity,
                ReservationResponse.class
        );
        assertTrue(secondResp.getStatusCode().is2xxSuccessful());
        assertNotNull(secondResp.getBody());
        assertEquals(originalReservationId, secondResp.getBody().reservationId());
        assertEquals(firstResp.getBody().amountPaise(), secondResp.getBody().amountPaise());
        assertEquals(firstResp.getBody().seats(), secondResp.getBody().seats());
    }

    @Test
    @Order(3)
    @DisplayName("3. Idempotency conflict: same key with different seats returns 409")
    void testIdempotencyConflict_sameKeyWithDifferentSeatsReturns409() {
        ShowDetailResponse show = createShow("Idempotency Conflict Show", List.of("B1", "B2", "B3"), 30000L);
        UUID showId = show.id();

        String userId = "conflict-user";
        String idempotencyKey = "fixed-key-555";

        // First request for B1
        ReserveSeatsRequest req1 = new ReserveSeatsRequest(List.of("B1"), idempotencyKey);
        ResponseEntity<ReservationResponse> resp1 = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + showId + "/reserve",
                new HttpEntity<>(req1, createHeaders(userId)),
                ReservationResponse.class
        );
        assertEquals(HttpStatus.CREATED, resp1.getStatusCode());

        // Second request with SAME key but DIFFERENT seat (B2)
        ReserveSeatsRequest req2 = new ReserveSeatsRequest(List.of("B2"), idempotencyKey);
        ResponseEntity<ErrorResponse> resp2 = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + showId + "/reserve",
                new HttpEntity<>(req2, createHeaders(userId)),
                ErrorResponse.class
        );
        assertEquals(HttpStatus.CONFLICT, resp2.getStatusCode());
        assertNotNull(resp2.getBody());
        assertEquals("idempotent_replay", resp2.getBody().reason());
    }

    @Test
    @Order(4)
    @DisplayName("4. Per-user limit (default 4) enforced under 10 parallel requests")
    void testPerUserLimit_with10ParallelRequests() throws InterruptedException {
        int seatCount = 20;
        List<String> seats = IntStream.rangeClosed(1, seatCount).mapToObj(i -> "Q-SEAT-" + i).toList();
        ShowDetailResponse show = createShow("Quota Test Show", seats, 10000L);
        UUID showId = show.id();

        String userId = "greedy-user-42";
        int requestsCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(requestsCount);
        CountDownLatch readyLatch = new CountDownLatch(requestsCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger quotaExceeded = new AtomicInteger(0);
        AtomicInteger serverErrors = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 1; i <= requestsCount; i++) {
            final int seatIndex = i;
            futures.add(executor.submit(() -> {
                String seatNum = "Q-SEAT-" + seatIndex;
                String key = "quota-key-" + seatIndex;
                ReserveSeatsRequest req = new ReserveSeatsRequest(List.of(seatNum), key);
                HttpEntity<ReserveSeatsRequest> entity = new HttpEntity<>(req, createHeaders(userId));

                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            getBaseUrl() + "/shows/" + showId + "/reserve",
                            entity,
                            String.class
                    );
                    if (response.getStatusCode().value() == 201) {
                        successes.incrementAndGet();
                    } else if (response.getStatusCode().value() == 409) {
                        quotaExceeded.incrementAndGet();
                    } else if (response.getStatusCode().value() >= 500) {
                        serverErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Quota test error", e);
                }
            }));
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();

        for (Future<?> f : futures) {
            try {
                f.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail(e.getMessage());
            }
        }
        executor.shutdown();

        log.info("Quota Results -> Successes: {}, QuotaExceeded: {}, 5xx: {}",
                successes.get(), quotaExceeded.get(), serverErrors.get());

        // Per user limit is 4: exactly 4 requests must succeed, and 6 must be declined with 409
        assertEquals(4, successes.get(), "User must be strictly capped at 4 seats");
        assertEquals(6, quotaExceeded.get(), "Remaining 6 requests must be rejected with 409");
        assertEquals(0, serverErrors.get(), "Zero 5xx under concurrency");
    }

    @Test
    @Order(5)
    @DisplayName("5. Multi-seat overlap without deadlock")
    void testMultiSeatOverlap_withoutDeadlock() throws InterruptedException {
        // Seats: S1, S2, S3, S4, S5
        List<String> seats = List.of("S1", "S2", "S3", "S4", "S5");
        ShowDetailResponse show = createShow("Deadlock Avoidance Show", seats, 15000L);
        UUID showId = show.id();

        // 20 threads requesting reverse and overlapping seat combinations
        List<List<String>> combinations = List.of(
                List.of("S2", "S1"), // reverse ordered input
                List.of("S1", "S2"),
                List.of("S3", "S2"),
                List.of("S2", "S3"),
                List.of("S4", "S3"),
                List.of("S3", "S4"),
                List.of("S5", "S4"),
                List.of("S4", "S5"),
                List.of("S1", "S3"),
                List.of("S3", "S1"),
                List.of("S2", "S4"),
                List.of("S4", "S2"),
                List.of("S3", "S5"),
                List.of("S5", "S3"),
                List.of("S1", "S5"),
                List.of("S5", "S1"),
                List.of("S2", "S5"),
                List.of("S5", "S2"),
                List.of("S1", "S4"),
                List.of("S4", "S1")
        );

        int workers = combinations.size();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch readyLatch = new CountDownLatch(workers);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger confirmedCount = new AtomicInteger(0);
        AtomicInteger declinedCount = new AtomicInteger(0);
        AtomicInteger deadlocksOr5xx = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            final int idx = i;
            final List<String> seatList = combinations.get(i);
            futures.add(executor.submit(() -> {
                String user = "deadlock-tester-" + idx;
                String key = "dl-key-" + idx;
                ReserveSeatsRequest req = new ReserveSeatsRequest(seatList, key);
                HttpEntity<ReserveSeatsRequest> entity = new HttpEntity<>(req, createHeaders(user));

                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            getBaseUrl() + "/shows/" + showId + "/reserve",
                            entity,
                            String.class
                    );
                    if (response.getStatusCode().value() == 201) {
                        confirmedCount.incrementAndGet();
                    } else if (response.getStatusCode().value() == 409) {
                        declinedCount.incrementAndGet();
                    } else if (response.getStatusCode().value() >= 500) {
                        deadlocksOr5xx.incrementAndGet();
                    }
                } catch (Exception ex) {
                    deadlocksOr5xx.incrementAndGet();
                }
            }));
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();

        for (Future<?> f : futures) {
            try {
                f.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Worker failed: " + e.getMessage());
            }
        }
        executor.shutdown();

        log.info("Deadlock Overlap Results -> Confirmed: {}, Declined: {}, Deadlocks/5xx: {}",
                confirmedCount.get(), declinedCount.get(), deadlocksOr5xx.get());

        // Crucial: zero deadlocks occurred, zero 5xx
        assertEquals(0, deadlocksOr5xx.get(), "Sorting seat IDs must completely eliminate deadlocks");
        assertTrue(confirmedCount.get() >= 1, "At least one non-overlapping pair should be confirmed");
        assertEquals(workers, confirmedCount.get() + declinedCount.get());
    }

    @Test
    @Order(6)
    @DisplayName("6. Reconciliation invariant: available + held + confirmed == total holds across lifecycle")
    void testReconciliationInvariant() {
        List<String> seats = List.of("R1", "R2", "R3", "R4");
        ShowDetailResponse show = createShow("Reconciliation Show", seats, 20000L);
        UUID showId = show.id();

        // 1. Initial State
        ShowDetailResponse step1 = restTemplate.getForObject(getBaseUrl() + "/shows/" + showId, ShowDetailResponse.class);
        assertNotNull(step1);
        assertEquals(4, step1.counts().available());
        assertEquals(0, step1.counts().held());
        assertEquals(0, step1.counts().confirmed());
        assertEquals(4, step1.counts().total());
        assertEquals(step1.counts().total(), step1.counts().available() + step1.counts().held() + step1.counts().confirmed());

        // 2. Reserve 2 seats
        ReserveSeatsRequest req = new ReserveSeatsRequest(List.of("R1", "R2"), "recon-key-1");
        ResponseEntity<ReservationResponse> resResp = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + showId + "/reserve",
                new HttpEntity<>(req, createHeaders("recon-user")),
                ReservationResponse.class
        );
        assertEquals(HttpStatus.CREATED, resResp.getStatusCode());
        assertNotNull(resResp.getBody());
        UUID reservationId = resResp.getBody().reservationId();

        ShowDetailResponse step2 = restTemplate.getForObject(getBaseUrl() + "/shows/" + showId, ShowDetailResponse.class);
        assertNotNull(step2);
        assertEquals(2, step2.counts().available());
        assertEquals(2, step2.counts().confirmed());
        assertEquals(4, step2.counts().total());
        assertEquals(step2.counts().total(), step2.counts().available() + step2.counts().held() + step2.counts().confirmed());

        // 3. Owner Cancels reservation
        ResponseEntity<CancelReservationResponse> cancelResp = restTemplate.postForEntity(
                getBaseUrl() + "/reservations/" + reservationId + "/cancel",
                new HttpEntity<>(null, createHeaders("recon-user")),
                CancelReservationResponse.class
        );
        assertEquals(HttpStatus.OK, cancelResp.getStatusCode());

        ShowDetailResponse step3 = restTemplate.getForObject(getBaseUrl() + "/shows/" + showId, ShowDetailResponse.class);
        assertNotNull(step3);
        assertEquals(4, step3.counts().available());
        assertEquals(0, step3.counts().confirmed());
        assertEquals(4, step3.counts().total());
        assertEquals(step3.counts().total(), step3.counts().available() + step3.counts().held() + step3.counts().confirmed());
    }

    @Test
    @Order(7)
    @DisplayName("7. Non-owner cancellation is forbidden (403)")
    void testCancelReservation_nonOwnerForbidden() {
        ShowDetailResponse show = createShow("Security Show", List.of("SEC-1"), 15000L);
        ReserveSeatsRequest req = new ReserveSeatsRequest(List.of("SEC-1"), "sec-key-1");
        ResponseEntity<ReservationResponse> res = restTemplate.postForEntity(
                getBaseUrl() + "/shows/" + show.id() + "/reserve",
                new HttpEntity<>(req, createHeaders("alice")),
                ReservationResponse.class
        );
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertNotNull(res.getBody());

        // Bob attempts to cancel Alice's reservation
        ResponseEntity<ErrorResponse> cancelAttempt = restTemplate.postForEntity(
                getBaseUrl() + "/reservations/" + res.getBody().reservationId() + "/cancel",
                new HttpEntity<>(null, createHeaders("bob")),
                ErrorResponse.class
        );
        assertEquals(HttpStatus.FORBIDDEN, cancelAttempt.getStatusCode());
        assertNotNull(cancelAttempt.getBody());
        assertEquals("forbidden", cancelAttempt.getBody().reason());
    }

    @Test
    @Order(8)
    @DisplayName("8. Health and Prometheus metrics endpoints operate correctly")
    void testHealthAndMetricsEndpoints() {
        // /health/live -> 200 UP
        ResponseEntity<Map<String, String>> liveResp = restTemplate.exchange(
                getBaseUrl() + "/health/live",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, liveResp.getStatusCode());
        assertNotNull(liveResp.getBody());
        assertEquals("UP", liveResp.getBody().get("status"));

        // /health/ready -> 200 UP (checks DB)
        ResponseEntity<Map<String, Object>> readyResp = restTemplate.exchange(
                getBaseUrl() + "/health/ready",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, readyResp.getStatusCode());
        assertNotNull(readyResp.getBody());
        assertEquals("UP", readyResp.getBody().get("status"));
        assertEquals("UP", readyResp.getBody().get("database"));

        // /metrics -> 200 plain text containing Prometheus metrics
        ResponseEntity<String> metricsResp = restTemplate.getForEntity(
                getBaseUrl() + "/metrics",
                String.class
        );
        assertEquals(HttpStatus.OK, metricsResp.getStatusCode());
        assertNotNull(metricsResp.getBody());
        assertTrue(metricsResp.getBody().contains("reservations_confirmed_total"));
        assertTrue(metricsResp.getBody().contains("reservations_declined_total"));
        assertTrue(metricsResp.getBody().contains("seats_available"));
    }
}
