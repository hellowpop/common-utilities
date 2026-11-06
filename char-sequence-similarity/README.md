# CharSequence Similarity

Java 17 이상에서 `CharSequence` 두 개의 유사도를 `float`으로 반환하는 유틸리티다. 외부 런타임 라이브러리가 필요 없다.

## 사용법

```java
import com.inzent.apim.utilities.text.CharSequenceSimilarity;

float exact = CharSequenceSimilarity.compare("hello", "hello");             // 100.0
float ignoreCase = CharSequenceSimilarity.compare("HELLO", "hello");       // 99.0
float naming = CharSequenceSimilarity.compare("hello_world", "hello-world"); // 98.0
float camel = CharSequenceSimilarity.compare("helloWorld", "hello_world"); // 98.0
float edges = CharSequenceSimilarity.compare(" [hello!]\r\n", "hello");     // 97.0
float spaces = CharSequenceSimilarity.compare("a bc", "abc");              // 75.0
float different = CharSequenceSimilarity.compare("abc", "acb");            // 0.0

// StringBuilder, StringBuffer, CharBuffer 등도 전달 가능하다.
float builders = CharSequenceSimilarity.compare(
        new StringBuilder("hello"), new StringBuffer("HELLO"));            // 99.0
```

메서드는 `public static float compare(CharSequence origin, CharSequence ref)`다. 두 인수를 바꾸어도 점수는 같다.

## 점수 규칙

아래 순서로 검사하여 처음 성립한 점수를 반환한다.

| 조건 | 점수 |
| --- | ---: |
| 완전히 동일 | 100 |
| 대소문자만 무시하여 동일 | 99 |
| 단어 사이 `_` 또는 `-`를 제거하고 대소문자 무시하여 동일 | 98 |
| 앞뒤 공백·문장부호·기호를 제거하고 대소문자 무시하여 동일 | 97 |
| 앞뒤를 정리한 다음 중간 공백을 제거하면 문자 순서가 동일 | 공백 비율 점수 |
| 나머지 | 0 |

snake/kebab 비교는 Unicode 문자·숫자로 된 단어와 단어 사이 `_`·`-`에 적용한다. 구분자 없는 이름이나 camelCase와도 비교한다. 앞뒤 `_`·`-`는 97점 규칙에서 제거하며, 중간의 `.` 같은 특수문자는 제거하지 않는다. snake/kebab과 중간 공백 제거는 별도로 검사하므로 `"a_b c"`와 `"ab c"`는 0이다.

공백은 space, tab, CR, LF, form feed 및 Unicode 공백(NBSP 포함)이다. Java `Character.isWhitespace` 또는 `Character.isSpaceChar`를 만족하는 문자다.

```text
L = 앞뒤 공백·문장부호·기호를 제거한 두 문자열 중 더 긴 길이
W = 해당 두 문자열 중 더 많은 공백 수
score = 100 - (W / L × 100)
```

길이와 공백 수는 UTF-16 `char` 단위로 계산한다. 예를 들어 `"a bc"`와 `"abc"`는 `100 - 1/4 × 100 = 75`다. `"a  b"`와 `"a b"`는 공유하는 공백도 포함하여 `100 - 2/4 × 100 = 50`이다. 앞뒤 공백은 감점하지 않는다. 공백 비율 점수는 97보다 클 수도 있으며, 고정 점수 범위로 제한하지 않는다.

빈 문자열끼리는 100이다. 앞뒤 제거 후 내용이 없어지는 서로 다른 입력(`"!!!"` / `"???"`, `" "` / `""` 등)은 0이다. `null`을 전달하면 `NullPointerException`이 발생한다. 대소문자 비교는 `String.equalsIgnoreCase`를 사용한다. 호출 중 변경 가능한 입력의 동시 변경은 호출자가 방지해야 한다.

## 빌드 및 의존성

저장소 루트에서 실행한다.

```shell
mvn -f char-sequence-similarity/pom.xml clean verify
mvn -f char-sequence-similarity/pom.xml install
```

생성 JAR: `char-sequence-similarity/target/char-sequence-similarity-1.0.0-SNAPSHOT.jar`.

`package` 단계에서 `*-sources.jar`를 생성·첨부한다. 모듈 루트의 Markdown 문서는 기본 JAR과 소스 JAR의 `META-INF/docs/char-sequence-similarity/`에 포함된다.

로컬 Maven 저장소에 설치한 뒤 사용 프로젝트에 추가한다.

```xml
<dependency>
  <groupId>com.inzent.apim.utilities</groupId>
  <artifactId>char-sequence-similarity</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

구성과 상세 계약은 [project.md](project.md)를 참고한다.
