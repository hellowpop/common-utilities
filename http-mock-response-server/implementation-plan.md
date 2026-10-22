# HTTP Mock Response Server Implementation Plan

> 실행 방식: 이 세션에서 executing-plans와 TDD를 적용해 직접 구현한다. 사용자 개발 지시 및 확인 질문 금지 규칙에 따라 추가 승인 단계 없이 진행한다.

**Goal:** Java 17에서 enum별 TCP mock 절차와 실패 격리 listener 체인을 제공한다.

**Architecture:** ServerSocket accept와 연결별 blocking worker를 분리한다. 세션이 소켓 수명을 소유하고 context는 point 실행과 제한된 I/O를 제공한다. listener 체인은 각 예외를 로깅하고 다음 원소를 호출한다.

**Tech Stack:** Java 17, Maven, JUnit Jupiter. 런타임 외부 의존성 없음.

**Spec:** [project.md](project.md)

## 공통 제약 및 판단

- 패키지 `com.inzent.apim.utilities.http.mockserver`, 독립 모듈 `http-mock-response-server`.
- 기존 develop 및 사용자 변경을 보존하며 현재 경로에서 구현한다. 커밋/push는 이번 작업 범위에 포함하지 않는다.
- 구현 파일은 책임별 분리하고 공개 API는 설계의 addMock/start/stop, enum, point, chain 계약을 유지한다.
- 기본 최대 연결 64, 종료 대기 5초, 응답 deadline 10초, 읽기 1,024 bytes/100 ms.
- listener/point에서 실행기 수명 메서드 재진입을 거부하여 자기 스레드 종료 대기 교착을 방지한다.

## 검토 집중 항목

- listener와 logger가 연속 실패해도 다음 listener 및 서버가 살아 있어야 한다(Task 1/3).
- start의 중간 bind 실패가 앞서 열린 포트를 누출하지 않아야 한다(Task 3).
- stop/accept 및 write deadline 경합에서 세션 종료 이벤트가 중복되지 않아야 한다(Task 3).
- 입력 half-close와 완전 disconnect, chunk와 TCP packet의 의미를 혼동하지 않아야 한다(Task 2/3).
- interrupt 또는 느린 사용자 callback으로 종료가 지연되면 성공을 보고하지 않아야 한다(Task 3).

## Task 1: 이벤트, 체인, 설정 및 응답 값

Files: `pom.xml`, `src/main/java/com/inzent/apim/utilities/http/mockserver/{ServiceEvent,ServiceEventType,ServiceStatusListener,ServiceStatusListenerChain,MockOptions,ServerOptions,MockResponse,NextActionException,CloseClientException,SendResponseException}.java`, `src/test/java/com/inzent/apim/utilities/http/mockserver/ContractsTest.java`.

Interfaces: listener `onEvent(ServiceEvent)`, chain `of(...)/andThen(...)`, options builder, response `utf8(int,String,String)`.

- [x] 테스트 작성: 체인 순서·예외 격리·logger 실패·불변성, null/범위/overflow 검증, response 방어 복사와 UTF-8.
- [x] 실패 확인 후 타입과 검증을 구현한다.
- [x] 모듈 전체 `mvn test` 통과 확인.

## Task 2: case와 point 및 응답 실행

Files: `MockScenario`, `MockCase`, `MockPoints`, `MockClientContext`, `ClientSession`, `ActionDispatcher`, `HttpResponseWriter` 및 통합 테스트.

Interfaces: `execute(MockClientContext) throws Exception`, `runPoint(String,PointOperation)`, `readChunk()`, `chunk()`, `delay(Duration)`.

- [x] 실제 loopback 소켓으로 대기·EOF·slow read·close delay·응답 action·일반 예외 테스트 작성 후 실패 확인.
- [x] 연결별 버퍼/point 상태, 옵션 적용, 단일 종료, 응답 HTTP 직렬화와 deadline을 구현한다.
- [x] 실행기와 함께 통합 검증한다.

## Task 3: 실행기 및 수명

Files: `MockServerExecutor`, `MockBinding`, `MockServerStartException`, `MockServerStopException`, `TerminationReason`, `src/test/java/com/inzent/apim/utilities/http/mockserver/ServerIntegrationTest.java`.

Interfaces: `addMock(enum,address,port[,options],listener)`, `start()/stop()/close()`.

- [x] bind rollback, 복수 바인딩, listener 장애 생존, 용량 거부, stop/read/delay/write 경합, 반복 수명·재진입·interrupt 테스트를 먼저 작성한다.
- [x] 전체 bind 준비 gate, 제한 worker, 세션 registry 및 종료 deadline을 구현한다.
- [x] Java 17 `clean verify`로 전체 테스트와 자원 회수를 확인한다.

## Task 4: 문서 및 최종 검토

- [x] README에 컴파일 가능한 caller 및 확장 enum 예제, 옵션, 이벤트, 제약, 빌드 명령을 작성한다.
- [x] 독립 코드 리뷰로 동시성·자원 정리·listener 계약을 검토하고 중요한 발견을 회귀 테스트로 수정한다.
- [x] 최종 clean verify, README 예제 컴파일/실행, JAR major 61, diff 검사를 수행한다.
- [x] project.md와 루트 문서에 구현/검증 결과를 기록한다.

## 실행 기록

## 자원 안전성 보완 계획 (2026-10-22)

사용자 분석결과 적용 지시에 따라 R1/R2/R3를 수정한다. 새 모듈 설계가 아닌 재현된 결함 보완이며 기존 다른 모듈의 변경은 유지한다.

- [x] ResourceSafetyTest에 worker/closer 생성 실패, 종료 단계 실패·재시도, 이벤트의 원본 제어 예외 참조 보존을 재현하고 수정 전 실패를 확인한다.
- [x] 미실행 작업을 포함한 session 소유권을 단 한 번 회수하며 accept부터 worker 인계까지 실패 경로를 보호한다.
- [x] close 제출 실패는 직접 close로 복구하고 interrupt 실패가 close를 막지 않게 한다. 종료 단계 실패를 격리하고 미완료 정리는 후속 stop으로 재시도한다.
- [x] 이벤트의 원본 Throwable를 본문 없는 ThrowableSnapshot으로 변환한다. cause/suppressed도 제한된 크기로 복사하고 action의 closeDelay/응답 메타데이터를 제공한다.
- [x] 전체 테스트, 수정된 분석 probe, 독립 리뷰 및 Java 17 clean verify를 수행하고 README/분석서를 갱신한다. 커밋/push는 요청 범위에 포함하지 않는다.

- 2026-10-22: 설계 확정 및 개발 지시. 기존 모듈을 수정하지 않는 독립 모듈이므로 새 모듈 전체 테스트를 검증 단위로 한다.
- Task 1 complete: 신규 타입 부재 실패 확인 후 contracts 7개 통과. 체인 순서·예외/로깅 실패 격리·불변성과 입력 검증을 확인했다.
- Task 2/3 complete: 실제 소켓 통합 테스트 20개 통과. 추가 enum, 응답 byte 길이, close delay, bind rollback, 수용 상한, write deadline, stop/interrupt/시작 경합을 검증했다.
- Final review: 독립 reviewer가 중요한 경합 2개를 발견했다. 두 회귀 테스트의 수정 전 실패를 확인하고 수정 후 전체 27개가 통과했다. 추가로 시작 중 interrupt의 정리 요청도 검증했다.
- 구현 구체화: stop은 시작 callback을 기다리는 수명 락 대기 대신 별도 coordinator에 취소/정리를 요청한다. 대기 제한을 지키면서 start rollback을 회수하기 위한 선택이다. 응답 전송 결과와 물리적 자원 회수를 분리하여 SENT/TIMEOUT 결과가 후속 stop으로 바뀌지 않게 했다.
- Task 4 complete: JDK 17.0.10 / Maven 3.9.11 clean verify 27개 통과, JAR의 32개 class 모두 major 61. README 전체 예제를 생성 JAR만 사용하는 classpath로 컴파일·실행했다.
- 완료 상태: develop 작업 디렉터리에 구현을 보존하며 커밋/push 및 기존 모듈 변경은 수행하지 않았다.
- 후속 사용자 push 요청: 기술문서 갱신 후 JDK 17 clean verify 27개를 재확인했다. HTTP mock response server 및 해당 루트 변경 기록만 커밋/push 대상으로 지정하고 기존 다른 모듈 변경은 유지한다.

- 2026-10-22 후속 push 요청: 자원 안전성 수정 R1/R2/R3와 관련 문서를 develop에 커밋하여 origin/develop에 반영한다. 테스트 32개와 안전성 프로브 14개를 확인했으며 다른 모듈의 미커밋 변경은 제외한다.
