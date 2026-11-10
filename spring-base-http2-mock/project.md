# spring-base-http2-mock 구성 및 실행 계획

## Git 반영 준비 (2026-11-10)

사용자의 push 요청에 따라 Spring Boot HTTP/HTTPS·HTTP/2·SSE·WebSocket 서버, 브라우저 테스트 콘솔, Netty/response 엔진 통합 및 콘솔 시작·중지 기능을 관련 테스트·문서·로컬 mock 인증서와 함께 develop에 반영한다.
관련 response 모듈의 named scenario 등록 overload도 포함한다. `pojo-definition-manager/command.md`의 별도 변경은 포함하지 않는다.
푸시 전 Netty verify, response 전체 테스트, Spring 전체 테스트 및 Node 콘솔 회귀 테스트를 다시 실행한다. Maven install 단계는 테스트를 실행하지 않는 기존 정책을 유지한다.
푸시 직전 재검증: Netty 48개(실행 JAR/임베디드 IT 포함), response 59개, Spring 39개로 Maven 146개와 Node 4개 테스트가 모두 통과했다. 관련 34개 파일만 반영하며 target 산출물·임시 브라우저 테스트 의존성은 제외한다.

## 콘솔 Mock 엔진 시작·중지 (2026-11-10)

Netty와 response 엔진을 각각 제어한다. response의 모든 바인딩은 단일 실행기와 공유 max-clients 한도를 유지하므로 함께 시작·중지한다.
Spring HTTP/HTTPS와 테스트 콘솔은 제어 대상에서 제외한다. 중지 후 시작은 종료한 객체를 재사용하지 않고 새 서버·실행기를 생성한다.
기존 `/test/services`는 실행 중인 포트 목록을 유지하며, `/test/service-engines`는 STOPPED/DISABLED/ERROR를 포함한 엔진 상태와 바인딩 주소를 제공한다.
`POST /test/services/{netty|response}/{start|stop}`는 JSON 요청만 받으며 브라우저 Origin이 있으면 동일 출처인지 검사한다.
전환 작업을 직렬화하고 중복 시작·중지를 무동작 처리한다. 실패한 시작은 해당 엔진만 정리하고 다른 엔진은 계속 서비스한다. 정리 실패 참조는 재시도를 위해 유지한다.
UI는 처리 중 버튼 비활성화, 결과·오류 표시, 상태 새로 고침, 비활성 설정 안내를 제공한다.

- [x] 실제 API로 독립 중지·재시작, 기존 콘솔 유지, 활성 연결 정리 및 시작 실패 후 재시도 테스트 작성·실패 확인.
- [x] 엔진별 수명주기·상태·제어 API 구현.
- [x] 콘솔 버튼·처리 중/실패 상태 및 문서 추가.
- [x] 전체 테스트·실제 브라우저 버튼 검증·패키징·서버 재시작.

검증: Spring 전체 39개 테스트 실패·오류 0, Node 콘솔 회귀 테스트 4개 통과.
실제 HTTP/HTTPS 제어, 중복·동시 시작, 활성 연결 정리, 시작 포트 충돌 후 ERROR 및 재시도, 다른 엔진 유지, 비활성 설정 거부, 다른 Origin 403, 일반 form POST 415를 확인했다.
Edge 브라우저에서 엔진 버튼 조작·모든 엔진 중지 후 콘솔 유지·묶음 바인딩·실패 표시·처리 후 상태 재조회 중 새 버튼도 비활성화·모바일 가로 넘침 없음·JavaScript 오류 없음을 검증했다.

## 여러 mock 엔진 동시 서비스 (2026-11-10)

기존 Netty 및 HTTP mock response 모듈을 Maven 라이브러리로 재사용한다. Spring 컨트롤러에서 동작을 흉내 내거나 별도 JVM을 시작하지 않는다.
HTTP/HTTPS 8080/8443 및 기존 경로·브라우저 콘솔은 유지한다. Netty HTTP/1.1은 28080, response 모듈은 REPLY 28081, WAIT_FOR_CLIENT_DISCONNECT 28082, SLOW_READ 28083을 기본으로 연다.
모두 Spring 수명주기에 연결해 동시 시작·종료하며 부분 기동 실패 시 이미 열린 자원도 정리한다.

- Netty: `library` classifier 의존성, 기존 ConfigLoader 및 모든 action 재사용. `mock.services.netty.config`로 classpath 또는 외부 YAML을 지정하고 `arguments`로 기존 scalar override 적용. 기본 파일 `netty-mock.yml`은 별도 환경변수를 사용해 Spring SERVER_PORT와 충돌하지 않는다.
- Spring Boot 실행 JAR의 중첩 라이브러리는 Spring annotation scanner로 processor를 찾아 기존 ActionRegistry에 전달한다.
- Response: `mock.services.response`의 실행기 한도 및 다중 바인딩 설정, 기존 MockOptions 전체 소켓 옵션 제공. REPLY/CLOSE 및 기존 WAIT_FOR_CLIENT_DISCONNECT/SLOW_READ 제공.
- 설정별 고정 응답을 지원하기 위해 response 모듈에 이름과 MockScenario를 받는 addMock overload를 추가한다. 기존 enum API는 유지한다.
- `/test/services` 및 브라우저 콘솔에서 실제 서비스 주소/상태를 읽기 전용으로 확인한다. 다른 포트의 action 호출은 curl 등으로 직접 수행하며 기존 CORS 정책은 유지한다.
- 모듈 간 소스 복사는 하지 않는다. 의존 모듈을 먼저 설치하는 빌드 스크립트 및 README를 제공한다. Maven install은 테스트를 생략한다.

실행 계획:

- [x] response named scenario API 실패 테스트 → 최소 구현 및 기존 테스트 검증.
- [x] 동시 서비스·설정 바인딩·Netty action·응답·종료·부분 실패 정리 테스트를 먼저 작성.
- [x] properties, Netty 설정 로드, Spring lifecycle, 실제 서비스 상태 API 구현.
- [x] 브라우저 상태 표시 및 문서·빌드 스크립트 추가.
- [x] 세 모듈 테스트, 실행 JAR 중첩 의존성/processor 탐색 및 실제 동시 서비스 검증.

검증: Spring 전체 36개 테스트와 Node 콘솔 회귀 테스트 4개 통과. Netty 기존 verify 및 response 전체 테스트 통과.
테스트는 response 바인딩 실패·Spring 웹 포트 실패·잘못된 소켓 설정 시 Netty 포트 해제, 비활성 엔진 설정 미로드, 커스텀 Netty 헤더·YAML scalar override, 여러 응답 시나리오 및 종료 후 포트 재사용을 포함한다.
실행 환경의 기존 다른 서비스가 18081을 사용 중이어서 추가 서버 기본 포트를 28080~28083으로 정했다. 기존 다른 서비스와 무관한 변경 파일은 수정하지 않는다.
최종 결과: Netty 48개(단위·실행 JAR/임베디드 IT 포함), response 59개, Spring 36개로 총 143개 테스트 실패·오류·건너뜀 0. Node 4개 테스트 통과.
실제 Spring Boot 실행 JAR에서 중첩 library processor 탐색과 Netty ok/copy/status/error/close/fake-close/delay-close, 설정된 JSON 응답 및 기존 TLS HTTP/2 ACK를 확인했다.
Edge 브라우저 HTTP/HTTPS 콘솔에서 4개 보조 서비스의 실제 포트·새로 고침·390px 모바일 가로 넘침 없음·JavaScript 오류 없음 검증을 완료했다.
Spring scanner가 원래 제외하는 abstract/non-independent annotated 타입도 native ActionRegistry에 전달한다. 잘못된 processor를 조용히 무시하지 않고 원본처럼 기동 시 거부하도록 회귀 테스트로 검증했다.

## 설계

독립 Maven 프로젝트, Java 17 이상, Spring Boot 3.5.16, Spring MVC와 내장 Tomcat을 사용한다.
클래스는 `com.inzent.apim.utilities.http2mock` 아래에 둔다.
HTTPS 기본 포트는 8443, HTTP 기본 포트는 8080이다. HTTP/2는 HTTPS에서 TLS ALPN의 `h2`로만 협상한다.
REST와 SSE는 HTTP/HTTPS 양쪽, WebSocket은 WS/WSS 양쪽에서 서비스한다. HTTP 버전은 연결에서 협상하고 필터가 경로별 버전 및 TLS 사용 여부를 검증한다.

| 경로 | 동작 |
| --- | --- |
| `/api/echo` | GET/POST/PUT/DELETE, HTTP/1.1 요청 메서드·URI·쿼리·프로토콜·다중 헤더를 JSON으로 반환. 본문은 반사하지 않음 |
| `/http2/ping` | GET, HTTP/2에서 애플리케이션 ACK JSON 반환. HTTP/2 PING 프레임 자체는 Tomcat이 처리 |
| `/sse/call` | GET, `time` 이벤트에 UTC ISO-8601 서버 시각과 순번을 1초마다 10회 전송 후 완료 |
| `/ws/call` | 표준 WebSocket, 서버 시각과 순번 JSON을 1초마다 10회 전송 후 정상 종료(1000) |

HTTP의 `/http2/*` 요청은 403을 반환한다. HTTPS에서 `/api/*`에 HTTP/2 요청과 `/http2/*`에 HTTP/1.1 요청은 505를 반환한다. TLS ALPN을 사용하므로 h2c Upgrade 안내 및 426 응답을 제거했다.
SSE는 HTTP/1.1과 HTTP/2를 허용한다. WebSocket은 HTTP/1.1 Upgrade 방식이다(RFC 8441 HTTP/2 WebSocket은 범위 밖).
타이머는 Spring 관리 TaskScheduler를 공유하며 연결마다 작업을 등록한다. 정상 완료·타임아웃·오류·클라이언트 종료 시 취소한다.
첫 전송은 연결 약 1초 후, 마지막 전송 및 종료는 약 10초 후다. 네트워크·스케줄링 지연이 있을 수 있다.
헤더를 그대로 표시하는 로컬 mock 도구이므로 인증정보가 포함된 요청은 사용에 주의한다.

## 구현 계획

- [x] 실제 임의 포트 서버에 HTTPS HTTP/1.1, TLS HTTP/2, 버전 거부, SSE, WSS 통합 테스트 작성.
- [x] 애플리케이션, 프로토콜 필터, REST/SSE 컨트롤러, WebSocket 핸들러와 스케줄러 구현.
- [x] 전체 테스트로 10회 전송·종료·시간 간격·다중 헤더·메서드 제한 검증.
- [x] README 사용법 작성, package 소스 JAR·문서 포함 검증, install 테스트 생략 검증.

## 브라우저 테스트 페이지 설계 및 계획

루트 `/`에 외부 의존성 없는 HTML/CSS/JavaScript 콘솔을 포함한다. 기존 Spring 정적 리소스 방식으로 제공하고 별도 SPA 빌드나 프록시는 추가하지 않는다.
`GET /test/config`는 실제 HTTP/HTTPS 커넥터 포트를 반환한다. 페이지는 현재 hostname과 이 포트로 HTTP/HTTPS 페이지 전환 링크를 만들며 변경된 포트 및 IPv6를 지원한다.
요청은 현재 페이지와 같은 출처로 전송하여 CORS·혼합 콘텐츠 정책과 WS Origin 제한을 유지한다.

- REST: GET/POST/PUT/DELETE 선택, 쿼리·JSON 헤더·본문 입력, 응답 상태·헤더·본문·경과 시간 표시.
- HTTP/2: HTTPS 페이지에서 `/http2/ping` 호출. 서버 protocol 및 브라우저 Resource Timing의 nextHopProtocol을 표시한다.
- SSE: EventSource로 `time` 이벤트 표시. 10회 수신하면 close하여 자동 재연결을 막고, 수동 중지·오류·타임아웃 처리.
- WebSocket: 현재 스킴에 따라 WS/WSS 연결, 메시지·수신 수·종료 코드 표시, 수동 종료·타임아웃 처리.
- 요청별 로그를 분리하고 서버 응답과 사용자 입력은 textContent로만 표시한다. 페이지 이탈 시 스트림과 요청을 정리한다.
- 브라우저 fetch는 HTTP 버전을 강제할 수 없다. HTTPS REST의 HTTP/2 협상 시 기존 505 응답을 그대로 표시하고 HTTP 페이지 사용 안내를 제공한다.
- HTTPS self-signed 인증서 수락 안내 및 HTTP/HTTPS 별도 탭 전환을 제공한다.

- [x] 페이지 및 포트 설정 엔드포인트 테스트 실패 확인 후 구현.
- [x] 정적 콘솔 구현, REST/SSE/WS 동작 및 오류·중지 처리.
- [x] 전체 Maven 테스트 및 실제 브라우저 화면/호출 검증.
- [x] README 갱신, 정적 파일 포함 패키징 및 서비스 재시작.

검증: Maven 28개 테스트 통과. `node --test src/test/js/test-console.test.cjs` 4개 테스트 통과.
실제 Edge 브라우저 검증: HTTP REST 4개 메서드, 잘못된 헤더 JSON 입력, 응답 HTML 이스케이프, HTTPS h2 ACK 및 REST 505, HTTP/HTTPS SSE 및 WS/WSS 각각 10회 수신·close 1000, 수동 중지, 시간 초과, 390px 모바일 가로 넘침 없음, JavaScript 실행 오류 없음.
리뷰에서 WebSocket 시간 초과 이후 close 1000이 오면 성공으로 표시되던 문제를 발견했다. 실제 브라우저의 지연된 WebSocket 모의 연결로 실패를 재현한 뒤 종료 원인을 보존하도록 수정했다.
수동 종료는 중립 상태, 시간 초과 및 10회 미만 서버 종료는 오류, 서버 정상 종료와 10회 수신이 모두 충족되어야 성공으로 표시한다.

## Maven 빌드 정책

`mvn install`에서 테스트가 실행되지 않도록 `skipTests=true`를 기본값으로 둔다. package도 기본적으로 테스트를 생략한다.
검증은 `mvn test -DskipTests=false` 또는 `mvn package -DskipTests=false`로 명시적으로 실행한다.
package에서 `jar-no-fork` 소스 JAR을 첨부하고 모듈 루트 `*.md`를 `META-INF/docs/spring-base-http2-mock/`에 넣는다.

## HTTP/HTTPS 동시 서비스 구성 및 계획

- `HttpConnectorConfiguration`: 공식 `TomcatServletWebServerFactory` customizer로 HTTP/1.1 전용 커넥터 추가. HTTP/2 upgrade protocol은 등록하지 않는다.
- `mock.http.port`: 기본 8080, `HTTP_PORT` 환경변수 또는 `--mock.http.port`로 변경. HTTPS는 기존 `server.port`/`PORT`, 기본 8443을 유지한다.
- `ProtocolFilter`: 정규화된 servlet path 검사 유지. `/http2/*`는 `isSecure()`가 false이면 403, HTTPS에서도 HTTP/2가 아니면 505로 거부한다.
- HTTP 요청의 자동 HTTPS 리다이렉트나 전역 CORS 허용은 추가하지 않는다. WS/WSS는 각 연결 대상과 동일 Origin 정책을 유지한다.
- 테스트는 HTTP·HTTPS 포트를 모두 0으로 지정해 실제 임의 포트에 기동하고 커넥터의 실제 바인딩 포트를 조회한다.

- [x] HTTP 커넥터가 없어서 테스트가 실패하는 것을 먼저 확인.
- [x] 추가 커넥터와 `/http2/*` TLS 제한 구현.
- [x] HTTP/HTTPS 각각 REST 4개 메서드, SSE 및 WS/WSS 10초 송신, HTTPS HTTP/2 ACK, HTTP 경로 우회 거부 등 전체 26개 테스트 검증.
- [x] README 사용법 갱신 및 package/install·실행 JAR 두 포트 검증.

`mvn test -DskipTests=false`: 26개 테스트, 실패 0, 오류 0. 독립 코드 리뷰에서 추가 중요 문제 없음.
`mvn install`: 테스트 실행 생략 및 소스 JAR 설치 확인. 실행 JAR을 두 임의 포트로 기동해 HTTP REST 200, HTTP `/http2/ping` 403, HTTPS HTTP/2 ACK 200 및 인증서·문서 포함을 확인했다.

## 참고

- [Spring Boot HTTP/2 설정](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html)

## TLS 전환 구성 및 실행 계획

- `scripts/New-LocalCertificate.ps1`: JDK keytool로 RSA 3072 / SHA256withRSA self-signed 인증서 생성 및 PEM 공개 인증서 내보내기.
- `src/main/resources/tls/localhost.p12`: alias `localhost`, PKCS12. 저장소 비밀번호는 로컬 mock 기본값 `local-mock-changeit`.
- `src/main/resources/tls/localhost.crt`: 공개 인증서. SAN은 `localhost`, `127.0.0.1`, `::1`, 유효기간 3650일.
- `application.yml`: SSL 활성화, TLSv1.2/TLSv1.3, HTTP/2 활성화. `SSL_KEY_STORE`, `SSL_KEY_STORE_PASSWORD`, `SSL_KEY_ALIAS`로 외부 저장소 지정 가능.
- 로컬 mock 편의를 위해 개인키를 배포물에 포함한다. 운영용 인증서는 별도로 주입한다.
- 통합 테스트는 이 공개 인증서만 신뢰하는 SSLContext를 사용한다. 전역 trust-all이나 호스트명 검증 비활성화는 사용하지 않는다.

- [x] HTTPS HTTP/2 및 평문 HTTP 거부 테스트를 먼저 작성, 기존 서버에서 SSL 오류 및 잘못된 평문 200 응답 실패 확인.
- [x] Self-signed 인증서 생성, SSL 설정 및 ALPN 안내 적용.
- [x] REST/SSE 클라이언트 HTTPS, WebSocket 클라이언트 WSS 전환. 미신뢰 인증서 거부 검증 추가.
- [x] 전체 17개 테스트 및 package 인증서·소스 JAR·문서 포함 검증.
- [x] 실행 JAR의 HTTPS 기동 및 응답 검증: curl의 공개 인증서 명시 신뢰로 REST 성공, TLS HTTP/2 ACK 성공.

`mvn package -DskipTests=false`: 17개 테스트, 실패 0, 오류 0. 실행 JAR에 `BOOT-INF/classes/tls/localhost.p12`, `localhost.crt` 포함 확인.
기존 mock 프로세스의 JAR 잠금을 해제한 뒤 빌드했다. target 디렉터리는 외부 터미널에서 사용 중이어서 디렉터리 삭제만 실패했으며, 내부 파일 제거 후 package로 전체 재컴파일 및 검증을 완료했다.

## 리뷰 및 검증 기록

- 초기 실제 서버 테스트: 엔드포인트 404 및 HTTP/2 미협상 실패를 확인한 후 구현, 최초 8개 테스트 통과.
- SSE를 HTTP/1.1 및 HTTP/2에서 수신 시각까지 검증하도록 확장. 스케줄 취소와 송신 실패 시 작업 제거 검증 추가.
- 독립 코드 리뷰에서 raw URI와 MVC 정규화 경로 차이에 의한 버전 검사 우회를 발견했다.
  `/%61pi/echo`, `/api;v=1/echo`가 잘못 200을 반환하는 회귀 테스트 실패를 확인하고 `getServletPath()` 기준 검사로 수정했다.
  `/%68ttp2/ping`, `/http2;v=1/ping`도 회귀 테스트에 포함했다.
- 기본 테스트 생략 정책은 install 요구를 단순하게 충족하기 위한 선택이다. package 시 검증이 필요하면 `-DskipTests=false`를 명시한다.
- 최종 `mvn clean package -DskipTests=false`: 15개 테스트, 실패 0, 오류 0. 소스 JAR 및 실행 JAR 내부 Markdown 문서 포함 확인.
- `mvn install`: `Tests are skipped.` 확인, 실행 JAR·소스 JAR 설치 성공.
