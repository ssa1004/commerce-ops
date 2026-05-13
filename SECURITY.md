# Security Policy

> OWASP API Security Top 10 (2023) 와의 매핑 / 점검 결과는 [docs/security/owasp-mapping.md](docs/security/owasp-mapping.md) 참조.

## 학습용 레포 안내

본 저장소는 **학습 / 포트폴리오 목적**의 마이크로서비스 + 옵저버빌리티 스택 데모입니다.
운영 환경 (production) 사용을 가정하지 않으며, 다음 사항을 미리 밝힙니다.

- `infra/` 아래의 docker-compose / Kubernetes manifest 에 들어 있는 비밀번호·토큰·키는
  모두 **데모용 placeholder** 입니다. 그대로 운영에 쓰지 마십시오.
- `services/*` 의 `application*.yml` 에 노출된 DB 패스워드·Kafka credentials 는 동일하게
  데모용입니다. 운영에서는 Secret Manager / Vault / Sealed Secrets 등으로 분리해야 합니다.
- 각 서비스는 인증/인가 게이트웨이가 비활성화된 상태로 동작합니다 — `/actuator/*`,
  `/api/*` 가 인증 없이 노출되니 외부 공개 환경에서 띄우지 마십시오.

## 지원 버전

`main` 브랜치만 적극적으로 유지 관리됩니다. 과거 태그/릴리스에 대한 보안 패치는
제공되지 않습니다.

| Branch | Supported |
|---|---|
| `main` | Yes |
| 기타   | No  |

## 취약점 보고

저장소에서 보안 이슈를 발견하셨다면 다음 경로로 알려 주세요.

1. **GitHub private vulnerability report** — 본 레포의 *Security → Report a vulnerability*
   탭에서 비공개로 제출할 수 있습니다 (권장).
2. 위 경로가 막혀 있다면, 저장소 owner 의 GitHub 프로필에 공개된 연락처로
   **공개 이슈를 만들기 전에** 먼저 직접 알려 주세요.

가능한 한 다음을 포함해 주시면 빠르게 검증/대응할 수 있습니다.

- 영향 받는 컴포넌트 (`services/<name>`, `modules/<name>`, `infra/<path>` 등)
- 재현 절차 또는 PoC
- 영향 범위에 대한 짐작 (예: 데이터 노출 / 코드 실행 / DoS 등)

학습용 레포 특성상 SLA 를 보장하지는 않지만, 의미 있는 제보에는 가능한 한 빠르게
회신하고, 수정 후 커밋/태그를 알려 드리겠습니다.

## 의존성 관리

- 운영 라이브러리 (`modules/*`) 는 컴파일 시점 `compileOnly` 로 사용자 앱의 표면을 그대로
  사용 — 사용자 앱이 받는 Spring Boot / Micrometer / OTel patch 가 그대로 효력을 갖습니다.
- 서비스 (`services/*`) 는 Spring Boot patch 버전을 `build.gradle.kts` 에 직접 고정
  합니다. CVE 공지가 있을 경우 patch chain 안에서만 bump 하는 것을 우선 합니다.
- GitHub Actions 의 워크플로 의존성은 메이저 태그 (`@v5`, `@v4` 등) 로 고정합니다.
  Dependabot 활성화 후에는 자동 PR 을 통해 한 메이저씩 점진적으로 sweep 합니다.
