# HTTP Client Executor

Java 17용 단일 Maven 라이브러리다. HT5(Apache HttpClient 5), Netty, Java socket 커넥터를 선택해 같은 API로 요청을 보내고 요청·응답 본문, 실행 이력, HTTP/1.1 전송 원본을 조회한다.

## 의존성과 빌드

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>http-client-executor</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

하위 Maven 모듈은 없다. Apache·Netty와 기존 payload-generator는 전이 의존성으로 포함된다. Java 커넥터 선택은 JDK socket 전송을 뜻하며 classpath에서 다른 라이브러리가 제거되는 의미는 아니다. 일반 JAR이며 실행 JAR/fat JAR은 생성하지 않는다. 애플리케이션의 SLF4J 구현을 사용하고 라이브러리는 별도 logging binding을 강제하지 않는다.

```powershell
mvn -f http-payload-generator/pom.xml "-Dmaven.test.skip=true" install
mvn -f http-client-executor/pom.xml clean verify "-Dio.netty.leakDetection.level=paranoid"
mvn -f http-client-executor/pom.xml "-Dmaven.test.skip=true" install
```

PowerShell에서는 `-D` 인자를 따옴표로 감싼다. package에서 sources JAR을 생성·첨부하고 프로젝트 루트 Markdown을 `META-INF/docs/http-client-executor/`에 포함한다. install은 명시적으로 테스트 실행·컴파일을 생략하며 검증은 verify에서 수행한다.

## 기본 실행

```java
import java.net.URI;
import java.nio.charset.StandardCharsets;
import com.inzent.apim.utilities.http.client.HttpClientExecutor;
import com.inzent.apim.utilities.http.client.HttpClientManager;
import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectMethod;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.RequestBody;

try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.HT5)
         .location(URI.create("http://localhost:8080/api"))
         .path("/orders")
         .method(ConnectMethod.POST)
         .build();
     ExecutionContext context = ExecutionContext.builder(executor)
         .body(RequestBody.bytes("application/json", "{}".getBytes(StandardCharsets.UTF_8)))
         .header("X-Request-ID", "sample-1")
         .build()) {
    context.execute().throwIfFailed();
    int status = context.response().status().code();
    byte[] response = context.response().body().bytes(1024 * 1024);
}
```

executor는 재사용·동시 실행할 수 있다. context는 실행마다 새로 생성하고 사용 후 닫는다. HTTP 4xx/5xx도 응답 수신 성공이다. 연결·프로토콜·저장 오류는 context.failure()에 기록하며 `throwIfFailed()`로 예외를 받을 수 있다. 일반 실행 오류에서는 execute가 같은 context를 반환한다. JVM Error와 이미 실행/종료한 context의 재실행은 직접 예외를 던진다.

전체 URL은 `ExecutionContext.builder(executor).url(URI)`로 지정한다. URL을 지정하면 executor location/path를 사용하지 않으며 context path와 함께 지정할 수 없다. URL 생략 시 base path와 요청 path의 경계 `/`를 하나로 합친다. `/api/` + `/orders?q=x%20y`는 `/api/orders?q=x%20y`가 되고 percent encoding을 보존한다.

## 설정과 커넥터 선택

manager 기본 설정 → `builder.connectionConfigure(...)` 전체 대체 → builder 필드 override 순서다. 생성된 executor마다 독립 snapshot과 전용 pool을 사용한다.

```java
ConnectionConfigure defaults = ConnectionConfigure.builder()
    .maxConnTotal(100)
    .maxConnPerRoute(20)
    .connectTimeout(java.time.Duration.ofSeconds(5))
    .executionTimeout(java.time.Duration.ofSeconds(60))
    .build();

// manager.executorBuilder(ConnectorType.NETTY)
//     .connectionConfigure(defaults)
//     .nettyOptions(new NettyOptions(2))
//     .configure(builder -> builder.maxConnPerRoute(10))
//     .build();
```

`Ht5Options(reactorThreads)`, `NettyOptions(eventLoopThreads)`, `JavaOptions(readBufferSize)`는 `.config` 패키지에 있다. 다른 커넥터의 옵션 주입은 거부한다. HT5 reactor 옵션은 HTTP/2 async 경로에 적용하며 HTTP/1.1은 호출 스레드에서 동작한다.

| 설정 | 기본값 |
|---|---|
| connect / pool acquire / TLS handshake | 5초 / 5초 / 10초 |
| response idle / execution / shutdown | 30초 / 60초 / 5초 |
| maxConnTotal / maxConnPerRoute | 100 / 20 |
| maxConcurrentStreamsPerConnection (HTTP/2) | 100 |
| leaseOrder / idleTimeout / timeToLive | LIFO / 30초 / 5분 |
| tcpNoDelay / TCP keepAlive | true / true |
| TLS protocols / hostname 검증 | TLSv1.3,TLSv1.2 / 활성 |
| memoryThreshold / maxStoredBytes | 1 MiB / store별 64 MiB |
| maxInterceptorBodyBytes | 16 MiB |

timeout은 양수, 최대 365일이다. 풀 제한은 executor별 물리 연결 수이며 raw 연결도 포함한다. FIFO/LIFO는 idle 연결 선택 순서이며 대기 스레드의 공정성을 보장하지 않는다. idle/TTL은 다음 acquire 시 검사한다. 자동 재시도, 리다이렉트, cookie 저장, 응답 자동 decompress는 수행하지 않는다.

### HTTP/2 multiplex

HT5와 Netty 모두 하나의 HTTP/2 연결에서 여러 context를 동시에 실행할 수 있다. 각 스레드에서 별도 context를 생성한다. `maxConnTotal`/`maxConnPerRoute`는 물리 연결 상한이고, `maxConcurrentStreamsPerConnection`은 연결별 동시 실행 상한이다.

```java
ConnectionConfigure h2Settings = ConnectionConfigure.builder()
    .sslContext(clientSslContext)
    .maxConnTotal(1)
    .maxConnPerRoute(1)
    .maxConcurrentStreamsPerConnection(8)
    .poolAcquireTimeout(java.time.Duration.ofSeconds(5))
    .build();
// manager.executorBuilder(ConnectorType.HT5) 또는 NETTY
//     .version(ConnectVersion.HTTP_2)
//     .build();
```

실제 동시 stream 수에는 서버의 `SETTINGS_MAX_CONCURRENT_STREAMS`도 적용한다. stream 슬롯 대기는 poolAcquireTimeout에 포함하고, 대기 중 취소·시간 만료된 요청을 나중에 전송하지 않는다. 서버가 상한을 줄여도 기존 stream은 유지하고 새 요청의 입장을 제한한다. GOAWAY/TTL로 draining 중인 연결에는 새 stream을 배정하지 않고 기존 실행을 마무리한다.

한 실행의 취소·응답 idle timeout·응답 저장 한도·중간 응답 개수 제한 초과는 해당 stream을 중단하며 같은 연결의 다른 실행을 유지한다. 연결 자체의 장애나 executor 종료는 공유 실행들에 영향을 줄 수 있다. HTTP/1.1은 계속 exclusive lease를 사용한다.

HT5는 Apache HttpClient 5.6.5의 full async route와 HttpCore 5.5를 사용한다. HttpCore 5.5는 송신 종료 후 응답 대기 중인 stream 취소를 즉시 reset하는 수정 때문에 명시적으로 고정했다. 임의의 낮은 HttpCore 버전으로 override하지 않는다.

### TLS/mTLS와 proxy

`ConnectionConfigure.builder().sslContext(SSLContext)`로 trust/key manager를 구성한 context를 주입한다. mTLS는 client key manager를 포함한 SSLContext를 사용한다. 주입 후 SSLContext를 변경하지 않는다. hostname 검증은 유지한다.

```java
// import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
ConnectionConfigure settings = ConnectionConfigure.builder()
    .sslContext(clientSslContext)
    .proxy(ProxyConfigure.basic("proxy.example", 8080, "user", "password"))
    .build();
```

인증 없는 proxy는 `ProxyConfigure.http(host, port)`다. HTTP 목적지는 absolute-form, HTTPS 목적지는 CONNECT 후 목적지와 TLS/mTLS를 수행한다. `Proxy-Authorization` 직접 헤더 주입은 금지하고 전용 설정을 사용한다. HTTPS proxy 자체의 TLS, SOCKS, NTLM/Kerberos는 제공하지 않는다.

HTTP/2에서도 HT5·Netty 모두 같은 proxy 설정과 `.localAddress(InetAddress)`를 지원한다. proxy와 localAddress를 함께 지정하면 해당 로컬 주소에서 proxy에 TCP 연결하고, CONNECT 터널 안에서 목적지 TLS/ALPN/mTLS를 수행한다. proxy 인증 헤더는 목적지 HTTP/2 요청에 전달하지 않는다.

## Payload Generator

```java
// import com.inzent.apim.utilities.http.client.payload.PayloadBody;
// import com.inzent.apim.utilities.http.payload.*;
PayloadBody body = new PayloadBody(new DefaultHttpPayloadGenerator(), false,
    ContentType.APPLICATION_JSON, ContentEncoding.GZIP, 1024, 32);
// ExecutionContext.builder(executor).body(body).build();
```

contentLength는 압축 전 길이, blockSize는 생성기 value 블록 크기다. callback 안에서 헤더와 stream을 실행 소유 store로 복사한다. generator의 stream을 callback 이후 읽지 않는다. `chunked=true`는 HTTP/1.1에서만 사용하며 chunk framing은 전송 계층이 작성한다. 사용자 헤더는 같은 이름의 generator 기본 헤더 목록을 대체한다.

## 본문 변경과 원본 기록

```java
// import com.inzent.apim.utilities.http.client.interceptor.ClientHandleInterceptor;
// import com.inzent.apim.utilities.http.client.model.CaptureLevel;
try (ExecutionContext context = ExecutionContext.builder(executor)
    .body(RequestBody.bytes("text/plain", new byte[] {65, 66}))
    .captureLevel(CaptureLevel.HTTP1_PLAINTEXT_BYTES)
    .interceptor(new ClientHandleInterceptor() {
        @Override public byte[] contentGenerated(byte[] content) {
            return new byte[] {65, 66, 67, 68, 69};
        }
    }).build()) {
    context.execute();
    // 기본 AUTO + HTTP/1.1: Content-Length는 2, 실제 본문은 5 bytes 그대로 전송.
    byte[] wire = context.requestCapture().bytes(1024 * 1024);
}
```

hook가 배열을 반환하면 이를 그대로 사용한다. null이면 전달된 배열을 유지하며 배열 내부 수정도 반영한다. hook 이후 방어 복사하며 재압축·padding·truncation·길이 헤더 보정을 하지 않는다. 배열 hook가 있는 본문은 입력과 반환값 모두 16 MiB 상한을 적용하며 초과하면 전송 전에 실패한다.

`WireMode.AUTO`는 HTTP/1.1에서 interceptor가 있으면 raw로 선택한다. 알림만 구현한 interceptor도 raw를 선택한다. 정상 검증과 풀 재사용이 필요하면 `.wireMode(WireMode.STANDARD)`를 명시한다. STANDARD에서는 길이 불일치를 전송 전 거부한다. `.wireMode(WireMode.RAW_HTTP1)`로 raw를 직접 지정할 수도 있다. raw는 단발 연결을 사용하고 세 커넥터 모두 지원한다.

서버가 짧은 본문의 나머지를 기다리면 timeout까지 응답하지 않을 수 있다. `.shutdownOutput(true)`는 마지막 write 후 출력 종료를 시도한다. TLS/서버의 종료 처리에 따라 응답 가능 여부가 달라지며 오류 status 수신 자체를 보장하지 않는다.

`CaptureLevel` 기본값은 LOGICAL이고 raw bytes는 명시적으로 켠다. 기록은 TLS 암호화 전 HTTP/1.1 애플리케이션 bytes이며 TCP packet/암호화 record/서버 도달 증거가 아니다. write 실패 시 기록은 성공적으로 write가 반환된 prefix이며 실제 원격 도달량은 알 수 없다. capture도 maxStoredBytes를 넘으면 실행 실패한다. 원본은 인증 헤더·본문을 포함하므로 기본 로그에 출력하지 않는다. 헤더 요약이 필요하면 `.redacted()`를 사용한다.

### Java TLS에서 본문 일부를 보내고 서버 종료 기다리기

[JavaTlsPartialBodySampleTest](src/test/java/com/inzent/apim/utilities/http/client/JavaTlsPartialBodySampleTest.java)는 임시 인증서로 루프백 TLS 서버를 열고 Java 커넥터로 한 번 연결한다. 프로젝트 루트에서 다음 명령으로 실행한다.

```powershell
mvn -f http-client-executor/pom.xml "-Dtest=JavaTlsPartialBodySampleTest" test
```

클라이언트는 HTTP/1.1 POST 본문을 `ABCDEFGH`로 준비한다. `RAW_HTTP1`에서 `contentGenerated`가 앞 3바이트만 반환하므로 서버는 `Content-Length: 8`과 본문 `ABC`를 받는다. 이 예제에서 지정하는 단위는 HTTP 본문 바이트이며 TCP 패킷 수나 TLS 레코드 경계는 전송 계층이 결정한다.

`shutdownOutput(false)`로 송신 측을 열어 둔다. 서버는 250ms 동안 다음 바이트와 EOF가 오지 않는 것을 확인한 뒤 HTTP 응답 없이 연결을 닫는다. 그러면 `execute()`가 반환되고 `context.failure()`에 `EOFException`이 기록되면 테스트가 통과한다. 클라이언트의 응답 대기 한도는 5초, 전체 실행 한도는 10초다.

## 이벤트·이력·종료

`ClientHandleInterceptor`의 `contentGenerated`, `connectResult`, `requestSent`, `responseReceived`를 구현한다. 정상 순서는 본문 준비 → 연결 결과 → 송신 완료 → 최종 응답 수신이다. 재사용은 ConnectResult.reused로 표시한다. 준비 실패에는 연결 이벤트가 없고 연결 시도 실패에는 실패 결과가 온다. 송신 완료는 원격 처리 완료가 아니다. 부분 응답에는 responseReceived가 오지 않는다.

HTTP/1.1은 송신과 수신을 병행한다. 업로드 도중 서버가 완전한 최종 응답을 반환하면 송신을 중단하고 응답을 보존한다. 이 경우 requestSent가 생략될 수 있고 연결을 재사용하지 않는다.

hook 예외는 실행을 실패시킨다. 나머지 알림의 RuntimeException은 interceptorFailures에 보관하고 전송을 계속한다. 콜백은 execute 호출 스레드에서 수행하며 사용자 코드를 I/O event loop에서 호출하지 않는다. 동일 executor/context의 close를 콜백에서 재진입하지 않는다. 공유 interceptor는 사용자 책임으로 thread-safe하게 작성한다.

`context.history()`는 순번·Instant·단조 시간 경과·stage/outcome을 제공한다. 네트워크 세부 DNS/TLS 구간 시간을 측정한 값으로 제공하지는 않는다. `response.complete()`로 부분 응답을 구분하고 `failure()`와 함께 조사한다.

`cancel()`은 다른 스레드에서 호출할 수 있으며 active socket/channel/stream을 닫는다. interrupt는 취소로 처리하고 interrupt flag를 유지한다. context의 close는 execute 반환 뒤 수행한다. context가 본문·capture 임시 파일을, executor가 pool·Netty worker·Apache client·deadline timer를 소유한다. manager는 생성한 executor를 닫는다. 사용자 hook/generator의 무한 대기를 강제 중단하지는 못한다.

## 지원 범위

| 기능 | HT5 | Netty | Java |
|---|---|---|---|
| HTTP/1.1, HTTPS, mTLS | 지원 | 지원 | 지원 |
| HTTP/1.1 raw 길이 불일치·원본 기록 | 지원 | 지원 | 지원 |
| HTTP proxy·HTTPS CONNECT (HTTP/1.1) | 지원 | 지원 | 지원 |
| HTTPS ALPN HTTP/2 | 지원 | 지원 | 미지원 |
| HTTP/2 multiplex / 서버 stream 상한 | 지원 | 지원 | 미지원 |
| HTTP/2 CONNECT proxy·Basic 인증·localAddress | 지원 | 지원 | 미지원 |
| HTTP/2 원본 bytes·raw framing | 미지원 | 미지원 | 미지원 |

HTTP/2는 정상 헤더·본문의 논리 기록을 제공하고 요청 버전을 강제한다. h2c와 HTTP/3는 제공하지 않는다. HTTP/1.1 serializer/parser는 공통 코드이며 HT5는 Apache managed writer로 요청을 직렬화하고, Netty는 NIO channel로 bytes를 전송한다. HTTP/1.1 응답 parser는 세 커넥터가 공통 사용한다.

상세 구조와 검증 결과는 [project.md](project.md), 초기 설계와 변경 전 계약은 [design.md](design.md)에 정리한다.

## CPU·리소스·NPE 점검

분석 근거와 수정 사항은 [safety-analysis.md](safety-analysis.md)에 정리했다. 활성 실행의 interrupt 감시는 executor당 하나이며, 완료된 요청의 타이머 작업과 개별 close한 executor의 manager 등록은 즉시 제거한다.

custom `RequestBody.prepare`는 null 대신 빈 `HttpHeaders`를 반환한다. null이면 준비 단계 IOException이 `context.failure()`에 기록된다. 성공·실패 모두 context를 try-with-resources로 닫고, body에서 연 입력 스트림은 context보다 먼저 닫는다. 파일 삭제 실패 시 close 예외를 처리한 후 다시 close할 수 있다.
