<!--
commerce-ops PR 템플릿. 빈 칸을 채우고, 해당 없는 섹션은 지운다.
CI(ci.yml) 와 CodeQL(codeql.yml) 이 자동으로 돈다 — 초록불을 확인하고 merge.
-->

## 무엇을 / 왜 (What & Why)

<!-- 이 PR 이 바꾸는 것과 그 이유를 2~3줄로. 관련 이슈가 있으면 `Closes #N`. -->

## 변경 종류 (Type)

- [ ] feat — 기능 추가
- [ ] fix — 버그 수정
- [ ] build / ci — 빌드·CI·공급망
- [ ] docs — 문서
- [ ] refactor / chore — 동작 변화 없는 정리

## 영향 범위 (Scope)

- [ ] services/* (order / payment / inventory)
- [ ] modules/* (공용 starter — sister repo 영향 주의)
- [ ] helm/* (배포 — prod 동작 변화 가능)
- [ ] infra/* (Prometheus / Alertmanager / OTel / compose)
- [ ] .github/* (CI / dependabot / 보안)

## 검증 (Verification)

<!-- 실제로 돌린 명령과 결과를 적는다. prod 동작에 영향이 있으면 특히 자세히. -->

- [ ] 영향받는 서비스/모듈 `./gradlew build` 통과
- [ ] (helm 변경 시) `helm lint` + `helm template | kubeconform` 통과
- [ ] (prometheus 변경 시) `promtool check rules` + `promtool test rules` 통과
- [ ] (Dockerfile 변경 시) `hadolint` clean
- [ ] CI / CodeQL 초록불

```text
# 여기에 검증 명령과 출력 요약
```

## 배포·롤백 메모 (Deploy / Rollback)

<!-- prod 영향이 있으면: 적용 순서, 마이그레이션, 롤백 방법. 없으면 "N/A". -->

## 체크리스트

- [ ] secret / 자격증명을 커밋하지 않았다
- [ ] 빌드 산출물(build/, *.tgz, rendered yaml)을 커밋하지 않았다
- [ ] 문서(README / docs)를 필요한 만큼 갱신했다
