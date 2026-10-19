# Curl HTTP Mock Executor Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 확정 설계에 따라 실제 curl 기반 더미 HTTP 실행·기록 라이브러리를 제공한다.

**Architecture:** 기존 http-payload-generator를 재사용하고 신규 독립 Maven 모듈에 컨텍스트, 저장소, 인자 구성, 로그 파싱, 프로세스 실행을 분리한다. stdout/stderr는 직접 파일로 기록하며 Closeable 컨텍스트가 모든 실행 자원을 소유한다.

**Tech Stack:** Java 17, Maven, Netty 4.1.139.Final, JUnit 5, curl, JDK 로컬 HTTP/HTTPS 서버. 초기 Java 21 구현 후 사용자 요청으로 기준 버전을 17로 변경했다.

**Spec:** [project.md](project.md)

## Global Constraints

- 패키지 `com.inzent.apim.utilities.http.curl`, 기존 생성기 공개 API 유지.
- 인증서 검증 기본 false, HTTP/1.1, 단일 요청, timeout 기본 30초.
- body 임계값 1,048,576바이트, stdout/stderr 항상 별도 파일.
- Closeable 정리, 입력 헤더/설정 복사, Netty 상수/API 사용.
- 사용자 지시에 따라 추가 승인 절차 없이 현재 develop에서 직접 구현. 기존 AGENTS.md 변경 보존. 커밋/push는 수행하지 않는다.

## Review Focus

- 응답 조기 종료 시 stdin broken pipe가 실제 curl 실패 원인을 가리지 않는지 검증.
- HEAD stdout 헤더가 응답 body로 잘못 기록되지 않는지 검증.
- timeout/interrupt 이후 프로세스와 파일 핸들이 남지 않는지 검증.
- header/argument 외부 변경 및 builder 재사용이 컨텍스트에 전파되지 않는지 검증.
- pre/post와 close 복합 실패에서 원래 오류/부분 기록이 유지되는지 검증.

## Task 1: 저장소와 공개 API

Files: pom.xml; BodyStore, ProcessOutputRecord, ExecuteConfigure, ExecuteContext, ExecutionRecord, StageRecord, RequestRecord, ResponseRecord, CurlExecutionException; ContextStorageTest.

Interfaces: `BodyStore.openStream()/size()/isFileBacked()`, `ExecuteContext implements Closeable`, `ExecuteConfigure.builder()`, `ExecuteContext.builder().requestHeader(CharSequence,Object).requestHeaders(HttpHeaders)`.

- [x] 임계값 경계, 조회 스트림 정리, 헤더/설정 복사와 유효성 테스트 작성.
- [x] `mvn -f curl-http-mock-executor/pom.xml test`로 미구현 실패 확인.
- [x] 독립 모듈/API/저장소 구현 후 동일 테스트 통과 확인.

## Task 2: 인자 및 기록 파싱

Files: CurlArguments, CurlLogParser; ArgumentsParserTest.

Interfaces: 컨텍스트에서 최종 argv 구성, stderr 파일을 RequestRecord/ResponseRecord로 파싱. 제한: 헤더 블록 256KiB/32개, 진단 요약 64KiB.

- [x] 추가 인자 arity/중복/충돌/마스킹, 헤더 블록/HEAD/진단 제한 테스트 작성 및 미구현 실패 확인.
- [x] Netty 기반 헤더/타입 매핑, 인자 구성/파서 구현 후 테스트 통과 확인.

## Task 3: 실제 curl 실행과 실패 수명

Files: CurlHttpMockExecutor; CurlExecutorTest, CurlHttpsTest.

Interfaces: `void handle(ExecuteContext)`; 실행 기록 확정 후 CurlExecutionException 전파.

- [x] 로컬 서버를 사용한 본문/압축/chunked/pre-post/로그/HEAD 테스트 작성 및 미구현 실패 확인.
- [x] 프로세스 리다이렉션, stdin 공급, timeout/interrupt 회수, 훅 기록 구현 후 테스트 통과 확인.
- [x] HTTP 오류/실행 파일 없음/pre-post 복합 실패/대용량/인증서 검증 테스트로 실패 경로 검증.

## Task 4: 문서 및 최종 검증

Files: README.md, project.md, 루트 project.md.

- [x] 실제 컴파일 가능한 사용 예제, 인자 지원 목록, 자원 수명, 빌드 순서를 README에 기록.
- [x] 신규 모듈 `clean verify` 및 기존 모듈 회귀 테스트 실행.
- [x] 요구사항과 자원/동시성 경계를 검토하고 발견한 문제의 재현 테스트/수정 수행.
- [x] `git diff --check`와 최종 파일/검증 내역 확인.

## 실행 기록

- Task 1–4 완료. 현재 develop 경로에 구현했으며 사용자 기존 변경사항은 보존했다.
- Ruling: 설계와 사용 예제에 있던 nullable 기록을 Optional 접근자로 표현했다. 생성/전송 전 실패에서도 빈 값으로 조회하기 위함이다.
- RED/GREEN: API 미구현 컴파일 실패 → 저장소/컨텍스트 통과, 인자/파서 미구현 실패 → 통과, 실행기 미구현 실패 → 실제 curl 통과.
- 실제 Windows verbose 줄 끝/자동 chunked 동작, 버전 probe interrupt, 독립 리뷰의 헤더 검증/빈 헤더/콜론 target 문제를 재현하고 수정했다.
- 최종 신규 모듈 30개 및 기존 모듈 147개 테스트 통과, README 예제 javac 성공. 독립 리뷰 재확인에서 추가 중요 지적 없음.
