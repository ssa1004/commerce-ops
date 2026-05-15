plugins {
	java
	`java-library`
	`maven-publish`
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.1.0-SNAPSHOT"
description = "Auto-detect slow JPA/JDBC queries and N+1 patterns; expose as Micrometer counters + WARN logs"

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
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
	}
}

dependencies {
	// 의존성 격리: 라이브러리는 Spring Boot starter 표면에 직접 의존하지 않는다 — 사용자 앱이
	// 들고 있는 표면을 그대로 사용. 컴파일에 필요한 최소 모듈만 compileOnly 로 선언.
	compileOnly("org.springframework.boot:spring-boot-autoconfigure")
	// NPlusOneContext 가 TransactionSynchronization / TransactionSynchronizationManager 를 참조한다
	// (트랜잭션 종료 시점에 ThreadLocal 정리). spring-tx 는 사용자 앱이 spring-boot-starter-data-jpa
	// 또는 spring-boot-starter-jdbc 를 통해 항상 들고 오는 모듈이라 compileOnly 로 충분.
	compileOnly("org.springframework:spring-tx")
	// servlet 환경에서만 의미있는 NPlusOneRequestFilter 만 등록 — 컴파일 시점엔 spring-web /
	// jakarta.servlet API 가 필요하지만 런타임은 사용자 앱 (services/*) 이 spring-web 을 가져올 때만
	// 활성화된다 (@ConditionalOnClass + @ConditionalOnWebApplication).
	compileOnly("org.springframework:spring-web")
	compileOnly("jakarta.servlet:jakarta.servlet-api")
	compileOnly("io.micrometer:micrometer-core")
	compileOnly("org.slf4j:slf4j-api")

	api("net.ttddyy:datasource-proxy:1.10.1")

	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator")
	// servlet 환경에서 ConditionalOnWebApplication 분기와 NPlusOneRequestFilter 등록을 검증.
	testImplementation("org.springframework.boot:spring-boot-starter-web")
	testImplementation("io.micrometer:micrometer-core")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `-parameters` — Spring 의 일부 리플렉션 경로 (특히 `@ConfigurationProperties` 의 record /
// constructor binding, actuator endpoint `@Selector`) 가 클래스 파일의 MethodParameters
// attribute 를 요구한다. Spring Boot Gradle 플러그인이 자동으로 켜는 옵션이지만 라이브러리
// 모듈은 직접 켜야 한다. 지금 이 모듈은 actuator endpoint 가 없어 즉각 깨지진 않지만 추후
// 추가될 때 회귀 (재발) 를 막기 위해 starter 표면에서 일관 적용.
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
				name.set("slow-query-detector")
				description.set(project.description)
			}
		}
	}
}
