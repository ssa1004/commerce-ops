import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	java
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.spring") version "2.1.0"
	kotlin("plugin.jpa") version "2.1.0"
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.0.1-SNAPSHOT"
description = "Order service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

kotlin {
	jvmToolchain(21)
}

repositories {
	mavenCentral()
}

extra["testcontainersVersion"] = "1.20.6"
extra["otelInstrumentationVersion"] = "2.10.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")

	// Kotlin 표준 + Jackson Kotlin 모듈 (record→data class 마이그레이션 후 JSON 직렬화 호환).
	implementation("org.jetbrains.kotlin:kotlin-stdlib")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// Micrometer Prometheus exporter -> /actuator/prometheus
	implementation("io.micrometer:micrometer-registry-prometheus")

	// OpenTelemetry: traces (auto-instrument Spring web, JDBC, Kafka...) + log appender (OTLP -> Loki)
	implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

	implementation("org.springframework.kafka:spring-kafka")

	// 자체 운영 라이브러리 (modules/* 를 composite build로 참조)
	implementation("io.minishop:slow-query-detector")
	implementation("io.minishop:jfr-recorder-starter")

	// Netflix concurrency-limits — adaptive concurrency limiter (Gradient2 알고리즘 — TCP Vegas
	// 로부터 영감, latency 측정 기반으로 동시 진행 중 요청 수를 자동 조절). 외부 호출에서
	// cascade (한 곳의 지연이 호출자 → 호출자 → ... 로 번져가는 도미노) 를 차단.
	// Resilience4j 의 Bulkhead 는 *고정* — 적응형이 아님. 그래서 별도 라이브러리.
	implementation("com.netflix.concurrency-limits:concurrency-limits-core:0.5.4")

	// Spring StateMachine — OrderSAGA 의 *상태 + 트리거 + 가드 + 액션* 을 명시 모델로 표현.
	// 기존의 동기 if/else SAGA 와 *병행* 동작 (initial step) 시키며 결정 일관성을 검증한다.
	// 후속 step 에서 진실의 원천을 StateMachine 으로 옮길 예정 — ADR-019 참고.
	implementation("org.springframework.statemachine:spring-statemachine-core:4.0.1")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.testcontainers:kafka")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
		mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${property("otelInstrumentationVersion")}-alpha")
	}
}

// Kotlin 컴파일 — JVM 21 target + Spring 호환 default args (null-safety annotations 일관).
tasks.withType<KotlinCompile> {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
		freeCompilerArgs.add("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// 테스트 중에는 OTel SDK 비활성화 (collector 미기동 환경에서 export 실패 로그 생략)
	environment("OTEL_SDK_DISABLED", "true")
	// test 프로파일에서 outbox poller / Kafka listener auto-startup 끔
	environment("SPRING_PROFILES_ACTIVE", "test")
}
