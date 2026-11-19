# HTTP Client Executor 구성

- 구현일: 2026-11-19
- Java 17 독립 Maven 라이브러리. 단일 POM·일반 JAR·sources JAR.
- 좌표: `com.inzent.apim.utilities:http-client-executor:1.0.0-SNAPSHOT`.
- 요구사항: [http-client-executor.md](http-client-executor.md), 사용법: [README.md](README.md).
- 초기 설계는 [design.md](design.md)에 보존한다. 현재 API와 지원 범위는 본 문서와 README가 기준이다.

## 단일 프로젝트 구조

하위 Maven 모듈 없이 `src/main/java/com/inzent/apim/utilities/http/client/`에 통합했다.

| 경로 | 구성과 책임 |
|---|---|
| 기본 패키지 | `HttpClientManager`, `HttpClientExecutor`: 설정 snapshot, builder, connector 선택과 실행/종료 |
| `config` | `ConnectionConfigure`, `ProxyConfigure`, `Ht5Options`, `NettyOptions`, `JavaOptions` |
| `model` | `ConnectMethod`, `ConnectVersion`, `ResponseStatus`, 다중 `HttpHeaders`, 요청·응답 payload와 body |
| `execution` | single-use `ExecutionContext`, 단계 이력, 실패·취소·저장 scope |
| `interceptor` | `ClientHandleInterceptor`의 본문 변경과 세 가지 알림 |
| `store` | bounded memory/file `BodyStore` |
| `spi` | 공통 HTTP/1.1 framing/parser, strict connection pool, 동시 송수신 helper |
| `connector.java` | JDK Socket/SSLSocket, TCP/TLS/mTLS/proxy 연결 |
| `connector.ht5` | Apache managed HTTP/1.1 writer 확장, async HTTP/2 endpoint |
| `connector.netty` | Netty NIO byte 전송과 TLS HTTP/2 frame/stream 전송 |
| `payload` | 기존 generator의 callback 안에서 stream/header를 복사하는 `PayloadBody` |

외부 provider discovery 대신 `ConnectionPool.open`의 enum registry로 세 구현을 명시적으로 선택한다. 선택하지 않은 커넥터의 네트워크 자원은 만들지 않는다. 공통 API는 Apache/Netty 요청·응답 타입을 노출하지 않는다. `.spi`는 내부 구현 경계이며 외부 확장 API로 보장하지 않는다.

## 의존성과 빌드

- Apache `httpclient5:5.6.5`, `httpcore5`/`httpcore5-h2:5.5`를 명시적으로 정렬한다. 송신 종료 후 응답 대기 중인 HTTP/2 stream reset 수정을 포함한 안정 버전이다.
- Netty BOM `4.2.21.Final`로 직접·전이 버전을 정렬한다.
- 기존 `http-payload-generator:1.0.0-SNAPSHOT`을 의존성으로 사용하며 소스를 복제하지 않는다.
- JUnit Jupiter 5.13.4와 테스트 전용 SLF4J NOP 1.7.36. 애플리케이션의 logging binding은 강제하지 않는다.
- package에 `maven-source-plugin:jar-no-fork`를 연결하고 `*.md`를 `META-INF/docs/http-client-executor/`에 포함한다.
- install은 `-Dmaven.test.skip=true`로 테스트 실행·컴파일을 생략하고 검증은 별도 verify로 수행한다.
- Java source/target은 release 17이다. 지정 `code_formatter.xml`을 IntelliJ headless formatter에 적용한다.

## 실행과 설정 계약

manager는 불변 기본 설정을 유지한다. builder의 connectionConfigure 전체 대체 후 필드 override를 적용해 executor마다 독립 snapshot·pool을 생성한다. typed connector option은 다른 connector에 주입할 수 없다.

context는 executor와 함께 builder로 생성하며 URI/method/version/body/header를 override한다. 전체 URL과 context path의 동시 지정은 거부한다. base path와 상대 path의 경계 slash만 합치고 기존 `%` escape는 보존한다. 유니코드 path/query는 UTF-8 percent encoding으로 변환한다. userinfo·fragment·잘못된 header/method token을 거부한다.

일반 execute 실패는 context에 기록해 같은 객체를 반환한다. HTTP 4xx/5xx는 정상 응답이다. 오류 응답과 부분 body를 별도로 확인할 수 있다. context는 single-use이며 다른 executor로 실행할 수 없다. 알림은 호출 스레드에서 직렬로 전달하고 RuntimeException은 interceptorFailures에 기록한다. 본문 hook 실패와 JVM Error는 별도 정책으로 처리한다.

## 본문·raw·원본 기록

raw bytes와 payload-generator 타입을 같은 RequestBody 경계로 받는다. generator stream은 callback 안에서 store로 복사하고 callback 밖으로 유출하지 않는다. 기본 memory threshold는 1 MiB, store당 최대 64 MiB다. 본문 배열 hook의 입력/반환 상한은 16 MiB다. context close가 store와 임시 파일을 정리한다.

AUTO는 HTTP/1.1 interceptor 실행을 RAW_HTTP1으로 선택한다. null 반환은 전달 배열을 유지하고 non-null은 반환 배열을 사용한다. 본문을 수정해도 Content-Length와 Content-Encoding을 다시 계산하지 않는다. STANDARD는 framing 정합성을 검사한다. raw는 전용 연결을 사용하며 정상 pool로 반환하지 않는다.

HT5의 초기 미해결 항목은 HTTP/1.1에서 해소했다. `DefaultManagedHttpClientConnection.createContentOutputStream`을 확장해 raw 본문 길이 제한을 우회하고, `SocketHolder` 출력 경계에서 실제 Apache writer bytes를 기록한다. Java/Netty로 전송한 뒤 HT5로 표시하는 대체 경로는 사용하지 않는다. HTTP/1.1 응답 파서는 공통 코드다.

HTTP1_PLAINTEXT_BYTES는 실제 write가 반환된 애플리케이션 bytes를 기록한다. TLS record/TCP packet/서버 도달 증거는 아니다. 실패 시 원격 수신량은 알 수 없고 capture limit 초과는 실행 실패다. 원본에는 비밀정보가 포함될 수 있으며 기본 로그로 출력하지 않는다.

## 풀·동시 송수신·자원 수명

- 물리 연결 total/perRoute 상한은 raw·정상 연결 모두에 적용한다. idle 선택 FIFO/LIFO와 idle/TTL 만료를 제공하며 검사는 acquire 시 수행한다.
- raw 요청이 idle 연결로 인해 perRoute 예산을 얻지 못하면 해당 idle 연결을 폐기하고 새 연결을 할당한다.
- HTTP/1.1은 executor 소유 reader worker에서 응답을 읽고 호출 스레드에서 송신한다. 서버가 업로드 중 완전한 최종 오류 응답을 보내면 송신을 중단하고 그 응답을 반환한다. 요청 송신 완료가 아니면 requestSent는 발생하지 않으며 연결을 폐기한다.
- cancel의 abort 호출과 detach를 동기화해 이전 요청의 취소가 pool에 반환된 다음 요청 연결을 닫지 않도록 한다.
- 플랫폼 스레드의 Socket read는 interrupt만으로 깨어나지 않으므로 deadline timer가 호출 스레드 interrupt를 관측해 socket을 닫는다. interrupt flag를 보존한다.
- callback에서 executor/manager close는 상태 변경 전에 거부한다. context close는 실행 반환 후 수행한다.
- manager는 소유 executor를, executor는 pool·response reader·Netty group·Apache client·deadline timer를 닫는다. 사용자 hook/generator의 무한 대기를 강제 중단하지는 못한다.

## HTTP/2와 명시적 제한

HT5/Netty는 HTTPS ALPN HTTP/2를 제공하고 실제 version을 검사한다. 응답 body는 bounded store로 수신하며 response idle과 전체 실행 timeout을 적용한다. Netty CONNECT는 목적지의 로컬 DNS 조회 없이 proxy에 원래 hostname을 전달한다. 두 커넥터 모두 multiplex와 HTTP/2 proxy/localAddress를 지원한다.

`ConnectionPool`은 물리 연결 entry와 stream lease를 별도로 관리한다. 기본 stream 상한은 `maxConcurrentStreamsPerConnection=100`이며 물리 연결 상한과 독립적이다. Netty는 peer SETTINGS와 GOAWAY를 관측하고, Apache는 native H2 scheduler의 peer 제한과 공통 acquire deadline을 함께 적용한다. stream 실패·취소는 정상 shared 연결을 폐기하지 않고, 연결 장애/TTL/GOAWAY는 draining 후 마지막 lease가 끝나면 폐기한다.

HT5는 full async `HttpRoute`에 proxy/localAddress를 주입한다. 최초 TLS 연결도 Apache sharing cache에 등록되도록 요청 bytes 전송 전에 연결된 endpoint를 재-lease한다. `Ht5ConnectionRegistry`/`Ht5StreamRuntime`은 Apache가 생성한 연결의 `RequestExecutionCommand`에 stream별 취소 dependency를 연결한다. `Ht5CommandSession`은 슬롯 대기 중 취소된 명령을 활성화 전에 제거한다. native protocol/context를 유지하며 reflection, Apache 패키지 주입이나 다른 커넥터로 대체하지 않는다. proxy Basic 인증은 CONNECT에만 적용한다.

초기 설계의 다음 항목은 실제 구현 범위로 구체화했다.

| 항목 | 현재 동작 |
|---|---|
| H2 multiplex | 동일 물리 연결에서 독립 stream/context 동시 실행. configured/peer 상한·대기 timeout 적용 |
| HT5 H2 proxy/localAddress | direct/proxy 바인딩과 CONNECT Basic 인증, TLS/mTLS·ALPN 지원 |
| Netty H2 proxy | CONNECT·Basic·localAddress·mTLS 조합의 통합 검증 완료 |
| Java H2 | 지원 오류, H1.1만 제공 |
| H2 원본 bytes/손상 frame | 미제공. 논리 headers/body 기록만 제공 |
| 개별 DNS/TCP/TLS 시간 | 별도 세부 시간 측정 미제공. 실행 stage와 monotonic 경과 기록 |
| TLS 설정 파일/alias builder | JDK SSLContext 주입 방식으로 제공. 파일/alias 관리는 호출자가 SSLContext에 구성 |
| pool fairness | FIFO/LIFO idle 선택만 제공. waiting thread 공정성은 보장하지 않음 |
| 자동 retry/redirect/cookie/decompress | 수행하지 않음 |

## 검증 기록

- 초기 공통 계약 테스트: 구현 전 없는 API로 실패 확인 후 구현.
- generator adapter: 미구현 PayloadBody 실패 확인 후 구현.
- HTTP/2: 두 connector의 미구현 전송 실패를 확인 후 TLS/mTLS 왕복과 연결 재사용 구현.
- pool idle → raw 교체와 callback close 재진입 실패를 재현하고 수정.
- 독립 코드 리뷰의 8개 경계 항목을 ReviewRegressionTest/Http2IdleTest로 재현하고 수정했다. 취소/lease handoff, manager close 재진입, context 소유권, Unicode URI, H2 idle timeout, interrupt flag, proxy DNS와 조기 최종 응답을 검증한다.

최초 구현 단계에서는 JDK 17 PARANOID clean verify 52개 테스트와 일반/소스 JAR 및 Markdown 패키징을 확인했다. 현재 HTTP/2 확장의 최종 결과는 아래 절을 기준으로 한다.

## HTTP/2 확장 최종 검증

- JDK 17.0.10에서 `mvn clean verify -Dio.netty.leakDetection.level=paranoid` 완료: 78개 테스트, 실패/오류/생략 0건. Netty LEAK 보고 0건. 연결 종료 테스트에서는 서버 fixture에 Connection reset 경고가 출력될 수 있다.
- 단일 물리 연결 동시 요청, stream별 응답 분리·취소·저장 한도 실패 격리, 설정/서버 stream 상한과 변경, acquire timeout 후 지연 전송 방지, GOAWAY 중 sibling 유지 및 연결 교체를 검증했다.
- HT5/Netty CONNECT Basic 인증·localAddress·mTLS 조합, HT5 direct 바인딩과 잘못된 인증/주소 실패, HTTP/1.1 exclusive pool 회귀를 검증했다.
- 지정 formatter를 적용했다. 확장 작업의 독립 리뷰는 크레딧 제한으로 완료되지 않아 자체 검토와 회귀 테스트로 검증했다.
- 일반 JAR의 class 67개는 Java 17 major 61이며, 소스 JAR의 Java 파일 34개와 루트 Markdown 6개 포함 및 문서 내용 일치를 확인했다. `-Dmaven.test.skip=true install`에서 테스트 생략과 로컬 저장소 설치를 확인했다.
- 설치한 라이브러리만 의존하는 별도 consumer의 dependency:tree에서 HttpCore/core-h2 5.5와 Netty 4.2.21.Final이 선택됨을 확인했다.
## HTTP/2 코드 리뷰 수정 (2026-11-19)

- HT5의 17번째 중간 응답에서 일반 IOException이 공유 연결 오류로 처리되어 sibling stream도 실패하는 문제를 재현했다. HttpStreamResetException으로 바꾸어 해당 stream만 reset한다. HT5/Netty 회귀 테스트는 sibling 성공과 다음 요청의 동일 연결 재사용까지 검증한다.
- HT5가 endpoint를 획득한 직후 GOAWAY를 받고 요청 제출이 동기 예외로 실패하면, catch가 안전한 endpoint 반환 callback을 우회하여 draining sibling까지 종료하는 문제를 재현했다. catch도 streamCallback.failed를 통하게 수정했다. 실제 GOAWAY를 registry 조회 경계에 주입하는 테스트에서 수정 전 sibling ConnectionClosedException, 수정 후 정상 완료를 확인했다.
- 독립 리뷰에서 Apache의 proxy DNS 해석·HttpRoute localAddress 전달·공유 native lease 제한·취소 명령 필터·오류 전파 경로를 확인했다. 수정 후 두 변경 경로와 회귀 테스트를 재검토했으며 추가 조치 항목은 없었다.
- JDK 17 대상 검증: Ht5GoAwayRaceTest와 Http2MultiplexTest 9개 통과. 최종 전체 검증 결과는 다음 기록을 따른다.
- 최종 JDK 17.0.10 PARANOID clean verify: 81개 테스트, 실패/오류/생략 0건, Netty LEAK 보고 0건. 일반·소스 JAR 빌드 성공.

## CPU·리소스·NPE 개선 (2026-11-19)

- 분석 상세: [safety-analysis.md](safety-analysis.md).
- 활성 실행의 interrupt 감시를 executor당 하나로 통합하고, 취소한 타이머 작업을 즉시 큐에서 제거한다. pool close 예외에도 timer를 종료한다.
- 개별 종료된 executor를 manager에서 해제하고, BodyStore 출력 종료 오류에도 임시 파일 삭제를 시도한다.
- custom body의 null 헤더를 설명 가능한 준비 실패로 처리한다. Netty 입력은 context를 직접 바인딩하고 timeout 이후 non-null 버퍼와 EOF 상태를 보존하며 단일 바이트 버퍼를 재사용한다.
- 신규 회귀 테스트 7개는 수정 전 실패·수정 후 통과를 확인했다. 운영 CPU 비율과 장시간 누수에 대한 부하 측정은 수행하지 않았다.
- 지정 formatter를 변경 Java 7개 파일에 적용했다. 독립 코드 검토에서 추가 조치 항목은 없었다.
- 최종 JDK 17.0.10 `clean verify -Dio.netty.leakDetection.level=paranoid`: 88개 테스트, 실패/오류/생략 0건. Netty LEAK 보고 0건. 연결 종료 테스트에서 기존 서버 fixture의 `Connection reset` 경고가 출력됐다.
- 일반 JAR·sources JAR을 생성했다. 최종 검증 기록을 반영한 문서만 갱신한 뒤 테스트를 생략하여 다시 package하고 루트 Markdown 7개의 포함·내용 일치를 확인했다.
