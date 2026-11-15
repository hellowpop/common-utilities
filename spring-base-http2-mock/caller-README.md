# Request Caller 사용법

Request Caller는 `spring-base-http2-mock`의 내장 기능입니다. Java 17+, Spring Boot 3.5.16, Netty와 JEXL을 사용합니다. 독립 caller 프로젝트와 library JAR 의존성은 제거했습니다.

## 빌드와 실행

저장소 루트에서 실행합니다.

```powershell
./spring-base-http2-mock/scripts/Build-WithMockServers.ps1 -RunTests
Set-Location spring-base-http2-mock
java -jar target/spring-mock.jar
```

[caller 콘솔](http://localhost:8080/caller/) 또는 `https://localhost:8443/caller/`를 엽니다. 기존 mock 콘솔은 `/`입니다. 상세 서버 설정은 [README](README.md)를 참고합니다. 선행 모듈 설치 후에는 `mvn -f spring-base-http2-mock/pom.xml -DskipTests=false verify`로 모든 Java 테스트를 수행할 수 있습니다. `mvn install`은 기본적으로 테스트를 생략합니다.

DB·본문·로그·journal은 실행 작업 디렉터리의 `data/caller/`에 저장합니다. 기존 caller 데이터는 이 프로젝트의 `data/caller/`로 복사했습니다. 데이터 경로를 절대 경로로 지정하려면 `--caller.data-directory=...`를 사용하세요. UI를 닫아도 실행은 계속되며 취소 버튼/API로 중단합니다.

`package`는 Spring 실행 JAR과 sources JAR을 생성하고 모듈 루트의 모든 Markdown을 `META-INF/docs/spring-base-http2-mock/`에 포함합니다. caller 전용 POM·실행 JAR·8085 서버는 사용하지 않습니다.

## 화면 사용

데스크톱 화면은 좌측 메뉴와 우측 본문을 독립적으로 스크롤합니다. 상세페이지를 아래로 내려도 좌측 시나리오 메뉴는 같은 위치에 유지되며, 메뉴가 길면 메뉴 영역 안에서 스크롤할 수 있습니다. 740px 이하 화면은 기존 세로 배치를 사용합니다.

본문은 최대 너비 제한 없이 좌측 메뉴를 제외한 브라우저의 가용 너비 전체를 사용합니다. 창 크기를 조절하면 본문과 내부 패널도 함께 확장·축소됩니다.

1. 새 시나리오에서 제목, base URL, worker 수, ramp-up과 본문 보관 정책을 설정합니다. **실행 기준**은 **반복 횟수 / 실행 시간** 중 선택하며 선택하지 않은 입력은 비활성화됩니다. 실행 시간은 초 단위입니다.
2. **요청 Path 목록** 그리드에서 등록된 순서·이름·메서드·base URL·path·payload 조합 수를 확인합니다. 그리드 하단 **추가** 버튼으로 입력 레이어를 열고, 기존 행 또는 **조회 / 편집** 버튼을 누르면 동일한 레이아웃에서 등록 내용을 조회·수정할 수 있습니다. 레이어는 요청 정보, 다중 헤더, payload 복수 선택·숫자 배열, 성공 상태 범위와 path/request 전후 스크립트를 제공합니다. 추가/적용 후 그리드·전문보기·예상 요청 수가 함께 갱신되며 취소·닫기·Esc는 원본을 보존합니다.
3. 편집창 하단 **전문보기**로 전체 JSON도 편집할 수 있습니다. 시나리오/worker hook과 로그 등 전체 설정을 확인하고 **JSON 정렬**·**적용**으로 반영합니다. 괄호·키·값을 색상으로 구분하고, 잘못된 JSON은 적용하지 않습니다. 적용은 서버 저장과 별개이며 Ctrl/⌘+Enter로 적용할 수도 있습니다. JSON에서 paths를 수정한 뒤 적용하면 그리드도 다시 표시됩니다.
4. 저장 → 저장본 검증 → 실행을 선택합니다. 실행 버튼은 현재 편집 내용을 저장한 뒤 새 실행을 만듭니다.
5. 실행 상세에서 완료/성공/실패/생략 수와 worker를 확인합니다. 상태 코드·오류, 지연·처리량, Path별 결과는 grid table로 표시합니다. **단위 요청 조회**를 펼치고 응답코드·Worker·최소/최대 소요시간(ms) 조건으로 **조회**한 뒤, 요청 행을 클릭하면 상세 및 보관된 본문 다운로드를 표시합니다. 조건은 AND로 적용하고 시간 경계값을 포함하며 빈 조건은 전체를 조회합니다. 다른 실행을 선택하면 조회 조건과 결과를 초기화합니다.
6. 실행 이력은 실행 당시 제목과 시작 시간 범위·상태로 검색합니다. 입력은 브라우저 현지 시간, 표시 시간은 Asia/Seoul입니다. 시간 범위는 시작 포함, 종료 미포함입니다.
7. 종료 실행은 외부 리소스 없는 HTML 보고서를 다운로드할 수 있습니다. **상태 코드 / 오류 분포**는 범주별 건수를 꺾은선 그래프로 표시합니다(시간 추이가 아닌 범주별 집계). **지연 시간**은 표본 수·최소·평균·최대·p50/p95/p99(ms), **Path별 결과**는 이름·ID·메서드·요청 path·완료/성공/실패, **Worker timeline**은 상태·시작/종료(Asia/Seoul)·수행 시간(ms)·완료 요청을 테이블로 표시합니다. 데이터가 없으면 빈 집계를 안내합니다. 보고서에는 본문·스크립트·header를 넣지 않아 자격 증명 노출을 줄입니다. 상세 이력/API에는 실제 header가 있으므로 데이터 디렉터리와 콘솔 접근을 관리하세요.

### Path 복사

기존 path의 **조회 / 편집 → 복사**를 누르면 현재 폼의 입력값을 새 path 추가 창으로 옮깁니다. 새 ID와 `(복사)` 이름을 사용하고 헤더·payload 중복 조합·스크립트·지연 설정을 독립적으로 보존합니다. **추가**를 누른 뒤 시나리오를 저장해야 반영됩니다. 취소하면 원본과 목록은 유지됩니다. path는 시나리오당 최대 100개입니다.

### YAML 전문 다운로드와 신규 추가

- 편집창 하단 **전문 다운로드**를 누르면 현재 입력값을 검증해 `scenario.yml`로 저장합니다. 서버 저장 전 수정 내용도 포함하며 시나리오 ID/version은 제외합니다. path ID, 실행 기준·시간·반복 수, 지연·헤더·payload·스크립트·로그 설정은 보존합니다.
- 좌측 **YAML로 시나리오 추가**에서 `.yml`/`.yaml` 파일을 선택하거나 전문을 붙여넣고 **불러오기**를 누릅니다. 검증 후 새 시나리오 편집창에 반영되며 **시나리오 저장**을 눌러 신규 등록합니다. 기존 시나리오를 덮어쓰지 않습니다.
- YAML은 단일 객체이며 최대 1048576자입니다(파일 선택은 최대 4 MiB). 중복 key, alias, 임의 Java tag, 여러 YAML 문서와 잘못된 정수/설정은 거부합니다. 오류 시 현재 편집 내용을 유지합니다.
- 선택 seed는 Java signed 64-bit 범위이며 브라우저 정밀도 손실을 막기 위해 JSON/YAML 출력에서 문자열로 보존합니다. 숫자 또는 정수 문자열 입력을 모두 받습니다.
- YAML에는 재사용에 필요한 실제 헤더 값과 스크립트가 포함됩니다. 파일을 공유하기 전에 자격 증명 등 입력 내용을 확인하세요.

JSON 구문 색상 처리와 path 편집의 회귀 테스트는 Node.js가 설치된 개발 환경에서 `node --test spring-base-http2-mock/src/test/js/*.test.cjs`로 실행할 수 있습니다. 앱 실행에는 Node.js가 필요하지 않습니다.

## 시나리오 예제

실제 편집 형식은 `/api/example`에서 조회할 수 있습니다. 생략한 필드는 기본값을 사용합니다.

```json
{
  "title": "orders 조합 테스트",
  "endpointBaseUrl": "http://127.0.0.1:28080/api",
  "maxWorkers": 4,
  "rampUpSeconds": 3,
  "loopCount": 2,
  "executionMode": "LOOPS",
  "durationSeconds": 60,
  "requestTimeoutMillis": 30000,
  "runTimeoutMillis": 900000,
  "selectionMode": "SHUFFLED_ALL",
  "seed": 12345,
  "bodyRetention": "FAILURES",
  "logging": {"enabled": true, "maxFileSize": 10485760, "maxFiles": 5, "gzip": true},
  "scenarioHooks": {"pre": "vars.put('region', 'local');", "post": "run.id;"},
  "workerHooks": {"pre": "worker.vars.put('token', 'test');", "post": ""},
  "paths": [{
    "name": "orders",
    "requestPath": "/orders?test=true",
    "method": "POST",
    "delay": {"mode": "NONE", "fixedMillis": 0, "minMillis": 0, "maxMillis": 0},
    "headers": {"X-Caller": ["mock"]},
    "payload": {
      "chunked": [false, true],
      "contentType": ["APPLICATION_JSON", "TEXT_PLAIN"],
      "contentEncoding": ["IDENTITY", "GZIP"],
      "contentLength": [4096, 8192],
      "blockSize": [128]
    },
    "pathHooks": {"pre": "", "post": ""},
    "requestHooks": {
      "pre": "exchange.request.setHeader('X-Token', worker.vars.get('token'));",
      "post": "exchange.assertSuccess(exchange.response.status == 200, 'expected 200');"
    }
  }]
}
```

예제는 path당 16개 조합이며 4 workers × 2 loops × 16 = 128 요청입니다. `requestUrl`을 path에 지정하면 scenario base를 대체합니다. base의 prefix를 유지하므로 `http://host/api` + `/orders`는 `/api/orders`가 됩니다. base query/fragment/user-info와 path의 scheme/authority/dot segment는 거부합니다.

동일 scenario의 동시 실행은 409, 전역 수용 한도 초과는 429입니다. 실행 중 편집은 새 정의 버전을 만들며 기존 실행 snapshot은 바뀌지 않습니다. 실행 중 삭제는 거부하고 완료 후 삭제해도 실행 이력은 보관 기간까지 남습니다.

### Payload와 요청 의미

- 다섯 속성은 값이 하나여도 배열입니다. 모든 조합을 방문하며 중복 값도 별도 요청입니다. `blockSize`가 가장 빨리 바뀝니다.
- 기본 `SHUFFLED_ALL`은 seed로 전체 조합의 방문 순서를 섞고 `ORDERED_ALL`은 입력 순서를 따릅니다. 생성기 본문 자체는 일정한 영숫자 패턴이며 난수가 아닙니다. seed는 결과에 남습니다.
- ContentType: `TEXT_PLAIN`, `APPLICATION_JSON`, `APPLICATION_XML`, `TEXT_HTML`, `APPLICATION_FORM_URLENCODED`, `APPLICATION_OCTET_STREAM`, `MULTIPART_FORM_DATA`.
- ContentEncoding: `IDENTITY`, `GZIP`, `DEFLATE`, `BR`, `ZSTD`, `SNAPPY`. native codec은 OS/아키텍처 지원이 필요합니다.
- contentLength는 압축 전 원문 크기이며 실제 HTTP Content-Length는 압축 후 크기입니다. blockSize는 생성 데이터 값의 크기이며 HTTP chunk 크기가 아닙니다.
- GET/HEAD는 `contentLength:[0]`, `contentEncoding:["IDENTITY"]`, `chunked:[false]`만 허용합니다. 기본 메서드는 POST입니다.
- Host·Content-Length·Transfer-Encoding·Content-Type·Content-Encoding·Connection·Upgrade·Expect는 엔진이 관리합니다. script 수정 후에도 검증합니다.
- 자동 redirect/retry/압축 해제를 하지 않습니다. 요청별 연결을 열고 닫으므로 keep-alive 성능 측정 도구로 해석하지 마세요. 응답 본문은 wire bytes로 보관합니다.
- HTTPS는 JVM 기본 truststore와 호스트명 검증을 사용합니다. 별도 인증서는 JVM의 `javax.net.ssl.trustStore` / `javax.net.ssl.trustStorePassword` 설정으로 지정합니다.

## Worker와 전·후처리

### 실행 기준 선택

- `executionMode="LOOPS"`(기본): 각 worker가 전체 path/조합을 `loopCount`번 수행합니다. `durationSeconds`는 무시합니다.
- `executionMode="DURATION"`: `durationSeconds` 동안 전체 path/조합을 반복하며 `loopCount`는 무시합니다. 기존 설정에 새 필드가 없으면 LOOPS / 60초로 읽으므로 기존 실행 방식은 유지됩니다.
- 시간 측정은 **scenario.pre가 성공한 직후** 시작하며 ramp-up·요청·path 지연을 모두 포함합니다. 시간이 끝나면 새 요청 scope를 시작하지 않고 진행 중인 요청과 후처리를 마무리하므로 실제 종료는 설정 시간보다 늦을 수 있습니다. 시간 만료 자체는 정상 COMPLETED입니다.
- 기존 `runTimeoutMillis`는 별도의 강제 안전 제한입니다. duration은 1..86400초 정수이며 `durationSeconds × 1000 < runTimeoutMillis`, `rampUpSeconds < durationSeconds`여야 합니다. 기본 전체 timeout은 15분이므로 더 긴 시간 실행은 전문보기에서 runTimeoutMillis도 늘려야 합니다. 요청은 여전히 requestTimeoutMillis와 전체 timeout을 적용받습니다.
- 시간 기준은 총 요청 수를 미리 정할 수 없어 API의 `planned`/`remaining`이 null이며 화면·리포트는 **시간 기준**으로 표시합니다. 진행률은 실행 시간 기준, 완료/성공/실패는 실제 요청 기준입니다. skipped는 0이며 반복 모드의 planned=completed+skipped 식은 적용하지 않습니다.
- 시간 기준도 실행당 100000회 요청 시작 한도를 적용합니다. 시간 만료 전에 한도에 도달하면 FAILED / REQUEST_LIMIT으로 중단합니다. 시간 도달 후의 새 요청은 시작하지 않으며 사용자 취소와 오류는 정상 시간 만료와 구분됩니다.

```json
{
  "executionMode": "DURATION",
  "durationSeconds": 60,
  "loopCount": 7
}
```

위 예제에서 loopCount=7은 보관만 하며 실행 종료 기준에 사용하지 않습니다. UI에서 다시 반복 횟수로 전환하면 해당 값을 사용할 수 있습니다.

### Path 요청 사이 간격

path 추가/편집 레이어의 **요청 사이 간격**에서 `NONE`(기본), `FIXED`, `RANDOM` 중 선택합니다. 단위는 ms이며 활성 값은 0..600000 정수입니다. RANDOM은 최소값 ≤ 최대값이고 양 끝 값을 모두 포함합니다. 사용하지 않는 모드의 숫자는 보관만 합니다.

```json
{
  "delay": {"mode": "FIXED", "fixedMillis": 100, "minMillis": 0, "maxMillis": 0}
}
```

범위 무작위는 `"delay":{"mode":"RANDOM","fixedMillis":0,"minMillis":100,"maxMillis":500}`처럼 지정합니다. 소수 시간/간격은 API에서도 정수로 잘라서 수용하지 않고 입력 오류로 거부합니다.

- 각 worker가 **동일 path의 이전 요청 완료(후처리·본문 정리 포함) → 다음 요청 시작** 사이 간격을 보장합니다. 서로 다른 worker의 요청을 직렬화하지 않습니다.
- 첫 요청은 즉시 시작하고 마지막 요청 뒤에는 별도로 기다리지 않습니다. 다음 loop에서 동일 path를 재방문할 때도 적용합니다. 중간에 다른 path를 실행한 시간은 이미 지난 간격으로 계산하여 남은 시간만 기다립니다.
- RANDOM은 worker/path별 seed 기반 난수를 사용하여 요청 간격마다 한 번 선택합니다. 실행 seed가 같으면 간격 선택 순서가 재현되지만 OS 스케줄링·네트워크 차이로 실제 도착 시각까지 같다는 의미는 아닙니다.
- 대기는 요청 timeout 밖에서 수행하지만 duration/전체 timeout에는 포함됩니다. 사용자 취소·앱 종료·전체 timeout 시 즉시 interrupt하며 시간 기준 실행은 남은 시간까지만 기다립니다.

첫 worker는 scenario pre 직후 시작하고 마지막 worker는 ramp-up 초에 시작합니다. worker=1 또는 ramp-up=0이면 즉시 시작합니다. worker당 한 번에 한 요청을 수행하고 일찍 끝난 worker를 다시 만들지 않습니다.

```text
scenario.pre
  worker.pre
    각 loop → 정의 순서의 path
      path.pre
        각 payload 조합
          기본 본문 생성 → request.pre → 최종 검증/본문 재인코딩 → HTTP → request.post
      path.post
  worker.post
scenario.post
결과 flush → 로그/자원 정리 → 최종 상태
```

진입한 scope의 post는 pre 실패·요청 실패·취소에도 한 번 시도합니다. path pre 실패는 해당 path iteration을 생략하고 다음 path를 실행합니다. request pre/HTTP/post 실패는 해당 요청을 실패로 기록하고 다음 조합으로 진행합니다. worker/scenario hook 실패와 저장·로그·자원 오류는 실행을 중단합니다.

COMPLETED는 실행 엔진의 완료입니다. 실패 요청/path hook이 있으면 `outcome=WITH_FAILURES`입니다. FAILED는 실행 자체의 실패, CANCELLED는 사용자/앱 종료 취소, INTERRUPTED는 이전 프로세스에서 정리가 끝나지 않은 실행입니다. 반복 횟수 기준은 `planned=completed+skipped`가 성립하며, 모든 모드에서 `completed=succeeded+failed`가 성립합니다.

### JEXL 컨텍스트

| 이름 | 읽기/수정 계약 |
| --- | --- |
| scenario | id/title/endpointBaseUrl, 읽기 전용 |
| run | id/seed/cancelled, 읽기 전용 |
| vars | scenario pre의 초기 변수. 각 worker.vars로 깊은 복사 |
| worker | index/threadName/threadId, 해당 worker의 vars map |
| path | id/name/requestPath, 읽기 전용 |
| exchange | request scope의 id/request/response/success/error/reason |
| scope | level/phase/failed/cancelled |
| workerVars | scenario post에서 worker 최종 vars 목록 |

```jexl
// worker.pre
worker.vars.put('token', 'local-token');

// request.pre
exchange.request.setHeader('X-Test', worker.vars.get('token'));
exchange.request.uri = 'http://localhost:8080/api/test';
exchange.request.body = 'replacement body';

// request.post
exchange.assertSuccess(exchange.response.status == 200, 'expected HTTP 200');
exchange.response.headers.put('X-Analysis', 'checked');
```

request header는 `headers.put(name,value)` / `setHeader(name,value)`로 수정합니다. 값은 String 또는 서버에서 주입한 다중 String 목록입니다. body는 UTF-8 String이며 override를 원문으로 취급하고 지정 encoding으로 다시 압축합니다. 전송 오류/HTTP 실패를 script가 성공으로 덮어쓰지 못합니다. response header/body 수정은 **분석용 view**이며 원본 wire 기록을 덮어쓰지 않습니다.

기본 생성과 응답 본문 중 **각각 최대 384KiB의 IDENTITY/GZIP/DEFLATE 해석 결과**만 자동으로 script에 노출합니다. 초과/다른 encoding이면 `body=null`, `bodyAvailable=false`, `bodySize`를 제공합니다. 큰 본문은 보관 artifact에서 확인합니다. script override는 최대 1MiB이며 컨텍스트 전체 1MiB 제한도 적용됩니다. JSON 호환 map/문자열/숫자/목록만 사용하고 map은 256 key, 깊이는 8입니다.

JEXL은 운영자가 작성한 제한된 script를 대상으로 합니다. loops/new/class/reflection/pragma/template와 임의 메서드는 차단합니다. map/list literal도 비활성화되어 기존 map과 vars를 사용합니다. `get`, `put`, `setHeader`, `assertSuccess`만 허용합니다. 기본 script timeout은 1초이고 수정은 성공한 script만 반영합니다. 취소에 응답하지 않는 평가가 발견되면 엔진을 degraded로 표시하고 신규 실행을 거부합니다.

## 결과·본문·로그·복구

`bodyRetention`은 NONE / FAILURES(기본) / ALL입니다. pre/post까지 임시 본문을 유지한 뒤 보관 정책에 맞는 요청·응답 본문을 artifact로 저장합니다. transport 실패 시 부분 응답은 폐기하고 오류를 기록합니다. 수신 성공 이후 보관한 wire body에는 SHA-256과 크기가 남습니다.

```text
data/db/                       H2 데이터
data/temp/                     전송/생성 임시 본문
data/bodies/<runId>/            보관 본문
data/logs/<runId>/control.log   control 로그
data/logs/<runId>/worker-0001.log
data/logs/<runId>/*.log.gz      rolling archive
data/journal/                  DB 장애 시 checksummed 복구 기록
```

로그 활성화 시 control과 실제 worker별 파일을 만듭니다. `maxFileSize`는 byte, `maxFiles`는 활성 파일을 포함한 stream별 최대 파일 수입니다. `maxFiles=1`이면 archive 없이 활성 파일만 유지합니다. gzip은 회전된 파일에 적용하며 개별 record는 16KiB로 제한합니다. 로그에는 전체 body/header를 기록하지 않습니다. 로그 조회는 최신 64KiB tail과 파일 목록을 제공합니다.

DB 저장 실패 시 신규 요청을 중단하고 미저장 결과를 bounded journal에 기록합니다. 앱 재시작 시 멱등 재적재한 뒤 미종료 실행을 INTERRUPTED로 표시합니다. 프로세스 강제 종료 중 잘린 마지막 journal 줄은 격리하고 정상 prefix를 복구하며 해당 실행은 `LOST_RESULTS/statisticsComplete=false`를 유지합니다. 완성된 내부 record의 checksum 손상은 임의로 건너뛰지 않고 기동 오류로 알립니다. DB와 journal이 함께 실패하면 결과 영속성을 보장할 수 없습니다.

기본 보관 기간은 7일입니다. 종료 시각이 보관 기간을 지난 실행의 이력/본문/로그를 시작 시와 매시간 정리하며 실행 중인 run은 유지합니다. DB tombstone을 남겨 파일 삭제 실패를 재시도합니다. 시작 시 journal 복구 후 임시 파일과 참조 없는 body를 정리합니다.

## REST API

상태 변경 API에는 세션 cookie와 `/api/csrf`가 반환한 `X-CSRF-Token`이 필요합니다. 브라우저 UI는 자동 처리합니다. Origin이 있으면 동일 origin인지 검사합니다. 기본 localhost bind이며 공용 네트워크 노출 시 인증된 reverse proxy가 필요합니다.

```powershell
$base = 'http://127.0.0.1:8080/api'
$csrf = Invoke-RestMethod "$base/csrf" -SessionVariable callerSession
$headers = @{ 'X-CSRF-Token' = $csrf.token }
$example = Invoke-RestMethod "$base/example" -WebSession $callerSession
$example.endpointBaseUrl = 'http://127.0.0.1:28080'
$created = Invoke-RestMethod "$base/scenarios" -Method Post -WebSession $callerSession -Headers $headers -ContentType 'application/json' -Body ($example | ConvertTo-Json -Depth 30)
$run = Invoke-RestMethod "$base/scenarios/$($created.id)/runs" -Method Post -WebSession $callerSession -Headers $headers
Invoke-RestMethod "$base/runs/$($run.id)" -WebSession $callerSession
```

| API (prefix `/api`) | 용도 |
| --- | --- |
| GET /csrf, /example, /status | token/기본 정의/엔진 상태 |
| GET/POST /scenarios | 제목 검색·신규 저장 |
| POST /scenarios/export | JSON 전문을 검증하고 application/yaml 첨부파일 반환, 저장 없음 |
| POST /scenarios/import | YAML 원문을 검증하고 id=null/version=0인 신규 JSON draft 반환, 저장 없음 |
| GET/PUT/DELETE /scenarios/{id} | 조회·version 기반 수정·soft delete |
| POST /scenarios/{id}/validate | 저장본 검증, 전송 안 함 |
| POST /scenarios/{id}/runs | 실행, 202. 선택 Idempotency-Key는 같은 scenario에서 재사용 시 기존 실행 반환 |
| GET /runs?title=&from=&to=&status=&scenarioId= | 제목/시간/상태 검색 |
| GET /runs/{id}, /runs/{id}/events | 집계 조회·1초 snapshot SSE |
| POST /runs/{id}/cancel | 취소. 종료 실행에는 상태 변경 없음 |
| DELETE /runs/{id} | 종료된 이력 및 요청·본문·로그·보고서·복구 journal 삭제 (CSRF 필요, 진행 중 409) |
| GET /runs/{id}/requests?pathId=&success=&status=&workerIndex=&minElapsedMillis=&maxElapsedMillis= | 조건 검색 후 요청 결과 pagination (소요시간 ms, 양쪽 경계 포함) |
| GET /runs/{id}/requests/{requestId} | 단위 요청 상세 |
| GET /runs/{id}/artifacts/{artifactId} | 해당 실행 소유 본문 다운로드 |
| GET /runs/{id}/logs?workerIndex= | control 또는 worker 로그 |
| GET /runs/{id}/report | 종료 실행의 offline HTML |

pagination은 page=0, size=20 기본이며 size 최대 100입니다. 시간 API는 UTC 또는 offset 포함 ISO-8601을 받습니다. 예: `from=2026-11-12T00:00:00Z`. SSE 구독 종료는 실행 취소를 의미하지 않습니다.

## 운영 설정과 한도

외부 application.yml 또는 `--caller.data-directory=...`, `--server.port=...` 등 Boot 인수로 변경합니다. H2 대신 PostgreSQL을 사용하려면 `spring.datasource.url/username/password`를 설정합니다. Liquibase가 schema를 관리하고 실제 저장은 JDBC를 사용합니다.

| 항목 | 기본값 |
| --- | --- |
| Spring HTTP / HTTPS port | 8080 / 8443 |
| caller.max-concurrent-runs / max-total-workers | 4 / 64 |
| caller.max-response-bytes | 67108864 (64MiB) |
| caller.script-timeout-millis | 1000 |
| caller.run-body-budget | 1073741824 (1GiB) |
| caller.retention-days | 7 |
| scenario workers / loop / ramp-up | 4 / 1 / 0초 |
| scenario executionMode / durationSeconds | LOOPS / 60초 (모드별 종료 기준 선택) |
| path delay mode / 고정 / 무작위 범위 | NONE / 0ms / 0..0ms, 활성 값 최대 600000ms |
| scenario request/run timeout | 30초 / 15분 |
| scenario worker 최대 / path 최대 / origin 최대 | 32 / 100 / 16 |
| 계획 요청 수 / 원문 크기 상한 | 100000 / 요청당 64MiB |
| BodyStore 메모리 → 파일 전환 | 256KiB |
| 앱 전체 영속 body 예산 | 4GiB |
| journal 예산 | run당 64MiB / 앱 전체 256MiB |
| 결과 queue / enqueue timeout | run당 1024건 / 5초 |
| hook·path iteration 상세 보존 | 실행당 각각 1000개 이후 truncation 표시 |

임시 payload 생성 전 디스크 여유를 확인하고 압축/원문을 streaming 처리합니다. 생성기 내부 압축 임시 파일은 JVM `java.io.tmpdir`를 사용하므로 필요하면 `-Djava.io.tmpdir=...`로 전용 디스크를 지정하세요. OS quota를 대체하는 정밀한 임시 디스크 총량 제어는 제공하지 않습니다. DNS는 제한된 별도 실행기에서 수행하지만 OS 조회가 interrupt를 무시하면 해당 조회는 OS가 반환할 때까지 남을 수 있습니다.

HTTP/2, keep-alive 부하, 분산 실행, 자동 재실행, 예약 실행, 본문 자체 난수 생성은 현재 범위에 포함하지 않습니다. 구성·설계 판단과 검증 기록은 [project.md](caller-project.md), 작업 단계는 [implementation-plan.md](caller-implementation-plan.md)에 정리합니다.
