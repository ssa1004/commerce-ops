plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "order-service"

// 자체 운영 라이브러리(modules/)를 composite build로 직접 참조.
// mavenLocal/Central에 publish 없이도 빌드된다 — 로컬·CI 동일.
includeBuild("../../modules/slow-query-detector")
includeBuild("../../modules/jfr-recorder-starter")
