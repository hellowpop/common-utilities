# Runtime Classpath Installation Plan

> **For agentic workers:** superpowers:executing-plans. 기존 경로에서 테스트 우선 직접 구현.

**Goal:** --install로 executable 배포 JAR에서 독립적인 기본 Java classpath 실행 환경을 runtime에 생성한다.
**Architecture:** runtime/classes에는 BOOT-INF/classes를, runtime/lib에는 BOOT-INF/lib의 dependency JAR들을 추출한다. 루트의 설정/모드별 script는 유지하며 java -cp로 MonitorApplication을 실행한다. 설치된 classes 경로도 설치 원본으로 지원해 재설치 시 원본 executable JAR이 필요 없게 한다.
**Tech Stack:** Java 17 NIO/JarFile/SHA-256, 기존 Spring Boot와 launcher templates.
**Spec:** 사용자 runtime 기반 일반 Java 실행 환경 요청.

## Decisions / Review Focus

- 최초 설치용 executable JAR 빌드는 유지한다. 설치 결과에는 루트 executable JAR을 복사하지 않는다.
- runtime/classes, runtime/lib, runtime/manifest.properties(SHA-256 파일 목록)를 생성한다. classes와 lib 전체를 staging에 완성한 후 no-replace 이동으로 공개한다.
- 기존 runtime은 동일 구성/내용만 재사용한다. 누락/변경/추가 파일 또는 다른 버전은 종료 코드 5로 거부한다. 사용자 설정·스크립트는 기존 보존 규칙 유지.
- source ZIP 경로 이탈, 중복 파일, 심볼릭 링크와 runtime 경로 충돌을 거부한다. 임시 runtime 정리는 설치 대상 아래 생성한 정확한 staging 경로만 삭제한다.
- 설치된 runtime을 원본으로 복사/재설치할 수 있어야 한다. Windows/Linux classpath 구분자와 공백 경로, 고정 flag, 외부 설정, exit code를 보존한다.
- 기존 java -jar script는 자동 교체하지 않는다. 사용자가 백업/이름 변경 후 재생성하는 전환 절차를 문서화한다.

## Tasks

1. [x] InstallTest를 Boot-layout probe archive로 변경하고 runtime 생성/무덮어쓰기/일반 java 실행 테스트 실패 확인.
2. [x] RuntimeLayout 클래스로 안전한 archive 추출/manifest 검증/설치된 runtime 복사 및 directory publication 구현.
3. [x] Installer/MonitorApplication의 source 탐지와 재설치 경로, launcher의 java -cp 실행 전환.
4. [x] ZIP 경로 이탈/다른 runtime/추가 dependency/공백 cwd와 원본 JAR 제거 후 재설치 회귀 검증.
5. [x] Java 17 clean verify, 실제 배포 JAR에서 생성한 runtime의 init/report/collect/install smoke, 독립 리뷰와 README/project 갱신.

## Completion ledger

### cron용 Linux 절대 경로 script (사용자 후속 요청)

- [x] Linux script에 설치 디렉터리, 설치 JVM의 Java 실행 파일, classpath 및 외부 설정 파일을 절대 경로로 기록한다. JAVA_HOME이 있으면 절대 경로만 허용하여 우선 적용한다.
- [x] 시작에 필요한 동작을 POSIX shell builtin으로 구성하여 빈 PATH/JAVA_HOME에서도 실행한다. Windows .bat는 기존 동작을 유지한다.
- [x] shell literal quote와 설정 URI 인코딩으로 공백/작은따옴표/달러/퍼센트/쉼표 경로를 보존한다. Windows Java 사용 시 classpath 구분자는 환경 또는 drive 경로로 결정하여 uname/cygpath를 요구하지 않는다.
- [x] 4개 shell을 cron 유사 최소 환경과 다른 cwd에서 실제 Java로 실행하는 회귀 테스트 후 전체 verify / 실제 JAR script smoke / crontab 예제와 재생성 절차 갱신.

### 설치 디렉터리 Hazelcast 우선 적용 (사용자 후속 요청)

- [x] 최상위 hazelcast*.jar 중 client/core API를 가진 본체 하나를 선택한다. sources/javadoc 및 API가 없는 부가 JAR은 제외, 복수 본체/손상 JAR은 설치 오류로 처리한다.
- [x] runtime stage에서 기존 본체를 제거하고 선택한 로컬 본체를 원래 파일명으로 복사한다. SQL 등 부가 dependency는 유지하며 선택 결과/실제 checksum을 기록한다.
- [x] 배포 JAR 및 설치된 runtime을 원본으로 하는 설치에 동일하게 적용한다. 기존 runtime 변경은 거부하고 같은 override 재설치는 허용한다. 원본/로컬 archive 제거 후에도 설치된 runtime을 재사용한다.
- [x] 회귀 실패 → 구현 → 전체 Java 17 verify / 실제 로컬 라이브러리로 runtime 구성 및 classpath 실행 / 문서 갱신.

- Java 17 clean verify 및 최종 verify 37개 통과(실패/오류/건너뜀 0).
- 실제 배포 JAR dependency 62개 추출, 설치 원본 제거 후 java -cp의 init/report/collect/install, 누락 script 재생성, 설정 hash 보존 및 runtime 다른 경로 복사/실행 확인.
- 독립 리뷰 중요 지적 없음. Properties malformed escape는 IOException(설치 오류 5) 처리로 회귀 수정. Git Bash의 네이티브 Windows Java classpath 구분자는 uname/cygpath 분기로 검증.
- 기존 설정/script 및 legacy JAR은 보존하며 runtime 업그레이드/스크립트 재생성 절차를 README에 반영. 커밋/push 없음.

- 로컬 Hazelcast override 완료: Java 17 verify 45개 통과, 실제 5.3.6 SDK로 3회 nodes=1/orders=2 정상 수집 및 DB/YAML/HTML/원본 제거 후 재설치 검증. 구 SDK 초기 membership 순서 차이는 동일 deadline의 이중 readiness로 보완했다. 독립 리뷰 중요 지적 없음.

- cron 절대 경로 shell 완료: Java 17 clean verify 46개 통과. PATH/JAVA_HOME 없는 Git Bash의 launch probe 및 실제 app init/collect/report/install과 특수문자 경로 외부 설정 검증 완료. 네이티브 Linux/crond는 환경 미검증으로 문서화했다.
