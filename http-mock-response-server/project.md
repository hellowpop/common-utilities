# HTTP Mock Response Server 설계

## Git 반영 준비 (2026-10-23)

- 사용자 push 요청에 따라 enum/AtomicReference point·readMode, context 내 기본 point 실행, HTTP 요청 단계별 해석과 메모리/NPE 보완을 관련 테스트·문서와 함께 develop에 커밋하고 origin/develop에 반영한다.
- 대상은 이 모듈 전체 변경과 저장소 루트 project.md다. push 전 JDK 17 clean verify와 SafetyProbe를 다시 실행하고 원격 동기 상태 및 stage 범위를 확인한다. 아래 미커밋/미푸시 기록은 각 작업 완료 시점의 이력이다.
- push 직전 재검증: 실제 JDK 17 HTTP 모듈 clean verify 58개와 SafetyProbe 14개 통과. 원격 대비 기존 develop 미푸시 커밋 bf0a1dd가 포함되므로 해당 Object Pool 65개 및 Object Recycle Container 61개도 verify하여 총 184개 테스트 실패/오류/건너뜀 0을 확인했다.

## 메모리 누수 / NPE 재분석 반영 (2026-10-23)

- 종료 후 외부에서 보관한 MockClientContext가 session/executor/listener/worker/read buffer/HTTP decoder·본문을 붙잡는 경로를 정상 완료·파싱 오류·stop에서 재현했다. 세 회귀 테스트가 수정 전 실패했다.
- 마지막 이벤트/callback 후 context.release로 강한 참조를 해제한다. 완료 상태를 먼저 검사하여 종료 후 연산은 내부 NPE 대신 IllegalStateException으로 거부하며, 최종 정리·permit 반환·ThreadLocal 제거를 중첩 finally로 보장한다. 별도 보관한 request snapshot의 사용 수명은 유지한다.
- 신규 안전성 테스트 7개 추가. 실제 JDK 17 clean verify **58개 테스트 통과**, SafetyProbe **14개 진단 통과**. 필수 null/방어 복사/terminal listener NPE 및 5개 parser 상태의 2,000개 잘못된 입력을 확인했다. 유효 경로 내부 NPE와 정상 서버의 지속적인 세션 누적은 미확인이다.
- 별도 리뷰에서 추가 중요 지적 없음. 상세 원인·범위·한계는 [resource-safety-review.md](resource-safety-review.md) 마지막 재분석 절에 기록했다. 커밋/push는 수행하지 않았다.

## MockClientContext 개선 구성 (2026-10-23)

- 최종 구조 변경: 기본 동작의 switch를 `MockClientContext.runPoint(MockPoint)`로 이동하고 MockPoints 클래스를 제거했다. enum 입력으로 취소 확인/raw 읽기/지연/HTTP 읽기를 실행하며 기존 runPoint(Enum<?>, PointOperation)을 통해 이벤트·실패 진단·AtomicReference 중첩 복원을 함께 처리한다. 기본 case와 README는 context.runPoint(point)만 호출하며 사용자 정의 operation overload는 유지한다.
- 분기 변경 검증: 통합 API 사용 테스트의 미구현 컴파일 실패 확인 후 구현했으며, 실제 JDK 17 `verify` 51개 테스트 통과(실패/오류/건너뜀 0). 변경된 README Java 예제를 생성 JAR로 컴파일하고 main/HTTP 요청·응답 예제를 실행했다.

- 사용자 요청: String point 제거, enum 기반 식별자, AtomicReference로 point 변경 관리, HTTP initial line/header/content/trailers의 미도달·수신중·완료·해당없음 상태와 해석 결과 제공.
- 후속 개선: readMode도 `AtomicReference<ReadMode>`로 관리한다. compareAndExchange(null, mode)로 최초 모드를 선택하며 동일 모드는 유지하고 다른 모드는 기존 값을 변경하지 않고 거부한다. worker 소유 규칙은 유지한다.
- `MockPoint`는 기본 point enum이다. `runPoint(Enum<?>, PointOperation)`은 사용자 enum도 받는다. 현재 point는 AtomicReference의 getAndSet으로 진입/복원하고 ClientSession 실패 진단과 ServiceEvent.point도 Enum<?>를 보관한다. 기존 String 시그니처는 제거하며 호출자 재컴파일이 필요하다.
- `HttpReceiveState`: NOT_REACHED/RECEIVING/COMPLETE/NOT_APPLICABLE. `HttpRequestSnapshot`: initialLine/headers/content/trailers와 단계별 상태, complete 및 contentBytes. 필드 이름은 소문자이고 중복 값과 binary content를 보존한다.
- `HttpRequestDecoder`는 연결별 incremental parser다. 고정 길이/chunked·extensions·trailers·같은 read 내 복수 요청·분할 요청을 해석하며 소켓은 소유하지 않는다. snapshot은 완료된 content 블록과 불변 header/trailer 맵을 공유한다. `HttpContentBuffer`는 8 KiB 블록을 동결하고 tail만 복사하며 content() 조회 시 배열로 합친다.
- `HttpProtocolResult`는 마지막 요청 snapshot, 이번 read의 완료 요청 목록, EOF와 truncated를 제공한다. 완료 목록을 연결 전체 이력으로 누적하지 않는다. `HttpProtocolLimits`는 line/header/content의 크기 한도이며 기본값은 8 KiB/64 KiB/16 MiB다. header 한도는 trailers에 별도 적용하고 chunk framing은 그 16배다.
- `MockClientContext.readHttpProtocol()` 및 limits overload는 한 번 읽은 모든 bytes를 해석하고 결과를 반환한다. `httpProtocol()`은 추가 I/O 없이 조회한다. context는 worker 전용이며 반환 snapshot은 스레드 간 전달할 수 있다. `context.runPoint(MockPoint.READ_HTTP_PROTOCOL)`로 실행한 결과도 context.httpProtocol()로 조회한다.
- Content-Length 0/없는 요청은 content·trailers 해당없음, 양수 고정 길이는 trailers 해당없음이다. chunked는 0 chunk 후 content 완료, 최종 빈 줄 후 trailers 완료다. EOF의 미완료 상태는 그대로 반환한다. raw와 HTTP 읽기는 연결 내 혼용을 거부한다.
- TE+CL/상충 CL/unsupported transfer coding/잘못된 framing/크기 초과는 IOException이며 이후 parser read는 I/O 전에 거부한다. 이벤트에 request snapshot이나 본문을 추가하지 않는다. 기본 raw case, 연결당 응답 후 close, 기존 취소/회수 계약은 유지한다.
- 세부 설계·작업 및 검증 기록: [context-improvement-plan.md](context-improvement-plan.md). 사용법과 migration: [README.md](README.md). HTTP framing 기준: [RFC 9112 §6.3](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3), [§7.1](https://www.rfc-editor.org/rfc/rfc9112.html#section-7.1).
- 최종 Windows JDK 17.0.10 / Maven 3.9.11 clean verify 51개 테스트 통과(실패/오류/건너뜀 0), SafetyProbe 14개 통과, README Java 예제의 JAR 단독 컴파일 및 main/HTTP 요청·응답 실행을 확인했다. 본문·헤더 반복 복사와 실패 후 재읽기를 회귀 테스트로 재현·보완했고 최종 별도 리뷰에서 추가 중요 지적 없음. 커밋/push는 수행하지 않았다.

아래 최초 설계/이력의 String point 및 HTTP parser 미제공 설명은 개선 전 기준이다. 현재 구성은 이 절과 README를 따른다.

## 기능 추가 사전 분석 (2026-10-23)

- 현재 구현의 구조, 공개 확장 API, 기능별 변경 지점과 자원·종료·이벤트 계약을 [기능 확장 분석서](feature-extension-analysis.md)에 정리했다.
- 실제 JDK 17.0.10 / Maven 3.9.11 `clean verify`: 테스트 32개, 실패/오류/건너뜀 0, BUILD SUCCESS.
- 이번 작업은 분석 문서 갱신이며 운영 코드·공개 API 변경, 커밋 및 push는 수행하지 않았다. 구체 추가 기능은 아직 확정하지 않았다.

- 작성일: 2026-10-22
- 상태: 2026-10-22 사용자 개발 승인 후 구현. 최종 검증 기록은 아래 13절을 따른다.
- 모듈: `http-mock-response-server` — 독립 Maven utility JAR
- 좌표: `com.inzent.apim.utilities:http-mock-response-server:1.0.0-SNAPSHOT`
- Java: 17, 기본 패키지: `com.inzent.apim.utilities.http.mockserver`
- 산출물: Java 소스, pom.xml, 단위/실제 소켓 통합 테스트, README 및 구현 계획.

## 1. 목적과 범위

HTTP 클라이언트의 연결 대기, 느린 업로드, 강제 연결 종료 및 지정 응답 수신 동작을 재현하는 임베디드 서버 유틸리티다. 호출자는 실행기를 생성하고 서로 다른 주소/포트에 mock을 등록한 뒤 start/stop한다. 기존 `curl-http-mock-executor`와 함께 사용할 수 있지만 두 모듈 간 런타임 의존성은 두지 않는다.

요청한 필수 기능은 enum 기반 절차 정의, 연결별 point method, 소켓 옵션, disconnect 대기/지연 읽기, NextActionException 기반 후속 동작, 상태 listener다. 아래 스레드 수, 시간 제한, HTTP 범위, 재시작 제한은 구현 가능하도록 구체화한 제안이다.

첫 버전은 평문 TCP 위에서 HTTP/1.1 형식의 지정 응답을 보낸다. HTTP 요청을 완전히 파싱하는 웹 서버가 아니며 URL 라우팅, TLS, keep-alive, pipelining, HTTP/2, 요청 압축 해제는 범위 밖이다. 하나의 바인딩에는 하나의 mock을 등록한다. 요청 메서드와 무관한 응답 주입 도구이므로 HEAD 등 메서드별 HTTP 응답 규칙을 자동 적용하지 않는다.

## 2. 접근 방식

| 방식 | 장점 | 비용/제약 |
| --- | --- | --- |
| **JDK ServerSocket + 연결별 blocking worker (권장)** | accept 직후·read 이전·close 시점 제어가 직접적이며 외부 런타임 의존성이 없음 | 장기 연결당 플랫폼 스레드가 필요하므로 연결 상한 필요 |
| JDK NIO Selector | 많은 유휴 연결을 적은 스레드로 처리 | 지연·부분 쓰기·후속 동작을 상태 머신으로 관리해야 함 |
| Netty pipeline | 비동기 처리 및 HTTP codec 활용 | 의존성 및 event loop 규칙이 추가되고 point에서 blocking 금지 필요 |

첫 버전은 소규모 장애 재현에 적합한 JDK blocking 방식으로 한다. Java 17 기준이므로 가상 스레드를 사용하지 않는다.

## 3. 구성 및 소유권

| 구성 | 책임 |
| --- | --- |
| `MockServerExecutor implements Closeable` | 등록, 전체 수명, bind rollback, accept/worker/deadline executor 소유 |
| `MockScenario` | enum이 구현하는 `execute(MockClientContext) throws Exception` 계약 |
| `MockCase implements MockScenario` | 기본 case별 실행 point와 순서를 enum 상수 본문에 선언 |
| `MockPoint` / `MockClientContext.runPoint(MockPoint)` | 기본 동작의 enum 식별자 및 읽기·지연·취소 확인 실행 |
| `MockClientContext` | 연결 ID, 설정, 현재 point, 누적 읽기 수, 취소 상태 및 제한된 I/O 기능 |
| `MockOptions` | 등록별 소켓 옵션, 읽기 크기/간격, 전송 제한 시간 |
| `ServerOptions` | 전체 최대 연결 수와 종료 대기 시간 |
| `NextActionException` | point 실행 중 다음 동작을 지정하는 abstract checked exception |
| `CloseClientException` | 지정 시간 후 연결 close 및 case 종료 |
| `SendResponseException` | 지정 HTTP 응답 write/flush 후 close 및 case 종료 |
| `MockResponse` | 상태 코드, content type, 방어 복사한 body byte[] |
| `ServiceStatusListener` / `ServiceEvent` | 단계별 불변 이벤트 전달 |
| `ServiceStatusListenerChain implements ServiceStatusListener` | 복수 listener의 불변 체인 구성, 순차 전달 및 개별 예외 로깅·격리 |
| 내부 `ClientSession`, `ActionDispatcher`, `HttpResponseWriter` | 소켓 소유·종료 일원화, 예외별 동작 분기, HTTP 직렬화 |

enum은 공유되는 불변 singleton이다. 연결별 카운터, socket, stream, 예외 인스턴스는 enum 필드에 저장하지 않는다. 각 연결에 새 context/session을 생성하며 종료 시 등록부에서 제거한다. listener에는 context/socket/본문 전체를 전달하지 않는다.

## 4. 공개 API 제안

아래는 공개 API 요약이다. 메서드 본문을 생략했으며 실제 컴파일 가능한 사용 예제는 README에 제공한다.

```java
public final class MockServerExecutor implements Closeable {
    public MockServerExecutor();
    public MockServerExecutor(ServerOptions options);
    public <E extends Enum<E> & MockScenario> void addMock(
        E mock, InetAddress bindAddress, int bindPort,
        ServiceStatusListener listener);
    public <E extends Enum<E> & MockScenario> void addMock(
        E mock, InetAddress bindAddress, int bindPort,
        MockOptions options, ServiceStatusListener listener);
    public void start();
    public void stop();
    public void close(); // stop과 동일
}

public interface MockScenario {
    void execute(MockClientContext context) throws Exception;
}

public interface ServiceStatusListener {
    void onEvent(ServiceEvent event);
}

public final class ServiceStatusListenerChain implements ServiceStatusListener {
    public static ServiceStatusListenerChain of(ServiceStatusListener... listeners);
    public ServiceStatusListenerChain andThen(ServiceStatusListener listener);
    public void onEvent(ServiceEvent event);
}
```

4인자 addMock은 기본 MockOptions를 사용한다. 5인자 overload로 동일 enum을 서로 다른 옵션과 포트에 등록한다. 기존 enum 상수 추가뿐 아니라 소비자가 작성한 별도 enum도 같은 interface를 구현하여 등록할 수 있다. Java enum 상속은 사용하지 않는다.

addMock의 listener 인자는 체인을 전달하는 진입점이다. 단일 listener를 전달해도 내부에서는 원소 1개의 체인으로 정규화한다. 여러 listener는 `ServiceStatusListenerChain.of(first, second).andThen(third)`로 구성한다. listener 생략 의도는 빈 체인 `ServiceStatusListenerChain.of()`으로 표현하며 null 인자·배열·원소는 거부한다.

bindPort는 0~65535이며 0은 OS 자동 할당이다. 실제 포트는 SERVER_SOCKET_OPEN 이벤트로 전달한다. 동일 주소/비영 포트 중복은 등록 시 거부하고 wildcard/IPv4/IPv6 간 충돌은 실제 bind 결과로 판정한다.

## 5. enum 실행 절차와 point

enum의 execute가 전체 순서를 기술한다. `context.runPoint(point, operation)`은 현재 point 설정, POINT_ENTER 전달, operation 실행, 성공 시 POINT_EXIT 전달을 담당한다. 실패한 point에는 POINT_EXIT를 보내지 않는다. exception 분기는 연결 worker의 최상위에서 한 번만 수행한다. context의 직접 socket close는 공개하지 않는다.

| point method | 역할 |
| --- | --- |
| `onConnected(context)` | accept 및 옵션 적용 후 실행하는 연결 초기화 point |
| `beforeRead(context)` | 다음 read 전 제어 point |
| `readChunk(context)` | 최대 readChunkSize만큼 1회 read, 결과 바이트 수 또는 EOF 반환 |
| `onChunkReceived(context, chunk)` | 읽은 데이터 관측/판단. 이 지점에서 후속 예외를 던질 수 있음 |
| `delayBeforeNextRead(context)` | 성공한 read 이후 설정된 readInterval 동안 취소 가능한 대기 |
| `onPeerEof(context)` | 입력 EOF 처리 point; 별도 action이 없으면 연결 종료 |

`MockClientContext.runPoint(MockPoint)`가 기본 동작과 이벤트 경계를 함께 제공하고 enum scenario는 필요한 point를 선택·조합한다. 사용자 정의 enum 식별자와 동작은 runPoint(Enum<?>, PointOperation)으로 감쌀 수 있다. listener callback은 mock point가 아니므로 후속 예외 제어 경로로 사용하지 않는다.

### WAIT_FOR_CLIENT_DISCONNECT

`onConnected → [beforeRead → readChunk → onChunkReceived] 반복 → onPeerEof → close`

- 서버가 먼저 응답하거나 연결을 종료하지 않는다. 수신 바이트는 이벤트 후 폐기한다.
- 읽기 없이 `isConnected()` 조회만으로 상대 disconnect를 감지하지 않는다.
- read의 EOF는 상대의 출력 half-close도 포함한다. 이 케이스는 입력 EOF를 종료 조건으로 삼아 자신의 연결도 닫는다.
- 상대가 FIN/RST를 보내지 않는 네트워크 단절은 즉시 감지할 수 없다. 기본 read timeout은 무제한이며 stop으로 해제한다.

### SLOW_READ

`onConnected → [beforeRead → readChunk → onChunkReceived → delayBeforeNextRead] 반복 → onPeerEof → close`

- 첫 read는 즉시 수행한다. 양수 길이 read 성공 후 다음 read를 시작하기 전 readInterval을 기다린다. EOF 후에는 지연하지 않는다.
- 기본 readChunkSize는 1,024 bytes, readInterval은 100 ms로 제안한다. 0 ms는 지연 없음이다.
- TCP는 byte stream이므로 read 한 번과 클라이언트 write 또는 실제 네트워크 패킷이 일치하지 않는다. 여기서 “패킷 단위”는 최대 크기가 제한된 애플리케이션 read chunk다.
- 이는 애플리케이션 소비 속도 지연이다. OS 수신 버퍼가 먼저 데이터를 수신하므로 송신자에게 즉시 동일 속도 제한이 걸리지는 않는다. 장기적으로 수신 버퍼가 차면 backpressure가 나타난다.
- 두 기본 case는 자동 응답하지 않는다. 향후 enum에서 특정 수신 횟수/바이트 수 등에 도달하면 SendResponseException을 던져 응답한다. HTTP 요청 완료 판정이 필요하면 별도 framing point를 추가한다.

chunk는 해당 point 동안만 유효한 읽기 전용 view로 제공한다. 보관하려면 호출자가 복사한다. 공통 구현은 전체 요청을 누적하지 않으므로 연결당 메모리는 읽기 버퍼 크기에 비례한다.

## 6. NextActionException 및 실패 처리

```text
Exception
└─ abstract NextActionException
   ├─ final CloseClientException(closeDelay)
   └─ final SendResponseException(response)
```

abstract class는 `extends Exception`, 구체 예외는 해당 class를 extends한다. 예외 class는 interface처럼 implements하지 않는다. checked exception으로 point와 execute 선언에 후속 동작 발생 가능성을 드러낸다.

| 발생 결과 | 이벤트와 후속 처리 |
| --- | --- |
| point 정상 반환 | 다음 point 실행 |
| CloseClientException | EXCEPTION_RECEIVED(action=true) → closeDelay 대기 → close → CLIENT_DISCONNECTED |
| SendResponseException | EXCEPTION_RECEIVED(action=true) → RESPONSE_SEND → write/flush → RESPONSE_SENT → close → CLIENT_DISCONNECTED |
| 그 외 Exception | EXCEPTION_RECEIVED(action=false) → 추가 응답·설정 지연 없이 해당 client close |
| action 수행 중 실패 | 새 EXCEPTION_RECEIVED(action=false) → close; 다른 action으로 재진입하지 않음 |
| stop에 의한 read/write 해제·대기 취소 | 정상 STOP 원인으로 close; 유발된 SocketException을 장애로 중복 보고하지 않음 |

최초 point 예외와 action 실패는 각각 기록하되 원인 관계를 보존한다. cleanup 실패는 최초 오류에 suppressed로 부착하며 다른 세션 정리는 계속한다. 심각한 Error도 finally 자원 정리는 수행하되 정상 case 결과로 변환하여 삼키지 않는다.

“즉시 종료”의 범위는 예외를 던진 client session이다. 다른 client와 바인딩의 서비스는 계속한다. accept 자체의 비정상 실패는 해당 바인딩을 FAILED로 전환하고 해당 바인딩의 세션을 닫으며 다른 바인딩은 유지한다.

closeDelay 기본값은 0이고 음수는 거부한다. 이는 예외를 받은 시점부터 close를 **호출하기 전**까지 기다리는 시간이다. 지연 동안 추가 읽기/응답은 수행하지 않으며 stop이 오면 지연을 취소한다. 양수 SO_LINGER에 따른 close 자체의 소요 시간과는 별개다. closeDelay=0은 의도적 대기가 없다는 뜻이며 TCP RST 보장을 뜻하지 않는다.

알 수 없는 NextActionException 하위 타입은 미지원 action 오류로 이벤트를 보내고 종료한다. 새 action 종류를 추가할 때는 dispatcher도 함께 확장한다.

## 7. HTTP 응답

MockResponse 기본 상태는 200 OK이며 body는 byte[]로 방어 복사한다. UTF-8 문자열 factory를 제공하며 Content-Length는 문자 수가 아닌 실제 byte 수로 계산한다. 기본 content type은 `application/octet-stream`이다.

응답은 상태행, Content-Type, Content-Length, `Connection: close`, 빈 행, 본문 순서로 CRLF를 사용해 보낸다. 첫 버전의 상태 코드는 본문을 허용하는 200~599로 제한하고 204/205/304는 빈 본문만 허용한다. 임의 헤더·상태 문구 입력은 제공하지 않으며 content type의 CR/LF 및 제어문자를 거부한다.

RESPONSE_SEND는 첫 write 직전, RESPONSE_SENT는 전체 write와 flush가 성공한 뒤 발생한다. SENT는 상대 애플리케이션이 읽었다는 확인이 아니다. 부분 쓰기 실패에는 SENT가 발생하지 않는다. 응답 전송 제한 시간 기본값은 10초로 제안한다. blocking write에는 SO_TIMEOUT을 적용할 수 없으므로 별도 deadline task가 socket을 닫아 해제하며, 완료 시 예약 작업을 취소·제거한다. timeout/stop/성공의 경합은 세션의 terminal 상태로 단일 판정한다.

미소비 요청이 남은 상태의 조기 응답/close는 OS와 상대 상태에 따라 reset으로 관측될 수 있다. 이 도구는 mock 응답 write를 수행하지만 상대의 응답 수신을 보장하지 않는다. body는 첫 버전에서 메모리형이며 대용량 streaming 응답은 별도 확장 범위다.

## 8. 소켓 옵션

리스닝 ServerSocket 옵션과 accept된 Socket 옵션을 분리한다.

| 설정 | 적용 대상/시점 | 기본 제안 |
| --- | --- | --- |
| reuseAddress | ServerSocket, bind 이전 | true |
| backlog | ServerSocket.bind | 50 |
| serverReceiveBufferSize | ServerSocket, bind 이전 | 미지정(OS 기본) |
| receiveBufferSize / sendBufferSize | client Socket, accept 직후 | 미지정(OS 기본) |
| lingerSeconds | client Socket, accept 직후 | -1(disabled), 0~65535 허용 |
| keepAlive | client Socket, accept 직후 | false |
| tcpNoDelay | client Socket, accept 직후 | true |
| readTimeoutMillis | client Socket, read 이전 | 0(무제한) |
| readChunkSize | 애플리케이션 read 배열 | 1,024 bytes |
| readInterval | SLOW_READ의 read 사이 대기 | 100 ms |

버퍼 요청 값은 양수이며 OS가 조정할 수 있다. 요청값과 getter로 확인한 실제값을 open/connected 이벤트에 구분해 담는다. SO_LINGER는 client Socket 옵션이며 리스닝 ServerSocket에는 설정하지 않는다. 옵션 적용 실패는 조용히 무시하지 않는다. 서버 옵션 실패는 start 실패, client 옵션 실패는 해당 연결 종료다.

설정값은 불변 객체로 등록하며 잘못된 범위와 Duration 변환 overflow를 등록 시 검증한다. 양수 read timeout 만료는 일반 예외로 이벤트 후 해당 연결을 종료한다. accept는 서버 socket close로 해제하므로 주기적 polling timeout은 두지 않는다.

Java 계약 근거: [Socket Java 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html), [ServerSocket Java 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html).

## 9. listener chaining 계약

각 mock 등록은 독립적인 listener 체인을 가진다. 체인은 로깅 → 계측 → 테스트 관측처럼 등록 순서대로 동일한 ServiceEvent를 전달한다. 모든 listener 호출은 체인이 책임지므로 개별 listener가 next를 호출하거나 이벤트 전달을 중단하는 방식은 사용하지 않는다.

- `of(...)`는 입력 배열을 방어 복사한다. `andThen(...)`은 기존 체인을 변경하지 않고 끝에 listener를 추가한 새 체인을 반환한다.
- 다른 체인을 추가하면 구성 원소를 순서대로 펼친다. 불변 구성과 평탄화로 순환 next 참조를 만들지 않는다.
- 동일 listener를 중복 등록하면 등록 횟수만큼 호출한다. 자동 중복 제거는 하지 않는다.
- 등록 후 실행 중인 체인에 동적 추가·삭제는 제공하지 않는다. 같은 체인을 여러 mock에 재사용할 수 있지만 listener 인스턴스의 상태까지 복사하지는 않는다.
- 한 이벤트의 전체 체인 호출이 끝난 뒤 해당 연결의 다음 이벤트를 전달한다. 빈 체인은 무동작이다.

| event | 발생 시점/범위 |
| --- | --- |
| SERVICE_START | start 준비 시작, 등록별 1회 |
| SERVER_SOCKET_OPEN | 해당 bind 성공, 실제 주소/포트 포함 |
| CLIENT_CONNECTED | accept 성공 및 세션 등록 직후, 옵션 적용 이전 |
| POINT_ENTER / POINT_EXIT | mock point 진입/정상 완료 |
| REQUEST_RECEIVED | 양수 read마다, read byte 수와 누계 포함 |
| RESPONSE_SEND / RESPONSE_SENT | 응답 쓰기 시작/전체 쓰기·flush 완료 |
| EXCEPTION_RECEIVED | point/action/I/O 실패 또는 제어 예외 수신 |
| CLIENT_DISCONNECTED | 세션 정리 완료 후 정확히 1회, 종료 원인 포함 |
| SERVER_SOCKET_CLOSED / SERVICE_STOP | 바인딩 정리/서비스 종료 완료 |

REQUEST_RECEIVED는 HTTP 요청 한 건의 완료가 아니라 chunk 수신이다. CLIENT_DISCONNECTED는 원격 disconnect만이 아니라 서버 action/stop에 의한 종료도 포함한다. 종료 원인은 PEER_EOF, ACTION_CLOSE, RESPONSE_COMPLETED, ERROR, STOP, CAPACITY_REJECTED 등으로 구분한다. 옵션 적용 전 연결 이벤트에는 실제 client 옵션 snapshot이 없으며 onConnected point 진입 이벤트에서 제공한다.

ServiceEvent는 시각, 등록 ID, case명, client ID(연결 이벤트만), 로컬/원격 주소, point, byte 수, 응답 메타데이터, 종료 원인, 예외를 선택적으로 포함한다. body 전체나 자격증명 데이터를 자동 기록하지 않는다.

체인은 이벤트 발생 스레드에서 동기 호출하며 내부 수명 락 밖에서 실행한다. 같은 연결의 이벤트와 그 listener 호출은 직렬 전달하고 다른 연결의 전역 순서는 보장하지 않는다. 공유 listener는 thread-safe이며 빠르게 반환해야 한다. 느린 listener는 같은 이벤트의 후속 listener와 해당 연결 처리를 지연시킨다. listener에서 addMock/start/stop 재진입은 거부한다. 제어가 필요하면 별도 caller thread로 전달한다.

체인은 각 listener 호출을 개별 try/catch로 감싼다. Exception(RuntimeException 포함)을 catch하면 해당 listener의 순번, 이벤트 종류, 등록/client ID 및 예외 stack trace를 JDK System.Logger에 즉시 기록하고 다음 listener를 실행한다. 앞선 listener의 예외 때문에 뒤의 listener를 건너뛰지 않으며 등록된 각 원소를 이벤트당 한 번씩 호출한다.

listener 예외는 체인 내부에서 처리하며 집계 예외를 만들거나 실행기로 다시 throw하지 않는다. `ListenerChainException`은 제공하지 않는다. listener 예외로 client를 종료하거나 start를 rollback하지 않으며 원래 mock 절차를 계속한다. 같은 체인에 재귀 EXCEPTION_RECEIVED를 보내지 않고 원래 이벤트와 원인 예외도 변경하지 않는다. 종료 이벤트에도 동일한 규칙을 적용하며 실패 listener를 자동 제거하지 않는다.

로깅 호출 자체의 Exception도 별도로 catch하여 다음 listener 실행을 보장한다. 로깅 실패를 같은 logger에 재귀 기록하지 않는다. 이 경우 로그 저장 성공은 보장하지 않지만 체인의 순회는 계속한다. 이 예외 격리는 listener와 로깅 경계에만 적용하며 mock point의 예외 처리 규칙은 그대로 유지한다.

listener callback은 NextActionException을 처리하는 point가 아니다. callback에서 발생한 예외로 응답 전송이나 지연 close를 지시할 수 없다. 실행 보장은 callback이 반환하거나 Exception을 던지는 경우의 순차 호출 보장이다. listener의 무한 대기나 JVM 실행을 지속할 수 없는 치명적 Error까지 복구하는 계약은 아니다. Error는 자원 정리 후 전파하며, 무한 대기는 강제 스레드 종료 대신 stop 제한 시간 초과로 보고한다.

## 10. 실행기 수명과 동시성

`NEW → STARTING → RUNNING → STOPPING → STOPPED`, start 실패는 자원 정리 후 FAILED다. STOPPED/FAILED 실행기는 재시작하지 않고 새 인스턴스를 생성한다.

- addMock은 NEW에서만 허용하며 등록이 없는 start는 거부한다.
- start는 모든 bind와 accept 작업 준비를 완료한 뒤 반환한다. 전체 준비 완료 gate가 열린 이후에만 client 처리를 시작한다. 한 bind라도 실패하면 이미 연 모든 서버 소켓/작업을 rollback하고 MockServerStartException(unchecked)을 던진다.
- addMock/start는 수명 제어로 직렬화한다. stop은 시작 callback 완료를 기다리지 않고 STOPPING을 설정하고 이미 열린 소켓의 정리를 요청한다. 시작 흐름은 callback 복귀 후 취소를 확인하고 rollback하며, 종료 coordinator는 시작 흐름 종료 후 남은 자원도 회수한다. stop 호출자는 이 모든 대기에 종료 제한 시간을 적용한다. RUNNING의 중복 start는 무동작, 중복 stop은 같은 종료 작업을 기다리는 멱등 호출이다. NEW의 stop은 STOPPED로 전환한다.
- 바인딩당 accept 스레드 1개, 실행기 전체 최대 client worker 기본 64개, deadline scheduler 1개를 제안한다. 작업 대기열은 두지 않고 수용 초과 연결은 이벤트 후 즉시 닫는다.
- accept 후 session 등록과 stop snapshot 사이에는 동일 수명 보호를 적용한다. STOPPING에서 수신한 소켓도 반드시 close한다. worker 제출 거부 시에도 세션을 제거하고 닫는다.
- stop은 신규 accept 차단 → 서버 소켓 close → 세션 취소·지연 interrupt → client socket close → executor shutdown → 작업 회수 순서다. socket close로 blocking read/write를 해제한다.
- 강제 stop/일반 오류 종료에서는 설정한 closeDelay를 무시하고 양수 linger를 비활성화한 뒤 close를 시도한다. 정상 action close는 등록된 linger를 유지한다. stop의 close 작업은 서로 독립적으로 진행하며 한 연결의 정리가 다른 연결 정리를 막지 않도록 한다.
- 종료 대기 제한 기본값은 전체 5초다. 미종료 작업이 있으면 STOPPING을 유지하고 MockServerStopException으로 알리며 후속 stop에서 재확인할 수 있다. 모든 자원을 회수하기 전에 SERVICE_STOP이나 성공을 보고하지 않는다.
- caller interrupt가 발생해도 모든 자원에 취소/close를 요청하고 interrupt 상태를 복원한 뒤 실패를 보고한다. 사용자 listener/point가 interrupt를 무시하는 경우 종료 완료는 보장하지 않는다.
- 기본 스레드는 non-daemon이며 caller가 stop/close를 호출하는 소유권 계약이다. start 반환 후 caller는 다른 작업을 계속할 수 있다.

## 11. caller 사용 시나리오

다음은 API 사용 형태 요약이다. [README.md](README.md)에 외부 패키지에서 컴파일·실행하는 전체 예제를 제공한다.

```java
ServiceStatusListenerChain listeners = ServiceStatusListenerChain
    .of(loggingListener, metricsListener)
    .andThen(testObserver);

try (MockServerExecutor server = new MockServerExecutor()) {
    InetAddress address = InetAddress.getLoopbackAddress();
    server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, address, 18080, listeners);
    server.addMock(MockCase.SLOW_READ, address, 18081,
        MockOptions.builder()
            .readChunkSize(1024)
            .readInterval(Duration.ofMillis(200))
            .build(), listeners);
    server.start();
    // caller가 HTTP 클라이언트 테스트를 수행한다.
    server.stop();
} // close는 stop과 동일하며 중복 호출 가능
// finish
```

추가 enum의 특정 point에서 `throw new CloseClientException(Duration.ofSeconds(2))` 또는 `throw new SendResponseException(MockResponse.utf8(200, "application/json; charset=UTF-8", "{\"ok\":true}"))`를 수행한다. action 이후 원래 enum 흐름으로 복귀하지 않는다.

## 12. 구현 및 검증 기준

1. Java 17 독립 Maven JAR, 외부 런타임 라이브러리 없이 기본 case 동작.
2. 서로 다른 포트에 복수 등록, 외부 enum 등록, 동일 enum의 다른 옵션, 포트 0의 실제 주소 이벤트 확인.
3. 전체 bind 성공 후 start 반환, 부분 bind/옵션/worker 시작 실패 시 누락 없는 rollback.
4. disconnect 대기 중 무응답 유지, 데이터 폐기, EOF/half-close 종료, RST 오류 이벤트와 정리.
5. 여러 client write를 합치거나 분할해서 보내도 데이터 누락 없이 읽기. write 횟수와 read 횟수 일치를 테스트 전제로 하지 않음.
6. 지연 테스트는 주입 가능한 대기 경계로 호출 순서를 검증하고 loopback 통합 테스트는 시간 허용 오차를 둠. stop은 진행 중 지연을 취소.
7. 각 point의 Close/Response/일반 예외 주입: 이후 point 미실행, 연결별 종료, 다른 연결 생존.
8. closeDelay=0/양수, linger disabled/0/양수의 계약 분리. OS별 FIN/RST 결과를 이식 가능한 단위 테스트에서 단정하지 않음.
9. HTTP 상태행/CRLF/Content-Length/UTF-8 byte 수, 빈 본문, 금지 헤더 문자, 쓰기 실패와 deadline에서 SENT 미발생.
10. listener 체인 등록 순서·동일 이벤트 전달, 빈/단일/중첩/중복 구성, 입력 배열 방어 복사와 andThen 불변성, 연결별 순서·단 한 번의 disconnect. 첫/중간/복수 listener 예외 시 각각 로깅 후 모든 후속 listener 호출, 로깅 자체 예외에도 순회 지속, 실행기로 예외 미전파, client 유지·start 성공·mock 절차 계속, terminal 이벤트 전달 및 비재귀 진단 검증.
11. 연결 상한 초과, accept/stop 경합, blocking read/write 중 stop, 중복 start/stop, 종료 제한 초과·interrupt 복원.
12. 요청을 장기간 읽어도 누적 body 저장 없음, 종료 후 socket/session/task 참조 및 취소된 deadline task 회수.

공개 API/설정, 실행기와 수명, case와 point, 후속 예외/응답, listener 체인 및 통합 테스트를 구현했다. 설정, 실행 예제, enum 추가법, 이벤트 및 종료 의미는 [README.md](README.md)에 정리한다.

## 13. 구현과 검토 기록 (2026-10-22)

- 독립 Java 17 Maven 모듈을 구현했다. 런타임 외부 의존성 없이 JDK 소켓과 플랫폼 스레드를 사용한다.
- 단일 listener도 불변 체인으로 정규화한다. listener/로깅 Exception은 각각 격리하고 다음 listener를 호출하며 서버의 상태에는 영향을 주지 않는다.
- 연결별 ClientSession이 자원을 소유한다. context는 worker 전용이며 읽기 전용 chunk view를 제공한다. 실제 소켓 옵션 snapshot 및 point 이름을 이벤트에 기록한다.
- 서비스 종료 coordinator가 모든 연결의 정리를 요청하고 작업 회수 및 마지막 이벤트 전달을 기다린다. callback이 지연되면 caller는 제한 시간 내 실패를 받으며 정리는 계속된다.
- 독립 리뷰에서 시작 callback 대기가 stop 제한 시간 밖에 있는 문제와 전송 성공 후 stop이 종료 원인을 덮어쓰는 문제를 재현했다. 회귀 테스트 2개가 수정 전 실패한 뒤 수정 후 통과했다.
- start 중 stop도 즉시 취소를 요청하도록 변경했다. 응답의 SENT/TIMEOUT terminal 결과는 후속 stop에도 보존하고 물리적인 소켓 정리는 별도로 수행한다.
- 테스트의 listener 예외 WARNING은 후속 listener 호출을 검증하기 위한 의도된 진단이다. README 전체 caller 예제는 실제 JDK 17로 컴파일·실행했다.
- 최종 Windows JDK 17.0.10 / Maven 3.9.11 `clean verify`: **27개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS**. 생성 JAR의 모든 class major version 61 및 JAR 단독 classpath의 README 예제 컴파일/실행을 확인했다. 다른 OS와 대규모 장기 부하 검증은 수행하지 않았다.
- 2026-10-22 사용자 push 요청에 따라 이 모듈과 관련 기술문서를 develop에 커밋하여 origin/develop에 반영한다. push 전 JDK 17 clean verify 27개 통과 및 원격 동기 상태를 재확인했다. 기존 다른 모듈의 사용자 변경은 제외한다.

## 14. 자원 누수 / NPE 분석 (2026-10-22)

- 상세: [resource-safety-review.md](resource-safety-review.md), 재현 코드: [analysis/SafetyProbe.java](analysis/SafetyProbe.java).
- 기존 27개 테스트를 재실행해 통과했고 JDK 17 독립 probe 14개 진단 항목을 확인했다. 정상 100응답 후 세션/예약 큐는 비었고 permit 64개 및 전체 executor 종료를 확인했다.
- 장애 주입으로 close worker 생성 실패 시 종료 coordinator 중단·정리 재시도 불가·열린 socket 잔존, client worker 생성 실패 시 미실행 세션/permit 잔존을 재현했다.
- 공개 API로 이벤트를 보관할 때 SendResponseException을 통해 응답 body가 보존되는 것도 확인했다. 1 MiB 응답 4건 후 세션은 0이나 이벤트가 body 4 MiB를 참조했다.
- 정상 입력의 내부 NPE는 확인하지 못했다. 필수 null 경계 17개와 listener/logger NPE 격리를 확인했다.
- 이번 작업은 분석 문서와 독립 재현 코드만 추가하며 운영 구현 수정·커밋·push는 수행하지 않았다.

## 자원 안전성 수정 적용 (2026-10-22)

분석 R1/R2/R3를 구현에 반영했다. 아래 최초 분석 내용은 수정 전 기록이며 현재 동작은 이 절을 따른다.

- worker 제출 실패 시 실행되지 않은 세션을 정확히 한 번 정리하고 소켓·permit을 반환한다.
- close executor 제출 실패 시 직접 close로 전환한다. interrupt 실패도 close 요청을 막지 않는다.
- 종료 단계별 오류를 수집하며 나머지 자원 정리를 계속한다. 종료 작업이 실패해 끝난 STOPPING 상태는 후속 stop으로 재시도한다. 모든 자원 정리 전 SERVICE_STOP을 발행하지 않는다.
- 이벤트의 Throwable은 원본이 아닌 ThrowableSnapshot이다. originalType(), 메시지, stack, cause/suppressed, closeDelay 및 응답 메타데이터만 복사하고 원본 제어 예외·응답 본문을 참조하지 않는다. 최대 깊이 8, 노드 64, 노드별 suppressed 8개, stack 64개, 메시지 4096자로 제한하며 순환·중복 참조는 생략한다.
- 기존 필수 null 거부와 listener/logger 예외 격리 계약은 유지한다. 이벤트의 nullable 필드는 사용자가 확인해야 한다.
- 회귀 테스트 5개 추가. 전체 32개 테스트와 독립 SafetyProbe 14개 검사항목 통과. 프로브는 정상 100응답 회수, worker/closer 생성 실패, 이벤트 참조 제거, null 경계를 확인한다.
- 독립 코드 리뷰에서 중요한 추가 결함 없음. Windows JDK 17 환경 검증이며 실제 OS 자원 고갈·장기 부하는 검증하지 않았다. 커밋/push는 수행하지 않았다.

- 2026-10-22 후속 push 요청: 자원 안전성 수정 R1/R2/R3와 관련 문서를 develop에 커밋하여 origin/develop에 반영한다. 테스트 32개와 안전성 프로브 14개를 확인했으며 다른 모듈의 미커밋 변경은 제외한다.
