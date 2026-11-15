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

### Offline report, path copy and YAML interchange (2026-11-15)

Design: retain the existing offline report and scenario editor. Render status/error category counts as inline SVG line charts (category axis, not a time series), with values and empty-state text. Render latency, path statistics (joined to snapshot names) and worker timing as escaped tables. Do not include scripts, bodies or headers in reports.

Path copy opens an isolated new-record draft of the current form, changes ID and name, and requires Add before insertion; cancel preserves the source. YAML export includes the full current configuration and strips scenario identity/version. Import accepts one safe YAML mapping, rejects duplicate keys/custom tags/aliases/oversized input, uses existing strict model validation and returns a detached scenario draft without persisting. New-scenario UI accepts a file or pasted YAML. Existing JSON editing remains available. Use already resolved SnakeYAML as an explicit dependency, with SafeConstructor and bounded LoaderOptions; no browser dependency or CDN.

- [x] Report: add rendering regressions (charts, tables, escaping, empty/one category), watch fail, implement HtmlReport helpers and run focused tests.
- [x] YAML: API round-trip and malformed/unsafe/fractional regressions first; implement ScenarioYaml and export/import endpoints, validate without persisting.
- [x] UI: path copy isolation tests first; add copy action, YAML download and new-scenario import layer; verify browser flows and full Java/Node suites.
- [x] Update README/project documents, package executable/sources/Markdown in isolated target-report-yaml and inspect delivery.

Verification: Java 54 tests and Node 19 tests pass. Independent review found copy-at-100 state corruption and JavaScript rounding of 64-bit seed; regressions reproduced both, then state ordering and string serialization of Scenario.seed fixed them. Browser verified invalid duplicate-key YAML rejection, paste/file import, copy cancel/add with source preserved, download/file round-trip into a distinct saved scenario, and exact Long.MAX_VALUE seed in the saved configuration. A 2-worker/3-loop/3-path run produced 18 completed (12 HTTP 200, 6 intentionally HTTP 404), and its downloaded report showed two charts and the three required tables. A smoke JVM held the packaged JAR during an intermediate build and blocked repackaging; stop the task-owned JVM before final verify/package. The user's default app remains running. No commit/push.

### Duration execution and per-path request pacing (2026-11-15)

Contract:
- Scenario `executionMode`: `LOOPS` (backward-compatible default) or `DURATION`; `durationSeconds`: positive integer, default 60. Only the selected limit is validated/used. DURATION is shorter than runTimeoutMillis and rampUpSeconds is shorter than durationSeconds.
- Duration starts after scenario.pre and includes ramp-up and request delays. No new request after expiry; current requests finish under request/run timeout. The existing runTimeoutMillis remains a fatal safety deadline. Duration mode has unknown planned/remaining request counts (`null` in API) and time-based progress. The 100000 request safety limit still applies and stops a duration run with FAILED/REQUEST_LIMIT if reached early.
- Each path has `delay:{mode:'NONE'|'FIXED'|'RANDOM',fixedMillis:0,minMillis:0,maxMillis:0}` (all defaults). Active bounds are integers 0..600000; RANDOM inclusive min<=max.
- A worker waits before a subsequent request to the same path, measured from previous request completion (post/cleanup included), including loop boundaries. First request is immediate, no delay after the final request, other-path execution counts toward the interval. Random intervals are selected once per gap using per-worker/per-path seeded RNG. Wait is interruptible, outside request timeout and inside duration budget.
- UI toggles loopCount/durationSeconds and exposes delay mode/values in the shared path layer/grid. JSON and existing missing-field configurations remain compatible. Reports/history/progress show duration and unknown request totals honestly.

- [x] Backend: tests first for mode validation, actual duration completion/cancel and deterministic pacing boundaries; implement model/validator/engine/counters.
- [x] Frontend: mode toggles, duration summary, path delay fields/validation/JSON round-trip and Node regressions (independent owned files).
- [x] Integrate Java/Node suites, review runtime races, browser verify, update Markdown and package isolated JAR.

Java verify 50 tests and frontend 16 tests passed. Review identified fractional integer coercion; API regressions reproduced it and strict integer deserialization fixed it. Browser verified mutually exclusive limits with retained values, delay range validation and persistence, and a 3-second duration run with 2 workers: 31 successes, 0 failures, normal COMPLETED. Executable/sources JARs are delivered under target-duration without replacing the user's running default JAR. No commit/push performed.

### Path grid / shared editor layer (2026-11-15)

- [x] Add path grid and bottom Add button; rows open the same form as new records.
- [x] Implement isolated drafts, payload arrays, repeated headers and four path/request hooks; preserve ids, order and duplicate payload entries.
- [x] Synchronize grid, scenario JSON and planned counts on apply; cancel/Esc preserve original.
- [x] Verify model tests, browser add/view/edit/cancel/JSON round-trip and Java suite; update README and package JARs.

Java verify 36 tests and frontend 9 tests passed. Browser verified add/edit, cancel/Esc, malformed numeric rejection, repeated headers/hooks preservation, stable id, JSON round-trip and persistence after reload. Main input events now synchronize quick fields before save. The task-owned smoke JVM was isolated from the user's running app; deliverable is under target-path-grid.

- 2026-11-12: 계획 작성. 기존 모듈은 테스트 baseline 대상이 아니며 신규 모듈의 red/green부터 진행한다.
- 실행 중 스크립트와 Netty callback의 별도 소유권을 유지한다. 타 작업과 소스 디렉터리를 분리하고 최종 통합 검증은 한 프로세스에서 수행한다.
- Ruling: JDBC + Liquibase와 정적 UI로 구현한다. ORM/template 의존 없이 실행 snapshot/결과의 명확한 소유권과 단독 배포를 유지한다.
- Ruling: 연결별 close, text body context와 실제 시간 통계의 차이는 project.md 구현 현황 및 README에 명시한다. command.md의 요구 기능은 유지한다.
- 2026-11-12: 실제 executable JAR에서 UI 저장→실행→2 worker HTTP 200 성공→HTML 보고서 다운로드를 확인했다. screenshot은 target/ui-smoke.png(빌드 산출물)로 기록했다.
- 독립 리뷰의 terminal/cancel, deadline callback, LOST_RESULTS 상태, journal tail/UTF-8 및 부분 파일 삭제 quota 문제를 수정하고 회귀 검증했다.
- 최종 JDK 17 clean verify: 36 tests, failures/errors/skipped 0. install은 Tests are skipped와 executable/sources 설치를 확인했다. 5개 루트 Markdown의 JAR 포함과 JS syntax를 확인했다.
