# HTTP Payload Generator

HTTP request/response 테스트용 dummy 본문과 Netty `HttpHeaders`를 생성하는 Java 21 라이브러리입니다.

## 빌드

```powershell
mvn -f http-payload-generator/pom.xml clean verify
mvn -f http-payload-generator/pom.xml install
```

호출 프로젝트의 Maven 의존성:

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>http-payload-generator</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Netty 4.1.139.Final, Brotli4j 1.23.0, zstd-jni 1.5.7-6을 사용합니다. Brotli4j는 Maven 실행 환경의 OS/아키텍처에 맞는 native 의존성을 선택합니다. 다른 플랫폼으로 배포할 때는 대상 플랫폼의 Brotli native artifact도 포함해야 합니다. Zstd 역시 지원되는 native 런타임이 필요합니다. 런타임을 사용할 수 없으면 해당 codec의 로딩 예외가 발생하며 무압축으로 대체하지 않습니다.

## API

패키지: `com.inzent.apim.utilities.http.payload`

```java
void ContentType.generate(long contentLength, long blockSize,
                          Consumer<InputStream> acceptor);

void HttpPayloadGenerator.generate(boolean chunked, ContentType contentType, ContentEncoding contentEncoding,
                                   long contentLength, long blockSize,
                                   BiConsumer<HttpHeaders, InputStream> acceptor);
```

`new DefaultHttpPayloadGenerator()`로 생성한 뒤 매 generate 호출에서 Content-Type을 지정합니다. 동일한 생성기로 서로 다른 타입을 생성할 수 있고 각 호출의 스트림, 헤더, 압축 상태는 독립적입니다. 기존 ContentType 생성자 인수는 generate의 두 번째 인수로 옮겨야 합니다. contentType이 null이면 자원 생성 전에 `NullPointerException("contentType")`으로 거부합니다.

| ContentType | Content-Type | 최소 양수 길이 |
|---|---|---:|
| TEXT_PLAIN | text/plain; charset=UTF-8 | 1 |
| APPLICATION_JSON | application/json; charset=UTF-8 | 2 |
| APPLICATION_XML | application/xml; charset=UTF-8 | 13 |
| TEXT_HTML | text/html; charset=UTF-8 | 26 |
| APPLICATION_FORM_URLENCODED | application/x-www-form-urlencoded; charset=UTF-8 | 1 |
| APPLICATION_OCTET_STREAM | application/octet-stream | 1 |

`ContentEncoding`은 Netty HTTP 압축 기능의 전체 코딩인 `GZIP`, `DEFLATE`, `BR`, `ZSTD`, `SNAPPY`와 `IDENTITY`를 제공합니다. `DEFLATE`는 zlib wrapper를 포함하며 `SNAPPY`는 framed Snappy입니다. IDENTITY에서는 Content-Encoding 헤더를 생략합니다. IANA에 등록된 모든 코딩(예: compress, dcb, dcz)을 지원한다는 의미는 아닙니다.

## 원문만 생성

```java
import com.inzent.apim.utilities.http.payload.ContentType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

ContentType.APPLICATION_JSON.generate(4096, 128, input -> {
    try {
        Files.copy(input, Path.of("dummy.json"));
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
});
```

`contentLength`는 압축 전 정확한 바이트 수입니다. ASCII dummy 데이터를 사용하므로 문자 수와 바이트 수가 같습니다. 음수 길이, 0 이하 blockSize, 최소 문서 길이 미만의 양수 길이는 `IllegalArgumentException`입니다. 길이 0은 모든 타입에서 빈 스트림이며 JSON/XML 문서로 파싱할 수 없습니다.

`blockSize`는 key/value 형식에서 value의 최대 길이입니다. `key0`, `key1`, ... 순서로 값을 생성하고 마지막 value는 남은 공간에 맞춰 짧아질 수 있습니다. JSON/XML/HTML에서는 문법을 유지하도록 남은 공간에 공백을 채우며 form에서는 `&`로 빈 필드를 추가합니다. text/plain은 `key0=aaa`와 줄바꿈 형식이며 끝부분에 공백이 들어갈 수 있습니다. 작은 길이에서는 key/value가 하나도 없을 수 있습니다. OCTET_STREAM은 `a` 바이트로 채우며 blockSize가 데이터 형식을 바꾸지 않습니다. blockSize는 HTTP 청크 크기가 아닙니다.

## Netty request/response 생성

다음은 작은 payload를 메모리에 복사하여 콜백 밖에서 사용할 request와 response를 만드는 예제입니다.

```java
import com.inzent.apim.utilities.http.payload.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

HttpPayloadGenerator generator =
    new DefaultHttpPayloadGenerator();

AtomicReference<FullHttpRequest> request = new AtomicReference<>();
generator.generate(false, ContentType.APPLICATION_JSON, ContentEncoding.GZIP, 4096, 128, (headers, input) -> {
    try {
        FullHttpRequest message = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/test",
            Unpooled.wrappedBuffer(input.readAllBytes()));
        message.headers().set(headers);
        message.headers().set(HttpHeaderNames.HOST, "localhost");
        request.set(message);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
});

AtomicReference<FullHttpResponse> response = new AtomicReference<>();
generator.generate(false, ContentType.APPLICATION_JSON, ContentEncoding.IDENTITY, 2048, 64, (headers, input) -> {
    try {
        FullHttpResponse message = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(input.readAllBytes()));
        message.headers().set(headers);
        response.set(message);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
});
// request.get(), response.get()를 전송하거나, 전송하지 않으면 release()한다.
```

`chunked=false`는 실제 인코딩 결과 크기를 Content-Length로 지정합니다. 압축을 사용하면 이 값은 입력 contentLength와 다릅니다. `chunked=true`는 Content-Length를 생략하고 Transfer-Encoding: chunked를 지정합니다. 스트림에는 청크 프레이밍 없이 인코딩된 본문만 들어 있습니다. 호출 측은 HttpRequest/HttpResponse, HttpContent, LastHttpContent를 구성하여 Netty HttpRequestEncoder/HttpResponseEncoder에 전달합니다. HTTP/2에서는 chunked 헤더를 사용하지 마세요.

## 스트림 수명과 대용량 사용

콜백은 동기 실행되며 반드시 콜백 내부에서 InputStream을 소비해야 합니다. 콜백이 반환하거나 예외를 던지면 스트림이 닫히고 임시 파일이 삭제됩니다. 콜백 예외는 그대로 전파하고 I/O 실패는 `UncheckedIOException`으로 전달합니다. 콜백이 던진 예외에 정리 실패가 추가되면 suppressed exception으로 보존됩니다.

원문은 전체 크기를 할당하지 않고 지연 생성합니다. 압축은 8 KiB 단위로 Netty encoder에 공급하고 결과를 임시 파일에 저장한 후 콜백을 실행합니다. 압축된 크기만큼 임시 디스크 공간이 필요합니다. 기본 JVM 임시 디렉터리(`java.io.tmpdir`)를 사용합니다. 압축 작업은 동기 I/O이므로 Netty event loop 밖에서 실행하세요.

대용량 처리에서는 위 예제의 `readAllBytes()` 대신 `transferTo()` 또는 버퍼 읽기를 사용합니다. 비동기 전송이 필요하면 콜백 안에서 호출자 소유의 파일/버퍼로 복사한 뒤 전송하고 자원을 직접 해제해야 합니다. 이미 인코딩된 본문을 다시 압축하지 않도록 pipeline을 구성하세요.

구성과 검증 범위는 [project.md](project.md)를 참고하세요.

리소스/NPE 실패 경로 분석과 조치 내역은 [resource-safety-review.md](resource-safety-review.md)에 정리했습니다. 압축 채널 초기화/쓰기 실패도 호출자에게 전달하며, 정리 중 추가 오류는 원래 오류의 suppressed 예외로 보존합니다. OS 파일 잠금·권한 문제로 임시 파일 삭제가 실패하면 예외로 알립니다.

리소스 회귀 테스트를 포함한 검증:

```powershell
mvn -f http-payload-generator/pom.xml '-Dio.netty.leakDetection.level=paranoid' clean verify
```
