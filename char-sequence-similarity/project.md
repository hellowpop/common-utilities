# CharSequence Similarity 구성

- 독립 Maven 모듈: `char-sequence-similarity`, Java 17 이상.
- 공개 클래스: `com.inzent.apim.utilities.text.CharSequenceSimilarity`.
- 진입 메서드: `public static float compare(CharSequence origin, CharSequence ref)`.
- 외부 런타임 의존성 없이 구현하며 JUnit Jupiter로 검증한다.

## 비교 설계 및 구현 순서

편집 거리 대신 요청한 규칙을 순차 적용한다. 첫 번째로 만족한 규칙의 값을 반환하므로 공백 비율 점수가 97보다 높아도 앞선 규칙을 다시 평가하지 않는다.

1. 원문 전체 일치: 100. 빈 문자열끼리도 100.
2. 원문 전체 대소문자 무시 일치: 99.
3. 단어 사이 snake(`_`)/kebab(`-`) 구분자를 제거하여 대소문자 무시 일치: 98. 영문자 외 Unicode 문자와 숫자를 단어로 허용한다. camelCase/구분자 없는 이름과도 비교한다. 앞뒤 구분자는 이 단계에서 제거하지 않는다.
4. 양쪽 끝의 공백·Unicode 문장부호·기호를 제거한 비어 있지 않은 문자열이 대소문자 무시 일치: 97. 중간의 특수문자는 유지한다.
5. 4단계에서 얻은 문자열의 공백을 모두 제거한 비어 있지 않은 내용이 대소문자 무시 일치하면 공백 비율 점수 반환.
6. 나머지: 0. 문자 순서 변경, 일반 문자 삽입·삭제·치환은 유사 일치로 인정하지 않는다.

공백은 `Character.isWhitespace` 또는 `Character.isSpaceChar`가 참인 문자로 정의한다. space/tab/CR/LF, form feed, Unicode 공백과 NBSP를 포함한다. 그 외 제어문자는 임의로 제거하지 않는다.

공백 비율은 `100 - 100 × max(양쪽 공백 수) / max(양쪽 전체 char 수)`이다. 길이와 공백 수는 4단계에서 앞뒤를 정리한 문자열의 UTF-16 `char` 단위로 센다. 양쪽에 공백이 있으면 많은 쪽의 비율로 감점하고 공유하는 공백도 센다. 이 정의는 인수 순서에 영향을 받지 않는다.

`null`은 `NullPointerException`으로 거부한다. 공백·특수문자를 제거한 결과만 비어 있는 두 입력은 유사 일치로 처리하지 않는다(원문이 동일하면 100). 대소문자 비교는 Java `String.equalsIgnoreCase`를 따른다. 구분자 제거·앞뒤 장식 제거·중간 공백 제거를 모두 합친 느슨한 비교는 하지 않는다.

## 작업 및 검증

1. 우선순위, 공백 비율, 대칭성, 경계 입력과 CharSequence 구현체 테스트 작성 및 실패 확인.
2. 순차 비교 구현.
3. 모듈 전체 `mvn clean verify` 실행 및 README 사용법 정리.

루트 집계 POM이 없으므로 이 모듈의 POM에서 독립적으로 빌드한다. 입력은 호출 시 String으로 복사하고 상태를 보관하지 않는다. 호출 중 변경 가능한 CharSequence의 동시 변경은 호출자가 방지한다.

검증 결과(2026-11-05): 초기 미구현 상태에서 테스트 34개 중 26개의 기대값 실패를 확인했다. 리뷰에서 긴 snake/kebab 이름의 정규식 스택 오류를 발견하고 회귀 테스트로 재현한 뒤 코드 포인트 반복문으로 변경했다. 최종적으로 실제 JDK 17.0.10의 `mvn -B -f char-sequence-similarity/pom.xml clean verify`에서 긴 입력 회귀 테스트 포함 35개 전체 통과, 실패·오류·건너뜀 0 및 JAR 생성을 확인했다.

## 패키징 및 최종 점검 (2026-11-06)

기본 package에서 소스 JAR을 생성·첨부하고 모듈 루트 Markdown을 `META-INF/docs/<artifactId>/`에 포함한다. 저장소 전체 검증 명령·결과와 외부 환경 미검증 범위는 루트 `project.md`의 전체 변경 푸시 전 검증 항목을 참고한다.