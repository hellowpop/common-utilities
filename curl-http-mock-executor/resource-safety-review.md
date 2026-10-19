# Curl HTTP Mock Executor 메모리 누수 / NPE 분석 및 보완

분석일: 2026-10-19. 대상은 `com.inzent.apim.utilities.http.curl`의 공개 API와 내부 저장소·스트림·프로세스·훅 실패 경로다. 기존 HTTP Payload Generator의 공개 계약을 유지하고 사용자가 추가한 `src/test/java/mains/CallPatterns.java`는 변경하지 않는다.

## 분석 방법

- 컨텍스트 → 프로세스/작업/기록/BodyStore/조회 스트림의 소유권과 강한 참조 경로를 점검했다.
- 정상·검증 실패·pre/post 실패·timeout·interrupt·스레드 시작 실패·복합 close 실패를 구분했다.
- 재현 테스트를 먼저 실행해 실패를 확인한 뒤 수정했다. 드문 작업 시작/정리 실패는 ThreadFactory 및 제어 가능한 Process/InputStream으로 재현한다.
- 메모리 참조 해제는 소유 필드와 자원 목록을 직접 검사한다. GC 타이밍에 의존하는 WeakReference 테스트나 heap 크기 추정은 사용하지 않는다.
- 실제 Windows curl/JDK 로컬 HTTP/HTTPS 통합 테스트를 함께 수행한다. 장기 부하/heap dump/다른 OS의 런타임 검증은 수행하지 않는다.

## 확인한 문제와 보완

| 항목 | 재현 경로 및 영향 | 보완 |
|---|---|---|
| R-01 / 중요 | curl 프로세스 시작 후 stdin 작업 스레드 생성/start가 실패하면 기존 정리 try/finally에 진입하지 못한다. post 훅에서 프로세스가 아직 살아 있고 파일을 점유하는 것을 확인했다. | 프로세스를 시작한 직후부터 전체 작업 준비를 정리 범위로 감싼다. 스레드 시작 Error도 프로세스 회수 후 post에 전달한다. |
| R-02 / 중요 | 조회 스트림 또는 컨텍스트 자원의 close가 RuntimeException/Error를 던지면 IOException만 잡던 루프가 중단되어 나머지 파일/스트림이 남는다. | Cleanup이 최초 Throwable을 유지하고 후속 오류를 suppressed로 병합한다. 나머지 정리를 계속하고 실패한 대상은 다음 close에서 재시도한다. |
| R-03 / 중요 | 작업 스레드 interrupt 자체가 실패하면 이후 파일 삭제가 실행되지 않는다. | interrupt와 join을 같은 정리 경계에서 보호하고 다른 자원 정리를 계속한다. |
| N-01 / 보통 | 조회 스트림 close가 내부 InputStream 참조를 null로 해제하지만 상속된 reset/mark/markSupported는 이를 직접 사용해 NPE를 발생시킨다. | 열린 상태에서 기능을 유지하고 닫힌 상태에서는 reset은 IOException, mark는 무동작, markSupported는 false로 통일한다. |
| N-02 / 경계 검증 | URL을 설정하지 않고 build 후 handle하면 VALIDATE에서 내부 NPE가 기록된다. | 필수 URL 누락은 IllegalArgumentException으로 명시하고 curl/임시파일 생성 전에 기록한다. 명시적인 url(null)은 기존 공개 setter의 fail-fast NPE 계약을 유지한다. |
| N-03 / 경계 검증 | 공개 HeaderBlock 생성자가 null status를 허용하여 이후 소비자가 NPE를 겪을 수 있다. | 생성자에서 status/headers null을 즉시 거부한다. |
| N-04 / 상태 보존 | BodyStore의 null 입력이 이미 캡처된 상태를 먼저 설정해 뒤이은 정상 입력도 거부한다. | 입력을 먼저 검증하여 잘못된 입력이 저장소 상태를 바꾸지 않는다. |

## 참조 수명 및 정리 계약

- 컨텍스트 finish는 pre/post Runnable과 실행 소유 Thread 참조를 해제한다. 완료한 stdin 작업 참조도 해제한다.
- close는 프로세스 회수, 스트림·조회 스트림 정리, 메모리 본문 참조 해제, 소유 파일 삭제를 수행한다. 공유 stdout을 참조하는 응답 BodyStore부터 역순으로 정리한다.
- 오류가 발생한 정리 대상은 재시도를 위해 추적을 유지한다. 성공한 정리 대상은 목록에서 제거한다. 프로세스 스트림 정리 오류도 컨텍스트가 회수하므로 다른 기록 파일 정리를 가로막지 않는다.
- BodyStore/ProcessOutputRecord의 openStream과 close를 직렬화하여 close 중 해제된 메모리로 ByteArrayInputStream을 생성하는 경합을 막는다. 추적된 스트림의 읽기/mark/reset/close도 같은 관리 락에서 처리하여 null 참조 경합을 막는다.
- BodyStore close는 reader 정리가 실패하더라도 메모리 배열 참조 해제와 파일 삭제를 시도한다. 성공적으로 닫힌 조회 스트림은 원본 스트림 참조를 해제한다.
- 버전 캐시는 최대 32개이며 컨텍스트/프로세스/요청 본문을 저장하지 않는다. stdout/stderr와 큰 본문은 파일에 보관하므로 전체 크기의 byte 배열을 기록에 쌓지 않는다.
- API의 명시적 null 인자는 경계에서 거부한다. 실행 전 미생성 body/stdout/stderr/status는 Optional로 표현한다. 정상적인 미수신 응답을 내부 NPE로 처리하지 않는다.

## 남는 조건과 검증의 한계

1. 호출자는 컨텍스트를 close해야 한다. 완료 기록을 close 없이 계속 보관하면 본문과 임시파일이 유지되는 것은 명시적 수명 계약이다.
2. close 후에도 요청/응답 헤더, 단계별 원본 예외, 인자 및 파일 경로 등 메타데이터는 보존한다. 사용자 예외 객체가 자체 필드로 큰 payload를 보관하면 그 참조는 오류 기록 계약에 따라 남는다.
3. Caller가 Builder 자체에 보관한 훅·설정이나 Path에서 직접 연 파일 스트림은 호출자 소유다. 외부 스트림/OS 파일 잠금/권한 문제로 삭제가 실패하면 오류를 알리고 재시도할 수 있다.
4. 끝나지 않는 사용자 pre/post 훅은 responseTimeout의 대상이 아니다. JVM 강제 종료와 회복 불가능한 OS I/O 정지는 Java 정리 로직만으로 자원 반환을 보장할 수 없다.
5. 스레드 시작/정리 Error 테스트는 제어 가능한 오류 주입이다. 실제 JVM OOM을 유발하거나 프로세스 전체를 불안정하게 만드는 방식은 사용하지 않는다.
6. 기존 생성기의 압축 임시파일은 생성 callback 수명에 따라 별도로 정리한다. 이번 변경에서 그 모듈의 구현은 수정하지 않는다.

## 회귀 테스트

`ResourceSafetyTest`는 닫힌 reader mark/reset, 연속 reader 정리 실패, null 입력과 필수 URL, 메모리/자원 목록 참조 해제, 스레드 start 실패 시 curl 회수, 프로세스 스트림 Error, interrupt 실패 및 정리 재시도를 검증한다. 기존 테스트는 실제 curl timeout/interrupt/부분 응답/대용량 본문/헤더 복사/HTTPS 검증을 계속 확인한다.

최종 실행 결과는 [project.md](project.md)의 안전성 보완 검증 기록에 정리한다.

## Java 17 전환 후 재분석 및 추가 보완 (2026-10-19)

Java 17.0.10에서 기존 null 경계·닫힌 스트림·실행 실패 검증을 재실행하고, 다음 실패 경로를 추가로 재현했다.

| 항목 | 재현 결과 | 보완 |
|---|---|---|
| J17-01 / 작업 참조 잔존 | 호출 스레드가 이미 interrupt된 상태에서 close하면 join이 즉시 중단되어 회수 가능한 stdin 작업도 참조가 남는다. | 최대 2초 정리 구간에서 interrupt를 기록하면서 join을 계속하고 완료 후 interrupt 상태를 복원한다. 종료된 작업 참조를 해제한다. |
| J17-02 / 최초 오류 소실 | 본문 읽기와 spill 파일 close가 함께 실패하면 close 오류가 최초 읽기 오류를 덮어쓴다. | 읽기/메모리 스냅샷/출력 종료 오류를 순서대로 병합한다. 본문 처리 실패 시에도 파일 종료를 항상 시도하고 후속 오류는 suppressed로 보존한다. |
| J17-03 / 실제 파일 핸들 잔존 | spill writer의 close가 하위 스트림을 닫기 전에 실패하면 지역 변수에만 있던 출력 스트림을 잃어, 파일 삭제 후에도 열린 핸들이 남을 수 있다. | pendingWriter 필드로 실패한 출력을 추적하고 BodyStore.close에서 재시도한다. 성공한 close 뒤에만 참조를 해제한다. 독립 리뷰 및 오류 주입 테스트로 재현했다. |
| J17-04 / 소유 스레드 경계 | 객체 재사용 모듈에서 Java 17의 재정의 가능한 Thread.getId를 동일성 검사에 사용해 다른 스레드가 소유자를 가장하거나 실제 소유자가 거부될 수 있다. | SubState는 WeakReference<Thread>로 실제 Thread 객체를 비교한다. 강한 Thread 참조를 보관하지 않으며 worker 반환 경로도 같은 기준을 사용한다. |

추가 회귀 테스트는 curl ResourceSafetyTest 3개와 객체 재사용 Java17SafetyTest 3개다. 이번 재분석에서 별도의 새 내부 NPE는 재현되지 않았으며, 기존 null 입력·미수신 Optional·닫힌 reader 검증은 계속 유지한다. 약한 참조의 실제 GC 시점이나 장기 heap 사용량을 검증했다는 의미는 아니다.

최종 JDK 17.0.10 clean verify 결과: curl 45개, 객체 재사용 61개, HTTP Payload Generator 43개, Partial Input 13개, LDAP 33개, **총 195개 통과(실패/오류/건너뜀 0)**. 독립 리뷰에서 writer 재시도 보완을 재확인했고 추가 지적은 없었다. 기존 Java 17 전환 변경과 사용자 샘플은 유지하며 커밋/push는 수행하지 않았다.
