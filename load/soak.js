// 장시간 안정성 (soak) 시나리오 — POST /orders 를 baseline 보다 낮은 VU 수로 길게 돌려 누수·점진 저하 확인.
// 201 (PAID) 외 409 (OUT_OF_STOCK) / 402 (PAYMENT_DECLINED) 는 의도된 비즈니스 결과 — 5xx 만 실패로 카운트.
// 실행: k6 run load/soak.js
//
// /actuator/health 만 두드리는 기동 smoke 시나리오는 load/health-only.js 참고.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 30,
  duration: '2h',
  thresholds: {
    'http_req_failed{status:5xx}': ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(99)<700'],
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

  sleep(1);
}
