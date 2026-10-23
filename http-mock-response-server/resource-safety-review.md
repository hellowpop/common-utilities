# HTTP Mock Response Server 자원 누수 / NPE 분석

- 분석일: 2026-10-22
- 대상: 커밋 a6bf160의 http-mock-response-server
- 환경: Windows, JDK 17.0.10, Maven 3.9.11
- 범위: 소켓, 스레드/executor, deadline 예약, 세션/연결 한도, 참조 보존, null 경계
- 최초 분석·재현 기록이다. 이후 구현에 반영했으며 최신 상태는 문서 마지막의 **HTTP parser/context 추가 재분석 (2026-10-23)** 절을 참조한다.

## 1. 결론

**장애 조건의 자원 회수 결함 2건과 이벤트 보관에 따른 메모리 보존 위험 1건을 재현했다. 정상 입력에서 발생하는 내부 NPE는 확인하지 못했다.**

| ID | 판정 | 발생 조건 | 관측 영향 | 우선순위 |
| --- | --- | --- | --- | --- |
| R1 | 자원 회수 결함, 장애 주입 재현 | close 작업의 worker 생성이 SecurityException 등으로 실패 | 종료 coordinator 중단, 열린 client socket/worker 잔존, stop 재시도도 정리 재개 못 함 | 높음 |
| R2 | 세션/연결 한도 회수 결함, 장애 주입 재현 | accept 이후 client worker 작업 제출이 RejectedExecutionException 이외의 예외로 실패 | 미실행 세션과 permit 미반환. stop 성공 후에도 참조 잔존 | 높음 |
| R3 | 조건부 메모리 보존, 공개 API 재현 | listener가 응답 제어 예외가 포함된 ServiceEvent를 수집 | 이벤트가 응답 본문 byte[]를 간접 보유 | 중간 |
| N1 | 내부 NPE 미확인 | 정상 설정과 정상 수명 | 기존 테스트/추가 probe에서 내부 NPE 미관측 | 관찰 결과 |
| N2 | 의도된 null 거부 또는 사용자 callback 오류 | 필수 null 입력, nullable 이벤트 필드 역참조 | 경계 NPE 또는 체인에서 격리되는 listener NPE | 계약/사용 주의 |

R1/R2는 기본 환경에서 예외가 자연 발생했다는 의미가 아니다. 분석용 JVM에서 reflection으로 executor를 조회하고 ThreadFactory에 SecurityException을 주입했다. 이는 스레드 생성 실패 후의 실제 운영 코드 흐름을 검증한 것이다. OS 자원 고갈이나 실제 보안 정책 거부의 발생 빈도는 측정하지 않았다. 일반 실행에서 항상 누수된다는 결론은 아니다.

## 2. 정상 경로의 자원 회수

| 자원 | 소유자 | 정상 회수 경로 | 확인 |
| --- | --- | --- | --- |
| 리스닝 ServerSocket | MockBinding / 실행기 | stop 및 bind rollback의 closeSocket | 기존 통합 테스트 및 probe |
| accept된 Socket | ClientSession | run finally → finish → close/forceClose | 정상 100회와 장애 probe |
| input/output stream | Socket | socket close와 함께 종료 | 별도 stream close 부재만으로 누수라 보지 않음 |
| read buffer | MockClientContext | context=null, session 제거 | 종료 등록부 검사 및 코드 확인 |
| 연결 permit | Semaphore | sessionFinished에서 release | 정상 100회 후 64개 복원 |
| client worker | ThreadPoolExecutor | shutdown/awaitTermination | 정상 종료 확인 |
| close worker | cached thread pool | client/accept 정리 후 shutdown/awaitTermination | 정상 종료 확인 |
| 응답 deadline | ScheduledThreadPoolExecutor | send finally의 cancel, remove-on-cancel | 정상 100회 후 queue=0 |
| 실행 guard | ThreadLocal depth | 대응 exit에서 depth=0이면 remove | 호출 쌍 정적 확인 |
| listener 등록 정보 | bindings/chain | 실행기 객체의 수명까지 보유 | 등록 수에 한정된 보존 |

정상 응답 100건 처리 뒤 sessions=0, availablePermits=64, deadline queue=0을 확인했다. stop 후 workers/closers/scheduler 모두 종료됐다. 기존 27개 테스트는 read/delay/write 중 stop, 부분 bind 실패 rollback, interrupt, 시작 callback 지연 등을 포함하고 모두 통과했다.

## 3. R1 — close 작업 생성 실패가 종료 절차 전체를 중단

### 위치

- [ClientSession.java](src/main/java/com/inzent/apim/utilities/http/mockserver/ClientSession.java) 96~102행: cancel에서 interrupt 후 closeAsync 호출.
- [MockServerExecutor.java](src/main/java/com/inzent/apim/utilities/http/mockserver/MockServerExecutor.java) 262~266행: closeAsync는 RejectedExecutionException만 fallback 처리.
- 같은 파일 194/204행: shutdownResources가 session.cancel을 예외 격리 없이 호출.
- 같은 파일 228행: coordinator의 최상위 catch는 InterruptedException만 처리.
- 같은 파일 177행: shutdownThread가 존재하면 정리를 다시 시작하지 않음.

### 흐름과 결과

1. client가 blocking read에 진입한다.
2. closers ThreadFactory가 SecurityException을 던지도록 설정한다.
3. stop의 coordinator가 session.cancel을 호출한다.
4. closeAsync의 execute가 예외를 던지고 coordinator 밖으로 전파된다.
5. workers.shutdown 및 나머지 정리에 도달하지 않는다.
6. 상태는 STOPPING이며 coordinator는 종료된다. 열린 client socket이 남는다.
7. 실패 조건을 제거해도 다음 stop은 같은 죽은 coordinator를 기다린 뒤 실패한다.

재현 출력:

> REPRODUCED: coordinator exited, state=STOPPING, workers not shut down
> REPRODUCED: stop retry does not restart abandoned cleanup
> REPRODUCED: blocked accepted socket remains open until diagnostic cleanup

probe는 관찰 이후 자신의 소켓과 executor만 직접 회수했다. 공개 stop API만으로는 복구하지 못했다. 일반 Exception이 shutdownFailure에 기록되지 않아 caller에는 최초 원인 대신 “Shutdown incomplete”가 전달되는 진단 손실도 있다.

### 권장 보완

- 세션별 취소와 전체 정리 단계를 예외 격리하여 하나의 실패 뒤에도 나머지 정리를 시도한다.
- close 작업 제출 실패 시 동일 세션의 직접 close를 시도하고 실패 원인을 보존한다.
- interrupt 실패와 close 요청을 분리해 interrupt 예외가 close 실행을 막지 않게 한다.
- 미완료 자원을 추적하고 재시도 가능한 종료 상태를 둔다. shutdownThread 생성 여부와 정리 완료 여부를 구분한다.
- 회수 전 SERVICE_STOP 또는 성공을 보고하지 않는다.

## 4. R2 — 실행되지 않은 session과 permit이 남음

### 위치

- [MockServerExecutor.java](src/main/java/com/inzent/apim/utilities/http/mockserver/MockServerExecutor.java) 125~139행: accept → permit 확보 → sessions 등록 → workers.execute.
- 같은 파일 135행: RejectedExecutionException에만 session.run 대체 경로 존재.
- 같은 파일 153~160행: failBinding은 cancel만 수행.
- 같은 파일 258~260행: sessionFinished가 등록 제거/permit 반환.
- [ClientSession.java](src/main/java/com/inzent/apim/utilities/http/mockserver/ClientSession.java) 68~75행: run finally에서 sessionFinished 호출.

client worker ThreadFactory가 SecurityException을 던지면 작업은 실행되지 않는다. failBinding은 socket을 닫지만 run finally가 없으므로 sessions.remove와 permit release도 없다.

기본 최대 연결 64개에서 첫 연결의 작업 제출을 실패시켰다.

> FAULT: accepted socket is closed after worker failure
> REPRODUCED: worker submission failure leaves sessions=1, permits=63
> REPRODUCED: successful stop still retains unstarted session and consumed permit

이 사례에서 **열린 socket은 남지 않았다.** 남는 것은 세션 참조와 연결 permit이다. 실패한 바인딩은 닫히지만 같은 실행기의 다른 바인딩에서 사용 가능한 한도가 줄어든다. 여러 바인딩에서 반복되면 감소가 누적될 수 있다. 실행기를 계속 보유하면 stop 후에도 해당 세션이 보존된다.

### 권장 보완

- accept 직후부터 작업 인계 성공 전까지 소켓/permit/등록 항목에 대한 소유권 보호를 둔다.
- 제출 실패 시 단 한 번 종료/해제하는 공통 경로를 호출한다. worker 시작 여부를 구분해야 한다.
- RejectedExecutionException 외 RuntimeException/Error도 자원 회수 후 전파한다.
- failBinding 및 stop은 실행되지 않은 세션도 회수할 수 있어야 한다.

## 5. R3 — 이벤트가 응답 body를 간접 참조

- [ClientSession.java](src/main/java/com/inzent/apim/utilities/http/mockserver/ClientSession.java) 49행: 원본 action 예외를 EXCEPTION_RECEIVED에 전달.
- 같은 파일 53행: 전송 실패 예외에 action을 suppressed로 추가.
- [SendResponseException.java](src/main/java/com/inzent/apim/utilities/http/mockserver/SendResponseException.java) 6~9행: MockResponse 보유.
- [MockResponse.java](src/main/java/com/inzent/apim/utilities/http/mockserver/MockResponse.java) 10/21행: 응답 byte[] 보유.

참조 경로는 **listener의 이벤트 목록 → ServiceEvent.exception → SendResponseException.response → MockResponse.body**다. 전송 실패에서는 exception의 suppressed action 경유로도 같은 참조가 남을 수 있다.

공개 API만으로 서로 다른 1 MiB 응답 4건의 이벤트를 목록에 보관했다. stop 후 sessions는 0이지만 이벤트를 통해 도달 가능한 body 크기는 **4,194,304 bytes**였다. 이는 강한 참조 경로와 contentLength로 확인한 값이며 전체 heap 계측값은 아니다.

실행기 자체는 이벤트 목록을 만들지 않으므로 이벤트를 저장하지 않을 때의 자동 누적 누수로 분류하지 않는다. 그러나 이벤트 수집 listener에서는 메모리 증가가 응답 크기에 비례하며 설계의 “listener에는 본문 전체를 전달하지 않는다”와 다르다.

### 권장 보완

listener용 제어 이벤트에는 action 종류, 지연, 상태/content type/본문 길이 등 진단 snapshot을 전달한다. 실행용 원본 예외를 그대로 노출하지 않는다. suppressed/cause까지 검토해야 하므로 response metadata 필드만 줄이는 것으로는 해결되지 않는다. 이벤트 저장소의 개수/보존 기간도 제한한다.

## 6. NPE 검토

### 내부 경로

- scheduler는 RUNNING 전에 초기화되고 세션은 startGate 이후 실행한다. 정상 공개 API에서 schedule의 scheduler가 null인 사례는 확인하지 못했다.
- context는 생성 후에만 ActionDispatcher에서 사용하고 종료 event 생성 시 null 검사한다.
- context는 worker 전용이므로 정상 사용에서 context=null과 다른 thread의 point 접근이 경합하지 않는다.
- 소켓 옵션 Map에는 null이 아닌 값만 넣으며 선택적 Integer는 setter 전에 null 검사한다.
- ExecutionGuard.exit의 Integer 언박싱은 대응 enter 이후 실행된다. 정상 enter/exit 불균형은 발견하지 못했다.
- chain은 null 원소를 구성 단계에서 거부하고 listener와 logger의 Exception을 각각 격리한다.

### 필수 null 입력

probe에서 **17개 필수-null 경계**를 확인했다. 실행기 옵션, mock/address/listener/options, readInterval/responseTimeout, shutdownTimeout, closeDelay, 응답 객체/content type/body, chain 배열/원소/andThen/event 등이다. 모두 검증 단계에서 거부됐고 해당 실행기에 session이나 permit 소비가 없었다.

이는 결함 NPE로 분류하지 않는다. null을 정상값으로 받는 계약이 아니며 필요하다면 명시적 문서나 매개변수 이름을 추가할 수 있다.

### nullable 이벤트의 사용자 NPE

SERVICE_START 등에서는 clientId/point/reason/exception/response가 null일 수 있다. listener가 event.response().statusCode()처럼 무조건 역참조하면 NPE가 발생한다.

이 NPE와 로깅 함수 NPE를 동시에 발생시켜도 후속 listener가 호출됐다. callback NPE가 서버 내부로 전파되어 자원 정리를 건너뛰는 경로는 확인하지 못했다.

## 7. 누수와 구분해야 할 보존/제약

- caller가 stop/close하지 않으면 서버가 non-daemon 스레드와 소켓을 계속 소유한다.
- listener나 point가 무한 대기하거나 interrupt를 무시하면 worker/context를 종료할 수 없다. callback 복귀 전까지 보존된다.
- 종료한 실행기 객체를 계속 보관하면 bindings와 listener closure도 남는다. 등록 수에 한정되며 실행기 참조도 해제하면 회수 가능한 구조다.
- 사용자 enum이 context를 static 필드 등에 저장하면 session/executor, owner Thread와 read buffer도 보관된다.
- chunk()의 ByteBuffer view를 보관하면 기반 read buffer가 남는다. 오래 사용할 데이터는 필요한 byte만 복사한다.
- 큰 readChunkSize, 메모리형 응답과 body() 방어 복사는 순간 메모리 사용을 키운다. 그 자체를 누수로 판단하지 않는다.
- 정상 경로 검증은 장기간 heap 안정성이나 모든 OS의 소켓 동작을 보증하지 않는다.

## 8. 검증 및 재현

### 기존 테스트

PowerShell에서 실행한다.

    $env:JAVA_HOME = 'D:\01.app\java\jdk-17.0.10'
    mvn -f http-mock-response-server/pom.xml test

결과: **27개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS**.

### 독립 probe

소스: [analysis/SafetyProbe.java](analysis/SafetyProbe.java)

    New-Item -ItemType Directory -Force http-mock-response-server/target/safety-probe | Out-Null
    & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp http-mock-response-server/target/classes -d http-mock-response-server/target/safety-probe http-mock-response-server/analysis/SafetyProbe.java
    & "$env:JAVA_HOME/bin/java.exe" -cp 'http-mock-response-server/target/classes;http-mock-response-server/target/safety-probe' com.inzent.apim.utilities.http.mockserver.SafetyProbe

**14개 진단 항목**을 확인했다. null 입력 17개는 그중 한 항목이다. REPRODUCED는 결함 존재 확인이며 수정 완료가 아니다. 수정 후에는 기대값을 변경한 자동 회귀 테스트로 옮겨야 한다.

| 항목 | 결과 |
| --- | --- |
| 정상 100응답 후 permit | 64/64 |
| 정상 100응답 후 sessions/deadline queue | 0/0 |
| stop 후 workers/closers/scheduler | 모두 terminated |
| worker 생성 실패 후 socket | 닫힘 |
| worker 생성 실패 후 sessions/permit | **1/63, stop 이후에도 동일** |
| closer 생성 실패 후 coordinator/상태 | **종료됨/STOPPING** |
| closer 실패 조건 제거 후 stop 재시도 | **정리 재개 안 됨** |
| closer 실패 후 client socket | **열린 상태로 남음** |
| 1 MiB 응답 4건 이벤트 보관 | **본문 4 MiB 강한 참조 유지** |
| 필수 null 17개 | 자원 할당 없이 거부 |
| listener NPE + logger NPE | 다음 listener 호출 |

fault injection은 probe 소유 서버 인스턴스의 executor에 한정된다. probe는 회수되지 않은 자신의 자원을 finally에서 직접 닫는다. 시스템 스레드 한도를 소진시키거나 다른 프로세스/서비스를 변경하지 않는다.

### Java API 근거

- [Java 17 ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html): ThreadFactory를 통한 스레드 생성, 권한 제약 및 direct handoff 동작.
- [Java 17 Thread](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Thread.html): 스레드 접근 권한, interrupt 및 non-daemon 수명.

## 9. 권장 수정 순서

1. R1: 종료 단계별 예외 격리, close 제출 실패 fallback, 미완료 cleanup 재시도.
2. R2: accept부터 worker 인계까지 소유권 보호 및 미실행 세션의 단 한 번 회수.
3. R3: 이벤트 진단 snapshot으로 본문 참조 차단, suppressed/cause 경로 검증.
4. 각 재현을 회귀 테스트로 옮기고 기존 27개 테스트와 함께 재검증.

분석 결과만 기록했으며 이번 요청에서 운영 구현의 동작을 변경하지 않았다.

## 자원 안전성 수정 적용 (2026-10-22)

분석 R1/R2/R3를 구현에 반영했다. 아래 최초 분석 내용은 수정 전 기록이며 현재 동작은 이 절을 따른다.

- worker 제출 실패 시 실행되지 않은 세션을 정확히 한 번 정리하고 소켓·permit을 반환한다.
- close executor 제출 실패 시 직접 close로 전환한다. interrupt 실패도 close 요청을 막지 않는다.
- 종료 단계별 오류를 수집하며 나머지 자원 정리를 계속한다. 종료 작업이 실패해 끝난 STOPPING 상태는 후속 stop으로 재시도한다. 모든 자원 정리 전 SERVICE_STOP을 발행하지 않는다.
- 이벤트의 Throwable은 원본이 아닌 ThrowableSnapshot이다. originalType(), 메시지, stack, cause/suppressed, closeDelay 및 응답 메타데이터만 복사하고 원본 제어 예외·응답 본문을 참조하지 않는다. 최대 깊이 8, 노드 64, 노드별 suppressed 8개, stack 64개, 메시지 4096자로 제한하며 순환·중복 참조는 생략한다.
- 기존 필수 null 거부와 listener/logger 예외 격리 계약은 유지한다. 이벤트의 nullable 필드는 사용자가 확인해야 한다.
- 회귀 테스트 5개 추가. 전체 32개 테스트와 독립 SafetyProbe 14개 검사항목 통과. 프로브는 정상 100응답 회수, worker/closer 생성 실패, 이벤트 참조 제거, null 경계를 확인한다.
- 독립 코드 리뷰에서 중요한 추가 결함 없음. Windows JDK 17 환경 검증이며 실제 OS 자원 고갈·장기 부하는 검증하지 않았다. 커밋/push는 수행하지 않았다.

## HTTP parser/context 추가 재분석 (2026-10-23)

### 대상과 결론

현재 미커밋 구현의 enum/AtomicReference point·readMode, context 내 기본 동작 분기, HTTP incremental decoder, content 블록·필드 맵 공유, snapshot, 세션/실행기 종료를 다시 분석했다. 기존 변경을 유지한 상태에서 아래 R4를 재현·수정했다.

| 항목 | 관측 및 조치 |
| --- | --- |
| R4 — 종료 context 외부 보관 | context를 보관하면 종료 세션·실행기·listener·worker Thread·읽기 버퍼·decoder 본문을 간접 보유. 정상 완료/파싱 오류/stop 세 경로에서 재현하고 종료 시 참조 해제로 수정 |
| 정상 서버 내부의 세션 누적 | 이번 검증에서는 미확인. sessions 제거, permit 반환, deadline 취소·queue 제거 및 executor 종료 확인 |
| 유효 호출의 내부 NPE | 이번 검증에서는 미확인. 신규 모델의 null 경계, 잘못된 입력, 종료 후 접근, listener NPE 격리 확인 |
| caller가 저장한 request snapshot | 보관한 snapshot의 본문은 의도된 보존. context 종료 후에도 snapshot은 사용 가능 |

R4는 외부에서 connection-scoped context를 보관할 때 발생하는 수명 종료 후 참조 보존이다. 외부 보관 없이 정상 서버만으로 세션이 무한 누적된다는 의미는 아니다. 우선순위는 중간이며, 반환된 context가 불필요하게 전체 실행기와 대용량 본문을 붙잡지 않도록 수명을 명시했다.

### R4 재현과 원인

수정 전 `MockClientContext.session`, `owner`, `buffer`는 final 강한 참조였고, decoder는 마지막 요청의 본문과 결과를 보유했다. `ClientSession`은 자신의 context 필드만 null로 만들었으므로 외부에서 context를 저장하면 다음 경로가 남았다.

```text
caller -> 저장한 MockClientContext -> ClientSession -> executor -> bindings/listeners
                                -> worker Thread
                                -> read buffer
                                -> HttpRequestDecoder -> content blocks / result snapshots
```

`HttpProtocolSafetyTest`에서 실제 소켓으로 1 MiB 요청 정상 완료, chunked 본문 뒤 잘못된 size에 의한 파싱 오류, 부분 본문 수신 중 stop을 실행했다. 종료 후 저장한 context의 참조를 검사하는 세 테스트가 모두 `ended context retains session`으로 실패했다. GC 성공 여부를 기다리는 방식 대신 참조 그래프의 직접 연결을 검사했다.

### 적용한 수명 관리

- context에 완료 상태와 package-private `release()`를 추가했다. 최종 disconnect 이벤트와 동기 listener 호출이 끝난 뒤 owning worker가 호출한다.
- session/owner/buffer/decoder/limits와 AtomicReference point/readMode를 해제한다. 별도로 반환한 HttpRequestSnapshot/HttpProtocolResult는 수정하지 않는다.
- `ClientSession`의 중첩 finally에서 release를 수행한다. 최종 callback 실패 시에도 runner/context 제거, session 등록 해제, permit 반환과 ThreadLocal guard 제거가 이어진다.
- 모든 public context 연산은 완료/owner 검사를 거쳐 정리된 참조를 사용하지 않게 한다. 종료된 context는 IllegalStateException으로 거부한다. callback/시나리오가 아직 실행 중이면 먼저 해제하지 않는다.
- 마지막 disconnect callback 안에서는 context와 마지막 결과를 조회할 수 있음을 실제 테스트로 확인했다. 완료 후 저장한 1 MiB snapshot의 본문·길이도 그대로 확인했다.

구현 위치: `MockClientContext.release/requireOwner`, `ClientSession.runClaimed`의 finally. 회귀 검증: [HttpProtocolSafetyTest.java](src/test/java/com/inzent/apim/utilities/http/mockserver/HttpProtocolSafetyTest.java).

### NPE 검토

- enum point의 null 입력, operation null, HTTP limits null 및 기존 설정의 필수 null은 입력 경계에서 거부하는 계약이다. 이를 정상 입력의 내부 NPE와 구분한다.
- 신규 decoder의 null feed는 상태를 변경하지 않고 거부하며 이후 정상 요청을 해석한다. snapshot/result의 필수 객체·목록·요소 및 각 단계 null을 거부한다.
- 공개 snapshot 생성자의 필드 Map/List와 body byte[]를 생성 이후 변경해도 snapshot은 바뀌지 않는다. content() 반환 배열도 독립적이다.
- initial line/header/fixed content/chunked framing/trailers의 다섯 시작 상태에서 고정 seed로 생성한 2,000개 입력을 1 byte씩 해석했다. IOException에 의한 입력 거부 또는 부분 결과 반환을 확인했고 내부 NPE는 발생하지 않았다. 이는 모든 입력에 대한 증명이 아니다.
- disconnect listener에서 NPE를 주입해 후속 listener 실행과 context 해제가 계속되는 것을 확인했다. snapshot을 포함한 이벤트의 nullable 필드는 여전히 caller가 확인해야 한다.
- 종료 후 public context 메서드는 IllegalStateException을 발생시킨다. 정리된 session/buffer를 역참조하는 NPE로 바꾸지 않는다.

### 누수와 구분한 보관

| 보관 대상 | 의미와 종료 조건 |
| --- | --- |
| 실행 중 요청 body·마지막 protocol 결과 | parser 기능상 필요. 기본 content 한도 16 MiB는 요청별 한도이며 전체 서버 메모리 한도가 아님 |
| caller가 모은 snapshot·완료 목록 | caller의 명시적 보관. 필요 이상으로 저장하면 본문이 누적되므로 보관 정책 필요 |
| 실행기가 가진 바인딩·listener | 등록 설정의 수명. 실행기 객체 자체를 보관하면 설정도 남음 |
| 끝나지 않은 scenario/listener | stop timeout 이후에도 작업이 실행 중일 수 있음. 작업 종료 전 강제로 context를 비우지 않음 |
| 비동기 socket close task | 실행 완료까지 세션 보관 가능. 완료된 session history를 수집하는 구조는 아님 |
| 신규 parser의 snapshot 생성 | 이전 본문 전체를 매번 복사하지 않고 동결 블록 공유. 변하지 않은 필드 맵도 재사용. 작은 단위의 header 수신 중에는 필드 추가 시 불변 맵을 다시 만들 수 있어 할당량은 입력 분할에 영향받음 |

### 검증 결과

Windows JDK 17.0.10 / Maven 3.9.11:

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-17.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -f http-mock-response-server/pom.xml clean verify
```

- **58개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS**. 기존 51개와 신규 안전성 테스트 7개다. 신규 7개 중 수명 회귀 3개는 수정 전 실패, 수정 후 통과했다.
- 기존 SafetyProbe를 JDK 17로 별도 컴파일·실행: **14개 진단 항목 통과**. 정상 100응답 후 sessions/deadline queue 0, permit 64/64 및 stop 후 executor 종료를 재확인했다.
- 기존 할당량 회귀 테스트와 body 블록 순서/이전 snapshot 불변성 테스트도 전체 suite에 포함되어 통과했다.
- 별도 코드 리뷰에서 종료 순서·NPE 방어·callback 실패 후 정리와 관련한 추가 중요 지적 없음.
- Windows loopback 및 제한된 장애 주입 기준이다. heap dump/장기 GC 추이, 다른 OS, 실제 OS 자원 고갈, 무한 대기 callback의 강제 중단은 검증하지 않았다. 커밋/push는 수행하지 않았다.
