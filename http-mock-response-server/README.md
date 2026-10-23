# HTTP Mock Response Server

Java 17용 독립 유틸리티 모듈이다. enum으로 TCP 연결의 mock 절차를 정의하고 HTTP 형식의 응답, 느린 읽기, 연결 종료를 재현한다. 외부 런타임 의존성은 없다.

## 빌드 및 의존성

저장소 루트에서 실행한다.

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-17.0.10'
mvn -f http-mock-response-server/pom.xml clean verify
mvn -f http-mock-response-server/pom.xml install
```

호출 프로젝트:

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>http-mock-response-server</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

모든 공개 타입은 `com.inzent.apim.utilities.http.mockserver` 패키지에 있다.

## 전체 실행 예제

아래를 `MockServerExample.java`로 저장한다. 기본 case 2개와 응답 case 1개를 등록하고, 실제 클라이언트로 응답을 읽은 다음 서버를 종료한다. 포트 0은 OS가 배정하며 SERVER_SOCKET_OPEN 이벤트에서 실제 포트를 확인한다.

```java
import com.inzent.apim.utilities.http.mockserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class MockServerExample {
    enum Point { SEND_JSON }
    enum ReplyCase implements MockScenario {
        JSON;
        @Override public void execute(MockClientContext context) throws Exception {
            context.runPoint(Point.SEND_JSON, () -> {
                throw new SendResponseException(
                    MockResponse.utf8(200, "application/json; charset=UTF-8", "{\"ok\":true}"));
            });
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger responsePort = new AtomicInteger();
        ServiceStatusListener logging = event ->
            System.out.println(event.type() + " " + event.mockCase() + " client=" + event.clientId());
        ServiceStatusListener ports = event -> {
            if (event.type() == ServiceEventType.SERVER_SOCKET_OPEN
                    && event.mockCase().equals("JSON")) {
                responsePort.set(event.localAddress().getPort());
            }
        };
        ServiceStatusListenerChain listeners = ServiceStatusListenerChain
            .of(logging).andThen(ports);

        try (MockServerExecutor server = new MockServerExecutor()) {
            InetAddress address = InetAddress.getLoopbackAddress();
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, address, 0, listeners);
            server.addMock(MockCase.SLOW_READ, address, 0,
                MockOptions.builder()
                    .readChunkSize(1024)
                    .readInterval(Duration.ofMillis(200))
                    .receiveBufferSize(4096)
                    .lingerSeconds(-1)
                    .build(), listeners);
            server.addMock(ReplyCase.JSON, address, 0, listeners);
            server.start();

            try (Socket client = new Socket(address, responsePort.get())) {
                client.setSoTimeout(3000);
                String response = new String(client.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                System.out.println(response);
                if (!response.endsWith("{\"ok\":true}")) {
                    throw new IllegalStateException("Unexpected response");
                }
            }
            server.stop();
        }
    }
}
```

이 예제의 JSON case는 요청을 기다리지 않고 연결 직후 응답한다. 고정 포트가 필요하면 각 0을 서로 다른 포트 번호로 바꾼다. `start()` 반환 시 모든 바인딩이 열려 있으며 caller thread는 테스트를 계속할 수 있다.

## 기본 case

| enum | 동작 |
| --- | --- |
| `WAIT_FOR_CLIENT_DISCONNECT` | 수신 데이터를 버리며 입력 EOF까지 대기하고 종료. 응답 없음 |
| `SLOW_READ` | 최대 readChunkSize씩 읽고 readInterval만큼 기다린 뒤 다음 read. 응답 없음 |

TCP는 byte stream이므로 read chunk는 송신 write 또는 네트워크 패킷 경계와 일치하지 않는다. 지연은 애플리케이션의 읽기 사이에 적용되며 OS가 미리 받는 데이터까지 지연시키지는 않는다. EOF에는 상대의 출력 half-close도 포함한다. 아무 FIN/RST도 없는 네트워크 단절은 기본 무제한 읽기에서 즉시 감지하지 못한다.

## enum 및 point 확장

enum 상수의 `execute`에 절차를 정의한다. enum에 연결별 mutable 상태를 저장하지 않는다. `MockClientContext`는 해당 연결 worker에서만 접근하며 socket을 직접 노출하지 않는다.

context는 연결 실행 수명에 한정된다. 마지막 CLIENT_DISCONNECTED callback 전달이 끝나면 session·worker·읽기 버퍼·HTTP decoder 참조를 해제하고 모든 public context 연산을 IllegalStateException으로 거부한다. 연결 종료 후 사용할 정보는 실행 중 `HttpProtocolResult`/`HttpRequestSnapshot`이나 이벤트의 값으로 저장한다. 반환된 snapshot은 context 종료 후에도 유효하며, 저장한 snapshot의 본문은 저장 기간 동안 유지된다. stop timeout으로 callback이 아직 실행 중이면 그 실행이 끝난 뒤 정리한다.

```java
enum UploadPoint { INSPECT_CHUNK }
enum UploadCase implements MockScenario {
    CLOSE_AFTER_FIRST_CHUNK;
    @Override public void execute(MockClientContext context) throws Exception {
        context.runPoint(MockPoint.ON_CONNECTED);
        context.runPoint(MockPoint.BEFORE_READ);
        context.runPoint(MockPoint.READ_CHUNK);
        if (context.lastRead() < 0) {
            context.runPoint(MockPoint.ON_PEER_EOF);
            return;
        }
        context.runPoint(UploadPoint.INSPECT_CHUNK, () -> {
            // chunk()는 읽기 전용 view다. 다음 read 이후 보관하려면 복사한다.
            System.out.println("received=" + context.chunk().remaining());
            throw new CloseClientException(Duration.ofSeconds(2));
        });
    }
}
```

`runPoint(Enum<?>, PointOperation)`은 기본 MockPoint 또는 사용자 enum 상수를 받는다. 현재 point는 AtomicReference의 getAndSet으로 전환하며, 중첩 point 종료 시 예외 여부와 관계없이 이전 값으로 복원한다. POINT_ENTER를 보내고 정상 완료에만 POINT_EXIT를 보낸다. 전파되는 예외 이후 point는 실행하지 않는다. context의 `readChunk()`, `delay(Duration)`, `lastRead()`, `totalBytes()`, `options()`, `clientId()`를 사용할 수 있다.

기본 동작은 `MockClientContext.runPoint(MockPoint)`에서 분기한다. ON_CONNECTED/BEFORE_READ/ON_CHUNK_RECEIVED/ON_PEER_EOF는 취소 확인, READ_CHUNK는 raw 읽기, DELAY_BEFORE_NEXT_READ는 readInterval 지연, READ_HTTP_PROTOCOL은 HTTP 해석 읽기를 수행한다. 이 overload는 point 전환·POINT_ENTER/POINT_EXIT·중첩 복원을 함께 처리하므로 별도 runPoint로 감쌀 필요가 없다. 이전 MockPoints 클래스는 제거했다. `context.runPoint(point, () -> MockPoints.execute(point, context))`는 `context.runPoint(point)`로 변경해 재컴파일한다. 사용자 정의 동작은 기존 `runPoint(Enum<?>, PointOperation)`을 사용한다. HTTP 실행 결과는 `context.httpProtocol()`에서 조회한다.

기존 `runPoint("name", ...)`는 `runPoint(MyPoint.NAME, ...)`로 변경한다. `context.point()`와 `event.point()`도 `Enum<?>`를 반환한다. 비교는 `event.point() == MockPoint.READ_CHUNK`, 문자열 출력은 null 확인 후 `event.point().name()`을 사용한다. 이 변경은 기존 String 기반 API와 소스/바이너리 호환되지 않으므로 호출 코드를 수정해 재컴파일한다. AtomicReference는 point 전환을 관리하며 context I/O의 worker 전용 접근 계약은 유지한다.

| point 결과 | 동작 |
| --- | --- |
| 정상 반환 | 다음 point 수행 |
| `CloseClientException()` | 의도적 지연 없이 close |
| `CloseClientException(Duration)` | 지정 시간 후 close, stop은 지연 취소 |
| `SendResponseException(MockResponse)` | HTTP 응답 write/flush 후 close |
| 다른 Exception | EXCEPTION_RECEIVED 후 해당 연결 종료 |
| 미지원 NextActionException 하위 타입 | 제어 예외 및 미지원 오류 이벤트 후 해당 연결 종료 |

`closeDelay`는 close 호출 **전** 대기 시간이다. SO_LINGER는 close 자체의 OS 동작이다. 일반 close가 TCP RST를 보장하지는 않는다.

## HTTP 요청 단계별 수신

`readHttpProtocol()`은 한 번 소켓을 읽고 해당 read의 모든 bytes를 해석하여 `HttpProtocolResult`를 반환한다. `httpProtocol()`은 추가 I/O 없이 마지막 결과를 조회한다. 한 read로 요청이 끝나지 않으면 다음 호출에 해석 상태를 이어간다. 한 read에 여러 요청이 들어 있으면 모두 해석한다.

```java
enum HttpCase implements MockScenario {
    RECEIVE_AND_REPLY;
    @Override public void execute(MockClientContext context) throws Exception {
        while (true) {
            context.runPoint(MockPoint.READ_HTTP_PROTOCOL);
            HttpProtocolResult result = context.httpProtocol();
            for (HttpRequestSnapshot request : result.completedRequests()) {
                System.out.println(request.initialLine());
                System.out.println(request.headers());
                System.out.println("content bytes=" + request.contentBytes());
                System.out.println(request.trailers());
            }
            if (!result.completedRequests().isEmpty()) {
                throw new SendResponseException(MockResponse.utf8(200, "text/plain", "received"));
            }
            if (result.endOfInput()) {
                if (result.truncated()) throw new java.io.EOFException("Incomplete HTTP request");
                return;
            }
        }
    }
}
```

`result.request()`는 마지막으로 해석 중인 요청의 snapshot이다. 마지막 요청이 완료되면 그 완료 snapshot을 유지한다. `completedRequests()`는 **이번 read에서 새로 완료한 요청**만 포함하며 다음 read/EOF 결과에는 과거 완료 목록을 누적하지 않는다. EOF에서는 `endOfInput()`이 true이고 부분 요청이면 `truncated()`도 true다. 필요하면 위 예제처럼 EOFException으로 종료한다.

| 조회 | 의미 |
| --- | --- |
| `request.initialLineState()` | initial line 수신 단계 |
| `request.headerState()` | header section 수신 단계 |
| `request.contentState()` | content 수신 단계 |
| `request.trailersState()` | trailers(trails) 수신 단계 |
| `request.complete()` | 모든 필수 단계 완료 및 해당없음 단계 확인 |

각 단계는 `NOT_REACHED`(미도달), `RECEIVING`(수신중), `COMPLETE`(수신 완료), `NOT_APPLICABLE`(해당없음)이다. 해당 단계의 첫 byte가 들어오면 RECEIVING으로 바뀐다. initial line/header/trailers의 CRLF도 단계 bytes에 포함한다.

- 명시적 Content-Length 0 또는 Content-Length/Transfer-Encoding 없는 요청은 content와 trailers가 NOT_APPLICABLE이다.
- 고정 길이 본문은 정확한 바이트 수 수신 후 COMPLETE, trailers는 NOT_APPLICABLE이다.
- chunked는 chunk size/data/종료 CRLF를 해석한다. 0 chunk 수신 후 content COMPLETE, trailers는 마지막 빈 줄 수신 후 COMPLETE다. 빈 chunked 본문과 빈 trailers도 해당 단계가 존재하므로 COMPLETE다.
- header/trailer 이름은 소문자이며 중복 값은 List로 보존한다. 본문은 binary byte[]이고 chunk framing을 제거한다. Content-Encoding 압축 해제나 JSON 등의 내용 해석은 하지 않는다.
- snapshot은 불변이며 다른 스레드로 전달할 수 있다. 본문은 완료된 8 KiB 블록을 공유하고 snapshot 생성 시 마지막 부분 블록만 복사한다. `content()` 호출 시 새 배열을 만들고 `contentBytes()`는 복사 없이 길이를 조회한다. 변경되지 않은 헤더/트레일러도 불변 맵으로 공유한다. snapshot을 계속 보관하면 그 본문도 보관된다.

첫 읽기에서 `readHttpProtocol(new HttpProtocolLimits(8192, 65536, 16 * 1024 * 1024))`로 상한을 지정할 수 있다. 기본값은 line 8 KiB(CRLF 포함), headers와 trailers 각각 64 KiB, decoded content 16 MiB다. 요청별 chunk framing은 header 한도의 16배로 제한한다. line/header 한도는 양수, content 한도는 0 이상이다. 한 연결에서 한도를 변경할 수 없으며 이후에는 인자 없는 read를 호출해도 최초 한도를 유지한다.

HTTP/1.0·HTTP/1.1 요청과 엄격한 CRLF를 지원한다. HTTP/1.1은 단일 nonempty Host가 필요하다. 동일한 Content-Length 반복/목록은 허용하며, 다른 길이·TE와 CL 동시 지정·chunked 이외 transfer coding·잘못된 framing·한도 초과는 IOException으로 처리한다. parser 실패 후 다시 읽기는 소켓 접근 전에 거부한다. 오류 응답을 자동 생성하지 않으므로 필요하면 사용자 scenario가 예외를 처리한다.

기본 WAIT_FOR_CLIENT_DISCONNECT/SLOW_READ는 원래의 raw read를 유지한다. 읽기 모드는 `AtomicReference<ReadMode>`의 compareAndExchange로 최초 선택 시 원자적으로 확정한다. 같은 모드의 반복 읽기는 허용하고 다른 모드로 변경하려는 호출은 기존 모드를 유지한 채 IllegalStateException으로 거부한다. 같은 연결에서 `readChunk()`와 `readHttpProtocol()`을 혼용할 수 없다. context I/O의 worker 전용 접근 계약은 유지한다. `REQUEST_RECEIVED`는 raw bytes 읽기 직후의 이벤트이며 HTTP 해석/완료 이벤트가 아니다. 단계 상태는 read의 반환 결과에서 확인한다. 요청 목록 해석은 지원하지만 실행기의 응답 후 close 계약은 그대로다.

## listener 체인

```java
ServiceStatusListenerChain chain = ServiceStatusListenerChain
    .of(loggingListener, metricsListener)
    .andThen(testObserver);
```

- 등록 순서대로 동일 이벤트를 각 listener에 전달한다.
- 각 호출의 Exception을 catch하고 System.Logger로 기록한 다음 후속 listener를 실행한다.
- 로깅 자체의 Exception도 격리한다. listener 예외를 실행기에 전파하거나 연결 종료/start rollback으로 바꾸지 않는다.
- 동일 체인에 EXCEPTION_RECEIVED를 재귀 전송하지 않는다.
- 체인은 불변이다. `andThen`은 새 체인을 반환하고 중첩 체인은 펼친다. 중복 등록은 등록 횟수만큼 호출한다.
- `of()`는 빈 체인이다. 단일 listener를 addMock에 전달해도 내부 체인으로 감싼다.
- 동기 호출이므로 listener는 빠르게 반환해야 한다. 같은 연결의 이벤트는 순서대로 전달하며 서로 다른 연결에서는 동시에 호출될 수 있다.
- callback과 mock point에서 실행기의 addMock/start/stop/close 호출은 거부한다. 별도 caller thread에서 제어한다.
- Exception 격리는 무한 대기나 Error 복구를 의미하지 않는다. Error는 정리 후 전파한다.

## 이벤트

`ServiceEvent`는 시각, 등록 ID, case명, client ID, 주소, point, 읽기 바이트 수와 누계, 종료 원인, 예외, 응답 메타데이터, 소켓 옵션 snapshot을 담는다. 해당하지 않는 값은 null이며 socketOptions는 불변 Map이다. 예외 참조는 진단 목적으로만 읽고 변경하지 않는다.

| 이벤트 | 의미 |
| --- | --- |
| SERVICE_START | 등록별 시작 준비 |
| SERVER_SOCKET_OPEN | bind 완료, 실제 주소/포트 및 서버 옵션 |
| CLIENT_CONNECTED | accept 후 세션 등록, client 옵션 적용 전 |
| POINT_ENTER / POINT_EXIT | point 진입 / 정상 반환 |
| REQUEST_RECEIVED | 양수 read마다 발생, HTTP 요청 완료가 아님 |
| RESPONSE_SEND / RESPONSE_SENT | 응답 write 직전 / write·flush 성공 |
| EXCEPTION_RECEIVED | point/action/I/O 오류 또는 제어 예외, `action()`으로 구분 |
| CLIENT_DISCONNECTED | 세션 종료, 원격 EOF/서버 종료 등 `reason()`으로 구분 |
| SERVER_SOCKET_CLOSED / SERVICE_STOP | 바인딩 및 서비스 종료 |

client 옵션의 실제 값은 옵션 적용 이후 POINT_ENTER부터 확인할 수 있다. `socketOptions().get("requested")`는 요청한 MockOptions이며 나머지는 OS getter로 조회한 값이다. OS 버퍼 값은 요청값과 다를 수 있다. RESPONSE_SENT는 클라이언트 애플리케이션이 읽었다는 확인이 아니다.

## 설정

`MockOptions.builder()`는 등록별 옵션을 구성한다.

| 메서드 | 기본값 / 범위 |
| --- | --- |
| reuseAddress(boolean) | true, bind 이전 적용 |
| backlog(int) | 50, 양수 |
| serverReceiveBufferSize(Integer) | null(OS 기본), 지정 시 양수 |
| receiveBufferSize(Integer), sendBufferSize(Integer) | null(OS 기본), 지정 시 양수 |
| lingerSeconds(int) | -1(disabled), -1~65535 |
| keepAlive(boolean), tcpNoDelay(boolean) | false, true |
| readTimeoutMillis(int) | 0(무제한), 0 이상 |
| readChunkSize(int) | 1024, 양수 |
| readInterval(Duration) | 100 ms, 0 이상 |
| responseTimeout(Duration) | 10초, 양수 |

리스닝 소켓에는 reuseAddress와 serverReceiveBufferSize를 적용한다. linger와 sendBufferSize 등은 accept한 client Socket에 적용한다. 버퍼 요청이나 옵션 적용 실패는 오류로 보고한다. read timeout은 해당 연결을 종료한다.

`new ServerOptions(maxClients, shutdownTimeout)`으로 실행기 전체 한도를 설정한다. 기본값은 64개 연결과 종료 대기 5초다. 연결 대기열은 두지 않으며 수용 초과 연결을 CAPACITY_REJECTED 원인으로 종료한다. Duration은 나노초 변환 범위 안이어야 한다.

## 응답 및 종료 계약

`MockResponse.utf8(status, contentType, body)` 또는 `new MockResponse(status, contentType, byte[])`를 사용한다. `new MockResponse(byte[])`는 200/application/octet-stream이다. byte[]는 입력과 조회 모두 복사한다.

HTTP/1.1 상태행, Content-Type, 실제 byte 길이의 Content-Length, Connection: close 및 본문을 보낸다. 상태 코드는 200~599이고 204/205/304는 빈 본문만 허용한다. content type은 출력 가능한 ASCII만 허용하며 CR/LF는 거부한다. body는 메모리형이므로 대용량 응답 생성 시 메모리 크기를 고려한다.

blocking write의 제한 시간은 별도 scheduler가 socket close를 요청하여 적용한다. 부분 쓰기나 timeout에는 RESPONSE_SENT가 발생하지 않는다. 미소비 요청이 남은 상태의 조기 응답과 close는 상대에서 reset으로 관측될 수 있다.

- NEW에서만 등록한다. 여러 bind 중 하나라도 실패하면 모두 rollback한다.
- RUNNING의 중복 start, 종료 완료 후 중복 stop은 무동작이다.
- 종료한 실행기는 재시작하지 않는다. 새 실행기를 생성한다.
- stop은 모든 연결을 취소하고 read/write/지연을 해제한다. 강제 종료에는 closeDelay를 적용하지 않고 양수 linger를 비활성화한다.
- 종료 제한 시간이 지나면 MockServerStopException을 던지고 정리는 백그라운드에서 계속한다. 사용자 callback이 끝나면 후속 stop으로 종료를 확인할 수 있다.
- caller interrupt 시에도 취소를 요청하며 interrupt 상태를 복원한다.
- non-daemon 스레드를 소유하므로 반드시 stop 또는 try-with-resources의 close를 호출한다.

URL 라우팅, TLS, keep-alive 응답 처리, HTTP/2, HEAD 등 메서드별 응답 규칙 자동 적용은 제공하지 않는다. 요청 framing/파싱은 명시적 readHttpProtocol 경로에서 제공한다.

## 구성 문서

- [설계와 구현 구성](project.md)
- [구현 계획 및 검증 기록](implementation-plan.md)
- [메모리 누수 / NPE 재분석과 보완](resource-safety-review.md)

## 장애 처리와 이벤트 예외 조회

worker 생성 실패 시 미실행 세션과 연결 한도를 회수한다. 비동기 close 제출이 실패하면 소켓을 직접 닫는다. 종료 단계가 실패하면 다른 자원 정리를 계속하고 MockServerStopException으로 알린다. 실패 원인을 해소한 다음 stop()을 다시 호출하면 미완료 정리를 재시도한다.

ServiceEvent.exception()은 원본 예외 대신 ThrowableSnapshot을 반환한다. 원래 종류는 다음과 같이 조회한다. 기존 instanceof SendResponseException 등의 검사는 originalType() 비교로 변경해야 한다.

```java
if (event.exception() instanceof ThrowableSnapshot diagnostic) {
    String originalType = diagnostic.originalType();
    String message = diagnostic.getMessage();
    // diagnostic.getCause(), getSuppressed(), getStackTrace()
    // diagnostic.closeDelay(), response(): 해당 action이 아니면 null
}
```

스냅샷은 응답 본문을 보유하지 않는다. 응답 상태·content type·길이는 event.response() 또는 diagnostic.response()로 확인한다. 진단 그래프는 크기 제한이 있으며 순환·중복 참조를 생략한다. 리스너에서 스냅샷을 변경하지 않는다. 이벤트를 무제한 수집하면 메타데이터 자체는 계속 누적되므로 보관 개수/기간은 호출자가 제한한다.
