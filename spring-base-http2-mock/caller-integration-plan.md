# HTTP Mock Request Caller 통합 계획

이 문서는 앞서 수행한 library 연결 방식의 이력이다. 이후 caller 프로젝트 전체를 이 프로젝트 내부로 이관하는 방식으로 대체했다. 현재 상태와 검증은 [전체 이관 기록](caller-migration.md)을 따른다.

## 설계

Spring mock에서 caller의 시나리오 저장·실행·취소·SSE·본문·로그·HTML 보고서를 같은 JVM으로 제공한다. 기존 `/` 콘솔과 `/api/execute-mock-request`는 유지하고 caller 화면은 `/caller/`, 기존 caller API는 `/api`를 사용한다.

소스 복사는 유지보수를 이중화하고 별도 프로세스는 단일 앱 통합 목적에 맞지 않는다. 따라서 caller에 일반 `library` classifier JAR을 추가하고 Spring Boot 3 호스트가 공통 설정을 import한다. caller의 독립 Boot 4 실행도 유지한다. library에서는 독립 `application.yml`을 제외한다.

## 구현 및 검증 순서

- [x] Spring 앱에서 caller 화면/API/CSRF 및 기존 echo 공존을 검증하는 통합 테스트를 먼저 추가하고 실패를 확인한다.
- [x] caller의 재사용 설정, namespaced 정적 리소스, library JAR, 보안 필터 범위를 구현한다. standalone의 `/`는 기존 화면을 제공한다.
- [x] Spring 의존성/import, JDBC·Liquibase 설정, 콘솔 링크와 빌드 순서를 연결한다.
- [x] caller와 Spring 전체 Java 테스트 및 두 모듈 Node 회귀 테스트를 실행한다.
- [x] executable/library/sources JAR, Markdown 포함 및 install 테스트 생략을 확인한다.
- [x] 각 모듈 README/project 문서에 통합 실행법·설정·호환성을 정리한다.

## 검증 결과와 구현 판단

- JDK 17: caller 전체 55개, Spring 전체 56개, 최종 library 통합 재검증 3개 통과. Node 26개 통과.
- 기존 SSE/WS가 ISO 시각을 요구하므로 caller 전용 ObjectMapper bean을 standalone application에만 두고 통합 호스트의 mapper를 사용한다.
- standalone root 콘솔 보안 헤더는 독립 application.yml에서만 활성화하는 `caller.security.include-root-console`로 보존한다. 통합 host에는 caller 경로만 적용한다.
- 실제 executable JAR에서 두 콘솔, caller JS/CSP, echo, 세션/CSRF, YAML export/import 통과.
- commit/push 및 별도 worktree 생성 없이 요청받은 프로젝트 경로에서 작업했다.

## 주의할 경계

- 독립 application.yml 및 root index 충돌 방지.
- caller CSRF/CSP가 기존 mock 요청·화면에 적용되지 않음.
- 기존 caller API Location 헤더와 UI 자원 주소 유지.
- Boot 3 호스트에서 JDBC/Liquibase 초기화 후 caller 복구 수행.
- 기존 mock `/api/*`의 HTTP/1.1 제한은 유지하고 caller 소유 API만 HTTPS HTTP/2를 허용한다.

진행 확인 질문을 하지 않는 프로젝트 규칙에 따라 현재 작업 세션에서 직접 구현·검증하며 commit/push는 수행하지 않는다.
