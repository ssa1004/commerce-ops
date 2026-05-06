// 현재는 서비스 기동 확인용 soak smoke 시나리오입니다.
// Phase 2에서 POST /orders happy path 기반의 장시간 안정성 검증으로 전환합니다.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 30,
  duration: '2h',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<700'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export default function () {
  const res = http.get(`${BASE}/actuator/health`);
  check(res, { 'status 200': r => r.status === 200 });
  sleep(1);
}
