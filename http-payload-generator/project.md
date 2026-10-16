# HTTP Payload Generator 구성

## 목적과 계약

Java 21 독립 Maven 모듈. 모든 클래스는 `com.inzent.apim.utilities.http.payload`에 둔다.
Netty `HttpHeaders`와 payload `InputStream`을 동기 콜백에 전달한다.
`ContentType.generate(long contentLength, long blockSize, Consumer<InputStream>)`로 원문만 생성할 수도 있다.
`HttpPayloadGenerator.generate(boolean chunked, ContentType contentType, ContentEncoding encoding, long contentLength, long blockSize, BiConsumer<HttpHeaders, InputStream>)`를 공개한다.
`new DefaultHttpPayloadGenerator()`로 생성하고 Content-Type은 generate의 두 번째 인수로 매번 지정한다.

## 구성

- `ContentType`: TEXT_PLAIN, APPLICATION_JSON, APPLICATION_XML, TEXT_HTML, APPLICATION_FORM_URLENCODED, APPLICATION_OCTET_STREAM. 각 enum이 형식별 생성 전략을 가진다.
- `DummyInputStream`: long 크기의 원문을 고정 메모리로 지연 생성. ASCII만 사용하여 문자/바이트 길이가 같다.
- `ContentEncoding`: IDENTITY, GZIP, DEFLATE(zlib wrapper), BR, ZSTD, SNAPPY(Netty framed format). Netty가 지원하는 코딩 전체를 대상으로 한다. IANA 레지스트리 전체나 사전 기반 코딩은 범위에 포함하지 않는다.
- `DefaultHttpPayloadGenerator`: Netty 압축 encoder를 사용하고 결과를 임시 파일에 보관한다. callback 완료/예외 시 파일과 스트림을 해제한다.
- `NettyPayloadEncoder`: codec과 채널을 소유하고 초기화 실패·쓰기 실패를 전달한다. 입력 ByteBuf는 writeAndFlush에 소유권을 넘기며 출력/대기 버퍼를 정리한다. 채널 정리 오류는 원래 실패의 suppressed 예외로 보존한다.

## 길이와 수명

`contentLength`는 압축 전 정확한 바이트 수다. `blockSize > 0`이며 key/value 형식의 value 최대 길이다. 마지막 value는 짧을 수 있다.
JSON/XML/HTML은 남은 공간에 공백을 넣는다. form은 마지막 빈 필드/구분자로 남는 길이를 맞춘다.
0바이트는 모든 타입에서 빈 스트림이다. 양수이면서 최소 문서 크기 미만인 JSON/XML/HTML은 거부한다.
chunked이면 Transfer-Encoding만, 아니면 실제 인코딩 결과의 Content-Length만 지정한다.
스트림은 callback 안에서 소비해야 하며 callback 이후 닫힌다. HTTP chunk 크기/프레이밍은 호출자의 Netty pipeline 책임이다.
압축은 생성 호출 중 완료되며 임시 디스크 공간은 인코딩된 크기에 비례한다. 원문 생성은 지연되지만 압축 생성은 지연되지 않는다.

## 구현 계획 및 검증

1. 테스트: 각 타입 정확한 길이, JSON/XML 파싱, value 길이, long 크기 부분 읽기, 잘못된 인수.
2. 구현: ContentType과 상수 메모리 DummyInputStream.
3. 테스트: 모든 압축 형식 원문 복원, chunked 헤더 배타성, 압축 결과 길이, callback 실패와 스트림 수명.
4. 구현: ContentEncoding, generator 인터페이스와 기본 구현.
5. `mvn -f http-payload-generator/pom.xml clean verify` 실행 후 README 사용 예제와 제한을 정리한다.

설계 선택: 전체 byte 배열 대신 지연 스트림과 임시 파일을 사용해 long 크기 API와 정확한 헤더를 동시에 지원한다. 별도 비동기 생산 스레드는 도입하지 않는다.

## 검증 기록 (2026-10-16)

- `mvn -f http-payload-generator/pom.xml clean verify`: 24 tests, failures/errors/skipped 0, JAR 빌드 성공.
- 모든 압축 형식에 대해 0/1/8191/8192/8193/200000바이트 원문 복원 확인.
- JSON/XML 파싱, 길이별 남은 공간 처리, Long.MAX_VALUE 크기의 부분 읽기, callback 실패 후 스트림 종료 확인.
- 기존 모듈 회귀 테스트: partial-input 13개, object-recycle-container 58개, ldap-connection-pool 33개 통과.
- Brotli4j 1.18.0에서 Netty decoder API 불일치를 재현했으며 Netty parent POM이 지정하는 1.23.0으로 정렬해 해결.
- 독립 코드 검토에서 비라틴 기본 로케일에 따른 key 손상을 발견. 아랍어 로케일에서 XML 파싱 실패를 재현한 후 Locale.ROOT로 수정하고 회귀 테스트를 통과했다.
- 실행 환경: Windows x86_64, JDK 21. 타 플랫폼 native 코딩은 이 환경에서 실행 검증하지 않았다.

## 리소스/NPE 후속 분석

- 분석과 조치 상세: [resource-safety-review.md](resource-safety-review.md).
- 닫힌 채널에 쓰기 시 입력 ByteBuf 누수(refCnt=1), 초기화 실패 은폐, 정리 예외에 의한 원래 오류 소실을 실패 주입 테스트로 재현하고 수정했다.
- 기존 24개에 13개 테스트를 추가하여 총 37개를 PARANOID 누수 감지 설정에서 검증한다.
- null 공개 인수의 fail-fast 및 callback NPE/Error 시 스트림·임시 파일 정리 경로를 확인했다. 정상 인수 경로의 예기치 않은 내부 NPE는 발견되지 않았다.

## generate API 변경 (2026-10-16)

- ContentType을 생성자 설정에서 generate의 두 번째 인수로 이동했다. 기본 구현은 무인수 생성자로 생성하며 호출별 상태를 보관하지 않는다.
- transfer encoding은 기존 boolean chunked, content encoding은 기존 enum, 길이와 callback 계약은 유지한다.
- 같은 인터페이스 인스턴스로 6개 Content-Type을 순차 생성하는 테스트를 모든 Content-Encoding에 대해 추가했다. null contentType은 파일 생성 전에 거부한다.
- `mvn -f http-payload-generator/pom.xml -Dio.netty.leakDetection.level=paranoid clean verify`: 43 tests, failures/errors/skipped 0, BUILD SUCCESS.

## 푸시 전 검증 (2026-10-16)

README의 호출 예제와 공개 인터페이스가 ContentType을 두 번째 인수로 받도록 일치하는지 확인했다. PARANOID 설정으로 clean verify를 다시 실행하여 43개 테스트와 JAR 빌드 성공을 확인했다. 빌드 결과물은 Git에서 제외하며 소스·테스트·Maven 설정과 기술 문서를 전달한다.
