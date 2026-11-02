# Netty HTTP Mock Server

요청 헤더로 모의 응답과 연결 종료 동작을 선택하는 Java 17 HTTP/1.1 서버다. 실행 가능한 단일 JAR로 배포하며, 부팅 시 annotation을 탐색해 action과 processor를 등록한다.

## 빌드와 실행

JDK 17 이상과 Maven 3.9 이상을 사용한다. 모듈 디렉터리에서 실행한다.

```shell
mvn clean verify
java -jar target/netty-http-mock-server.jar
```

기본 주소는 `127.0.0.1:8080`이다. 종료는 Ctrl+C로 수행한다. 테스트 중에는 서버와 별도 JVM을 기동하므로 로컬 loopback 연결이 가능해야 한다. 산출물 `target/netty-http-mock-server.jar`에는 Netty와 YAML parser가 포함되며 Lombok은 실행 시 필요하지 않다.

```shell
java -jar target/netty-http-mock-server.jar --server.port=9090
java -jar target/netty-http-mock-server.jar --config=./application.yml --SERVER_PORT=9090
java -DSERVER_PORT=9090 -jar target/netty-http-mock-server.jar
```

외부 접속이 필요하면 `--server.host=0.0.0.0`처럼 바인딩 주소를 지정한다. 상대 설정 경로는 실행한 작업 디렉터리 기준이며 공백이 있는 인수는 shell 규칙에 따라 인수 전체를 따옴표로 감싼다. JVM의 `-D` 옵션은 `-jar` 앞에 지정한다.

## Java 애플리케이션에 임베디드 실행

외부 프로세스를 만들지 않고 호스트 JVM 안에서 실행할 때는 `com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer` 인터페이스를 사용한다. 기존 `NettyHttpMockServer`가 이 인터페이스를 구현한다. 생성 factory는 shutdown hook을 등록하거나 `System.exit`를 호출하지 않으며, 호스트가 서버 종료를 담당한다.

### 1. 라이브러리 설치와 의존성 선언

이 모듈 디렉터리에서 로컬 Maven 저장소에 설치한다.

```shell
mvn clean install
```

호스트 프로젝트의 `pom.xml`에 다음 의존성을 추가한다.

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>netty-http-mock-server</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <classifier>library</classifier>
</dependency>
```

`target/netty-http-mock-server-library.jar`는 프로젝트 클래스와 설정 리소스만 포함하며, Netty와 SnakeYAML Engine은 Maven 전이 의존성으로 공급된다. classifier 없는 `target/netty-http-mock-server.jar`는 기존 `java -jar`용 실행 파일이다. 추가 classifier 구성은 [Maven JAR Plugin의 attached artifact 방식](https://maven.apache.org/plugins/maven-jar-plugin/examples/attached-jar.html)을 따른다.

### 2. 설정과 인스턴스 생성

설정 파일과 인수를 그대로 적용하려면 다음 factory를 사용한다. 호출 시 설정 검증과 action 등록까지 수행하고 아직 포트를 열지 않는다.

```java
import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;

HttpMockServer server = HttpMockServer.fromArguments(
        "--server.host=127.0.0.1",
        "--server.port=9090",
        "--server.worker-threads=2");
```

외부 YAML을 사용할 때는 `--config=/path/mock-server.yml`을 함께 전달한다. 내부 기본 YAML은 `/com/inzent/apim/utilities/netty/http/mockserver/application.yml`에서 읽으므로 호스트의 루트 `/application.yml`을 읽지 않는다.

설정을 Java에서 조정하거나 registry를 직접 구성할 수도 있다.

```java
import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;
import java.util.List;

ServerConfig config = ConfigLoader.load(new String[]{"--server.port=9090"})
        .toBuilder().workerThreads(2).maxConnections(50).build();

// 설정의 scan-packages를 자동 탐색한다.
HttpMockServer server = HttpMockServer.create(config);

// 또는 명시적인 processor 클래스 목록으로 registry를 구성하여 classpath 탐색을 생략한다.
// 이 경우 목록에 포함한 processor의 HttpResponseType 값만 등록되며 defaultAction도 등록되어 있어야 한다.
// ActionRegistry registry = ActionRegistry.ofClasses(List.of(MyDefaultProcessor.class, MyCopyProcessor.class));
// HttpMockServer server = HttpMockServer.create(config, registry);
```

registry 예시의 `MyDefaultProcessor`/`MyCopyProcessor`는 호스트가 작성한 `@HttpResponseType` 적용 processor 클래스다. 자동 탐색하면서 외부 processor를 추가하려면 기본 processor 패키지와 호스트 processor 패키지를 `mock.scan-packages`에 함께 지정한다.

### 3. 시작과 종료를 호스트 생명주기에 연결

| 호출 | 동작 |
| --- | --- |
| `HttpMockServer.fromArguments(String... args)` | YAML/CLI 설정 로드, 검증, 자동 등록. 시작 전 인스턴스 반환 |
| `HttpMockServer.create(ServerConfig)` | 설정을 받아 action 자동 등록. 시작 전 인스턴스 반환 |
| `HttpMockServer.create(ServerConfig, ActionRegistry)` | 호스트가 제공한 불변 registry 사용 |
| `int start()` | bind 완료까지 기다리고 서비스 포트 반환. 이후 요청 처리는 별도 I/O 스레드에서 실행 |
| `void awaitClose()` | listen channel이 닫힐 때까지 대기. interrupt 가능하며 자원 정리는 수행하지 않음 |
| `void close()` | listen/자식 연결과 예약을 닫고 자체 이벤트 루프 정리까지 대기. 중복 호출 가능 |

호스트 시작 callback에서 생성한 인스턴스를 필드로 보관하고 `start()`를 호출한다. 시작 후에는 호스트의 처리를 계속하면 되며 `awaitClose()`를 호출할 필요가 없다. 호스트 종료 callback에서 같은 인스턴스의 `close()`를 호출한다. 별도의 Netty I/O 스레드를 공유하는 API는 제공하지 않는다.

`start()`와 `close()`는 호스트 스레드에서 호출한다. 특히 `close()`를 이 서버의 processor나 I/O callback에서 호출하지 않는다. `awaitClose()`도 요청 처리 스레드에서 호출하면 안 된다. 서버 전용 대기 스레드가 필요한 경우에만 사용하고, 반드시 finally 또는 try-with-resources로 `close()`를 보장한다.

시작된 인스턴스의 `start()`를 다시 호출하면 동일 포트를 반환한다. 종료했거나 bind에 실패한 인스턴스는 재시작할 수 없으므로 새 인스턴스를 생성한다. 시작 전 `awaitClose()`는 `IllegalStateException`이며, 시작하지 않은 인스턴스를 `close()`하는 것은 가능하다. 설정/등록/bind 오류는 호출자에게 예외로 전달하므로 호스트가 처리한다. 임베디드 실행에는 `MockServerApplication.main()`을 사용하지 않는다.

### 4. 완전한 실행 예제

[EmbeddedExample.java](examples/EmbeddedExample.java)는 서버 생성 → 시작 → HTTP 요청 → 응답 확인 → close → 호스트 코드 계속 실행을 한 번에 보여준다. try-with-resources 블록을 벗어나면 서버가 닫히므로 장기 실행 호스트에서는 앞 절처럼 시작/종료 callback 사이에 인스턴스를 유지한다.

Maven 호스트에서는 예제 소스를 호스트의 소스 경로에 넣고 실행하면 된다. 이 모듈 안에서 직접 컴파일해 확인하려면 Windows PowerShell 기준으로 다음을 실행한다.

```powershell
mvn package dependency:copy-dependencies -DincludeScope=runtime
javac --release 17 -cp "target/netty-http-mock-server-library.jar;target/dependency/*" -d target/embedded-example examples/EmbeddedExample.java
java -cp "target/embedded-example;target/netty-http-mock-server-library.jar;target/dependency/*" com.inzent.apim.utilities.netty.http.mockserver.examples.EmbeddedExample --server.host=127.0.0.1 --server.port=9090 --server.worker-threads=1
```

Linux/macOS에서는 classpath의 `;`를 `:`로 바꾼다. 정상 출력은 `Embedded response: 200 OK`와 `Embedded server closed; host JVM continues`이며, 예제가 끝나면 포트와 자체 I/O 스레드가 정리된다.

## 제공 action

| `x-response-action` 값 | 동작 |
| --- | --- |
| 생략 또는 `ok` | 200, UTF-8 `OK` |
| `copy-and-send` | 200, 요청 본문을 바이너리 그대로 복사. Content-Type 한 값만 복사 |
| `error-500` | 500, UTF-8 `MOCK_ERROR`와 줄바꿈 |
| `close` | 최종 HTTP 응답 없이 연결 종료 |
| `fake-close` | 200 `OK`, `Connection: keep-alive`를 보내고 전송 완료 후 채널 종료 |
| `delay-close` | `x-delay-amount` 밀리초 후 최종 HTTP 응답 없이 채널 종료 |

헤더 이름은 대소문자를 구분하지 않고 action 값은 구분한다. action 값은 양 끝 공백을 제거한 후 검사하며, 동일 action 헤더를 여러 번 보내면 400이다. 알 수 없는 action은 400 `UNKNOWN_ACTION`이다.

```shell
curl --http1.1 -i http://127.0.0.1:8080/
curl --http1.1 -i -X POST -H "x-response-action: copy-and-send" -H "Content-Type: text/plain" --data-binary "hello" http://127.0.0.1:8080/
curl --http1.1 -i -H "x-response-action: error-500" http://127.0.0.1:8080/
curl --http1.1 -v -H "x-response-action: fake-close" http://127.0.0.1:8080/
curl --http1.1 -v -H "x-response-action: delay-close" -H "x-delay-amount: 1500" http://127.0.0.1:8080/
```

Windows PowerShell에서 curl 별칭이 적용되는 환경은 `curl.exe`를 사용한다. `close`와 `delay-close`는 HTTP 응답을 보내지 않으므로 curl이 빈 응답 오류를 표시하는 것이 의도된 동작이다.

### Fake close

요청의 Connection 값과 관계없이 응답 헤더를 `Connection: keep-alive`로 설정한다. Content-Length에 맞는 전체 응답의 Netty write 완료 후 close listener가 채널을 닫는다. 전송 실패 시에도 채널을 닫는다. 이 완료는 클라이언트 애플리케이션의 수신 확인을 뜻하지 않는다. HEAD는 본문 없이 헤더 전송 후 닫는다.

### Delay close

`x-delay-amount`는 정확히 한 개가 필수이며, 0~2,147,483,647 범위의 ASCII 10진 정수 밀리초다. 0은 지연 없이 종료 예약을 뜻한다. 헤더 누락·중복·빈 값·음수·부호·소수·단위 문자열·범위 초과는 400 `INVALID_DELAY_AMOUNT`다. 다른 action에서는 이 헤더를 무시한다.

전체 요청 본문을 받은 후 검증과 예약이 끝난 시점부터 지연을 계산한다. Netty 이벤트 루프 예약을 사용해 다른 연결의 처리를 차단하지 않으며 실제 종료는 이벤트 루프 상황에 따라 늦어질 수 있다. 지연 중에는 후속 요청을 처리하지 않고 일반 수신 timeout도 중지한다. 상대측 연결 해제, 앞선 응답의 전송 실패, 서버 종료 시에는 예약 시각 전에 연결이 끝날 수 있다.

## 설정

내부 [application.yml](src/main/resources/com/inzent/apim/utilities/netty/http/mockserver/application.yml)을 기본으로 사용한다. JAR 내부에서는 패키지 경로에 두어 호스트 애플리케이션의 `/application.yml`과 충돌하지 않게 한다. `--config=<path>` 지정 시에는 해당 파일만 읽고, 누락된 항목은 코드 기본값을 사용한다. 내부 YAML과 외부 YAML을 병합하지 않는다.

```yaml
server:
  host: "${SERVER_HOST:127.0.0.1}"
  port: "${SERVER_PORT:8080}"
  worker-threads: 2
  max-connections: 100
  request-timeout-ms: 30000
mock:
  header-name: x-response-action
  default-action: ok
  scan-packages:
    - com.inzent.apim.utilities.netty.http.mockserver.processor
```

`${NAME}` 치환 순서는 `--NAME=value` → JVM system property → OS 환경 변수다. 모두 없으면 `${NAME:default}`의 기본값을 사용한다. 문자열 scalar와 문자열 목록 항목에만 적용하며, 재귀/중첩 치환은 지원하지 않는다.

`--server.port=9090` 같은 점 표기 인수는 치환을 마친 YAML의 scalar 설정을 최종 덮어쓴다. 이 override에는 치환을 적용하지 않는다. 목록 override, YAML key 치환, 실행 중 재로딩은 지원하지 않는다. 중복 인수, 사용하지 않은 치환용 인수, 알 수 없는 설정, null, 잘못된 타입은 기동 실패다. YAML은 1 MiB·중첩 깊이 40으로 제한하고 alias·객체 태그·중복 key·다중 문서를 거부한다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `server.host`, `server.port` | `127.0.0.1`, `8080` | 바인딩 주소, 1~65535 포트 |
| `server.boss-threads`, `server.worker-threads` | `1`, `0` | accept/I/O 스레드 수. worker 0은 Netty 기본값 |
| `server.backlog`, `server.reuse-address` | `128`, `true` | listen 옵션 |
| `server.tcp-no-delay`, `server.keep-alive` | `true`, `true` | TCP_NODELAY, TCP SO_KEEPALIVE. HTTP 연결 유지 여부는 요청/action에 따름 |
| `server.max-connections` | `100` | 지연 종료 대기 연결을 포함한 활성 연결 상한 |
| `server.max-initial-line-length` | `4096` | 요청 시작 줄 byte 한도 |
| `server.max-header-size` | `8192` | 요청 헤더 byte 한도 |
| `server.max-content-length` | `1048576` | 전체 요청 본문 byte 한도 |
| `server.request-timeout-ms` | `30000` | 다음 요청 대기부터 전체 수신까지의 절대 시간 한도 |
| `server.write-buffer-low-water-mark` | `32768` | 쓰기 대기량 감소 시 읽기 재개 기준 byte |
| `server.write-buffer-high-water-mark` | `65536` | 쓰기 대기량 증가 시 읽기 중단 기준 byte |
| `server.max-pending-responses` | `8` | 연결별 미완료 응답 write 수 상한 |
| `server.shutdown-timeout-ms` | `10000` | 이벤트 루프 종료 한도 ms |
| `mock.header-name` | `x-response-action` | action 선택 헤더 |
| `mock.default-action` | `ok` | 헤더 생략 시 실행할 등록 action |
| `mock.scan-packages` | 위 예시 목록 | `@HttpResponseType` processor 클래스 탐색 범위 |

잘못된 요청/action은 400, 과대 본문은 413, 미지원 HTTP 버전은 505, processor 예외/null 결과는 500 후 닫는다. 수신 timeout은 응답 없이 닫는다. 애플리케이션 오류는 `text/plain; charset=UTF-8`의 `오류코드\n` 본문을 사용하고 Netty codec/aggregator 자체 응답은 기본 형식을 따른다.

## Processor 추가

processor 클래스 하나를 추가하고 **`@HttpResponseType.value()`** 로 처리할 헤더 값을 지정한다. 별도 stereotype/configuration annotation은 필요하지 않다.

```java
package com.inzent.apim.utilities.netty.http.mockserver.processor;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.ActionResult;
import com.inzent.apim.utilities.netty.http.mockserver.response.Responses;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;

@HttpResponseType("hello")
public final class HelloProcessor extends AnnotationProcessor {
    @Override public ActionResult process(Channel channel, FullHttpRequest request) {
        return new ActionResult.SendResponse(Responses.text(200, "hello"));
    }
}
```

`HelloProcessor.java`를 `mock.scan-packages` 하위에 배치한다. JAR에 함께 포함하여 다시 빌드/시작하면 `x-response-action: hello`로 실행한다. 외부 JAR의 실행 중 동적 추가는 지원하지 않는다.

`AnnotationProcessor`는 generic 타입과 설정 annotation 없이 `process(Channel, FullHttpRequest)`만 제공한다. `annotationType()`과 `validate(annotation)`은 제거했다. HTTP 상태/본문은 processor가 직접 작성하며 기본 `error-500` processor는 고정 500 응답을 반환한다.

processor에는 공개 무인수 생성자가 필요하며 공유 인스턴스로 사용하므로 요청 상태를 필드에 저장하지 않는다. `HttpResponseType` 값 중복/잘못된 형식, processor가 아닌 클래스에 선언, abstract/nonpublic processor, 생성자 오류는 기동 실패다. `HttpResponseType`이 없는 클래스는 등록하지 않는다.

processor는 요청을 release하거나 보관하지 않고 channel에 직접 write/close하지 않는다. 독립 응답 buffer를 만들어 ActionResult로 소유권을 넘긴다. 결과는 `SendResponse`, `FakeCloseResponse`, `CloseConnection`, `DelayCloseConnection` 중 하나다. 파일 I/O·sleep·외부 통신 같은 blocking 작업은 이벤트 루프에서 수행하지 않는다.

기존 버전에서 이전할 때는 복사 요청의 헤더 값을 `copy`에서 `copy-and-send`로 바꾸고, 외부 YAML의 탐색 경로를 `.action`에서 `.processor`로 변경한다. `HttpResponseProcessor`와 action 정의 클래스 등록 방식은 제거했다. `ActionRegistry.ofClasses(...)`에는 정의 클래스 대신 processor 클래스를 전달한다. 기존 `copy` alias는 등록하지 않는다.

이전 generic processor 구현은 `extends AnnotationProcessor<T>`를 `extends AnnotationProcessor`로 바꾸고 `process`의 첫 annotation 인수를 제거한다. `Response*Action` stereotype과 annotation 기본값 Proxy는 삭제했다. 헤더 binding용 `HttpResponseType`은 유지한다.

## 지원 범위와 검증

HTTP/1.1 평문, 전체 요청 집계, 일반 디렉터리/flat shaded JAR 탐색을 지원한다. TLS, HTTP/2, 스트리밍, JPMS module-path, 중첩 JAR 실행기, 정밀한 TCP RST 제어는 지원하지 않는다.

```shell
mvn test
mvn clean verify
```

`test`는 설정·등록·실제 socket·가상 시간/버퍼 소유권을 검증하고, `verify`는 패키징한 실행 JAR의 별도 JVM 검증까지 수행한다. 실제 JDK 17 검증 결과와 설계 변경은 [project.md](project.md), 구현 순서는 [implementation-plan.md](implementation-plan.md)에 기록한다.

2026-11-02 JDK 17.0.10에서 `mvn clean verify`로 총 40개 테스트 통과(실패·오류·건너뜀 0)를 확인했다. stereotype 없는 processor API와 직접 binding, 임베디드 인터페이스의 생명주기와 library JAR 소비자의 별도 JVM 실행/정상 종료 검증을 포함한다.
