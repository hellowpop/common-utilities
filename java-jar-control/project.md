# Java JAR Control 구성

## 목적과 구현 선택

Linux executable JAR를 `a.ctl start|stop|status`로 백그라운드 실행·정지·조회한다.
독립 Bash 스크립트와 설정 파일로 구성해 Java 코드 및 Maven 의존성 없이 기존 JAR에 적용한다.
서비스 설치가 필요한 systemd 방식보다 파일 복사로 사용하는 이번 요구에 맞는 Bash 방식을 선택했다.
Java 클래스는 운영 구현에 추가하지 않으며, 테스트용 클래스는 `com.inzent.apim.utilities` 패키지를 사용한다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `a.ctl` | Java 선택, 프로세스 기동·정지·조회, PID 식별, 잠금 |
| `configure` | 고정 파일명 설정. JAR, Java home, 메모리, GC, 일반 JVM, agent, 애플리케이션 인수 |
| `README.md` | 설치·운영·옵션 예제·종료 코드·검증 방법 |
| `tests/test_control.py` | 실제 Java executable JAR 기반 Linux 통합 테스트 |
| `.gitattributes` | 실행 파일과 설정·테스트의 LF 줄바꿈 유지 |
| `.gitignore` | 실행 PID·로그·배포 JAR·Python 캐시 제외 |

## 실행 흐름

`log` 명령은 필수 설정 확인 후 프로세스 제어 잠금을 획득하기 전에 GNU tail을 실행한다.
`logs/${APP_NAME}.log`가 읽기 가능한 일반 파일인지 확인하고 `tail -n 100 -F`로 마지막 100줄과 새 로그를 출력한다.
stdout/stderr 통합 로그를 사용하며 Ctrl+C로 조회만 종료한다. JVM이 정지해도 기존 로그는 조회할 수 있고, 로그가 없으면 코드 1로 종료한다.

1. 명령 검증. `staus`는 `status`와 동일하게 처리한다.
2. 스크립트 디렉터리로 이동하고 선택 항목의 기본값 및 고정 파일명 `configure` Bash 설정을 읽는다. APP_NAME/JAR_FILE은 기본값을 두지 않고 환경 변수도 제거한 뒤 설정에서 읽는다. 두 항목 모두 명시적 비공백 값이 필수이며 누락 시 모든 운영 명령을 코드 1로 종료한다. 스크립트·configure·JAR는 같은 디렉터리에 배포하며 상대 JAR 경로는 이 디렉터리 기준이다. 호출 위치와 환경 변수로 설정 파일을 변경하지 않는다.
3. 설정값과 Linux 실행 환경을 확인하고 인스턴스별 `flock` 잠금을 획득한다.
4. PID, `/proc/PID/stat`의 프로세스 시작 tick, boot ID로 현재 프로세스를 확인한다. zombie는 정지 상태로 본다.
5. start: 기존 실행이면 성공 반환. 오래된 PID를 제거하고 JAR/Java를 확인한 뒤 `nohup` 실행, PID 저장, 생존 확인을 수행한다.
6. stop: 식별이 일치하는 프로세스에 SIGTERM 전송, 시간 초과 시 설정에 따라 오류 또는 SIGKILL을 수행한다.
7. status: 실행 여부와 PID를 출력한다. 실행 중이면 procps `ps -ww`로 PID·PPID·사용자·프로세스 상태·시작 일시·경과 시간·CPU/메모리 사용률·RSS(KiB)·전체 실행 명령을 출력한다. 조회 후 프로세스 식별을 다시 확인하며, 정지면 코드 3을 반환한다.

JVM에는 lock descriptor를 전달하지 않아 장기 실행 중에도 stop/status가 잠금을 얻을 수 있다.
모든 인수는 Bash 배열로 전달하여 공백과 와일드카드를 그대로 보존한다.
PID가 재사용되거나 이전 부팅의 PID 파일이 남았을 때 식별이 다르면 신호를 보내지 않는다.
PID 파일은 보호된 설치 경로에서 관리해야 하며 조작된 PID 파일을 보안 경계로 취급하지 않는다.

## 범위와 운영 조건

- Linux Bash 4+, util-linux `flock`, coreutils, procps `ps`, `/proc` 및 애플리케이션에 맞는 Java 필요.
- Java 우선순위: 설정 JAVA_HOME → 환경 JAVA_HOME → PATH java.
- 메모리/GC/일반 JVM/agent/애플리케이션 옵션을 독립 구역으로 분리한다.
- 기본 로그는 append, 기본 종료는 SIGTERM 대기이며 강제 종료는 명시적으로 설정한다.
- 기동 검증은 프로세스 생존 기준이며 서비스 준비 상태 검증·자동 재시작·부팅 등록은 범위 밖이다.
- 다중 인스턴스는 각자 별도의 설치 디렉터리로 배포한다. 실행 중 이름·설치 경로 변경은 지원하지 않는다.
- 설정·PID 파일을 잃거나 변조한 상태에서 전체 시스템의 Java 프로세스를 검색·종료하지 않는다.

## 검증 기록

- 2026-11-03: push 전 WSL Ubuntu 24.04에서 a.ctl/configure Bash 문법 검사 및 실제 JDK 기반 전체 통합 테스트 17개를 재실행해 통과했다. 스크립트·configure·테스트·README·구성 문서만 develop에 커밋하여 origin/develop에 반영한다. 운영 로그/PID/JAR와 다른 작업의 변경은 제외한다.

- 2026-10-29: `a.ctl log`를 추가했다. 최근 100줄·새 로그 추적·stdout/stderr 표시·제어 명령과의 동시 실행·파일 누락 오류를 검증한다. 필수 항목 검증도 log 명령을 포함한 30개 하위 사례로 확장했다. Bash 문법 검사 및 전체 통합 테스트 17개 통과. 첫 전체 실행에서 기존 `test_early_exit_and_invalid_jvm_option_fail_start`의 1초 생존 확인 시 기대와 달리 실행 중으로 반환한 사례가 있었으며, 단독 재실행과 전체 재실행에서는 통과했다. 해당 동작은 재현되지 않아 구현을 변경하지 않았다.

- 2026-10-29: APP_NAME/JAR_FILE을 configure 필수 항목으로 변경했다. shell 기본값·환경 변수 대체를 제거하고 모든 운영 명령에서 누락·빈 값·공백 값 오류를 검증한다. Bash 문법 검사 및 전체 통합 테스트 15개 통과(필수 항목 검증 24개 하위 사례 포함).

- 2026-10-29: 설정 파일명을 `configure`로 고정하고 `CTL_CONFIG` 선택 기능을 제거했다. 같은 디렉터리의 JAR·설정 배포와 기존 파일명 변경 방법을 README에 반영했다. 다른 작업 디렉터리에서의 로딩·외부 설정 선택 무시·configure 누락 오류를 포함해 전체 통합 테스트 14개 통과.

- 2026-10-29: status/staus에 실제 프로세스 상세 정보를 추가했다. 설정 변경 후에도 실행 중인 JVM의 명령을 조회하며 긴 인수를 보존한다. Bash 문법 검사 및 실제 JDK 기반 전체 통합 테스트 12개 통과.

- 2026-10-29: WSL Ubuntu 24.04에서 Bash 문법 검사 및 실제 JDK 기반 통합 테스트 11개 통과.
- 수명주기, 중복/동시 기동, 잘못된 Java/JAR/JVM 옵션, 기동 직후 종료, Java home/PATH 선택, 공백 포함 인수, PID 재사용/부팅 불일치, 비정상 PID, 종료 시간 초과와 강제 종료를 검증한다.
- 기존 Java 모듈은 수정하지 않는다. 실행 방법은 README.md에 정리한다.
