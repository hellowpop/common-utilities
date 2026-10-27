# Install Mode Implementation Plan

현재 runtime 기반 설치 구조는 [runtime-plan.md](runtime-plan.md)를 따른다. 아래 초기 root JAR 복사 계획은 후속 runtime/classes·lib 구성으로 대체되었다.

> **For agentic workers:** superpowers:executing-plans. 기존 작업 경로에서 테스트 우선으로 직접 구현한다.

**Goal:** `--install` 한 번으로 현재 실행 디렉터리에 편집 가능한 설정과 Windows/Linux 실행 스크립트를 생성한다.
**Architecture:** CLI 형식 검증 직후 install을 분기하여 Spring 설정·DB·Hazelcast에 의존하지 않는다. ApplicationHome으로 실제 실행 JAR을 찾아 현재 작업 디렉터리에 고정 이름으로 배치하고 resource template을 파일로 출력한다.
**Tech Stack:** Java 17 NIO, 기존 Boot ApplicationHome/AtomicFiles, batch/POSIX sh.
**Spec:** 사용자 install 모드 요청 및 project.md의 추가 구성.

## Global Constraints / 결정

- 생성 파일: `hazelcast-monitoring.jar`, `application.yml`, `run.bat`, `run.sh`. 실행 디렉터리는 현재 working directory.
- JAR이 이미 같은 위치/같은 내용이면 재사용한다. 다른 JAR이 같은 대상 이름으로 존재하면 실패하며 교체하지 않는다.
- 기존 설정·스크립트는 보존한다. 디렉터리 충돌/쓰기 오류는 종료 코드 5다. install은 DB/client 및 설정 의미 검증 없이 동작한다.
- Windows CRLF, Linux LF/UTF-8 BOM 없음. Linux POSIX 파일시스템에서는 run.sh에 755 실행 권한 적용.
- 스크립트는 자기 디렉터리에서 외부 application.yml을 필수 로딩하고 CLI 인수/Java 종료 코드를 보존한다. JAVA_HOME 우선, 없으면 PATH의 java.
- 별도 승인 질문·커밋·push 없이 기존 AGENTS.md 지침대로 진행한다.

## Review Focus

- 공백/특수문자 경로와 다른 current directory에서 스크립트 실행.
- 기존 application.yml 사용자 편집 보존과 JAR 충돌 무덮어쓰기.
- invalid DB/client 설정이 있어도 install 가능.
- 설치 도중 파일 경합과 잘못된 대상 디렉터리에서의 실패.
- 실제 packaged JAR 경로 탐지와 생성 script의 인수/종료 코드 전달.

## Tasks

1. [x] `InstallTest`: 파일 생성·기존 파일 보존·충돌, 생성 스크립트 실제 실행으로 cwd/설정/인수/exit code 검증을 작성하고 실패 확인.
2. [x] `install/Installer.java`: `install(Path directory, Path sourceJar)` 및 `runningJar()` 구현. resource template 3개, NIO 실행 권한, no-replace publication 적용.
3. [x] `Options.installRequested`, INSTALL enum 및 `MonitorApplication`의 초기 분기/도움말 구현. DB와 client 설정을 열지 않는 테스트 통과.
4. [x] Java 17 clean verify, packaged JAR install → 생성 설정 편집 → Windows 스크립트로 init/report smoke. Linux script는 사용 가능한 sh 환경에서 실행 검증.
5. [x] README/project/루트 변경 기록 갱신, 독립 코드 리뷰와 중요 지적 수정, 결과 기록.

## Ledger

### 단독 모드 flag로 변경 (사용자 후속 요청)

- [x] `--collect`, `--report`, `--init`, `--install`만 값 없는 배타적 모드 flag로 허용한다. 미지정 시 기본 수집 및 외부 설정 mode fallback은 유지한다. 예전 CLI `--mode=value`와 값이 붙은 flag는 입력 오류로 거부한다.
- [x] pre-install 분기와 Boot 이후 분기는 동일 선택 함수를 사용하여 CLI flag가 YAML mode보다 우선하게 한다. 중복 flag는 DB/설정 로딩 전에 거부한다.
- [x] 8개 script template, 도움말, 모든 실행 예제와 테스트를 갱신하고 Java 17 전체 verify 및 실제 JAR 설치/script smoke로 확인한다.

### 모드별 script 분리 (사용자 후속 요청)

- [x] 생성 파일을 collect/report/init/install 각각의 `.bat`, `.sh` 총 8개로 변경한다. 공통 resource template의 모드 자리만 치환하고 기존 생성/보존/권한 규칙을 재사용한다.
- [x] 수집 script도 명시적으로 선택하도록 `--collect` alias를 허용한다. 기본 모드 생략 동작은 유지한다. script에 다른 mode를 추가 전달하면 중복 옵션 오류로 거부한다.
- [x] 실제 Java probe로 8개 script의 고정 mode/cwd/인수/종료 코드를 검증하고, 기존 설정 및 script와 legacy run 파일 보존을 검증한다.
- [x] 전체 verify / JAR install smoke와 문서·도움말 갱신. 새 설치에는 run.bat/run.sh를 생성하지 않으며 기존 사용자 파일을 자동 삭제하지 않는다.

- 설계 결정: 자체 포함 실행 폴더를 위해 JAR도 고정 이름으로 복사한다. 설정 템플릿은 Hazelcast·DB·출력·timeout을 편집할 수 있도록 제공한다.

- 완료: Java 17 기본 verify 28개 통과(실패/오류/건너뜀 0). Windows 및 Git Bash launch probe, packaged JAR install/edit/init/report/reinstall 검증 완료.
- 리뷰: 보존 분기의 동시 생성 대상 재검증을 추가했고 독립 재검토에서 중요 지적 없음. native Linux/POSIX 권한은 환경 미검증으로 문서화.

- 모드별 script 분리 완료: JDK 17 clean verify 29개 통과, 8개 launch probe 및 packaged JAR smoke 확인. 독립 리뷰 중요 지적 없음.

- 단독 flag 변경 완료: Java 17 clean verify 32개 통과, actual JAR --install 및 생성 script --init/--report/--collect/--install 검증. YAML mode=install보다 CLI 우선, 구문 오류 종료 코드 2 확인. 독립 리뷰 중요 지적 없음.
