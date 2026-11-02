# Netty HTTP Mock Server Implementation Plan

이 문서는 최초 구현 계획과 완료 기록이다. 이후 header binding 변경은 [processor-binding-plan.md](processor-binding-plan.md)의 processor 직접 등록 계약을 따른다.

> 실행: `superpowers:executing-plans`를 적용하여 현재 세션에서 순차 구현한다. 사용자의 개발 시작 요청과 확인 질문 생략 지침에 따라 실행을 계속한다.

**Goal:** Java 17 실행 JAR로 annotation 기반 HTTP mock 및 close/fake-close/delay-close를 제공한다.

**Architecture:** 부팅 시 설정과 annotation registry를 확정한다. Netty pipeline은 요청 집계 후 typed processor를 호출하고 dispatcher가 응답·종료·지연 종료를 소유한다.

**Tech Stack:** Java 17, Maven, Netty 4.1, Lombok, SnakeYAML Engine, JUnit Jupiter.

**Spec:** [project.md](project.md)

## Global Constraints

- 생성 패키지는 `com.inzent.apim.utilities` 하위, 문서는 Markdown.
- 기존 모듈과 사용자의 수정 파일을 변경하지 않는다. 자동 commit/push하지 않는다.
- pure Java 탐색/등록/설정 매핑, YAML 해석에만 추가 런타임 의존성 사용.
- fake-close는 keep-alive 응답 전송 완료 후 close; delay-close는 x-delay-amount ms 후 응답 없이 close.

## Review Focus

- malformed/duplicate delay 값은 400이고 processor 오류는 500이어야 한다(Task 3).
- 긴 delay-close가 수신 timeout에 의해 조기 종료되면 안 된다(Task 3).
- pipelined 후속 요청은 terminal 결정 뒤 실행되면 안 된다(Task 3).
- classpath 디렉터리와 shaded JAR에서 같은 action이 발견되어야 한다(Task 2/4).
- 설정 오타/중복/YAML alias/미해결 placeholder는 조용히 기본값으로 대체되면 안 된다(Task 1).

## Task 1: 설정 및 빌드

Files: `pom.xml`, `src/main/resources/com/inzent/apim/utilities/netty/http/mockserver/application.yml`, `config/{ServerConfig,ConfigLoader}.java`, `ConfigLoaderTest.java`.
Produces: `ConfigLoader.load(String[]): ServerConfig`, immutable typed 설정 및 defaults.

- [x] 설정 우선순위·형식·제한·오류 테스트를 작성하고 미구현 실패 확인.
- [x] Maven/Java 17, YAML 제한·치환·직접 override·검증 구현.
- [x] `mvn test -Dtest=ConfigLoaderTest` 통과 확인.

## Task 2: annotation registry와 기본 processor

Files: `annotation/`, `action/`, `processor/`, `response/ActionResult.java`, `discovery/`, `registry/`, `RegistryTest.java`.
Consumes: `ServerConfig.scanPackages()`.
Produces: `ActionRegistry.discover(List<String>)`, `ActionRegistry.execute(String, Channel, FullHttpRequest): ActionResult`.

- [x] 기본 action 발견, 중복·타입/생성자 오류, 실제 응답/본문 복사 테스트를 작성하고 실패 확인.
- [x] annotation별 processor와 표식 클래스, 디렉터리/JAR scanner, typed binding 구현.
- [x] registry 테스트 및 기존 설정 테스트 통과 확인.

## Task 3: 서버 및 장애 동작

Files: `server/`, `response/ResponseWriter.java`, `MockServerApplication.java`, `ServerIntegrationTest.java`, `HandlerTest.java`.
Consumes: ServerConfig, ActionRegistry, ActionResult.
Produces: `NettyHttpMockServer.start(): int`, `close()`, executable main.

- [x] 실제 socket 정상/오류/copy/close/fake-close/delay-close, HEAD, pipeline, timeout/연결 제한 테스트를 작성하고 실패 확인.
- [x] bootstrap, 요청 검증, 수신 timeout, 응답 framing, 예약 종료·취소, backpressure와 정리 구현.
- [x] 전체 `mvn test` 통과, EmbeddedChannel refCnt/실패 경로 검증.

## Task 4: 실행 JAR와 문서

Files: `README.md`, `project.md`, 실행 JAR 검증용 `src/test/.../ExecutableJarIT.java`.

- [x] `java -jar` 통합 테스트를 작성하고 package 전 실패 확인.
- [x] Shade 실행 JAR 빌드, 외부 YAML 실행·종료·정상/장애 요청 검증.
- [x] Java 17 `mvn clean verify`, 결과를 README/project.md와 이 문서에 기록.
- [x] 전체 구현 별도 코드 리뷰 후 중요한 지적은 회귀 테스트와 함께 수정.

## 실행 기록

- 2026-11-02 시작: 대상 모듈은 요구사항과 설계만 존재하여 실행 가능한 baseline 없음. JDK 17.0.10 및 Maven 3.9.11 사용 가능.
- Ruling: 현재 develop 체크아웃에서 대상 모듈만 수정 — 기존 untracked 설계/사용자 변경 보존 및 확인 질문 생략 지침에 따름.
- Pre-flight: Task 1→2/3는 불변 설정, Task 2→3은 ActionResult/registry, Task 3→4는 main과 실행 JAR 계약을 공유하며 충돌 없음.

- Task 1~4 complete: Java 17 clean verify, 32 tests, 0 failures/errors/skips. Executable JAR built and README completed.
- Ruling: YAML Engine 3.2 dependency applied to fulfill YAML support; alias disabled and event depth limited to 40.
- Review: peer-FIN admission leak and explicit-null YAML root reproduced RED, fixed GREEN; same reviewer confirmed both fixes. Startup config-key diagnostic regression also verified.
- Final changes remain uncommitted in current checkout; no branch/merge/push performed.
