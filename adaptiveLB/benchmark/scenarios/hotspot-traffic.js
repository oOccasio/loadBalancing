/**
 * Hotspot Traffic Scenario
 *
 * Simulates session-affinity workloads where the same clients repeatedly
 * hit the same endpoints (e.g. WebSocket sessions, sticky user flows).
 * A subset of VUs hit the target with X-Forwarded-For fixed to a small
 * IP range to trigger IP-Hash clustering, while another group adds random
 * load to create imbalance — testing OVERLOADED_NODE detection.
 *
 * Profile:
 *   0–60s: 40 sticky VUs (fixed IPs) + 20 random VUs
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const latency = new Trend('request_latency', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('request_count');

export const options = {
    scenarios: {
        sticky: {
            executor: 'constant-vus',
            vus: 40,
            duration: '60s',
            env: { MODE: 'sticky' },
        },
        random: {
            executor: 'constant-vus',
            vus: 20,
            duration: '60s',
            env: { MODE: 'random' },
            startTime: '0s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<700'],
        error_rate: ['rate<0.1'],
    },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8081';

// Fixed IPs to create hotspot (all map to server-1 or server-2 under IP-Hash)
const STICKY_IPS = ['10.0.0.1', '10.0.0.2', '10.0.0.3', '10.0.0.4'];

export default function () {
    const isSticky = __ENV.MODE === 'sticky';
    const ip = isSticky
        ? STICKY_IPS[__VU % STICKY_IPS.length]
        : `192.168.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}`;

    const res = http.get(`${BASE_URL}/`, {
        headers: { 'X-Forwarded-For': ip },
    });

    latency.add(res.timings.duration);
    errorRate.add(res.status >= 400 || res.status === 0);
    requestCount.add(1);

    check(res, {
        'status 2xx': (r) => r.status >= 200 && r.status < 300,
        'latency < 700ms': (r) => r.timings.duration < 700,
    });

    sleep(isSticky ? 0.02 : 0.05);
}

export function handleSummary(data) {
    return {
        'benchmark/results/hotspot-traffic.json': JSON.stringify(data, null, 2),
    };
}
