# Architectural Deep-Dive & Engineering Writeup

**System:** High-Concurrency Seat Reservation Engine  
**Author:** Senior Backend Engineering Team  
**Date:** October 2026  

---

## 1. The Atomic Mechanism (Zero Double-Selling Under Concurrency)

### The Fallacy of Application-Level Checks
In naive reservation systems, developers frequently implement a "read-validate-write" workflow:
```java
// ANTI-PATTERN: Prone to double-selling under concurrency
List<Seat> seats = seatRepo.findByShowIdAndStatus(showId, "available");
if (seats.containsAll(requestedSeats)) {
    seatRepo.updateStatus(requestedSeats, "confirmed"); // Race condition!
}
```
Under heavy concurrency (e.g., 500 threads contending for a single seat), all 500 threads execute the `SELECT` query concurrently within their respective transaction isolation snapshots. Each thread reads the seat as `available`, passes the application-level validation, and subsequently attempts an `UPDATE`. The result is catastrophic double-selling or lost updates.

### The Single Conditional Atomic UPDATE
To achieve absolute correctness, we push the synchronization boundary down to the PostgreSQL database engine using a **single conditional atomic UPDATE**:

```sql
UPDATE seats
SET status = 'confirmed',
    reservation_id = :reservationId,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE show_id = :showId
  AND seat_number IN (:seats)
  AND status = 'available';
```

### PostgreSQL Engine Execution & Tuple Lock Mechanics
PostgreSQL implements Multi-Version Concurrency Control (MVCC) with row-level locks:
1. **Tuple Locking:** When Transaction $T_1$ evaluates the `UPDATE` query, it acquires an `ExclusiveLock` on the target row tuple in PostgreSQL's heap.
2. **Concurrent Contenders ($T_2, \dots, T_{500}$):** All other transactions attempting to update the same physical row block at the row-level lock, awaiting $T_1$'s completion.
3. **Commit & Re-Evaluation (EvalPlanQual):** When $T_1$ successfully commits, the row's `status` becomes `'confirmed'`. PostgreSQL releases the lock to the next queued transaction ($T_2$). Because the transaction is running at `READ COMMITTED` isolation, PostgreSQL's `EvalPlanQual` mechanism re-evaluates the updated row against the query's `WHERE` predicate:
   $$\text{Predicate: } \texttt{status} = \text{'available'}$$
   Since `status` is now `'confirmed'`, the predicate evaluates to **FALSE**.
4. **Zero Rows Affected:** $T_2$ updates 0 rows and returns an update count of `0`.
5. **Immediate Application Rollback:**
   ```java
   int updatedRows = seatRepository.reserveSeatsAtomically(showId, sortedSeats, ...);
   if (updatedRows != sortedSeats.size()) {
       reservationMetrics.incrementDeclined("seat_taken");
       throw new SeatNotAvailableException("One or more requested seats are already reserved or unavailable");
   }
   ```
   If `updatedRows < requestedSeats.size()`, Spring rolls back the transaction. The request is declined with an HTTP `409 Conflict` (`reason: seat_taken`). Physical double-selling is mathematically impossible.

---

## 2. Deadlock Avoidance in Multi-Seat Contention

### The Circular Wait Problem
When users reserve multiple seats simultaneously, concurrency introduces the classic **Dining Philosophers / Circular Wait** vulnerability:
- User Alice requests seats `[A1, A2]`. Her transaction locks `A1` and attempts to acquire `A2`.
- User Bob requests seats `[A2, A1]`. His transaction locks `A2` and attempts to acquire `A1`.
- Neither transaction can proceed; both wait on the other's lock. PostgreSQL's deadlock detector triggers after `deadlock_timeout` (default: 1s), aborting one transaction with error code `40P01` (`deadlock_detected`).

### The Solution: Monotonic Total Lock Ordering
A directed dependency graph can only deadlock if it contains a cycle. By establishing a strict **global total ordering** on all lock acquisitions, cycles become impossible:

```java
List<String> sortedSeats = request.seats().stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .distinct()
        .sorted() // Lexicographical / alphanumeric ascending order
        .toList();
```

Whether a user requests `["A2", "A1"]` or `["A1", "A2"]`, the engine canonicalizes the request to `["A1", "A2"]`. Both transactions acquire locks in identical sequence: $A_1 \to A_2$.
- Alice acquires $A_1$.
- Bob attempts to acquire $A_1$ and blocks cleanly.
- Alice acquires $A_2$, completes her reservation, and commits.
- Bob resumes, discovers $A_1$ is confirmed, and fails gracefully with `409 Conflict`.
- **Zero deadlocks occur, and zero 5xx errors are generated.**

---

## 3. Deterministic Idempotency Architecture

### The Distributed Systems Re-Submission Problem
In unreliable networks, clients may experience socket timeouts even though the server successfully processed the reservation. If the client retries, a naive system either double-charges the user or returns a confusing error ("seat already taken").

### Architecture & Storage
Idempotency is enforced using the `idempotency_records` table:
```sql
CREATE TABLE idempotency_records (
    user_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID REFERENCES reservations(id) ON DELETE CASCADE,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, idempotency_key)
);
```

### Canonical Request Hashing
To prevent payload manipulation under the same key, we compute a SHA-256 hash of the canonical request state:
$$\text{Hash} = \text{SHA-256}(\texttt{showId} + \text{":"} + \text{String.join(",", sortedSeats)})$$

### Execution Matrix
When a request arrives with `(user_id, idempotency_key)`:
1. **Cache Miss (New Request):** The reservation transaction executes normally. Before committing, the exact generated `ReservationResponse` is serialized into `response_payload` and saved alongside the hash.
2. **Cache Hit + Identical Hash (Network Retry):** The system detects an identical replay. The transaction short-circuits, increments `reservations_declined_total{reason="idempotent_replay"}`, and deserializes the original `ReservationResponse`, returning HTTP `200/201` with the exact original reservation ID.
3. **Cache Hit + Mismatched Hash (Key Collision / Mutation):** The client reused the same idempotency key for different seats or parameters. The request is rejected with HTTP `409 Conflict` (`reason: idempotent_replay`).

---

## 4. Temporary Holds vs. Direct Confirmation

### Architectural Comparison

| Dimension | Direct Confirmation (Implemented) | Temporary Holds (Two-Phase Hold $\to$ Commit) |
|---|---|---|
| **Mechanism** | `available` $\to$ `confirmed` in 1 atomic step | `available` $\to$ `held` (TTL 10m) $\to$ `confirmed` / `expired` |
| **Transactions** | Single transactional boundary | Two independent distributed transactions |
| **Inventory Vulnerability** | None; payment & booking atomic | Vulnerable to "cart hoarding" DOS attacks |
| **System Complexity** | Minimal; purely database-driven | High; requires asynchronous sweepers or Redis TTL |

### Implementing Production Holds
The current schema provisions for `HELD` seats via `SeatStatus.HELD` and the `held` count in `ShowCounts`. In a high-traffic production system requiring a 10-minute payment window:
1. **Hold Transition:**
   ```sql
   UPDATE seats
   SET status = 'held', held_by_user = :userId, hold_expires_at = NOW() + INTERVAL '10 minutes'
   WHERE show_id = :showId AND seat_number IN (:seats)
     AND (status = 'available' OR (status = 'held' AND hold_expires_at < NOW()));
   ```
2. **Hold Expiry Sweeper:** An asynchronous worker (e.g. pg_cron or Spring `@Scheduled` worker) periodically reclaims abandoned holds:
   ```sql
   UPDATE seats SET status = 'available', held_by_user = NULL, hold_expires_at = NULL
   WHERE status = 'held' AND hold_expires_at < NOW();
   ```

---

## 5. CAP Theorem & Consistency Trade-Offs

### Where This System Sits: Strict CP (Consistency & Partition Tolerance)
In ticketing systems for finite physical resources (e.g., stadium seats, airline seats), **Availability cannot be prioritized over Consistency**. Selling the same concert seat twice creates real-world operational chaos, financial liability, and customer distrust.

- **Consistency (C):** Every read from `/shows/{id}` and write to `/shows/{id}/reserve` sees the most recent state across all transactions. The reconciliation invariant $\text{available} + \text{held} + \text{confirmed} == \text{total}$ is strictly maintained at all times.
- **Partition Tolerance (P):** If network partitions occur between replicas or database nodes, transactions in the minority partition are declined rather than allowed to execute unverified writes.
- **Availability Trade-Off (A):** The readiness probe `/health/ready` deliberately **fails closed**. If database connectivity degrades, the service returns `503 Service Unavailable`, preventing clients from executing split-brain reservations.

---

## 6. The 2 AM Production Alert Runbook

### Alert 1: `ReconciliationInvariantBroken` (Severity: SEV-1 Critical)
- **Expression:** `show_seats_sum_mismatch > 0`
- **Meaning:** For at least one show, $\text{available} + \text{held} + \text{confirmed} \neq \text{total}$.
- **Root Cause:** A raw SQL script or bypass operation modified seat records without updating corresponding quotas or counters.
- **Runbook:**
  1. Inspect `/shows/{showId}` logs for reconciliation failures.
  2. Run the diagnostic audit query:
     ```sql
     SELECT show_id, count(*) FILTER (WHERE status='available') as avail,
            count(*) FILTER (WHERE status='held') as held,
            count(*) FILTER (WHERE status='confirmed') as conf,
            count(*) as total
     FROM seats GROUP BY show_id HAVING count(*) != (count(*) FILTER (WHERE status='available') + count(*) FILTER (WHERE status='held') + count(*) FILTER (WHERE status='confirmed'));
     ```
  3. Lock show rows and repair invalid status flags.

### Alert 2: `DatabaseReadinessFailsClosed` (Severity: SEV-1 Critical)
- **Expression:** `probe_success{endpoint="/health/ready"} == 0 for 1m`
- **Meaning:** Application cannot execute `SELECT 1` against PostgreSQL.
- **Runbook:**
  1. Check PostgreSQL process state and resource utilization (CPU, memory, disk space):
     `docker exec -it seat-reservation-postgres pg_isready`
  2. Verify HikariCP connection pool metrics (`hikaricp_pending_threads`, `hikaricp_active_connections`).
  3. If connections are saturated, identify long-running queries via `pg_stat_activity`.

### Alert 3: `HotSeatContentionSpike` (Severity: SEV-3 Warning)
- **Expression:** `rate(reservations_declined_total{reason="seat_taken"}[1m]) > 500`
- **Meaning:** Extreme contention storm on a single or small group of seats (e.g. blockbuster on-sale).
- **Runbook:**
  1. Check database CPU load and lock acquisition wait times.
  2. Verify that 5xx errors remain at zero (`rate(http_server_requests_seconds_count{status=~"5.."}[1m]) == 0`).
  3. Ensure application upstream rate limiting or CDN queuing (e.g. Cloudflare Waiting Room) is engaged if traffic exceeds ingress capacity.

---

## 7. AI Usage & Engineering Process Disclosure

During the engineering of this system:
1. **Scaffolding & Architecture:** AI assisted in designing the schema migration scripts, structuring Flyway migration files, and configuring Spring Boot Actuator / Micrometer Prometheus scrape endpoints.
2. **Concurrency Analysis:** Formal verification of lock ordering (monotonic seat sorting) and analysis of PostgreSQL tuple lock evaluation (`EvalPlanQual`) were derived from standard database concurrency theory.
3. **Integration Test Generation:** Testcontainers suites testing 500 concurrent threads, multi-seat overlap permutations, and quota limits were crafted and verified against real PostgreSQL 16 instances.
4. **Validation:** All code was compiled with JDK 21 (`javac -parameters -release 21`), verified with Maven Surefire, and benchmarked using the standalone Java 21 Burst Load script.
