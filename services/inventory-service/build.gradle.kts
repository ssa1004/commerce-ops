plugins {
	java
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.0.1-SNAPSHOT"
description = "Inventory service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["testcontainersVersion"] = "1.20.4"
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
	implementation("org.redisson:redisson-spring-boot-starter:3.31.0")

	implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
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
}
