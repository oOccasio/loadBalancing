/**
 * Gradual Increase Scenario
 *
 * Simulates organic traffic growth (e.g. business hours ramping up).
 * Tests whether the adaptive balancer detects GRADUAL_INCREASE state
 * and switches to WEIGHTED_ROUND_ROBIN before latency degrades.
 *
 * Profile:
 *   0–20s:  low     (5 VUs)
 *   20–40s: ramp    (5→30 VUs)
 *   40–70s: medium  (30 VUs)
 *   70–90s: ramp    (30→60 VUs)
 *   90–120s: high   (60 VUs)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const latency = new Trend('request_latency', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('request_count');

export const options = {
    scenarios: {
        gradual: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '20s',  target: 5 },
                { duration: '20s',  target: 30 },
                { duration: '30s',  target: 30 },
                { duration: '20s',  target: 60 },
                { duration: '30s',  target: 60 },
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<600'],
        error_rate: ['rate<0.05'],
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
        'latency < 600ms': (r) => r.timings.duration < 600,
    });

    sleep(0.05);
}

export function handleSummary(data) {
    return {
        'benchmark/results/gradual-increase.json': JSON.stringify(data, null, 2),
    };
}
