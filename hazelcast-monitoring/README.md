# Hazelcast Monitoring

Java 17에서 한 번 실행하고 종료하는 Hazelcast 관측 CLI다. 클러스터 멤버로 참가하지 않고 Java client로 접속한다. 실행 결과는 JPA로 DB에 저장하고 YAML 파일을 생성한다. 저장된 이력은 인터넷과 웹 서버 없이 열리는 단일 HTML 보고서로 내보낸다.

## 빌드

Java 17 이상과 Maven 3.6.3 이상이 필요하다. Java 17로 빌드/실행하는 것을 기준으로 검증한다.

```powershell
$env:JAVA_HOME='D:\01.app\java\jdk-17.0.10'
mvn clean verify
java -jar target/hazelcast-monitoring.jar --help
```

Spring Boot 4.1.2, Hazelcast Community 5.7.0을 사용한다. Spring Boot가 관리하는 Hibernate/JPA, Liquibase, H2/PostgreSQL JDBC 및 SnakeYAML 의존성이 executable JAR에 포함된다. 다른 모듈을 빌드할 필요가 없다.

## install 모드: 일반 Java runtime 생성

원하는 실행 디렉터리로 이동한 뒤 다음 명령을 한 번 실행한다.

```shell
java -jar /path/to/hazelcast-monitoring.jar --install
```

설치 대상은 **현재 작업 디렉터리**다. 명시적 `--install`은 Spring 설정 로딩 전에 실행하며 DB/Hazelcast에 접속하지 않는다.

| 생성 파일 | 용도 |
| --- | --- |
| `runtime/classes/` | 애플리케이션 class와 내장 resource |
| `runtime/lib/` | 일반 classpath로 로딩할 dependency JAR |
| `runtime/manifest.properties` | runtime 파일 목록과 SHA-256 무결성 기록 |
| `application.yml` | 클러스터, DB, 출력 경로, timeout 등 편집용 설정 |
| `collect.bat` / `collect.sh` | 기본 단발 수집 |
| `report.bat` / `report.sh` | HTML 보고서 생성 |
| `init.bat` / `init.sh` | 모니터링 스키마 초기화 |
| `install.bat` / `install.sh` | 설정/실행 파일 설치 또는 누락 파일 재생성 |

`application.yml`에서 `monitor.cluster-id`, `monitor.cluster-name`, `monitor.members`, `spring.datasource` 등을 수정한 뒤 스크립트를 실행한다. 기본 실행은 단발 수집이다.

배포용 executable JAR은 최초 설치 원본이다. install이 `BOOT-INF/classes`와 `BOOT-INF/lib`를 위 구조로 풀며 루트에 executable JAR을 복사하지 않는다. 설치 이후 모든 모드는 **`java -cp ... MainClass`**로 실행한다. 배포 원본 JAR을 이동/제거해도 실행과 `install.bat`/`install.sh` 재설치가 가능하다. `runtime`은 애플리케이션/라이브러리 경로이며 Java 17 이상의 JDK/JRE는 별도 설치한다.

직접 Java 명령으로 실행할 수도 있다. 아래 명령은 설치 디렉터리에서 실행한다.

```bat
java -cp "runtime\classes;runtime\lib\*" com.inzent.apim.utilities.hazelcastmonitoring.MonitorApplication --collect "--spring.config.location=classpath:/application.yml,file:./application.yml"
```

```sh
java -cp "runtime/classes:runtime/lib/*" com.inzent.apim.utilities.hazelcastmonitoring.MonitorApplication --report --spring.config.location=classpath:/application.yml,file:./application.yml
```

### 설치 디렉터리의 Hazelcast 라이브러리 사용

`--install`을 실행하는 **현재 디렉터리 최상위**에 `hazelcast-5.3.6.jar` 또는 `hazelcast-custom.jar`처럼 `hazelcast*.jar`가 있으면, client/core API를 함께 포함한 본체 JAR을 찾아 내장 dependency 대신 사용한다. 이름은 대소문자를 구분하지 않는다. 설치 원본 JAR이 다른 디렉터리에 있어도 검색 기준은 설치 디렉터리다.

```bat
cd /d C:\apps\hzmon
copy C:\libs\hazelcast-5.3.6.jar .
java -jar C:\downloads\hazelcast-monitoring.jar --install
```

이 경우 runtime/lib의 내장 Hazelcast 본체를 제거하고 로컬 JAR을 원래 파일명으로 복사한다. SQL 등 부가 dependency는 유지하고 본체가 중복되지 않게 구성한다. 선택한 파일은 `HAZELCAST_OVERRIDE .../runtime/lib/<파일명>`으로 출력하며 manifest에는 실제 복사본의 SHA-256을 기록한다.

sources/javadoc JAR과 client/core API가 없는 부가 JAR은 대체 후보에서 제외한다. 본체 후보가 여러 개이거나 손상된 JAR, client/core가 분리된 JAR은 종료 코드 5로 거부하며 임의 선택하거나 내장 버전으로 조용히 대체하지 않는다. 후보가 없으면 원본 dependency를 사용한다. 로컬 JAR은 Java 17 및 이 애플리케이션의 API와 대상 cluster에 호환되어야 한다.

기존 runtime은 계속 보호한다. 같은 라이브러리로 재설치할 수 있지만 로컬 JAR을 바꿔 기존 runtime과 달라지면 오류다. 버전 교체는 실행 종료 후 runtime을 백업/이름 변경하고 배포 JAR로 다시 설치한다. 설치 후 `install.bat`/`install.sh`는 선택된 라이브러리를 가진 runtime 자체를 원본으로 사용하므로 루트의 로컬 JAR을 제거해도 재설치할 수 있다.

Windows 명령 프롬프트:

```bat
mkdir C:\apps\hzmon
cd /d C:\apps\hzmon
java -jar C:\downloads\hazelcast-monitoring.jar --install
notepad application.yml
collect.bat
report.bat --monitor.report-file=./output/report.html
init.bat
install.bat
```

Linux:

```sh
mkdir -p "$HOME/hzmon"
cd "$HOME/hzmon"
java -jar /tmp/hazelcast-monitoring.jar --install
vi application.yml
./collect.sh
./report.sh --monitor.report-file=./output/report.html
./init.sh
./install.sh
```

Java 17 이상이 필요하다. Windows 배치는 `JAVA_HOME` 우선, 없으면 PATH의 Java를 사용한다. Linux 셸은 절대 경로의 `JAVA_HOME`이 있으면 우선 사용하고, 없으면 설치를 실행한 JVM의 Java 절대 경로를 사용한다. 상대 JAVA_HOME은 오류(2)다. Windows 배치는 CRLF, Linux 셸은 LF와 POSIX 파일시스템 권한 755로 생성한다. Windows에서 생성한 파일을 Linux로 복사했다면 설치/Java 경로를 새 환경에 맞게 재생성하고 실행 권한을 적용한다.

Windows 배치는 자신의 디렉터리로, Linux 셸은 설치 시 기록한 절대 디렉터리로 이동하여 **그 디렉터리의 application.yml을 필수 로딩**한다. Linux의 Java·classpath·설정 URI도 절대 경로다. 다른 디렉터리에서 호출해도 상대 DB/출력 경로는 설치 디렉터리를 기준으로 한다. 각 파일은 자기 모드 플래그를 지정하므로 YAML의 mode 값보다 우선한다. 다른 모드 플래그를 추가하면 중복 오류로 거부하므로 실행할 모드의 파일을 선택한다. 그 밖의 인수와 Java 종료 코드는 그대로 전달하며 CLI 값이 YAML보다 우선한다. 공백이 있는 인수는 `"--monitor.report-file=./my report.html"`처럼 전체를 인용한다.

재설치는 기존 설정/스크립트를 수정하지 않고 `PRESERVED`로 표시한다. runtime은 manifest와 실제 파일의 누락/변경/추가를 확인하고 같은 구성/내용일 때만 재사용한다. 다른 버전 또는 손상된 runtime과 파일/디렉터리 충돌은 종료 코드 5로 거부하며 덮어쓰지 않는다. runtime은 임시 디렉터리에 완성한 뒤 공개하므로 추출 중 실패한 미완성 환경을 최종 runtime으로 사용하지 않는다. 설치 후에는 runtime 내부 대신 루트 application.yml을 수정한다.

이전 `java -jar` script에서 전환하거나 버전을 업데이트할 때는 실행을 종료하고 기존 runtime 및 생성 script를 별도 백업/이름 변경한 후 새 배포 JAR로 `--install`을 실행한다. 기존 application.yml은 보존한다. 이전 루트 JAR은 설치 프로그램이 자동 삭제하지 않으며 새 script에서는 사용하지 않는다. runtime 생성 이후 다른 파일 생성이 실패하면 일부 파일이 남을 수 있고 원인을 해결한 뒤 다시 실행할 수 있다.

새 설치에는 공통 `run.bat`/`run.sh`를 생성하지 않는다. 이전 설치 폴더에 이 파일이 남아 있으면 사용자 파일로 보존하며 자동 삭제하지 않는다.

모드 선택은 값 없는 `--collect`, `--report`, `--init`, `--install` 중 하나만 사용한다. 기존 `--mode=value`와 `--report=true` 등은 입력 오류다. CLI 플래그가 없을 때는 외부 설정의 `mode` 속성을 사용할 수 있으며 해당 속성도 없으면 수집한다.

## 기본 수집 모드

모드 플래그를 생략하거나 `--collect`를 지정한다. 실행마다 client 생성 → 한 번 수집 → JPA transaction commit → YAML 출력 → 모든 자원 종료 순서로 동작한다.

```shell
./collect.sh --monitor.cluster-id=prod-a --monitor.cluster-name=prod --monitor.members=10.0.0.11:5701,10.0.0.12:5701 --monitor.snapshot-dir=./output/snapshots
```

기본 접속은 cluster-name `dev`, member `127.0.0.1:5701`이다. DB 기본값은 `jdbc:h2:file:./data/hazelcast-monitor`, 계정 `sa`, 빈 비밀번호다. 상대 경로는 JAR 위치가 아니라 **실행한 working directory** 기준이다. DB 경로와 출력 디렉터리는 쓰기 가능해야 한다.

내장 기본 설정은 `src/main/resources/application.yml`에서 관리한다.

`monitor.cluster-id`는 DB 안에서 클러스터를 식별하는 안정적인 값이다. 생략하면 cluster-name을 사용한다. 같은 cluster-name을 사용하는 별도 개발/운영 클러스터에는 서로 다른 cluster-id를 지정한다.

한 번만 실행하므로 주기적 관측은 OS 스케줄러로 반복한다. H2 file 기본 환경에서는 작업이 중복 실행되지 않도록 설정한다. 여러 프로세스가 DB를 공유하는 운영 환경은 PostgreSQL을 사용한다.

Linux cron 예시 (설치 계정의 crontab에서 매 5분; 경로는 설치 환경에 맞게 변경):

```cron
*/5 * * * * /opt/hazelcast-monitoring/collect.sh >> /opt/hazelcast-monitoring/collect.log 2>&1
```

로그인 셸 로딩이나 `cd`, PATH/JAVA_HOME 설정 없이 절대 script 경로로 실행할 수 있다. 생성된 셸은 `dirname`, `uname`, `cygpath` 등 외부 명령에도 의존하지 않는다. 별도 Java를 사용할 때만 crontab에 `JAVA_HOME=/절대/Java/경로`를 지정한다.

Linux 셸은 설치/Java 경로가 고정되므로 디렉터리를 이동하거나 설치 JVM 경로를 변경하면 재생성한다. 기존 `.sh`는 보존되므로 먼저 백업/이름 변경한 뒤 새 위치의 배포 JAR 또는 일반 Java classpath 명령으로 `--install`을 실행한다. application.yml은 보존된다.

Windows 작업 스케줄러는 프로그램을 `cmd.exe`, 인수를 `/d /c C:\apps\hzmon\collect.bat`, 시작 위치를 `C:\apps\hzmon`으로 설정한다. 새 인스턴스 시작 규칙은 기존 실행이 끝나기 전 시작하지 않도록 선택한다.

## 외부 설정과 PostgreSQL

일반 Spring Boot 설정 파일과 환경변수를 지원한다. 비밀번호는 명령줄 대신 외부 보호 설정 또는 환경변수로 전달하는 것을 권장한다.

외부 `config/application.yml` 예시:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/hzmon?currentSchema=monitoring
    username: hzmon
    password: ${HZMON_DB_PASSWORD}
monitor:
  cluster-id: prod-a
  cluster-name: prod
  members: 10.0.0.11:5701,10.0.0.12:5701
  snapshot-dir: ./output/snapshots
  connect-timeout: 30s
  operation-timeout: 10s
  collection-timeout: 120s
```

```shell
./collect.sh --spring.config.additional-location=file:./config/
```

PostgreSQL의 DB/사용자 및 지정한 `monitoring` schema는 운영자가 미리 생성하고 해당 schema에 접속 계정의 생성/조회/쓰기 권한을 부여한다. Liquibase는 접속의 현재 schema에 모니터링 객체를 만든다. `report`는 호환되는 schema와 SELECT 권한만 필요하며 migration을 실행하지 않는다. 첫 실행은 기본 수집 또는 init으로 schema를 생성한다.

TLS·인증·네트워크 상세 설정은 외부 Hazelcast client YAML로 지정한다:

```yaml
hazelcast-client:
  cluster-name: prod
  network:
    cluster-members:
      - 10.0.0.11:5701
      - 10.0.0.12:5701
```

```shell
./collect.sh --monitor.cluster-id=prod-a --monitor.client-config=./config/hazelcast-client.yml
```

이 파일을 사용할 때 `monitor.cluster-name`과 `monitor.members`를 함께 지정하면 오류다. 파일의 cluster-name이 저장되는 실제 이름과 cluster-id 기본값에 사용된다. 관측 프로그램의 접속/호출 제한시간 및 client instance-name은 프로그램이 제어한다. 외부 YAML이 시스템 속성에 설정되어 있어도 프로그램은 별도 서버 멤버를 생성하지 않는다. 실제 대상 Hazelcast 서버 버전과 인증/TLS 구성은 배포 환경에서 호환성을 확인해야 한다.

## 수집 정보와 count 의미

- 실행 UUID, 시작/종료시간, 관측 active node 수, SUCCESS/PARTIAL/FAILED, topology 변경 여부.
- 노드별 member UUID, 이름, address, OBSERVED_ACTIVE, lite member 여부. 이름은 member attribute `monitor.node-name`을 읽고 없으면 address로 대체한다. attribute key는 `--monitor.node-name-attribute`로 변경한다.
- 객체별 type, 원문 service name, distributed object name, count, SUCCESS/UNSUPPORTED/ERROR/NOT_COLLECTED, 관측시각.

| 타입 | count |
| --- | --- |
| Map | entry 수 |
| MultiMap | key-value pair 수 |
| List / Set / Queue | 항목 수 |
| Ringbuffer | 보관 중인 항목 수; capacity 아님 |
| ReplicatedMap | API가 관측한 size; eventual consistency 영향 가능 |
| Topic / ReliableTopic | 수집 제외; object 관측 row를 생성하지 않음 |
| executor / 기타 | null + UNSUPPORTED |

Topic과 ReliableTopic은 새 수집의 DB/YAML object 항목에서 제외한다. 같은 이름의 Map 등 다른 타입과 기존에 저장한 Topic 이력은 유지한다.

object name은 Map entry key가 아니다. `getDistributedObjects()`가 반환하는 생성된 객체만 포함하며, 아직 생성되지 않은 설정상의 객체나 CP subsystem 전체 인벤토리를 의미하지 않는다. key/value를 읽거나 Queue를 소비하지 않는다. 목록/size 조회 자체의 서버 비용은 존재한다.

active node 수는 클라이언트가 시작 시 관측한 전체 멤버 수이며 lite member를 포함하고 client는 제외한다. 서버 내부의 ACTIVE/PASSIVE/FROZEN 상태를 추측하지 않고 `clusterState: UNKNOWN`으로 저장한다. 연결 실패를 node count 0으로 저장하지 않는다. 멤버/객체의 각 값은 수집 구간의 관측값으로 원자적 스냅샷이 아니다.

한 객체의 오류는 count null + ERROR이며 다른 객체 수집을 계속한다. 호출이 timeout이면 같은 worker에 작업을 쌓지 않고 남은 지원 객체를 NOT_COLLECTED로 기록한다. 전체 수집 제한은 최초 연결을 포함한다. JVM 스케줄링과 client 종료 정리 시간은 제한 이후 조금 더 걸릴 수 있다. 지원하지 않는 객체만 있는 경우는 SUCCESS이며, 지원 객체 실패/미수집 또는 topology 변경은 PARTIAL이다.

## YAML과 출력 실패 복구

경로 예시:

```text
output/snapshots/2026/10/27/2026-10-27_10-00-00_<run-uuid>.yml
```

파일 내부 `schemaVersion: 2`, `timeZone: Asia/Seoul`을 기록한다. `snapshots` 실행시간 key와 완료/관측 시간은 `yyyy-MM-dd HH:mm:ss` 형식(예: `2026-10-27 15:57:19`)이다. 일자 폴더도 Asia/Seoul 기준이며, 파일명은 Windows 호환을 위해 콜론 대신 하이픈을 사용한다. 같은 초에도 UUID로 파일을 구분한다. DB는 기존 UTC timestamp와 microsecond 정밀도를 유지한다. 기존 schemaVersion 1 YAML은 원본 내용이 일치하면 덮어쓰지 않고 복구한다.

DB/파일의 분산 transaction은 사용하지 않는다. DB commit 시 export 상태 PENDING과 계획 경로를 기록하고 파일 완료 후 EXPORTED로 바꾼다. 쓰기 실패 시 DB 관측 결과를 남기고 ERROR로 기록한다. 다음 기본 수집 실행에서 같은 cluster-id의 PENDING/ERROR를 100건씩 읽어 재출력한다. 이때 Hazelcast를 다시 읽지 않는다.

같은 경로에 이미 정상적인 동일 관측 YAML이 있으면 상태만 복구한다. 내용이 다르거나 파일이 손상되면 덮어쓰지 않고 오류를 기록한다. 기존 출력 경로가 쓰기 불가능하면 권한/경로를 복구한 뒤 기본 모드를 재실행한다. 복구 실패가 있어도 새 수집은 진행하며 종료 코드 3으로 알린다.

파일은 같은 디렉터리의 임시 파일에 완성한 뒤, 지원되는 파일시스템에서는 hard link로 원자적으로 공개한다. 미지원 provider에서는 replace 없는 move로 대체한다. 기존 파일을 덮어쓰지 않는다. 강제 프로세스 종료로 임시 파일이 남을 수 있으며, 최종 `.yml` 파일만 관측 결과로 취급한다.

## report 모드

```shell
./report.sh --monitor.cluster-id=prod-a "--monitor.start-time=2026-10-27 00:00:00" "--monitor.end-time=2026-10-28 00:00:00" --monitor.report-file=./output/reports/prod-a.html
```

DB 설정은 수집 때와 동일하게 지정한다. report는 Hazelcast client를 생성하지 않고 DB migration/DDL을 수행하지 않는다. schema가 없거나 호환되지 않으면 오류다.

기간은 실행 시작시간 기준 `[start, end)`다. 한쪽만 지정하면 해당 방향만 제한하고 생략하면 cluster-id의 전체 이력을 조회한다. `yyyy-MM-dd HH:mm:ss` 입력은 Asia/Seoul로 해석한다. 공백이 있으므로 명령행 인수 전체를 따옴표로 감싼다. 기존 `Z` 또는 `+09:00` offset을 포함하는 ISO-8601 입력도 지원한다. HTML 날짜도 `yyyy-MM-dd HH:mm:ss` 형식이다. 표시 시간대 기본값은 `Asia/Seoul`이며 `--monitor.report-zone=UTC` 등으로 바꾼다.

파일을 브라우저에서 직접 열면 active node와 각 객체 count 그래프, 객체 이름/서비스 검색, 값·실행 ID tooltip, 실행 요약을 볼 수 있다. 동일 이름의 다른 service는 별도 series다. 오류/미지원/목록에서 누락된 시점은 선이 끊기고 실제 0만 0으로 표시한다. 요청 결과가 없어도 `조회 결과 없음` HTML을 생성한다. CSS/SVG/검색 JavaScript가 파일에 포함되므로 CDN/외부 요청 없이 동작한다.

보고서는 repeatable-read transaction에서 100개 실행씩 keyset 조회하고 자식 관측은 batch 조회한다. 완성 HTML 데이터에는 전체 선택 이력이 포함된다. 기본 상한은 실행 10,000건, object 관측 1,000,000건이며 옵션 `--monitor.max-report-runs`, `--monitor.max-report-objects`로 바꿀 수 있다. 브라우저 렌더링을 위해 object series는 최대 1,000개다. 한도 초과 시 잘라내지 않고 오류를 반환하므로 기간을 줄인다. 지정한 HTML 파일이 이미 있으면 오류이며 덮어쓰지 않는다.

## init 모드와 Liquibase

```shell
./init.sh --spring.config.additional-location=file:./config/
```

`init`은 **DB 현재 schema의 모든 모니터링 이력(모든 cluster-id)을 삭제한다.** 실행 중인 수집/리포트를 중단한 뒤 사용한다. Hazelcast 데이터는 변경하지 않는다. JPA가 먼저 validate하지 않도록 초기화 경로가 분리되어 있어 이전/불일치 schema도 재생성할 수 있다.

삭제 대상은 `hzmon_object_info`, `hzmon_node_info`, `hzmon_monitor_summary`와 전용 Liquibase 이력/lock 테이블뿐이다. 외부 테이블은 삭제하지 않으며 외부 FK가 삭제를 막으면 cascade로 제거하지 않고 실패한다. PostgreSQL은 session advisory lock, H2 file은 OS file lock으로 init 중복 실행을 막는다. H2 server/AUTO_SERVER는 지원하지 않는다. DDL은 DB에 따라 부분 적용될 수 있으며 실패 시 상태를 점검하고 init을 재실행한다.

평상시에는 Liquibase가 누락된 changeset만 적용하고 Hibernate는 schema를 validate한다. 이미 적용한 changeset을 수정하지 않고 새 changeset을 추가한다. 개발 시 원본 changelog는 `src/main/resources/db/changelog/db.changelog-master.yaml`이다.

## 종료 코드

| 코드 | 의미 |
| --- | --- |
| 0 | 작업 성공; 미지원 타입만 존재하는 수집도 포함 |
| 2 | CLI/설정 오류 또는 보고서 범위/크기 제한 초과 |
| 3 | PARTIAL/FAILED 수집 또는 과거 YAML 복구 실패 |
| 4 | DB, migration, init, DB 상태 갱신 실패 |
| 5 | 신규 YAML/HTML 출력 또는 install 파일 생성/충돌 실패 |

DB 저장 실패 시 YAML을 출력하지 않는다. 오류가 겹치면 DB 실패가 우선이며, 파일 출력 실패는 수집 실패보다 우선한다. FAILED 수집도 DB가 정상이면 이력으로 저장한다.

## 테스트

```shell
mvn clean verify
```

기본 테스트는 H2와 테스트 전용 Hazelcast 2노드 cluster를 사용한다. 사용 중인 운영 cluster/DB에는 연결하지 않는다. PostgreSQL 계약 테스트는 **테스트 전용 DB**에 다음과 같이 실행한다. 각 테스트는 고유 schema를 만들고 종료 시 제거하며 테스트 계정은 `postgres` / 빈 비밀번호다.

```powershell
$env:HZMON_TEST_PG_URL='jdbc:postgresql://127.0.0.1:55432/postgres'
mvn -Ppostgres-tests clean verify
```

실제 검증 결과 및 배포 환경에서 확인할 항목은 [project.md](project.md)의 구현 기록에 정리한다.

2026-10-27 실제 JDK 17 `mvn -Ppostgres-tests clean verify`: **24개 테스트 통과**, 실패/오류/건너뜀 0. 별도 JAR 프로세스로 노드 수 `2 → 2 → 1`, Map count `2 → 3 → 3`의 DB/YAML/HTML 출력을 확인했다. 운영 서버의 버전·인증·TLS와 최대 규모 부하는 배포 환경에서 별도 확인한다.

install 추가 후 Java 17 기본 verify는 **28개 테스트 통과**다. 생성 Windows 배치/Git Bash 셸의 cwd·인수·종료 코드 전달과 실제 JAR 설치 → YAML 편집 → init/report → 재설치 보존을 확인했다. 네이티브 Linux와 POSIX 실행 권한은 별도 환경에서 확인해야 한다.

모드별 script 분리 후 Java 17 `clean verify`: **29개 테스트 통과**. 8개 script의 고정 mode, YAML mode 재정의, 인수/cwd/종료 코드 및 기존 사용자 파일 보존을 확인했다.

단독 모드 flag 변경 후 Java 17 `clean verify`: **32개 테스트 통과**. 값 없는 flag의 선택/배타성, 기존 CLI 형식 거부, 외부 mode보다 CLI flag 우선 적용 및 8개 script를 검증했다.

Topic 제외 후 Java 17 `clean verify`: **33개 테스트 통과**. 실제 2노드 cluster에서 일반/Reliable Topic 제외와 동일 이름 Map 유지, 다른 미지원 타입의 기존 처리를 검증했다.

일반 Java runtime 설치 전환 후 Java 17 `clean verify`: **37개 테스트 통과**. 실제 배포본의 dependency 62개를 추출하고 원본 JAR 제거 후 Windows/Git Bash에서 init/report/collect/install, 설정 보존과 runtime 재설치를 검증했다. 다른 디렉터리로 runtime 복사 후 실행도 확인했다. 네이티브 Linux의 실행 권한은 별도 검증 대상이다.

로컬 Hazelcast 교체 및 초기 membership 대기 보완 후 Java 17 verify: **45개 테스트 통과**. 다른 SDK의 연결/초기 멤버 이벤트 순서 차이를 고려하여 두 조건이 준비된 뒤 수집하며 기존 접속/전체 제한시간은 유지한다.

실제 내장 5.7.0 → 로컬 5.3.6 대체 후 로딩 경로와 checksum을 확인했고, 5.3.6 클러스터에서 3회 연속 정상 수집(nodes=1, orders=2), DB/YAML/HTML 및 로컬 원본 제거 후 재설치를 검증했다.

cron용 절대 경로 shell 변경 후 Java 17 `clean verify`: **46개 테스트 통과**. Git Bash에서 PATH/JAVA_HOME 없는 환경의 4개 shell과 실제 application의 init/collect/report/install을 검증했다. Windows에서 POSIX shell 테스트를 실행하려면 `HZMON_TEST_SH`에 Git Bash 실행 파일의 절대 경로를 지정한다. 네이티브 Linux/crond 실행은 별도 환경 검증 항목이다.
