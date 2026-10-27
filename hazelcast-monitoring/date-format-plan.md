# Stored Date Format Change

**Goal:** 날짜 문자열을 `yyyy-MM-dd HH:mm:ss`로 통일한다.
**Scope:** YAML key/모든 관측 날짜, HTML 날짜, 파일명 안전 표현 및 같은 형태의 기간 인수 지원. DB의 UTC timestamp/Instant 및 microsecond 정밀도는 유지하여 기간 조회/정렬에 영향을 주지 않는다.

## Decisions

- YAML의 날짜는 Asia/Seoul 기준 초 단위 문자열이며 schemaVersion 2, timeZone 정보를 기록한다. report는 기존 report-zone 설정을 유지하면서 같은 문자열 형식을 사용한다.
- 파일명은 Windows 호환 `yyyy-MM-dd_HH-mm-ss` + UUID, 일자 폴더는 YAML 시간대와 일치한다.
- 기존 ISO-8601 기간 인수는 유지한다. 새 공백 구분 시간은 Asia/Seoul로 해석하고 잘못된 날짜는 엄격히 거부한다.
- 기존 schemaVersion 1 YAML은 DB 관측 내용과 정확히 일치할 때만 재출력 성공으로 인정하며 덮어쓰지 않는다.

## Steps

- [x] 새 시간 변환/형식·기간 인수·YAML/HTML/이전 YAML 호환 테스트 작성 및 실패 확인.
- [x] 공통 날짜 formatter/parser와 exporter/report/CLI 적용.
- [x] Java 17 전체 verify 및 실제 DB timestamp 정밀도/기간 경계 유지 확인, 날짜 샘플·문서 갱신.

## Verification

- Java 17 `mvn -f hazelcast-monitoring/pom.xml clean verify`: 51개, 실패/오류/건너뜀 0, BUILD SUCCESS.
- YAML/HTML 형식, UTC 보고서 시간대, 자정 경계, 같은 초 파일 구분, 기존 YAML 동일성/충돌, 새 기간 인수 및 기존 ISO 입력을 검증했다.
- H2 JPA round-trip에서 microsecond Instant 동일성과 `[start, end)` 기간 조회를 유지한다. PostgreSQL profile은 이번 변경에서 재실행하지 않았다.
- README, project.md, 설치 application.yml 예시 및 CLI help 갱신. 로그: `target/hzmon-date-verify.log`. 커밋/push 미수행.
