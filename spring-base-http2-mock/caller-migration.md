# Request Caller 전체 이관 (2026-11-15)

## 목적과 현재 구성

기존 `http-mock-request-caller` 프로젝트의 개발내역·설계·소스·테스트·UI·DB 스키마를 `spring-base-http2-mock`으로 이전한다. 기존 별도 Maven 모듈과 library 의존성을 제거하고 단일 Boot 3 앱에서 `/caller/` 및 기존 `/api` caller API를 제공한다.

## 이관 대응표

| 이전 내용 | 현재 위치 |
| --- | --- |
| `src/main/java/.../http/caller` | 이 프로젝트의 같은 Java 패키지 경로 |
| `src/test/java/.../http/caller` | 이 프로젝트의 같은 테스트 패키지 경로 |
| `src/main/resources/static/*` | `src/main/resources/caller-ui/*` |
| `src/main/resources/db/*` | `src/main/resources/db/*` |
| `src/test/js/*` | `src/test/js/*`; 자원 경로를 caller-ui로 변경 |
| `README.md` | [현재 caller 사용법](caller-README.md) |
| `project.md` | [개발내역·상세 설계](caller-project.md) |
| `command.md` | [원래 요구사항](caller-command.md) |
| `implementation-contract.md` | [구현 계약](caller-implementation-contract.md) |
| `implementation-plan.md` | [구현 계획·기록](caller-implementation-plan.md) |
| POM·application 설정 | 현재 POM/application.yml로 필요한 의존성·한도 통합 |
| `scripts/Build.ps1` | `scripts/Build-Caller.ps1`가 통합 빌드로 위임 |
| 기존 `data/` | 현재 `data/caller/`에 복사 |
| 원본 전체 파일·로그·여러 target 디렉터리 | `.migration-archive/caller-original/`에 원문 보존 |

이전 main 클래스는 호환용으로 현재 MockApplication에 위임한다. standalone Boot 설정과 전용 ObjectMapper는 제거하여 기존 mock JSON/ISO 시각·프로토콜 동작을 유지한다. 기본 caller 한도는 CallerProperties의 기존 기본값을 사용한다.

## 원본 보존과 삭제 조건

- 원본 전체 1,052개 파일을 복사하고 SHA-256으로 모든 파일의 일치를 확인했다. 파일 목록과 해시는 `.migration-archive/caller-original-manifest.md`에 기록한다.
- `.migration-archive/`와 `data/`는 Git에서 제외하며 `target` 외부에 두어 `mvn clean`에도 보존한다. 기존 POM·application.yml·Build.ps1·gitignore도 원문 보존본에 포함한다.
- 실제 사용 코드·테스트·문서는 Git 추적 가능한 현재 프로젝트 경로에 둔다. 보존본의 옛 프로젝트 설정은 실행에 사용하지 않는다.
- 전체 Java/Node 테스트, 실행 JAR·소스·Markdown 패키징, 이전 모듈 의존성 부재 및 데이터/원본 해시 검증을 완료한 후 기존 프로젝트 루트를 삭제한다.

## 검증

- JDK 17 단일 프로젝트 `mvn -DskipTests=false verify`: Java 111개, 실패·오류·건너뜀 0.
- 이전한 caller API/기존 mock 공존 사전 검증 8개 및 Node 전체 회귀 테스트 26개 통과.
- 실행 JAR의 `BOOT-INF/classes`에 caller 엔진·UI·DB 스키마가 직접 포함되며 caller library JAR은 없다.
- 기존 H2 데이터 복사본으로 실제 실행 JAR 기동 후 두 콘솔·자원·CSP·echo·세션/CSRF·YAML export/import 정상 동작 확인.
- 27개 엔진 클래스, 전체 UI 및 runtime 데이터의 바이트 일치를 확인했다. main 위임·공통 설정·보안 필터·API 테스트와 JS 경로는 단일 앱 구성에 맞게 조정했다.
- 삭제 직전 원본 전체 1,052개 파일과 보존본의 SHA-256을 다시 비교해 일치 확인. 기존 프로젝트의 모든 파일과 루트 디렉터리를 삭제했다.
- 기존 프로젝트 디렉터리를 작업 경로로 잡고 있던 유휴 IntelliJ cmd 세션 하나를 종료해 빈 디렉터리 잠금을 해제했다. 다른 IDE 세션과 실행 작업은 유지했다.
- 기본 install에서 테스트를 생략하며 executable/sources JAR과 갱신된 전체 루트 Markdown 포함을 확인했다.

## 푸시 전 검증

2026-11-15 사용자 푸시 요청에 따라 JDK 17 `verify`를 다시 실행해 Java 111개, Node 26개 모두 실패·오류·건너뜀 없이 통과했다. 커밋 대상은 이전 프로젝트 삭제와 현재 프로젝트의 소스·테스트·기술문서이며, 로컬 runtime 데이터·로그·빌드 산출물·원본 보존본은 제외한다. 대상 브랜치는 `develop`, 원격은 `origin/develop`이다.
