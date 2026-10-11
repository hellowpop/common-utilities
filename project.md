# Common Utilities

## 프로젝트 기준 경로

- 프로젝트 루트: `C:\Develop\codex-project\common-utilities`
- 기술문서: `project.md`
- Java: 21
- 클래스 패키지: `com.inzent.apim.utilities` 하위
- 첫 번째 설계 대상: `object-recycle-container` 독립 유틸리티 모듈
- Git 저장소: `https://github.com/hellowpop/common-utilities.git`
- 원격 이름: `origin`, 초기 브랜치: `master`
- 개발 브랜치: `develop`, 추적 브랜치: `origin/develop`. 이후 개발 작업은 `develop`을 유지한다.
- Git 관리 대상: 소스, 테스트, Maven 설정, `AGENTS.md`, `project.md`, `.gitignore`
- Git 제외 대상: Maven `target/`, IntelliJ `.idea/`, `*.iml`

## 구조 변경내역

- 2026-10-11: Object Recycle Container 설계 초안을 simple-http-forward-proxy/project.md에서 이 프로젝트로 이관. 프로젝트 기준 경로, 패키지, Maven 좌표 및 빌드 기준 디렉터리를 수정. 설계 검토 단계이며 구현은 시작하지 않음.
- 2026-10-11: Main만 thread-safe, Sub는 소유 스레드 전용으로 변경. Main 객체 수 initial/min/max 및 Supplier 생성 정책 추가. worker 반환과 patrol은 Main 관리 상태를 통해 처리하도록 수정.
- 2026-10-11: Main 생성자에 선택적 LeakListener 추가. 미지정 시 감지 대상 forceClose, 지정 시 Listener가 처리 여부를 결정하도록 누수 감지와 강제 종료를 분리.
- 2026-10-11: Main을 abstract 클래스로 변경하고 Supplier/ObjectLifecycle을 createNewHolder/isRecycleable/onRecycle/onDestroy 추상 훅으로 대체. Sub onBorrow, 생성 후 start 초기화, 재사용 Holder와 대여 핸들 분리 계약 추가.
- 2026-10-11: Holder 반환을 소유 Sub의 LIFO 구조에 보관하도록 변경. 부족 시 Main.newObject로 충당하며 Sub 반환 시 borrow/recycle 통계 불일치 warning 추가. worker 반환도 Sub 소유를 유지하는 전달 경로로 수정.
- 2026-10-11: `object-recycle-container` Java 21 독립 Maven 모듈 구현. Main 단일 락 수명 관리, owner 전용 Sub LIFO, 대여별 HolderLease, worker 반환함, patrol/LeakListener, initial/min/max 및 종료·실패 처리를 추가.
- 2026-10-11: 독립 코드 리뷰에서 할당 rollback의 소유권 경쟁과 추가 Holder의 LIFO 순서 오류를 재현하고 수정. 실패한 min 보충을 다음 borrow에서 재시도하도록 보완.
- 2026-10-11: 사용자의 GitHub push 요청에 따라 로컬 Git 저장소와 `origin`을 연결. 비어 있는 원격 저장소에 기존 로컬 브랜치 `master`로 초기 구현을 등록하도록 구성하고, push 전에 기술문서를 갱신. IDE 설정과 빌드 산출물을 Git 관리에서 제외.
- 2026-10-11: `master`에서 `develop` 브랜치를 생성. 원격 `origin/develop`을 추적하도록 구성하고 로컬 작업 브랜치는 `develop`으로 유지한다. push 전에 브랜치 운영 정보를 이 문서에 기록.

## Object Recycle Container

### 2026-10-11 구현 계획 및 진행 기록

사용자의 “질문하지 않고 프로젝트 문서 참조하여 구현 시작” 요청에 따라 아래 설계를 구현 기준으로 채택한다.
과거 설계 검토 단계/구현 착수 금지 문구는 당시의 기록이며 이번 요청으로 구현 단계로 전환한다.
구현 당시에는 Git 저장소가 없는 디렉터리여서 현재 프로젝트에 직접 구현하였다.
이후 사용자의 push 요청으로 로컬 Git/원격 origin을 연결하고 master 초기 커밋을 구성한다.

1. Java 21 독립 Maven JAR와 JUnit 테스트를 구성한다. 기본 수명, LIFO, worker 반환,
   만료된 핸들, 용량, 소유권에 대한 테스트를 먼저 작성하고 미구현 실패를 확인한다.
2. `ContainerConfig`, `ObjectHolder`, `HolderLease`, `SubContainer`, `MainContainer` 및
   immutable 통계/누수 이벤트 타입을 `com.inzent.apim.utilities.recycle`에 구현한다.
   Main 단일 ReentrantLock으로 슬롯/세대/회계를 보호하고 모든 사용자 훅은 락 밖에서 실행한다.
3. 가짜 단조 시계와 latch 테스트로 patrol, 사용 중 회수, 생성/정리 실패, 종료 경쟁,
   min 보충/idle 축소, onBorrow 준비 및 worker 반환 경로를 검증한다.
4. 전체 `mvn -f object-recycle-container/pom.xml clean verify`를 실행하고 API 사용 예제,
   구조 변경내역과 실제 검증 결과를 이 문서에 기록한다.

설계 보완: 사용자 정의 Sub 생성은 Main의 `protected createSubContainer()` 훅으로 제공한다.
기본 구현은 일반 Sub를 반환하며, 확장 구현은 `new SubContainer<>(this)`의 하위 타입을 반환한다.
세대 소진 슬롯은 폐기한다. 예외 진단과 통계 불일치 경고에는 `System.Logger`를 사용한다.
테스트 시계 주입은 protected 생성자 `MainContainer(config, listener, LongSupplier)`로 제공한다.

진행 기록:

- 공개 API 미구현 상태의 테스트 컴파일 실패를 확인한 뒤 구현하였다.
- 기본 수명 10개, 설정·훅·정책 19개, 동시성 15개의 테스트가 `mvn ... test`에서 통과하였다.
- `patrolReportsOnlyTheCurrentScansForcedClosures` 및
  `mainCanRevokeSubFromAnotherThreadWithoutAccessingItsStack` 실패를 확인한 뒤
  patrol의 이벤트별 회수 집계와 registry 기반 Main 반환으로 수정하였다.
- `failedMinRecoveryAlsoRetriesOnNextBorrow`의 expected=3 / actual=2 실패를 확인한 뒤
  Sub/Holder 대여 시작 시 min 보충을 재시도하도록 수정하였다.
- 독립 리뷰의 `failedAllocationRollbackCannotDestroyAReassignedSlot` 실패를 확인한 뒤
  rollback에서 최초 배정의 Sub 소유자와 세대를 검사하도록 수정하였다.
  min 보충 중 생성 부분 실패의 동일 경쟁도 공개 API/latch 테스트로 검증하였다.
- `additionalAssignmentPushesToTheTopOfTheLifoStack`의 expected=id-2 / actual=id-1
  실패를 확인한 뒤 초기·추가 배정을 모두 stack.push 방식으로 통일하였다.
- 구현 당시 Git 저장소가 없어 worktree/commit/PR 생성은 적용하지 않았다.
  이후 push 요청에 따라 현재 프로젝트의 초기 커밋과 원격 등록을 진행한다.

### 상태와 목적

2026-10-11 기준 아래 설계를 채택하여 독립 라이브러리로 구현하였다.
기존 프록시 프로젝트와 별개이며 이 문서에 이 모듈의 구현·검증 기록을 유지한다.

Java 21 환경에서 객체를 재사용하고, 호출 스레드가 빌린 객체를 다른 작업 스레드에서도
반환할 수 있는 독립 유틸리티를 만든다. 성공 기준은 객체의 중복 대여·중복 반환 방지,
만료된 참조의 재사용 차단, 정상 반환 시 초기화, 누수 의심 자원의 탐지와 안전한 회수이다.
객체 풀링의 성능 이점은 전제하지 않고 실제 객체 생성 비용과 경합을 측정한다.

요청에서 정한 사항:

- Main Container가 Sub Container와 전체 Object Pool을 관리한다.
- Main Container는 thread-safe이며 객체 인스턴스의 initial/min/max를 관리한다.
- Sub Container는 non-thread-safe이며 자체 locking 없이 소유 스레드만 조작한다.
- Main은 abstract 클래스이며 `createNewHolder()`로 부족한 Holder를 생성한다.
- 반환 시 `isRecycleable(holder)`가 true이면 `onRecycle(holder)`, false이면 forceClose 경로의 `onDestroy(holder)`를 호출한다.
- Main에서 Sub Container를 대여할 때 해당 Sub의 `void onBorrow()`를 호출한다.
- Sub Container는 기본 개수의 객체를 받아 시작하고 Object Holder의 대여·반환을 관리한다.
- Holder borrow는 Sub 내부 구조체에서 pop, 정상 recycle은 같은 구조체에 push한다. 부족하면 Main의 newObject를 호출한다.
- Holder recycle 시 Main 풀로 반환하지 않는다. Sub recycle 시 borrow/recycle 통계를 비교하여 다르면 warning을 출력한다.
- Sub Container 반환 시 보유 자원은 Main Container로 돌아간다.
- 두 계층 모두 borrow time 및 last use time 제한을 감시한다. Main 생성자에서 LeakListener를 받으며 미지정 시 감지 대상을 forceClose한다.
- Managed POJO는 Holder 참조를 보유하고 caller 또는 worker thread에서 dispose한다.

구현 기준:

- 하나의 Main Container는 한 가지 객체 타입 `T`와 한 가지 생성·초기화 정책을 관리한다.
- 동기화 책임은 Main에 집중한다. Sub의 로컬 자료구조는 다른 스레드에 공개하지 않는다.
- Managed POJO 자체는 호출자가 생성한다. 풀링 대상은 `ObjectHolder<T>`와 내부의 `T`이며, POJO는 대여별 핸들을 보유한다.
- 모든 정상 Holder 반환은 소유 Sub에 보관한다. Main 풀로의 일괄 반환은 Sub recycle 단계에서 수행한다.
- 풀 부족 시 대기하지 않고 즉시 실패한다. 무제한 증설과 blocking borrow는 초기 범위에서 제외한다.

### 접근 방식 비교

| 방식 | 장점 | 비용·제약 | 판단 |
| --- | --- | --- | --- |
| thread-safe Main + 소유 스레드 전용 Sub + 대여 토큰 | Sub 자체 락 없이 비동기 반환과 patrol의 정합성 유지 | 공유 수명 전이는 Main 동기화 경유 | 요구사항 반영안 |
| Main/Sub별 락 분리 | 여러 스레드가 Sub를 직접 조작 가능 | Sub에도 locking 필요 | 요청에 따라 제외 |
| lock-free 큐 + CAS 상태 관리 | 일부 경합 경로에서 이점 가능 | ABA, 세대, 다중 자원 회수의 정합성 복잡 | 초기 제외 |

### 구성과 프로젝트 배치

```text
abstract MainContainer<T>
  ├─ ObjectPool<T>                 전체 슬롯 및 유휴 객체
  ├─ SubState registry            Main이 보호하는 상태·통계·Sub별 worker 반환함
  │    └─ SubContainer<T>          소유 스레드 전용 외부 핸들, 자체 락 없음
  │         ├─ holder stack      초기 개수로 시작하는 LIFO 구조, 슬롯/세대 토큰 보관
  │         └─ HolderLease<T>      대여마다 새로 발급하는 외부 핸들
  │              └─ slot + generation → ObjectHolder<T> → T
  ├─ createNewHolder()            Holder 생성 추상 훅
  ├─ isRecycleable(holder)        재사용 가능 여부 판정 추상 훅
  ├─ onRecycle(holder)            재사용 전 Holder 리소스 정리 추상 훅
  ├─ onDestroy(holder)            최종 리소스 해제 추상 훅
  └─ Patrol + LeakListener        만료 검사와 이벤트
```

`ObjectPool`과 `SubState`, 슬롯은 내부 구현이다. 재사용할 내부 상태와 외부에 노출할
대여 핸들을 분리한다. 핸들은 재활용하지 않아 오래된 POJO가 새로운 대여를 반환하는
문제를 막는다. `createNewHolder()`의 반환 객체는 풀에서 재사용하는 실제 Holder이다.
`borrowObjectHolder()`는 그 Holder를 감싼 `HolderLease<T>`를 반환한다. Managed POJO가
보유하는 Holder 참조는 이 대여 핸들이다. 훅에는 실제 Holder를 전달한다.
실제 Holder를 그대로 POJO에 재노출하지 않아 이전 대여자의 close가 새 대여를 종료하지 못한다.

common-utilities 프로젝트 아래에 독립 Maven 모듈을 추가하였다. 공개 패키지는 com.inzent.apim.utilities.recycle 하위이다.

```text
object-recycle-container/
  pom.xml                         Java 21, 일반 library JAR
  src/main/java/com/inzent/apim/utilities/recycle/      공개 API 및 내부 구현
  src/test/java/com/inzent/apim/utilities/recycle/      수명·경쟁·장애 검증
project.md                        이 문서에 설계와 변경내역 유지
```

좌표는 `com.inzent.apim.utilities:object-recycle-container:1.0.0-SNAPSHOT`이다.
런타임 외부 의존성 없이 JDK만 사용하고 테스트에는 JUnit을 사용한다.
유틸리티는 common-utilities 프로젝트 루트에서
`mvn -f object-recycle-container/pom.xml verify`로 별도 빌드한다.
현재 common-utilities 루트에는 pom.xml이 없으며, 모듈을 직접 지정하여 빌드한다. 프록시 연결과 Maven aggregator 구성,
JPMS `module-info.java` 추가는 이번 범위에 포함하지 않는다.

### 객체 수명과 소유권

Main만 객체 생성과 전체 용량을 관리한다. 슬롯은 언제나 Main 유휴 풀, Sub 스택/반환함,
대여 중, 생성·정리 중, 격리 중 중 정확히 한 상태에 속한다.

| 대상 | 상태 전이 | 의미 |
| --- | --- | --- |
| Main | NEW → STARTING → OPEN → CLOSED | start 성공 후 대여 가능, 시작 실패/종료는 CLOSED |
| Sub | ACTIVE → REVOKED → RELEASED | 회수 시작과 동시에 핸들 무효화, 정리 완료 후 등록 해제 |
| HolderLease | ACTIVE → RECYCLED 또는 EXPIRED | 한 번 종료한 핸들은 다시 활성화하지 않음 |
| 내부 슬롯 | IDLE → BORROWED → RECYCLING → IDLE | 판정 및 onRecycle 성공 시 재사용 |
| 내부 슬롯 | BORROWED → QUARANTINED → DESTROYED | 만료·강제 회수 경로 |

- 대여 토큰에 Main 식별자, Sub 대여 식별자, 슬롯 식별자와 세대를 포함한다.
- 같은 슬롯의 다음 대여는 새로운 세대를 사용한다. 세대 소진 시 슬롯을 폐기해 wrap-around를 방지한다.
- 이미 반환되거나 만료된 핸들의 `recycle()`은 `false`, `close()`는 no-op이다.
- 만료·반환 후 `use()` 및 `touch()`는 명시적인 예외로 실패한다.
- 다른 Main 또는 Sub에 반환하려는 호출은 소유권 오류이며 실제 소유자의 상태를 변경하지 않는다.
- Holder가 `T`를 독점 대여한다. 서로 다른 스레드가 순차적으로 사용할 수 있지만,
  동일 Holder의 중첩·동시 `use()`는 예외로 거절한다. `T` 자체의 thread safety를 전제하지 않는다.

Sub 대여 시 `initialObjectsPerSub`개의 사용 가능한 슬롯을 원자적으로 예약한다.
부족하면 Main 전체 상한 안에서 생성한다. 일부 생성이 실패하면 Sub를 외부에 공개하지 않고,
기존 풀에서 가져온 슬롯은 되돌리고 새로 만든 객체는 정리한다. 생성 예약 용량도 정확히 반환한다.
초기 슬롯 토큰은 Sub 내부 `ArrayDeque` 기반 LIFO 스택에 push한다. 초기 개수는 고정 용량이
아니며 이후 확장할 수 있다. borrow는 worker 반환함을 먼저 반영한 뒤 스택에서 pop한다.
유효한 토큰이 없으면 `Main.newObject(sub)`로 한 개를 추가 확보하여 대여한다.
newObject는 Main idle Holder를 우선 배정하고 없으면 max 범위에서 createNewHolder를 호출한다.
Sub가 createNewHolder를 직접 호출하여 Main의 용량 예약을 우회하지 않는다.

소유 스레드가 즉시 정리할 수 있는 정상 Holder 반환은 `isRecycleable(holder)`가 true이고
`onRecycle(holder)`가 성공한 뒤 소유 Sub 스택에 push한다. Main 풀에는 등록하지 않는다.
다른 worker의 반환은 같은 Sub의 반환함에 적재하고 owner가 이를 스택으로 옮긴다.
사용 완료까지 정리를 미룬 반환도 정리 완료 스레드에 따라 동일하게 처리한다.
그 사이 Sub가 recycle되었다면 Sub 회수 절차가 정리 완료 슬롯을 Main으로 반환한다.
이는 Holder 단독 반환 경로가 아닌 종료된 Sub의 일괄 회수 후속 처리이다.
Sub가 forceClose되었거나 Main이 종료되었다면 폐기한다.
판정이 false이거나 판정/onRecycle에서 예외가 발생한 Holder는 forceClose 경로로 전환하여
`onDestroy(holder)`를 호출하고 풀에 재등록하지 않는다.

`recycle()`의 boolean은 최초 유효 반환 접수 여부이다. 이는 재사용 가능 여부를 뜻하는
`isRecycleable()`의 boolean과 별개이다. 재사용 불가로 폐기하더라도 최초 반환 접수는 true이다.
사용 중 반환되면 대여를 먼저 종료하고, 마지막 use가 끝난 뒤 판정과 훅을 실행한다.
Sub 예약 유휴 Holder는 이미 정리된 상태이므로 정상 Sub 반환에서 Main으로 이동할 때
onRecycle을 중복 호출하지 않는다. Sub forceClose에서는 소유한 모든 Holder를 폐기한다.

Sub 캐시는 객체 자체가 아닌 슬롯 ID와 세대 토큰을 보유한다. Main은 모든 예약 슬롯의
실제 소유권을 관리한다. patrol이 슬롯을 회수해도 Sub 큐는 직접 수정하지 않는다.
소유 스레드가 다음에 토큰을 꺼낼 때 Main에서 세대·Sub 유효성을 검증하며 오래된 토큰은 버린다.
Main에 반환된 슬롯이 다른 Sub로 이동한 뒤에도 이전 토큰으로 접근할 수 없다.

### force reset의 정확한 의미

만료되었다는 사실만으로 사용 중인 Java 객체를 안전하게 초기화할 수는 없다.
따라서 force reset을 수행하는 `forceClose()`는 **대여 권한 즉시 취소 + 안전한 시점에 객체 정리**로 정의한다.
제한 초과 감지 자체와 종료는 분리한다. 미지정 Listener의 기본 처리 또는 사용자 Listener의
명시적 `forceClose()` 호출이 있어야 누수 감지에 따른 무효화가 발생한다.
아래에서 만료된 핸들이란 제한 초과를 감지하기만 한 핸들이 아니라 강제 종료된 핸들이다.

1. Main 메타데이터 락 안에서 대상 ID·세대와 활성 여부를 확인하고 대여를 무효화한다.
2. 아직 시작하지 않은 사용은 차단한다. 이미 실행 중인 사용은 강제 중단하지 않는다.
3. 사용 중인 슬롯은 격리한다. 실행 중인 `use()`나 재활용 훅이 종료될 때까지 onDestroy/재대여하지 않는다.
4. 강제 종료한 Holder는 재활용 판정과 onRecycle을 건너뛰고 `onDestroy(holder)` 후 풀에서 제거한다.
5. 필요하면 이후 대여에서 새 객체를 생성한다. 이전 세대 참조는 새 객체에 영향을 주지 못한다.

일반 `get(): T`는 공개하지 않고 `use(Consumer<? super T>)` 콜백으로 사용 구간을 추적한다.
콜백의 시작·종료마다 실행 상태를 관리하고 마지막 사용 시간을 갱신한다.
콜백 밖으로 `T` 또는 내부 가변 참조를 유출하거나 콜백 내부에서 비동기 사용을 시작하는 것은
API 계약 위반이다. Java 타입 시스템만으로 이러한 유출을 완전히 방지할 수는 없다.

콜백이 영원히 끝나지 않으면 물리적 회수도 끝나지 않는다. 격리 슬롯은 계속 용량에 포함하며,
상한 도달 시 신규 borrow를 실패시킨다. 따라서 강제 회수는 메모리 무조건 회수나 외부
I/O 강제 취소를 보장하지 않는다. 외부 자원 취소 기능은 타입별 별도 계약이 필요하다.

### Sub 반환과 worker dispose의 관계

`recycleContainer()`는 Sub 대여 종료이다. 유휴 슬롯은 Main으로 반환하고,
미반환 Holder는 모두 무효화하여 위 강제 회수 경로로 처리한다.
활성 `use()`가 있으면 Sub는 REVOKED로 남고 마지막 정리 후 RELEASED가 된다.
회수 시작 시 borrowCount/recycleCount를 비교하여 다르면 WARNING 로그를 한 번 출력한다.
경고 후에도 회수는 계속하며 이미 정상 반환된 스택 및 반환함의 Holder를 Main으로 일괄 이동한다.

일반적인 비동기 시나리오는 다음 순서를 따른다.

```text
caller: Main.borrowContainer()
caller: Sub.borrowObjectHolder()
caller: ManagedPojo(holder) 생성
caller: executor를 통해 POJO를 worker에 전달
worker: holder.use(...)로 객체 사용
worker: POJO.dispose() → holder.close() → 소유 Sub의 worker 반환함
caller: 다음 Sub 연산에서 반환함을 비우고 Sub 스택에 push
caller: worker 완료 확인 → sub.close()
caller: thread finish
```

다른 스레드로의 전달은 Executor 제출 등 안전한 publication 경로를 사용한다.
worker는 Sub의 borrow/recycle/patrol/close를 직접 호출하지 않는다.
Holder는 Main의 수명 관리 상태를 참조하므로 Sub의 로컬 큐를 변경하지 않고 반환할 수 있다.
worker 반환함은 Main이 보호하는 SubState에 위치하지만 그 Holder의 소유자는 계속 해당 Sub이다.
Main idle 풀에 등록하거나 다른 Sub에 배정하지 않는다. owner는 borrow와 recycleContainer 전에
반환함을 drain하여 자기 스택에 push한다. owner가 돌아오지 않으면 Main patrol이 registry와
반환함으로 회수하며 Sub 스택에는 접근하지 않는다. 중복 반환을 차단하므로 반환함 항목 수는
해당 Sub 소유 Holder 수를 넘지 않는다.
POJO는 자신의 중복 dispose를 허용하고 Holder 종료의 멱등성도 활용한다.
반환 중 콜백이 실행되고 있다면 신규 사용만 차단하고 재활용 판정과 onRecycle은 콜백 종료 후 수행한다.

caller가 worker 완료 전에 Sub를 반환하면 worker의 이후 사용은 실패한다.
조기 Sub 반환과 worker의 계속 사용을 동시에 허용하는 drain/ref-count 모드는 초기 범위에서 제외한다.
스레드 종료 자체를 자동 감지해 반환하지는 않으며, 명시적인 close 및 patrol이 수명을 관리한다.

### 동시성 및 콜백 실행

Main별 `ReentrantLock` 하나가 Main 풀, registry, 용량, 세대, 시각, 실행 중 여부,
대여별 통계 및 worker 반환함을 보호한다.
Sub는 `ArrayDeque` 등의 일반 자료구조를 사용하며 자체 synchronized/Lock을 두지 않는다.
Sub를 빌린 스레드가 owner이며 Sub의 모든 public 연산은 이 스레드만 호출할 수 있다.
다른 스레드의 직접 호출은 owner 검사로 거절한다. 소유 스레드 이전은 초기 범위에서 제외한다.

Sub는 로컬 토큰을 읽고 쓸 때 락을 사용하지 않지만, 대여 확정·반환·유효성 검사 등
공유 수명 상태의 변경은 Main API를 경유한다. 즉 Sub에 락이 없다는 의미이며,
Sub를 통한 전체 호출 경로가 lock-free라는 의미는 아니다.
Holder의 use/recycle과 worker 반환도 Main이 보호하는 상태를 사용한다.
이 관리 상태 변경과 Main의 추상 정리 훅 호출은 Main 풀로 Holder를 반환하는 연산이 아니다.
성공한 borrow, recycle, 만료의 판정 지점은 Main 락 안의 상태 전이이다.

Main의 recycleContainer/patrol/close는 자신의 registry만으로 Sub 대여를 취소하고
슬롯을 정리한다. Sub 객체의 큐나 owner 전용 필드를 읽거나 변경하지 않는다.
owner는 다음 Sub 연산에서 무효화를 확인하고 로컬 토큰을 버린다.

createNewHolder/isRecycleable/onRecycle/onDestroy 및 Sub onBorrow, `use()` 본문,
누수 리스너는 메타데이터 락 밖에서 실행한다.
콜백 전에는 슬롯을 예약 상태로 만들고, 콜백 후에는 Main/Sub의 최신 상태를 다시 확인해
게시하거나 폐기한다. 사용 중 recycle과 patrol의 승자는 한 번만 상태를 변경하며,
정리 책임자도 한 번만 지정한다. 명시적 반환보다 먼저 forceClose가 상태를 전이하면 강제 회수 경로를 따른다.

`use()` 예외는 호출자에게 전달하고 실행 중 표시를 반드시 해제한다.
부분 변경된 객체를 재사용하지 않도록 해당 Holder도 무효화하고 객체를 폐기한다.
생성 콜백 예외는 start 또는 borrow 실패로 전달한다. 재활용/폐기 훅 및 리스너의 일반 예외는
진단에 기록하고 다른 슬롯 정리를 계속한다. JVM 치명적 오류까지 복구한다고 보장하지 않는다.
onDestroy 실패 슬롯은 재사용하지 않고 실패 누적 횟수를 기록한다. 외부 자원이 실제로
해제되었는지는 보장할 수 없으며 초기 버전은 onDestroy를 자동 재시도하지 않는다.

추상 수명 훅과 onBorrow는 유한 시간에 끝나야 하며 같은 Main에 재진입하지 않는 계약으로 한다.
재진입은 예외로 거절한다. 한 작업이 다른 작업의 종료를 락을 잡고 기다리지 않는다.
thread-safe는 관리 상태의 정합성을 의미하며 콜백의 무한 대기 해결까지 의미하지 않는다.

### patrol과 누수 감지

Main이 보호하는 SubState와 Holder 대여 상태에 각각 `borrowedAtNanos`, `lastUsedAtNanos`를 기록한다.
빌린 시점에는 두 값이 같으며 borrow 시각은 해당 대여 동안 변하지 않는다.
Holder의 `use()` 진입·종료 및 `touch()`는 Holder와 소유 Sub의 last use를 갱신한다.
Sub의 borrow/recycle holder와 Sub 자체 `touch()`도 Sub의 last use를 갱신한다.
무효화된 대여의 늦은 콜백 완료는 새 대여의 시각에 영향을 주지 않는다.

```text
expired = (borrowLimit 활성 && now - borrowedAt >= borrowLimit)
       || (idleLimit 활성   && now - lastUsedAt >= idleLimit)
```

elapsed time은 `System.nanoTime()` 기반으로 계산하며 테스트에서는 단조 시계를 주입한다.
벽시계 변경의 영향을 받지 않도록 이벤트 출력 시각과 만료 판단 시계를 분리한다.
긴 실행 중인 콜백도 제한을 넘으면 무효화될 수 있다. `touch()`는 idle 제한만 연장하고
총 borrow 제한은 연장하지 않는다. 적절한 제한값은 호출 측 작업 시간에 맞춰 설정한다.

- Main `patrol()`은 자신의 registry로 활성 Sub와 Holder 만료를 모두 검사한다. Sub 메서드를 호출하지 않는다.
- Sub `patrol()`은 owner가 호출하며, Main에 해당 Sub의 Holder 검사만 위임한다. Sub 자신의 만료 검사는 Main의 책임이다.
- Sub 누수 감지를 먼저 처리한다. Sub를 forceClose했다면 자식은 부모 회수 사유로 기록하고 개별 누수 이벤트는 추가 발행하지 않는다.
- 사용자 Listener가 Sub를 종료하지 않았다면 자식 Holder의 누수 검사도 계속 수행한다.
- 스냅샷을 찍은 뒤 이벤트 발행을 확정하기 전에 Main 락 안에서 상태·세대·시간을 재검사한다.
- 반환된 Sub의 오래된 핸들로 patrol을 호출하면 거절한다.
- 최초 구현은 등록된 대여의 전체 순회다. 비용은 활성 Sub 수 + 활성 Holder 수에 비례한다.
- 제한 초과는 누수 의심이다. 정상적인 장시간 작업도 해당할 수 있으므로 실제 누수로 단정하지 않는다.
- 이벤트에는 대상 종류(Sub/Holder), 대상/부모 ID, 세대, 사유, 감지 시각, 대여 경과/유휴 시간을 포함한다.
  객체 데이터는 기록하지 않고 대여 스택 수집은 선택 기능으로 기본 비활성화한다.

자동 patrol은 Main당 선택적인 단일 daemon scheduler가 `scheduleWithFixedDelay`로 실행한다.
동시 수동 patrol은 검사 진행 중이면 skipped 결과를 반환하여 스캔을 겹치지 않는다.
일반 예외는 실행 경계에서 진단 처리해 다음 주기 실행이 유지되도록 한다.
별도 무제한 cleanup 큐는 만들지 않는다. 정리 콜백이 오래 걸리면 patrol도 늦어질 수 있으며,
만료 검출은 정밀 타이머 보장이 아닌 best-effort 주기 검사이다.

### LeakListener 생성자 파라미터와 기본 처리

Main 생성자는 다음 두 형태를 제공한다. 테스트/외부 단조 시계를 위한 LongSupplier 주입 생성자도 제공한다.

```java
protected MainContainer(ContainerConfig config);
protected MainContainer(ContainerConfig config, LeakListener leakListener);
```

Listener 타입은 `LeakListener`이며 `void onLeak(LeakEvent event)`를 제공한다.
생략하거나 null을 전달하면 기본 Listener인 `event -> event.forceClose()`를 사용한다.
Listener를 지정한 경우에는 사용자 Listener만 호출하며 반환 후 자동 forceClose를 추가하지 않는다.
로그만 남기거나, 자체 판단 후 `event.forceClose()`를 호출할 수 있다.

`LeakEvent.forceClose()`는 감지 당시의 대상과 세대에 바인딩된 Main 관리 작업이다.
Sub 감지이면 해당 Sub 대여와 그 자식을 종료하고, Holder 감지이면 해당 Holder만 종료한다.
Main 전체를 종료하지 않으며 non-thread-safe Sub의 public 메서드도 직접 호출하지 않는다.
실제로 무효화한 경우 true, 이미 반환·종료되었거나 세대가 바뀐 경우 false를 반환한다.
늦은 호출이나 중복 호출이 새 대여에 영향을 주어서는 안 된다.
명시적인 종료 요청이므로 감지 후 touch로 idle 시간이 갱신되어도 같은 대여가 활성 상태라면 종료한다.

Listener는 patrol을 실행하는 스레드에서 Main 락 밖으로 호출한다.
이벤트의 종료 작업은 thread-safe이므로 다른 worker에 전달해 나중에 호출해도 된다.
Listener에서 `event.forceClose()`를 호출하는 것은 허용한다. 추상 수명 훅의 재진입 금지와 구분한다.
중첩 patrol은 기존 skipped 규칙을 따른다. Listener 예외는 진단하고 다른 대상 검사를 계속하며,
사용자 Listener의 예외를 이유로 강제 종료 정책을 임의로 추가하지 않는다.

같은 대상·세대에 대해서는 한 patrol에서 한 번만 알린다. Listener가 종료하지 않았거나
예외를 던진 경우, 다음 patrol에서도 제한을 초과하면 다시 알린다.
이미 감지한 대여가 이벤트 전달 전에 반환될 수 있으므로 이벤트는 감지 시점의 기록이며,
종료 성공 여부는 `forceClose()` 반환값으로 판단한다.
Main/Sub의 명시적 close 및 객체 사용 실패에 따른 폐기는 누수 Listener로 지연하거나 막지 않는다.

### 공개 API 방향

공개 API 계약은 다음과 같다.

| 타입 | 메서드 | 계약 |
| --- | --- | --- |
| `abstract MainContainer<T>` | `protected MainContainer(config)` | 설정 저장, 기본 누수 처리로 감지 대상 forceClose |
| | `protected MainContainer(config, leakListener)` | 사용자 Listener 주입, null이면 기본 처리 |
| | `final start()` | initial개 Holder 생성 완료 후 OPEN으로 전환 |
| | `protected abstract ObjectHolder<T> createNewHolder()` | 독립적인 새 Holder와 내부 객체 생성 |
| | `protected abstract boolean isRecycleable(ObjectHolder<T> holder)` | 정상 반환 Holder의 재사용 가능 여부 판정 |
| | `protected abstract void onRecycle(ObjectHolder<T> holder)` | 재사용할 Holder 수준의 리소스 정리 |
| | `protected abstract void onDestroy(ObjectHolder<T> holder)` | 재사용하지 않을 Holder의 최종 리소스 해제 |
| | `protected createSubContainer()` | 새 Sub 생성 확장 지점, onBorrow 재정의에 사용 |
| `MainContainer<T>` | `borrowContainer()` | 기본 객체 수를 확보한 새 Sub 핸들 반환 |
| | `newObject(SubContainer<T>)` | 유효한 Sub에 Holder 추가 배정, idle 부족 시 createNewHolder 호출 |
| | `recycleContainer(SubContainer<T>)` | 통계 비교·불일치 경고 후 일괄 반환, 최초 유효 반환이면 true |
| | `patrol()` | 검사·만료·격리·정리·실패 수와 skipped 여부 반환 |
| | `stats()` | 락 아래 일관된 관리 상태 snapshot 반환 |
| | `close()` | 신규 대여 차단, 모든 Sub 무효화, 유휴 객체 정리, scheduler 취소 |
| `SubContainer<T>` | `protected void onBorrow()` | Main에서 이 Sub를 대여할 때 호출하는 훅, 기본 no-op |
| | `HolderLease<T> borrowObjectHolder()` | owner 전용, 반환함 drain 후 스택 pop, 부족하면 Main.newObject |
| | `recycleObjectHolder(HolderLease<T>)` | owner 전용, 판정·정리 성공 후 Sub 스택 push |
| | `stats()` | 현재 Sub 대여의 borrow/recycle 및 정리 상태 snapshot |
| | `touch()`, `patrol()`, `close()` | owner 전용, Main에 활동 기록/자식 검사/반환 위임 |
| `HolderLease<T>` | `use(Consumer<? super T>)` | 유효성 검사 후 사용 구간 추적 |
| | `touch()`, `recycle()`, `close()` | Main 경유, worker에서도 사용 가능, 멱등 반환, AutoCloseable 지원 |
| `LeakListener` | `onLeak(LeakEvent)` | 감지 이벤트 처리, 필요 시 event.forceClose 호출 |
| `LeakEvent` | `forceClose()` | 감지 대상·세대만 Main을 통해 멱등 강제 종료 |

`createNewHolder()`의 null은 생성 실패이다. `onRecycle(holder)`은 다음 대여자가 사용할 수 있도록
이전 대여의 데이터와 임시 리소스를 정리한다. `onDestroy(holder)`는 최종 해제이며 메모리 객체만
보유했다면 no-op이어도 된다. 각 실제 Holder에 대한 onDestroy는 최대 한 번 시도한다.
`use()`로 만든 불변 결과는 호출자 소유이며 내부 가변 참조를 외부에 보존해서는 안 된다.

Main `close()`는 진행 중인 사용자 콜백의 완료를 기다리지 않는다. 호출 시 정리 가능한
유휴 객체의 onDestroy는 실행하므로 항상 즉시 반환하는 API는 아니다.
나중에 끝난 작업이 자신의 정리를 완료하며 CLOSED Main에 자원을 다시 등록하지 않는다.
`stats()`는 종료 후에도 격리·정리 중 개수를 확인할 수 있다.

### 설정과 용량 제한

initial/min/max는 **Main 전체의 실제 `ObjectHolder<T>` 인스턴스 수**이다. Holder는 T 하나를 관리한다.
대여 핸들 수는 풀 용량에 중복 산입하지 않는다. Sub 개수와 Sub별 초기 배분 개수는
별도 설정이다. min은 최소 idle 개수가 아니라 최소 전체 보유 개수로 정의한다.
따라서 모든 객체가 대여 중이어도 min을 만족할 수 있으며 idle 확보를 위해 max를 넘지 않는다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `initial` | 16 | start 시 createNewHolder로 미리 생성할 Holder 수 |
| `min` | 8 | Main이 유지하려는 전체 객체 수의 하한 |
| `max` | 1,024 | 생성 예약·Main/Sub 유휴·대여·정리·격리 슬롯을 모두 포함한 상한 |
| `initialObjectsPerSub` | 8 | Sub마다 확보할 초기 객체 수, 0 이상 |
| `maxSubContainers` | 128 | ACTIVE 및 정리 미완료 REVOKED Sub 합계 상한 |
| `poolIdleLimit` | 비활성 | 활성화 시 오래 유휴인 Main 풀 객체를 min까지 축소 |
| `subBorrowLimit`, `subIdleLimit` | 비활성 | Sub 총 대여/유휴 제한 |
| `holderBorrowLimit`, `holderIdleLimit` | 비활성 | Holder 총 대여/유휴 제한 |
| `patrolInterval` | 1초 | 자동 patrol 활성 시 fixed delay |
| `automaticPatrol` | false | 명시적 활성화 필요 |
| `captureBorrowStack` | false | 진단 비용 때문에 선택 기능 |

시간 제한은 업무 시간 정보가 없으므로 임의의 강제 만료값을 기본 활성화하지 않는다.
자동 patrol은 누수 검사 외에도 min 보충 및 선택적인 Main 유휴 풀 축소를 수행할 수 있다.
활성 Duration은 양수이고 nanos로 안전하게 변환 가능해야 한다. 비활성은 별도로 표현한다.
`0 <= min <= initial <= max`, `max > 0`을 만족해야 한다.
Sub 상한은 양수이고 Sub 초기 배분 개수는 0 이상 max 이하여야 한다.
`initialObjectsPerSub × maxSubContainers` 전체를 미리 생성하지 않는다.

Main 객체 상한 또는 Sub 상한 도달은 `PoolExhaustedException`으로 즉시 실패한다.
추가 생성 실패는 createNewHolder의 원인을 보존한 예외로 구분한다.
만료로 격리한 객체를 무조건 새 객체로 대체하여 상한을 우회하지 않는다.
슬롯 수 상한은 객체 그래프의 byte 상한이나 외부 자원 수의 절대 상한을 뜻하지 않는다.

### 추상 훅과 크기 조절 절차

구체 Main 하위 클래스에서 네 개의 추상 훅을 구현한다. Supplier와 ObjectLifecycle 파라미터는 제거한다.
설정과 선택적인 Listener만 상위 생성자에 전달한다. 기본 클래스의 생성자에서는 추상 메서드를
호출하지 않는다. 하위 클래스 생성 완료 후 호출자가 `start()`를 실행하고 Main을 공유한다.
start 전의 borrow/patrol은 거절한다. OPEN에서 start 재호출은 no-op, STARTING 중 재호출은
명시적인 상태 예외, CLOSED에서는 재시작을 거절한다. 자동 patrol은 OPEN 이후 시작한다.

createNewHolder는 매 호출마다 독립적인 새 Holder 및 T를 생성한다. 공유 singleton 반환은
계약 위반이다. 다른 Holder에 대한 훅은 동시에 호출될 수 있으므로 구현체의 공유 상태는
thread-safe여야 한다. 같은 Holder의 use/재활용/폐기 훅은 겹치지 않게 Main이 보장한다.

1. 생성자에서 설정을 검증하고 start에서 `initial`개를 생성한다. 하나라도 실패하면 생성된 Holder를
   onDestroy로 정리하고 CLOSED로 전환하여 start를 실패시킨다. 부분 초기화한 Main에서는 대여하지 않는다.
2. Sub 배분 또는 추가 요청 시 Main idle 객체를 먼저 사용한다.
3. idle이 부족하면 Main 락 안에서 부족한 수만큼 생성 용량을 예약한다.
   `현재 슬롯 수 + 생성 예약 수 <= max`를 유지하고 락 밖에서 createNewHolder를 호출한다.
4. 생성 성공 시 락 안에서 OPEN 상태와 요청한 Sub의 유효성을 재검사하여 객체를 배정한다.
   Sub가 종료되었으면 Main idle로 보내고 Main이 종료되었으면 폐기한다.
5. 생성 실패 시 해당 예약을 해제하고 원인을 보존하여 호출자에게 전달한다.
   복수 객체 배분의 부분 실패는 해당 배분 전체를 rollback한다.
6. 객체 폐기 완료 후 보유 수가 min 미만이면 가능한 부족분을 같은 예약 절차로 한 번 보충한다.
   실패 시 진단하고 다음 borrow 또는 patrol에서 재시도한다. 무한 재시도는 하지 않는다.
7. min은 생성 실패와 정리 진행 중에는 일시적으로 미달할 수 있는 유지 목표이다.
   격리 객체는 용량에 포함되며, max를 우회한 보충을 하지 않는다. CLOSED에서는 보충하지 않는다.
8. poolIdleLimit이 활성화된 경우 patrol은 오래된 Main idle 객체만 폐기한다.
   min 아래로 줄이지 않도록 축소 예정 수를 Main에서 예약하고, 실패·격리 슬롯의 종료와
   경쟁해 일시적으로 min 미만이 되면 위 보충 절차를 적용한다. Sub 예약 객체는 축소 대상이 아니다.

`stats()`에는 initial/min/max, 생성 예약, Main idle, Sub 스택, Sub 반환함, 대여, 정리, 격리 수를 노출한다.
모든 상태 카운터는 서로 중복되지 않으며 합계가 max 이하인지를 확인할 수 있어야 한다.

### recycle 및 onBorrow 실행 순서

```text
HolderLease.recycle()
  → Main 관리 상태에서 대여 종료·recycleCount 증가 및 정리 책임 확정 (Main 풀 반환 아님)
  → 진행 중 use가 끝나면 실제 ObjectHolder에 isRecycleable(holder)
      true  → onRecycle(holder) → 상태 재검사 → 소유 Sub 스택에 push
                worker에서 완료하면 Sub 반환함 → owner가 스택에 push
      false → forceClose → onDestroy(holder) → 재활용 대상에서 제외

forceClose (누수, 사용 실패, Main 종료 등)
  → 즉시 대여 권한 취소 → 진행 중 작업 완료 → onDestroy(holder)

Main.borrowContainer()
  → Sub 대여 및 기본 Holder 배분 준비
  → caller/owner 스레드에서 sub.onBorrow() 한 번 호출
  → Main 상태 재검사 → Sub를 caller에게 반환

Sub.borrowObjectHolder()
  → worker 반환함 drain → 내부 스택 pop
  → 비어 있으면 Main.newObject(sub)로 충당 → borrowCount 증가 → 대여 핸들 반환

Sub.close() / Main.recycleContainer(sub)
  → Main에서 Sub 대여 종료와 통계 snapshot 확정
  → borrowCount != recycleCount 이면 warning
  → Sub 스택/반환함의 정리된 Holder를 Main으로 일괄 반환
  → 미반환 대여는 강제 종료, 정리 진행 중인 정상 반환은 완료 후 회수
```

isRecycleable은 부수 효과 없는 판정이며 false 경로에서는 onRecycle을 호출하지 않는다.
onRecycle은 Holder가 제공하는 정리 연산을 호출하는 확장 지점이다. 기본 클래스가 별도의 reset을
추가 실행하지 않는다. onDestroy도 실제 리소스 해제를 담당하며 기본 클래스는 풀 제거와 회계를 담당한다.
판정/onRecycle 예외도 onDestroy 경로로 보낸다. onRecycle 도중 forceClose 또는 Main close가
발생하면 onRecycle 종료 후 onDestroy를 실행하며 유휴 풀에 게시하지 않는다.

Sub의 onBorrow는 Holder 대여마다 실행하는 훅이 아니라 Sub 대여마다 한 번 실행하는 훅이다.
Sub의 owner와 대여 정보 및 초기 배분을 설정한 뒤 실행하되, 성공 전에는 Sub를 외부에 반환하지 않는다.
이 훅에서는 Sub의 대여별 상태를 준비한다. 준비 중 Sub의 borrow/recycle/patrol 등 공개 수명 연산은 거절한다.
훅 실패 또는 그 사이 Main 종료 시 Sub 대여를 rollback하고 해당 준비에 배정한 Holder를
onDestroy 경로로 정리한다. 호출자는 원인 예외를 받고 부분 초기화된 Sub를 받지 않는다.

### Sub borrow/recycle statistics 및 warning

통계는 Sub의 한 대여 기간을 기준으로 0부터 시작한다. worker도 반환할 수 있으므로
카운터는 Main이 보호하는 SubState에서 수명 전이와 함께 갱신한다. Sub 스택에는 락을 추가하지 않는다.

| 항목 | 증가 시점 |
| --- | --- |
| `borrowCount` | 유효한 Holder 대여가 성공할 때마다 1 증가 |
| `recycleCount` | 아직 유효한 대여의 최초 정상 recycle 접수 시 1 증가 |
| `forcedCloseCount` | 정상 recycle 없이 활성 대여를 강제로 끝낸 경우 1 증가 |
| `outstandingCount` | 현재 활성 Holder 대여 수 |
| `pendingCleanupCount` | 정상 반환 접수 후 use 종료나 정리 훅 완료를 기다리는 수 |

초기 배분·추가 생성·스택 push/pop 자체는 카운터를 중복 증가시키지 않는다.
중복 recycle, 다른 소유자 반환, 이미 강제 종료된 대여의 늦은 recycle은 recycleCount에 포함하지 않는다.
정상 recycle 후 isRecycleable=false 또는 정리 실패로 폐기한 경우에도 recycleCount는 유지한다.
이 경우는 정상 반환 접수 뒤 폐기한 것이므로 forcedCloseCount에는 중복 산입하지 않는다.

대여 회계는 `borrowCount = recycleCount + forcedCloseCount + outstandingCount`를 만족한다.
Sub recycle 시 Main 락 안에서 신규 대여를 막는 상태 전이와 통계 snapshot을 함께 확정한다.
그 직전까지 접수된 worker 반환은 포함하고, 그 이후의 미반환 대여는 회수 대상이므로
늦은 반환으로 확정한 통계나 로그를 바꾸지 않는다. 경고는 Main 락 밖에서 기록한다.

`borrowCount != recycleCount`이면 Sub ID·대여 세대·두 카운터·차이·강제 종료 수·미반환 수·정리 대기 수를
`System.Logger`의 WARNING 수준으로 한 번 기록한다. 별도 로깅 의존성은 추가하지 않는다.
Listener 지정 여부와 관계없이 통계 불일치 경고를 출력한다. 경고는 회수를 막지 않는다.
이미 patrol이 강제 종료한 대여도 불일치에 포함되므로 이를 새 누수라고 단정하지 않는다.
반환 횟수가 같아도 정리 대기가 남을 수 있으며, 이를 이유로 사용 중인 자원을 재배정하지 않는다.
두 카운터가 같으면 이 통계 경고는 출력하지 않는다. 중복 Sub recycle에서도 경고를 재출력하지 않는다.

### 구현 시 검증할 수용 조건

1. 초기 Holder 개수, 추가 대여, onRecycle 정리, Sub 반환 후 다른 Sub의 재사용을 검증한다.
2. caller borrow → worker use/dispose → caller Sub 반환을 latch로 제어해 검증한다.
3. 중복 close, 다른 소유자 반환, 만료 후 use, 오래된 핸들의 재반환이 새 대여에 영향을 주지 않는다.
4. 가짜 시계로 두 계층의 borrow/idle 경계(`>=`), touch, 비활성 제한을 검증한다.
5. 기본 Listener에서 실행 중 use와 patrol을 겹쳐 즉시 권한 취소되지만 콜백 종료 전 정리 훅이 호출되지 않음을 검증한다.
6. recycle/patrol/Sub close/Main close 경쟁에서도 슬롯의 중복 대여·중복 정리가 없다.
7. 생성 부분 실패, 판정/onRecycle/onDestroy 실패, 리스너 실패 뒤 회계와 후속 patrol을 검증한다.
8. 정리 중 Main 종료, worker 늦은 반환, 콜백 예외, 동시 use 거절을 검증한다.
9. 생성 예약과 격리까지 포함한 상한을 검증하고, latch로 막은 콜백은 테스트 종료 시 풀어준다.
10. 동시 부하 후 전체 슬롯 수가 상태별 합계와 일치하고 활성 대여·정리·격리가 0인지 확인한다.
11. Sub를 다른 스레드에서 직접 호출하면 거절되고 worker Holder 반환은 해당 Sub 반환함에 전달된다. Sub 회수 전 Main idle 수는 증가하지 않는다.
12. patrol이 Sub 예약 슬롯을 회수·재배정한 후 owner가 오래된 로컬 토큰을 사용해도 새 대여에 접근할 수 없다.
13. initial 선생성 수, min 보충, min까지의 유휴 축소, 설정 범위 오류를 검증한다.
14. 동시 createNewHolder 호출과 부분 실패·Main 종료가 겹쳐도 생성 예약 포함 max를 초과하지 않는다.
15. Listener 생략/null 시 Sub 및 Holder 감지 대상만 forceClose하고 Main은 계속 사용할 수 있다.
16. 사용자 Listener의 관찰만 수행하는 경우 자동 종료하지 않으며, 명시적 forceClose 시에만 강제 종료한다.
17. Listener 예외와 다음 patrol 재알림, 늦은 이벤트 처리, 중복 forceClose가 수명 정합성을 훼손하지 않는다.
18. 상위 생성자에서 추상 훅이 호출되지 않고 start에서 initial개 생성, 실패/종료 경쟁 시 전부 정리되는지 검증한다.
19. isRecycleable true는 onRecycle 후 재등록, false는 onRecycle 없이 onDestroy 후 제외됨을 검증한다.
20. 중복 반환과 forceClose 경쟁에서 훅 호출이 중복되지 않으며 onRecycle과 onDestroy가 동시에 실행되지 않는다.
21. Sub 대여당 onBorrow 한 번, Holder 대여에서는 미호출, 훅 실패 시 rollback을 검증한다.
22. 초기 개수로 스택이 준비되고 LIFO pop/push로 재사용되며, 스택/반환함 소진 시에만 Main.newObject를 호출한다.
23. Holder 정상 반환에서는 Main 풀로 이동하지 않으며 Sub recycle에서 일괄 반환되는지 검증한다.
24. borrow/recycle 일치 시 무경고, 불일치 시 정확한 수치의 warning 한 번, 중복/잘못된 반환 시 통계 불변을 검증한다.
25. worker 반환과 Sub 회수를 경쟁시켜 통계 snapshot·반환함·정리 대기 Holder의 중복 이동 및 누락이 없음을 검증한다.

성능 검토는 실제 유틸리티 구현 후 직접 할당과 비교한다. 단순 POJO와 생성 비용이 큰 객체,
동시 스레드 수별 처리량·지연·할당량·GC를 측정한 뒤 락 분리 필요성을 결정한다.
수용 조건은 아래 구현 검증 기록과 테스트 클래스에서 검증한다. 실제 할당/GC/경합 벤치마크는 아직 수행하지 않았다.

### 설계 판단 요약 및 참고

구현의 핵심 선택은 독립 library JAR, thread-safe Main과 락 없는 owner 전용 Sub,
Main의 추상 생성/판정/정리 훅, initial/min/max, Sub onBorrow, 대여별 새 핸들과 만료 객체 폐기이다.
이번 구현 요청에 따라 모듈 생성과 구현·수명 검증까지 수행하였다.

시간 측정과 scheduler 동작의 근거는 Java 21 공식 문서를 따른다.

- [System.nanoTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()): 경과 시간 측정용이며 벽시계와 무관하다.
- [ScheduledExecutorService](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html): 상대 지연 기반 주기 실행에 사용한다.

### 구현 파일과 반환 동작

| 파일 | 책임 |
| --- | --- |
| `MainContainer.java` | 풀/registry/용량 예약, 세대 검증, 락 밖 훅 실행, 일괄 회수, patrol와 scheduler |
| `SubContainer.java` | owner 검사와 LIFO 스택, Main 관리 상태에 대한 위임, onBorrow 확장 |
| `ObjectHolder.java`, `HolderLease.java` | 재사용하는 실제 객체와 재사용하지 않는 대여 핸들 분리 |
| `ContainerConfig.java` | 불변 설정, builder와 범위/Duration 검증 |
| `LeakEvent.java`, `LeakListener.java` | 감지 snapshot과 대상·세대에 바인딩된 thread-safe forceClose |
| `ContainerStats.java`, `SubStats.java`, `PatrolResult.java` | 불변 회계·검사 결과 |
| `PoolExhaustedException.java`, `ObjectCreationException.java` | 용량 부족과 생성 실패 구분 |

ObjectPool/SubState는 Main 내부 map/deque/slot 상태로 구현하여 별도의 공개 타입을 늘리지 않는다.
`Main.recycleContainer(sub)`는 어느 스레드에서든 registry만으로 회수한다. Sub 메서드를 호출하거나
Sub 스택을 읽거나 비우지 않는다. 소유 스레드의 `Sub.close()`가 자신의 로컬 토큰을 비운다.
정상 반환 시 반환함과 스택에 해당하는 슬롯을 registry에서 직접 일괄 회수하므로
반환 직전 worker 반환함을 로컬 스택으로 옮기는 불필요한 작업은 생략한다.

Main 통계의 `total`은 현재 슬롯 수이며 `total + creating <= max`이다.
`mainIdle + subStack + subReturns + borrowed + recycling + quarantined + destroying == total`이다.
`destroying`은 사용이 끝나 onDestroy 실행 중인 슬롯이며 용량에서 아직 제외하지 않는다.
`activeSubs`에는 공개 전 PREPARING도 포함하고, `revokedSubs`는 진행 중 사용/정리/생성 때문에
등록 해제를 기다리는 Sub 수이다. `destroyedCount`는 최종 폐기 시도 수이며 실제 외부 자원 해제 성공을 보장하지 않는다.

`PatrolResult.forcedClosed`는 이번 감지 이벤트 중 리스너 처리 동안 forceClose에 성공한 대상 수이다.
Sub 회수에 딸린 자식은 별도 이벤트/대상 수로 중복 집계하지 않는다. `quarantined`는 검사 종료 시점의
격리 슬롯 수이며 `cleaned`, `failures`는 검사 전후 Main 카운터 차이이다. 동시에 처리되는 다른 작업의
정리도 이 카운터 차이에 포함될 수 있다. 저장해 둔 이벤트를 검사 종료 뒤 실행한 회수는 해당 결과에 포함하지 않는다.

### 사용 예제

```java
import com.inzent.apim.utilities.recycle.*;
import java.util.concurrent.Executors;

final class BuilderPool extends MainContainer<StringBuilder> {
    BuilderPool() {
        super(ContainerConfig.builder()
                .initial(16).min(8).max(128).initialObjectsPerSub(4).build());
    }
    @Override protected ObjectHolder<StringBuilder> createNewHolder() {
        return new ObjectHolder<>(new StringBuilder(256));
    }
    @Override protected boolean isRecycleable(ObjectHolder<StringBuilder> holder) {
        return holder.value().capacity() <= 4096;
    }
    @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) {
        holder.value().setLength(0);
    }
    @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
        // 외부 자원이 없으므로 별도 해제 불필요.
    }
}

// caller 스레드:
try (var main = new BuilderPool(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
    main.start(); // 하위 클래스 생성 완료 후 명시적으로 초기화한다.
    try (var sub = main.borrowContainer()) {
        var lease = sub.borrowObjectHolder();
        workers.submit(() -> {
            try (lease) {
                lease.use(value -> value.append("worker payload"));
            } // worker 반환은 소유 Sub의 반환함으로 전달된다.
        }).get(); // 작업 완료 후 caller가 Sub를 반환한다.
    }
}
```

Managed POJO의 dispose/close에서는 저장한 `HolderLease.close()`를 호출하면 된다.
중복 dispose는 안전하다. worker에게 Sub의 public 메서드를 직접 호출하게 하지 않는다.
가변 `T` 참조를 use 밖에 저장하지 않으며, 필요하면 콜백 안에서 불변 결과를 복사한다.

제한 비활성은 Duration의 `null`로 표현한다. `automaticPatrol` 기본값은 false이며,
활성화할 때 `holderBorrowLimit`/`holderIdleLimit`/`subBorrowLimit`/`subIdleLimit`을 업무 시간에 맞춰 지정한다.
제한이 없어도 자동 patrol은 min 보충과 선택적 poolIdleLimit 축소를 수행한다.
리스너를 생략하거나 null로 주면 감지 대상을 forceClose하고, 사용자 리스너를 주면 관찰만으로는 종료하지 않는다.

### 빌드와 검증

프로젝트 루트에서 실행한다.

```powershell
mvn -f object-recycle-container/pom.xml clean verify
```

런타임 외부 의존성은 없고 테스트에는 JUnit Jupiter 5.13.4를 사용한다.
빌드 산출물은 `object-recycle-container/target/object-recycle-container-1.0.0-SNAPSHOT.jar`이다.
테스트 로그의 Sub 통계 불일치 WARNING은 의도적으로 강제 회수한 시나리오의 정상 진단이다.

검증 테스트 구성:

2026-10-11 최종 검증: Java 21.0.3 / Maven 3.9.11에서
`mvn -f object-recycle-container/pom.xml clean verify` 실행 결과 **BUILD SUCCESS**.
총 44개 테스트, 실패 0, 오류 0, 건너뜀 0이며 library JAR 생성까지 확인하였다.

- `MainContainerTest`: 초기화, 정상 재사용, worker 반환, 다른 소유자, 만료 핸들,
  사용 중 반환/회수, 동시 use, 사용 실패, start 실패, patrol 집계와 Main 타 스레드 회수.
- `ContainerLifecycleTest`: 설정 오류, 부분 할당 rollback, 판정/정리/폐기 실패,
  min 보충 재시도, Main idle 축소, 두 계층 시간 제한/비활성/단조 시계 wrap,
  사용자 리스너/지연 이벤트, onBorrow, 잘못된 팩토리, 경고 횟수/수치, LIFO 추가 배정.
- `ContainerConcurrencyTest`: 생성/초기화/onBorrow/사용/정리 중 종료,
  미완료 회수의 객체·Sub 상한, 동시 생성 예약, 메타데이터 락 밖 훅, 중첩/동시/자동 patrol,
  worker 반환/일괄 회수 경쟁, rollback 이후 다른 Sub로 재배정된 슬롯 보호,
  min 생성 부분 실패와 대여의 경쟁, 8개 스레드 × 200회 부하 및 종료 회계.

동시성 테스트는 latch/barrier로 실행 순서를 제어하며 sleep에 의존하지 않는다.
공개 API 사이의 매우 짧은 예약/rollback 경쟁 한 건은 테스트에서 private 락을 교체해
예약 트랜잭션 직후에만 정지한다. 프로덕션에 테스트 전용 훅은 추가하지 않았다.
