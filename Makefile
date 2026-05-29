# commerce-ops — 자주 쓰는 명령 단일 진입점
#
#   make up        인프라(DB/Redis/Kafka/관측 스택) 기동
#   make ps        컨테이너 상태
#   make logs      인프라 로그 follow
#   make demo      통합 데모 (POST /orders → trace/log/metric)
#   make down      인프라 정지 (볼륨 유지)
#   make clean     인프라 정지 + 볼륨 삭제 (옛 데이터 제거)
#   make build     전체 gradle 빌드
#   make test      전체 테스트
#   make run-order / run-payment / run-inventory   각 서비스 호스트 실행
#
# 서비스는 호스트에서 ./gradlew bootRun 으로 띄운다 — Kafka 는 localhost:9092 로 붙는다
# (infra/docker-compose.yml 의 EXTERNAL listener). 자세한 건 README "Quick Start".

COMPOSE := docker compose -f infra/docker-compose.yml
GRADLE  := ./gradlew

.DEFAULT_GOAL := help
.PHONY: help up ps logs demo down clean build test \
        run-order run-payment run-inventory urls

help: ## 이 도움말
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

up: ## 인프라 기동 (DB/Redis/Kafka/Prometheus/Grafana/Loki/Tempo/Alertmanager)
	$(COMPOSE) up -d
	@echo "→ Grafana http://localhost:3000 (admin/admin) · Prometheus :9090 · Tempo :3200"

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## 인프라 로그 follow
	$(COMPOSE) logs -f --tail=100

demo: ## 통합 데모 실행 (서비스가 떠 있어야 함)
	./scripts/integration-demo.sh

down: ## 인프라 정지 (볼륨 유지)
	$(COMPOSE) down

clean: ## 인프라 정지 + 볼륨 삭제 (다음 기동 시 깨끗한 상태)
	$(COMPOSE) down -v

build: ## 전체 gradle 빌드 (테스트 제외)
	$(GRADLE) build -x test

test: ## 전체 테스트
	$(GRADLE) test

run-order: ## order-service 호스트 실행 (:8081)
	cd services/order-service && $(GRADLE) bootRun

run-payment: ## payment-service 호스트 실행 (:8082)
	cd services/payment-service && $(GRADLE) bootRun

run-inventory: ## inventory-service 호스트 실행 (:8083)
	cd services/inventory-service && $(GRADLE) bootRun

urls: ## 주요 UI / 엔드포인트
	@echo "Grafana     http://localhost:3000  (admin/admin)"
	@echo "Prometheus  http://localhost:9090  (/alerts)"
	@echo "Tempo       http://localhost:3200"
	@echo "Alertmanager http://localhost:9093"
	@echo "order-service  :8081 · payment :8082 · inventory :8083"
