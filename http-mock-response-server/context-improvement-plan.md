# MockClientContext 개선 설계 및 구현 계획

- 날짜: 2026-10-23
- Java 17, 외부 런타임 의존성 추가 없음. 기존 develop 작업과 분석 문서 보존.
- 사용자 지시대로 확인 질문 없이 구현한다. writing-plans/TDD 흐름으로 현재 세션에서 직접 수행하며 커밋/push는 하지 않는다.

## 계약

point 식별자는 `Enum<?>`로 통일하고 기본 enum `MockPoint`를 제공한다. context의 현재 point는 `AtomicReference<Enum<?>>`의 getAndSet으로 진입/복원을 관리한다. ServiceEvent와 실패 point도 enum을 보관한다. String overload는 제거한다. context I/O는 기존 worker 소유 규칙을 유지한다.

`readHttpProtocol()`은 한 번의 소켓 read에 포함된 모든 bytes를 incremental decoder에 전달하고 불변 `HttpProtocolResult`를 반환한다. 결과는 마지막 요청 snapshot, 이번 read에서 완료한 요청 목록, EOF를 포함한다. `httpProtocol()`은 추가 read 없이 마지막 결과를 조회한다. raw read와 protocol read는 연결 내 혼용을 거부하여 해석되지 않은 선행 바이트가 생기지 않게 한다.

`HttpRequestSnapshot`은 initial line, headers, decoded content, trailers와 각 단계의 NOT_REACHED/RECEIVING/COMPLETE/NOT_APPLICABLE 상태를 제공한다. Content-Length 0 또는 framing 없는 요청은 content/trailers 해당없음, 고정 길이 양수 요청은 trailers 해당없음, chunked는 0 chunk 뒤 content 완료 및 최종 빈 줄 뒤 trailers 완료다. EOF 시 부분 상태와 truncated 여부를 반환하며 정상 완료로 꾸미지 않는다.

HTTP/1.0 및 HTTP/1.1 request line, CRLF, 중복 필드, 동일한 Content-Length 목록, chunked 및 chunk extensions를 처리한다. TE+CL, 서로 다른 CL, 잘못된 chunk, unsupported transfer coding은 IOException이다. gzip 등 content decoding은 수행하지 않는다. 한 요청의 line/headers/trailers/content 및 chunk framing에 상한을 둔다. 크기 한도는 `HttpProtocolLimits`로 설정하며 연결 중 변경하지 않는다. socket/context/원본 예외/본문은 이벤트에 넣지 않는다.

## 작업 순서

- [x] enum point·중첩 복원·실패 진단 테스트를 먼저 추가하고 실패 확인. MockClientContext/MockCase/ClientSession/ServiceEvent와 기존 테스트·probe를 enum으로 변경.
- [x] 분할/합쳐진 HTTP 입력, 상태 전이, 0 body, chunked/trailers, EOF, invalid framing, 크기 상한 테스트를 먼저 추가하고 실패 확인. HttpRequestDecoder와 불변 결과 모델 구현.
- [x] 실제 소켓으로 protocol read, raw 혼용 거부, stop 중단을 검증. 기본 raw case 동작 유지.
- [x] Java 17 clean verify, SafetyProbe 및 README 예제 컴파일/실행. project.md·README.md·분석서 갱신 및 diff 검토.

## 판단 및 검증 기록

- 최초 테스트는 새 enum/HTTP API가 없는 기존 코드에서 컴파일 실패를 확인했다. 구현 후 새 기능 테스트와 기존 회귀 테스트를 함께 실행했다.
- Ruling: 기존 raw case는 HTTP parser를 자동 적용하지 않는다. 장애 재현용 임의 bytes를 유지하고, 명시적 protocol 읽기에서 전체 입력을 해석한다. 따라서 raw/HTTP 혼용은 거부한다.
- Ruling: EOF는 부분 요청의 수신 단계를 보존하고 truncated로 알린다. 자동 HTTP 오류 응답이나 정상 완료를 만들어내지 않고 caller가 정책을 정한다.
- Ruling: enum 타입은 Enum<?>로 받아 외부 enum 확장을 유지한다. AtomicReference를 도입해도 context I/O의 worker 소유 제약은 변경하지 않는다.
- 별도 리뷰에서 누적 본문을 매 read마다 전체 복사하는 문제를 지적했다. 512 KiB/1 KiB 단위 읽기 테스트가 수정 전 270,350,272 bytes 할당으로 실패했고, 블록 공유·지연 배열 생성 적용 후 64 MiB 미만 회귀 조건을 통과했다.
- 같은 리뷰의 큰 헤더 반복 복사도 회귀 테스트로 확인했다. 4,000개 필드와 256 KiB/1 KiB 읽기에서 수정 전 108,253,416 bytes 할당으로 실패했고, 변경 시에만 새 불변 맵을 생성하도록 수정 후 64 MiB 미만 조건을 통과했다.
- parser 실패 후 재읽기가 다시 소켓 read를 수행하는 결함은 실제 소켓으로 재현했다. 수정 전 두 번째 읽기의 timeout 때문에 ACTION_CLOSE 대신 ERROR가 발생했다. decoder 상태를 I/O 전에 검사하도록 변경한 뒤 통과했다.
- 최종 JDK 17.0.10 / Maven 3.9.11 `mvn -f http-mock-response-server/pom.xml clean verify`: **51개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS**. 기존 32개 및 새 context 6개/parser 13개다.
- SafetyProbe를 실제 JDK 17로 별도 컴파일·실행하여 **14개 검사항목** 통과. README main/UploadCase/HttpCase를 생성 JAR만 classpath에 두고 컴파일했으며 main 예제와 실제 HTTP protocol 예제를 실행했다.
- 최종 별도 코드 리뷰에서 추가 actionable 지적 없음. Windows loopback 기준이며 다른 OS·대규모 장기 부하는 검증하지 않았다. 커밋/push는 수행하지 않았다.
