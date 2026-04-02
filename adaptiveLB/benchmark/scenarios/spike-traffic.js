/**
 * Spike Traffic Scenario
 *
 * Simulates a sudden surge of traffic (e.g. marketing campaign, viral event).
 * Tests how quickly the adaptive load balancer detects and reacts to the spike
 * by switching to LEAST_CONNECTIONS.
 *
 * Profile:
 *   0–10s:  warm-up  (10 VUs)
 *   10–20s: spike    (100 VUs) ← sudden 10x increase
 *   20–40s: peak     (100 VUs)
 *   40–60s: cool-down (10 VUs)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const latency = new Trend('request_latency', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('request_count');

export const options = {
    scenarios: {
        spike: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 10 },   // warm-up
                { duration: '5s',  target: 100 },  // spike ramp
                { duration: '25s', target: 100 },  // sustained peak
                { duration: '20s', target: 10 },   // cool-down
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<800'],
        error_rate: ['rate<0.1'],
    },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8081';

export default function () {
    const res = http.get(`${BASE_URL}/bench`);

    latency.add(res.timings.duration);
    errorRate.add(res.status >= 400 || res.status === 0);
    requestCount.add(1);

    check(res, {
        'status 2xx': (r) => r.status >= 200 && r.status < 300,
        'latency < 800ms': (r) => r.timings.duration < 800,
    });

    sleep(0.01);
}

export function handleSummary(data) {
    return {
        'benchmark/results/spike-traffic.json': JSON.stringify(data, null, 2),
    };
}
