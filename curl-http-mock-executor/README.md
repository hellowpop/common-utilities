# Curl HTTP Mock Executor

Java 21 라이브러리로 더미 HTTP 요청을 실제 curl 프로세스에서 실행하고, 요청/응답과 실행 단계를 `ExecuteContext`에 기록합니다. HTTP/1.1 단일 요청을 실행하며 응답은 대상 서버의 실제 응답입니다.

## 빌드

저장소 루트에서 기존 생성기를 먼저 설치한 뒤 실행기 모듈을 빌드합니다.

```powershell
mvn -f http-payload-generator/pom.xml install
mvn -f curl-http-mock-executor/pom.xml clean verify
mvn -f curl-http-mock-executor/pom.xml install
```

사용 프로젝트의 의존성:

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>curl-http-mock-executor</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Netty `netty-codec-http:4.1.139.Final`을 직접 참조합니다. Brotli/Zstd 등 압축 의존성은 HTTP Payload Generator에서 가져옵니다. 실제 요청 실행 환경에 curl 실행 파일이 필요합니다. 테스트는 실제 curl과 JDK 로컬 HTTP/HTTPS 서버를 사용하며 HTTPS 테스트의 인증서는 JDK keytool로 임시 생성합니다.

## 단위 실행

```java
import com.inzent.apim.utilities.http.curl.*;
import com.inzent.apim.utilities.http.payload.ContentType;
import com.inzent.apim.utilities.http.payload.ContentEncoding;
import io.netty.handler.codec.http.*;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class Example {
    public static void main(String[] args) throws Exception {
        ExecuteConfigure configure = ExecuteConfigure.builder()
            // 생략하면 Windows curl.exe, 기타 OS curl을 기본 탐색 경로에서 찾습니다.
            // .curlExecutable(Path.of("C:/tools/curl/bin/curl.exe"))
            .verifyCertificate(false) // 기본값: HTTPS 서버 인증서 검증 비활성화
            .additionalArguments(List.of("--limit-rate", "1M"))
            .build();

        CurlHttpMockExecutor executor = new CurlHttpMockExecutor();
        try (ExecuteContext context = ExecuteContext.builder()
                .configure(configure)
                .url(URI.create("http://localhost:8080/mock"))
                .method(HttpMethod.POST)
                .requestHeader(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
                .requestHeader("X-Test-Id", "sample-001")
                .contentType(ContentType.APPLICATION_JSON)
                .contentEncoding(ContentEncoding.GZIP)
                .contentLength(2L * 1024 * 1024)
                .blockSize(128)
                .chunked(true)
                .responseTimeout(Duration.ofSeconds(30))
                .build()) {
            context.setPreExecute(() -> context.requestHeaders().set("X-Pre", "ready"));
            context.setPostExecute(() -> {
                System.out.println(context.record().response().headers());
                context.record().failure().ifPresent(Throwable::printStackTrace);
            });
            try {
                executor.handle(context);
            } catch (CurlExecutionException failure) {
                // 실패 시에도 부분 응답 및 단계 기록을 조회합니다.
                System.err.println(failure.getCause());
            }
            ExecutionRecord record = context.record();
            System.out.println(record.state());
            System.out.println(record.stage(ExecutionRecord.Stage.CURL).status());
            System.out.println(record.exitCode());
            record.response().status().ifPresent(status -> System.out.println(status.code()));
            record.stdout().ifPresent(output -> System.out.println("stdout: " + output.path()));
            record.stderr().ifPresent(output -> System.out.println("stderr: " + output.path()));
            if (record.response().body().isPresent()) {
                try (InputStream body = record.response().body().orElseThrow().openStream()) {
                    body.transferTo(System.out);
                }
            }
        } // 프로세스/스트림을 정리하고 소유 기록 파일을 삭제합니다.
    }
}
```

한 컨텍스트는 한 번만 실행합니다. 실행기는 다른 컨텍스트의 병렬 실행에 재사용할 수 있습니다. 같은 컨텍스트의 외부 동시 접근과 실행 중 close는 지원하지 않습니다. handle이 반환하거나 예외를 전달한 후 close합니다.

## 설정

| 항목 | 기본값 |
|---|---|
| configure.curlExecutable | Windows `curl.exe`, 기타 OS `curl` |
| configure.workingDirectory | JVM 현재 작업 경로 |
| configure.temporaryDirectory | `java.io.tmpdir` |
| configure.connectTimeout | 10초, responseTimeout 이하로 적용 |
| configure.verifyCertificate | **false** |
| configure.additionalArguments | 빈 목록 |
| method | `HttpMethod.POST` |
| contentType | `ContentType.APPLICATION_JSON` |
| contentEncoding | `ContentEncoding.IDENTITY` |
| contentLength / blockSize | 4096 / 128 |
| chunked | false |
| responseTimeout | 30초 |

URL은 필수이며 http/https만 지원합니다. URL 사용자 정보와 fragment는 허용하지 않습니다. POST/PUT/PATCH/DELETE는 더미 본문을 전송합니다. GET/HEAD는 contentLength=0, chunked=false, IDENTITY로 사용합니다. 최초 curl 버전 확인은 별도로 최대 5초 기다리며, 요청의 responseTimeout은 프로세스 시작 후 DNS/연결/업로드/응답 전체 전송에 적용합니다. 사용자 훅과 본문 생성 시간에는 적용하지 않습니다.

인증서 검증을 활성화하려면 `.verifyCertificate(true)`를 사용합니다. 사용자 CA는 `.additionalArguments(List.of("--cacert", "C:/certs/ca.pem"))`로 지정할 수 있습니다.

## 요청 헤더와 훅

`requestHeader(CharSequence, Object)`는 개별 값을 추가합니다. 같은 이름을 반복하면 중복 값을 순서대로 유지합니다. `requestHeaders(HttpHeaders)`는 Builder 사용자 헤더 전체를 복사본으로 교체합니다. 이후 requestHeader 호출은 해당 복사본에 추가합니다. Netty `HttpHeaderNames`와 `HttpHeaderValues` 상수를 사용할 수 있습니다.

입력 헤더와 값을 복사하므로 원본 변경이나 Builder 재사용이 이미 생성된 컨텍스트에 영향을 주지 않습니다. pre에서는 context.requestHeaders()를 수정하고, pre 종료 후에는 읽기 전용 스냅샷을 반환합니다. 기록의 헤더도 읽기 전용입니다.

Content-Type/Content-Encoding/Content-Length/Transfer-Encoding/Host 및 Expect는 실행기가 관리합니다. 예약 헤더는 컨텍스트와 일치해야 하며 잘못된 값은 실행 전에 거부합니다. 일반 사용자 헤더는 Builder 또는 pre로 설정합니다.

pre/post는 선택적 Runnable이며 Builder의 preExecute/postExecute 또는 컨텍스트의 setPreExecute/setPostExecute로 지정합니다. post는 curl 종료와 파일 파싱 후 실행합니다. 기본 검증을 통과했다면 pre/본문 생성/curl 실패 때에도 post를 한 번 실행하여 실패 기록을 확인할 수 있습니다. 실시간 응답 callback은 제공하지 않습니다.

## 기타 curl 인자

옵션과 값은 각각 목록 요소 하나로 전달합니다. 공백이 포함된 값에도 셸용 따옴표를 덧붙이지 않습니다.

```java
ExecuteConfigure configure = ExecuteConfigure.builder()
    .additionalArguments(List.of(
        "--resolve", "mock.example:443:127.0.0.1",
        "--user-agent", "Curl Mock Executor/1.0",
        "--limit-rate", "1M"))
    .build();
```

| 옵션 | 값 |
|---|---|
| --tlsv1.2, --tlsv1.3 | 없음, 둘 중 하나 |
| --ipv4, --ipv6 | 없음, 둘 중 하나 |
| --cacert, --capath, --cert, --key, --pass | 각각 1개 |
| --resolve, --connect-to | 각각 1개, 반복 허용 |
| --interface, --limit-rate | 각각 1개 |
| --proxy, --noproxy, --proxy-user | 각각 1개 |
| --user | `사용자:비밀번호` 1개 |
| --user-agent, --referer | 각각 1개 |

축약 없는 긴 옵션과 분리된 값만 지원합니다. `-k`, `--name=value`, 추가 URL, 미등록 옵션은 거부합니다. 인증서 검증 여부는 verifyCertificate로, 헤더는 Builder/pre로, 메서드/본문/timeout은 컨텍스트로 지정합니다. 출력·trace·redirect·retry·parallel·config·다중 요청 인증 협상 옵션은 지원하지 않습니다. 프록시는 기본 비활성화하며 --proxy/--noproxy로 명시합니다. --proxy-user도 `사용자:비밀번호`가 필요합니다. --user-agent/--referer와 동일한 requestHeaders를 함께 지정하면 충돌입니다.

record.arguments()는 최종 인자 목록의 마스킹 사본입니다. 인증 인자와 인증서 비밀번호 등을 마스킹하며 stdout/stderr 원문 파일과 HTTP 기록은 원문을 유지합니다.

## 본문과 기록 파일

ContentType은 TEXT_PLAIN/APPLICATION_JSON/APPLICATION_XML/TEXT_HTML/APPLICATION_FORM_URLENCODED/APPLICATION_OCTET_STREAM을 지원합니다. 기존 enum의 `generate(long, long, Consumer<InputStream>)`도 그대로 사용할 수 있습니다. contentLength는 압축 전 바이트 수이며 blockSize는 key/value의 value 최대 길이입니다. 마지막 값은 짧아질 수 있고 blockSize는 HTTP 청크 크기가 아닙니다.

ContentEncoding은 IDENTITY/GZIP/DEFLATE/BR/ZSTD/SNAPPY를 지원합니다. DEFLATE는 zlib wrapper, SNAPPY는 framed 형식입니다. Brotli/Zstd native 런타임이 필요할 수 있으며 사용할 수 없으면 실패합니다. contentLength와 실제 인코딩 후 request.body().size()는 다를 수 있습니다. non-chunked Content-Length에는 인코딩 후 길이를 사용합니다.

요청 body는 보내려고 준비한 전체 인코딩 본문이며 stdinBytesWritten은 curl에 공급한 양입니다. 서버 수신 확인값은 아닙니다. 응답 body는 curl이 HTTP 청크를 제거한 entity이며 콘텐츠 압축은 해제하지 않습니다. 실패 시 부분 본문과 bodyComplete=false를 확인할 수 있습니다.

요청/응답 BodyStore는 각각 1,048,576바이트까지 메모리, 초과하면 파일을 사용합니다. stdout/stderr는 크기와 관계없이 항상 별도 원문 파일입니다. 일반 요청에서 대용량 응답 body는 stdout 파일을 공유합니다. HEAD의 stdout에는 헤더가 출력되지만 응답 body는 0바이트입니다.

record.stdout()/stderr()는 Optional<ProcessOutputRecord>이며 path(), size(), openStream()을 제공합니다. 실행 단계 전에 실패하면 값이 없을 수 있습니다. 원문 파일은 handle 후에도 유지되며 context.close() 때 삭제됩니다. 파일을 유지하려면 close 전에 호출자 소유 경로로 복사합니다. 컨텍스트 openStream으로 연 스트림은 close 시 함께 닫힙니다. Path에서 직접 연 외부 스트림은 호출자가 먼저 닫습니다.

각 단계의 시간/실패, 프로세스 종료 코드와 HTTP 상태 코드를 따로 기록합니다. 4xx/5xx도 전송이 완료되면 COMPLETED입니다. curl/입출력/훅 실패는 기록 확정 후 CurlExecutionException으로 전달합니다. 최초 원인을 유지하고 후속 오류는 suppressed로 추가합니다. post만 실패하면 전체 상태는 FAILED지만 CURL 단계와 완료 응답은 유지합니다.

close는 IOException을 선언합니다. 한 자원 정리에 실패해도 나머지를 정리하고 실패한 대상은 다음 close에서 재시도합니다. 지정한 임시 상위 디렉터리, curl 실행 파일, 인증서 등 외부 입력 파일은 삭제하지 않습니다. close 후에는 메타데이터만 조회할 수 있습니다.

정리 중 RuntimeException/Error도 나머지 자원 정리를 마친 뒤 원래 타입으로 전달합니다. 닫힌 조회 스트림의 read/reset은 IOException이며 mark는 무동작, markSupported는 false입니다. URL 미지정 실행은 VALIDATE 단계의 IllegalArgumentException으로 기록하고, 명시적인 null API 인자는 경계에서 즉시 거부합니다.

메모리 누수·NPE 분석, 재현과 보완 범위는 [resource-safety-review.md](resource-safety-review.md)에 정리했습니다. 컨텍스트/예외 메타데이터 보존과 호출자 소유 참조에 대한 한계도 포함합니다.

설계와 구현 내역은 [project.md](project.md), 작업 계획과 검증 기록은 [implementation-plan.md](implementation-plan.md)를 참고하세요.
