# Main/Sub Object Pool Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task-by-task. 사용자 프로젝트 규칙에 따라 추가 확인 질문 없이 현재 세션에서 직접 실행한다.

**Goal:** 승인된 Java 17 Closeable Main/Sub 풀과 필수 상태 관리 API를 구현한다.

**Architecture:** Main 단일 락이 registry와 소유권을 관리한다. target은 재사용하지만 Sub와 Holder는 대여별로 발급한다. 생성·폐기 훅은 락 밖에서 실행하고 종료 중 borrowed 객체는 지연 폐기한다.

**Tech Stack:** Java 17, Maven, JUnit Jupiter 5.13.4. 운영 외부 의존성 없음.

**Spec:** [project.md](project.md)

## Global Constraints

- 패키지 `com.inzent.apim.utilities.pool`, 독립 `object-pool` 모듈.
- Main/Sub/ObjectHolder는 java.io.Closeable, close는 멱등적.
- 초기값 0, maxSize 128, maxSubPools 32, maxCachedPerSub 8, initialObjectsPerSub 0.
- 기존 사용자 변경과 다른 모듈 소스는 수정하지 않는다. develop에서 현재 모듈 경로로 작업한다.
- 설계 승인과 추가 질문 금지 규칙에 따라 계획 승인 재요청 없이 직접 실행한다. commit/push는 이번 범위에 포함하지 않는다.

## Review Focus

- 생성 중 Main/Sub 종료: 생성된 target 폐기와 예약 해제가 모두 완료되어야 한다.
- 두 생성이 같은 target을 반환: 기존 target을 폐기하지 않고 실패해야 한다.
- Sub 초기 배정 부분 실패: idle과 준비 슬롯을 복구해야 한다.
- 동시 중복 반납: 다음 대여에 영향 없이 정확히 한 번 반납해야 한다.
- 정리 Error와 IOException 혼합: 나머지 객체를 정리하고 Error를 우선 보존해야 한다.

## Task 1: 기본 수명과 필수 상태

**Files:** `pom.xml`, `src/main/java/com/inzent/apim/utilities/pool/{PoolConfig,PoolStats,SubPoolStats,PoolState,PoolExhaustedException,MainPool,SubPool,ObjectHolder,PooledEntry,SubState}.java`, `src/test/java/com/inzent/apim/utilities/pool/PoolLifecycleTest.java`.

**Interfaces:** MainPool(PoolConfig), generateTargetObject()/destroyTargetObject(T), start(), borrowSubPool(), recycleSubPool(SubPool), stats(), isTerminated(), close(); SubPool.borrowObjectHolder()/recycleObjectHolder()/stats()/close(); ObjectHolder.get()/close().

- [x] 먼저 정상 수명·초기 배정·Main/Sub 6개 필수 카운트·설정 검증·조기 Sub/Main 종료·중복 반납·잘못된 소유권 테스트를 작성한다.
- [x] `mvn -f object-pool/pom.xml test`로 미구현 API 실패를 확인한다.
- [x] Config와 snapshot record, 대여 핸들, registry 상태 전이와 실패 처리를 구현한다.
- [x] 같은 명령으로 기본 수명 테스트가 통과하는지 확인한다.

## Task 2: 실패 및 동시성 계약

**Files:** `PoolFailureTest.java`, `PoolConcurrencyTest.java`, 필요 시 위 구현 파일 보완.

**Interfaces:** Task 1의 API와 snapshot 카운트를 사용한다. 테스트용 확장 MainPool이 생성/폐기 횟수와 latch를 보유한다.

- [x] null/중복 target, 부분 초기화 실패, 여러 폐기 실패, 훅 재진입, 반환 후 참조 해제를 검사한다.
- [x] latch/barrier로 생성 대 종료, 동시 초기 배정 상한, Holder 중복 close, 대여/반납/종료 경쟁을 검사한다.
- [x] 새 테스트 실패를 확인한 뒤 필요한 수정을 적용하고 전체 모듈 테스트를 통과시킨다.

## Task 3: 사용 문서·최종 검증·리뷰

**Files:** `README.md`, `project.md`, 루트 `project.md`.

- [x] 설정, 전체 사용 예제, 상태 항목, 지연 폐기, 예외 계약을 README에 정리한다.
- [x] 실제 JDK 17로 `mvn -f object-pool/pom.xml clean verify`를 수행한다.
- [x] README 예제를 javac로 확인하고 JAR major version 61을 확인한다.
- [x] requesting-code-review 스킬에 따라 독립 리뷰를 요청한다. 중요 지적은 재현 테스트 후 수정하고 전체 검증한다.
- [x] 기술문서와 이 실행 기록에 실제 검증 결과를 기록한다.

## Execution Ledger

- 사전 검토: 승인된 명세의 필수 상태·초기 배정·지연 종료 계약을 각 Task에 매핑했다. 독립 신규 모듈이므로 기존 develop 작업 경로에서 수행하고 사용자 변경은 보존한다.
- Task 1: 미구현 API 컴파일 실패 확인 후 기본 수명 7개 통과. 실제 JDK 17.0.10, `mvn -f object-pool/pom.xml test -q` exit 0.
- Task 2: 초기 배정 경쟁 테스트에서 expected 2/actual 1 실패를 재현. 준비 중 Sub를 idle 회수에서 제외한 뒤 전체 24개 통과, 같은 명령 exit 0.
- Task 2 판단: 반환 상태는 외부 훅 없이 단일 락에서 완료하므로 returningCount는 항상 0으로 제공한다. 준비 Sub의 초기 객체는 발급 전까지 다른 대여에서 회수하지 않는다.
- Task 3: JDK 17 clean verify 24/24, README 전체 예제 컴파일·실행 성공, JAR 11개 클래스 major 61 확인. 문서 직접 컴파일 시 Windows 기본 인코딩 오류를 확인하고 UTF-8을 명시해 해결했다.
- Final review: 독립 reviewer의 Critical/Important 지적 없음. 코드 수정 필요 없음. 반환 이후 raw target 사용 금지, 유한 훅, 폐기 target 재등록 금지를 문서에 명시했다.
- 작업 결과는 현재 develop의 신규 모듈에 보존한다. 사용자 규칙에 따라 추가 통합 선택 질문은 하지 않고 커밋·push도 수행하지 않는다.

## 분석 적용 후속 작업 (2026-10-20)

- [x] 로깅 NPE/RuntimeException/Error 및 복합 실패·락 점유 회귀 테스트를 먼저 작성한다.
- [x] 수정 전 8개 실행 사례 중 5개 실패를 확인한다.
- [x] 경고 snapshot 수집과 락 밖 진단을 분리하고, cleanup을 항상 먼저 수행한다.
- [x] 전체 32개 테스트 통과를 확인한다.
- [x] Java 17 clean verify와 갱신된 분석 probe를 실행한다.
- [x] 독립 리뷰 및 최종 문서 갱신을 완료한다.

후속 판단: 이미 다른 스레드가 수행 중인 DESTROYING 작업을 close 재호출에서 다시 claim하지 않는다. 원인인 로깅 경로의 정리 실행 차단을 제거해 중복 폐기 없이 보장한다.

후속 결과: JDK 17 clean verify 32/32, probe 20개 확인과 CLOSED/created=0/destroying=0. 독립 리뷰 중요 지적 없음. 커밋·push 없음.
