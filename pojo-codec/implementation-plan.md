# POJO Codec Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 승인된 POJO 바이너리 Codec을 JDK 컨테이너 기본 지원과 함께 독립 Maven 모듈로 구현한다.

**Architecture:** 공통 프레임 계층이 길이·타입·제한을 관리한다. Type/Field Codec은 본문만 처리하며 공통 Value Codec으로 중첩한다. 불변 Registry와 클래스 메타데이터를 공유하고 호출별 컨텍스트를 격리한다.

**Tech Stack:** Java 17, Maven, JDK I/O/reflection/collections, JUnit Jupiter(테스트 전용).

**Spec:** [승인 설계](project.md), [원본 요구](command.md).

## Global Constraints

- 기본 패키지: `com.inzent.apim.utilities.pojocodec`.
- 운영 의존성 없이 JDK 기반 구현. Java `var`·static import 금지, XML pretty format.
- 날짜·시간 값은 long 밀리초, 나노초 보정 필드 없음. 시간대 옵션 유지.
- null 필드는 기본 직렬화에서 제외. 역직렬화는 OMIT 설정에서도 NULL을 처리한다.
- JDK Queue/Deque·정렬·불변·동시성 컨테이너 Codec을 기본 배포에 포함한다.
- `maven-source-plugin:jar-no-fork`를 package에 연결한다.
- 모듈 루트 `*.md`를 `META-INF/docs/${project.artifactId}/`에 포함한다.
- 검증은 test/package, 설치는 `mvn -DskipTests install`로 수행한다.
- 초기 구현 중에는 기존 사용자 작업인 `command.md`와 `data/`를 변경·stage하지 않았다. 2026-11-19 사용자의 푸시 요청에 따라 모듈 요구사항인 `command.md`는 구현과 함께 포함하고, `data/`는 계속 제외한다.
- 사용자 계획 승인 후 이 세션에서 직접 순차 구현했다. 2026-11-19 추가 요청으로 모듈 변경을 커밋·푸시한다.

## Review Focus

1. 길이는 정상이나 InputStream이 조금씩 읽거나 skip이 0을 반환하는 입력: 경계와 다음 문서를 보존한다(Task 1).
2. 빈 EnumSet/EnumMap 및 checked wrapper의 타입 소거: 등록된 타입을 사용하고 모르면 명시적으로 실패한다(Task 6).
3. 밀리초 절삭으로 날짜 key가 중복되는 Map/Set: 복원 중 조용히 항목을 덮어쓰지 않는다(Task 3, 5).
4. 동시성 컨테이너에서 size와 iteration 결과 불일치: 확정 snapshot의 개수로 출력한다(Task 7).
5. 생성자 초기값과 NULL/ABSENT의 구분: 기본 OMIT 독자도 명시적 NULL을 대입한다(Task 9).

## 파일과 인터페이스 규약

아래 Java 파일은 `src/main/java/com/inzent/apim/utilities/pojocodec/` 기준이며 테스트는 동일 패키지의 `src/test/java/`에 둔다. 별도 언급 없는 신규 파일은 해당 Task에서 생성한다. 테스트 예시의 assertion은 `org.junit.jupiter.api.Assertions`의 일반 메서드 호출을 사용한다.

- 루트: `PojoCodecController.java`, `PojoCodecException.java`, `CodecOptions.java`, `CodecLimits.java`, `EncodeContext.java`, `DecodeContext.java`.
- `annotation/`: `PojoCodec.java`, `NullPolicy.java`, `EmptyPolicy.java`, `ZonePolicy.java`.
- `internal/`: `FrameIO.java`, `BoundedInputStream.java`, `BudgetedBuffer.java`, `ValueFrame.java`, `FieldRecord.java`, `ContainerHeader.java`, `WireKind.java`, `TemporalKind.java`.
- `codec/`: `AbstractTypeCodec.java`, `DefaultTypeCodec.java`, `AbstractFieldCodec.java`, `DefaultFieldCodec.java`.
- `codec/value/`: `ValueCodec.java`, `ScalarValueCodec.java`, `TemporalValueCodec.java`, `ArrayValueCodec.java`, `CollectionValueCodec.java`, `MapValueCodec.java`, `EntryValueCodec.java`.
- `codec/container/`: `JdkContainerCodec.java`, `BasicContainerCodec.java`, `SortedContainerCodec.java`, `EnumContainerCodec.java`, `WrapperContainerCodec.java`, `ConcurrentContainerCodec.java`, `BlockingContainerCodec.java`.
- `registry/`: `ValueCodecRegistry.java`, `CodecRegistry.java`, `TypeRegistry.java`, `JdkContainerCodecRegistry.java`, `ComparatorRegistry.java`, `ContainerPolicyRegistry.java`.
- `metadata/`: `ClassMetadata.java`, `FieldMetadata.java`, `MetadataResolver.java`, `GenericTypeResolver.java`.

공통 value 인터페이스는 `void write(OutputStream stream, Object value, Type type, EncodeContext context) throws IOException` 및 `Object read(InputStream stream, Type type, DecodeContext context) throws IOException`로 통일한다. 이 메서드는 전체 ValueFrame을 처리한다. 개별 ValueCodec은 `void writeBody(OutputStream, Object, Type, EncodeContext)` 및 `Object readBody(InputStream, Type, DecodeContext)`로 본문을 처리한다. EncodeContext/DecodeContext는 동일한 writeValue/readValue 위임을 노출한다.

Registry 설정은 `PojoCodecController.Builder`가 수집하고 build 시 검증·동결한다. Builder는 `options(CodecOptions)`, `limits(CodecLimits)`, `registerType(String, Class<?>)`, `registerCodec(String, int, Object)`, `registerComparator(String, Comparator<?>)`, `registerContainerPolicy(Class<?>, ContainerPolicyRegistry.Policy)`와 `build()`를 제공한다. 각 설정 메서드는 Builder를 반환한다. Codec 등록은 두 추상 기반 클래스의 인스턴스만 허용하며 중복 ID 또는 클래스별 충돌은 build 시 실패한다. 컨테이너 정책에는 enum/check 타입, 공정성, access-order를 담는다. 인스턴스마다 다른 정책이 필요하면 별도 컨트롤러를 사용한다.

## Task 1: 빌드와 프레임 경계

**Files:** `pom.xml`, `PojoCodecException.java`, `CodecLimits.java`, `internal/FrameIO.java`, `internal/BoundedInputStream.java`, `internal/BudgetedBuffer.java`, `internal/WireKind.java`; 테스트 `FrameIOTest.java`.

**Interfaces:** `FrameIO.readExact(InputStream, int): byte[]`, `skipExact(InputStream, int): void`, `writeString(OutputStream, String): void`, `readString(InputStream, int): String`. BoundedInputStream은 주어진 길이를 초과하지 않으며 close 시 부모를 닫지 않는다. CodecLimits는 설계 8절의 정확한 기본 예산을 제공한다.

- [x] `FrameIOTest`에 짧은 read, skip=0, 잘린 프레임, 음수 길이, 잘못된 UTF-8, 부모 스트림 미종료 테스트를 작성한다. 다음 문서가 남는지도 확인한다.
- [x] Java 17/JUnit 및 설계 9절 Maven 플러그인·리소스 설정을 pretty XML로 생성한다. `mvn -f pojo-codec/pom.xml -Dtest=FrameIOTest test`로 미구현 실패를 확인한다.
- [x] 설계 5절의 고정 kind 번호·PJCD 헤더·big-endian 길이와 정확한 경계 읽기, 메모리 합산 예산을 구현한다.
- [x] 위 명령을 다시 실행해 오류 입력은 정해진 예외, 정상 입력은 경계 보존으로 통과하는지 확인한다.

## Task 2: Registry와 호출 컨텍스트

**Files:** 루트 옵션·컨텍스트, `registry/ValueCodecRegistry.java`, `registry/CodecRegistry.java`, `registry/TypeRegistry.java`, `registry/ComparatorRegistry.java`, `registry/ContainerPolicyRegistry.java`, `codec/value/ValueCodec.java`; 테스트 `RegistryTest.java`, `ContextLimitsTest.java`.

**Interfaces:** 앞의 공통 write/read 계약, `TypeRegistry.resolve(String): Class<?>`, `ComparatorRegistry.resolve(String): Comparator<?>`. Context는 경로·깊이·항목·바이트 예산과 수신 Codec 버전, 선언 Type, 유효 옵션을 제공한다.

- [x] 중복 ID 거부, 미등록 타입 거부, 설정 동결, 깊이 64 허용/65 거부, 문서·프레임·항목 예산 경계 테스트를 작성한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=RegistryTest,ContextLimitsTest test`의 미구현 실패를 확인한다.
- [x] 불변 Registry, 타입 대입 검사, 호출별 identity 경로 추적 및 예산을 구현한다. 데이터의 클래스명으로 Class.forName을 호출하지 않는다.
- [x] 같은 명령으로 통과를 확인한다. 동시 호출이 경로·예산을 공유하지 않는지 추가 확인한다.

## Task 3: 단일값과 밀리초 날짜

**Files:** `codec/value/ScalarValueCodec.java`, `codec/value/TemporalValueCodec.java`, `internal/TemporalKind.java`; 테스트 `ScalarValueCodecTest.java`, `TemporalValueCodecTest.java`.

**Interfaces:** ValueCodec 본문 계약. temporalKind는 설계 표 순서대로 Date=1, sql.Date=2, sql.Time=3, Timestamp=4, Instant=5, LocalDate=6, LocalTime=7, LocalDateTime=8, OffsetDateTime=9, ZonedDateTime=10으로 고정한다.

- [x] primitive/boxed·Unicode·큰 수·enum·NaN·무한대·음수 0 테스트와 다음 밀리초 assertion을 작성한다: `Assertions.assertEquals(-1L, decodedInstant.toEpochMilli())`(입력 epoch 직전 1ns), `Assertions.assertEquals(123000000, decodedTimestamp.getNanos())`(입력 nanos=123456789).
- [x] `mvn -f pojo-codec/pom.xml -Dtest=ScalarValueCodecTest,TemporalValueCodecTest test`의 실패를 확인한다.
- [x] 설계 5절 단일값 형식과 6.2절 날짜 표를 구현한다. LocalDate는 UTC 자정, LocalTime은 millisecond-of-day, zone 생략 복원은 UTC로 통일한다.
- [x] 같은 명령으로 음수 epoch, 나노초 손실, 범위 초과, DST·zone 및 시스템 기본 시간대 독립성 테스트가 통과하는지 확인한다.

## Task 4: Annotation과 메타데이터

**Files:** `annotation/`의 네 파일, `metadata/`의 네 파일; 테스트 `MetadataResolverTest.java`, `GenericTypeResolverTest.java`.

**Interfaces:** `MetadataResolver.resolve(Class<?>): ClassMetadata`, `GenericTypeResolver.resolve(Type, Class<?>): Type`. FieldMetadata에는 wire 이름, Field, 해석 Type, 선택 Codec과 유효 정책을 보관한다.

- [x] 상속·이름 정렬·static/transient/synthetic 제외·final 거부·필드명 충돌·접근 불가·상속 제네릭·정책 우선순위 테스트를 작성한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=MetadataResolverTest,GenericTypeResolverTest test`의 실패를 확인한다.
- [x] ClassValue 기반 캐시와 설계 4.2/6.1절 정책 해석을 구현한다. 잘못된 annotation 대상은 조용히 무시하지 않는다.
- [x] 같은 명령으로 통과를 확인한다. 메타데이터 조회 중 대상 객체를 변경하지 않는지도 확인한다.

## Task 5: 배열·일반 컨테이너와 Entry

**Files:** `internal/ContainerHeader.java`, `codec/value/ArrayValueCodec.java`, `codec/value/CollectionValueCodec.java`, `codec/value/MapValueCodec.java`, `codec/value/EntryValueCodec.java`, `codec/container/JdkContainerCodec.java`, `codec/container/BasicContainerCodec.java`, `registry/JdkContainerCodecRegistry.java`; 테스트 `BasicContainerCodecTest.java`.

**Interfaces:** `JdkContainerCodec.snapshot(Object, EncodeContext): Snapshot`, `restore(ContainerHeader, List<Object>, Type, DecodeContext): Object`. Map snapshot 항목은 key/value Entry로 표현한다. Snapshot은 종류·메타데이터·확정 항목을 갖는 불변 내부 값이다.

- [x] primitive/참조/다차원 배열, 일반 List/Set/Map, Vector/Stack, null 원소, IdentityHashMap/WeakHashMap/Properties 및 Entry 테스트를 작성한다. 같은 밀리초로 절삭되는 서로 다른 Instant key 두 개를 읽으면 중복 오류가 발생하는 테스트를 포함한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=BasicContainerCodecTest test`의 실패를 확인한다.
- [x] 공통 ContainerHeader와 snapshot 기반 개수 출력, 대입 검사, 각 컨테이너 동등성에 따른 중복 검사를 구현한다. Properties는 직접 항목과 유효 문자열 기본값을 구분한다.
- [x] 같은 명령으로 통과를 확인한다. Map/Set 순서를 무조건 정규화하지 않는다.

## Task 6: 정렬·enum·불변·wrapper 컨테이너

**Files:** `codec/container/SortedContainerCodec.java`, `codec/container/EnumContainerCodec.java`, `codec/container/WrapperContainerCodec.java`; 테스트 `SortedContainerCodecTest.java`, `WrapperContainerCodecTest.java`, `EnumContainerCodecTest.java`.

**Interfaces:** Task 5의 snapshot/restore 계약. 자연순 comparator ID는 `natural`, 자연 역순은 `reverse-natural`. JDK 계열 containerKindId는 클래스명이 아닌 고정 소문자 이름(예: `tree-set`, `immutable-list`)과 버전 1을 사용하며 테스트 fixture에 동결한다.

- [x] TreeSet/TreeMap/PriorityQueue, EnumSet/EnumMap, factory immutable, empty/singleton/nCopies, Arrays.asList 및 unmodifiable/synchronized/checked/뷰 테스트를 작성한다. raw empty enum·checked 타입 누락은 실패하고 등록 시 복원되도록 한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=SortedContainerCodecTest,WrapperContainerCodecTest,EnumContainerCodecTest test`의 실패를 확인한다.
- [x] 공개 factory·wrapper 복원을 구현한다. JDK 내부 클래스 식별 adapter는 검증된 클래스 식별만 사용하며 내부 필드를 읽지 않는다. 알 수 없는 comparator는 등록을 요구하고 뷰는 독립 컨테이너로 복원한다.
- [x] 같은 명령으로 정렬·원소 추가 거부·고정 크기 set 허용·checked 타입 검사·wrapper 동기화 특성 및 구체 타입 대입 검증이 통과하는지 확인한다.

## Task 7: 동시성·blocking 컨테이너

**Files:** `codec/container/ConcurrentContainerCodec.java`, `codec/container/BlockingContainerCodec.java`; 테스트 `ConcurrentContainerCodecTest.java`, `BlockingContainerCodecTest.java`.

**Interfaces:** Task 5 계약. capacity와 enum/comparator 등의 metadata는 해당 Codec이 버전 1 고정 순서로 인코딩한다. 명시 정책이 없으면 설계의 공정성 false 기본값을 사용한다.

- [x] 설계 6.3.1의 모든 concurrent/blocking 구체 타입에 대한 왕복 테스트를 작성한다. size와 iterator 개수가 다른 테스트 컨테이너, 빈 SynchronousQueue, 미만료 Delayed 항목과 bounded capacity를 포함한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=ConcurrentContainerCodecTest,BlockingContainerCodecTest test`의 실패를 확인한다.
- [x] 제한 예산 아래 snapshot을 확정하고 해당 구체 타입으로 복원한다. Queue.offer를 사용하며 put/take를 호출하지 않는다. CopyOnWrite와 ConcurrentSkipList 계열도 등록한다.
- [x] 같은 명령으로 교착·항목 유실 없이 통과하는지 확인한다. DelayQueue 원소는 명시적으로 등록한 Delayed 테스트 Codec을 사용한다.

## Task 8: 추상 Codec과 기본 POJO 본문

**Files:** `codec/`의 네 파일, 필드 레코드 처리를 담당하는 `internal/Engine.java`; 테스트 `PojoCodecControllerTest.java`, `CustomCodecTest.java`.

**Interfaces:** Type Codec은 `writeBody(OutputStream, T, EncodeContext)` / `readBody(InputStream, Class<T>, DecodeContext): T`; Field Codec은 `writeField(OutputStream, Object, Field, EncodeContext)` / `readField(InputStream, Object, Field, DecodeContext)`이며 모두 IOException을 전달한다.

- [x] 중첩 POJO·부모 선언 투영·삭제 필드 건너뛰기·사용자 Type/Field Codec 버전 읽기·본문 미소비·프레임 초과 테스트를 작성한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=PojoCodecControllerTest,CustomCodecTest test`의 실패를 확인한다.
- [x] 메타데이터 순회와 필드명 대입, 선택한 Codec의 본문 처리 및 공통 프레이밍을 연결한다. NULL/ABSENT에는 사용자 본문 훅을 호출하지 않는다.
- [x] 같은 명령으로 통과를 확인한다. 알 수 없는 삭제 필드는 Codec 등록 없이 건너뛰어야 한다.

## Task 9: 컨트롤러와 null 계약

**Files:** `PojoCodecController.java`, `internal/ValueFrame.java`; 테스트 `PojoCodecControllerTest.java`, `NullPolicyTest.java`, `CompatibilityTest.java`.

**Interfaces:** 설계 4절의 여섯 공개 overload와 본 계획의 Builder를 제공한다. Object 기반 serialize는 null 루트 추론을 거부하며 Class 기반 overload는 null 루트를 지원한다.

- [x] 여섯 API, 연속 문서, 필드 ABSENT/NULL/VALUE, null/empty 정책과 루트·중첩 다형성 테스트를 작성한다. 초기값 `"seed"`인 참조 필드에 대해 누락이면 `Assertions.assertEquals("seed", result.value)`, 명시 NULL이면 `Assertions.assertNull(result.value)`를 기본 OMIT 독자에서 확인한다.
- [x] `mvn -f pojo-codec/pom.xml -Dtest=PojoCodecControllerTest,NullPolicyTest,CompatibilityTest test`의 실패를 확인한다.
- [x] 헤더·공통 값 처리·Registry·컨텍스트를 연결하고 제한·경로 예외·순환 검사를 완성한다. 손실 없는 수치 확대만 허용하고 스트림 flush/close를 호출하지 않는다.
- [x] 같은 명령으로 기본 null 제외, INCLUDE 출력의 NULL 읽기 유지, primitive NULL 거부, 추가/삭제/순서 변경 및 금지된 타입 변환 테스트가 통과하는지 확인한다.

## Task 10: 포맷 동결·전체 검증·사용 문서

**Files:** `src/test/resources/compatibility/v1-minimal.hex`, `src/test/resources/compatibility/v1-container.hex`, `src/test/java/com/inzent/apim/utilities/pojocodec/WireFixtureTest.java`, `MalformedInputTest.java`, `README.md`; 수정 `project.md`.

**Interfaces:** 공개 API와 동결된 포맷의 외부 소비 관점만 사용한다. hex fixture는 인코더 결과를 그대로 정답으로 삼지 않고 설계 포맷으로 수작업 대조한다.

- [x] kind·temporal/container ID·기본 Codec ID·메타데이터 순서를 동결하고 fixture 읽기·쓰기 비교 테스트를 작성한다. 악성 길이, 중복 필드, 미등록 종류, 잘못된 날짜, 순환/공유 객체, 동시 독립 호출과 스트림 소유권도 검증한다.
- [x] `mvn -f pojo-codec/pom.xml test`로 전체 실패를 확인하고 해당 결함을 수정한 뒤 같은 명령으로 전체 통과를 확인한다.
- [x] README에 API 사용 예, Codec 상속·등록, 밀리초 손실, null 기본 생략/명시 NULL 읽기, 컨테이너 지원표·제약, 제한 조절 및 설치 명령을 작성한다.
- [x] `mvn -f pojo-codec/pom.xml package`를 실행한다. 본체 JAR의 `META-INF/docs/pojo-codec/`에 command/project/implementation-plan/README가 있고 sources JAR이 생성됐는지 `jar tf`로 확인한다.
- [x] `mvn -f pojo-codec/pom.xml -DskipTests install`로 테스트 미실행과 설치 성공을 확인한다. 구현 완료 사실과 실제 검증 결과를 project.md에 반영하고 package로 최종 문서 포함을 갱신한다.

## 계획 자체 검토

- 설계 4~5절 API/포맷은 Task 1~3, 8~10에 대응한다.
- 설계 6절 POJO·제네릭·컨테이너 전체는 Task 3~9에 대응한다.
- 설계 7~8절 호환성·안전성·동시성은 Task 1~2, 5~10에 대응한다.
- 설계 9~10절 빌드·문서·인수 기준은 Task 1, 10과 각 Task의 테스트에 대응한다.
- 사용자 확정사항인 밀리초 통일·JDK 컨테이너 기본 제공·null 쓰기 생략/읽기 지원 유지는 각각 Task 3, 5~7, 9에서 검증한다.
- commit/push 및 저장소 외부 변경은 이 계획의 완료 조건에 포함하지 않는다.

## 실행 결과

2026-11-18 구현 완료. 내부 구현 파일은 승인 설계의 책임을 유지하면서 공통 `Engine`과 `JdkContainers`로 통합했다. 메타데이터/제네릭 테스트 및 통합 테스트에서 Annotation 정책을 검증했다. `FieldRecord`/`ValueFrame`은 별도 데이터 클래스를 생성하지 않고 공통 프레임 로직에서 직접 처리한다. 전용 forwarding Registry·family별 forwarding Codec도 추가하지 않았다. 최종 Java 17 전체 테스트 50개가 통과했고 리뷰 지적사항의 실패→성공 회귀 테스트를 포함한다. 빌드·설치 및 포맷 계약은 project.md 11~12절에 기록한다.

## 추가 성능 요구 실행 결과

사용자의 Jackson 대비 CPU·메모리 개선 요구는 [performance.md](performance.md)의 후속 계획으로 수행했다. JMH 기준 측정 → CompiledCodec/재사용 버퍼 최적화 → 기능·메모리 회귀 테스트 → 별도 코드 리뷰 → 정식 측정 2회 순서로 검증했다. 최종 기능 테스트는 60개, 시간/할당량 비교 6개 조합은 모두 통과했다. 측정되지 않은 타입과 유지 힙/RSS까지 개선했다고 해석하지 않는다.
