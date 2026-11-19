# POJO Codec 설계

- 작성일: 2026-11-18
- 상태: 구현 및 성능 개선 완료, 테스트 60개·Jackson 비교 6개 조합 통과
- 승인일: 2026-11-18
- 구현 계획: [implementation-plan.md](implementation-plan.md)
- 요구사항: [command.md](command.md)
- 모듈: 독립 Maven 모듈 `pojo-codec`
- 제안 좌표: `com.inzent.apim.utilities:pojo-codec:1.0.0-SNAPSHOT`
- 제안 환경: Java 17, 운영 의존성 없이 JDK 기반 구현
- 기본 패키지: `com.inzent.apim.utilities.pojocodec`

## 1. 목적과 범위

POJO와 바이너리 스트림 사이의 직렬화·역직렬화를 제공한다. 기본 구현은 필드명을 저장하여 클래스의 필드 추가·삭제·선언 순서 변경 이후에도 이전 데이터를 읽는다. 클래스 단위 Type Codec과 필드 단위 Field Codec을 분리하고 추상 기반 클래스 상속으로 확장한다.

본 문서는 승인 설계와 구현 계약을 기록한다. `pom.xml`, Java 소스·테스트 및 사용법 `README.md`를 구현했다. 사용자는 밀리초 날짜 변환, JDK 기본 컨테이너 Codec 포함, 직렬화 null 기본 제외 및 역직렬화 NULL 지원 유지를 반영한 설계와 구현 계획을 승인했다.

초기 범위에는 스키마 자동 마이그레이션, 임의 클래스 로딩, Java 기본 직렬화, 객체 식별성 보존, 순환 참조, 사용자 정의 immutable POJO·record 생성자 바인딩을 포함하지 않는다. 기본 POJO는 인자 없는 생성자와 대입 가능한 인스턴스 필드를 갖는다. JDK 기본 컨테이너는 불변·동시성 컨테이너를 포함해 전용 기본 Codec으로 처리한다. 지원 계열과 복원 계약은 6.3절에 명시하며 객체 공유 관계의 완전 복원은 보장하지 않는다.

## 2. 접근 방식

| 방식 | 장점 | 제약 | 판단 |
| --- | --- | --- | --- |
| 필드 순서 기반 포맷 | 작은 데이터, 단순한 순차 처리 | 필드 변경 시 이전 데이터 해석이 어려움 | 요구와 부합하지 않음 |
| 필드명·타입·길이 기반 자체 바이너리 | 알 수 없는 필드 건너뛰기, 구조 중첩, Codec 확장을 직접 제어 | 포맷과 방어적 파서 구현 필요 | 본 설계에서 채택 |
| 외부 직렬화 라이브러리 래핑 | 기존 변환 기능 활용 | 라이브러리 포맷 및 확장 모델과 요청 API 조율 필요 | 초기 버전에서는 보류 |

모든 값에 경계가 있는 프레임을 적용한다. 역직렬화는 출력의 기록 순서를 같은 순서로 읽으며 각 인코딩을 역변환한다. 요구사항의 'serialize의 역순'은 스트림을 뒤에서 읽는 의미가 아닌 인코딩의 역연산으로 해석한다.

## 3. 구성과 책임

| 구성 | 역할 |
| --- | --- |
| `PojoCodecController` | 요청된 공개 진입점, 호출 컨텍스트 생성, 타입 결정, 헤더 처리 |
| `@PojoCodec` | 클래스·필드별 Codec 선택 및 null·empty·날짜 정책 지정 |
| `AbstractTypeCodec<T>` | 하나의 값에 대한 본문 쓰기·읽기 확장점 |
| `DefaultTypeCodec<T>` | POJO 필드 목록과 각 필드 프레임 처리 |
| `AbstractFieldCodec` | 필드 추출·대입을 포함한 확장점 |
| `DefaultFieldCodec` | 필드 타입과 옵션에 맞춰 공통 값 인코더에 위임 |
| `ValueCodecRegistry` | 단일값·배열·컬렉션·Map·POJO 변환기 선택 |
| `JdkContainerCodecRegistry` | JDK 컨테이너별 기본 Codec, 논리 종류 ID, 공개 API 기반 생성·복원 |
| `ComparatorRegistry` | 자연순·역순 및 사전 등록한 Comparator의 안정적 ID 관리 |
| `CodecRegistry` | 사용자 Codec 인스턴스와 안정적인 Codec ID·버전 관리 |
| `TypeRegistry` | 다형성 타입의 안정적인 논리 ID와 허용 클래스 대응 |
| `ClassMetadata` / `FieldMetadata` | 필드·생성자·제네릭·옵션에 대한 불변 메타데이터 |
| `EncodeContext` / `DecodeContext` | 호출별 경로, 깊이, 타입, 유효 옵션, 제한값 전달 |
| `CodecOptions` / `CodecLimits` | 전역 기본 정책과 크기·깊이 제한 |
| `PojoCodecException extends IOException` | 포맷·타입·접근·제한 위반과 필드 경로 전달 |

공통 프레이밍은 컨트롤러와 내부 프레임 계층이 소유한다. 사용자 Codec은 한 프레임의 본문만 처리하며 헤더와 상위 길이를 직접 변경하지 않는다.

```text
PojoCodecController
  -> 공통 값 프레임 / Registry
     -> AbstractTypeCodec -> DefaultTypeCodec
        -> AbstractFieldCodec -> DefaultFieldCodec
           -> 단일값 / Collection / Map / 배열 / 중첩 POJO
```

## 4. 공개 API 계약

다음은 구현 시 제공할 선언 형태이며 이 문서에서 컴파일 가능한 소스를 생성하지 않는다.

```java
public void serialize(OutputStream stream, Object bean) throws IOException;
public <T> void serialize(OutputStream stream, Class<T> type, T bean)
        throws IOException;
public <T> T deserialize(InputStream stream, Class<T> type) throws IOException;

public void serialize(OutputStream stream, Object bean, Field field)
        throws IOException;
public void deserialize(InputStream stream, Object bean, Field field)
        throws IOException;
```

- 위 메서드는 모두 `PojoCodecController`의 오버로드다.
- 첫 serialize는 null이 아닌 `bean.getClass()`를 사용한다. 타입을 추론할 수 없는 null은 오류다. 명시적 타입 오버로드는 null 루트를 지원한다.
- 명시적 타입 serialize는 bean의 대입 가능성을 검사한 뒤 해당 타입의 annotation과 상속 필드를 사용한다. 하위 클래스에만 있는 필드는 기록하지 않는다. 루트에서 런타임 하위 타입으로 자동 전환하지 않는다.
- deserialize는 명시된 클래스의 Codec과 필드 구성을 사용한다. null 루트는 참조 타입에서 null을 반환하며 primitive 클래스 요청은 거부한다.
- 타입 API는 호출마다 한 문서를 기록·소비한다. 연속 문서도 처리할 수 있도록 다음 문서의 바이트를 미리 소비하지 않는다. 단일 문서 전용 사용 시 남은 데이터 검사 기능을 별도 옵션으로 제공한다.
- 필드 API는 non-null bean 및 bean에 속한 유효 Field를 요구한다. 헤더 없는 단일 필드 레코드를 기록·소비하며 이름 일치를 검사한다. 생략 대상도 `ABSENT` 레코드를 기록하여 독립 호출 간 경계를 유지한다. POJO 본문의 생략 필드는 실제 레코드를 쓰지 않는다.
- 필드 deserialize에서 `ABSENT`는 현재 값을 유지하고 `NULL`은 참조 필드를 null로 대입한다. primitive 필드의 NULL은 오류다.
- 호출자는 스트림을 소유한다. 라이브러리는 close나 flush를 호출하지 않는다. 처리 중 오류 후 스트림 재사용과 출력 원자성은 보장하지 않는다.

### 4.1 추상 확장점

`AbstractTypeCodec<T>`는 `writeBody(OutputStream, T, EncodeContext)`와 `readBody(InputStream, Class<T>, DecodeContext)`를 구현하도록 한다. `AbstractFieldCodec`은 `writeField(OutputStream, Object, Field, EncodeContext)`와 `readField(InputStream, Object, Field, DecodeContext)`를 구현하도록 한다. 모두 `IOException`을 전달한다.

필드 Codec 본문은 자신이 추출·대입할 값을 소유한다. 필드명, NULL/ABSENT, 길이, Codec ID·버전은 공통 계층에서 처리한다. 따라서 NULL/ABSENT에는 사용자 본문 훅을 호출하지 않는다. 컨텍스트는 공통 값 읽기·쓰기 기능과 현재 필드의 해석된 `Type`을 제공하여 사용자 Codec도 중첩 형식과 제한을 재사용할 수 있다.

구현 클래스는 public 인자 없는 생성자로 생성하거나 Registry에 인스턴스를 등록한다. 컨트롤러 생성 시 등록을 확정한다. Codec은 컨트롤러 간 공유 가능성을 고려해 상태 없이 thread-safe하게 구현해야 한다. 호출별 상태는 컨텍스트에 둔다.

### 4.2 Annotation

`@PojoCodec`은 런타임 보존, TYPE/FIELD 대상으로 정의한다. 클래스 상속은 메타데이터 해석기가 명시적으로 처리하며 `@Inherited`의 암묵적 동작에 의존하지 않는다.

| 속성 | 기본값 | 의미 |
| --- | --- | --- |
| `typeCodec` | `DefaultTypeCodec.class` | 클래스에만 지정 가능한 Type Codec |
| `fieldCodec` | `DefaultFieldCodec.class` | 필드에만 지정 가능한 Field Codec |
| `nullPolicy` | `INHERIT` | 직렬화 시 null 필드 출력 정책: `INCLUDE`, `OMIT`, `INHERIT` |
| `emptyPolicy` | `INHERIT` | empty Collection/Map의 `INCLUDE`, `OMIT`, `INHERIT` |
| `zonePolicy` | `INHERIT` | `INCLUDE`, `OMIT`, `INHERIT` |

서로 다른 추상 기반 클래스를 하나의 class 속성으로 혼용하지 않도록 Codec 선택 속성을 나눈다. 잘못된 대상에 기본값 이외의 Codec 속성을 지정하면 메타데이터 생성 시 실패한다. 초기 버전에서는 임의 문자열 속성 Map을 추가하지 않는다.

유효 정책은 필드 > 해당 객체의 클래스 > 가장 가까운 상위 클래스 > 전역 기본값 순서로 정한다. Codec 선택은 필드의 Field Codec과 값 타입의 Type Codec을 각각 해석한다. 사용자 Field Codec이 본문을 직접 처리하면 해당 필드의 Type Codec 호출 여부는 그 구현이 결정한다.

전역 기본값은 null OMIT, empty OMIT, zone OMIT이다. 클래스 옵션은 그 객체의 직접 필드에 적용한다. 중첩 POJO는 자기 클래스 정책을 다시 적용한다. 필드의 zone 옵션은 그 필드에 속한 컬렉션·Map·배열 내부 날짜 값에도 적용하되 중첩 POJO 경계에서 다시 해석한다. null/empty 생략은 POJO 필드에만 적용하며 컨테이너 내부 원소·Map key/value는 제거하지 않는다.

null 정책은 직렬화의 출력 여부만 제어한다. 기본 직렬화는 null 필드를 제외하고, annotation으로 INCLUDE를 지정한 경우에만 명시적인 NULL 필드 레코드를 출력한다. 역직렬화의 NULL 지원은 유지하며 현재 설정이 OMIT이어도 입력의 NULL을 무시하거나 거부하지 않는다. 참조 필드는 초기값이 있더라도 null로 대입하고, 누락된 필드/ABSENT는 초기값 또는 현재 값을 유지한다. primitive 필드에 대한 NULL 거부 규칙과 루트·컨테이너 내부의 null 처리도 유지한다.

## 5. 바이너리 포맷 v1

모든 수치는 big-endian이다. 길이와 개수는 부호 있는 32비트 정수의 0 이상 범위로 제한한다. 음수는 즉시 오류다. 문자열은 `i32 바이트 길이 + UTF-8 바이트`이며 잘못된 UTF-8은 거부한다. 아래 이름은 개념상의 레코드이며 구현 클래스명을 고정하지 않는다.

```text
Document = magic[4]='PJCD' | version:u16=1 | flags:u16=0
           | rootFrameLength:i32 | RootValueFrame

ValueFrame = kind:u8 | payloadLength:i32 | payload

POJO payload = typeId:String | codecId:String | codecVersion:i32
               | bodyLength:i32 | body
Default POJO body = fieldCount:i32 | FieldRecord * fieldCount

FieldRecord = fieldName:String | state:u8
              | fieldCodecId:String | fieldCodecVersion:i32
              | fieldBodyLength:i32 | fieldBody
Default fieldBody = ValueFrame

Array payload = arrayTypeId:String | elementCount:i32 | ValueFrame * elementCount
Collection payload = ContainerHeader | elementCount:i32 | ValueFrame * elementCount
Map payload = ContainerHeader | entryCount:i32 | (key:ValueFrame | value:ValueFrame) * entryCount
ContainerHeader = containerKindId:String | containerVersion:i32
                  | metadataLength:i32 | metadata
```

- `rootFrameLength`는 RootValueFrame 전체 길이와 정확히 일치해야 한다. 본문 길이도 자신의 경계와 정확히 일치해야 한다.
- `kind`: 0 NULL, 1 BOOLEAN, 2 BYTE, 3 SHORT, 4 INT, 5 LONG, 6 FLOAT, 7 DOUBLE, 8 CHAR, 9 STRING, 10 BIG_INTEGER, 11 BIG_DECIMAL, 12 ENUM, 13 DATE_TIME, 14 BYTES, 15 ARRAY, 16 COLLECTION, 17 MAP, 18 POJO. 번호는 재사용하지 않는다.
- NULL payload 길이는 0이다. BOOLEAN은 0 또는 1의 1바이트, 정수·실수·char는 각각 Java 타입 크기의 고정 폭으로 기록한다. 실수는 원시 IEEE 비트, char는 unsigned 16비트 코드 단위다.
- STRING payload는 UTF-8 원문이다. BIG_INTEGER payload는 2의 보수 바이트열이며 길이 0을 거부한다. BIG_DECIMAL은 `scale:i32 + unscaledLength:i32 + unscaledBytes`다.
- ENUM payload는 논리 타입 ID와 상수 이름 문자열이다. ordinal은 저장하지 않는다. BYTES는 byte 배열 원문이다. ARRAY는 배열 타입 논리 ID를 앞에 기록한 뒤 위 원소 목록을 기록한다.
- COLLECTION/MAP은 런타임 컨테이너 계열을 안정적인 containerKindId로 기록한다. 선언 타입이 인터페이스 또는 Object여도 해당 계열의 불변성·동시성·정렬 특성을 복원한다. JDK 내부 구현 클래스명은 wire 계약으로 사용하지 않는다. metadata는 계열별로 comparator ID, enum 타입 ID, 용량, wrapper 정책 등을 기록하며 6.3절의 기본 Codec이 해석한다. 요소 값의 프레임은 각 요소의 실제 타입을 식별할 수 있어야 한다.
- `state`: 0 ABSENT, 1 NULL, 2 VALUE. ABSENT/NULL에서는 fieldCodec ID는 빈 문자열, 버전 0, body 길이 0이다. 기본 Field Codec ID는 `default-field`, 기본 Type Codec ID는 `default-pojo`, 버전은 각각 1이다.
- 일반 POJO 본문에는 ABSENT 레코드를 넣지 않는다. 포함 옵션의 null만 NULL로 기록한다. 중복 필드명은 거부한다.
- 사용자 Field Codec body는 자체 형식이다. 이를 알 수 없는 삭제 필드는 Codec을 찾지 않고 길이만큼 건너뛸 수 있다. 알려진 필드의 Codec ID·버전은 현재 선택한 Codec에서 지원해야 한다.
- 현재 클래스에서 정의하지 않은 필드의 본문과 알 수 없는 kind는 필드 전체를 건너뛰는 경우에만 허용한다. 알려진 필드나 컨테이너 원소의 알 수 없는 kind는 오류다.
- v1 외 문서 버전과 0이 아닌 flags는 거부한다. 체크섬·압축·암호화는 v1에서 제공하지 않는다.

각 프레임은 제한된 입력 스트림으로 읽는다. 정확한 본문 소비 여부를 검사하고 `skip`이 부분 진행하거나 0을 반환할 때도 EOF까지 무한 반복하지 않는 공통 건너뛰기 함수를 사용한다.

길이를 선행 기록하기 위해 초기 구현은 제한이 있는 메모리 버퍼를 사용한다. 문서와 활성 중첩 버퍼의 합계에도 예산을 적용한다. 깊이에 비례한 반복 복사를 최소화하도록 내부 버퍼 구성을 선택한다. 파일 임시 저장과 무제한 스트리밍 길이 계산은 후속 범위다.

## 6. 기본 타입 처리

### 6.1 POJO 필드와 생성

- 상위 클래스부터 Object 직전까지 인스턴스 필드를 수집한다. static/transient/synthetic 필드는 제외한다. annotation이 없는 필드도 포함한다.
- 필드명을 기준으로 정렬하여 POJO 필드 출력 순서를 고정한다. Map/Set 순서까지 정규화하지는 않는다.
- 부모와 자식이 같은 필드명을 선언하면 모호한 데이터가 생성되지 않도록 실패한다. 기본 wire 이름은 Java 필드명이며 rename alias는 초기 범위 밖이다.
- private 필드와 생성자는 접근 권한을 확보할 수 있을 때만 사용한다. 접근 불가 시 필요한 클래스·필드를 포함해 실패하며 조용히 누락하지 않는다.
- final 인스턴스 필드가 존재하면 기본 Type Codec을 거부한다. 생성자 기반 사용자 Type Codec으로 처리한다.
- 역직렬화는 생성자 호출 후 수신된 필드만 대입한다. 누락 필드는 생성자·필드 초기값을 유지한다. 초기값이 있는 필드에서는 생략 전 값과 복원 값의 동등성이 보장되지 않으므로 정확한 null/empty 복원이 필요하면 INCLUDE를 사용한다.

### 6.2 단일값과 날짜

지원 단일값은 primitive/boxed, String, BigInteger, BigDecimal, enum과 아래 날짜 타입이다. primitive와 boxed는 같은 wire kind를 사용하며 대상 선언 타입으로 구분한다.

DATE_TIME payload는 `temporalKind:u8 + millis:i64 + zonePresent:u8 + optional zone:String`이다. 모든 날짜·시간 값은 long 밀리초 단위로 통일하며, 밀리초 미만의 나노초 정밀도 손실은 허용한다. 별도 나노초 보정 필드는 저장하지 않는다. temporalKind는 타입별 고정 번호를 부여하며 구현 전 포맷 상수에 동결한다. `zonePresent`는 0/1만 허용한다.

| Java 타입 | millis | zone |
| --- | --- | --- |
| `java.util.Date` | epoch millisecond | 없음 |
| `java.sql.Date` | LocalDate의 UTC 자정 기준 epoch millisecond | 없음 |
| `java.sql.Time` | LocalTime의 millisecond-of-day | 없음 |
| `java.sql.Timestamp` | 실제 instant의 epoch millisecond | 없음 |
| `Instant` | epoch millisecond | 없음 |
| `LocalDate` | UTC 자정 기준 epoch millisecond | 없음 |
| `LocalTime` | millisecond-of-day | 없음 |
| `LocalDateTime` | UTC offset 0으로 계산한 epoch millisecond | 없음 |
| `OffsetDateTime` | 실제 instant의 epoch millisecond | INCLUDE이면 offset 문자열 |
| `ZonedDateTime` | 실제 instant의 epoch millisecond | INCLUDE이면 ZoneId 문자열 |

LocalDateTime의 UTC 계산은 지역 시간 성분을 long으로 표현하기 위한 규약이며 실제 시간대 변환을 뜻하지 않는다. 시스템 기본 시간대에 의존하지 않는다. zone이 생략된 OffsetDateTime/ZonedDateTime은 UTC로 복원한다. 따라서 instant는 밀리초 정밀도로 유지하되 원래 offset/zone·지역 시각은 유지하지 않는다. INCLUDE는 원래 offset/zone도 함께 보존한다.

instant 기반 값은 `Instant.toEpochMilli()`와 같은 규칙으로 변환한다. 음수 epoch에서도 밀리초 미만을 과거 방향으로 내림하며, 예를 들어 epoch 직전 1나노초는 -1밀리초로 기록한다. LocalTime은 하루 시작부터 경과한 나노초를 1,000,000으로 나눈 정수 밀리초로 기록한다. 복원한 값의 나노초 부분은 항상 밀리초의 배수다.

날짜 kind·변환 결과의 long 범위·타입별 유효 범위·zone 유효성을 검사한다. LocalDate 계열은 UTC 자정에 해당하는 밀리초만 허용하고, 시간만 있는 타입은 0 이상 86,400,000 미만으로 제한한다. long 범위를 벗어나는 값은 오류로 처리한다. 현재 읽기 옵션과 관계없이 wire에 포함된 zone은 복원에 사용한다. Calendar, Duration, Period 및 임의 Date 하위 클래스는 초기 기본 지원에서 제외한다.

### 6.3 Collection, Map, 배열, 제네릭

- JDK 17의 Collection/Map 구현, Queue/Deque, Sorted/Navigable 계열, immutable·동시성 컨테이너는 기본 Codec을 구현해 모듈에 포함한다. 사용자가 별도 Codec을 등록하지 않아도 아래 지원 범위를 처리한다.
- 컨테이너는 POJO reflection보다 기본 컨테이너 Codec을 우선 선택한다. 공개 생성자·factory·wrapper로 복원하고 JDK 내부 필드를 reflection으로 읽거나 수정하지 않는다.
- 런타임 종류를 확인할 수 없는 사용자 구현은 인자 없는 생성자와 add/put을 통한 일반 복원을 적용할 수 있다. 기본 계열로 묵시적 변환하여 불변성이나 동시성을 잃게 하지 않는다. 대입 가능성과 복원 계약을 충족하지 못하면 사용자 Codec을 요구한다.
- empty Collection/Map 필드는 기본 생략하며 INCLUDE 옵션으로 크기 0을 기록한다. 빈 String과 빈 배열은 생략하지 않는다.
- 컨테이너 내부 null 원소·null key/value는 프레임으로 기록하고 복원 컨테이너가 허용하는지 검사한다. null 원소가 있는 primitive 배열은 거부한다.
- Map key도 일반 ValueFrame이다. 복원 후 대상 컨테이너의 동등성 기준으로 중복 key 또는 Set 원소가 발생하면 데이터 손실을 숨기지 않고 실패한다. IdentityHashMap은 equals 대신 참조 동일성을 사용한다. 별도 공유 참조 복원이 필요한 identity key는 지원 범위를 벗어나면 명시적으로 실패한다.
- primitive 배열과 참조 배열, 다차원 배열을 지원한다. byte[]는 BYTES를 사용한다. 배열 컴포넌트 타입은 타입 ID로 검증한다.
- `Field.getGenericType()`의 ParameterizedType, GenericArrayType과 상속 TypeVariable 바인딩을 해석한다. 하위 클래스가 `Base<String>`을 상속하면 Base의 T 필드도 String으로 처리한다.
- 타입 정보가 부족한 raw/Object/와일드카드 위치는 내장 kind와 허용된 논리 타입 ID로 복원하고 대입 가능성을 검사한다. 해결하지 못한 TypeVariable의 상한도 검사한다. 런타임 컨테이너 클래스명을 데이터에서 로딩하지 않는다.
- 순서가 계약인 List/FIFO/Deque와 삽입 순서 컨테이너는 순서를 보존한다. 정렬 컨테이너는 comparator를, 우선순위 Queue는 우선순위 규칙을 보존하며 heap 내부 배열·iterator 순서는 보장하지 않는다. 같은 참조가 여러 위치에 있으면 독립 객체로 복원될 수 있다.

#### 6.3.1 기본 제공 컨테이너 Codec

| 계열 | 기본 지원 대상 | 복원 계약 |
| --- | --- | --- |
| 일반 List | ArrayList, LinkedList, Vector, Stack | 공개 구체 타입과 항목 순서 유지 |
| 일반 Set/Map | HashSet, LinkedHashSet, HashMap, LinkedHashMap, Hashtable | 공개 구체 타입 유지, 삽입 순서 계열은 기록 순서 유지 |
| 특수 Set/Map | EnumSet, EnumMap, IdentityHashMap, WeakHashMap, Properties | enum 타입, identity/weak 특성, Properties 조회 의미 처리 |
| Queue/Deque | ArrayDeque, LinkedList, PriorityQueue | FIFO/양끝 순서 또는 comparator 기반 우선순위 유지 |
| 정렬 계열 | TreeSet, TreeMap 및 SortedSet/SortedMap/NavigableSet/NavigableMap 뷰 | comparator와 정렬·탐색 특성 유지 |
| 불변 factory | List.of/copyOf, Set.of/copyOf, Map.of/ofEntries/copyOf | 항목을 먼저 읽은 후 factory로 생성, 변경 불가 특성 유지 |
| JDK wrapper | Collections.empty/singleton/nCopies, unmodifiable·synchronized·checked 계열, Arrays.asList | 불변·동기화·타입 검사·고정 크기 특성에 맞는 공개 wrapper로 복원 |
| 동시성 List/Set | CopyOnWriteArrayList, CopyOnWriteArraySet | 해당 구체 타입으로 복원 |
| 동시성 Map/정렬 | ConcurrentHashMap, ConcurrentSkipListMap, ConcurrentSkipListSet | 동시성 특성과 해당 comparator 유지 |
| 동시성 Queue/Deque | ConcurrentLinkedQueue, ConcurrentLinkedDeque, LinkedTransferQueue | 해당 구체 타입과 저장된 항목 복원 |
| blocking 계열 | ArrayBlockingQueue, LinkedBlockingQueue, LinkedBlockingDeque, PriorityBlockingQueue, DelayQueue, SynchronousQueue | 용량·우선순위·지연 원소 등 아래 정책 적용 |

이 표의 Codec은 선택적 외부 확장이 아닌 기본 배포 산출물에 포함한다. 내부 구현 클래스가 여러 개인 EnumSet과 immutable factory 결과는 동일한 논리 계열 ID를 사용한다. `Collections.newSetFromMap`, `asLifoQueue`, subList/subMap/subSet 및 keySet/values/entrySet 같은 뷰도 항목을 독립 컨테이너로 복원하며 공개 Set/Collection/Queue·정렬 계약을 유지한다. 원본 backing 객체와의 연결·구간 경계는 복원하지 않는다. Map.Entry 값은 기본 Entry Codec으로 key/value를 기록하고 수정 가능 여부에 맞춰 SimpleEntry/SimpleImmutableEntry로 복원한다.

선언 타입이 Collection/List/Set/Map/Queue/Deque/SortedSet/SortedMap/NavigableSet/NavigableMap/ConcurrentMap/ConcurrentNavigableMap/BlockingQueue/BlockingDeque/TransferQueue여도 wire의 종류 ID를 우선 사용하고 대입 가능성을 검사한다. 인터페이스의 단순 기본 생성값이 필요한 경우 각각 ArrayList/LinkedHashSet/LinkedHashMap/ArrayDeque/TreeSet/TreeMap/ConcurrentHashMap/ConcurrentSkipListMap/LinkedBlockingQueue/LinkedBlockingDeque/LinkedTransferQueue 계열로 매핑한다. 미등록 JDK 내부 클래스를 클래스명으로 동적 로딩하지 않는다.

#### 6.3.2 생성 메타데이터와 보존 범위

- 정렬 Set/Map과 PriorityQueue/PriorityBlockingQueue는 자연순·자연 역순을 내장 comparator ID로 지원한다. 그 외 comparator는 ComparatorRegistry에 인스턴스를 등록한 논리 ID를 기록한다. 등록되지 않은 comparator를 자연순으로 바꾸지 않고 실패한다. comparator 객체에 대한 임의 reflection/Java 직렬화는 사용하지 않는다.
- EnumSet/EnumMap은 비어 있어도 enum 타입 ID가 필요하다. 선언 제네릭 또는 명시적 컨테이너 타입 등록에서 가져오며, 비어 있지 않으면 원소/key로 보완한다. 타입을 알 수 없는 raw empty 값은 enum 타입 등록을 요구하는 명시적 오류로 처리한다.
- 불변·unmodifiable은 수정 불가 특성을 보존한다. 원소 객체까지 불변으로 만들지는 않는다. `Arrays.asList`는 set 가능·크기 변경 불가를 유지한다. checked wrapper는 제네릭 또는 명시적 등록에서 검사 타입을 얻으며 불명확하면 실패한다. wrapper 식별은 지원 JDK별 검증된 adapter를 사용하고 식별 불가 시 가변 컨테이너로 조용히 변환하지 않는다.
- synchronized wrapper는 새 backing 컨테이너와 새 wrapper로 복원한다. 원래 mutex·외부 락·공유 backing 관계는 기록하지 않는다. 정렬 wrapper는 comparator와 정렬 인터페이스를 함께 유지한다.
- bounded blocking 컨테이너는 공개 capacity 정보로 용량을 기록하고 크기 이상인지 검사한다. ArrayBlockingQueue/SynchronousQueue의 공정성, LinkedHashMap의 access-order, backing map 종류처럼 공개 API로 읽을 수 없는 생성 정책은 필요할 때 컨트롤러의 명시적 컨테이너 정책 등록으로 공급한다. 미등록 시 공정성 false, LinkedHashMap은 기록 순서를 삽입 순서로 복원하는 기본 정책을 문서화한다. 원본 값의 완전한 내부 설정 복원을 주장하지 않는다.
- 복원에는 Collection.add, Queue.offer, Map.put을 사용하며 blocking put/take를 호출하지 않는다. 대기 중 스레드·transfer 작업·락 상태는 저장하지 않는다. SynchronousQueue는 저장 항목 0으로 복원한다. DelayQueue는 iterator snapshot으로 만료 전 원소도 포함하며 Delayed 구현 타입의 값 Codec을 사용한다. 지연 시간의 기준과 외부 시계 의미는 해당 원소 Codec이 담당한다.
- WeakHashMap은 snapshot 시점에 관측된 항목과 weak-key 컨테이너 특성을 복원하며 GC 시점은 보존하지 않는다. Properties는 공개 API로 확인할 수 있는 직접 항목 및 상속 문자열 기본값의 유효 조회 결과를 기록해 복원하고 defaults 객체의 공유 관계는 보존하지 않는다.
- 컬렉션의 구체 타입별 null 허용, 원소 대입 조건, comparator 동등성 및 capacity를 검사한다. 빈 컨테이너도 INCLUDE일 때 종류·생성 메타데이터를 보존한다. OMIT이면 이전 설계와 동일하게 필드 초기값을 유지한다.

지원 대상과 공개 계약 확인 기준은 [JDK 17 java.util](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/package-summary.html)과 [JDK 17 java.util.concurrent](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/package-summary.html)이다. 모든 JDK 객체를 포괄하는 요구로 확대하지 않고 본 절의 컨테이너와 Entry를 기본 지원 범위로 정의한다.

## 7. 타입 식별과 호환성

POJO 및 enum·배열 타입 ID는 클래스명 대신 Registry의 논리 ID를 사용한다. 명시적 루트 타입과 그 필드에서 정적으로 도달 가능한 구체 클래스는 컨트롤러에 등록한 메타데이터에서 찾는다. 등록 ID를 지정하지 않은 정적 타입은 바이너리 클래스명을 기본 논리 ID로 사용하되, 입력 문자열로 `Class.forName`을 수행하지 않는다. 패키지·클래스명 변경 호환성이 필요하면 양쪽에서 같은 명시적 ID를 등록한다.

인터페이스/추상 클래스/Object 선언의 실제 POJO·enum·배열 타입은 허용 Registry에 사전 등록한다. 중첩 필드의 다형성은 등록된 실제 타입을 기록하고 선언 타입 대입 가능성을 검사한다. 명시적 루트 타입 serialize는 4절의 선언 타입 투영 정책을 우선한다. 루트 ID가 요청 타입 ID와 다르면 기본적으로 실패하며 명시적 ID 매핑으로만 호환시킨다.

| 변경 | 기본 동작 |
| --- | --- |
| 필드 추가 | 예전 데이터에 없으면 생성자 초기값 유지 |
| 필드 삭제 | 예전 레코드 길이만큼 건너뛰기 |
| 필드 선언 순서 변경 | 필드명으로 복원 |
| 필드명 변경 | 이전 이름은 삭제, 새 이름은 추가로 취급 |
| primitive ↔ 대응 boxed | null이 아닌 값은 허용 |
| byte → short/int/long, short → int/long, int → long, float → double | 손실 없는 확대 허용 |
| 그 외 수치 변환·축소·String 자동 변환 | 실패, 사용자 Codec 필요 |
| 날짜 타입 변경 / enum 상수 삭제 | 기본 실패 |
| 사용자 Codec ID·버전 변경 | 해당 Codec의 명시적 구버전 읽기 지원 필요 |

타입 변경 후 이전 binary 읽기는 모든 변환을 허용한다는 의미가 아니다. 구조 변경과 위 표의 변환을 기본 지원하며 의미가 다른 변경은 사용자 Codec이 맡는다. 사용자 Codec이 구버전 본문을 읽어야 하므로 컨텍스트는 수신 Codec 버전을 노출한다.

## 8. 실행 흐름과 오류 처리

serialize는 타입·메타데이터 검증 → 순환 참조 경로 검사 → null/empty 필터 → 필드 본문 생성 → 길이 계산 → 상위 프레임 기록 순으로 진행한다. 객체 그래프의 현재 탐색 경로를 identity 기준으로 추적하여 순환을 오류로 처리하며, 이미 끝난 형제 경로의 공유 객체는 다시 기록한다.

deserialize는 헤더 검사 → 경계 제한 스트림 생성 → 타입·Codec 확인 → 객체 생성 → 필드명 조회 → 알 수 없는 필드 건너뛰기 또는 값 검증·대입 → 정확한 프레임 소비 확인 순서다. 기본 본문은 기록 순서와 무관하게 이름으로 필드를 찾는다. 사용자 Codec은 컨텍스트의 하위 값 처리 기능을 사용해야 동일한 제한을 보장할 수 있다.

기본 제한 제안: 문서 64 MiB, 개별 프레임 16 MiB, 문자열 1 MiB, 타입 ID/Codec ID/필드명 각각 UTF-8 4 KiB, 컨테이너 100,000항목, POJO당 10,000필드, 중첩 깊이 64, 활성 인코딩 버퍼 합계 128 MiB. 항목 수는 총 1,000,000개 예산도 적용한다. 제한은 입력·출력 모두 동일하게 검사하고 길이 합산은 overflow를 검증한다. 대량 데이터 사용자는 제한을 명시적으로 조절한다.

잘린 데이터, 음수/초과 길이, 잘못된 kind/state, 등록되지 않은 타입·Codec, 중복 필드, 대입 실패, 접근 실패는 `PojoCodecException`으로 전달한다. 원래 I/O 오류는 원인을 보존한다. 예외에는 경로(예: `root.orders[2].createdAt`), 가능한 경우 바이트 위치, 기대 타입과 수신 kind를 포함하며 값 전체를 자동 출력하지 않는다.

컨트롤러와 Registry는 설정 확정 후 불변으로 사용한다. 클래스 메타데이터는 ClassValue 등 클래스 언로딩을 방해하지 않는 구조로 캐시한다. 스트림과 호출 컨텍스트는 공유하지 않는다. 컨테이너는 제한 예산 안에서 먼저 항목 snapshot을 확정한 뒤 그 개수와 본문을 기록한다. 동시성 컨테이너의 snapshot은 관측한 항목 집합이며 여러 항목의 원자적 시점 일관성을 보장하지 않는다. 정확한 시점 snapshot과 일반 컨테이너·bean의 동시 변경 방지는 호출자가 보장한다. synchronized wrapper의 snapshot은 해당 wrapper의 monitor 아래 수행한다.

## 9. 모듈 구성과 빌드 규칙

```text
pojo-codec/
  command.md
  project.md
  README.md                         # 구현 완료 후 실제 사용법
  pom.xml                           # 구현 단계 생성
  src/main/java/com/inzent/apim/utilities/pojocodec/
    annotation/
    codec/                          # 추상/기본 Type·Field Codec
    codec/value/                    # 단일값·컨테이너 인코더
    metadata/
    registry/
    internal/                       # 프레임·경계 스트림·버퍼
  src/test/java/com/inzent/apim/utilities/pojocodec/
  src/test/resources/compatibility/  # 동결된 v1 바이너리 샘플
```

- `maven-source-plugin:jar-no-fork`를 package에 연결하여 기본 빌드에서 sources JAR을 생성·첨부한다.
- 모듈 루트 `*.md`를 filtering 없이 `META-INF/docs/${project.artifactId}/`에 포함한다. package 전에 생성한 Markdown도 포함되도록 문서 생성 작업은 prepare-package 이전에 끝낸다.
- `mvn test`와 `mvn package`는 검증 경로로 사용한다. 설치는 `mvn -DskipTests install`을 문서화한다. Maven install 호출은 앞선 test 단계도 통과하므로 install 플러그인만 바꿔서는 테스트 실행을 막을 수 없다. 자동 빌드 설치 경로에도 skipTests를 명시한다.
- 생성 XML은 pretty format, Java 코드는 명시적인 타입 선언을 사용하며 `var`와 static import를 사용하지 않는다.
- 모듈의 빌드 파일을 독립적으로 구성하며 저장소 전체 빌드 설정은 변경하지 않는다.

## 10. 구현 순서와 검증 기준

1. 포맷 상수·날짜 subtype 번호·Registry ID 충돌 규칙을 고정하고 프레임 경계 및 제한 기반 구현을 만든다.
2. 단일값·날짜·배열과 JDK 기본 컨테이너 Codec 전체, ContainerHeader 메타데이터, comparator 등록 및 중첩 처리를 구현한다.
3. 메타데이터, 제네릭 해석, 기본 Type/Field Codec과 공개 API를 연결한다.
4. Annotation 정책과 사용자 Codec·다형성 등록을 연결한다.
5. 호환성·손상 데이터 테스트 및 패키징을 검증하고 실제 예제를 README에 작성한다.

필수 인수 기준:

- primitive/boxed, Unicode, NaN·무한대·음수 0, 큰 정수·소수, enum의 왕복.
- 날짜별 밀리초 변환·복원, 밀리초 미만 정밀도 손실 허용, 음수 epoch의 내림, long 범위 초과 거부, DST 경계, zone 포함/생략 및 시스템 기본 시간대 변경 시 동일 결과.
- 중첩 POJO·다차원 배열·컨테이너 안의 컨테이너, 상속 제네릭, 다형성과 null 항목 처리.
- 6.3.1 표의 각 JDK 컨테이너 Codec에 대해 구체 타입·인터페이스·Object 선언, empty/non-empty, 중첩 왕복을 검증한다. 불변/고정 크기/checked/synchronized wrapper의 변경 동작, Queue/Deque 순서, comparator·enum 타입·bounded capacity, 동시성 계열 타입, SynchronousQueue의 빈 복원, DelayQueue의 미만료 원소 포함을 확인한다.
- JDK 내부 클래스 reflection 없이 기본 Codec이 선택되는지 검증하고, 미등록 comparator·불명확한 empty enum/checked 타입·대입 불가·정밀도 손실로 인한 중복 key/원소는 정해진 오류로 처리한다. 뷰의 독립 복원과 동시 변경 snapshot의 개수·본문 일치도 검증한다.
- null/empty OMIT·INCLUDE, 부모/클래스/필드 옵션 우선순위와 생성자 초기값 유지.
- 기본 직렬화 결과에 null 필드 레코드가 없음을 확인한다. INCLUDE로 생성한 NULL 레코드를 기본 OMIT 설정으로 역직렬화해도 참조 필드가 null로 대입되는지 검증한다. 누락/ABSENT의 초기값 유지와 명시적 NULL의 초기값 덮어쓰기를 구분하고 primitive NULL 거부도 확인한다.
- 루트 런타임 타입 추론, 명시적 부모 타입 투영, 독립 필드 API의 ABSENT/NULL/VALUE 및 연속 문서 경계.
- 추가·삭제·순서 변경 전후 타입 간 읽기. 삭제된 사용자 Codec 필드를 Registry 없이 건너뛰기. 금지된 타입 변환은 실패.
- 쓰기와 읽기가 같은 버그를 공유해도 통과하지 않도록 수작업으로 검증한 고정 hex 샘플 및 이전 버전 바이너리 fixture를 읽는다.
- 사용자 Type/Field Codec의 본문 처리·버전 분기·미소비 바이트·경계 초과를 검증한다.
- 악성 길이·개수, 잘린 입력, 중복 필드·Map key·Set 원소, 잘못된 UTF-8·날짜·타입 ID, 순환 참조, final/접근 불가 필드가 정해진 예외로 종료된다.
- caller 스트림이 닫히거나 flush되지 않고, 다중 스레드 독립 호출에서 메타데이터 캐시와 Registry가 안전하다.
- `mvn test`, `mvn package` 통과, 본체 JAR의 문서 경로와 sources JAR 존재 확인, `mvn -DskipTests install`에서 테스트 미실행 확인.

완료 조건은 위 계약과 테스트를 만족하는 독립 모듈 및 README 제공이다. 처리량 목표·벤치마크, rename alias, 순환/참조 보존, 사용자 정의 immutable POJO 생성자 바인딩은 후속 요구가 있을 때 확장한다. JDK immutable 컨테이너 Codec은 초기 구현에 포함한다.

## 11. 구현 구체화

- 공통 `internal.Engine`이 프레임·타입·필드 처리를 연결하고, 단일값/날짜는 `codec.value`, JDK 컨테이너는 `codec.container.JdkContainers`가 처리한다. 전달만 하는 Registry·컨테이너 클래스를 따로 늘리지 않고 Registry는 타입·Codec·Comparator·생성 정책에 한정한다.
- `Map.Entry` 기본 Codec을 위해 wire kind **19 ENTRY**를 추가한다. 본문은 `mutable:u8(0/1) + key:ValueFrame + value:ValueFrame`이다.
- temporalKind는 Date=1, sql.Date=2, sql.Time=3, Timestamp=4, Instant=5, LocalDate=6, LocalTime=7, LocalDateTime=8, OffsetDateTime=9, ZonedDateTime=10으로 고정한다.
- ContainerHeader metadata v1은 `comparatorId:String + enumTypeId:String + capacity:i32 + fair:u8 + accessOrder:u8 + checkedKeyTypeId:String + checkedValueTypeId:String + defaultCount:i32 + (key:String,value:String)*defaultCount` 순서다. 기본값은 comparator `natural`, 나머지 ID는 빈 문자열, capacity=0, boolean=0이다. defaults는 Properties의 상속 문자열 조회값이며 key 순서로 출력한다.
- 컨테이너 종류 ID는 `JdkContainers`의 고정 종류 목록과 wrapper 접두사 `immutable:`, `unmodifiable:`, `synchronized:`, `checked:`를 사용한다. 현재 버전은 1이다. `src/test/resources/compatibility/`의 수작업 hex fixture로 포맷을 검증한다.
- JDK 공개 getter가 없는 enum/check 타입·공정성·access-order는 `ContainerPolicyRegistry.Policy`에 명시한다. ArrayBlockingQueue는 입력 allocation 보호를 위해 capacity도 containerItems 이하로 제한한다.
- 연속 문서 API는 `deserialize`, 단일 문서 및 EOF 검사는 `deserializeSingle`로 구분한다.
- 사용 예와 전체 제약은 [README.md](README.md)에 정리한다.

## 12. 구현 검증 결과

- 검증일: 2026-11-18. JDK 17.0.10에서 전체 테스트 **50개, 실패 0, 오류 0**.
- 고정 wire hex fixture, 잘린 입력·잘못된 길이·UTF-8, 밀리초 변환, 생성자 초기값/NULL, 필드 변경 호환, 사용자 Codec 및 JDK 컨테이너 왕복을 검증했다.
- 별도 코드 리뷰에서 확인한 정책 전용 enum/checked 타입의 새 독자 등록 누락과 nullable checked Queue 복원 오류를 각각 실패 테스트로 재현하고 수정했다. 명시적인 Object 검사 타입도 누락 타입과 구분한다.
- `mvn package`의 본체·sources JAR 생성과 Markdown 문서 포함, `mvn -DskipTests install`의 테스트 미실행·로컬 설치를 확인했다. 테스트/생성 로그는 작업 기록용 `.superpowers/sdd/implementation-plan/`에 보관한다.
- 구현은 현재 develop 체크아웃의 모듈 경로에 한정했다. 공통 Engine/컨테이너 adapter로 내부 파일 구성을 간소화했고, 포맷 동결 전에 Entry kind 19를 추가했다. Windows에서는 계획의 POSIX 보조 스크립트 대신 PowerShell로 같은 테스트·기록 절차를 수행했다. 별도 commit/push는 하지 않았다.

## 13. Jackson 대비 성능 개선

사용자의 추가 요구에 따라 CPU 처리 시간과 연산당 할당량을 개선했다. 기본 POJO의 scalar/String/primitive 배열/typed ArrayList 경로에 `internal.CompiledCodec`을 적용한다. ClassValue 실행 계획·필드 헤더 캐시, 단일 버퍼 위치 수정, primitive boxing 제거와 64 KiB 이하 스크래치 재사용을 사용하며 v1 포맷 및 범용 Engine의 기존 확장 기능을 유지한다.

Java 17 전체 테스트는 60개다. Jackson 2.20.1과 JMH 1.36의 flat/nested/numeric × serialize/deserialize 6개 조합에서 시간과 할당량이 모두 감소하고 신뢰구간도 겹치지 않았다. 최종 처리 시간은 1.16~6.82배 개선됐다. 이는 재사용 스트림의 정상 상태 할당량 측정이며 모든 지원 타입·유지 힙/RSS의 우위를 의미하지 않는다.

상세 수치·원본 결과·재현 명령과 적용 범위는 [performance.md](performance.md)에 기록한다. Jackson/JMH는 opt-in benchmark profile의 test 의존성으로만 추가하며 운영 의존성은 없다.

## 14. 배포 준비 — 2026-11-19

사용자의 푸시 요청에 따라 `pojo-codec`의 요구사항·설계·구현·테스트·벤치마크 결과를 함께 커밋하여 `origin/develop`에 반영한다. 별도 사용자 작업인 `data/`는 포함하지 않는다. 푸시 전에 Java 17 package와 저장된 정식 성능 결과의 합격 판정을 다시 확인한다. 이전 절의 커밋·푸시 미수행 기록은 당시 구현 완료 시점의 상태다.
