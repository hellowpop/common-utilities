# Hazelcast Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** Java 17 단발 CLI로 Hazelcast 관측을 JPA/DB와 YAML에 기록하고 offline HTML을 생성한다.
**Architecture:** Spring Boot는 외부 설정과 CLI context를 제공한다. 명시적 bootstrap으로 Liquibase와 JPA를 모드별 제어하고 client는 수집에서만 생성한다. 불변 Snapshot을 DB/YAML/report 사이의 계약으로 사용한다.
**Tech Stack:** Java 17, Maven, Spring Boot 4.1.2, Hazelcast 5.7.0, Hibernate/JPA, Liquibase, H2/PostgreSQL, SnakeYAML, SVG.
**Spec:** [project.md](project.md)

## Global Constraints

- 패키지 `com.inzent.apim.utilities.hazelcastmonitoring`, standalone Maven executable JAR.
- 단발 실행, 기본/report/init 모드, report는 schema 변경과 client 연결 없음.
- UTC 저장, 기간 [start,end), null/실패와 0 구분, init은 소유 객체만 삭제.
- 현재 develop에서 새 모듈만 개발한다. 사용자 진행 질문 금지에 따라 별도 worktree/계획 승인 절차 없이 진행한다. 커밋/push는 이번 요청에 포함하지 않는다.

## Review Focus

- 잘못된/중복 CLI와 offset 없는 시간: 접속 전 거부.
- init: 외부 테이블 보존, 오래된 스키마에서도 JPA 기동 전 재생성.
- DB commit 후 파일 실패: PENDING/ERROR 복구와 내용 불일치 감지.
- 보고서: null gap, 동일 이름의 다른 서비스, HTML/script 문자열 escape.
- 실패한 수집: active count null과 FAILED 저장, 모든 client/thread 회수.

## Tasks

### 1. CLI와 snapshot 계약
**Files:** pom.xml, src/main/resources/application.yml, cli/Options.java, snapshot/Snapshot.java, MonitorApplication.java, OptionsTest.java.
**Interfaces:** `Options.parse(String[], Environment)`, immutable Snapshot/Node/ObjectInfo records, `MonitorApplication.run(String[], PrintStream)` returns exit code.
- [x] 입력 검증·시간 경계 테스트 작성, 실패 확인.
- [x] 모드/외부 설정/DTO 구현, 테스트 통과 확인.

### 2. Liquibase/JPA 저장과 init
**Files:** persistence/{SummaryEntity,NodeEntity,ObjectEntity,SnapshotStore}.java, schema/SchemaManager.java, db/changelog/*.yaml, PersistenceTest.java.
**Interfaces:** `SchemaManager.update()/reset()/check()`, `SnapshotStore.save(Snapshot, Path)`, `load(UUID)`, `pending(clusterId)`, `markExport`, `report(Options)`.
- [x] 실제 H2 테스트로 이력 round-trip/범위/스키마 초기화/외부 테이블 보존 실패 확인.
- [x] Liquibase/명시적 JPA bootstrap 구현 후 테스트 통과.
- [x] PostgreSQL은 사용 가능한 로컬 테스트 런타임으로 동일 계약 검증, 불가하면 환경 제약 명시.

### 3. Hazelcast collection
**Files:** collection/ClusterCollector.java, ClusterCollectorTest.java.
**Interfaces:** `collect(Options)` owns client; `collect(HazelcastInstance, Options)` collects from provided client.
- [x] 실제 2-member cluster의 타입별 count와 unsupported/실패 테스트 작성, 실패 확인.
- [x] timeout/상태/멤버 및 객체 수집 구현, 전체 테스트 통과.

### 4. YAML export와 HTML report
**Files:** export/{AtomicFiles,YamlExporter}.java, report/HtmlReport.java, ExportReportTest.java.
**Interfaces:** `YamlExporter.export(Snapshot,Path)`, `recover(SnapshotStore,Options)`, `HtmlReport.write(List<Snapshot>,Options)`.
- [x] 실제 파일 기반 충돌/복구/기간/gap/escape 테스트 작성, 실패 확인.
- [x] serializer/atomic publication/독립 SVG 보고서 구현, 테스트 통과.

### 5. 조합 / 배포 검증 / 문서
**Files:** MonitorApplication.java, README.md, project.md, ../project.md, ApplicationTest.java.
- [x] CLI 모드 분리 및 exit code 통합 테스트 실패 확인 후 orchestration 구현.
- [x] 실제 JDK 17 `mvn clean verify`, jar help/init/report/collection smoke 실행.
- [x] 독립 최종 코드 리뷰, 중요한 지적 회귀 테스트/수정 후 verify.
- [x] 사용법과 최종 검증 결과/제약 기록. 커밋/push 없음.

## Execution ledger

- 시작: 설계 확정. Boot GA 4.1.2 및 Hazelcast Community 5.7.0 확인. 기존 모듈 변경 없음.
- Ruling: 자동 구성을 사용하지 않는 Boot configuration과 명시적 JPA factory 사용 — report/init의 선행 schema/client 부작용 제거. Spring Data repository 대신 JPA EntityManager를 직접 사용한다.

- Task 1–5: complete. JDK 17 clean verify with PostgreSQL profile: 24 tests, failures/errors/skips 0. Executable JAR smoke and Edge file rendering verified.
- Ruling: final branch stays develop with uncommitted changes per requested development scope; no integration/push menu or extra confirmation.
- Review fixes: external configuration semantic validation deferred until Boot environment; total collection deadline includes initial client connection. Both reproduced RED then GREEN; independent re-review clear.
- Packaging: use inherited Boot parent repackage execution once, removing duplicate explicit execution.
- 후속 Topic 제외 완료: 실제 Topic/ReliableTopic + 동일 이름 Map 회귀 테스트의 실패를 확인한 뒤 ITopic을 DTO 생성 전에 제외했다. JDK 17 clean verify 33개 통과, 이전 이력 유지.
