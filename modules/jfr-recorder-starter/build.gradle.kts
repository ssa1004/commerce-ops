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

	// AWS SDK v2 — JFR chunk 의 S3/MinIO 업로드 구현이 사용. compileOnly 로만 잡아 사용자
	// 앱이 의존성을 안 들고 있으면 (= 업로드 비활성 환경) 자동설정이 클래스 부재 조건으로
	// 평가되어 NoopJfrChunkUploader 가 선택된다. SDK 자체가 무거우므로 (~수십 MB) 명시적
	// opt-in 으로 둔다.
	compileOnly("software.amazon.awssdk:s3:2.25.30")

	// JFR (jdk.jfr.*) 은 JDK 11+ 부터 표준 모듈에 포함 — 별도 의존성 불필요. Java 21 toolchain
	// 을 강제하므로 컴파일/런타임 모두 안전.

	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator")
	testImplementation("io.micrometer:micrometer-core")
	// 비동기 업로드 검증 — JfrRecorderUploadTests 에서 jfr-uploader 스레드의 결과를 polling.
	testImplementation("org.awaitility:awaitility")
	// 단위 테스트에서 S3 fake / 실제 SDK 객체 검증 위해 runtime 에 끼움.
	testImplementation("software.amazon.awssdk:s3:2.25.30")
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
