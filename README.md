# High-Concurrency Seat Reservation Service

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blueviolet.svg)](https://www.docker.com/)

A production-grade, highly concurrent seat-reservation backend service built in Java 21 and Spring Boot 3. The service guarantees absolute consistency under heavy concurrent contention (zero double-selling, zero deadlocks, deterministic idempotency, fail-closed readiness, and zero 5xx server errors under burst load).

---

## 🏗️ Architecture & Technology Stack

- **Runtime:** Java 21 LTS (utilizing records, sealed hierarchies, and modern concurrency features).
- **Framework:** Spring Boot 3.3.4 (Spring Data JPA, Spring Validation, Spring Actuator).
- **Database & Migration:** PostgreSQL 16 managed strictly through Flyway schema migrations (`db/migration/V1__init_schema.sql`).
- **Observability:** Micrometer + Prometheus metrics scraping (`/metrics`) and Logstash Logback JSON encoder with MDC request tracing (`request_id`, `user_id`).
- **Testing:** Testcontainers (PostgreSQL 16) and JUnit 5 concurrency stress tests.
- **Deployment:** Multi-stage Dockerfile and Docker Compose topology (App + Postgres + Prometheus).

---

## 🚀 Key Architectural Guarantees

1. **Atomic Conditional UPDATE (Zero Double-Selling):**
   ```sql
   UPDATE seats
   SET status = 'confirmed', reservation_id = :resId, updated_at = NOW(), version = version + 1
   WHERE show_id = :showId AND seat_number IN (:seats) AND status = 'available';
   ```
   If updated row count does not equal the number of requested seats, the transaction rolls back immediately and returns `409 Conflict` (`reason: seat_taken`).

2. **Deadlock Elimination via Monotonic Lock Ordering:**
   All multi-seat requests are sorted canonically by seat identifier prior to acquiring database locks, eliminating circular wait conditions ($T_1: S_1 \to S_2$ vs $T_2: S_2 \to S_1$).

3. **Per-User Quota Concurrency (Default: 4 Seats):**
   Enforced via row-level write lock on `(show_id, user_id)` in `user_show_quotas` inside the reservation transaction. Serializes only the quota increments for that specific user and show, without bottlenecking other users.

4. **Deterministic Idempotency:**
   Enforces `(user_id, idempotency_key)` uniqueness coupled with a canonical SHA-256 request payload hash. 
   - Replaying identical requests returns the original reservation (`200 OK` / `201 Created`).
   - Reusing the same key with different seats or show parameters triggers `409 Conflict` (`reason: idempotent_replay`).

5. **Fail-Closed Health Probes:**
   - `GET /health/live`: Reports basic container liveness (`200 UP`).
   - `GET /health/ready`: Performs live database ping (`SELECT 1`). Fails closed (`503 SERVICE UNAVAILABLE`) if the database is unreachable or degraded.

6. **Zero 5xx Under Load:**
   Global exception translation interceptor translates all domain rejections, unique constraint conflicts, and pessimistic lock contentions into `4xx` responses (`409 Conflict`, `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`).

---

## 📡 API Endpoints

All monetary values are strictly represented in **integer paise** (e.g. ₹250.00 = `25000` paise). User identity is extracted exclusively from the `Authorization: Bearer <token>` header; any `user_id` inside request payloads is ignored.

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/shows` | Public | Create a show with seats starting `available` and `price_paise` |
| `POST` | `/shows/{id}/reserve` | User (`Bearer <user_id>`) | Atomically reserve seats with an `idempotency_key` |
| `POST` | `/reservations/{id}/cancel` | Owner (`Bearer <user_id>`) | Guarded cancellation; releases seats back to `available` |
| `GET` | `/shows/{id}` | Public | Detailed per-seat statuses + counts (`available + held + confirmed == total`) |
| `GET` | `/health/live` | Public | Kubernetes liveness probe |
| `GET` | `/health/ready` | Public | Kubernetes readiness probe (fails closed on DB) |
| `GET` | `/metrics` | Public | Prometheus scrape exposition endpoint |

---

## 💻 Quick Start & Running Locally

### Prerequisites
- JDK 21
- Maven 3.9+ (or use the included `./mvnw`)
- Docker & Docker Compose

### 1. Build and Test
```bash
# Compile and run unit tests
./mvnw clean test

# Package executable JAR
./mvnw clean package -DskipTests
```

### 2. Deploy Full Stack via Docker Compose
Starts PostgreSQL 16, Seat Reservation Service, and Prometheus:
```bash
docker compose up --build -d
```

Verify service readiness:
```bash
curl -i http://localhost:8080/health/ready
```
Output:
```json
{"database":"UP","status":"UP"}
```

---

## ⚡ Concurrency & Burst Load Testing

The project includes an autonomous, zero-dependency Java 21 benchmark script as well as a k6 load script.

### Option A: Run Java 21 Burst Benchmark
Executes a **Hot-Seat Storm** (200 threads competing for 1 seat) followed by a **Mixed On-Sale Load** (300 requests across 100 seats):
```bash
java scripts/BurstLoadBenchmark.java http://localhost:8080
```

Sample Benchmark Output:
```text
================================================================================
  HOT-SEAT STORM SUMMARY
--------------------------------------------------------------------------------
  Total Requests:                  200
  Duration:                        412 ms (~485.4 req/s)
  Confirmed (201 / 200):           1
  Declined - seat_taken (409):     199
  Declined - per_user_limit (409): 0
  Declined - idempotency (409):    0
  Server Errors (5xx):             0  (MUST BE ZERO)
  Latency (ms):                    P50: 18 ms | P95: 35 ms | P99: 42 ms
--------------------------------------------------------------------------------
  Reconciliation Invariant Check:
  [Available: 0] + [Held: 0] + [Confirmed: 1] == Total: 1
  ==> STATUS: [PASS] Invariant perfectly preserved! Zero phantom or leaked seats.
```

### Option B: Run k6 Load Test
```bash
k6 run scripts/burst_load.js
```

---

## 📊 Observability & Metrics

Prometheus scrapes metrics from `http://localhost:8080/metrics`.
Prometheus Web UI is accessible at `http://localhost:9090`.

Key Domain Metrics:
- `reservations_confirmed_total`: Counter of successfully booked reservations.
- `reservations_declined_total{reason="seat_taken"}`: Counter of seat contention conflicts.
- `reservations_declined_total{reason="per_user_limit"}`: Counter of quota breach attempts.
- `reservations_declined_total{reason="idempotent_replay"}`: Counter of duplicate / conflicting replay requests.
- `seats_available`: Dynamic gauge tracking currently available seats across the system.
