# HTTP Client Executor Implementation Plan

> 실행 방식: `superpowers:executing-plans`로 현재 세션에서 직접 구현한다. 사용자 지시에 따라 확인 질문 없이 진행하며 기존 develop 변경은 보존한다.

**Goal:** 단일 Maven JAR에서 HT5/Netty/Java 선택, 설정 override, 본문 생성·변경, 원본 기록 및 실행 이력을 제공한다.

**Architecture:** 공통 실행 계약과 저장·풀을 공유하고 커넥터는 전송을 담당한다. HT5 HTTP/1.1은 Apache managed connection의 content output 확장으로 길이 제한을 우회한다. raw 연결은 재사용하지 않는다.

**Tech Stack:** Java 17, Maven, Apache HttpClient 5.6.5, Netty 4.2.21.Final, 기존 payload-generator, JUnit.

**Spec:** [project.md](project.md), [요구 원문](http-client-executor.md).

## Global Constraints

- 단일 POM·일반 JAR·sources JAR, `META-INF/docs/http-client-executor/` 문서 포함.
- 모든 Java 클래스는 `com.inzent.apim.utilities` 하위. var/static import 금지, 인스턴스 접근 this 사용.
- 기존 변경·다른 모듈 소스는 수정하지 않는다. install은 `-Dmaven.test.skip=true` 사용.
- 커넥터를 몰래 교체하지 않고 실제 버전·전송 가능 범위를 명시한다.

## Review Focus

- 길이 불일치 본문의 실제 socket bytes 및 capture 일치.
- timeout/cancel/close/부분 응답에서 연결과 임시 파일 회수.
- generator callback 이후 stream 접근 금지와 배열 hook 상한.
- 설정 snapshot 격리, 풀 상한과 재사용, HTTPS proxy 터널과 hostname 검증.
- URI/header 주입, 복수 framing 헤더, HTTP/2 버전 강제와 thread 수명.

## Task 1: 공통 API와 실행 계약

파일: `pom.xml`, `src/main/java/.../http/client/{config,model,execution,interceptor,store}` 및 `CoreContractTest`.

- [x] 설정·URI·상태·헤더 경계 테스트 작성 및 실패 확인.
- [x] 불변 모델, builder, manager/executor/context와 BodyStore 구현.
- [x] 테스트 통과 및 snapshot·single-use·실패 기록 검증.

## Task 2: HTTP/1.1 세 커넥터와 원본 기록

파일: `connector/{java,ht5,netty}`, `spi`, `Http1IntegrationTest`.

- [x] 실제 socket fixture에서 POST bytes·capture·hook 순서 및 길이 불일치 전송 테스트 작성, 실패 확인.
- [x] Java socket, Apache managed connection writer 확장, Netty channel 전송 구현.
- [x] 공통 parser/framing, 풀·deadline·취소·close 및 proxy/TLS 구현.
- [x] 정상·짧은/긴 본문·응답 오류·재사용·부분 수신 테스트 통과.

## Task 3: Generator와 HTTP/2

파일: `payload/PayloadBody`, HT5/Netty HTTP/2 전송 및 통합 테스트.

- [x] generator 수명/압축·HTTP/2/TLS fixture 테스트 작성, 실패 확인.
- [x] generator adapter, ALPN/HTTP2 전송, 설정과 자원 회수 구현.
- [x] mTLS·proxy·취소·본문 크기 경계 회귀 검증.

## Task 4: 문서·패키징·최종 검증

- [x] README와 project.md를 실제 API/제한/검증 결과로 갱신.
- [x] JDK 17 clean verify, dependency tree와 jar/sources/docs 확인.
- [x] 독립 코드 리뷰의 중요 지적을 회귀 테스트로 수정.
- [x] 테스트를 생략한 install, 변경 범위와 git diff --check 확인.

## 진행 기록

- 작업 시작: 기존 checkout의 develop에서 작업. 기존 미커밋 파일은 보존하고 신설 프로젝트만 구현한다.
- 선행 조사: Apache `DefaultManagedHttpClientConnection`의 protected `createContentOutputStream`을 확인했다. HTTP/1.1은 classic managed connection을 사용하고 HTTP/2는 별도 async 경로를 사용하도록 설계 결정을 구체화한다.
- 커밋·push는 요청하지 않았으므로 자동 수행하지 않는다.

## 최초 구현 완료 기록

- Task 1~4: complete. Java 17 PARANOID clean verify 52/52 통과, 일반/소스 JAR과 Markdown 5개 포함 확인.
- 독립 리뷰 중요 지적 8건은 ReviewRegressionTest/Http2IdleTest의 실패를 확인한 뒤 수정했다. 전체 테스트를 다시 통과했다.
- 최초 범위 기록: 연결당 단일 active 실행과 HT5 H2 proxy/localAddress 제한은 이후 [HTTP/2 확장](http2-extension-plan.md)에서 해소했다. 세부 DNS/TLS 시간은 현재도 미제공이다. 실제 지원표는 README/project.md를 따른다.
- 현재 develop의 기존 변경은 보존했다. 구현·검증 산출물은 새 프로젝트 경로에 생성했고 커밋/push는 수행하지 않았다.
