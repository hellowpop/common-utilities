# Embedded 실행 추가 계획

이 문서는 임베디드 추가 시점의 계획과 완료 기록이다. 이후 registry 입력 및 scan 경로 변경은 [processor-binding-plan.md](processor-binding-plan.md)를 따른다.

**목표:** 기존 executable JAR와 구현 클래스의 사용을 유지하면서 Java 애플리케이션에서 사용할 public interface와 library artifact를 제공한다.

**설계 선택:** `server.HttpMockServer extends AutoCloseable`에 `start(): int`, `awaitClose()`, `close()`와 생성 factory를 둔다. YAML/인수 생성, 불변 설정 생성, 사용자 registry 주입을 지원한다. embedding에서는 shutdown hook/System.exit를 호출하지 않고 호스트가 종료를 소유한다.

**빌드 선택:** 기존 main executable JAR는 유지하고 `library` classifier로 프로젝트 클래스/리소스만 포함한 JAR를 추가한다. Maven 소비자는 classifier library와 POM의 전이 의존성을 사용한다.

- [x] 인터페이스 타입만 사용하는 실제 socket/설정 기반 lifecycle 테스트 작성 → 누락 API 실패 확인.
- [x] 인터페이스 및 factory, 기존 구현의 implements 적용 → 전체 unit/socket 테스트 통과.
- [x] 별도 library JAR 소비자의 컴파일/기동/HTTP/종료 검증 작성 → 미생성 library 실패 확인.
- [x] library classifier 빌드 및 Java 17 clean verify 통과.
- [x] README에 Maven 설치/의존성, 설정/registry 지정, 비차단 시작과 호스트 종료 절차, 완전한 Java 예제 추가. project.md에 구성과 결과 기록.

사용자 지침에 따라 확인 질문 없이 현재 체크아웃의 대상 모듈에서 진행하고 commit/push는 하지 않는다. 기존 `start`/`awaitClose`/`close` 시그니처와 여섯 action의 동작을 유지한다.

## 실행 기록

- 2026-11-02: 미존재 interface와 library artifact의 테스트 실패 확인 후 구현. 호스트 application.yml 충돌도 실제 소비자 JVM에서 실패를 확인한 뒤 namespaced 기본 리소스로 해결했다.
- JDK 17.0.10 `mvn clean verify`: 36개, 실패/오류/건너뜀 0. 별도 focused 코드 리뷰에서 추가 지적 없음.
- 실제 로컬 Maven install 및 runtime 의존성 복사 후 README의 javac/java classpath 절차를 실행했다. `Embedded response: 200 OK`, `Embedded server closed; host JVM continues` 출력 및 프로세스 정상 종료 확인.
- Ruling: Jar plugin forceCreation을 적용하여 반복 package/install 시 이미 shade한 JAR를 다시 입력으로 사용하는 것을 방지한다. 기존 executable artifact 이름을 유지한다.
