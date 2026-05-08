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
		mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.14")
	}
}

dependencies {
	// 의존성 격리: 라이브러리는 Spring Boot의 starter 표면에 직접 의존하지 않는다.
	// 사용자 앱이 가져오는 spring-boot-autoconfigure / spring-jdbc / micrometer를 그대로 사용.
	compileOnly("org.springframework.boot:spring-boot-autoconfigure")
	compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
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
