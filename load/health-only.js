// 서비스 기동 직후 /actuator/health 만 가볍게 두드리는 smoke 시나리오.
// 비즈니스 엔드포인트는 부하 대상이 아니다 — 인프라가 떴는지 확인하는 용도.
// 정식 부하 시나리오는 baseline.js / peak.js / soak.js 참고.
//
// 실행 예: k6 run load/health-only.js
//        BASE_URL=http://staging:8081 k6 run load/health-only.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<200'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export default function () {
  const res = http.get(`${BASE}/actuator/health`);
  check(res, { 'status 200': r => r.status === 200 });
  sleep(1);
}
