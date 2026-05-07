// 평상시 트래픽: order-service의 POST /orders로 happy path 부하를 만든다.
// inventory의 시드(1001/1002/1003)에 맞춰 productId를 무작위 선택.
// 실행: k6 run load/baseline.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 50,
  duration: '5m',
  thresholds: {
    // PAID(201) 외에 OUT_OF_STOCK(409), PAYMENT_DECLINED(402)는 의도된 결과 코드라 실패로 보지 않는다.
    // 실패는 5xx만 카운트.
    'http_req_failed{status:5xx}': ['rate<0.01'],
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
