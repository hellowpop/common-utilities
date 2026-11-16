# Response 서버 통합 계획 및 이관 기록

작성일: 2026-11-16

## 목적과 설계

`http-mock-response-server`의 구현을 `spring-base-http2-mock`으로 옮겨 `spring-mock.jar`에 직접 포함한다. 기존 Spring `MockServicesLifecycle`이 이미 엔진 생성·시작·종료·재시작과 설정을 담당하므로 이 수명주기를 유지한다. 별도 모듈을 계속 의존하거나 HTTP/2 전송 구현으로 다시 작성하는 방식은 이번 소스 통합에 필요하지 않다.

공개 패키지는 `com.inzent.apim.utilities.http.mockserver`를 유지한다. Response 엔진의 평문 HTTP/1.1·TCP 바인딩과 Spring HTTPS HTTP/2 서비스는 현재처럼 공존한다. 이번 변경은 response 바인딩 자체에 HTTP/2를 추가하지 않는다.

## 제약

- Java 17, Spring Boot 3.5.16, 실행 산출물 `spring-mock.jar`를 유지한다.
- 소스 JAR 첨부, 모듈 루트 Markdown 패키징, install 시 테스트 생략을 유지한다.
- 기존 포트·`mock.services.response.*` 설정과 이벤트·연결 종료·자원 회수 계약을 유지한다.
- 기존 소스·문서·진단 도구의 원본은 target 외부의 `.migration-archive/response-original/`에 보존하고 Git에서 제외한다.
- 이관 소스는 명시적 Java 타입과 일반 import를 사용한다.

## 실행 계획

- [x] 기존 response 모듈의 테스트와 파일 목록을 확인한다.
- [x] Spring POM에서 response 의존성을 제거하고, 구현 부재로 컴파일이 실패하는 것을 확인한다.
- [x] response 소스·테스트·진단 도구·기술 문서를 Spring 프로젝트로 이전한다. 사용법 링크와 Maven 명령을 통합 프로젝트 기준으로 갱신한다.
- [x] `Build-WithMockServers.ps1`에서 response 별도 설치를 제거하고 README/project.md 및 저장소 기술 문서를 갱신한다.
- [x] 통합 Java·JavaScript 테스트, 실행/소스 JAR 및 Markdown 포함, 외부 response JAR 부재를 검증한다.
- [x] 원본 보존을 검증한 뒤 기존 모듈의 추적 파일과 잔여 디렉터리를 정리한다.

## 검증 초점

1. 기존 HTTP parser·named scenario·자원 회수 테스트가 같은 패키지에서 실행되어야 한다.
2. Spring response 엔진의 시작·종료·재시작과 다른 mock/caller 기능이 유지되어야 한다.
3. 이전 response artifact가 없는 Maven 구성에서도 통합 소스가 컴파일되어야 한다.
4. `spring-mock.jar`에는 구현 클래스가 직접 포함되고 이전 response JAR이 중복 포함되지 않아야 한다.
5. 이전 문서와 진단 도구가 소실되지 않고 현재 문서의 상대 링크가 유효해야 한다.

## 최종 검증

- 기존 response 모듈: 테스트 59개 통과. 의존성 제거 직후 Spring 컴파일이 누락 타입 오류로 실패하는 것을 확인하고 소스를 통합한 뒤 같은 59개가 통과했다.
- 실제 JDK 17.0.10에서 통합 `mvn test -DskipTests=false`: 176개, 실패·오류·건너뜀 0.
- Node.js 전체 테스트 31개 통과, 독립 SafetyProbe 진단 14개 통과.
- 공개 API 31개 클래스의 `javap -public` 결과가 원본과 일치한다. 이관 코드·테스트·probe에 var/static import가 없다.
- 실행 JAR에 response 엔진 class 파일 45개와 response Markdown 7개가 직접 포함되고, BOOT-INF/lib의 이전 response JAR은 없다. 소스 JAR에는 response Java 소스 31개가 포함된다.
- 실제 Java 17 실행 JAR에서 response 바인딩 3개와 REPLY HTTP 200, 엔진 STOPPED → RUNNING 전환 후 HTTP 200을 확인했다. 테스트 데이터와 임시 포트를 사용했다.
- 원본 150개 파일을 보존본과 SHA-256으로 다시 비교한 뒤 기존 모듈 디렉터리를 `.migration-archive/response-retired/`로 이동했다. 활성 프로젝트 경로의 이전 모듈은 없고 Git에서는 기존 모듈 삭제·현재 프로젝트 추가로 반영된다. 복사 보존본과 원본 디렉터리는 모두 Git·패키징에서 제외한다.
- 기술 문서의 현재 상대 링크와 PowerShell 빌드 스크립트 구문을 검증했다. 과거 문서의 옛 명령·좌표는 이력으로 표시하고 현재 실행법은 response-README.md에 제공한다.

## 이관 대응표

| 이전 경로 | 통합 경로 |
| --- | --- |
| src/main/java/.../http/mockserver (31개) | 같은 패키지의 Spring main 소스 |
| src/test/java/.../http/mockserver (7개) | 같은 패키지의 Spring 테스트 |
| analysis/SafetyProbe.java | analysis/response-server/SafetyProbe.java |
| README.md | response-README.md |
| project.md | response-project.md (설계 이력) |
| 구현·확장·context·자원 분석 문서 4개 | response-*.md (설계 이력) |
| pom.xml과 기존 target | 무시되는 원본 보존 경로 |

별도 response Maven 모듈·의존성·선행 설치는 제거했다. 패키지/API·응답 내용·설정 키와 기존 포트 기본값은 유지한다. 소스 JAR 첨부와 문서 패키징은 통합 POM에서 수행하며 install은 기본적으로 테스트를 실행하지 않는다.
