---
date: YYYY-MM-DD
tags: [tag1, tag2]
severity: P1
duration: 30m
---

# <한 줄 제목>

## 증상
- 사용자/지표/알람에서 어떻게 보였나

## 환경
- JDK / GC / Heap / Pod 스펙 / QPS / 트래픽 패턴

## 가설 → 검증
1. (가설)
   - (검증) 어떻게 확인했나
   - (결과) 맞다 / 아니다
2. ...

## 진단 도구와 명령
- jcmd / jstat / async-profiler / Grafana 쿼리 / Trace 검색 등 *그대로 복붙 가능하게*

## 원인
- 한두 줄로 명확히

## 해결
- 적용한 옵션·코드·설정 (PR 링크)

## 배운 점
- 일반화 가능한 교훈
- runbook으로 옮길 가치가 있나? → docs/runbook/ 에 추가

## 참고
- 관련 문서, 외부 링크
