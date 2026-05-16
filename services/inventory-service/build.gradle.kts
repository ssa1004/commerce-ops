plugins {
	java
	// Phase: Java → Kotlin 100% 마이그레이션. Java 호출자 호환을 위해 @JvmStatic /
	// @get:JvmName / @JvmRecord 패턴 적용. plugin.spring 은 @Service / @Component /
	// @RestController 같은 Spring 빈 클래스를 자동으로 `open` 처리, plugin.jpa 는
	// @Entity 클래스에 noarg 생성자를 합성한다.
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.jpa") version "1.9.25"
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
	implementation("org.redisson:redisson-spring-boot-starter:4.4.0")

	implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0")

	implementation("org.springframework.kafka:spring-kafka")

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
