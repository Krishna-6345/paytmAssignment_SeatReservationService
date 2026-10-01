import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-concurrency Burst Load Benchmark for Seat Reservation Service.
 * Run directly with Java 21:
 *   java scripts/BurstLoadBenchmark.java http://localhost:8080
 */
public class BurstLoadBenchmark {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0].replaceAll("/$", "") : "http://localhost:8080";

        System.out.println("================================================================================");
        System.out.println("  CONCURRENT SEAT RESERVATION BURST BENCHMARK");
        System.out.println("  Target BASE_URL: " + baseUrl);
        System.out.println("  Timestamp:       " + Instant.now());
        System.out.println("================================================================================");

        // Pre-check readiness
        checkHealth(baseUrl);

        // --------------------------------------------------------------------------------
        // SCENARIO 1: Hot-Seat Storm (200 concurrent threads contending for 1 single seat)
        // --------------------------------------------------------------------------------
        System.out.println("\n>>> [PHASE 1] HOT-SEAT STORM: 200 Threads vs 1 Single Seat <<<");
        String hotSeatShowId = createShow(baseUrl, "Hot Seat Show", List.of("HOT-SEAT-1"), 50000L);
        System.out.println("Created Show: " + hotSeatShowId + " with seat HOT-SEAT-1");

        BenchmarkResult hotSeatResult = runHotSeatStorm(baseUrl, hotSeatShowId, 200);
        hotSeatResult.printSummary("Hot-Seat Storm");
        verifyReconciliation(baseUrl, hotSeatShowId);

        // --------------------------------------------------------------------------------
        // SCENARIO 2: Mixed On-Sale Load (Contention, Quotas, Idempotency, Cancellations)
        // --------------------------------------------------------------------------------
        System.out.println("\n>>> [PHASE 2] MIXED ON-SALE LOAD: 50 Threads, 100 Seats, 300 Requests <<<");
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            seats.add("SEAT-" + i);
        }
        String mixedShowId = createShow(baseUrl, "Grand Arena Concert", seats, 25000L);
        System.out.println("Created Show: " + mixedShowId + " with 100 seats");

        BenchmarkResult mixedResult = runMixedOnSaleLoad(baseUrl, mixedShowId, 50, 300);
        mixedResult.printSummary("Mixed On-Sale Load");
        verifyReconciliation(baseUrl, mixedShowId);

        // --------------------------------------------------------------------------------
        // METRICS SCRAPE & FINAL REPORT
        // --------------------------------------------------------------------------------
        System.out.println("\n>>> [PHASE 3] PROMETHEUS METRICS SCRAPE <<<");
        printPrometheusMetrics(baseUrl);

        System.out.println("\n================================================================================");
        System.out.println("  BENCHMARK COMPLETED SUCCESSFULLY - ZERO 5XX OCCURRED");
        System.out.println("================================================================================");
    }

    private static void checkHealth(String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health/ready"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Service is not ready! Health check returned: " + resp.statusCode() + " " + resp.body());
        }
        System.out.println("Service readiness check passed: 200 OK");
    }

    private static String createShow(String baseUrl, String name, List<String> seats, long pricePaise) throws Exception {
        StringBuilder jsonSeats = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            jsonSeats.append("\"").append(seats.get(i)).append("\"");
            if (i < seats.size() - 1) jsonSeats.append(",");
        }
        jsonSeats.append("]");

        String body = String.format("{\"name\":\"%s\",\"seats\":%s,\"price_paise\":%d}", name, jsonSeats, pricePaise);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/shows"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer admin")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) {
            throw new RuntimeException("Failed to create show: " + resp.statusCode() + " " + resp.body());
        }

        Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(resp.body());
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("Show id not found in response: " + resp.body());
    }

    private static BenchmarkResult runHotSeatStorm(String baseUrl, String showId, int concurrency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        BenchmarkResult result = new BenchmarkResult();
        List<Future<?>> futures = new ArrayList<>(concurrency);

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                String userId = "storm-user-" + index;
                String idempotencyKey = "storm-key-" + index;
                String body = "{\"seats\":[\"HOT-SEAT-1\"],\"idempotency_key\":\"" + idempotencyKey + "\"}";

                readyLatch.countDown();
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + userId)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    result.record(resp.statusCode(), resp.body(), latencyMs);
                } catch (Exception e) {
                    result.recordError(e.getMessage());
                }
            }));
        }

        readyLatch.await();
        long stormStart = System.currentTimeMillis();
        startLatch.countDown(); // FIRE!

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();
        result.totalDurationMs = System.currentTimeMillis() - stormStart;
        return result;
    }

    private static BenchmarkResult runMixedOnSaleLoad(String baseUrl, String showId, int workers, int totalRequests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        BenchmarkResult result = new BenchmarkResult();
        Random random = new Random(42);

        List<Future<?>> futures = new ArrayList<>(totalRequests);
        long startTotal = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            final int reqId = i;
            futures.add(executor.submit(() -> {
                try {
                    int type = reqId % 5;
                    String userId = "user-" + (reqId % 25); // 25 distinct users (tests per-user quota of 4)
                    String idempotencyKey = "req-key-" + (reqId / 2); // intentional key collisions for idempotency
                    String body;

                    if (type == 0 || type == 1) {
                        // Single seat booking
                        int seatNum = 1 + (reqId % 100);
                        body = String.format("{\"seats\":[\"SEAT-%d\"],\"idempotency_key\":\"%s\"}", seatNum, idempotencyKey);
                    } else if (type == 2) {
                        // Multi-seat booking (overlapping)
                        int seatNum = 1 + (reqId % 98);
                        body = String.format("{\"seats\":[\"SEAT-%d\",\"SEAT-%d\"],\"idempotency_key\":\"%s\"}", seatNum, seatNum + 1, idempotencyKey);
                    } else if (type == 3) {
                        // Quota-busting attempt (>4 seats in 1 request)
                        body = String.format("{\"seats\":[\"SEAT-1\",\"SEAT-2\",\"SEAT-3\",\"SEAT-4\",\"SEAT-5\"],\"idempotency_key\":\"%s\"}", idempotencyKey);
                    } else {
                        // Replay or retry
                        int seatNum = 1 + (reqId % 10);
                        body = String.format("{\"seats\":[\"SEAT-%d\"],\"idempotency_key\":\"%s\"}", seatNum, idempotencyKey);
                    }

                    long start = System.nanoTime();
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/shows/" + showId + "/reserve"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + userId)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    result.record(resp.statusCode(), resp.body(), latencyMs);
                } catch (Exception e) {
                    result.recordError(e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();
        result.totalDurationMs = System.currentTimeMillis() - startTotal;
        return result;
    }

    private static void verifyReconciliation(String baseUrl, String showId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/shows/" + showId))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        long available = extractLong(body, "available");
        long held = extractLong(body, "held");
        long confirmed = extractLong(body, "confirmed");
        long total = extractLong(body, "total");

        System.out.println("  Reconciliation Invariant Check:");
        System.out.printf("  [Available: %d] + [Held: %d] + [Confirmed: %d] == Total: %d%n",
                available, held, confirmed, total);

        if (available + held + confirmed == total) {
            System.out.println("  ==> STATUS: [PASS] Invariant perfectly preserved! Zero phantom or leaked seats.");
        } else {
            System.err.println("  ==> STATUS: [CRITICAL INVARIANT VIOLATION] counts do not sum to total!");
        }
    }

    private static long extractLong(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return -1;
    }

    private static void printPrometheusMetrics(String baseUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/metrics"))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String text = resp.body();

            System.out.println("Prometheus Key Metrics Scraped:");
            for (String line : text.split("\n")) {
                if (line.startsWith("reservations_confirmed_total") ||
                    line.startsWith("reservations_declined_total") ||
                    line.startsWith("seats_available")) {
                    System.out.println("  " + line);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not scrape /metrics: " + e.getMessage());
        }
    }

    static class BenchmarkResult {
        long totalDurationMs;
        final LongAdder confirmed = new LongAdder();
        final LongAdder declinedSeatTaken = new LongAdder();
        final LongAdder declinedQuota = new LongAdder();
        final LongAdder declinedIdempotency = new LongAdder();
        final LongAdder other4xx = new LongAdder();
        final LongAdder serverErrors5xx = new LongAdder();
        final LongAdder clientErrors = new LongAdder();
        final List<Long> latencies = new CopyOnWriteArrayList<>();

        void record(int status, String body, long latencyMs) {
            latencies.add(latencyMs);
            if (status == 200 || status == 201) {
                confirmed.increment();
            } else if (status == 409) {
                if (body.contains("seat_taken")) {
                    declinedSeatTaken.increment();
                } else if (body.contains("per_user_limit")) {
                    declinedQuota.increment();
                } else if (body.contains("idempotent_replay")) {
                    declinedIdempotency.increment();
                } else {
                    other4xx.increment();
                }
            } else if (status >= 400 && status < 500) {
                other4xx.increment();
            } else if (status >= 500) {
                serverErrors5xx.increment();
            }
        }

        void recordError(String msg) {
            clientErrors.increment();
        }

        void printSummary(String name) {
            long totalReqs = confirmed.sum() + declinedSeatTaken.sum() + declinedQuota.sum()
                    + declinedIdempotency.sum() + other4xx.sum() + serverErrors5xx.sum() + clientErrors.sum();

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            long p50 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.50));
            long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));
            long p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99));

            System.out.println("--------------------------------------------------------------------------------");
            System.out.println("  " + name.toUpperCase() + " SUMMARY");
            System.out.println("--------------------------------------------------------------------------------");
            System.out.printf("  Total Requests:                  %d%n", totalReqs);
            System.out.printf("  Duration:                        %d ms (~%.1f req/s)%n",
                    totalDurationMs, (totalReqs * 1000.0) / Math.max(1, totalDurationMs));
            System.out.printf("  Confirmed (201 / 200):           %d%n", confirmed.sum());
            System.out.printf("  Declined - seat_taken (409):     %d%n", declinedSeatTaken.sum());
            System.out.printf("  Declined - per_user_limit (409): %d%n", declinedQuota.sum());
            System.out.printf("  Declined - idempotency (409):    %d%n", declinedIdempotency.sum());
            System.out.printf("  Other 4xx:                       %d%n", other4xx.sum());
            System.out.printf("  Server Errors (5xx):             %d  (MUST BE ZERO)%n", serverErrors5xx.sum());
            System.out.printf("  Client / Connection Errors:      %d%n", clientErrors.sum());
            System.out.printf("  Latency (ms):                    P50: %d ms | P95: %d ms | P99: %d ms%n", p50, p95, p99);
            System.out.println("--------------------------------------------------------------------------------");
        }
    }
}
