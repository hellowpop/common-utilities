# Curl HTTP Mock Executor 설계

- 작성일: 2026-10-19
- 상태: 구현 및 Windows 실제 curl 검증 완료.
- 모듈: `curl-http-mock-executor` — Java 17 독립 Maven 라이브러리
- Maven 좌표: `com.inzent.apim.utilities:curl-http-mock-executor:1.0.0-SNAPSHOT`
- 신규 패키지: `com.inzent.apim.utilities.http.curl`

## 1. 목적과 범위

더미 HTTP 요청을 실제 curl 프로세스로 실행하고 요청/응답, 훅 실행, 오류를 단위 실행 컨텍스트에 보관한다. 여기서 mock은 더미 요청 데이터 생성을 의미한다. 응답은 대상 서버의 실제 응답이며 mock HTTP 서버를 새로 구현하지 않는다.

사용 순서는 컨텍스트 준비 → `void handle(ExecuteContext context)` → 컨텍스트 기록 조회다. 동기 실행 라이브러리로 제공하고 CLI 애플리케이션, UI, 반복 부하 실행기, 실시간 응답 이벤트 API는 이번 범위에 포함하지 않는다.

요청에서 확정된 요구는 curl 사용, 경로/추가 인자 설정, enum 기반 본문 생성/인코딩, boolean chunked, pre/post Runnable, 요청/응답 기록 및 1M 초과 본문 파일 저장이다. 추가로 HTTPS 인증서 검증 기본 비활성화, stdout/stderr 별도 파일 기록과 컨텍스트 참조, Closeable 컨텍스트의 자원 정리, Netty 의존성과 HTTP 상수 사용을 반영한다. 아래의 나머지 기본값, HTTP/1.1 고정, 기존 생성기 재사용, timeout 범위, post 시점 및 1M의 MiB 해석은 본 설계에서 제안하는 계약이다.

## 2. 접근 방식과 선택

| 방식 | 장점 | 비용/제약 |
|---|---|---|
| **기존 생성기 의존 + 신규 curl 실행 모듈 (권장)** | enum/더미 문법/압축/스트림 수명 검증 재사용, 실행 책임 분리 | 기존 모듈 Maven install 필요, Netty/native codec 의존성 전이 |
| 신규 모듈에 생성기까지 독립 구현 | 배포 의존성 선택 자유 | enum/압축/길이 계산 중복과 두 구현의 동작 불일치 가능 |
| 기존 생성기 모듈에 실행 기능 추가 | 단일 artifact 배포 | 순수 payload 생성기와 외부 프로세스 실행 책임 결합 |

권장안을 채택한 초안으로 정리한다. 기존 모듈 공개 API는 변경하지 않는다. 신규 모듈에도 `io.netty:netty-codec-http`를 직접 의존성으로 선언하고 기존 생성기와 동일한 `4.1.139.Final` 버전으로 맞춘다. 공개 API는 Netty `HttpMethod`, `HttpHeaders`, `HttpResponseStatus`를 사용한다. 헤더 이름/표준 값/HTTP 버전은 `HttpHeaderNames`, `HttpHeaderValues`, `HttpVersion` 상수를 사용한다.

## 3. 클래스 구성

| 타입 | 책임 |
|---|---|
| `CurlHttpMockExecutor` | 재사용 가능한 무상태 실행기, `handle(ExecuteContext)` 제공 |
| `ExecuteConfigure` | 불변 실행 환경: curl 실행 파일, 작업/임시 디렉터리, 연결 timeout, 추가 curl 인자 |
| `ExecuteContext` | 단위 요청 입력, pre/post Runnable, 실행 기록 및 자원 소유. `java.io.Closeable` 구현 |
| `ExecutionRecord` | 단계, 타임스탬프/소요 시간, curl 종료 코드, 상태 코드, 오류, 요청/응답 |
| `StageRecord` | VALIDATE/PRE/PREPARE/CURL/POST별 시작·종료·결과·오류 |
| `RequestRecord` | 준비된 헤더/본문, curl이 관측한 송신 헤더 블록, stdin 공급 바이트 수 |
| `ResponseRecord` | 응답 상태/헤더 블록 목록, 최종 응답 헤더, 부분 또는 완료 본문 |
| `BodyStore` | 메모리 → 파일 전환, 크기 조회, 반복 InputStream 열기, 자원 정리 |
| `ProcessOutputRecord` | stdout/stderr 각각의 기록 파일 Path, 크기, 반복 조회 스트림 제공. 컨텍스트가 파일 수명 관리 |
| 내부 클래스 | 인자 구성, 프로세스/입출력 관리, verbose 헤더 파서, payload 어댑터 |

내부 구현은 같은 패키지의 package-private 최상위 클래스로 분리한다. ContentType/ContentEncoding은 `com.inzent.apim.utilities.http.payload`의 기존 enum을 그대로 사용한다.

## 4. 설정과 컨텍스트 입력

| 입력 | 기본값/계약 |
|---|---|
| `configure` | 미지정 시 `ExecuteConfigure.defaults()` |
| `configure.curlExecutable` | Windows `curl.exe`, 기타 OS `curl`; 실행 환경의 기본 실행 파일 탐색 사용. 절대 파일 경로 지정 가능 |
| `configure.workingDirectory` | JVM 현재 작업 디렉터리. curl 실행 파일 위치와 별도 설정 |
| `configure.temporaryDirectory` | `java.io.tmpdir`; 실행별 전용 하위 디렉터리 사용 |
| `configure.connectTimeout` | 10초. 실제 적용값은 responseTimeout 이하 |
| `configure.verifyCertificate` | **false**. 기본 `--insecure`; true이면 `--no-insecure`로 서버 인증서 체인/호스트명 검증 활성화 |
| `configure.additionalArguments` | 기본 빈 `List<String>`. 옵션과 값을 각각 독립 토큰으로 지정하고 순서를 유지한다. 빌드 시 방어 복사 |
| `url` | 필수, http/https 단일 URI, user-info 및 CR/LF 거부 |
| `method` | Netty `HttpMethod`, 기본 `HttpMethod.POST`. POST/PUT/PATCH/DELETE/GET/HEAD 지원. 확장 메서드는 1차 범위 밖 |
| `contentType` | `APPLICATION_JSON` |
| `contentLength` | 4096. 압축 전 원문의 정확한 바이트 수, long |
| `blockSize` | 128. key/value 값의 최대 바이트 길이, 마지막 값은 짧을 수 있음 |
| `chunked` | false. HTTP 전송 프레이밍 선택 |
| `contentEncoding` | `IDENTITY`. 요청 본문 인코딩 선택 |
| `responseTimeout` | 30초, 양수 Duration. curl 시작부터 DNS/연결/업로드/응답 전체 전송의 제한 |
| `requestHeaders` | Builder에서 개별 추가/일괄 지정 가능한 Netty `HttpHeaders`. 빌드 시 복사하며 pre까지 수정 가능. 기록에는 독립된 읽기 전용 스냅샷 보관 |
| `preExecute`, `postExecute` | 선택적 `Runnable`, 미지정 시 단계 SKIPPED |

curl 실행 경로는 실행 파일을 의미하고 작업 디렉터리는 별도다. 명시 경로가 디렉터리이거나 실행할 수 없으면 시작 오류로 기록하며 다른 curl로 대체하지 않는다. 응답 timeout은 본문 생성/압축과 사용자 훅 수행 시간에는 적용하지 않는다.

HEAD는 body를 생성/전송하지 않고 curl의 `--head`를 사용한다. HEAD 컨텍스트는 `contentLength=0`, `chunked=false`, `IDENTITY`여야 한다. GET은 `contentLength=0`인 본문 없는 요청만 지원한다. POST/PUT/PATCH/DELETE는 0바이트를 포함한 더미 본문을 지원한다.

### 4.1. 기타 curl argument 설정

`ExecuteConfigure.additionalArguments(List<String>)`로 추가 실행 인자를 설정한다. 설정 객체를 여러 컨텍스트에서 공유하거나 단위 실행마다 다른 설정 객체를 지정할 수 있다.

```java
ExecuteConfigure configure = ExecuteConfigure.builder()
    .additionalArguments(List.of(
        "--resolve", "mock.example:443:127.0.0.1",
        "--limit-rate", "1M",
        "--user-agent", "Curl Mock Executor/1.0"))
    .build();
```

- 명령문 문자열을 분리하는 방식이 아닌 argv 토큰 목록이다. 옵션은 축약하지 않은 긴 이름(`--name`)으로 쓰고 값은 다음 요소에 지정한다. 짧은 옵션, 결합 옵션, `--name=value`, 옵션명 축약은 지원하지 않는다.
- 공백/한글이 있는 값도 요소 하나로 전달하며 셸용 따옴표를 덧붙이지 않는다. 목록/요소의 null, NUL 및 CR/LF를 거부한다. `--noproxy`의 빈 값처럼 의미 있는 빈 문자열 값은 허용한다.
- 내부 옵션 명세에 정확한 이름, 값 개수, 반복 가능 여부, 민감 값 여부를 등록하여 검증한다. 등록되지 않은 옵션, 값 누락, 불필요한 단독 토큰/추가 URL, 중복 불가 옵션의 중복은 VALIDATE 실패다. 값이 `-`로 시작해도 등록된 값 개수에 따라 해석한다.
- 1차 지원 옵션은 아래 표로 정한다. 옵션 명세를 확장하여 추가 지원할 수 있으며, 설치된 curl이 해당 옵션/기능을 지원하지 않으면 curl 실패를 그대로 기록한다.

| 구분 | 지원 옵션 | 값 개수 / 반복 |
|---|---|---|
| TLS | `--tlsv1.2`, `--tlsv1.3` | 0 / 둘 중 하나 |
| 인증서 | `--cacert`, `--capath`, `--cert`, `--key`, `--pass` | 1 / 각각 1회 |
| 접속 | `--resolve`, `--connect-to` | 1 / 반복 허용 |
| 네트워크 | `--interface`, `--limit-rate` | 1 / 각각 1회 |
| IP 선택 | `--ipv4`, `--ipv6` | 0 / 둘 중 하나 |
| 프록시 | `--proxy`, `--noproxy`, `--proxy-user` | 1 / 각각 1회 |
| Basic 인증 | `--user` | 1 / 1회. 비대화형 `사용자:비밀번호` 형태 필수 |
| 일반 요청 헤더 | `--user-agent`, `--referer` | 1 / 각각 1회 |

TLS 최소 버전 옵션은 둘 중 하나만 허용한다. `--proxy-user`도 `사용자:비밀번호` 형태로 지정하여 입력 대기를 방지한다. `--user-agent`/`--referer`와 requestHeaders의 동일 헤더를 함께 지정하면 우선순위가 모호하므로 최종 검증에서 거부한다. 추가 인자에서 일반 `--header`는 허용하지 않고 기존 requestHeaders/pre 훅으로 일원화한다.

실행기 관리 옵션은 추가 인자에서 허용하지 않는다. URL/메서드/본문/헤더/HTTP 버전/timeout/인증서 검증 여부, stdout·stderr·trace 출력, content decoding, redirect/retry/parallel/next, 외부 config 로딩 및 인증 재요청 협상이 해당한다. 예를 들어 `--output`, `--data-binary`, `--header`, `--max-time`, `--insecure`, `--no-insecure`, `--compressed`, `--location`, `--config`, `--anyauth`는 거부한다. 인증서 검증 여부는 `configure.verifyCertificate`로 일원화한다. 후순위 옵션으로 덮어쓰거나 조용히 무시하지 않고 충돌 원인과 옵션명을 기록한다.

argv 순서는 `실행 파일 → --disable → 실행기 기본값 → 검증된 추가 인자 → 실행기 필수 옵션 → --url과 단일 URL`이다. 기본 프록시 환경 차단은 `--proxy "" --noproxy ""`로 설정하고 추가 `--proxy`/`--noproxy`가 이를 명시적으로 대체한다. 전송/기록에 필요한 필수 옵션은 별도 검증으로 보호한다.

실행 기록에는 최종 인자 목록의 마스킹 사본을 남긴다. `--user`, `--proxy-user`, `--pass`, 비밀번호를 포함할 수 있는 `--cert`, user-info가 있는 `--proxy`의 값 전체를 마스킹한다. 실제 요청/응답 헤더와 본문 및 stdout/stderr 파일은 원문 기록 계약을 유지하며, 전체 argv를 기본 로그에 출력하지 않는다.

## 5. ContentType과 ContentEncoding

기존 ContentType의 공개 메서드를 유지한다.

```java
void generate(long contentLength, long blockSize, Consumer<InputStream> acceptor);
```

- 타입: TEXT_PLAIN, APPLICATION_JSON, APPLICATION_XML, TEXT_HTML, APPLICATION_FORM_URLENCODED, APPLICATION_OCTET_STREAM.
- enum은 생성 전략 선택용으로 유지한다. 실행기의 미디어 타입 매핑은 Netty `HttpHeaderValues.TEXT_PLAIN`, `APPLICATION_JSON`, `APPLICATION_XML`, `TEXT_HTML`, `APPLICATION_X_WWW_FORM_URLENCODED`, `APPLICATION_OCTET_STREAM`을 사용한다. charset이 필요한 타입은 Netty `HttpHeaderValues.CHARSET`과 `CharsetUtil.UTF_8`을 조합하여 기존 생성기와 같은 헤더 값을 만든다. 별도 문자열 상수 체계를 중복 정의하지 않는다.
- 각 enum은 타입별 문법과 dummy 생성 전략을 소유한다. ASCII 데이터로 바이트 길이를 정확하게 맞춘다.
- blockSize는 HTTP 청크나 I/O 버퍼 크기가 아니다. OCTET_STREAM에서는 값 길이 개념이 없어 결과 형식에 영향을 주지 않는다.
- `contentLength >= 0`, `blockSize > 0`. JSON/XML/HTML 양수 최소 길이는 기존 생성기 계약을 적용한다. 0바이트는 빈 스트림이다.
- callback 안에서 스트림을 소비해야 하며 반환 이후 닫힌다. 실행기는 callback 안에서 자신의 BodyStore로 복사한다.

ContentEncoding 지원 범위는 기존 생성기의 모든 값인 IDENTITY/GZIP/DEFLATE/BR/ZSTD/SNAPPY다. DEFLATE는 zlib wrapper, SNAPPY는 framed 형식이다. 모든 IANA 등록 코딩을 구현한다는 의미가 아니며 사전 기반 코딩과 compress 등은 지원하지 않는다. SNAPPY 해석은 서버와 맞춰야 한다.

압축은 Java 생성기가 수행하고 curl은 인코딩된 바이트를 업로드한다. curl 빌드의 압축 기능과 요청 인코딩 가능 여부는 별개다. native codec 사용 불가 시 준비 단계 실패로 기록하고 무압축으로 대체하지 않는다.

`contentLength`는 원문 길이, `request.body.size()`는 인코딩 후 길이다. non-chunked Content-Length는 후자의 값이다. IDENTITY에서는 Content-Encoding을 생략한다.

ContentEncoding enum의 HTTP 토큰도 실행기 매핑에서 Netty `HttpHeaderValues.IDENTITY/GZIP/DEFLATE/BR/ZSTD/SNAPPY`를 사용한다. 생성기에서 받은 헤더와 매핑 결과의 일치 여부를 검증한다. 메서드와 미디어 타입 상수의 기준은 [Netty HttpMethod](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpMethod.html), [Netty HttpHeaderValues](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpHeaderValues.html)다.

## 6. 훅 및 헤더 계약

### 6.1. Builder 요청 헤더 설정

```java
ExecuteContext.Builder requestHeader(CharSequence name, Object value);
ExecuteContext.Builder requestHeaders(HttpHeaders headers);
```

- `requestHeader(name, value)`는 Netty `HttpHeaders.add` 의미로 값을 추가한다. 같은 이름을 반복하면 기존 값을 유지하며 중복 값을 호출 순서대로 추가한다.
- `requestHeaders(headers)`는 Builder에 설정된 사용자 헤더 전체를 전달된 헤더의 복사본으로 교체한다. 이후 requestHeader 호출은 이 복사본에 추가한다. 따라서 `requestHeader → requestHeaders` 순서에서는 앞서 개별 추가한 값이 교체된다.
- 헤더 이름은 Netty `HttpHeaderNames` 상수나 사용자 정의 CharSequence를 받는다. 입력값은 호출 시 문자열로 스냅샷하여 가변 객체의 후속 변경이 헤더에 영향을 주지 않게 한다. null 이름/값/HttpHeaders는 거부한다.
- 일괄 지정 시 입력 HttpHeaders를 즉시 복사하고 build 시에도 컨텍스트 소유의 독립 `DefaultHttpHeaders`로 복사한다. 원본 헤더 변경, Builder 재사용 및 서로 다른 컨텍스트 사이에서 헤더를 공유하지 않는다.
- 이름/값의 HTTP 문법은 설정 시 Netty 검증을 적용한다. 예약 헤더 및 추가 인자와의 충돌은 실행 전 최종 검증 계약을 동일하게 적용한다.

### 6.2. pre/post 처리

pre Runnable은 컨텍스트를 closure로 참조하여 Builder에서 지정한 요청 헤더를 추가/변경한다. 입력 자체와 configure는 빌드 시 고정하고 요청 헤더만 pre 종료까지 변경할 수 있다. 실행기는 기본 헤더 준비 → Builder 사용자 헤더 반영 → pre → 최종 검증 → 송신 스냅샷 순으로 처리한다. 기본 헤더와 충돌하는 예약 헤더 값도 검증 없이 덮어쓰지 않고 기존 예약 헤더 계약대로 일치 여부를 확인한다.

Content-Type/Content-Encoding/Content-Length/Transfer-Encoding은 컨텍스트에서 계산하는 예약 헤더다. pre에서 이 헤더를 잘못 변경하면 최종 검증에서 실패시킨다. Host도 URI에서 정하고 임의 override를 거부한다. 일반 헤더는 추가/교체/삭제와 중복 값이 가능하다. 이름/값의 CR/LF 등 잘못된 HTTP 헤더는 프로세스 실행 전에 거부한다.

post Runnable은 프로세스 종료 및 모든 출력 수집 후 실행하며 기록된 응답 헤더와 BodyStore 스트림을 읽는다. response listen은 이 시점에 완성 또는 부분 수집된 응답을 관찰한다는 의미다. 실시간 네트워크 수신 callback이 필요하면 별도 API 설계로 추가한다.

post에서는 요청/응답 기록을 변경할 수 없다. 전송 실패와 pre 실패에서도 유효한 컨텍스트의 post는 한 번 호출하여 실패 기록을 관찰할 수 있다. 기본 입력 검증 자체가 실패한 경우 pre/post 모두 SKIPPED다. post는 CURL 단계 완료 여부를 확인한 뒤 응답을 사용한다.

## 7. curl 프로세스 실행

- 셸 없이 `ProcessBuilder(List<String>)`로 실행 파일/인자를 전달한다. 기본 curl 설정 파일은 첫 옵션 `--disable`로 차단한다.
- HTTP/1.1을 명시하여 chunked true/false가 같은 의미를 갖도록 한다. HTTP/2·3은 이번 범위 밖이다.
- URL globbing을 끄고 http/https 프로토콜만 허용한다. 자동 redirect/retry를 사용하지 않아 컨텍스트 하나가 단일 요청을 나타낸다. 3xx도 해당 응답으로 기록한다.
- 프록시는 기본적으로 비활성화하여 환경변수 영향을 배제하고 추가 인자로 명시 설정할 수 있다. HTTPS 서버 인증서 검증은 기본 비활성화하며 `verifyCertificate=true`로 활성화한다. HTTPS 프록시 인증서 검증은 별개의 curl 기본 동작을 유지한다. 다중 요청이 필요한 인증 협상은 지원하지 않는다.
- 본문 요청은 스트리밍 업로드 `--upload-file -`와 명시적 `--request`를 사용한다. 준비한 BodyStore를 stdin에 공급하고 완료 즉시 stdin을 닫는다. 전체 본문을 curl 입력용 문자열/byte[]로 만들지 않는다.
- non-chunked는 인코딩 후 정확한 Content-Length를 주고 Transfer-Encoding을 제거한다. chunked는 Transfer-Encoding: chunked와 Content-Length 제거를 적용한다. 청크 프레이밍은 curl이 담당한다.
- Expect: 100-continue는 기본 비활성화한다. 응답으로 수신되는 기타 1xx 헤더 블록은 보존한다.
- 실행별 전용 임시 디렉터리에 `stdout.bin`, `stderr.log`를 만들고 `ProcessBuilder.redirectOutput/redirectError`로 각각 직접 기록한다. 두 채널을 병합하지 않으며 빈 출력도 별도 파일로 남긴다. 출력은 디코딩/가공하지 않고 바이트 그대로 저장한다.
- stderr에는 단일 `--verbose` 출력을 사용한다. 종료 후 파일을 스트리밍 파싱하여 요청/응답 헤더와 진단 요약을 생성한다. 전체 본문 trace는 활성화하지 않는다. stdout은 일반 요청에서 응답 본문이고 HEAD에서는 curl이 출력한 헤더일 수 있으므로 HEAD 응답 body는 별도로 빈 본문으로 취급한다.
- 출력 파이프 대신 파일 리다이렉션을 사용하고 stdin writer만 독립 작업으로 실행한다. 프로세스 종료 후 writer를 회수하고 파일 파싱을 완료한 뒤 post를 실행한다. 파일 생성/입력 공급/종료 후 파싱 실패도 단계 오류로 기록하고 잔여 프로세스/스트림/작업을 회수한다.
- `--max-time`에 responseTimeout을 주고 Java 측에서도 같은 제한과 종료 회수 유예 2초로 감시한다. timeout/interrupt 시 종료 → 필요 시 강제 종료 → 스트림 닫기/작업 회수 후 post를 실행한다. interrupt 상태를 복원한다.
- curl의 자동 Content-Encoding 해제를 활성화하지 않는다. 응답 본문은 content-encoded 바이트를 보관하고 HTTP 청크 프레이밍은 curl이 제거한다. TLS/청크를 포함한 wire dump는 아니다.
- curl 버전/기능 출력은 실행 파일별 최초 사용 시 확인하여 진단에 보관한다. 지원 옵션/verbose 파싱 호환성은 Windows 및 Linux의 실제 curl 통합 테스트로 검증한다.

curl 옵션의 기준: [공식 curl 설명서](https://curl.se/docs/manpage.html), 특히 [upload-file](https://curl.se/docs/manpage.html#-T), [max-time](https://curl.se/docs/manpage.html#-m), [verbose](https://curl.se/docs/manpage.html#-v), [compressed](https://curl.se/docs/manpage.html#--compressed). 실제 적용은 설치된 curl의 기능을 기준으로 검증한다.

## 8. 기록과 본문 저장

1M은 1 MiB = 1,048,576바이트로 정의한다. 요청/응답 각각 독립적으로 판정한다.

stdout/stderr 원문 기록 파일은 크기와 관계없이 항상 생성한다. 1 MiB 정책은 구조화된 요청/응답 body 저장에 적용한다. 일반 응답의 stdout 파일이 1 MiB를 초과하면 Response BodyStore가 같은 파일을 참조하여 복제를 피하고, 이하이면 메모리 스냅샷으로 읽되 stdout 원문 파일도 유지한다. HEAD는 stdout 원문과 무관하게 빈 Response BodyStore를 사용한다. 파일 삭제 소유자는 컨텍스트 하나로 통일하여 공유 파일을 중복 삭제하지 않는다.

`context.record().stdout()`/`stderr()`는 각각 `ProcessOutputRecord`를 반환하고 `path()`, `size()`, `openStream()`으로 실행 후 원문을 참조한다. 프로세스 실행 단계 전 실패하여 파일이 만들어지지 않았으면 Optional.empty로 표현한다. 열린 스트림은 컨텍스트에 등록하여 close 시 함께 정리한다. Path로 직접 연 외부 스트림은 호출자가 컨텍스트 close 전에 닫아야 한다.

| 크기 | 저장 |
|---|---|
| 0 ~ 1,048,576 | 메모리 |
| 1,048,577 이상 | 임시 파일 |

BodyStore는 한 번 쓰고 완료 후 읽는 구조다. 요청 본문은 임계값을 넘는 순간 기존 메모리 데이터를 파일로 옮기고 버퍼 참조를 해제한다. 응답은 종료 후 실제 stdout 파일 크기로 저장 방식을 정하므로 수신 Content-Length에 의존하지 않는다. 전체 본문 byte[] 반환 API는 제공하지 않고 `size()`, `isFileBacked()`, `openStream()`을 제공한다.

요청 body는 전송하려고 준비한 전체 인코딩 본문이다. 전송 실패 때 서버가 받은 전체 본문이라는 의미가 아니다. `stdinBytesWritten`은 curl 프로세스에 공급한 양이며 서버 수신 확인값이 아니다. 관측된 송신 헤더가 없으면 빈 목록으로 남기고 준비 헤더를 실제 송신 헤더로 표기하지 않는다.

응답은 수신한 부분까지 저장하고 `bodyComplete`를 별도 표시한다. 완료 전 curl 오류/수집 오류/timeout이면 false다. HEAD/204/304 등 정상 무본문 응답은 길이 0인 완료 본문이다. 중복 헤더 순서와 1xx를 포함한 응답 블록을 보존하고, 최종 비-1xx 블록이 없으면 최종 상태 코드도 없는 값으로 표현한다.

헤더 한 블록은 최대 256 KiB, 전체 블록은 최대 32개로 제한한다. 종료 후 파싱에서 초과가 확인되면 구조화된 기록 수집 실패로 처리하고 기존 기록 및 절단 여부를 남긴다. stderr 진단 요약은 최근 64 KiB를 메모리에 보관하고 절단 여부를 표시하되 stderr 원문 파일은 절단하지 않는다. 크기 제한은 바이트 기준으로 적용하고 무제한 readLine 누적을 사용하지 않는다.

컨텍스트는 `public final class ExecuteContext implements java.io.Closeable`로 정의하며 `void close() throws IOException`을 제공한다. BodyStore, stdout/stderr 파일, 열린 조회 스트림, 실행기 소유 프로세스 핸들/입출력 스트림 및 작업 자원은 모두 컨텍스트 소유다. handle은 정상/실패 시 프로세스를 종료·회수하되 기록 파일은 close까지 보존한다.

close는 남은 프로세스의 종료/강제 종료와 작업 회수 → 입출력 및 조회 스트림 닫기 → 메모리 본문 참조 해제 → 소유 파일 삭제 → 실행별 임시 디렉터리 삭제 순으로 정리한다. 호출자가 지정한 임시 상위 디렉터리, curl 실행 파일, 인증서 등 외부 입력 파일은 삭제하지 않는다. 한 자원 정리가 실패해도 나머지를 계속 정리하고 최초 IOException에 후속 실패를 suppressed로 추가한다. 실패한 정리 대상은 추적을 유지하여 다음 close에서 재시도하며, 모두 정리된 후 반복 close는 무동작이다. 최초 close 이후 신규 실행/스트림 열기는 거부하지만 메타데이터는 조회 가능하다.

기존 압축 생성기는 크기와 무관하게 압축 중간 파일을 사용한다. 1 MiB 정책은 실행 기록 BodyStore의 저장 정책이며, 생성기의 일시적인 압축 작업 파일까지 금지한다는 의미는 아니다. 이 중간 파일은 생성 callback 완료 시 삭제된다. 신규 configure의 임시 경로는 실행기 소유 파일에 적용되고 기존 생성기 작업 파일은 기존 계약대로 JVM 임시 경로를 사용한다.

## 9. 실행 순서와 실패

```text
NEW → VALIDATE → PRE → PREPARE → CURL → POST → COMPLETED / FAILED
                                              ↓
                                            close
```

1. NEW 컨텍스트만 실행 진입을 허용하고 상태를 원자적으로 선점한다. 같은 컨텍스트의 중복/동시 실행은 거부한다.
2. 기본 입력 검증과 기본 헤더 준비를 기록한다.
3. pre Runnable의 시간·성공/실패를 기록한다.
4. 최종 헤더 검증, 본문 생성/인코딩, 저장, 최종 송신 스냅샷을 기록한다.
5. curl stdout/stderr를 별도 파일에 기록하고 프로세스/작업을 회수한 뒤 파일을 파싱하여 구조화된 요청/응답 기록을 완성한다.
6. post Runnable의 시간·성공/실패를 기록한다. 이전 단계 실패 시에도 기본 검증이 통과했다면 한 번 실행한다.
7. 최종 상태와 전체 시간을 확정한다. 실행 실패는 기록을 먼저 완성한 뒤 `CurlExecutionException`으로 전달한다.

단계 상태는 NOT_STARTED/RUNNING/SUCCEEDED/FAILED/SKIPPED로 둔다. 최초 실행 오류를 주원인으로 유지하고 post/정리 오류는 suppressed로 보관한다. post만 실패한 경우 curl 기록은 성공 상태를 유지하되 전체 컨텍스트는 FAILED다. HTTP 4xx/5xx는 HTTP 응답 결과이고 curl 전송이 완료되었다면 실행 자체는 성공이다. HTTP 상태 코드와 프로세스 종료 코드를 별개로 제공한다.

실행기는 서로 다른 컨텍스트의 병렬 handle 호출을 허용한다. 개별 컨텍스트는 실행 스레드 전용이며 실행 중 외부 변경/조회 및 close는 지원하지 않고 즉시 거부한다. pre/post 안에서도 close나 재진입 실행은 거부한다. close는 handle 반환/예외 후 수행한다.

## 10. 제안 사용 형태

아래는 구현 전 API 사용 형태이며 컴파일된 사용 예제가 아니다. Builder는 제안 입력에 대응하는 메서드를 제공한다.

```java
ExecuteConfigure configure = ExecuteConfigure.builder()
    .curlExecutable(Path.of("C:/tools/curl/bin/curl.exe"))
    .verifyCertificate(false) // 기본값: HTTPS 인증서 검증 비활성화
    .additionalArguments(List.of("--limit-rate", "1M", "--ipv4"))
    .build(); // 생략하면 시스템 기본 curl

try (ExecuteContext context = ExecuteContext.builder()
        .configure(configure)
        .url(URI.create("http://localhost:8080/mock"))
        .method(HttpMethod.POST)
        .requestHeader(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
        .requestHeader("X-Test-Group", "curl-mock")
        .contentType(ContentType.APPLICATION_JSON)
        .contentLength(2L * 1024 * 1024)
        .blockSize(128)
        .chunked(true)
        .contentEncoding(ContentEncoding.GZIP)
        .responseTimeout(Duration.ofSeconds(30))
        .build()) {
    context.setPreExecute(() ->
        context.requestHeaders().set("X-Test-Id", "sample-001"));
    context.setPostExecute(() -> {
        // context.record()에서 단계 결과와 응답 헤더를 확인한다.
        // 응답 본문은 context.record().response().body().openStream()으로 읽는다.
    });
    try {
        new CurlHttpMockExecutor().handle(context);
    } catch (CurlExecutionException failure) {
        // 실패해도 context.record()의 단계와 부분 응답을 조회할 수 있다.
    }
    ExecutionRecord record = context.record();
    record.stdout().ifPresent(output -> {
        Path stdoutFile = output.path(); // context.close() 전까지만 유효
    });
    record.stderr().ifPresent(output -> {
        Path stderrFile = output.path(); // 원문 verbose/진단 출력
    });
    // 응답 본문 조회 스트림도 try-with-resources로 닫는다.
}
```

## 11. 구현 시 수용 조건

- 시스템 기본 curl과 공백/한글 포함 명시 실행 경로, 작업 디렉터리, 실행 파일 없음/권한 오류.
- 추가 인자 순서/방어 복사, 공백/한글/빈 값 및 `-` 시작 값의 토큰 보존, 반복 옵션, 미등록/축약/결합 옵션·값 누락·추가 URL·관리 옵션 충돌 검증.
- 추가 인자에 의한 resolve/속도 제한/User-Agent 적용, 명시 프록시와 기본 환경 차단, 헤더 중복 충돌, 민감 인자 마스킹, curl 미지원 옵션의 실패 기록.
- 모든 ContentType의 길이/형식/blockSize 계약 유지, 모든 ContentEncoding 원문 복원 및 native 실패 기록.
- Netty 직접 의존성 버전 일치, HttpMethod/HttpHeaders 공개 타입, ContentType/ContentEncoding과 Netty 상수 매핑 일치 및 기록 헤더 스냅샷 변경 방지.
- 자체 서명 HTTPS 서버에 대한 기본 검증 비활성화 성공, verifyCertificate=true의 검증 실패 및 신뢰 인증서 설정 시 성공.
- 로컬 HTTP 서버에서 인코딩된 요청 바이트 일치, chunked/Content-Length 배타성, 빈 본문, HEAD/GET 검증.
- CR/LF 거부, 예약 헤더 충돌, 중복 헤더, curl 추가 헤더 관측, 1xx/최종 헤더 분리, 3xx/4xx/5xx 기록.
- Builder 개별 헤더 추가/일괄 교체의 호출 순서, 중복 값 순서, Netty 상수 입력, null/잘못된 헤더 거부, 원본/가변 값 변경 및 Builder 재사용 시 격리, pre 수정 반영과 최종 스냅샷 확인.
- 요청/응답 각각 임계값 -1/동일/+1 저장 방식, gzip 후 작아진 본문, 길이 불명 응답, long 대용량 처리.
- stdout/stderr 동시 대량 출력, 조기 서버 거부와 broken pipe, 연결 실패, 업로드/응답 지연 timeout, interrupt, 부분 응답 보존.
- stdout/stderr의 별도 파일/바이트 원문/빈 출력 보존, 실행 후 컨텍스트 경유 반복 조회, HEAD stdout과 빈 응답 body 구분, 대용량 stdout 파일의 Response BodyStore 공유.
- pre/생성기/curl/post 실패 조합, 최초 원인 보존, 미실행 단계 SKIPPED, 중복 실행/close 재진입 거부.
- 기록 반복 읽기, 조회 스트림 조기 close, 컨텍스트 close, 파일 생성/쓰기/삭제 실패 시 나머지 자원 회수.
- Closeable/try-with-resources, close 후 프로세스·조회 스트림·소유 파일·실행 디렉터리 정리, 중복 삭제 방지, 외부 입력 파일 보존 및 실패한 정리의 close 재시도.
- Java 단위 테스트에서 제어 가능한 프로세스 경계와 실제 curl + JDK 로컬 HTTP 서버 통합 테스트를 함께 사용한다. 외부 공개 서버에 의존하지 않는다.

## 12. 후속 문서와 단계

구현 계획은 [implementation-plan.md](implementation-plan.md), 사용법과 실제 API 예제는 [README.md](README.md)에 정리한다. 독립 Maven 모듈로 구현했으며 기존 생성기 공개 API는 변경하지 않았다.

## 13. 구현 및 검토 기록 (2026-10-19)

- `ExecuteConfigure`/`ExecuteContext` Builder, Netty 헤더/메서드/표준 값, 기존 enum 생성기 재사용을 구현했다.
- `CurlArguments`/`PayloadPreparation`/`CurlProcess`/`CurlLogParser`로 인자·본문·프로세스·파싱 책임을 분리했다. `BodyStore`와 `ProcessOutputRecord`는 컨텍스트 수명에 묶인다.
- 응답/요청 body 및 stdout/stderr 접근자는 생성 전 실패를 표현하도록 Optional을 반환한다. 실제 사용은 `record.response().body().orElseThrow().openStream()` 및 `record.stdout().orElseThrow().path()` 형태다.
- curl 버전 확인은 최초 실행 파일/작업 디렉터리 조합별로 최대 5초 수행하며 최근 32개만 캐시한다. 요청 responseTimeout과 별도다. 버전 출력도 실행별 소유 파일로 저장하고 close에서 삭제한다.
- Windows curl 8.21.0의 verbose 헤더 줄 `CR CR LF`를 파싱하고 원문 로그는 그대로 유지한다.
- stdin 업로드 시 자동 chunked 헤더가 붙는 동작을 확인하여 non-chunked에서 `Transfer-Encoding:` 제거 옵션, chunked에서 `Content-Length:` 제거 옵션을 명시했다.
- 빈 사용자 헤더는 curl의 `Name;` 형식으로 전송한다. URL target에 콜론이 있거나 프록시 요청이 absolute-form이어도 송신 헤더 블록을 식별한다.
- pre에서 Netty 비검증 헤더를 일괄 복사해도 우회할 수 없도록 최종 헤더를 항목별로 검증한다. 독립 리뷰의 세 지적을 실제 curl 재현 테스트로 확인한 뒤 수정했다.
- 버전 확인 도중과 본 요청 도중 interrupt를 모두 복원한다. 프로세스를 회수하고 stdin 작업을 제한 시간 동안 기다리며 남은 작업도 컨텍스트가 추적한다.
- 서버가 업로드를 조기 거절하더라도 curl 종료 코드가 0이고 최종 응답이 있으면 단순 stdin pipe 종료 때문에 완료 응답을 실패로 바꾸지 않는다. 본문 원본 읽기 오류는 별도로 실패 처리한다.
- 컨텍스트는 역순으로 자원을 정리하여 공유 stdout을 참조하는 응답 스트림부터 닫는다. 정리 실패 시 나머지 정리를 계속하고 후속 close로 재시도한다.
- 최초 구현은 Windows Java 21 및 실제 curl 환경에서 검증했다. 현재 Java 17 전환 검증은 아래에 별도 기록한다. Linux 런타임 검증은 이번 환경에서 수행하지 않는다.

### 최종 검증

- Java 21.0.3, Maven 3.9.11, Windows curl 8.21.0에서 `mvn -f curl-http-mock-executor/pom.xml clean verify`: **30개 테스트, 실패 0, 오류 0, 건너뜀 0, BUILD SUCCESS**, JAR 생성 완료.
- 기존 네 모듈 `clean verify`: HTTP Payload Generator 43개, Partial Input 13개, Object Recycle Container 58개, LDAP Connection Pool 33개 모두 통과. 전체 177개 테스트 통과.
- Object Recycle Container의 예외 주입 테스트 WARNING은 기존 테스트의 의도된 진단이다. 신규 테스트의 deprecated Netty 생성자는 비검증 헤더 복사 우회를 재현하기 위한 fixture에 한정된다.
- README의 전체 Java 사용 예제를 실제 런타임 의존성 classpath로 javac 컴파일했다.
- 독립 리뷰의 재확인에서 추가 중요 지적 없음. `git diff --check` 통과. 기존 AGENTS.md 변경은 유지하며 커밋/push는 수행하지 않았다.

## 14. 메모리 누수 / NPE 분석 및 보완 (2026-10-19)

- 분석 리포트: [resource-safety-review.md](resource-safety-review.md).
- 닫힌 조회 스트림의 inherited mark/reset 경로 NPE를 재현하고 수명 검사와 동기화를 적용했다. BodyStore/ProcessOutputRecord의 openStream과 close도 직렬화한다.
- RuntimeException/Error 정리 실패 후 다른 스트림/파일 정리가 중단되는 경로를 재현했다. 최초 원인을 보존하면서 전체 정리를 계속하고 실패한 자원만 재시도한다. stdin 스레드 interrupt 실패도 같은 계약을 적용한다.
- 프로세스 시작 후 stdin 스레드 start 실패가 post 전에 프로세스를 회수하지 않는 문제를 실제 curl로 재현했다. 전체 시작 준비를 프로세스 정리 범위로 감싸고 원래 Error를 유지한다. ThreadFactory는 package-private 의존성 경계이며 공개 실행 API는 유지한다.
- 필수 URL 누락은 기록된 IllegalArgumentException으로 처리한다. HeaderBlock null status/headers와 BodyStore null source를 상태 변경 전에 검증한다. 공개 API의 명시적 null 인자 거부 계약을 검증했다.
- 추가 `ResourceSafetyTest` 12개: 실패 재현, 실제 프로세스 회수, 복합 오류/재시도, null 경계, 메모리 배열/조회 목록/훅/스레드/프로세스 참조 해제 확인.
- 최종 `mvn -f curl-http-mock-executor/pom.xml clean verify`: **42개 테스트, 실패 0, 오류 0, 건너뜀 0, BUILD SUCCESS**, JAR 재생성 완료. 기존 실제 HTTP/HTTPS 30개를 포함한다.
- 독립 리뷰에서 추가 구체적 자원 누수/NPE/락 문제 없음. 장기 heap/부하/다른 OS 검증을 수행했다는 의미는 아니다. 사용자 `CallPatterns.java`와 AGENTS.md 변경은 보존하고 커밋/push는 수행하지 않는다.

## 15. Java 17 기준 변경 (2026-10-19)

- 이 모듈과 의존하는 HTTP Payload Generator의 compiler release를 17로 변경했다. 저장소의 다른 세 모듈도 동일 기준으로 맞췄다.
- stdin 스레드는 `new Thread(task, "curl-stdin")`와 `setDaemon(true)`로 생성한다. ThreadFactory 경계, 명시적 start, 프로세스 회수·interrupt·close 계약은 유지한다.
- 테스트의 가상 스레드 executor는 일반 cached thread pool로 변경하고 기존 shutdownNow 정리를 유지한다. Thread builder와 List.getFirst를 Java 17 API로 교체했다.
- 실제 JDK 17.0.10 / Maven 3.9.11 / Windows curl 8.21.0으로 `clean verify` 42개 테스트 통과. 생성 JAR의 모든 클래스 major version 61을 확인했다.
- 독립 리뷰에서 Java 17 전환 관련 추가 중요 지적 없음. 이전 Java 21 실행 기록은 당시 검증 이력으로 유지한다.

## 16. Java 17 안전성 재분석 (2026-10-19)

- interrupt된 close에서도 2초 내 stdin 작업 회수를 시도하고 interrupt 상태를 복원한다. 완료된 작업 참조가 남지 않도록 했다.
- BodyStore의 본문 처리/메모리 스냅샷/파일 종료 복합 오류에서 최초 원인을 보존한다. 종료가 실패한 출력 스트림은 pendingWriter로 추적하고 후속 close에서 재시도한다. 파일 삭제만으로 열린 핸들을 잃는 문제를 보완했다.
- 내부 BodyFileFactory 경계로 파일 종료 실패를 재현했으며 공개 API는 유지한다. 독립 리뷰가 지적한 writer 참조 소실은 실패 테스트로 확인한 뒤 수정·재검토했다.
- JDK 17.0.10의 최종 `clean verify`: **45개 테스트, 실패/오류/건너뜀 0**. 기존 null 입력·닫힌 스트림·부분 응답 검증을 포함한다. 새 내부 NPE는 별도로 재현되지 않았다.
- 객체 재사용 모듈은 약한 Thread 참조로 소유자를 비교하고 61개 테스트가 통과했다. 다른 세 모듈까지 전체 195개 테스트 통과. 상세는 [resource-safety-review.md](resource-safety-review.md)의 Java 17 추가 분석에 정리한다.
