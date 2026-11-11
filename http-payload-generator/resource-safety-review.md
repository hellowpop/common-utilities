# 리소스 누수 및 NPE 분석·조치

## Multipart 후속 분석·조치 (2026-11-11)

범위: multipart 추가분과 ContentType.generate → DummyInputStream → DefaultHttpPayloadGenerator → NettyPayloadEncoder의 소유권 경로 및 연관 검증 코드. 아래 2026-10-16 기록은 이전 분석 이력이다.

### 발견 및 수정

| 구분 | 발생 조건·근거 | 조치 |
|---|---|---|
| 검증 코드의 요청 ByteBuf 누수 | MultipartPayloadTest에서 디코더를 try 범위 전에 생성했다. maxFields=1로 두 번째 파트를 거부하면 생성자가 예외를 던져 request.release()가 실행되지 않았다. 요청 refCnt=1로 실패하는 테스트로 재현했다. | 디코더 생성 전부터 요청을 바깥 try/finally로 소유하고, 디코더 destroy의 성공 여부와 관계없이 요청을 해제한다. |
| 검증 코드의 file 파트 버퍼 누수 | Netty 4.1.139.Final의 DefaultHttpDataFactory(false)는 memory 파트를 factory 정리 목록에 등록하지 않는다. 디코더가 file 파트를 만든 뒤 maxFields 제한으로 bodyList 등록을 거부하면 생성자 내부 destroy가 해당 파트를 정리하지 못한다. 실제 file 파트 refCnt=1과 PARANOID LEAK 로그를 확인했다. | 검증 helper를 기본 mixed-mode DefaultHttpDataFactory()로 변경하여 목록 등록 전 거부된 파트까지 factory가 추적·정리하게 했다. 동일 실패 조건에서 요청과 file 파트 refCnt=0을 직접 검사한다. 외부 Netty 소스나 생성기 공개 API는 변경하지 않았다. |

소스 근거는 로컬 Maven `netty-codec-http-4.1.139.Final-sources.jar`의 HttpPostMultipartRequestDecoder 생성자·parseBodyMultipart/addHttpData/destroy 및 DefaultHttpDataFactory createFileUpload/cleanRequestHttpData 경로다. 생성기 운영 코드는 multipart 디코더를 사용하지 않는다.

### 메모리·NPE 검토 결과

- DummyInputStream은 파트 목록이나 전체 파일 내용을 쌓지 않는다. 현재 헤더의 작은 byte 배열, long 카운터와 영숫자 순번만 유지하며 파트 수·본문 길이·blockSize에 비례하는 배열을 만들지 않는다. Long.MAX_VALUE 부분 읽기도 검증한다. 헤더 String/byte[]의 반복 할당은 GC 대상이며 누적 참조를 보관하지 않는다.
- DefaultHttpPayloadGenerator는 원문 스트림·압축 파일 입출력·임시 파일을 중첩 try-with-resources로 정리한다. NettyPayloadEncoder는 입력 쓰기 후 소유권을 채널에 넘기고 출력 버퍼는 finally로 해제한다. 기존 초기화/입출력 실패의 refCnt 검증을 유지했다.
- enum의 multipart 설정값은 non-null이고 공개 contentType/contentEncoding/acceptor는 자원 생성 전에 검사한다. 이번 범위에서 정상 인수로 발생하는 생성기 내부 NPE는 발견하지 못했다. 잘못된 read 버퍼의 NPE와 callback이 던진 NPE는 호출 계약에 따른 실패로 구분한다.
- NPE/Error/RuntimeException을 던진 callback에도 동일 예외를 전달하고 스트림과 파일을 정리하는지 모든 ContentType × ContentEncoding에서 확인한다. 디코더 consumer NPE 후 실제 요청·text/file 파트 refCnt=0도 확인한다.

### 회귀 검증 구성

- PayloadResourceSafetyTest: 7개 ContentType × 6개 ContentEncoding × 7개 callback 종료 경로(미읽기/부분/전체/조기 close/RuntimeException/NPE/Error), 종료 후 스트림 읽기 거부·임시 파일 삭제.
- 공개 null 인수는 모든 타입·encoding에서 이름이 명시된 NPE 및 임시 파일 미생성을 검사한다. TEXT_PLAIN/MULTIPART_FORM_DATA는 null/잘못된 read 범위가 위치를 바꾸지 않는지 확인한다.
- PayloadCancellationTest: multipart의 5개 압축 encoding에서 interrupt 상태 보존·callback 미호출·예외 원인·취소 후 임시 파일 미잔류.
- MultipartPayloadTest: 디코더 생성 실패 시 요청·거부된 file 파트 해제, consumer NPE 시 요청·모든 등록 파트 해제. 실패 테스트의 최종 보호 정리는 단언 실패 시 테스트 자체가 버퍼를 남기지 않도록 한다.

검증의 한계: PARANOID 로그 부재만으로 모든 실행 환경의 누수 부재를 증명하지 않는다. 실제 참조 수·파일 상태·스트림 상태를 함께 검사한다. OS 파일 잠금/권한 변경, JVM 강제 종료, 호출자 소유 자원의 미해제에 대한 기존 한계는 유지한다.

최종 검증: Windows x86_64/JDK 21.0.3에서 `mvn -f http-payload-generator/pom.xml -Dio.netty.leakDetection.level=paranoid clean verify` 실행. 총 123개 테스트, 실패·오류·건너뜀 0, BUILD SUCCESS이며 해당 실행 로그에 LEAK 보고가 없었다. JAR·소스 JAR을 생성했고 문서를 포함한 최종 산출물의 install은 `-Dmaven.test.skip=true`로 수행한다. 운영 코드에서 이번 점검으로 추가 수정할 내부 NPE/누수는 발견하지 못했으며 위 두 조치는 검증 코드의 실패 경로에 적용했다.

분석일: 2026-10-16. 대상은 `http-payload-generator` 모듈이다. 기존 다른 모듈은 회귀 테스트 대상으로만 확인했다.

## 확인된 문제와 조치

| 문제 | 발생 조건과 영향 | 조치 | 재현·회귀 테스트 |
|---|---|---|---|
| 입력 ByteBuf 누수 | codec이 첫 블록 처리 후 채널을 닫으면 다음 `EmbeddedChannel.writeOutbound`가 버퍼 소유권을 받기 전에 실패한다. 새 입력 버퍼 refCnt가 1로 남는 것을 재현했다. | `Channel.writeAndFlush`로 소유권을 이전하고 실패 future를 예외 경로로 전달한다. pending task 실행 후 오류를 확인한다. | `channelClosedBetweenBlocksDoesNotLeakUnacceptedInputBuffer`: 할당된 실제 버퍼 전부 refCnt=0 확인 |
| 원래 예외 소실 | 입력 읽기 실패와 채널 정리 실패가 겹치면 기존 finally의 정리 예외가 원래 IOException을 덮어쓴다. | 채널을 AutoCloseable로 관리하여 원래 예외를 유지하고 정리 예외를 suppressed로 보존한다. | `originalReadFailureSurvivesCleanupFailure`: 원래 IOException 객체와 suppressed 정리 예외 확인 |
| 초기화 실패 은폐 | `new EmbeddedChannel(handler)` 내부 ChannelInitializer가 handlerAdded 실패를 로깅하고 닫아 버려 빈 입력에서 실패가 호출자에게 전달되지 않는 것을 재현했다. | 빈 채널을 자원 관리 범위에 먼저 넣고 handler를 추가한 뒤 checkException으로 실패를 확인한다. | `handlerInitializationFailureClosesChannelAndReleasesItsBuffers`: 초기화 원인 전달, 채널 종료, 할당 버퍼 해제 확인 |

변경은 package-private `NettyPayloadEncoder`에 모았으며 공개 API는 유지한다. 압축 출력 파일을 연 뒤 codec을 생성해 파일 열기 실패 시 불필요한 codec 자원을 할당하지 않는다.

Netty의 소유권 처리와 비교한 근거는 [EmbeddedChannel 소스](https://netty.io/4.1/xref/io/netty/channel/embedded/EmbeddedChannel.html) 및 로컬 Maven의 동일 버전(4.1.139.Final) sources JAR이다. 특히 writeOutbound 진입 시 ensureOpen이 실행되는 순서와 finishAndReleaseAll의 정리 경로를 확인했다.

## 자원별 소유권

| 자원 | 소유자 | 종료·실패 시 처리 |
|---|---|---|
| 원문 DummyInputStream | ContentType.generate | try-with-resources로 callback/압축 실패 시에도 close |
| 임시 파일과 파일 입출력 스트림 | DefaultHttpPayloadGenerator | 출력 스트림 → 압축 결과 입력 스트림 → 임시 파일 순서로 각 범위에서 정리 |
| EmbeddedChannel와 codec | NettyPayloadEncoder | handler 초기화 전부터 자원 관리. 종료 시 finishAndReleaseAll 및 inbound/outbound 큐 최종 해제 |
| 입력 ByteBuf | 쓰기 호출 전 생성기, writeAndFlush 호출 후 채널 | 닫힌 채널에 대한 쓰기 거부도 채널이 release |
| 출력 ByteBuf | outbound 큐에서 꺼낸 후 drain | 파일 쓰기가 실패해도 finally에서 현재 버퍼 release, 남은 큐는 채널 정리 시 해제 |
| callback이 생성한 request/response/추가 파일 핸들 | 호출자 | 호출자가 전송 완료 후 release/close |

내부 압축 helper는 전달받은 입력/출력 스트림을 닫지 않는다. 이 스트림들은 생성기의 외부 try-with-resources가 관리한다.

## NPE 분석

- 공개 인수 `contentType`, `contentEncoding`, `acceptor`는 기존 Objects.requireNonNull로 자원 생성 전에 거부한다. 모든 enum 경로에서 인수 이름을 포함한 예외와 임시 파일 미생성을 확인했다.
- 내부 압축 helper도 encoder/in/out 인수를 검사한 뒤 채널을 만든다.
- 정상 생성 경로의 헤더, 스트림, enum 설정에는 nullable 상태가 없으며, 이번 분석·테스트 범위에서 예기치 않은 내부 NPE는 발견되지 않았다.
- `InputStream.read(null, ...)`의 NPE는 표준 인수 계약이다. 잘못된 버퍼·범위가 스트림 위치를 변경하지 않는지 확인했다.
- callback 자체에서 발생한 NPE는 동일한 예외로 전파한다. NPE를 포괄적으로 잡아 성공 처리하지 않으며 해당 경우에도 스트림과 임시 파일은 정리한다.
- generate API 변경 후 contentType은 각 호출의 두 번째 인수에서 검사한다. 모든 Content-Encoding에 대해 null contentType 거부와 임시 파일 미생성을 다시 검증했다.

## 검증

- 기존 기능 테스트 24개 + 압축 실패·소유권 테스트 5개 + 공개 수명/null 테스트 8개 = 37개.
- 모든 encoding에 대해 callback 미읽기, 부분/전체 읽기, 조기 close, RuntimeException, NPE, Error의 7개 종료 경로를 검사한다. 압축 파일이 callback 중 존재하고 이후 사라지는지도 검사한다.
- 출력 쓰기 실패, 한 블록 이후 입력 읽기 실패 시 실제 출력 ByteBuf의 refCnt=0과 codec handler 제거를 검사한다.
- `mvn -f http-payload-generator/pom.xml -Dio.netty.leakDetection.level=paranoid clean verify`로 테스트 및 JAR 빌드를 수행한다. 참조 수 직접 검증을 함께 사용하며 누수 로그가 없다는 사실만으로 판단하지 않는다.
- 최종 실행 결과: 37 tests, failures/errors/skipped 0, BUILD SUCCESS. 기존 모듈 partial-input 13개, object-recycle-container 58개, ldap-connection-pool 33개도 재실행하여 모두 통과했다.
- 독립 코드 검토에서 추가 조치가 필요한 정확성 문제는 발견되지 않았다.
- 후속 generate API 변경 검증: 호출별 ContentType 선택 테스트 6개를 추가하여 총 43개 테스트와 JAR 빌드가 PARANOID 설정에서 통과했다.

## 보장 범위

검증 환경은 Windows x86_64/JDK 21이다. JVM 강제 종료, 프로세스 중단, OS 권한 변경·외부 파일 잠금에 따른 임시 파일 삭제 실패까지 제거를 보장하지 않는다. 삭제 실패는 IOException/UncheckedIOException 또는 기존 실패의 suppressed 예외로 전달한다. callback 밖에서 스트림을 비동기로 사용하거나 호출자가 별도로 연 핸들을 닫지 않는 경우는 호출자 소유권에 해당한다.
