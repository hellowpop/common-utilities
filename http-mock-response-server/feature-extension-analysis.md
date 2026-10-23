# HTTP Mock Response Server 기능 추가를 위한 프로젝트 분석

> 이 문서는 개선 전 사전 분석이다. 이후 MockClientContext에 enum/AtomicReference point 관리와 HTTP 요청 incremental parser가 추가되었다. 현재 API·구성·수신 상태는 [project.md](project.md)의 개선 구성 절 및 [README.md](README.md)를 따른다. 아래 parser 미제공·확장 필요 설명은 분석 시점의 이력이다.

- 분석일: 2026-10-23
- 대상: 현재 `develop`의 소스, Maven 설정, 테스트 및 기존 설계/안전성 문서
- 범위: 기능 확장 지점과 제약 분석. 운영 소스 및 공개 API 변경 없음.

## 1. 현재 구현 상태

이미 구현된 Java 17 독립 Maven JAR이다. 패키지는 `com.inzent.apim.utilities.http.mockserver`, 좌표는 `com.inzent.apim.utilities:http-mock-response-server:1.0.0-SNAPSHOT`이다. 운영 소스 25개와 테스트 소스 3개로 구성되며 외부 런타임 의존성은 없다. JUnit Jupiter만 테스트 범위로 의존한다. 저장소 루트에는 통합 빌드용 pom.xml이 없으므로 모듈 pom.xml을 직접 지정한다.

`curl-http-mock-executor`는 HTTP 요청 실행, `http-payload-generator`는 요청 payload 생성 역할이다. 서버 모듈과 직접적인 Maven 의존 관계는 없다. 기존 curl 실행기는 payload 생성기와 Netty에 의존하지만 서버에 같은 의존성을 추가해야 하는 구조는 아니다.

## 2. 실행 흐름과 책임

| 단계 | 주요 클래스 | 현재 동작 |
| --- | --- | --- |
| 등록 | `MockServerExecutor.addMock` | enum + MockScenario 구현체, 주소/포트, MockOptions, listener를 등록. NEW 상태에서만 허용 |
| 시작 | `MockServerExecutor.start`, `MockBinding` | 모든 주소 bind 후 accept 시작. 일부 bind 실패 시 전체 rollback |
| 수용 | `MockServerExecutor.acceptLoop` | 바인딩별 accept thread, 실행기 전체 Semaphore로 연결 수 제한. 작업 대기열 없음 |
| 연결 실행 | `ClientSession`, `MockClientContext` | 연결별 worker/context 생성, 소켓 옵션 적용, scenario.execute 호출 |
| 세부 절차 | `MockCase`, `MockPoints`, `runPoint` | 읽기·지연·point 이벤트. point 정상 완료에만 POINT_EXIT 발생 |
| 후속 동작 | `ActionDispatcher` | CloseClientException이면 지연 후 종료, SendResponseException이면 응답 후 종료 |
| 응답 | `ClientSession.send`, `HttpResponseWriter`, `MockResponse` | HTTP/1.1 고정 헤더와 메모리 본문 전송. scheduler가 쓰기 제한 시간 관리 |
| 정리 | `ClientSession.finish`, `sessionFinished`, `shutdownResources` | 소켓 종료, 세션 제거와 permit 반환, 실행기 종료 및 최종 이벤트 |
| 관찰 | `ServiceStatusListenerChain`, `ServiceEvent`, `ThrowableSnapshot` | 동기 listener 체인, 개별 Exception 격리, 본문 참조 없는 예외 진단 |

기본 case는 `WAIT_FOR_CLIENT_DISCONNECT`와 `SLOW_READ` 두 가지다. 전자는 입력 EOF까지 데이터를 읽어 버리며, 후자는 양수 read 이후 다음 읽기 전 지연한다. 두 case 모두 기본 응답은 없다. 응답은 사용자 enum에서 제어 예외를 던져 주입할 수 있다.

## 3. 기존 공개 API로 가능한 확장

`MockScenario`를 구현한 별도 enum을 작성하면 기존 서버 구현 수정 없이 새 절차를 등록할 수 있다. `MockClientContext`는 `runPoint`, `readChunk`, `chunk`, `delay`, `checkCancelled`, `options`, `lastRead`, `totalBytes`, `clientId`를 제공한다.

- 연결 직후 또는 지정 지연 후 고정 HTTP 응답 전송.
- 일정 바이트 수를 읽은 후 응답하거나 연결 종료.
- 읽은 chunk를 사용자 절차에서 검사하여 응답 분기.
- 읽기/지연/종료를 조합한 장애 재현 절차.
- listener 체인으로 이벤트 기록 및 통계 수집.

단, 요청 한 건의 완료를 판단하는 HTTP parser는 없다. `REQUEST_RECEIVED`는 양수 read 발생을 뜻한다. 완전한 요청이나 TCP 패킷 경계를 뜻하지 않는다. 사용자 절차에서 요청을 누적하거나 검사한다면 분할·합쳐진 read와 크기 제한을 직접 다뤄야 한다.

## 4. 기능별 변경 지점

아래는 추가 기능이 정해졌을 때 참고할 영향 범위이며, 이번 분석에서 구현 대상으로 확정한 목록은 아니다.

| 기능 유형 | 주요 변경 지점 | 확인할 계약 |
| --- | --- | --- |
| 기본 시나리오 추가 | `MockCase`, 필요 시 `MockPoints` | point 순서, 취소 처리, 정상 반환 시 종료 의미 |
| 등록마다 다른 시나리오 매개변수 | `MockOptions` 또는 등록 API/설정 모델 | 현재 addMock은 enum만 수용. 설정 객체·일반 MockScenario 인스턴스는 직접 등록 불가 |
| 임의 응답 헤더 | `MockResponse`, `HttpResponseWriter` | CR/LF 입력, 중복 헤더, 자동 생성 Content-Length/Connection과의 충돌 |
| 파일·스트림 응답 | `MockResponse`, `HttpResponseWriter`, `ClientSession.send`, 응답 메타데이터 | 본문 소유권과 close, 길이 표현, 부분 쓰기·실패·취소 시 회수 |
| 응답 분할 전송·전송 간 지연 | writer와 send의 쓰기 상태/제한 시간 처리 | 전체/구간 제한 시간 정의, stop 중단, RESPONSE_SENT 발생 시점 |
| 요청 파싱·URL/메서드 라우팅 | context 읽기 계층, 별도 parser/요청 모델, 시나리오 | framing, 헤더/본문 크기 제한, 조기 응답, 미완료 요청 처리 |
| 새로운 제어 예외 | `NextActionException` 하위 타입, `ActionDispatcher`, 필요 시 `ThrowableSnapshot` | 미지원 타입은 현재 오류 종료. 제어 예외에 보관한 본문/자원은 이벤트에 복사하지 않음 |
| 실행 중 등록·재시작 | `MockServerExecutor`, `MockBinding`, 시작/종료 동기화 | 현재 NEW 전용 등록 및 일회성 실행 계약 전체에 영향 |
| TLS·keep-alive·다중 응답 | 소켓 생성, 세션 실행, writer, 상태 및 이벤트 모델 | 현재 평문·연결당 단일 응답 후 close 계약 전체에 영향 |

## 5. 확장 시 유지할 제약

1. **연결별 상태:** enum과 listener는 여러 연결에서 공유된다. 연결별 가변 상태는 execute의 지역 변수나 연결 전용 객체에 둔다. `chunk()`는 다음 read에서 덮어쓰는 읽기 전용 view이므로 보관하려면 복사한다. context는 생성한 worker에서만 접근한다.
2. **응답 메모리:** MockResponse는 입력 byte[]와 body() 반환 시 방어 복사한다. 현재 길이는 int이며 파일/스트림 전송 API는 없다. 대용량 본문 기능은 단순 생성자 추가보다 수명·길이·진단 모델의 영향을 먼저 확인해야 한다.
3. **취소와 시간 제한:** 지연은 context.delay를 사용하면 stop의 interrupt에 반응한다. 응답 제한 시간은 실제 writer 호출 전 예약되며 RESPONSE_SEND listener 실행 시간은 포함하지 않는다. 응답 전 사용자 지연도 포함하지 않는다.
4. **이벤트:** listener는 호출 스레드에서 동기 실행된다. 느린 listener는 연결 처리 또는 시작/종료를 지연시킨다. listener의 Exception은 격리하지만 Error나 무한 대기를 정상 복구하는 계약은 없다. worker/point/listener 안에서 실행기의 addMock/start/stop/close를 호출할 수 없다.
5. **종료 원인:** 사용자 scenario가 제어 예외 없이 정상 반환하면 현재 ClientSession은 PEER_EOF로 기록한다. 새 절차가 EOF 없이 정상 반환하는 경우 이 의미를 그대로 수용할지 종료 모델을 확장할지 검토해야 한다.
6. **자원 회수:** worker 제출 실패의 미실행 session/permit 회수, close 제출 실패의 직접 close, 종료 오류 격리·재시도를 유지한다. SENT/TIMEOUT의 최종 쓰기 결과가 후속 stop으로 덮어써지지 않아야 한다.
7. **진단 참조:** ServiceEvent.exception은 원본 예외가 아닌 ThrowableSnapshot이다. 타입은 originalType으로 조회한다. 새 이벤트·제어 예외도 socket/context/본문을 listener에 강하게 보관시키지 않아야 한다.
8. **소스 호환성:** MockOptions와 ServiceEvent는 공개 record다. 구성요소 추가는 정식 생성자 시그니처에 영향을 준다. builder뿐 아니라 직접 생성하는 호출자도 고려한다. enum 상수나 이벤트 종류 추가 시 외부 switch의 영향도 확인한다.

기본 전체 연결 한도는 64개, 종료 대기 5초이며 응답 제한 시간은 등록별 10초다. 연결마다 플랫폼 worker를 사용한다. 이 설정은 소규모 장애 재현 기준이며 대규모 연결 부하 성능은 이번 분석에서 측정하지 않았다.

## 6. 현재 검증과 추가 기능의 검증 위치

2026-10-23 Windows에서 실제 JDK 17.0.10과 Maven 3.9.11로 아래 명령을 실행했다.

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-17.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -f http-mock-response-server/pom.xml clean verify
```

결과는 **BUILD SUCCESS, 테스트 32개, 실패/오류/건너뜀 0**이다.

| 테스트 | 수 | 현재 담당 범위 |
| --- | --- | --- |
| `ContractsTest` | 7 | 옵션 검증, 응답 방어 복사·문자 검증, listener 체인 |
| `ResourceSafetyTest` | 5 | worker/closer 생성 실패, 종료 재시도, 예외 snapshot과 참조 격리 |
| `ServerIntegrationTest` | 20 | 실제 loopback 소켓, 응답/읽기/지연, 연결 상한, deadline, 시작/종료 경합 |

추가 기능의 값 검증은 ContractsTest, 실제 wire 동작과 중단은 ServerIntegrationTest, 자원 소유·실패 시 회수는 ResourceSafetyTest가 기존 확장 위치다. 테스트 중 listener Exception WARNING은 예외 격리 검증을 위한 의도된 출력이다.

`analysis/SafetyProbe.java`는 Maven 기본 테스트 경로 밖의 독립 진단 코드다. 기존 문서의 14개 probe 통과 기록은 2026-10-22 이력이며 이번 작업에서는 재실행하지 않았다. 다른 모듈, 다른 OS, 장기 부하, 실제 OS 자원 고갈도 이번 검증 범위에 포함하지 않았다.

## 7. 기능 추가 작업의 기준

절차 조합으로 표현되는 기능은 기존 enum/context 확장점이 우선 적용 위치다. 응답 형식·본문 저장 방식은 response/writer/send 경로, 요청 의미 해석은 읽기·parser 경로, 수명 변경은 executor/session 경로에서 영향을 평가한다. 구체 추가 기능은 아직 지정되지 않았으므로 구현 범위와 새 공개 API는 확정하지 않았다.

실제 구현 시 구성과 계약은 [project.md](project.md), 사용법은 [README.md](README.md)에 반영한다. 기존 안전성 수정 이력은 [resource-safety-review.md](resource-safety-review.md)의 적용 절을 기준으로 확인한다.
