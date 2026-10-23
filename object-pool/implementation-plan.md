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

## PoolConfig Jackson 후속 변경 (2026-10-20)

- [x] 기존 record와 기본 ObjectMapper 동작을 검토했다. 완전한 JSON은 기존에도 가능하나 빈/부분/null 속성의 기본값 적용은 불가능함을 확인했다.
- [x] 15개 JSON 회귀 사례를 먼저 작성하고 수정 전 3개 오류를 확인했다.
- [x] 불변성을 보존하는 명시적 creator와 nullable 속성 기본값 처리를 적용하고 전체 47개 테스트 통과를 확인했다.
- [x] optional annotations/test databind 의존성과 README 사용 설명을 추가했다.
- [x] 최종 Java 17 clean verify, JSON 예제 및 Jackson 없는 일반 사용을 검증한다.

판단: setter 기반 가변 설정으로 전환하지 않아 Main 구성 후 외부 설정 변경으로 용량 불변식이 깨지는 경로를 만들지 않는다. ObjectMapper는 외부 애플리케이션이 제공하며 풀 내부에 보관하지 않는다.

결과: JDK 17 clean verify 47/47, README JSON 예제 실행 시 부분 설정+기본값 확인, 기존 풀 예제의 Jackson 없는 런타임 실행 성공. 독립 리뷰 중요 지적 없음. 커밋·push 없음.

## Sub 독립 컨테이너 후속 변경 (2026-10-20)

- [x] 독립 소유권·로컬 재사용·초기 배정·JSON 초기 수·Main 이전 회귀 사례 5개를 추가한다.
- [x] 이전 cross-Sub idle 회수 기대를 새 계약에 맞게 조정하고 수정 전 3개 실패를 확인한다.
- [x] SubPoolContainer를 SubPool별로 소유하고 실제 entry 관리·idle 대여/반납을 수행하도록 변경한다.
- [x] 다른 Sub의 idle 회수를 제거하고 초기 배정 완료 전 발급 금지·실패 rollback·지연 폐기 계약을 유지한다.
- [x] 전체 52개 테스트 통과를 확인하고 독립 리뷰를 수행한다.
- [x] README/기술문서와 최종 Java 17 clean verify·분석 probe 결과를 갱신한다.

판단: initialObjectsPerSub를 그대로 사용해 기존 Jackson 설정과 생성자 호환성을 유지한다. 컨테이너는 독립 소유하되 Main의 조정 락을 사용해 종료·반납·생성 경쟁의 상태 전이를 원자적으로 유지한다.

결과: JDK 17 clean verify 52/52, 분석 probe 20개 확인과 최종 CLOSED/created=0/destroying=0. 독립 리뷰 중요 지적 없음. 커밋·push 없음.

## Main 초과 생성과 idle 상한 후속 변경 (2026-10-20)

- [x] maxSize 초과 동시 대여, Main idle 상한, 지연 반납·Sub 종료·cache 초과·rollback·폐기 실패·동시 반납을 테스트한다.
- [x] 이전 hard limit 기대를 수정하고 변경 전 8개 오류를 확인한다.
- [x] 생성 수 제한을 제거하고 Main 반납을 recycleToMainLocked로 통합한다.
- [x] Sub cache와 Main idle 상한의 설정 검증을 분리한다.
- [x] 전체 58개 테스트 통과와 독립 리뷰를 확인한다.
- [x] 최종 Java 17 clean verify·분석 probe를 실행하고 문서를 갱신한다.

판단: maxSize는 Main idle만 제한한다. maxSubPools는 다른 설정이므로 활성·준비 Sub 수 제한을 유지한다. 기존 초기 수와 JSON 속성·공개 API는 유지한다.

결과: JDK 17 clean verify 58/58, 분석 probe 20개 확인. 독립 리뷰 중요 지적 없음. 커밋·push 없음.

## 메모리·NPE 재분석 및 적용 (2026-10-20)

- [x] 현재 코드의 target/Main 참조, 예외·종료·독립 컨테이너 및 null 경계를 재검토한다.
- [x] 버스트 후 내부 배열과 빈 ThreadLocal 잔존을 독립 계측·회귀 테스트로 재현한다.
- [x] 수정 전 7개 회귀 중 5개 실패를 확인한다.
- [x] 큰 관리 Map/idle Deque의 점진 축소, 종료 후 빈 저장소 교체, 일반 진입의 빈 ThreadLocal 제거를 구현한다.
- [x] 전체 65개 통과 및 독립 리뷰를 확인한다.
- [x] 최종 Java 17 clean verify, 기존 안전성 probe와 새 메모리 계측 probe를 검증하고 기술문서를 갱신한다.

판단: target 누수와 최고 크기 배열 보관을 구분한다. 살아 있는 entry를 제거하지 않고 동일 락 아래 저장소만 재구성한다. 실제 객체 수나 강제 회수·생성 상한 정책은 변경하지 않는다.

결과: JDK 17 clean verify 65/65, 기존 안전성 20개 확인 및 memory probe --check-compaction exit 0. 독립 리뷰 중요 지적 없음. 커밋·push 없음.

## 메모리 검사 실행 환경 보완 (2026-10-20)

- [x] fork 없는 Maven과 VM 옵션 없는 독립 probe에서 모듈 접근 예외를 재현한다.
- [x] 내부 계측을 필요한 모듈만 연 자식 JVM에서 자동 실행하고 실패·시간 초과를 전달한다.
- [x] 전역 Surefire argLine을 제거하고 Java 17 전체 65개, fork 없는 메모리 검사 7개와 독립 probe를 검증한다.
- [x] 독립 리뷰와 README·분석서·구성 문서 갱신을 완료한다.
