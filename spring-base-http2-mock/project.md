# spring-base-http2-mock 프로젝트 구성

최종 정리: 2026-11-16. 이 문서는 현재 구성과 실행 계약을 설명한다. 실행 예제와 옵션 사용법은 [README.md](README.md)를 참고한다.

## 목적과 모듈 구성

Java 17 이상, Spring Boot 3.5.16, Spring MVC와 내장 Tomcat 기반의 독립 Maven 프로젝트다. 클래스는 `com.inzent.apim.utilities.http2mock` 아래에 둔다.
HTTP/HTTPS·HTTP/2·SSE·WebSocket과 별도 mock 엔진을 같은 JVM에서 제공하고, payload 생성기를 사용한 동시·반복 요청 결과를 SSE로 수집한다.

| 의존 모듈 | 역할 |
| --- | --- |
| `http-payload-generator` | 원문 생성, ContentType/Encoding, chunked/fixed-length 헤더 및 압축 |
| `netty-http-mock-server` | `library` classifier로 기존 Netty action 서버를 임베딩 |
| response 엔진 (내장 소스) | `com.inzent.apim.utilities.http.mockserver`에서 named scenario와 다중 바인딩 응답 제공 |

`scripts/Build-WithMockServers.ps1`는 payload → Netty → Spring 순으로 모듈을 설치한다. Response 서버와 Request Caller는 외부 모듈 없이 이 프로젝트 소스로 빌드한다.

`MockApplication`은 `CallerConfiguration`을 import한다. 이 설정은 `com.inzent.apim.utilities.http.caller`의 bean·properties·JDBC store와 `/caller/**` 정적 자원 handler를 등록한다. `src/main/resources/caller-ui/`에 UI를 두고 기존 root static 콘솔과 분리한다. 이전 caller main 클래스는 `MockApplication.main`으로 위임하는 호환 진입점으로만 남으며 별도 Boot application이나 ObjectMapper를 등록하지 않는다.

단일 Boot 3 POM에서 JDBC/H2/PostgreSQL/Liquibase/Netty/JEXL/YAML 의존성을 관리한다. `CallerStore.initialize()`가 migration 후 복구를 시작하므로 Liquibase 자동 실행은 끈다. 저장 기본 경로는 `./data/caller`다. 기존 caller 데이터도 이 경로로 복사했다. 테스트는 임시 데이터 디렉터리와 context별 H2 메모리 DB로 격리하며 이전 caller의 Java/Node 테스트를 모두 이 프로젝트에서 실행한다.

## Response 서버 소스 통합 구성

`http-mock-response-server`의 31개 구현 클래스와 7개 테스트 클래스를 동일한 `com.inzent.apim.utilities.http.mockserver` 패키지로 통합했다. 기존 `MockServicesLifecycle`과 `MockServicesProperties`가 엔진의 바인딩·설정·시작·종료·재시작을 계속 담당한다. Response 엔진은 JDK 소켓 기반이며 별도 runtime 의존성을 추가하지 않는다. `pom.xml`의 이전 artifact 의존성과 통합 빌드 스크립트의 response 선행 설치 단계는 제거했다.

사용법은 `response-README.md`, 기존 설계·분석 기록은 `response-*.md`, 진단 도구는 `analysis/response-server/SafetyProbe.java`에 둔다. 원본 150개 파일은 `.migration-archive/response-original/`에 보존하고 SHA-256을 검증했다. 이관 중 `var`와 static import는 명시적 타입·클래스 참조로 바꾸되 공개 API와 실행 계약은 유지한다. 전체 이관·검증 기록은 [response-migration.md](response-migration.md)를 따른다.

## Caller 이관 파일 구성

### Caller 실행 상세 조회 구성 (2026-11-15)

- `caller-ui/index.html`, `app.js`, `app.css`: 상태코드·오류, 지연·처리량, Path별 집계를 테두리 있는 grid table로 표시한다. Path 이름과 URL은 실행 snapshot에 저장된 설정으로 해석한다.
- 지연·처리량 표의 숫자와 단위를 각각 별도 셀로 렌더링한다. `latency-grid`에서 숫자 열은 우측 정렬 및 tabular 숫자, 단위 열은 좌측 정렬을 적용한다.
- 실행 이력의 행별 삭제 버튼은 클릭 전파를 차단하고 삭제 확인 후 `DELETE /api/runs/{id}`를 호출한다. 종료 상태만 활성화하며, 선택된 이력을 삭제하면 상세를 초기화하고 마지막 행을 삭제한 페이지는 이전 페이지로 이동한다.
- `RunService.delete`는 active 실행 및 종료 저장 중인 실행을 거부한다. `StorageMaintenance.deleteRun`은 정리 표식을 먼저 저장한 뒤 실행별 journal·본문·로그·보고서와 DB의 요청·artifact·이력을 정리한다. 보관기간 정리와 수동 삭제는 같은 잠금과 경로 검증을 사용하며 실패 시 표식을 남겨 재시도한다. 시작 시 journal 복구는 정리 표식이 있는 실행을 건너뛰어 삭제된 이력의 재생성을 방지한다.
- 단위 요청은 접이식 조회 영역으로 분리한다. 실행 선택이나 SSE 종료만으로 요청을 조회하지 않으며, 사용자가 조회한 이후의 갱신·페이지 이동에는 적용된 조건을 유지한다. 요청 조회가 진행되는 동안 실행·조건·페이지가 바뀌면 이전 응답을 무시한다.
- 검색 입력별 `×` 버튼은 해당 입력값을 비우고 포커스를 돌려준다. 자동 재조회 없이 다음 조회에 반영한다. 시나리오 편집의 실행 기준·반복 횟수·실행 시간은 `execution-fields`의 한 줄 grid로 묶고, 좁은 화면은 가로 스크롤로 제공한다.
- `CallerController.requests`: 선택 조건 `status`, `workerIndex`, `minElapsedMillis`, `maxElapsedMillis`를 전달한다. `CallerStore.results`는 범위를 검증하고 기존 `pathId`·`success`와 AND로 결합한 parameterized SQL에 limit/offset을 적용한다. 소요시간 입력은 ms이고 저장·비교는 nanos 기준이다.
- Liquibase `caller-3-result-search`: `status_code`, `worker_index`, `elapsed_nanos`, `search_ready`와 검색 인덱스를 추가한다. 기존 changeSet은 유지한다. 시작 시 미보완 행을 500개씩 읽어 JSON의 검색값을 보완하며, 신규 저장은 검색값을 함께 기록한다. 이전 데이터의 누락값은 null로 유지한다.
- 회귀 검증: `RequestSearchTest`는 조건 조합·시간 경계·검색 후 페이지 분할·기존 JSON 보완·잘못된 조건 거부를 검증한다. `execution-ui.test.cjs`는 집계 표·지연 조회·적용 조건 유지·빈 결과를 검증한다.

| 경로 | 역할 |
| --- | --- |
| `src/main/java/com/inzent/apim/utilities/http/caller/` | 시나리오, payload, Netty transport, 실행·취소, JEXL, 저장·복구, 로그, REST/SSE·보고서 |
| `src/main/resources/caller-ui/` | 시나리오 UI와 JavaScript/CSS |
| `src/main/resources/db/changelog/master.xml` | caller 스키마·인덱스·migration |
| `src/test/java/com/inzent/apim/utilities/http/caller/`, `src/test/js/` | 이전 caller의 Java/Node 전체 회귀 테스트 |
| `caller-README.md` | 현재 통합 앱의 caller 사용법 |
| `caller-project.md`, `caller-command.md` | 이전 개발내역·상세 설계·원래 요구사항 보존 |
| `caller-implementation-contract.md`, `caller-implementation-plan.md` | 이전 구현 계약·단계별 기록 보존 |
| `caller-migration.md` | 현재 배치와 원본 보존·검증·삭제 기록 |

과거 설계의 독립 Boot 4/POM/8085/library 관련 내용은 당시 이력이다. 현재 구성은 이 문서와 README를 우선한다. 모든 루트 `caller-*.md`도 실행 JAR의 `META-INF/docs/spring-base-http2-mock/`에 포함한다.

## 리스너와 프로토콜

| 리스너 | 기본 주소 | 구성 |
| --- | --- | --- |
| Spring HTTP | `localhost:8080` | `mock.http.port` / `HTTP_PORT`, HTTP/1.1 커넥터 |
| Spring HTTPS | `localhost:8443` | `server.port` / `PORT`, TLS ALPN h2 및 HTTP/1.1 |
| Netty mock | `127.0.0.1:28080` | `mock.services.netty` |
| Response REPLY / WAIT / SLOW | `127.0.0.1:28081` / `28082` / `28083` | `mock.services.response.bindings` |

`ProtocolFilter`가 정규화된 servlet path로 경로별 TLS/버전을 검사한다. HTTP의 `/http2/*`는 403, HTTPS에서 HTTP/1.1의 `/http2/*`와 HTTP/2의 mock `/api/*`는 505를 반환한다. caller 소유 API는 `CallerRoutes.isApi`로 구분해 HTTP/1.1과 HTTPS HTTP/2를 허용한다.
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
| `/caller/` | GET | 시나리오 편집·실행·이력 caller 콘솔 |
| `/api/csrf`, `/api/example`, `/api/status` | GET | caller 세션 토큰·예제·상태 |
| `/api/scenarios/**`, `/api/runs/**` | REST/SSE | caller 정의·실행·취소·이력·본문·로그·보고서 |

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

`build.finalName=spring-mock`으로 실행 산출물 이름을 `target/spring-mock.jar`로 고정한다. package 단계에 maven-source-plugin의 jar-no-fork를 연결해 `spring-mock-sources.jar`를 첨부한다. 모듈 루트 `*.md`는 `META-INF/docs/spring-base-http2-mock/`에 포함한다.

루트 `build.xml`과 `mc.cmd`는 Maven 패키징과 `runtime/sping-mock/` 복사·실행을 연결한다. `settings.xml`에는 Maven 미러 설정을 두며 `runtime/`은 실행 산출물·데이터 경로로 Git에서 제외한다.
이 모듈은 skipTests=true가 기본이다. 검증은 `mvn test -DskipTests=false` 또는 `mvn verify -DskipTests=false`, install은 테스트를 생략한다.
최초 빌드 스크립트의 -RunTests는 각 모듈을 verify한 뒤 테스트 없이 install하고 Node 콘솔 테스트를 실행한다. Windows에서 실행 중인 동일 JAR을 다시 패키징하려면 서버를 먼저 종료한다.

## 검증과 변경 이력

2026-11-15 푸시 전 재검증: 단일 프로젝트 JDK 17 `verify` 111개 및 Node 26개가 실패·오류·건너뜀 없이 통과했다. 이전 프로젝트 삭제·파일 이관·문서 갱신만 `develop`에 반영하며 데이터·보존본·target 산출물은 제외한다.

2026-11-15 전체 이관 완료: caller Java 30개·Java 테스트 14개·UI 6개·Node 테스트 6개·DB schema·5개 원문 문서를 이 프로젝트로 이관했다. caller POM/library 의존성과 별도 빌드를 제거했다. 단일 프로젝트 JDK 17 verify 111개, Node 26개 통과. 기존 H2 데이터 복사본으로 실행 JAR을 검증했으며 1,052개 전체 원본을 `.migration-archive/caller-original/`에 해시 검증 후 보존하고 이전 프로젝트 디렉터리를 삭제했다. 현재 배치·데이터·문서 대응은 [전체 이관 기록](caller-migration.md)에 정리한다.

2026-11-15 caller 통합 검증: JDK 17에서 caller 전체 55개와 Spring 전체 56개가 실패·오류·건너뜀 없이 통과했다. 최종 라이브러리 재설치 후 화면·CSRF·저장·mock 호출·보고서 및 HTTPS HTTP/2의 caller API 통합 테스트 3개를 재실행해 통과했다. 기존 SSE/WebSocket ISO 시각과 mock 프로토콜 제한을 유지한다. 두 모듈의 Node 회귀 테스트는 26개 통과했다. 실제 executable JAR을 별도 프로세스로 실행해 root 콘솔, caller 화면·자원·CSP, echo, 세션/CSRF와 YAML export/import를 확인했다. library의 독립 서버 설정/root static 제외 및 Markdown 포함을 확인했다.

2026-11-11 푸시 전 재검증: Spring `mvn test -DskipTests=false` 53개, payload PARANOID `verify` 74개, Node 콘솔/decoder 7개 테스트 모두 통과했다. 실패·오류·건너뜀은 없었다. 독립 코드 리뷰에서 중요 정확성 문제는 발견되지 않았다.
Edge에서 HTTP/HTTPS 프로토콜, SSE/WS 10회 송신, 엔진 제어를 확인했다. 최신 실행 API는 32조합 × 2 threads × 2 loops의 128건 성공, endpointUrl/status만 전송, responseSummaries 제외, 빈 배열 400, 모바일 화면과 JavaScript 오류 없음을 검증했다.

| 날짜 | 완료 내용 |
| --- | --- |
| 2026-11-15 | caller 개발내역·설계·소스·테스트·자원·데이터 전체 이관, 별도 모듈 의존성 제거 및 원래 프로젝트 삭제 |
| 2026-11-15 | request caller를 library로 통합, `/caller/` UI·기존 API·JDBC 저장·보고서, 보안/JSON 설정 격리 및 HTTP/2 caller API 지원 |
| 2026-11-10 | Spring HTTP/HTTPS·TLS HTTP/2, 시간 SSE/WS, self-signed 인증서, 콘솔, Netty/response 동시 서비스 및 엔진 제어 |
| 2026-11-10 | payload 생성 기반 백그라운드 POST, SSE 집계, 제한·timeout·취소 |
| 2026-11-11 | 5개 payload 배열의 전체 조합 실행, SSE 개별 응답 간소화, summary 응답 목록 제거 |
| 2026-11-11 | 현재 계약 중심으로 기술 문서 통합 및 README 사용법 정리 |
