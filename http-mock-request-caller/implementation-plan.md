# HTTP Mock Request Caller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans; independent transport and script/logging domains use superpowers:dispatching-parallel-agents. Steps use checkbox syntax for tracking.

**Goal:** command.md의 시나리오 기반 HTTP caller를 단독 실행 웹 애플리케이션으로 제공한다.
**Architecture:** Spring MVC UI/API와 영속 실행 이력, worker coordinator를 연결한다. 전송은 Netty와 기존 payload 생성기, 스크립트는 제한된 JEXL을 사용한다.
**Tech Stack:** Java 17, Spring Boot 4.1.2, Maven, Netty 4.1.139.Final, JEXL 3.7.1, H2, Liquibase.
**Spec:** [project.md](project.md), [command.md](command.md).

## Global Constraints

- 패키지는 `com.inzent.apim.utilities.http.caller` 하위, Java release 17.
- package는 executable/source JAR 및 루트 Markdown을 포함한다. install은 테스트 생략, verify는 명시 활성화.
- 현재 develop 경로 유지라는 저장소 지침을 우선한다. 사용자 command.md 변경은 보존한다.
- 이번 요청은 구현이며 commit/push는 요청되지 않았으므로 수행하지 않는다.
- 각 독립 작업자는 담당 디렉터리만 수정한다. Maven target 경합을 피하도록 각자 별도 build.directory를 사용한다.

## Review Focus

- 본문 생성 중/전송 중 취소 시 스트림·파일·채널 회수: transport tests.
- pre/post 실패와 scope 정리 시 중복 요청/누락 카운터: engine tests.
- 재시작·DB 저장 실패와 복구 이력: persistence tests.
- JEXL class/new/loop/반사 차단, 본문 크기 제한: script tests.
- 편집 중 실행 snapshot 불변과 HTML/XSS/CSRF: API tests.

### Task 1: Build and definition contracts

Files: pom.xml, config/CallerProperties.java, scenario/Scenario.java, scenario/ScenarioValidator.java, scenario/PayloadMatrix.java.
Interfaces: Scenario public JSON model; validator.validate(Scenario) -> long planned; PayloadMatrix.at(long) -> payload.PayloadSpec.
- [x] Write validation tests for prefix URL, complete Cartesian product, invalid sizes/overflow and watch fail.
- [x] Implement models, validation and mixed-radix permutation.
- [x] Run ScenarioTest with JDK 17.

### Task 2: Streaming transport (independent worker)

Files: transport/*, payload/PayloadSpec.java, payload/PayloadAdapter.java, transport tests.
Interfaces: see implementation-contract.md; closeable BodyStore and TransportResponse, NettyTransport.execute(TransportRequest, Duration) synchronous interruptible call; PayloadAdapter.prepare(PayloadSpec, Path).
- [x] Write real local server tests for framing/encoding and cancellation before implementation.
- [x] Implement Netty streaming, BodyStore and bounded I/O; verify focused tests.

### Task 3: Scripts and rolling logs (independent worker)

Files: script/*, logging/* and focused tests.
Interfaces: ScriptService.validate(String), execute(String, Map<String,Object>, Duration); RunLogManager.open(Path,boolean,long,int,boolean), log(String,String), close().
- [x] Write JEXL sandbox, facade mutation, timeout and gzip rollover tests first.
- [x] Implement restricted engine and log lifecycle; verify focused tests.

### Task 4: Durable scenario/run store

Files: persistence/*, resources/db/changelog/*.
Interfaces: Store JSON definitions/snapshots and individual results; optimistic version update; run mutation/search, recovery and artifact ownership.
- [x] Write H2 CRUD/version, snapshot and interrupted restart tests; watch fail.
- [x] Implement schema migration, transactional storage and bounded recovery journal.
- [x] Run persistence tests.

### Task 5: Coordinator and worker execution

Files: execution/* and engine integration tests.
Interfaces: RunService.start(id,key), cancel(runId), progress(runId), worker scoped hooks and results.
- [x] Write local HTTP integration tests for Cartesian counts, all hooks, ramp-up/cancel and isolation; watch fail.
- [x] Implement admission, snapshot, bounded result writer, body retention, worker lifecycle, cleanup.
- [x] Run engine/transport/script suites together.

### Task 6: Web UI, API, report and operational delivery

Files: web/*, resources/static/*, README.md, build script.
Interfaces: /api/scenarios CRUD; /api/runs search/progress/requests/cancel/events/report/artifacts/logs; same-origin CSRF token API.
- [x] Write REST integration checks for CRUD/start/search/report/body ownership and security; watch fail.
- [x] Implement UI, offline escaped/masked HTML, SSE snapshots and bounded log tail.
- [x] Run clean verify, JAR/source/docs inspections and install test skipping; actual executable JAR smoke test.
- [x] Update README/project.md with actual behavior, verification and limits; independent final review and fixes.

## Progress / decisions

- 2026-11-12: 계획 작성. 기존 모듈은 테스트 baseline 대상이 아니며 신규 모듈의 red/green부터 진행한다.
- 실행 중 스크립트와 Netty callback의 별도 소유권을 유지한다. 타 작업과 소스 디렉터리를 분리하고 최종 통합 검증은 한 프로세스에서 수행한다.
- Ruling: JDBC + Liquibase와 정적 UI로 구현한다. ORM/template 의존 없이 실행 snapshot/결과의 명확한 소유권과 단독 배포를 유지한다.
- Ruling: 연결별 close, text body context와 실제 시간 통계의 차이는 project.md 구현 현황 및 README에 명시한다. command.md의 요구 기능은 유지한다.
- 2026-11-12: 실제 executable JAR에서 UI 저장→실행→2 worker HTTP 200 성공→HTML 보고서 다운로드를 확인했다. screenshot은 target/ui-smoke.png(빌드 산출물)로 기록했다.
- 독립 리뷰의 terminal/cancel, deadline callback, LOST_RESULTS 상태, journal tail/UTF-8 및 부분 파일 삭제 quota 문제를 수정하고 회귀 검증했다.
- 최종 JDK 17 clean verify: 36 tests, failures/errors/skipped 0. install은 Tests are skipped와 executable/sources 설치를 확인했다. 5개 루트 Markdown의 JAR 포함과 JS syntax를 확인했다.
