plugins {
	java
	`java-library`
	`maven-publish`
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.minishop"
version = "0.1.0-SNAPSHOT"
description = "JFR (Java Flight Recorder) always-on continuous profiling starter — rolling chunks + actuator dump endpoint"

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
	// 있는 표면을 그대로 사용. 컴파일에 필요한 최소 모듈만 compileOnly 로 선언.
	compileOnly("org.springframework.boot:spring-boot-autoconfigure")
	compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
	compileOnly("org.springframework.boot:spring-boot-actuator")
	compileOnly("io.micrometer:micrometer-core")
	compileOnly("org.slf4j:slf4j-api")

	// JFR (jdk.jfr.*) 은 JDK 11+ 부터 표준 모듈에 포함 — 별도 의존성 불필요. Java 21 toolchain
	// 을 강제하므로 컴파일/런타임 모두 안전.

	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator")
	testImplementation("io.micrometer:micrometer-core")
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
				name.set("jfr-recorder-starter")
				description.set(project.description)
			}
		}
	}
}
