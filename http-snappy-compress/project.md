# HTTP Snappy Compress 구성

- Java 17 독립 Maven 모듈, Apache Commons Compress 1.28.0 사용. 직접 codec을 구현하는 대신 라이브러리 사용 예제를 제공한다.
- 패키지: `com.inzent.apim.utilities.http.snappy`.
- `SnappyStreamExample.compress(InputStream, OutputStream, long)`: raw Snappy 압축. 원본 길이를 헤더에 기록하며 실제 입력 길이가 다르면 IOException.
- `decompress(InputStream, OutputStream)`: raw Snappy 해제.
- `compressFramed(InputStream, OutputStream)`, `decompressFramed(InputStream, OutputStream)`: 길이를 미리 알 수 없는 입력의 framed 예제.
- 예제 메서드는 전달받은 입력/출력을 모두 닫는다. 출력 종료 시 잔여 압축 데이터도 완료한다. HTTP 스트림을 직접 관리해야 하면 README의 finish 예제를 사용한다.
- 오류 시 출력에 일부 데이터가 기록될 수 있다. raw 해제는 한 블록을 처리하며 후행 데이터 검증을 추가하지 않는다.
- Maven package에서 소스 JAR을 첨부하고 모듈 루트 Markdown을 `META-INF/docs/http-snappy-compress/`에 포함한다.

## 구현 및 검증 계획

1. raw/framed 왕복, 알려진 raw 블록, 반복 데이터 압축, 잘못된 크기/절단 입력, 스트림 종료 테스트 작성.
2. 미구현 컴파일 실패 확인 후 Commons Compress 기반 스트림 예제 구현.
3. clean verify 실행, 기본/소스 JAR과 문서 포함 확인.

## 검증 기록 (2026-11-06)

- `mvn -f http-snappy-compress/pom.xml clean verify` 성공: 테스트 5개, 실패/오류/건너뜀 0.
- raw/framed 빈 입력·랜덤 데이터 왕복, 알려진 raw 블록, 반복 데이터 압축, 크기 불일치·절단 입력 및 스트림 종료 검증.
- 기본 JAR과 소스 JAR의 Markdown 포함 및 소스 JAR의 Java 소스 포함 확인.
- 독립 코드 리뷰에서 중요 결함 없음. HTTP 연동, 해제 크기 제한, raw 후행 데이터 검증은 예제 범위 밖이다.
- install 실행 시 프로젝트 규칙에 따라 `-DskipTests`를 지정한다. 테스트는 별도 `verify`로 수행한다.

## 규격 참고

- https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/snappy/SnappyCompressorOutputStream.html
- https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/snappy/FramedSnappyCompressorOutputStream.html
