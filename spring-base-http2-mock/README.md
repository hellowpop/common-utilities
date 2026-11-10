# spring-base-http2-mock

Java 17 이상에서 실행하는 Spring Boot 3.5.16 기반 HTTP/1.1 · HTTP/2(TLS h2) · SSE · WebSocket mock webapp.

## 빌드 및 실행

프로젝트 디렉터리에서 실행한다.

```powershell
# 최초 빌드: 두 형제 모듈을 먼저 설치하고 Spring 앱을 빌드한다(테스트 실행 생략).
./scripts/Build-WithMockServers.ps1
java -jar target/spring-base-http2-mock-1.0.0-SNAPSHOT.jar
```

세 모듈의 테스트까지 실행하려면 `./scripts/Build-WithMockServers.ps1 -RunTests`를 사용한다. Node.js가 설치되어 있으면 콘솔 JavaScript 테스트도 실행한다.
의존 모듈이 설치된 이후에는 이 모듈에서 일반 `mvn package`를 사용할 수 있다. 실행 중 JAR을 Windows에서 다시 빌드하려면 해당 서버를 먼저 종료한다.

기본 주소는 HTTP `http://localhost:8080`, HTTPS `https://localhost:8443`이다.
HTTPS 포트는 `--server.port=9443` 또는 `PORT`, HTTP 포트는 `--mock.http.port=9080` 또는 `HTTP_PORT` 환경변수로 변경한다.

```powershell
# 전체 통합 테스트 실행(실제 네트워크, SSE/WS 각각 약 10초)
mvn test -DskipTests=false
# 테스트 포함 패키징
mvn clean package -DskipTests=false
# 기본 install은 테스트 실행 생략
mvn install
# 브라우저 연결 상태 로직 회귀 테스트(Node.js 사용)
node --test src/test/js/test-console.test.cjs
```

`skipTests=true`가 기본값이므로 package도 테스트 실행을 생략한다. 테스트 컴파일은 수행한다.
package는 실행 JAR과 `*-sources.jar`를 만든다. 루트의 `README.md`, `project.md` 등 `*.md`는 JAR의 `META-INF/docs/spring-base-http2-mock/`에 포함된다.

## 여러 Mock 서버 동시 서비스

단일 Spring Boot JAR을 실행하면 다음 서비스를 같은 JVM에서 시작한다. 기존 HTTP/HTTPS, HTTP/2, SSE 및 WS/WSS 경로와 동작은 유지된다.

| 서비스 | 기본 주소 | 기능 |
| --- | --- | --- |
| Spring HTTP / HTTPS | `localhost:8080` / `localhost:8443` | 기존 REST, HTTP/2, SSE, WebSocket 및 콘솔 |
| Netty HTTP mock | `127.0.0.1:28080` | 요청 헤더로 action 선택 |
| Response REPLY | `127.0.0.1:28081` | 전체 HTTP 요청 수신 후 설정된 상태·본문 응답 및 close |
| Response WAIT | `127.0.0.1:28082` | 데이터를 소비하며 클라이언트 EOF 대기, 응답 없음 |
| Response SLOW_READ | `127.0.0.1:28083` | 설정한 크기와 간격으로 느리게 읽기, 응답 없음 |

추가 서버는 기본적으로 loopback에 바인딩하는 평문 HTTP/TCP 서비스다. HTTPS/HTTP/2는 기존 8443에서 제공한다.
콘솔의 **Mock 서비스 제어**에서 Netty와 response 엔진의 상태·주소를 확인하고 각각 시작·중지할 수 있다.
response의 REPLY·WAIT·SLOW 등 모든 바인딩은 하나의 실행기이므로 함께 제어한다. Spring HTTP/HTTPS와 테스트 콘솔은 계속 실행된다.
중지하면 해당 엔진의 연결·예약·스레드를 정리하고, 다시 시작하면 새 객체로 서비스한다. 다른 엔진은 계속 서비스한다.
처리 중에는 서비스 제어 버튼을 비활성화하고 완료·오류 결과를 표시한다. 포트 충돌로 시작에 실패하면 원인을 해소한 뒤 다시 시작할 수 있다.

| 제어 API | 동작 |
| --- | --- |
| `GET /test/services` | 실행 중인 추가 서버의 포트 목록(기존 API 유지) |
| `GET /test/service-engines` | 중지·오류·비활성 상태를 포함한 엔진 상태와 바인딩 정보 |
| `POST /test/services/netty/start` 또는 `/stop` | Netty 시작 또는 중지 |
| `POST /test/services/response/start` 또는 `/stop` | response 전체 바인딩 시작 또는 중지 |

POST는 `Content-Type: application/json`으로 호출한다. 브라우저에서 Origin을 보내면 콘솔과 동일 출처여야 한다.
지원하지 않는 엔진·작업은 400, 다른 출처는 403, 설정 비활성화·기동/중지 실패는 409다.
엔진 상태는 `RUNNING`, `STOPPED`, `STARTING`, `STOPPING`, `ERROR`, `DISABLED`이며 중지 상태에서도 마지막 바인딩 정보를 표시한다.
`enabled: false`는 실행 중 제어로 해제할 수 없으며 설정 수정 후 앱을 다시 시작해야 한다.

```powershell
curl.exe -X POST -H "Content-Type: application/json" --data "{}" http://localhost:8080/test/services/netty/stop
curl.exe -X POST -H "Content-Type: application/json" --data "{}" http://localhost:8080/test/services/netty/start
```
다른 포트에 대한 브라우저 CORS 허용이나 요청 프록시는 추가하지 않는다. 추가 서버의 action은 아래와 같이 직접 호출한다.

```powershell
curl.exe --http1.1 -i -H "x-response-action: ok" http://127.0.0.1:28080/
curl.exe --http1.1 -i -X POST -H "x-response-action: copy-and-send" --data-binary "hello" http://127.0.0.1:28080/
curl.exe --http1.1 -i -H "x-response-action: status" -H "x-status: 418" http://127.0.0.1:28080/
curl.exe --http1.1 -i -H "x-response-action: error-500" http://127.0.0.1:28080/
curl.exe --http1.1 -v -H "x-response-action: delay-close" -H "x-delay-amount: 1000" http://127.0.0.1:28080/
curl.exe --http1.1 -i http://127.0.0.1:28081/
# wait/slow는 응답이 없으므로 --max-time으로 클라이언트 대기 시간을 제한한다.
curl.exe --http1.1 --max-time 2 --data-binary "hello" http://127.0.0.1:28082/
```

Netty의 `default`, `ok`, `copy-and-send`, `error-500`, `status`, `close`, `fake-close`, `delay-close` action을 원래 구현 그대로 사용한다.
`close`와 `delay-close`는 최종 HTTP 응답 없이 연결을 닫으므로 클라이언트의 빈 응답 오류가 의도된 결과다.
응답 서버는 한 연결에서 설정된 응답을 한 번 전송하고 닫는다. wait/slow는 EOF, read timeout 또는 앱 종료까지 응답하지 않는다.

### Netty 설정

기본 파일은 `src/main/resources/netty-mock.yml`이며 원래 Netty 모듈의 `server`, `mock` 스키마와 설정 검증을 그대로 사용한다.
아래 Spring 설정으로 외부 Netty YAML을 지정할 수 있다. Netty 설정 파일에는 Spring `application.yml`의 다른 섹션을 넣지 않는다.

```yaml
mock:
  services:
    netty:
      enabled: true
      config: file:./netty-custom.yml
      arguments:
        - --server.worker-threads=4
        - --server.max-connections=200
```

`netty-custom.yml` 예:

```yaml
server:
  host: 127.0.0.1
  port: 28080
  worker-threads: 2
  max-content-length: 1048576
  request-timeout-ms: 30000
mock:
  header-name: x-response-action
  default-action: default
  scan-packages:
    - com.inzent.apim.utilities.netty.http.mockserver.processor
```

`arguments`는 Netty ConfigLoader의 `--key=value` scalar override이며 지정한 YAML보다 우선한다. `--config` 대신 Spring의 `config` 속성으로 파일을 지정한다.
외부 YAML의 누락 값은 원본 Netty 코드 기본값을 사용하므로, 포트 충돌을 피하려면 `server.port`를 명시한다(원본 기본값은 8080).
기본 내장 YAML은 `${NETTY_MOCK_HOST:127.0.0.1}`, `${NETTY_MOCK_PORT:28080}`를 사용한다. Spring HTTPS 포트 환경변수와 분리되어 있다.
내장 YAML 수정 없이 포트를 바꾸려면 `NETTY_MOCK_PORT=29080` 또는 `--mock.services.netty.arguments[0]=--server.port=29080`을 사용할 수 있다.
사용자 processor는 JAR에 포함하고 `mock.scan-packages`에 패키지를 추가한다. Spring의 annotation scanner를 사용하므로 실행 JAR 내부 중첩 라이브러리에서도 탐색한다.
전체 옵션은 [Netty 서버 사용법](../netty-http-mock-server/README.md)을 참고한다.

### HTTP mock response 설정

`application.yml` 또는 외부 Spring 설정에서 `mock.services.response`를 지정한다. `bindings`는 Spring 리스트 설정이므로 외부 설정으로 덮어쓸 때 유지할 바인딩도 모두 나열한다.

```yaml
mock:
  services:
    response:
      enabled: true
      max-clients: 64
      shutdown-timeout: 5s
      bindings:
        - name: custom-reply
          scenario: REPLY
          host: 127.0.0.1
          port: 28081
          delay: 250ms
          options:
            reuse-address: true
            backlog: 50
            server-receive-buffer-size: 8192
            receive-buffer-size: 8192
            send-buffer-size: 8192
            linger-seconds: -1
            keep-alive: false
            tcp-no-delay: true
            read-timeout-millis: 30000
            read-chunk-size: 1024
            read-interval: 100ms
            response-timeout: 10s
          reply:
            status: 201
            content-type: application/json; charset=UTF-8
            body: '{"created":true}'
            max-line-bytes: 8192
            max-header-bytes: 65536
            max-content-bytes: 16777216
        - name: delayed-close
          scenario: CLOSE
          host: 127.0.0.1
          port: 28084
          delay: 2s
```

`scenario`는 `REPLY`, `CLOSE`, `WAIT_FOR_CLIENT_DISCONNECT`, `SLOW_READ` 중 하나다. 이름은 비어 있지 않아야 하며 중복할 수 없다.
`delay`는 REPLY의 요청 수신 후 응답 전 지연 또는 CLOSE의 close 전 지연이다. wait/slow에는 적용하지 않으며 느린 읽기는 `options.read-interval`을 사용한다.
REPLY는 완전한 HTTP 요청을 받은 뒤 응답한다. `reply.max-*`는 요청 파서 한도이며 응답 본문 크기 제한이 아니다.
포트는 response 모듈에서 0~65535이며 0은 OS가 배정한다. 실제 주소는 `/test/services`에서 확인한다. Netty 포트는 1~65535다.
`max-clients`는 모든 response 바인딩이 공유하는 최대 연결 수다. 소켓 옵션의 기본값·범위와 종료 계약은 [response 서버 사용법](../http-mock-response-server/README.md)을 따른다.

각 엔진을 끄려면 `--mock.services.netty.enabled=false`, `--mock.services.response.enabled=false`를 사용한다. 환경변수는 `NETTY_MOCK_ENABLED`, `RESPONSE_MOCK_ENABLED`다.
기본 response 포트 환경변수는 `RESPONSE_MOCK_PORT`, `RESPONSE_WAIT_PORT`, `RESPONSE_SLOW_PORT`, 바인딩 주소는 `RESPONSE_MOCK_HOST`다.
프로세스 종료 시 열린 연결·예약·스레드를 함께 정리한다. 한 엔진이나 Spring 포트의 기동이 실패하면 앱 기동을 실패시키고 이미 시작한 엔진도 정리한다.
새 서비스 추가나 Spring 설정 변경은 앱 재시작이 필요하다. Netty는 콘솔에서 중지 후 다시 시작할 때 지정된 외부 Netty YAML을 다시 읽는다.

## TLS 인증서 구성

생성된 인증서를 포함하므로 빌드 후 바로 HTTPS로 실행된다.

- 키 저장소: `src/main/resources/tls/localhost.p12` (PKCS12, alias `localhost`)
- 공개 인증서: `src/main/resources/tls/localhost.crt` (PEM)
- 로컬 mock 전용 저장소 비밀번호: `local-mock-changeit`
- RSA 3072 / SHA256withRSA, SAN: `localhost`, `127.0.0.1`, `::1`, 유효기간 3650일

개인키와 알려진 비밀번호는 로컬 mock 실행을 위해 배포물에 포함한다. 운영용 인증서로 사용하지 않는다.
curl 예제는 `--cacert`로 이 공개 인증서만 신뢰한다. 간단한 수동 테스트에서는 `-k`로 인증서 검증을 생략할 수도 있다.

인증서를 다시 생성하려면 기존 `localhost.p12`, `localhost.crt`를 백업하여 다른 위치로 옮긴 뒤 JDK의 keytool로 생성하고 재빌드한다.

```powershell
./scripts/New-LocalCertificate.ps1
mvn clean package
```

외부 인증서는 `SSL_KEY_STORE`(예: `file:C:/certs/server.p12`), `SSL_KEY_STORE_PASSWORD`, `SSL_KEY_ALIAS` 환경변수로 지정한다.
재생성 스크립트의 `-Password`를 변경한 경우 실행 시 `SSL_KEY_STORE_PASSWORD`도 맞춘다.
기본 테스트는 포함된 공개 인증서만 신뢰하며 호스트명 및 인증서 유효성 검증을 유지한다.

SSL 및 HTTP/2 설정은 [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html)를 따른다.

## 브라우저 테스트 콘솔

- HTTP: [http://localhost:8080/](http://localhost:8080/)
- HTTPS: [https://localhost:8443/](https://localhost:8443/)

상단의 HTTP/HTTPS 콘솔 링크로 각각 새 탭을 열 수 있다. 설정한 포트가 기본값과 달라도 실제 커넥터 포트(`/test/config`)를 자동 조회한다.
HTTPS는 처음 접속할 때 self-signed 인증서 경고를 수락해야 한다.

1. **REST**: 메서드·쿼리·JSON 형식 헤더·본문을 입력한 뒤 요청을 전송한다. 상태 코드, 응답 헤더, 본문, 경과 시간과 브라우저 전송 프로토콜을 표시한다. GET은 본문 입력을 비활성화하며 일부 요청 헤더는 브라우저가 제한한다.
2. **HTTP/2**: HTTPS 콘솔에서 Ping을 보내 ACK와 서버 `protocol`을 확인한다. HTTP 콘솔에서는 버튼을 비활성화한다.
3. **SSE**: 스트림 시작 후 `time` 이벤트와 수신 수를 확인한다. 10회 수신하면 연결을 닫아 자동 재연결을 방지하며 중간에 중지할 수도 있다.
4. **WebSocket**: HTTP 페이지는 WS, HTTPS 페이지는 WSS로 연결한다. 메시지 10개와 서버 종료 코드 1000을 확인하거나 수동으로 종료한다.

모든 테스트 호출은 현재 페이지와 같은 출처를 사용한다. HTTP와 HTTPS를 한 탭에서 교차 호출하지 않으므로 별도의 CORS 허용이나 혼합 콘텐츠 예외가 필요 없다.
브라우저 fetch는 HTTP 버전을 강제할 수 없다. HTTPS REST가 HTTP/2로 협상되면 기존 API 제한에 따라 505가 표시되며, HTTP/1.1 REST 성공 테스트는 HTTP 콘솔을 사용한다.
HTTP/2 Ping의 응답 `protocol`과 `nextHopProtocol` 표시로 실제 협상을 확인할 수 있다. 브라우저가 Resource Timing 정보를 제공하지 않으면 `브라우저 미제공`으로 표시한다.
SSE와 WebSocket의 연결 제한 시간은 15초이며 페이지 이동 시 연결을 정리한다.

## API 목록

| 경로 | 메서드/프로토콜 | 응답 |
| --- | --- | --- |
| `/api/echo` | GET, POST, PUT, DELETE / HTTP 또는 HTTPS의 HTTP/1.1 | 요청 메타데이터와 다중 헤더 JSON |
| `/http2/ping` | GET / HTTPS의 HTTP/2 전용 | `ack: true`, 프로토콜, 서버 시각 JSON |
| `/sse/call` | GET / HTTP의 HTTP/1.1, HTTPS의 HTTP/1.1 또는 HTTP/2 | `time` 이벤트 10회 후 스트림 완료 |
| `/ws/call` | WS 또는 WSS / HTTP/1.1 Upgrade | 시각 JSON 10회 후 close code 1000 |

HTTP 8080에서는 `/api/*`, `/sse/*`, `/ws/*`를 호출할 수 있다. `/http2/*`는 HTTPS 8443에서만 처리한다.
HTTP 커넥터는 HTTP/1.1 전용이며 h2c Upgrade를 제공하지 않는다. HTTP 요청은 HTTPS로 자동 리다이렉트하지 않는다.

```powershell
curl.exe --http1.1 -H "X-Test: hello" http://localhost:8080/api/echo
curl.exe --http1.1 -N http://localhost:8080/sse/call
# HTTP의 /http2/*는 403 응답
curl.exe -i http://localhost:8080/http2/ping
```

SSE/WS 첫 메시지는 연결 약 1초 후, 이후 1초 간격으로 10회 전송한다. 서버 시각은 UTC ISO-8601이다.
순번은 연결마다 1부터 시작한다. 오류/종료/타임아웃이 발생하면 해당 타이머를 취소한다.

### REST echo

Windows PowerShell에서는 `curl` 별칭 대신 `curl.exe`를 사용한다.

```powershell
curl.exe --cacert src/main/resources/tls/localhost.crt --http1.1 -H "X-Test: hello" "https://localhost:8443/api/echo?name=test"
curl.exe --cacert src/main/resources/tls/localhost.crt --http1.1 -X POST -H "Content-Type: text/plain" --data "hello" https://localhost:8443/api/echo
curl.exe --cacert src/main/resources/tls/localhost.crt --http1.1 -X PUT -H "X-Test: update" https://localhost:8443/api/echo
curl.exe --cacert src/main/resources/tls/localhost.crt --http1.1 -X DELETE https://localhost:8443/api/echo
```

```json
{"method":"GET","uri":"/api/echo","query":"name=test","protocol":"HTTP/1.1","headers":{"host":["localhost:8443"],"x-test":["hello"]}}
```

헤더 이름은 소문자로 정규화하고 중복값은 배열에 보존한다. 쿼리가 없으면 `query`는 `null`이다.
요청 본문은 반사하지 않는다. `Authorization`, `Cookie`를 포함한 모든 수신 헤더가 응답에 표시되므로 로컬 mock 용도로 사용한다.

### HTTP/2 ping

HTTP/2 지원 curl 빌드가 필요하다(`curl.exe -V`의 Features에 HTTP2 확인).

```powershell
# TLS ALPN으로 HTTP/2(h2) 협상
curl.exe --cacert src/main/resources/tls/localhost.crt -v --http2 https://localhost:8443/http2/ping
```

```json
{"ack":true,"protocol":"HTTP/2.0","serverTime":"2026-11-09T05:00:00Z"}
```

HTTP/2는 연결 단위로 협상되므로 URL 방문만으로 업그레이드되지 않는다. 클라이언트가 TLS ALPN으로 h2를 협상해야 한다.
HTTP 8080의 `/http2/*` 접근은 `403 Forbidden`이다. HTTPS 8443의 `/http2/*`에 HTTP/1.1로 접근하거나 `/api/*`에 HTTP/2로 접근하면 `505 HTTP Version Not Supported`를 반환한다.
ACK는 애플리케이션 상태 확인 응답이며 HTTP/2 PING 프레임 ACK와 구분한다.
브라우저에서 HTTPS에 접근할 때는 self-signed 인증서를 신뢰하거나 인증서 경고를 수락한다.

### SSE

```powershell
curl.exe --cacert src/main/resources/tls/localhost.crt --http1.1 -N https://localhost:8443/sse/call
```

```text
id:1
event:time
data:{"sequence":1,"serverTime":"2026-11-09T05:00:01Z"}

```

브라우저에서 같은 출처로 실행한다. EventSource는 서버가 스트림을 완료하면 재연결하므로 10번째 이벤트 수신 시 클라이언트도 닫는다.

```javascript
const source = new EventSource('/sse/call');
source.addEventListener('time', event => {
  const data = JSON.parse(event.data);
  console.log(data);
  if (data.sequence === 10) source.close();
});
source.onerror = () => source.close();
```

### WebSocket

`https://localhost:8443`과 같은 출처의 브라우저 개발자 콘솔에서 실행한다. 외부 도구는 Origin을 생략하거나 서버와 같은 Origin을 사용한다.

```javascript
const socket = new WebSocket('wss://localhost:8443/ws/call');
socket.onmessage = event => console.log(JSON.parse(event.data));
socket.onclose = event => console.log('closed', event.code); // 정상 완료: 1000
socket.onerror = event => console.error(event);
```

HTTP 페이지에서는 `new WebSocket('ws://localhost:8080/ws/call')`로 연결한다.
Origin을 보내는 클라이언트는 연결 대상과 같은 스킴·호스트·포트의 Origin을 사용한다.
브라우저 SSE도 페이지와 같은 출처의 `/sse/call`을 사용하면 HTTP와 HTTPS에서 모두 동작한다.

수신 예: `{"sequence":1,"serverTime":"2026-11-09T05:00:01Z"}`.
연결만 하면 송신을 시작한다. 클라이언트 텍스트 메시지에 대한 echo 기능은 없다.
WebSocket은 표준 HTTP/1.1 Upgrade를 사용하며 HTTP/2 확장 CONNECT(RFC 8441)는 구현하지 않는다.

구성 및 설계는 [project.md](project.md)를 참고한다.
