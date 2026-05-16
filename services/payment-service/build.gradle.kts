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
description = "Payment service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		// null-safety 엄격 — JSR-305 (@Nullable 등) 어노테이션을 strict 로 해석
		freeCompilerArgs.addAll("-Xjsr305=strict")
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")

	implementation("io.micrometer:micrometer-registry-prometheus")

	implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

	implementation("org.springframework.kafka:spring-kafka")

	// Kotlin runtime + reflect (Spring 의 reflection 기반 binding / DI)
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// Kotlin data class 직렬화 (Jackson) — record 등 record-style 매핑 호환
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
