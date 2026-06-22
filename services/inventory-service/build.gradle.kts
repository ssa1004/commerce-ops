plugins {
	java
	// Phase: Java → Kotlin 100% 마이그레이션. Java 호출자 호환을 위해 @JvmStatic /
	// @get:JvmName / @JvmRecord 패턴 적용. plugin.spring 은 @Service / @Component /
	// @RestController 같은 Spring 빈 클래스를 자동으로 `open` 처리, plugin.jpa 는
	// @Entity 클래스에 noarg 생성자를 합성한다.
	kotlin("jvm") version "2.4.0"
	kotlin("plugin.spring") version "2.4.0"
	kotlin("plugin.jpa") version "2.4.0"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	// OpenAPI spec build-time export — generateOpenApiDocs 가 앱을 부팅한 뒤
	// /v3/api-docs 를 fetch 해 docs/openapi/inventory-service.yaml 로 떨어뜨린다.
	id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
	// Kover — Kotlin code coverage. ./gradlew koverXmlReport / koverHtmlReport.
	id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

group = "io.minishop"
version = "0.0.1-SNAPSHOT"
description = "Inventory service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		// 인터페이스 default 메서드를 Java 측에 그대로 노출 (-Xjvm-default=all).
		freeCompilerArgs.add("-Xjvm-default=all")
	}
}

repositories {
	mavenCentral()
}

extra["testcontainersVersion"] = "1.20.6"
extra["otelInstrumentationVersion"] = "2.10.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")

	implementation("io.micrometer:micrometer-registry-prometheus")

	// Redisson for distributed locks (auto-configures from spring.data.redis.*)
	implementation("org.redisson:redisson-spring-boot-starter:4.6.1")

	implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

	implementation("org.springframework.kafka:spring-kafka")

	// OpenAPI / Swagger UI — REST API 를 OpenAPI 3 spec 으로 노출. Spring Boot 3.5 호환 2.8.x.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

	// Kotlin support — Jackson Kotlin 모듈은 data class 의 nullable / default 값을 정확히
	// 역직렬화하기 위해 필요. reflect 는 Spring 의 인자 이름 기반 바인딩에 사용.
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
		mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${property("otelInstrumentationVersion")}-alpha")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	environment("OTEL_SDK_DISABLED", "true")
	environment("SPRING_PROFILES_ACTIVE", "test")
}

// OpenAPI spec export 설정 — ./gradlew generateOpenApiDocs.
// 플러그인이 bootRun 으로 앱을 띄우고 apiDocsUrl 을 fetch 해 outputFileName 으로 저장한다.
// outputDir 은 repo 루트 docs/openapi (각 서비스가 services/<name>/ 하위라 ../../docs).
// 앱 부팅에 Postgres / Redis / Kafka 가 필요하므로 로컬 단독 실행보다는 CI 에서
// docker compose 와 함께 돌리는 것을 권장 (../../docs/openapi/README.md 참고).
openApi {
	apiDocsUrl.set("http://localhost:8083/v3/api-docs.yaml")
	outputDir.set(layout.projectDirectory.dir("../../docs/openapi"))
	outputFileName.set("inventory-service.yaml")
	waitTimeInSeconds.set(120)
}

// Kover coverage — Spring bootstrap / DTO / 설정 클래스는 분모에서 제외.
kover {
	reports {
		filters {
			excludes {
				classes(
					"io.minishop.inventory.InventoryServiceApplication*",
					"io.minishop.inventory.InventoryServiceApplicationKt",
					"io.minishop.inventory.*.dto.*",
					"io.minishop.inventory.config.*",
				)
			}
		}
	}
}
