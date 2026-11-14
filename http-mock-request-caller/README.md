# HTTP Mock Request Caller

HTTP 요청 시나리오를 저장하고 여러 worker로 payload 조합을 실행하는 독립 웹 애플리케이션입니다. Java 17+, Spring Boot 4.1.2, Netty 4.1.139.Final, JEXL 3.7.1을 사용합니다. HTTP/1.1과 HTTPS를 지원하며 실제 대상 서버의 응답을 수집합니다.

## 빌드와 실행

저장소 루트에서 실행합니다. JDK 17과 Maven 3.6.3 이상이 필요합니다.

```powershell
$env:JAVA_HOME = 'D:/01.app/java/jdk-17.0.10' # 설치 경로에 맞게 변경
mvn -f http-payload-generator/pom.xml '-Dmaven.test.skip=true' install
mvn -f http-mock-request-caller/pom.xml '-DskipTests=false' clean verify
mvn -f http-mock-request-caller/pom.xml '-DskipTests=true' install
Set-Location http-mock-request-caller
& "$env:JAVA_HOME/bin/java.exe" -jar target/http-mock-request-caller-1.0.0-SNAPSHOT.jar
```

또는 `./http-mock-request-caller/scripts/Build.ps1 -RunTests`로 선행 모듈 검증·설치와 앱 빌드를 수행합니다. `-RunTests`를 생략하면 테스트 없이 설치합니다.

[로컬 콘솔](http://127.0.0.1:8085/)을 엽니다. 종료는 실행 콘솔에서 Ctrl+C입니다. UI 연결을 닫아도 실행은 계속되며 취소 버튼/API로 중단합니다. 기본 DB·본문·로그·복구 journal은 **실행 작업 디렉터리의 `data/`**에 저장합니다. 같은 H2 파일에 두 앱 프로세스를 동시에 연결하지 마세요.

기본 package는 executable JAR 및 `-sources.jar`를 생성·첨부하며 모든 모듈 루트 `*.md`를 `META-INF/docs/http-mock-request-caller/`에 포함합니다. 기본 `skipTests=true`로 install에서 테스트가 실행되지 않습니다. 테스트는 위와 같이 `-DskipTests=false`로 명시 활성화합니다.

## 화면 사용

데스크톱 화면은 좌측 메뉴와 우측 본문을 독립적으로 스크롤합니다. 상세페이지를 아래로 내려도 좌측 시나리오 메뉴는 같은 위치에 유지되며, 메뉴가 길면 메뉴 영역 안에서 스크롤할 수 있습니다. 740px 이하 화면은 기존 세로 배치를 사용합니다.

본문은 최대 너비 제한 없이 좌측 메뉴를 제외한 브라우저의 가용 너비 전체를 사용합니다. 창 크기를 조절하면 본문과 내부 패널도 함께 확장·축소됩니다.

1. 새 시나리오에서 제목, base URL, worker 수, ramp-up, 반복 수와 본문 보관 정책을 설정합니다.
2. 편집창 하단의 **전문보기**를 누르면 JSON 편집 레이어가 열립니다. `paths`에 요청을 추가하고 URL·메서드·headers·payload와 네 레벨 hook을 편집합니다. 괄호는 보라색, 키는 파란색, 문자열 값은 녹색, 숫자는 갈색, boolean/null은 자주색으로 구분합니다.
3. 레이어의 **JSON 정렬**로 들여쓰기를 정리하고 **적용**을 눌러 편집창에 반영합니다. 취소·닫기·Esc는 변경을 반영하지 않습니다. JSON 문법이 잘못되면 레이어 안에 오류를 표시하며 적용하지 않습니다. 적용은 서버 저장과 별개입니다. Ctrl/⌘+Enter로 적용할 수도 있습니다.
4. 저장 → 저장본 검증 → 실행을 선택합니다. 실행 버튼은 현재 편집 내용을 저장한 뒤 새 실행을 만듭니다.
5. 실행 상세에서 완료/성공/실패/생략 수, worker, 상태 코드·오류, 지연과 path별 결과를 확인합니다. 단위 요청을 클릭하면 상세 및 보관된 본문 다운로드를 표시합니다.
6. 실행 이력은 실행 당시 제목과 시작 시간 범위·상태로 검색합니다. 입력은 브라우저 현지 시간, 표시 시간은 Asia/Seoul입니다. 시간 범위는 시작 포함, 종료 미포함입니다.
7. 종료 실행은 외부 리소스 없는 HTML 보고서를 다운로드할 수 있습니다. 보고서에는 본문·스크립트·header를 넣지 않아 자격 증명 노출을 줄입니다. 상세 이력/API에는 실제 header가 있으므로 데이터 디렉터리와 콘솔 접근을 관리하세요.

JSON 구문 색상 처리의 회귀 테스트는 Node.js가 설치된 개발 환경에서 `node --test http-mock-request-caller/src/test/js/json-view.test.cjs`로 실행할 수 있습니다. 앱 실행에는 Node.js가 필요하지 않습니다.

## 시나리오 예제

실제 편집 형식은 `/api/example`에서 조회할 수 있습니다. 생략한 필드는 기본값을 사용합니다.

```json
{
  "title": "orders 조합 테스트",
  "endpointBaseUrl": "http://127.0.0.1:28080/api",
  "maxWorkers": 4,
  "rampUpSeconds": 3,
  "loopCount": 2,
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

COMPLETED는 실행 엔진의 완료입니다. 실패 요청/path hook이 있으면 `outcome=WITH_FAILURES`입니다. FAILED는 실행 자체의 실패, CANCELLED는 사용자/앱 종료 취소, INTERRUPTED는 이전 프로세스에서 정리가 끝나지 않은 실행입니다. `planned=completed+skipped`, `completed=succeeded+failed`가 정상 종료 시 성립합니다.

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
$base = 'http://127.0.0.1:8085/api'
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
| GET/PUT/DELETE /scenarios/{id} | 조회·version 기반 수정·soft delete |
| POST /scenarios/{id}/validate | 저장본 검증, 전송 안 함 |
| POST /scenarios/{id}/runs | 실행, 202. 선택 Idempotency-Key는 같은 scenario에서 재사용 시 기존 실행 반환 |
| GET /runs?title=&from=&to=&status=&scenarioId= | 제목/시간/상태 검색 |
| GET /runs/{id}, /runs/{id}/events | 집계 조회·1초 snapshot SSE |
| POST /runs/{id}/cancel | 취소. 종료 실행에는 상태 변경 없음 |
| GET /runs/{id}/requests?pathId=&success= | 요청 결과 pagination |
| GET /runs/{id}/requests/{requestId} | 단위 요청 상세 |
| GET /runs/{id}/artifacts/{artifactId} | 해당 실행 소유 본문 다운로드 |
| GET /runs/{id}/logs?workerIndex= | control 또는 worker 로그 |
| GET /runs/{id}/report | 종료 실행의 offline HTML |

pagination은 page=0, size=20 기본이며 size 최대 100입니다. 시간 API는 UTC 또는 offset 포함 ISO-8601을 받습니다. 예: `from=2026-11-12T00:00:00Z`. SSE 구독 종료는 실행 취소를 의미하지 않습니다.

## 운영 설정과 한도

외부 application.yml 또는 `--caller.data-directory=...`, `--server.port=...` 등 Boot 인수로 변경합니다. H2 대신 PostgreSQL을 사용하려면 `spring.datasource.url/username/password`를 설정합니다. Liquibase가 schema를 관리하고 실제 저장은 JDBC를 사용합니다.

| 항목 | 기본값 |
| --- | --- |
| server.address / port | 127.0.0.1 / 8085 |
| caller.max-concurrent-runs / max-total-workers | 4 / 64 |
| caller.max-response-bytes | 67108864 (64MiB) |
| caller.script-timeout-millis | 1000 |
| caller.run-body-budget | 1073741824 (1GiB) |
| caller.retention-days | 7 |
| scenario workers / loop / ramp-up | 4 / 1 / 0초 |
| scenario request/run timeout | 30초 / 15분 |
| scenario worker 최대 / path 최대 / origin 최대 | 32 / 100 / 16 |
| 계획 요청 수 / 원문 크기 상한 | 100000 / 요청당 64MiB |
| BodyStore 메모리 → 파일 전환 | 256KiB |
| 앱 전체 영속 body 예산 | 4GiB |
| journal 예산 | run당 64MiB / 앱 전체 256MiB |
| 결과 queue / enqueue timeout | run당 1024건 / 5초 |
| hook·path iteration 상세 보존 | 실행당 각각 1000개 이후 truncation 표시 |

임시 payload 생성 전 디스크 여유를 확인하고 압축/원문을 streaming 처리합니다. 생성기 내부 압축 임시 파일은 JVM `java.io.tmpdir`를 사용하므로 필요하면 `-Djava.io.tmpdir=...`로 전용 디스크를 지정하세요. OS quota를 대체하는 정밀한 임시 디스크 총량 제어는 제공하지 않습니다. DNS는 제한된 별도 실행기에서 수행하지만 OS 조회가 interrupt를 무시하면 해당 조회는 OS가 반환할 때까지 남을 수 있습니다.

HTTP/2, keep-alive 부하, 분산 실행, 자동 재실행, 예약 실행, 본문 자체 난수 생성은 현재 범위에 포함하지 않습니다. 구성·설계 판단과 검증 기록은 [project.md](project.md), 작업 단계는 [implementation-plan.md](implementation-plan.md)에 정리합니다.
