// Phase 2에서 작성 예정 — 평상시 트래픽 시뮬레이션
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 50,
  duration: '5m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<500'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export default function () {
  // TODO: POST /orders happy path 작성
  const res = http.get(`${BASE}/actuator/health`);
  check(res, { 'status 200': r => r.status === 200 });
  sleep(1);
}
