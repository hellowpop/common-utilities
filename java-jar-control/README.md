# Java JAR Linux 실행 제어

`a.ctl`로 executable JAR를 백그라운드에서 실행하고 정지·조회한다.
Linux Bash 4 이상, `/proc`, `flock`(일반적으로 util-linux), `nohup`, `ps`(procps), 애플리케이션에 맞는 Java가 필요하다.
설치는 파일 복사만으로 가능하며 Maven 빌드가 필요 없다.

## 빠른 시작

`a.ctl`, `configure`, 실행할 JAR를 같은 디렉터리에 복사한다.
`configure`의 `JAR_FILE`을 실제 파일명으로 변경한다.
설정 파일명은 확장자 없는 `configure`로 고정하며, 호출한 작업 디렉터리와 관계없이 `a.ctl` 디렉터리에서 읽는다.
`APP_NAME`과 `JAR_FILE`은 `configure`에서 반드시 지정한다. 스크립트 기본값이나 환경 변수로 대체하지 않으며, 누락·빈 값·공백만 있는 값이면 start/stop/status/staus 모두 종료 코드 1로 실패한다.

```bash
# configure 필수 항목
APP_NAME="my-service"
JAR_FILE="my-service.jar"
```

```bash
cd /opt/my-app
chmod +x a.ctl
./a.ctl start
./a.ctl status
./a.ctl log
# 로그 조회는 Ctrl+C로 종료한 뒤 다음 명령 실행
./a.ctl stop
```

`./a.ctl staus`도 `status`의 별칭으로 동작한다.
PATH에 설치 디렉터리를 추가하면 `a.ctl start` 형태로 호출할 수 있다.
다른 작업 디렉터리에서는 `/opt/my-app/a.ctl status`처럼 절대 경로로 실행한다.
스크립트와 설정 파일은 LF 줄바꿈으로 배포한다.

## 상태 조회의 프로세스 정보

`./a.ctl status`와 `./a.ctl staus`는 실행 중인 JVM의 정보를 표로 출력한다.

| 항목 | 의미 |
| --- | --- |
| `PID`, `PPID` | 프로세스 ID, 부모 프로세스 ID |
| `USER`, `STAT` | 실행 사용자, Linux 프로세스 상태 코드 (`S`: 대기, `R`: 실행 등) |
| `STARTED` | 프로세스 시작 일시 (서버의 시간대) |
| `ELAPSED` | 시작 후 경과 시간 (`[[dd-]hh:]mm:ss`) |
| `CPU%` | 프로세스 수명 동안의 평균 CPU 사용률 |
| `MEM%` | 물리 메모리 대비 상주 메모리 비율 |
| `RSS_KB` | 상주 메모리 크기 (KiB) |
| `COMMAND` | Java 실행 경로, 실제 JVM 옵션, JAR 및 애플리케이션 인수 |

실제 프로세스의 정보를 `ps -ww`로 조회하므로 긴 실행 명령도 자르지 않는다.
시작 후 설정을 편집해도 실행 중인 JVM의 명령을 표시한다. 명령에 포함된 속성·인수도 그대로 출력된다.
조회 전후에 PID의 시작 tick과 boot ID를 확인하며, 정지 상태에서는 표 없이 정지 메시지와 종료 코드 3을 반환한다.

## Java 선택

다음 순서로 Java를 선택한다.

1. `configure`에서 `JAVA_HOME` 지정
2. 호출 환경의 `JAVA_HOME`
3. `PATH`의 `java` 자동 탐지

```bash
# configure에서 명시적으로 선택
JAVA_HOME="/opt/java/jdk-17"
```

또는 환경 변수로 선택한다.

```bash
JAVA_HOME=/opt/java/jdk-21 ./a.ctl start
```

설정에서 `JAVA_HOME=""`로 지정하면 기존 환경 변수 대신 PATH 탐지를 사용한다.
JAVA_HOME이 비어 있지 않으면서 `bin/java`가 없으면 오류로 종료한다.
Java 버전은 자동 변경하지 않는다. 기본 G1 GC 옵션은 해당 JVM이 지원해야 한다.
`stop`과 `status`에는 Java 실행 파일이나 원본 JAR가 필요 없다.

## 설정 구역

`configure`는 Bash 설정 파일이다. 각 옵션은 공백으로 분리한 문자열 대신 **배열**로 지정한다.
배열 항목 하나가 JVM 인수 하나가 되므로 공백이 포함된 경로·속성도 따옴표로 보존된다.

| 구역 | 용도 | 예시 |
| --- | --- | --- |
| `APP_NAME` | 필수: PID·잠금·로그 파일 이름 | `my-service` |
| `JAR_FILE` | 필수: executable JAR 경로 | `"my-service.jar"` |
| `MEMORY_OPTS` | heap, metaspace, direct memory | `("-Xms512m" "-Xmx2g")` |
| `GC_OPTS` | GC 선택 및 GC 로그 옵션 | `("-XX:+UseG1GC")` |
| `JAVA_OPTS` | 기타 JVM 옵션·시스템 속성 | `("-Dfile.encoding=UTF-8")` |
| `AGENT_OPTS` | javaagent, native agent, agent 속성 | 아래 예시 |
| `APP_ARGS` | JAR에 전달할 애플리케이션 인수 | `("--server.port=8080")` |
| `START_WAIT` | 기동 직후 프로세스 생존 확인 대기(초) | 기본 `2` |
| `STOP_TIMEOUT` | SIGTERM 이후 종료 대기(초) | 기본 `30` |
| `FORCE_STOP` | 시간 초과 시 SIGKILL 허용 | 기본 `false` |

`START_WAIT`, `STOP_TIMEOUT`은 1~99999 범위의 정수다. `APP_NAME`은 영문·숫자로 시작하며 영문·숫자·점·밑줄·하이픈만 허용한다.
상대 경로와 JVM의 작업 디렉터리는 `a.ctl`이 위치한 디렉터리를 기준으로 한다.
`APP_NAME`과 설치 위치는 실행 중에 변경하지 않는다.

```bash
MEMORY_OPTS=("-Xms512m" "-Xmx2g" "-XX:MaxMetaspaceSize=256m")
GC_OPTS=(
    "-XX:+UseG1GC"
    # Java 9 이상에서 사용 가능한 예시
    "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
)
JAVA_OPTS=("-Dspring.profiles.active=production")
AGENT_OPTS=(
    "-javaagent:/opt/agents/monitor.jar=config=/opt/agents/monitor.conf"
    "-Dagent.service.name=my-service"
)
APP_ARGS=("--server.port=8080")
```

기동 시 `logs/`가 생성된다. GC 옵션은 Java 배포판·버전에 맞게 선택하고 상충하는 GC 선택 옵션을 함께 지정하지 않는다.
에이전트 파일은 별도 준비하며, 예시의 agent 속성은 사용하는 제품의 설정으로 변경한다.
모든 명령은 고정된 `configure`를 사용한다. 별도 설정 파일 선택 환경 변수는 지원하지 않는다.
기존 `a.conf`를 사용 중이면 같은 디렉터리의 `configure`로 이름을 변경한다.

실제 JVM 인수의 순서는 다음과 같다.

```text
java [MEMORY_OPTS] [GC_OPTS] [JAVA_OPTS] [AGENT_OPTS] -jar JAR_FILE [APP_ARGS]
```

## 프로세스·로그 운영

- 기본 PID 파일: `run/a.pid` (PID, `/proc` 시작 tick, Linux boot ID)
- 기본 잠금 파일: `run/a.lock` (10초 잠금 대기, 파일을 삭제하지 않음)
- 기본 표준 출력·오류 로그: `logs/a.log` (append)
- 표준 입력은 `/dev/null`이며 `nohup`으로 터미널 종료 후에도 계속 실행한다.
- `start` 중복 호출은 기존 PID를 반환하며, 동시에 호출해도 하나만 기동한다.
- `stop`은 SIGTERM을 보내 shutdown hook 실행을 기다린다. 기본값에서는 시간 초과 시 오류를 반환하고 PID를 유지한다. `FORCE_STOP=true`이면 SIGKILL 후 최대 5초 확인한다.
- PID의 시작 tick과 boot ID가 맞지 않으면 다른 프로세스에 신호를 보내지 않는다. `stop`과 다음 `start`가 오래되거나 잘못된 PID 파일을 제거한다. `status`는 PID 파일을 보존한다.

```bash
./a.ctl log
```

`log`는 `logs/${APP_NAME}.log`의 마지막 100줄을 출력한 뒤 `tail -n 100 -F`로 새 로그를 계속 보여준다.
JVM 표준 출력(stdout)과 표준 오류(stderr)가 함께 표시된다. Ctrl+C는 로그 조회만 종료한다.
프로세스가 정지된 상태에서도 기존 로그를 조회할 수 있으며, 파일이 없거나 읽을 수 없으면 코드 1로 실패한다.
로그 조회는 프로세스 제어 잠금을 획득하지 않으므로 별도 터미널에서 start/stop/status를 함께 사용할 수 있다.
`log`에도 configure의 APP_NAME/JAR_FILE 필수 지정 규칙이 적용된다. GNU coreutils의 `tail`이 필요하다.

`start` 성공은 대기 구간 뒤 프로세스가 살아 있다는 뜻이다. HTTP 준비 완료나 업무 처리가 가능한 상태까지 검사하지 않는다.
기동 시 바로 종료되거나 JVM 옵션이 잘못되면 `start`가 실패하며 로그에 원인이 남는다.
자동 재시작·부팅 시 시작·로그 회전은 포함하지 않는다. 표준 출력 로그는 별도 logrotate 등으로 관리한다.
foreground로 유지되는 executable JAR를 사용하며, 스스로 별도 프로세스를 생성하고 종료하는 런처는 지원하지 않는다.

동일한 OS 사용자로 실행·정지한다. 설정 파일은 `source`로 읽는 실행 가능한 Bash 코드이므로 신뢰하는 사용자만 수정하도록 관리한다.
설치 디렉터리와 `run/`을 보호하고, 실행 중 PID·잠금 파일을 삭제하거나 같은 JAR를 다른 controller로 중복 기동하지 않는다.
프로세스는 저장된 PID로 관리하므로 PID 파일을 잃으면 JAR 이름 검색으로 복구하지 않는다.

## 종료 코드

| 코드 | 의미 |
| --- | --- |
| `0` | start/stop 성공, 이미 실행/정지됨, status 실행 중 |
| `1` | 설정·기동·잠금·종료·로그 조회 오류 |
| `2` | 잘못된 명령 또는 인수 개수 |
| `3` | status/staus 조회 결과 정지 상태 |

## 검증

`log` 추가 후 Bash 문법 검사와 전체 통합 테스트 17개 통과(2026-10-29, WSL Ubuntu 24.04).
최근 100줄·후속 출력·stdout/stderr·조회 중 stop/status·로그 누락 오류를 확인했다.

Linux에서 JDK(`java`, `javac`, `jar`)와 Python 3를 준비한다. 테스트는 임시 디렉터리에서 실제 JAR를 빌드·실행하며 운영 설정은 사용하지 않는다.

```bash
bash -n a.ctl
bash -n configure
python3 -m unittest discover -s tests -v
```

2026-10-29 WSL Ubuntu 24.04에서 통합 테스트 15개 통과. 필수 항목 누락·빈 값·공백 값·환경 변수 대체 거부, 고정 configure 로딩·외부 설정 선택 무시·기동·중복 기동·동시 기동·정지·status/staus 프로세스 정보 및 긴 명령 출력·Java 선택·공백 인수·기동 실패·오래된 PID·종료 시간 초과·강제 종료를 확인했다.
