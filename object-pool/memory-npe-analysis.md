# Object Pool 메모리 누수 및 NPE 개연성 분석

- 분석일: 2026-10-20
- 범위: `object-pool`의 MainPool, SubPool, ObjectHolder, PooledEntry, SubState 및 설정·상태 타입
- 기준: 현재 작업 디렉터리 소스. 커밋되지 않은 신규 모듈이므로 특정 commit SHA의 분석 결과는 아니다.
- 수행 범위: 정적 참조·상태 전이 검토, 기존 테스트 실행, Java 17 독립 재현 프로그램, 독립 리뷰
- 변경 범위: 분석 문서와 독립 probe만 추가했다. 운영 소스와 기존 테스트는 수정하지 않았다.
- 후속 상태: 사용자 “분석적용” 요청으로 M-01을 수정했다. 위 변경 범위와 1–6절 재현 결과는 최초 분석 시점의 기록이며, 현재 결과는 7절을 따른다.
- 최근 재분석: 독립 Sub 컨테이너·초과 생성 정책 이후의 내부 저장 공간 및 NPE 재검증·수정은 10절을 따른다. 현재 API·정책은 후속 변경 절을 함께 참고한다.

## 1. 결론

**최초 분석에서 종료 진단 로깅 실패가 실제 자원 정리를 중단시키는 결함 1건을 재현했다.** 수정 전에는 로깅 처리에서 NPE가 발생한 후 Main.close를 재호출해도 DESTROYING 객체가 남아 CLOSING에서 종료되지 않았다. 해당 결함은 후속 요청으로 수정했으며 7절에 결과를 기록한다. 일반적인 동시 실행만으로 발생하는 라이브러리 내부 NPE는 확인하지 못했다.

정상 반납·종료 경로에서는 Holder/Sub의 Main 참조와 폐기 entry의 target 참조가 해제된다. 다만 미반납 Holder, 끝나지 않는 훅, 캐시된 target의 내부 버퍼 및 외부 자원 해제 실패는 별도 운영 위험이다. 객체 수 상한은 메모리 바이트 수의 상한이 아니다.

기존 24개 테스트 통과와 정상 참조 해제만으로 아래의 로깅 실패 경로가 검증되었다고 볼 수 없다.

## 2. 확인된 결함

### M-01 / P1 / 조건부 재현: 종료 로깅 예외로 정리 작업 유실

**수정 전 위치:** `MainPool.java`의 closeSubLocked 223–237행, close 299–309행. 아래 설명과 행 번호는 최초 분석 시점의 구현 기준이다.

closeSubLocked는 idle entry를 DESTROYING으로 전환해 지역 cleanup 목록에 넣은 뒤, outstanding Holder가 있으면 234–236행에서 System.Logger를 호출한다. 이 호출은 Main 락을 보유한 상태이며 예외 방어가 없다. 로깅에서 RuntimeException/NPE가 나오면 Main.close는 309행의 destroyAll에 도달하지 못한다.

이미 Main은 CLOSING이고 idle entry는 DESTROYING이다. 후속 Main.close는 303행에서 즉시 반환하므로 유실된 정리 작업을 회수하지 못한다. 남은 Holder를 정상 반납해도 앞서 DESTROYING으로 바뀐 idle target은 registry에 계속 남는다. 순회가 중단된 뒤의 다른 Sub/idle도 정리가 지연될 수 있다.

**재현 조건:** JDK 기본 System.Logger의 JUL 연결에 사용자 Handler를 등록하고, publish에서 NPE를 발생시킨다. 실제 운영 로거가 항상 실패한다는 의미는 아니다. 사용자 로깅 확장 또는 로깅 시스템 장애가 정리 수명으로 전파되는 조건부 결함이다.

**Java 17 재현 순서:**

1. 객체 2개를 한 Sub에서 대여하고 하나를 반납한다. borrowed=1, Sub idle=1이다.
2. `java.util.logging.Logger.getLogger(MainPool.class.getName())`에 NPE를 던지는 Handler를 등록한다.
3. Main.close를 실행한다. idle 객체는 DESTROYING이 되지만 경고 출력에서 NPE가 전파된다.
4. 실패 Handler를 제거하고 남은 Holder를 반납한다. Main.close와 Sub.close를 재호출한다.
5. `state=CLOSING, createdObjectCount=1, destroyingObjectCount=1`, 실제 폐기 훅 호출=1이 남는다. idle 객체의 폐기 훅은 한 번도 호출되지 않는다.

**참조 경로:** 호출자가 보관한 Main → entries/targets → 미정리 PooledEntry/target. Main이 살아 있는 동안 target이 유지된다. 이 예제에서 모든 외부 참조까지 해제하면 Java 객체의 GC 가능성은 달라지지만, 외부 자원의 명시적 close 누락은 해결되지 않는다.

**중요도 판단:** P1은 종료 정리 보장 훼손과 외부 자원 잔존의 영향 기준이다. 발생 조건은 실패하는 사용자 로깅 설정이며 정상 기본 로깅에서의 높은 발생 확률을 뜻하지 않는다.

**권장 수정 방향 (이번 분석에서는 미적용):**

- 락 안에서는 경고 데이터를 snapshot으로 수집하고 외부 로깅 호출은 락 밖에서 수행한다.
- 로깅 실패와 실제 cleanup 실행을 분리해 진단 오류가 destroyAll을 건너뛰지 못하게 한다.
- 한 정리 경로의 실패에도 다른 Sub와 대상 정리를 계속 수행하고 오류를 집계한다.
- CLOSING 재호출이 진행 중 정리와 중복 폐기를 만들지 않으면서 미착수 정리를 회수할 수 있는지도 검토한다.
- 현재 probe를 수정 전 실패/수정 후 통과하는 종료 보장 회귀 테스트로 전환한다. NPE뿐 아니라 RuntimeException/Error 진단 실패도 포함한다.

## 3. 누수와 구분해야 하는 참조 유지

| 항목 | 참조 경로·관찰 | 판단 및 영향 |
| --- | --- | --- |
| 미반납 Holder | Main.entries → entry → holder, owner → Sub.handle. Holder/Sub도 Main을 유지 | 설계상 유지. Main.close만으로 강제 해제하지 않아 CLOSING과 자원 점유가 무기한 지속될 수 있음 |
| 열린 idle 캐시 | Main.targets/entries → target. Sub queue는 entry ID만 보관 | 의도된 재사용. maxSize로 수량은 제한하나 T의 내부 버퍼 증가량은 제한하지 않음 |
| 끝나지 않는 생성 훅 | 호출 스택 → Main 및 생성 중 자원, reservedCreationCount 유지 | 훅이 끝나야 취소·정리 가능. 이후 close도 강제 취소하지 않음 |
| 끝나지 않는 폐기 훅 | 호출 스택과 registry → DESTROYING target | 실패를 던지는 훅과 달리 finally가 아직 실행되지 않으므로 CLOSING 지속 |
| destroy 훅의 자원 해제 실패 | entry 참조는 해제되지만 실제 연결/파일 등 외부 자원 해제 여부는 훅에 달림 | CLOSED/created=0이어도 실제 해제 성공을 뜻하지 않음. 자동 재시도 없음 |
| 종료 핸들 장기 보관 | Holder는 식별자만, Sub는 SubState·최종 snapshot·비어 있는 deque를 보관 | 정상 최종 반환 후 Main/target 직접 유지 없음. 핸들 목록을 무한 보관하면 메타데이터 자체는 증가 |
| 컬렉션 최고 용량 | 비워진 ArrayDeque/Map의 내부 저장 공간은 객체 수가 줄었다고 바로 축소되지는 않을 수 있음 | target 잔존과 다른 메타데이터/캐시 비용. 이 분석에서 backing-array 크기나 heap 사용량은 측정하지 않음 |
| 확장 Main/target의 사용자 필드 | Main 상속 클래스의 추가 필드, target 내부 버퍼, 사용자 정적 캐시 | 풀은 임의 사용자 필드를 정리하지 않음. 애플리케이션 참조 그래프 별도 점검 필요 |

최초 분석 시에는 미반납 Holder 수가 maxSize로 제한되었지만, 이후 9절의 정책 변경으로 현재는 제한하지 않는다. T 하나의 메모리나 외부 자원 비용도 제한하지 않는다. 닫힌 Sub가 미반납 객체를 가진 상태로 새 Sub를 발급할 수 있으므로 activeSubPoolCount만으로 전체 잔존 범위를 판단하면 안 된다. created/borrowed/closedSub/destroying/reserved와 실패 누계를 함께 확인한다.

### 정상 경로에서 확인한 해제

- ObjectHolder.detach는 main을 null로 바꾼다. Holder 자체에는 target/Sub 인스턴스 필드가 없다.
- Sub는 마지막 미반납 객체가 돌아오면 Main 참조를 해제하고 최종 snapshot을 보관한다. SubState에는 target이 아닌 ID queue와 카운트가 있다.
- PooledEntry.close의 finally에서 value/main을 null로 만들고, destroyAll의 finally에서 targets/entries를 제거한다. destroy 훅 NPE도 이 경로를 통과한다.
- exception 객체를 Main 필드에 영구 보관하지 않는다. 호출자가 예외나 raw target을 별도로 저장한 경우의 수명은 호출자가 관리한다.
- inHook은 생성·폐기 훅 종료 시 remove된다. checkEntry의 ThreadLocal.get으로 훅을 실행하지 않은 스레드에 null 값의 ThreadLocal 항목이 남을 수 있으나, 해당 값이 Main/target을 보관하는 구조는 아니다. ThreadLocal 내부 테이블의 사용량은 측정하지 않았다.

## 4. NPE 경계와 개연성

| 입력·경로 | 현재 처리 | 평가 |
| --- | --- | --- |
| Main 생성자 config=null | Objects.requireNonNull → NPE | 의도된 입력 계약. 생성·예약 전 실패 |
| recycleSubPool(null) | requireNonNull → NPE | 의도된 입력 계약. 수명 상태 변경 없음 |
| recycleObjectHolder(null) | requireNonNull → NPE | 의도된 입력 계약. 반납 누계 변경 없음 |
| generate 훅이 null 반환 | IOException, 예약 해제 | 우발 NPE로 이어지지 않음. 기존 테스트 검증 |
| generate 훅이 NPE 발생 | 원래 NPE 전파, 예약 해제·실패 카운트 증가 | 확장 훅의 결함. 독립 probe에서 다음 대여 가능 확인 |
| destroy 훅이 NPE 발생 | 나머지 대상도 정리 시도, registry 제거, 원래 NPE 전파 | 확장 훅의 결함. 3개 대상 모두 시도되고 failure=3 확인 |
| 경고 로거가 NPE 발생 | Main.close 중단, 미착수 정리 유실 | M-01 실제 결함. 입력 null 계약과 구분해야 함 |
| 반납 후 Holder.get | IllegalStateException | main의 volatile 값을 로컬로 복사하고 null 검사. NPE 경로 확인 안 됨 |
| Main 종료와 Holder.get 경쟁 | Main 락에서 수명·entry·holder 검증 | null target을 반환하는 정상 전이 확인 안 됨 |
| 종료 Sub.stats | finalStats 게시 후 main=null | null main을 관찰하면 최종 snapshot도 게시된 상태. 정상 공개 경로의 null snapshot 확인 안 됨 |
| entries.get의 null·entry.owner 역참조 | target/recycle은 entry 검증, owner 변경은 같은 락 | queue/registry 불변식이 유지되는 한 정상 경로 NPE 확인 안 됨. 임의 reflection 변경은 분석 전제에서 제외 |
| 사용자 T 내부 null | get 이후 사용자 메서드에서 발생 가능 | 풀의 null 계약으로 방어할 수 없음 |

get으로 얻은 raw T는 Holder.close와 사용 완료를 호출자가 동기화해야 한다. close 이후에도 raw 참조는 물리적으로 사라지지 않는다. 그 참조로 target을 사용하면 재대여·물리 close와 충돌해 사용자 타입에서 NPE 또는 다른 오류가 발생할 수 있다. 이는 raw get API의 수명 제약이다.

## 5. 검증 방법과 결과

### 기존 테스트

실제 JDK 17.0.10과 Maven 3.9.11에서 `mvn -f object-pool/pom.xml test -q` 실행: **24개 통과, 실패/오류/건너뜀 0**. 기존 수명·실패·동시성 테스트는 수정하지 않았다.

### 독립 재현 프로그램

소스: [analysis/PoolSafetyProbe.java](analysis/PoolSafetyProbe.java). JUnit을 사용하지 않으며 분석용 프로그램이다. probe의 성공은 기대한 결함 상태까지 재현했다는 뜻이며 라이브러리의 무결점 판정이 아니다.

저장소 루트, JDK 17의 java/javac를 사용하는 PowerShell에서 실행한다.

```powershell
mvn -f object-pool/pom.xml test
New-Item -ItemType Directory -Force object-pool/target/analysis-classes | Out-Null
javac -encoding UTF-8 --release 17 -cp object-pool/target/classes -d object-pool/target/analysis-classes object-pool/analysis/PoolSafetyProbe.java
java -cp "object-pool/target/classes;object-pool/target/analysis-classes" com.inzent.apim.utilities.pool.analysis.PoolSafetyProbe
```

최초 실행은 `D:\01.app\java\jdk-17.0.10\bin`의 java/javac를 명시했다. **20개 검사항목 확인, exit 0**. 아래 출력은 수정 전 기록이다. 현재 probe 기본 모드는 수정 후 보장을 검사하며, 수정 전 클래스를 따로 준비한 경우에만 `--expect-original`로 당시 결함 상태를 검사할 수 있다.

```text
NULL_BOUNDARIES: expected NPE; no state corruption
HOOK_NPE: generation reservation released; all 3 destroy attempts completed
REFERENCES: closed Sub retains Main only while Holder outstanding; final refs null
EXTERNAL_RESOURCE: CLOSED/created=0 can coexist with failed target release
LOGGER_FAILURE_REPRODUCED: state=CLOSING created=1 destroying=1 destroyed=1; repeated close cannot recover
ANALYSIS CHECKS PASSED: 20
```

독립 리뷰에서도 로깅 실패 경로를 확인하고, 별도 JDK 21 프로세스에서 같은 잔존 상태를 재현했다. 그 외 일반적인 동시성만으로 발생하는 내부 NPE나 추가적인 중대한 참조 유지 결함은 확인하지 못했다.

### 분석 한계

힙 덤프, 장시간 부하·실트래픽, GC 강제 실행에 의한 수거 여부, 네이티브 파일/소켓 계측은 수행하지 않았다. 정상 핸들의 Main 필드 null 검사는 구조적 증거이며 전체 애플리케이션의 GC 도달 가능성 증명은 아니다. VM 메모리 고갈, 강제 스레드 종료, 프로세스 중단 중의 정리 완료도 보장하지 않는다.

## 6. 권장 후속 조치

1. M-01의 진단 실패와 cleanup 실행을 분리하고 회귀 테스트로 보호한다.
2. 서비스 종료 시 Holder → Sub → Main 종료 순서와 사용 완료 동기화를 보장한다.
3. destroyFailureCount, destroyingObjectCount, reservedCreationCount와 CLOSING 지속 시간을 관찰한다. activeSubPoolCount=0만으로 종료 완료를 판단하지 않는다.
4. T별 reset/버퍼 축소 및 외부 자원 해제 정책을 검토한다. timeout/강제 회수 도입 여부는 raw get API의 안전성과 함께 결정한다.

최초 분석에서는 M-01을 수정하지 않았으며, 이후 사용자 요청에 따라 아래와 같이 적용했다.

## 7. 분석 결과 적용 (2026-10-20)

### 적용 내용

- closeSubLocked는 불변 SubPoolStats 경고 snapshot만 수집한다. target/Main을 snapshot에 담지 않는다.
- Sub.close와 Main.close는 모든 상태 전이와 대상 수집을 마친 뒤 락을 해제한다.
- finishClose는 destroyAll을 먼저 실행하고, 이후 모든 경고를 락 밖에서 출력한다. 로깅이 실패해도 이미 수집한 폐기 작업이 유실되지 않는다.
- 폐기와 로깅 오류를 모두 집계하고 정리 후 전파한다. Error 우선, 후속 suppressed 보존, 논리 반납/종료 완료 계약을 유지한다.
- 공개 API와 필수 상태 record는 변경하지 않았다. 미반납 객체의 지연 폐기와 훅 실패 자동 재시도 없음도 유지한다.
- CLOSING 재호출에서 모든 DESTROYING entry를 다시 폐기하는 방식은 적용하지 않았다. 다른 스레드가 이미 폐기 중인 entry와 중복 처리될 위험이 있어, 원인인 진단 실패의 정리 실행 차단을 제거했다.

### 회귀 검증

`PoolDiagnosticsTest`에 8개 실행 사례를 추가했다: Main/Sub 종료 각각 NPE·RuntimeException·Error, 로깅 중 다른 스레드의 stats 조회, 폐기 오류와 여러 로깅 오류의 집계.

수정 전 8개 중 5개 실패로 Main 정리 누락, 후속 Sub 종료 누락, Main 락 점유와 오류 집계 누락을 확인했다. 수정 후 기존 테스트와 합계 **32개 통과**했다. 기존 분석 프로그램도 현재 구현의 정상 종료를 검사하도록 갱신했다.

### 최종 결과

- 실제 JDK 17.0.10 / Maven 3.9.11에서 `mvn -f object-pool/pom.xml clean verify` 성공. **32개 테스트, 실패/오류/건너뜀 0**.
- 갱신한 probe를 같은 JDK로 컴파일·실행해 **20개 검사항목 확인, exit 0**. 수정 전 잔존했던 객체를 포함해 폐기 훅이 정확히 2회 실행되고 최종 created/destroying=0을 확인했다.

```text
LOGGER_FAILURE_FIXED: state=CLOSED created=0 destroying=0 destroyed=2
ANALYSIS CHECKS PASSED: 20
```

- 독립 리뷰에서 락 밖 진단, 정리 우선 순서, 로깅 재진입·동시 반납의 단일 폐기, 복합 오류 보존과 snapshot 참조 수명을 확인했다. 추가 중요 지적 없음.
- 미반납·무한 훅·외부 자원 해제 실패의 계약상 위험은 이번 수정으로 자동 회수되는 대상이 아니다. 정상 Holder 반납과 유한 훅을 전제로 하며, 힙 덤프·장기 부하 검증은 추가하지 않았다.
- 기존 사용자 변경은 보존했으며 커밋·push는 수행하지 않았다.

## 8. 독립 Sub 컨테이너 후속 구조 (2026-10-20)

최초 분석의 SubState ID queue는 이후 사용자 요청으로 SubPool이 소유하는 SubPoolContainer의 실제 PooledEntry 관리 Map·idle Deque로 변경했다. 따라서 미반납 Holder가 있는 동안에는 Sub → container → entry → target 참조도 존재한다. 해당 entry가 Main으로 이전되거나 폐기되면 local managed에서 제거하며, 최종 반납 후 managed/idle이 0이고 Main 참조도 해제되는 계약이다.

다른 Sub의 컨테이너 객체를 직접 회수하지 않는다. 독립 저장소의 대여·반납·종료·소유권 변경은 기존 Main 조정 락 안에서 수행한다. 전체 Java 17 clean verify 52개 통과 및 분석 probe 20개 통과를 확인했고 독립 리뷰에서 추가 중요 참조 유지 결함은 확인하지 못했다. 힙 덤프나 장기 GC 검증은 추가하지 않았다.

## 9. Main idle 상한 후속 정책 (2026-10-20)

사용자 요청으로 maxSize는 Main idle 보관 상한으로 변경됐다. 전체 생성·대여 수와 reservedCreationCount는 이 값을 넘을 수 있다. 반납 시 Main idle이 이미 maxSize이면 반납 대상을 DESTROYING으로 전환하고 Sub 관리 목록에서 해제한 뒤 락 밖에서 폐기한다. local cache 초과, 닫힌 Sub 지연 반납, Sub 종료, 초기 배정 rollback 모두 동일 경로를 사용한다.

이 정책은 전체 메모리 점유를 제한하지 않는다. Sub cache는 maxCachedPerSub에 따라 독립 유지되고 borrowed 객체는 정상 반납까지 유지된다. 폐기 실패의 registry 제거·참조 해제·오류 집계 계약은 동일하다. Java 17 clean verify 58개와 probe 20개를 확인했으며 독립 리뷰에서 추가 중요 지적 없음.

## 10. 독립 컨테이너·초과 생성 이후 재분석 및 적용 (2026-10-20)

### 분석 범위와 결론

현재 MainPool/SubPoolContainer/SubPool/SubState/ObjectHolder/PooledEntry와 Jackson 설정 경로를 다시 검토했다. 새로운 일반 동시성 경로의 내부 NPE나 정상 반납 이후 target의 강한 참조 잔존은 확인하지 못했다. **실제 확인한 개선 대상은 버스트 후 내부 저장소의 과다 유지와 일반 호출 후 빈 ThreadLocal 항목의 유지다.** target 객체 누수와 내부 배열·메타데이터 보관을 구분한다.

이전 58개 테스트는 반환된 핸들의 직접 필드를 중심으로 검사했다. 이번에는 Main/Sub 컨테이너의 Map·Deque까지 따라가는 참조 검사, 실제 할당된 내부 배열 길이, ThreadLocalMap 키를 추가 검증했다. VM의 GC 시점에 의존하지 않는다.

### M-02 / P2 / 재현·수정: 관리 Map의 최고 용량 유지

Main.entries와 targets, SubPoolContainer.managed는 현재 보유 수만큼 entry를 제거하더라도 내부 배열을 자동으로 줄이지 않았다. 전체 생성 수를 제한하지 않는 정책에서 4,096개를 대여했다 반납하면 Main에 target이 2개만 남아도 배열이 각각 8,192·16,384·8,192 슬롯으로 유지됐다. Main.subs도 미반납을 가진 종료 Sub가 많이 쌓인 뒤 모두 반납해도 큰 테이블을 유지했다.

Map의 요소가 삭제된 자리에서 target 참조가 남는 문제는 아니다. Main이나 종료 핸들이 도달 가능한 상태로 보관되면 불필요하게 큰 배열이 함께 유지되는 저장 공간 문제다. 반복 버스트가 같은 크기이면 계속 무한 증가하는 누수라고 단정할 수 없으나, 더 큰 버스트가 있을 때 최고 용량이 늘 수 있다.

**적용:** 최고 관리 수를 추적하고, 32 초과 규모에서 현재 수가 최고 수의 1/4 미만으로 감소하면 Map을 재구성한다. 살아 있는 PooledEntry의 동일성과 순서, IdentityHashMap의 참조 동일성 계약은 유지한다. Main 조정 락 안에서 교체하며 실제 target의 폐기·생성·대여 수는 바꾸지 않는다.

### M-03 / P2 / 재현·수정: 빈 컨테이너의 배열 유지

1,024개를 초기 배정한 Sub의 idle Deque는 내부 배열 길이가 1,257이었다. 전부 대여하거나 Sub.close로 비운 뒤에도 빈 배열이 같은 크기로 남았다. Main idle과 Main/Sub 관리 테이블도 완전 종료 후 자동 축소되지 않았다.

**적용:** Sub idle이 크게 줄면 같은 비율 규칙으로 Deque를 재구성한다. 최종 반납이 끝나 컨테이너가 실제로 비었을 때만 저장소를 교체한다. Main도 entries와 생성 진행 수가 모두 0이어서 CLOSED가 되는 시점에 빈 registry와 idle 저장소를 교체한다. 미반납/생성/폐기 중인 entry를 버리는 방식은 사용하지 않는다.

### M-04 / P3 / 재현·수정: 빈 ThreadLocal 항목

checkEntry의 ThreadLocal.get은 훅 밖에서도 null 값의 항목을 해당 스레드에 생성했다. initialSize=0으로 start만 호출해도 풀의 감시용 키가 작업 스레드 ThreadLocalMap에 남는 것을 확인했다. 값은 null이므로 이 항목이 Main/target을 강하게 보관한다는 의미는 아니다. 여러 풀과 장수 스레드가 조합되면 항목 수와 조회 비용이 늘 수 있는 메타데이터 유지다.

**적용:** true인 훅 감시 상태는 유지하고 재진입을 거절한다. 그 외의 일반 진입에서는 빈 항목을 즉시 remove한다. 기존 생성·폐기 훅 finally의 remove도 유지한다.

### NPE 재검증

| 경로 | 검증 결과 |
| --- | --- |
| 생성 훅 null/NPE, 부분 Sub 초기화 실패 | 생성 진행 수를 해제하고 기존 rollback 경로로 Main idle 상한 내 보관 또는 폐기 |
| overflow 폐기 NPE + 경고 로거 NPE | 모든 확보 대상의 정리 시도, suppressed 오류 집계, 최종 반환 후 Main/Sub/Holder의 transitive target/Main 참조 없음 |
| get와 Main.close/Holder.close 경쟁 | 유효 target 또는 IllegalStateException. null target 노출 확인 안 됨 |
| Sub.stats와 최종 반납 경쟁 | null snapshot 노출 확인 안 됨. 최종 snapshot 게시 후 volatile Main 참조 해제 |
| 저장소 교체 | Map·Deque 필드를 null로 만들지 않고 락 안에서 교체. 준비 중 생성과 미반납을 유지 |
| 입력 null 및 Jackson 속성 null | 기존 입력 계약 유지. JSON 속성 null은 기본값, Main config 자체 null은 NPE로 거절 |

### 실제 계측 결과

아래 값은 JDK 17.0.10에서 계측한 **내부 배열 원소 수**다. IdentityHashMap 배열은 key/value 슬롯을 함께 포함하며, 표를 메모리 바이트 수나 전체 heap 감소량으로 해석하지 않는다.

| 시나리오 / 저장소 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 4,096개 반납 후 Main.entries | 8,192 | 32 |
| 4,096개 반납 후 Main.targets | 16,384 | 64 |
| 4,096개 반납 후 Sub.managed | 8,192 | 32 |
| 완전 종료 후 Main.entries / targets / subs | 8,192 / 16,384 / 16 | 0 / 8 / 0 |
| 1,024 초기 배정 후 종료된 Main.idle / Sub.idle | 1,257 / 1,257 | 1 / 1 |
| 1,024 초기 배정 후 종료된 Sub.managed | 2,048 | 0 |
| 일반 start 후 빈 ThreadLocal 항목 | 있음 | 없음 |

### 재현 및 검증 방법

[PoolMemorySafetyTest.java](src/test/java/com/inzent/apim/utilities/pool/PoolMemorySafetyTest.java)에 7개 회귀 테스트를 추가했다. 수정 전에는 저장소 유지·빈 ThreadLocal 5개 실패를 확인했다. NPE 복합 실패와 get/stats/반납 경쟁은 수정 전에도 통과했으며 새 결함으로 보고하지 않는다.

테스트의 `--add-opens`는 JDK 내부 저장 공간을 확인하기 위한 것으로, 운영 소스에서는 해당 reflection을 사용하지 않는다. 운영 런타임에 모듈 개방 옵션은 필요하지 않다.

저장소 루트의 PowerShell, JDK 17 java/javac 환경에서 계측한다.

```powershell
mvn -f object-pool/pom.xml clean verify
mvn -f object-pool/pom.xml dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt -DincludeScope=test
$probeDependencies = (Get-Content object-pool/target/probe-classpath.txt -Raw).Trim()
New-Item -ItemType Directory -Force object-pool/target/analysis-classes | Out-Null
javac -encoding UTF-8 --release 17 -cp "object-pool/target/classes;$probeDependencies" -d object-pool/target/analysis-classes object-pool/analysis/PoolMemoryProbe.java
java --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED -cp "object-pool/target/classes;object-pool/target/analysis-classes" com.inzent.apim.utilities.pool.analysis.PoolMemoryProbe --check-compaction
```

--check-compaction은 수정 후 회수 보장 검증용이다. 수정 전 클래스를 별도로 준비해 당시 값을 계측할 때는 이 인수를 생략한다. 실제 작업에서는 JDK 17.0.10 bin의 java/javac를 명시했다.

### 최종 검증 결과

- 실제 JDK 17.0.10 / Maven 3.9.11에서 `mvn -f object-pool/pom.xml clean verify` 성공. **65개 테스트, 실패/오류/건너뜀 0**.
- PoolSafetyProbe의 기존 **20개 검사항목 통과**, 최종 CLOSED/created=0/destroying=0 확인. 추가 VM 모듈 개방 없이 기본 풀 런타임을 실행했다.
- PoolMemoryProbe --check-compaction 성공. 수정 후 반납 배열 32/64/32, 종료 저장소 0/8/0/1/0/1, 빈 ThreadLocal 키 없음의 실제 출력을 확인했다.
- 독립 리뷰에서 저장소 교체 시 entry 동일성·락·종료 조정·재진입 감시와 참조 해제를 확인했다. 추가 중요 지적 없음.
- 기존 별도 사용자 변경은 수정하지 않았으며 커밋·push는 수행하지 않았다.

이 분석에서도 실제 메모리 고갈·장기 부하·heap dump·외부 소켓/파일 계측은 수행하지 않았다. 이미 유지 중인 정상 캐시와 미반납 객체, 사용자가 보관한 raw target 또는 훅의 무한 실행을 자동 해소하는 변경은 아니다.
