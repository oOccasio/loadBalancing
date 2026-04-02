/**
 * Stable Traffic Scenario
 *
 * Constant, moderate load to test steady-state performance.
 * Measures baseline throughput, latency distribution, and error rate
 * under uniform conditions.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const latency = new Trend('request_latency', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('request_count');

export const options = {
    scenarios: {
        stable: {
            executor: 'constant-vus',
            vus: 20,
            duration: '60s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<500'],
        error_rate: ['rate<0.05'],
    },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8081';

export default function () {
    const res = http.get(`${BASE_URL}/`);

    latency.add(res.timings.duration);
    errorRate.add(res.status >= 400 || res.status === 0);
    requestCount.add(1);

    check(res, {
        'status 2xx': (r) => r.status >= 200 && r.status < 300,
        'latency < 500ms': (r) => r.timings.duration < 500,
    });

    sleep(0.05);
}

export function handleSummary(data) {
    return {
        'benchmark/results/stable-traffic.json': JSON.stringify(data, null, 2),
    };
}
