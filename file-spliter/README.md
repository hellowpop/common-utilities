# File Splitter

정규식으로 입력 파일을 검출값별로 분할하거나, 분할된 파일을 내용에 따라 분류하는 명령줄 도구다. Java 17 이상에서 실행한다.

## 빌드

저장소 루트에서 실행한다.

```powershell
mvn -f file-spliter/pom.xml clean package
```

배포용 실행 파일은 `file-spliter/target/file-spliter.jar`다. 빌드에서 소스 JAR도 Maven 산출물로 첨부한다. 기본 빌드는 테스트 실행을 생략한다. 테스트까지 실행하려면 다음 명령을 사용한다.

```powershell
mvn -f file-spliter/pom.xml '-DskipTests=false' clean verify
```

## 실행

```powershell
java -jar file-spliter/target/file-spliter.jar --help
```

### 분할 (`split`)

```powershell
java -jar file-spliter/target/file-spliter.jar split '<정규식>' '<출력 폴더>' '<파일 경로 또는 패턴>' ['<추가 경로 또는 패턴>' ...]
```

예를 들어 아래 명령은 각 줄에서 `key=` 뒤의 값을 찾아 `output/<검출값>.txt`로 분리한다.

```powershell
java -jar file-spliter/target/file-spliter.jar split '^key=([A-Za-z0-9_-]+)' output first.log second.log.gz
```

`first.log`에 다음 내용이 있고, `second.log.gz`를 풀었을 때 `추가 본문`으로 시작한다고 가정한다.

```text
머리말
key=ALPHA
첫 본문
key=BETA
```

`output/ALPHA.txt`에는 `key=ALPHA`와 `첫 본문`이 들어간다. `output/BETA.txt`에는 `key=BETA`와 두 번째 파일의 `추가 본문`이 들어간다. 이후 `ALPHA`가 다시 검출되면 이미 열린 `ALPHA.txt`에 이어 쓴다.

- 각 줄에서 첫 매치의 첫 캡처 그룹을 키로 사용한다. 캡처 그룹이 없는 정규식은 매치 전체를 사용한다.
- 입력은 인수 순서대로 읽는다. 첫 매치 이전의 줄은 건너뛴다. 이후에는 원본 파일이 바뀌어도 다음 매치까지 직전 키에 기록한다.
- `.gz` 확장자는 대소문자를 구분하지 않는다. 일반 파일과 gzip 파일을 함께 지정할 수 있으며, gzip은 원본을 보존하고 별도 압축 해제 파일 없이 읽는다. gzip을 읽는 중 압축 해제에 실패하면 입출력 오류로 종료한다.
- 입력과 출력은 UTF-8이다. 각 입력 파일 맨 앞의 UTF-8 BOM은 제거한다. 출력은 LF 줄바꿈을 사용하며 마지막 줄에도 LF를 붙인다.
- 출력 디렉터리는 필요할 때 생성한다. 각 키는 유효한 파일명이어야 하며 같은 이름의 기존 항목이 있으면 오류로 종료한다. 실패 시 이미 생성된 출력은 남는다.
- 파일명 대소문자를 구분하지 않는 파일시스템에서는 서로 다른 키인 `A`와 `a`도 같은 출력 경로로 처리되어 충돌 오류가 발생한다.

#### 파일명 패턴으로 입력 지정

```powershell
java -jar file-spliter/target/file-spliter.jar split '^key=([A-Za-z0-9_-]+)' output '*.txt'
java -jar file-spliter/target/file-spliter.jar split '^key=([A-Za-z0-9_-]+)' output first.log 'logs/acb-log*.txt' 'archive/*.gz'
```

- 파일명의 `*`는 임의 길이의 문자열과 일치하고, `?`는 문자 한 개와 일치한다. 명령줄에서 패턴을 따옴표로 감싸 전달한다.
- 폴더를 생략하면 현재 작업 폴더를 사용한다. 폴더를 지정하면 그 바로 아래의 일반 파일만 선택하며, 와일드카드는 파일명에 사용한다.
- 인수 순서를 따르고, 각 패턴 안에서는 파일명 오름차순으로 처리한다. 경로나 패턴이 겹치면 해당 파일은 지정된 횟수만큼 읽는다.
- 패턴에 일치하는 파일이 없으면 분할을 시작하기 전에 오류로 종료한다.

로그의 `[C:UUID]` 또는 `[H:UUID]`에서 UUID를 파일명으로 쓰려면 UUID만 캡처한다. PowerShell에서 정규식의 여닫는 대괄호 앞에는 백슬래시를 한 개씩 쓴다.

```powershell
java -jar file-spliter/target/file-spliter.jar split "\[[CH]:([0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12})\]" output 'session*.log'
```

### 분석 및 분류 (`analyze`)

```powershell
java -jar file-spliter/target/file-spliter.jar analyze '<정규식>' '<분할 폴더>' '<결과 폴더>'
java -jar file-spliter/target/file-spliter.jar analyze 'ERROR|Exception' output classified
```

예제는 `output` 바로 아래의 일반 파일을 UTF-8로 읽고, 줄에 `ERROR` 또는 `Exception`이 한 번이라도 나오면 파일 전체를 같은 이름으로 `classified`에 복사한다.

- 입력 폴더는 존재해야 하며, 결과 폴더는 필요할 때 생성한다. 두 폴더는 서로 달라야 한다.
- 하위 폴더와 심볼릭 링크는 건너뛴다. 정규식은 한 줄 안에서 검색하며, 파일 시작의 UTF-8 BOM은 검색할 때 건너뛴다. 복사본은 BOM과 줄바꿈을 포함한 원본 바이트를 보존한다.
- 같은 이름의 결과가 이미 있으면 오류로 종료한다. 그 전에 복사한 파일은 남는다.

종료 코드는 정상 처리 또는 도움말이 `0`, 파일 입출력 오류가 `1`, 잘못된 명령·인수·정규식·출력 키가 `2`다.
