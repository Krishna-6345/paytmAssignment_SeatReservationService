import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// Custom Prometheus-aligned metrics
export const confirmedCounter = new Counter('reservations_confirmed');
export const declinedSeatTakenCounter = new Counter('reservations_declined_seat_taken');
export const declinedQuotaCounter = new Counter('reservations_declined_quota');
export const declinedIdempotencyCounter = new Counter('reservations_declined_idempotency');
export const serverErrorRate = new Rate('server_error_rate');

export const options = {
    scenarios: {
        // Scenario 1: Hot Seat Contention Storm
        hot_seat_storm: {
            executor: 'shared-iterations',
            vus: 100,
            iterations: 200,
            maxDuration: '30s',
            exec: 'hotSeatStorm',
        },
        // Scenario 2: Mixed On-Sale Load
        mixed_onsale_load: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 50 },
                { duration: '20s', target: 50 },
                { duration: '5s', target: 0 },
            ],
            startTime: '35s',
            exec: 'mixedOnSale',
        },
    },
    thresholds: {
        'server_error_rate': ['rate==0'], // Zero 5xx permitted
        'http_req_duration': ['p(95)<300'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
let stormShowId = null;
let mixedShowId = null;

export function setup() {
    // 1. Check health
    const healthRes = http.get(`${BASE_URL}/health/ready`);
    check(healthRes, { 'Service is ready': (r) => r.status === 200 });

    // 2. Create Hot Seat Show
    const hotSeatPayload = JSON.stringify({
        name: 'k6 Hot Seat Contention',
        seats: ['HOT-1'],
        price_paise: 50000,
    });
    const hotRes = http.post(`${BASE_URL}/shows`, hotSeatPayload, {
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer admin' },
    });
    const stormId = JSON.parse(hotRes.body).id;

    // 3. Create Mixed Show with 50 seats
    const seats = [];
    for (let i = 1; i <= 50; i++) {
        seats.push(`K6-SEAT-${i}`);
    }
    const mixedPayload = JSON.stringify({
        name: 'k6 Grand Mixed On-Sale',
        seats: seats,
        price_paise: 25000,
    });
    const mixRes = http.post(`${BASE_URL}/shows`, mixedPayload, {
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer admin' },
    });
    const mixId = JSON.parse(mixRes.body).id;

    return { stormShowId: stormId, mixedShowId: mixId };
}

export function hotSeatStorm(data) {
    const userId = `k6-storm-user-${__VU}-${__ITER}`;
    const key = `k6-storm-key-${__VU}-${__ITER}`;
    const payload = JSON.stringify({
        seats: ['HOT-1'],
        idempotency_key: key,
    });

    const res = http.post(`${BASE_URL}/shows/${data.stormShowId}/reserve`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${userId}`,
        },
    });

    recordResult(res);
}

export function mixedOnSale(data) {
    const userId = `k6-user-${__VU % 15}`;
    const key = `k6-key-${Math.floor(__ITER / 2)}`;
    const seatIndex = 1 + (__ITER % 50);

    const payload = JSON.stringify({
        seats: [`K6-SEAT-${seatIndex}`],
        idempotency_key: key,
    });

    const res = http.post(`${BASE_URL}/shows/${data.mixedShowId}/reserve`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${userId}`,
        },
    });

    recordResult(res);
    sleep(0.05);
}

function recordResult(res) {
    if (res.status >= 500) {
        serverErrorRate.add(1);
    } else {
        serverErrorRate.add(0);
    }

    if (res.status === 201 || res.status === 200) {
        confirmedCounter.add(1);
    } else if (res.status === 409) {
        if (res.body.includes('seat_taken')) {
            declinedSeatTakenCounter.add(1);
        } else if (res.body.includes('per_user_limit')) {
            declinedQuotaCounter.add(1);
        } else if (res.body.includes('idempotent_replay')) {
            declinedIdempotencyCounter.add(1);
        }
    }
}

export function teardown(data) {
    // Validate final reconciliation invariant
    const res = http.get(`${BASE_URL}/shows/${data.stormShowId}`);
    const body = JSON.parse(res.body);
    const counts = body.counts;
    console.log(`Reconciliation Invariant: Available(${counts.available}) + Held(${counts.held}) + Confirmed(${counts.confirmed}) == Total(${counts.total})`);
    check(counts, {
        'Reconciliation Invariant holds': (c) => c.available + c.held + c.confirmed === c.total,
    });
}
