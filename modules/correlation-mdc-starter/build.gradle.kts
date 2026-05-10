plugins {
	java
	`java-library`
	`maven-publish`
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.1.0-SNAPSHOT"
description = "Servlet 요청 단위로 OTel trace_id / span_id 를 SLF4J MDC 에 자동 동기화하는 starter"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
	withSourcesJar()
	withJavadocJar()
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.14")
		mavenBom("io.opentelemetry:opentelemetry-bom:1.45.0")
	}
}

dependencies {
	// 의존성 격리 — 라이브러리는 starter 표면에 직접 의존하지 않는다 (사용자 앱이 가진 표면을
	// 그대로 사용). compileOnly 로 컴파일에 필요한 최소만.
	compileOnly("org.springframework.boot:spring-boot-autoconfigure")
	compileOnly("org.springframework:spring-web")
	compileOnly("jakarta.servlet:jakarta.servlet-api")
	compileOnly("io.opentelemetry:opentelemetry-api")
	compileOnly("org.slf4j:slf4j-api")

	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-web")
	testImplementation("io.opentelemetry:opentelemetry-api")
	testImplementation("io.opentelemetry:opentelemetry-sdk")
	testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `-parameters` — Spring 의 일부 리플렉션 경로 (특히 `@ConfigurationProperties` 의 record /
// constructor binding, actuator endpoint `@Selector`) 가 클래스 파일의 MethodParameters
// attribute 를 요구한다. starter 표면에서 일관 적용 — slow-query-detector / jfr-recorder-starter
// 와 같은 정책 (ADR 부재 시 회귀를 막는 자리).
tasks.withType<JavaCompile> {
	options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

publishing {
	publications {
		create<MavenPublication>("library") {
			from(components["java"])
			pom {
				name.set("correlation-mdc-starter")
				description.set(project.description)
			}
		}
	}
}
