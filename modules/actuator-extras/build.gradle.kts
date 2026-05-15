plugins {
	java
	`java-library`
	`maven-publish`
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.1.0-SNAPSHOT"
description = "Spring Boot Actuator 확장 starter — HikariCP 커넥션 풀 스냅샷 endpoint (/actuator/hikari)"

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
	}
}

dependencies {
	// 의존성 격리: 라이브러리는 Spring Boot 의 표면에 직접 의존하지 않는다 — 사용자 앱이 들고
	// 있는 표면을 그대로 사용. 컴파일에 필요한 최소 모듈만 compileOnly 로 선언
	// (slow-query-detector / jfr-recorder-starter / correlation-mdc-starter 와 같은 정책).
	compileOnly("org.springframework.boot:spring-boot-autoconfigure")
	compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
	compileOnly("org.springframework.boot:spring-boot-actuator")
	compileOnly("org.slf4j:slf4j-api")

	// HikariCP — endpoint 가 HikariDataSource / HikariPoolMXBean 을 읽는다. 사용자 앱이
	// HikariCP 를 안 쓰면 (= 다른 커넥션 풀) 자동설정의 ConditionalOnClass 가 클래스 부재로
	// 평가되어 endpoint 가 등록되지 않는다. Spring Boot 의 기본 풀이라 사실상 항상 존재하지만
	// 표면 의존은 만들지 않는다.
	compileOnly("com.zaxxer:HikariCP")

	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
	// 단위 테스트에서 실제 HikariDataSource 를 띄워 풀 스냅샷을 검증 — 인메모리 H2 로 충분.
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `-parameters` — Spring Boot Actuator endpoint (`@ReadOperation` / `@Selector`) 가 파라미터
// 이름을 리플렉션으로 읽어 path variable 에 매핑한다. 클래스 파일에 MethodParameters attribute
// 가 빠지면 사용자 앱 부팅 시 "Failed to extract parameter names for ..." 로 즉시
// BeanCreationException — actuator 가 깨지면 health check 까지 같이 막힌다. 라이브러리 모듈은
// Spring Boot Gradle 플러그인이 없어 직접 켜야 한다 (jfr-recorder-starter 와 같은 자리).
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
				name.set("actuator-extras")
				description.set(project.description)
			}
		}
	}
}
