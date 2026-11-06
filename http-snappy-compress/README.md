# Apache Commons Compress Snappy 스트림 예제

Java 17 이상. Snappy 구현은 순수 Java이며 JNI 설치가 필요 없다. Maven이 전이 의존성을 함께 구성하도록 한다.

```xml
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-compress</artifactId>
  <version>1.28.0</version>
</dependency>
```

## Raw Snappy 압축과 해제

raw 출력 생성자에는 **압축 전 바이트 수**가 필요하다. `SnappyCompressorOutputStream(OutputStream)` 생성자는 없다.

```java
import java.io.*;
import java.nio.file.*;
import org.apache.commons.compress.compressors.snappy.*;

// 압축: 원본 파일은 작업 중 변경되지 않아야 한다.
Path original = Path.of("request.json");
try (InputStream in = Files.newInputStream(original);
     OutputStream file = Files.newOutputStream(Path.of("request.snappy"));
     SnappyCompressorOutputStream out =
         new SnappyCompressorOutputStream(file, Files.size(original))) {
    in.transferTo(out);
} // close()에서 잔여 압축 데이터를 완료한다.

// 해제
try (InputStream file = Files.newInputStream(Path.of("request.snappy"));
     SnappyCompressorInputStream in = new SnappyCompressorInputStream(file);
     OutputStream out = Files.newOutputStream(Path.of("restored.json"))) {
    in.transferTo(out);
}
```

프로젝트의 `SnappyStreamExample`에도 같은 스트림 메서드를 제공한다.

```java
SnappyStreamExample.compress(source, target, uncompressedByteCount);
SnappyStreamExample.decompress(compressedSource, restoredTarget);
```

이 메서드는 전달받은 스트림을 모두 닫는다. 원본 크기는 문자 수가 아닌 바이트 수이며 `InputStream.available()`로 구하지 않는다. 음수와 raw 규격 최대치 4,294,967,295 초과는 거부한다. 실제 입력 크기 불일치/읽기/쓰기 오류 시 IOException을 전파하며 부분 출력은 호출자가 폐기한다.

## 길이를 모르는 스트림: Framed Snappy

```java
try (InputStream in = source;
     FramedSnappyCompressorOutputStream out =
         new FramedSnappyCompressorOutputStream(target)) {
    in.transferTo(out);
}

try (FramedSnappyCompressorInputStream in =
         new FramedSnappyCompressorInputStream(compressedSource);
     OutputStream out = restoredTarget) {
    in.transferTo(out);
}
```

`SnappyStreamExample.compressFramed` / `decompressFramed`에서도 사용할 수 있다. raw와 framed는 다른 포맷이다. 송수신자가 같은 포맷을 사용해야 하며 HTTP 헤더만으로 포맷을 자동 판단하지 않는다. 이 모듈은 HTTP 클라이언트/서버나 헤더를 구성하지 않는다.

## HTTP 출력 스트림을 닫지 않고 완료하기

호출자가 소유한 응답 스트림에는 직접 라이브러리를 사용한다.

```java
SnappyCompressorOutputStream compressed =
    new SnappyCompressorOutputStream(httpOutput, uncompressedByteCount);
source.transferTo(compressed);
compressed.finish(); // 잔여 압축 데이터를 기록하고 httpOutput은 열어 둔다.
httpOutput.flush();
```

`flush()`만으로 압축을 완료하지 않는다. `finish()` 이후 추가 본문을 쓰지 않는다. source와 httpOutput의 종료는 소유자가 담당하며 실패 시 부분 응답을 정상 응답으로 취급하지 않는다. raw에는 체크섬이 없으며 이 예제에는 해제 크기 제한이 없으므로 서비스 적용 시 HTTP 요청 크기/해제 크기 정책을 별도로 적용한다.

## 빌드

```powershell
mvn -f http-snappy-compress/pom.xml clean verify
```

로컬 Maven 저장소에 설치할 때는 테스트를 별도 수행한 뒤 다음과 같이 실행한다.

```powershell
mvn -f http-snappy-compress/pom.xml install -DskipTests
```

`target/http-snappy-compress-1.0.0-SNAPSHOT.jar`와 `*-sources.jar` 생성. 두 JAR에 `META-INF/docs/http-snappy-compress/` 문서가 포함된다. 의존성은 JAR에 합치지 않으므로 사용하는 애플리케이션은 Maven 의존성을 함께 구성한다.
