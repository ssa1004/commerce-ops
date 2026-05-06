# Roadmap

진행 상황을 체크박스로 관리합니다. 완료 시 `[x]`, 진행 중 `[~]`.

---

## Phase 0 — Scaffold ✅

- [x] 디렉토리 구조 확정
- [x] README / ARCHITECTURE / ROADMAP 작성
- [x] 모듈·서비스 placeholder
- [x] Prometheus 자체 상태를 보는 Grafana infra overview dashboard
- [x] CI workflow 골격
- [x] GitHub 레포 생성 + 첫 푸시

---

## Phase 1 — MVP (목표: 3~4주)

> "Compose 한 줄로 서비스가 뜨고 Grafana에 메트릭이 찍힌다"

- [ ] `order-service` Spring Boot 3 프로젝트 생성 (Gradle, Java 21)
- [ ] `payment-service` 동일
- [ ] `inventory-service` 동일
- [ ] 각 서비스에 Postgres 연결 + Flyway 마이그레이션
- [ ] `POST /orders` happy-path 동작 (Kafka 없이 동기 호출만)
- [ ] Micrometer + Prometheus exporter
- [ ] `infra/docker-compose.yml`로 services + postgres + prometheus + grafana 한 번에 기동
- [ ] Grafana 기본 JVM 대시보드 자동 프로비저닝
- [ ] README Quick Start 검증

---

## Phase 2 — Observability 완성 (3~4주)

> "장애가 나면 어디가 아픈지 한 화면에서 보인다"

- [ ] Kafka 도입 → 서비스 간 비동기 이벤트로 전환
- [ ] OpenTelemetry Java agent 적용 (auto-instrumentation)
- [ ] Tempo 연동 + Grafana에서 trace 조회
- [ ] Loki + Promtail (또는 OTel logs) 적용 + 구조화 로그
- [ ] Trace ID ↔ Log 상관관계 동작 확인
- [ ] 의미 있는 알람 5개 정의 (p99 latency, error rate, Hikari saturation, GC pause, Kafka consumer lag)
- [ ] k6 baseline 시나리오 1개 (`load/baseline.js`)
- [ ] 첫 트레이스 분석 케이스 스터디 1개

---

## Phase 3 — 자체 모듈 적용 (3~4주)

> "내가 만든 라이브러리가 운영을 더 편하게 한다"

- [ ] `modules/slow-query-detector` 구현 + publish (Maven local)
- [ ] 서비스에 적용 → 의도적으로 N+1 발생시켜 감지 데모
- [ ] `modules/correlation-mdc-starter` 구현 + 적용
- [ ] `modules/actuator-extras` 구현 + 커스텀 엔드포인트 노출
- [ ] 각 모듈 README에 사용법 + 설계 의도 정리

---

## Phase 4 — Chaos & Case Studies (지속)

> "문제 상황을 만들어보고, 진단하고, 회고로 남긴다"

- [ ] `chaos/` 시나리오 3개 이상 (네트워크 지연, DB 고갈, Pod kill)
- [ ] 케이스 스터디 매월 1~2개 (`case-studies/`)
- [ ] Kind/K3d로 K8s 환경 옵션 추가
- [ ] HPA·PDB·Probe 설계 사례
- [ ] (선택) ArgoCD GitOps 연동

---

## Backlog (우선순위 미정)

- GraalVM Native Image 빌드 옵션
- JIB로 컨테이너 이미지 빌드
- WebFlux 서비스 1개 추가 (vs MVC 비교)
- Virtual Threads on/off 비교 벤치 + 결과 글
- ArgoCD + Argo Rollouts (canary)
- Chaos Mesh 연동
