# spring-base-http2-mock 프로젝트 구성

최종 정리: 2026-11-11. 이 문서는 현재 구성과 실행 계약을 설명한다. 실행 예제와 옵션 사용법은 [README.md](README.md)를 참고한다.

## 목적과 모듈 구성

Java 17 이상, Spring Boot 3.5.16, Spring MVC와 내장 Tomcat 기반의 독립 Maven 프로젝트다. 클래스는 `com.inzent.apim.utilities.http2mock` 아래에 둔다.
HTTP/HTTPS·HTTP/2·SSE·WebSocket과 별도 mock 엔진을 같은 JVM에서 제공하고, payload 생성기를 사용한 동시·반복 요청 결과를 SSE로 수집한다.

| 의존 모듈 | 역할 |
| --- | --- |
| `http-payload-generator` | 원문 생성, ContentType/Encoding, chunked/fixed-length 헤더 및 압축 |
| `netty-http-mock-server` | `library` classifier로 기존 Netty action 서버를 임베딩 |
| `http-mock-response-server` | named scenario와 다중 바인딩 응답 서버를 임베딩 |

소스를 복사하거나 별도 JVM을 띄우지 않는다. `scripts/Build-WithMockServers.ps1`는 payload → Netty → response → Spring 순으로 모듈을 설치한다.

## 리스너와 프로토콜

| 리스너 | 기본 주소 | 구성 |
| --- | --- | --- |
| Spring HTTP | `localhost:8080` | `mock.http.port` / `HTTP_PORT`, HTTP/1.1 커넥터 |
| Spring HTTPS | `localhost:8443` | `server.port` / `PORT`, TLS ALPN h2 및 HTTP/1.1 |
| Netty mock | `127.0.0.1:28080` | `mock.services.netty` |
| Response REPLY / WAIT / SLOW | `127.0.0.1:28081` / `28082` / `28083` | `mock.services.response.bindings` |

`ProtocolFilter`가 정규화된 servlet path로 경로별 TLS/버전을 검사한다. HTTP의 `/http2/*`는 403, HTTPS에서 HTTP/1.1의 `/http2/*`와 HTTP/2의 `/api/*`는 505를 반환한다.
SSE는 HTTP/1.1과 HTTPS HTTP/2를 허용한다. WebSocket은 HTTP/1.1 Upgrade 방식이며 WS/WSS를 제공한다. 자동 HTTPS redirect, h2c, HTTP/2 WebSocket은 제공하지 않는다.

## 엔드포인트

| 경로 | 메서드 | 동작 |
| --- | --- | --- |
| `/api/echo` | GET/POST/PUT/DELETE | 요청 메서드·URI·쿼리·프로토콜·다중 헤더 JSON, 본문 반사 없음 |
| `/api/execute-mock-request` | POST JSON | 백그라운드 payload 조합 요청, 같은 연결에서 SSE 응답 |
| `/http2/ping` | GET | HTTPS HTTP/2의 애플리케이션 ACK JSON; 실제 PING 프레임은 Tomcat 처리 |
| `/sse/call` | GET | 서버 UTC 시각과 순번을 1초마다 10회 보내고 종료 |
| `/ws/call` | WebSocket | 서버 시각과 순번을 1초마다 10회 보내고 close 1000 |
| `/test/config` | GET | 실제 Spring HTTP/HTTPS 포트 |
| `/test/services` | GET | 실행 중인 보조 mock 서비스 주소 |
| `/test/service-engines` | GET | 엔진 설정·상태·바인딩·오류 |
| `/test/services/{netty\|response}/{start\|stop}` | POST JSON | 엔진별 시작·중지; Origin이 있으면 동일 출처 검사 |
| `/` | GET | 외부 의존성 없는 브라우저 테스트 콘솔 |

## Payload 배열 조합 실행

`ExecuteMockRequest`는 threadCount, loopCount, endpointUrl, 선택 headers와 5개 payload 배열을 받는다.
`chunked`, `contentType`, `contentEncoding`, `contentLength`, `blockSize`는 값이 하나여도 배열로 입력한다. 배열은 불변 복사하며 빈 배열, null, null 요소, 단일 값 형식은 거부한다.

각 worker가 매 loop마다 전체 카테시안 곱을 순차 수행한다.

```text
조합 수 = chunked.length × contentType.length × contentEncoding.length × contentLength.length × blockSize.length
총 요청 수 = threadCount × loopCount × 조합 수
```

입력 순서와 중복 항목을 유지한다. chunked → contentType → contentEncoding → contentLength → blockSize 순으로 중첩하며 blockSize가 가장 빨리 바뀐다.
전체 조합을 객체 목록으로 생성하지 않고 인덱스를 mixed-radix로 해석해 실행 시 선택한다. 잘못된 타입/길이 조합, long overflow와 요청 수 상한은 작업을 시작하기 전에 검사한다.

`MockHttpTransport`는 생성기의 동기 callback 안에서 원문 스트림을 소비하고 HTTP/1.1 POST를 전송한다. chunked는 길이 미지정 publisher, fixed-length는 실제 인코딩 길이를 사용한다.
Content-Type/Encoding은 생성기의 값을 사용하며 framing은 JDK HttpClient가 관리한다. redirect는 추적하지 않고 HTTPS 인증서·호스트명은 JVM 기준으로 검증한다.
추가 헤더는 최대 32개이며 Host, Connection, Expect, Upgrade 및 본문 타입·인코딩·길이·framing 헤더는 직접 지정할 수 없다.

## SSE와 집계 계약

| 이벤트 | 전송 내용 |
| --- | --- |
| `started` | jobId, 스레드·반복·조합 수, 총 요청 수, endpointUrl, 시작 시각 |
| `response` | **endpointUrl, status 두 필드만**. HTTP 응답을 받지 못하면 status:null |
| `summary` | 계획·시작·완료·전달·성공·실패·남은 건수, 상태/오류별 건수, 수신 바이트와 시간 통계, 취소 정보 |

개별 response는 완료 순서로 보내며 SSE id, 본문, 응답 헤더, 개별 오류, 요청/조합 메타데이터는 보내지 않는다. summary에서 `responseSummaries`를 제외하며 본문 샘플도 보관하지 않는다.
`ungroupedResponseCount`는 기존 통계 호환을 위해 유지한다. 내부 응답 구분 키는 max-summary-groups 한도까지만 추적하며 초과분도 전체 성공/실패/상태 집계에 포함한다.
대상 본문은 끝까지 읽어 전체 수신량과 완료 여부를 집계한다. 오류 없이 전체 응답을 받은 2xx만 성공이다. 이미 HTTP 200을 받았어도 본문 timeout이면 status:200 이벤트와 실패 summary를 보낸다.
최종 summary를 전송하면 SSE를 종료한다. 클라이언트가 먼저 연결을 닫으면 summary를 전달할 수 없다. 결과 저장·재전송은 제공하지 않는다.

## 실행 제한과 자원 관리

`MockRequestExecutionService`는 한도가 있는 worker/coordinator pool, 작업별 bounded event queue, admission semaphore와 deadline scheduler를 사용한다.
worker당 반복 실행하고 요청마다 Future를 누적하지 않는다. SSE 소비가 느리면 queue로 backpressure를 적용한다.
요청 timeout은 payload 생성·HTTP 전송·본문 읽기를 포함한다. job timeout, 클라이언트 종료, 앱 종료 시 worker를 interrupt하고 응답 스트림을 닫는다. 압축 생성 중 interrupt는 payload encoder에서도 감지한다.
취소된 queued task와 실행 중인 task의 종료를 구분하며 실제 worker 정리가 끝난 뒤 admission을 반납한다.
SSE 쓰기가 클라이언트에서 막히면 HTTP worker는 job timeout에 중단되지만 슬롯 회수는 전송/연결 정리까지 지연될 수 있다.

기본 `mock.execution` 제한은 16 threads/job, 동시 4 jobs, 총 100000 requests/job, 원문 1 GiB/request, event queue 64개, 내부 응답 키 256개다.
connect/request/job timeout은 각각 5초/10초/15분, heartbeat는 2초다. 전체 설정과 유효 범위는 README와 `MockExecutionProperties`를 참고한다.

## 보조 엔진과 콘솔

Spring lifecycle에 엔진 기동·종료를 연결하고 부분 기동 실패 시 열린 자원을 정리한다. Netty 설정은 classpath/외부 YAML 및 scalar arguments로 전달하고 중첩 JAR의 action processor는 Spring scanner로 탐색한다.
Response의 모든 바인딩은 하나의 실행기와 max-clients 한도를 공유하므로 함께 시작·중지한다. 전환 작업을 직렬화하고 재시작 시 새 엔진을 생성한다. Spring HTTP/HTTPS와 콘솔은 계속 서비스한다.
콘솔은 REST/HTTP2/SSE/WS 호출, 엔진 상태·시작·중지, payload JSON 실행·취소를 제공한다. 포트 설정은 서버에서 조회하며 사용자 입력·서버 응답은 textContent로 표시한다.
일반 시간 SSE는 EventSource를, POST 실행 SSE는 fetch reader를 사용한다. 페이지 이탈·수동 취소·timeout 시 연결을 정리한다. 로그는 최근 40개 이벤트로 제한한다.
브라우저는 fetch의 HTTP 버전을 강제할 수 없어 HTTP/1.1 전용 API 실행은 HTTP 콘솔에서 제공한다. HTTPS 콘솔은 TLS HTTP/2와 WSS 등을 확인하는 데 사용한다.

## TLS와 빌드

`scripts/New-LocalCertificate.ps1`는 JDK keytool로 self-signed PKCS12와 공개 인증서를 생성한다. 기본 alias는 localhost, SAN은 localhost/127.0.0.1/::1이며 TLSv1.2/TLSv1.3을 사용한다.
로컬 mock 인증서와 개인키를 배포물에 포함한다. 별도 인증서는 SSL_KEY_STORE, SSL_KEY_STORE_PASSWORD, SSL_KEY_ALIAS로 지정한다. 대상 HTTPS 호출에는 JVM에서 신뢰하는 인증서가 필요하다.

package 단계에 maven-source-plugin의 jar-no-fork를 연결해 sources JAR을 첨부한다. 모듈 루트 `*.md`는 `META-INF/docs/spring-base-http2-mock/`에 포함한다.
이 모듈은 skipTests=true가 기본이다. 검증은 `mvn test -DskipTests=false` 또는 `mvn verify -DskipTests=false`, install은 테스트를 생략한다.
최초 빌드 스크립트의 -RunTests는 각 모듈을 verify한 뒤 테스트 없이 install하고 Node 콘솔 테스트를 실행한다. Windows에서 실행 중인 동일 JAR을 다시 패키징하려면 서버를 먼저 종료한다.

## 검증과 변경 이력

2026-11-11 푸시 전 재검증: Spring `mvn test -DskipTests=false` 53개, payload PARANOID `verify` 74개, Node 콘솔/decoder 7개 테스트 모두 통과했다. 실패·오류·건너뜀은 없었다. 독립 코드 리뷰에서 중요 정확성 문제는 발견되지 않았다.
Edge에서 HTTP/HTTPS 프로토콜, SSE/WS 10회 송신, 엔진 제어를 확인했다. 최신 실행 API는 32조합 × 2 threads × 2 loops의 128건 성공, endpointUrl/status만 전송, responseSummaries 제외, 빈 배열 400, 모바일 화면과 JavaScript 오류 없음을 검증했다.

| 날짜 | 완료 내용 |
| --- | --- |
| 2026-11-10 | Spring HTTP/HTTPS·TLS HTTP/2, 시간 SSE/WS, self-signed 인증서, 콘솔, Netty/response 동시 서비스 및 엔진 제어 |
| 2026-11-10 | payload 생성 기반 백그라운드 POST, SSE 집계, 제한·timeout·취소 |
| 2026-11-11 | 5개 payload 배열의 전체 조합 실행, SSE 개별 응답 간소화, summary 응답 목록 제거 |
| 2026-11-11 | 현재 계약 중심으로 기술 문서 통합 및 README 사용법 정리 |
