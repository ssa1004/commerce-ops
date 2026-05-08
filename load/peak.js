// 피크 트래픽 시나리오 — order-service POST /orders happy path 부하.
// inventory 시드 (1001/1002/1003) 에 맞춰 productId 무작위 선택, baseline.js 와 동일한 페이로드 패턴.
// 201 (PAID) 외 409 (OUT_OF_STOCK) / 402 (PAYMENT_DECLINED) 는 의도된 비즈니스 결과이므로 실패가 아니다 — 5xx 만 실패로 카운트.
// 실행: k6 run load/peak.js
//
// /actuator/health 만 두드리는 기동 smoke 시나리오는 load/health-only.js 참고.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '5m', target: 200 },
    { duration: '10m', target: 200 },
    { duration: '5m', target: 0 },
  ],
  thresholds: {
    'http_req_failed{status:5xx}': ['rate<0.02'],
    'http_req_duration{name:create_order}': ['p(99)<800'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const PRODUCT_IDS = [1001, 1002, 1003];

function randomItem() {
  const productId = PRODUCT_IDS[Math.floor(Math.random() * PRODUCT_IDS.length)];
  const quantity = 1 + Math.floor(Math.random() * 3);
  return { productId, quantity, price: 9990 };
}

export default function () {
  const payload = JSON.stringify({
    userId: 1 + Math.floor(Math.random() * 1000),
    items: [randomItem(), randomItem()],
  });

  const res = http.post(`${BASE}/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'create_order' },
  });

  check(res, {
    'is 201/402/409': r => [201, 402, 409].includes(r.status),
  });

  sleep(0.5 + Math.random());
}
