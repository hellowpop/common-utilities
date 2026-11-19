# POJO Codec

Java 17 이상에서 동작하는 독립 바이너리 직렬화 모듈이다. 운영 라이브러리 의존성이 없으며 클래스·필드별 Codec 상속으로 확장한다. 상세 포맷·지원 타입은 [project.md](project.md), 개발 순서는 [implementation-plan.md](implementation-plan.md)에 정리한다.

## 빌드와 설치

저장소 루트에서 실행한다.

```powershell
mvn -f pojo-codec/pom.xml test
mvn -f pojo-codec/pom.xml package
mvn -f pojo-codec/pom.xml -DskipTests install
```

좌표는 `com.inzent.apim.utilities:pojo-codec:1.0.0-SNAPSHOT`이다. package는 본체 JAR과 sources JAR을 생성하고, 모듈 루트 Markdown 문서를 본체 JAR의 `META-INF/docs/pojo-codec/`에 포함한다. install 명령에는 `-DskipTests`를 지정하여 테스트를 실행하지 않는다.

## 기본 사용

```java
import com.inzent.apim.utilities.pojocodec.PojoCodecController;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class Profile {
    private String name;
    private int age;

    public Profile() { }

    public static void main(String[] args) throws Exception {
        Profile profile = new Profile();
        profile.name = "홍길동";
        profile.age = 30;
        PojoCodecController codec = new PojoCodecController();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.serialize(output, profile);
        Profile restored = codec.deserialize(
                new ByteArrayInputStream(output.toByteArray()), Profile.class);
        System.out.println(restored.name);
    }
}
```

- `serialize(stream, bean)`은 실제 루트 클래스를 사용한다. null의 타입을 추론할 수 없어 null 루트는 거부한다.
- `serialize(stream, Profile.class, bean)`은 지정한 클래스와 상속 필드만 기록한다. 하위 클래스 전용 필드를 제외하며 참조 타입 null 루트도 허용한다.
- `deserialize(stream, Profile.class)`는 한 문서만 읽으므로 연속 문서를 처리할 수 있다. 단일 문서 후 EOF를 요구하려면 `deserializeSingle(stream, Profile.class)`를 사용한다.
- `serialize(stream, bean, field)` / `deserialize(stream, bean, field)`는 독립 필드 레코드 하나를 처리한다. static/transient/final 필드는 거부한다.
- 스트림은 호출자가 close/flush한다. 실패한 스트림의 재사용은 보장하지 않는다.

## null·empty·시간대 옵션

```java
import com.inzent.apim.utilities.pojocodec.annotation.PojoCodec;
import com.inzent.apim.utilities.pojocodec.annotation.NullPolicy;
import com.inzent.apim.utilities.pojocodec.annotation.EmptyPolicy;
import com.inzent.apim.utilities.pojocodec.annotation.ZonePolicy;
import java.time.ZonedDateTime;
import java.util.List;

@PojoCodec(nullPolicy = NullPolicy.INCLUDE, emptyPolicy = EmptyPolicy.INCLUDE)
class Message {
    String value;
    List<String> items;
    @PojoCodec(zonePolicy = ZonePolicy.INCLUDE)
    ZonedDateTime createdAt;
}
```

기본 직렬화는 null 및 empty Collection/Map 필드를 생략한다. 누락 필드와 독립 필드의 ABSENT는 생성자 초기값 또는 현재 값을 유지한다. 명시적 NULL을 읽으면 독자의 옵션이 OMIT이어도 참조 필드에 null을 대입한다. primitive NULL은 오류다. 컨테이너 내부 null 원소/key/value는 위치·항목을 보존하고 대상 컨테이너의 허용 여부를 검사한다.

날짜·시간은 long 밀리초로 저장한다. 밀리초 미만 정밀도는 복원하지 않으며 Instant의 음수 epoch는 과거 방향으로 내린다. LocalDate는 UTC 자정, LocalTime은 하루 시작부터의 밀리초, LocalDateTime은 UTC offset 0을 사용한 지역 성분 표현이다. ZonedDateTime/OffsetDateTime은 zone을 기본 생략하고 UTC로 복원한다. `zonePolicy = INCLUDE`를 지정하면 원래 zone/offset도 보존한다. 시스템 기본 시간대를 포맷에 저장하지 않는다. JDBC Date/Time은 공개 API의 지역 날짜·시간 성분을 사용하며 Time의 밀리초 잔여값도 보존한다.

## 사용자 Codec

클래스는 `AbstractTypeCodec<T>`, 필드는 `AbstractFieldCodec`을 상속한다. 각각 `writeBody/readBody` 또는 `writeField/readField`를 구현한다. 예를 들어 생성자로만 복원할 수 있는 타입도 Type Codec으로 지원할 수 있다.

```java
public final class TokenCodec extends AbstractTypeCodec<Token> {
    @Override
    public void writeBody(OutputStream stream, Token bean, EncodeContext context)
            throws IOException {
        context.writeValue(stream, bean.text(), String.class);
    }

    @Override
    public Token readBody(InputStream stream, Class<Token> type, DecodeContext context)
            throws IOException {
        return new Token((String) context.readValue(stream, String.class));
    }
}
```

위 예제의 Token은 `text()`와 `Token(String)`을 제공하는 애플리케이션 타입이며 `@PojoCodec(typeCodec = TokenCodec.class)`를 지정한다. Codec과 컨텍스트는 `com.inzent.apim.utilities.pojocodec.codec` 및 `com.inzent.apim.utilities.pojocodec`에서 import한다. field annotation에는 `fieldCodec`을 지정한다. Codec의 public 인자 없는 생성자를 사용하거나 Builder에 인스턴스를 등록한다.

```java
PojoCodecController codec = PojoCodecController.builder()
        .registerType("token", Token.class)
        .registerCodec("token-body", 1, new TokenCodec())
        .build();
```

Codec은 자기 본문만 처리하고 읽을 때 전부 소비해야 한다. NULL/ABSENT에는 본문 훅이 호출되지 않는다. 중첩 값은 컨텍스트의 writeValue/readValue를 사용하여 제한·타입 검사를 재사용한다. 버전 확장은 `supportsVersion(int)`을 override하고 수신 버전은 `context.codecVersion()`으로 분기한다. 등록한 현재 버전도 읽을 수 있어야 한다. Codec 인스턴스는 thread-safe해야 한다.

## JDK 컨테이너와 등록

일반 List/Set/Map, 배열, Queue/Deque, TreeSet/TreeMap/PriorityQueue, EnumSet/EnumMap, factory immutable 및 Collections wrapper, concurrent/blocking 계열을 기본 지원한다. [지원표와 복원 규칙](project.md#631-기본-제공-컨테이너-codec)을 참조한다. JDK 내부 필드에 대한 reflection이나 `--add-opens`는 사용하지 않는다.

- 불변·고정 크기·checked·synchronized wrapper는 공개 factory/wrapper로 복원한다. JDK 내부 클래스명과 원본 backing 객체 공유는 계약에 포함하지 않는다.
- 정렬·우선순위는 자연순과 역순을 기본 지원한다. 임의 comparator는 `.registerComparator("length", comparator)`로 양쪽 컨트롤러에 등록한다.
- 빈 EnumSet/EnumMap은 선언 제네릭으로 enum 타입을 결정한다. raw empty 컨테이너는 `.registerContainerPolicy(containerClass, ContainerPolicyRegistry.Policy.enumType(MyEnum.class))`를 사용한다.
- checked wrapper의 검사 타입을 제네릭으로 알 수 없으면 `Policy.checked(String.class)` 또는 `Policy.checkedMap(String.class, Integer.class)`를 등록한다. 생성 정책 등록 단위는 런타임 클래스다.
- 공정성·LinkedHashMap access-order는 공개 getter가 없어 필요하면 `new Policy(enumType, checkedKeyType, checkedValueType, fair, accessOrder)`로 공급한다. 기본은 공정성 false, 기록 순서를 삽입 순서로 복원한다.
- bounded Queue는 capacity를 보존한다. ArrayBlockingQueue의 capacity도 `containerItems` 제한 이하여야 한다. SynchronousQueue는 저장 항목 0으로 복원한다. DelayQueue는 만료 전 원소도 포함하며 Delayed 구현 타입의 등록·Codec이 필요하다.
- 뷰는 독립 컨테이너로 복원한다. 대기 스레드·락·GC 상태·transfer 작업·원본 backing 연결은 저장하지 않는다. 동시성 snapshot은 여러 항목의 원자적 시점 일관성을 보장하지 않는다.

인터페이스·Object 필드의 실제 POJO/enum/참조 배열 타입은 `.registerType("stable-id", ActualType.class)`로 사전 등록한다. 정적으로 선언된 타입은 자동 발견한다. 임의 입력의 클래스명으로 클래스를 로딩하지 않는다.

## 호환성·제한

필드 추가·삭제·순서 변경과 primitive/boxed 대응, byte→short/int/long, short→int/long, int→long, float→double 확대를 지원한다. 필드 rename은 삭제+추가로 처리한다. 패키지·클래스명 변경은 양쪽에서 같은 논리 타입 ID를 등록한다. 루트 POJO의 수신 ID는 요청 타입 ID와 같아야 한다. 객체 공유·순환 참조와 일반 final 필드는 기본 POJO Codec으로 복원하지 않는다.

기본 제한은 문서 64 MiB, 프레임 16 MiB, 문자열 1 MiB, 식별자 4 KiB, 컨테이너 100,000항목, POJO 10,000필드, 깊이 64, 활성 출력 버퍼 128 MiB, 누적 항목 1,000,000개다. `builder().limits(new CodecLimits(...))`로 조절한다. `PojoCodecException`은 경로와 원인·기대 타입을 제공한다. 잘린 데이터·잘못된 길이·중복 key/원소·금지된 변환은 오류이며, 밀리초 변환으로 key가 충돌해도 덮어쓰지 않는다.

## Jackson 비교와 최적화 경로

반복 호출에서는 같은 controller를 재사용한다. 기본 Codec을 사용하는 일반 POJO의 primitive/boxed·String·primitive 배열·타입이 정해진 ArrayList 중첩 경로는 v1 바이트 형식을 유지하면서 실행 계획·필드 헤더를 캐시하고, 길이 위치를 나중에 수정하는 단일 버퍼를 사용한다. root 클래스당 64필드 이하이며 순환 타입 그래프가 없는 경우에 적용한다. 그 밖의 타입·사용자 Codec·다른 컨테이너는 기존 범용 경로로 처리한다. 의미와 호환성은 동일하지만 모든 입력에서 같은 성능 개선을 보장하지 않는다.

스크래치는 controller·스레드별로 최대 64 KiB의 바이트 배열을 보관한다. 더 큰 배열은 호출 종료 시 보관하지 않으며 재진입 호출은 별도 스크래치를 쓴다. 스크래치가 controller/Registry를 역참조하지 않도록 구성하여 폐기한 Codec 인스턴스가 작업 스레드에 붙잡히지 않는다.

`PojoInjectable` 타입이 정적으로 포함된 그래프는 추측 실행 후 fallback 과정의 중복 콜백을 방지하기 위해 범용 경로로 처리한다. 비대상 POJO의 최적화 경로는 유지한다.

`benchmark` profile에서만 Jackson/JMH test 의존성을 사용한다. 아래 명령은 Java 17, 동일 스트림 조건, warmup 5회, 측정 5회, JVM fork 2회로 CPU 처리 시간(us/op)과 할당량(B/op)을 비교한다.

```powershell
./pojo-codec/benchmarks/Run-Benchmarks.ps1
./pojo-codec/benchmarks/Test-Performance.ps1 -ResultPath pojo-codec/target/performance-final.json
```

판정은 flat·nested·numeric 각 직렬화/역직렬화 6개 조합에서 시간과 할당량이 모두 감소하고 JMH 신뢰구간이 겹치지 않아야 통과한다. 연산당 할당량은 유지 힙이나 RSS 측정과 구분한다. 측정 환경·원본 데이터·구체적인 결과는 [performance.md](performance.md)에 기록한다.

## PojoInjectable 콜백

POJO가 `com.inzent.apim.utilities.pojocodec.PojoInjectable`을 구현하면 해당 인스턴스의 생명주기 콜백을 실행한다. 여섯 메서드는 default no-op이므로 필요한 것만 override한다.

| 메서드 | 호출 시점 |
| --- | --- |
| `void beforeSerialize()` | 직렬화 시작 전, 필드 값을 읽기 전 |
| `void afterSerialize()` | 루트 문서 출력 완료 후, 중첩 객체는 해당 값 프레임 기록 완료 후 |
| `void onSerializeFail(Throwable cause)` | before/본문/after 또는 출력 실패 시 |
| `void beforeDeserialize()` | bean 생성 직후, 필드 주입 시작 전 |
| `void afterDeserialize()` | 값 프레임 검증 완료 후, 루트는 문서 전체 검증 후 |
| `void onDeserializeFail(Throwable cause)` | 생성된 bean의 before/본문/after 또는 입력 검증 실패 시 |

```java
import com.inzent.apim.utilities.pojocodec.PojoInjectable;

public class InjectableMessage implements PojoInjectable {
    private String value;
    private transient Throwable lastFailure;

    @Override
    public void beforeSerialize() {
        if (value != null) {
            value = value.trim();
        }
    }

    @Override
    public void afterDeserialize() {
        // 이 시점에는 수신 필드 주입과 프레임 검증이 끝나 있다.
    }

    @Override
    public void onDeserializeFail(Throwable cause) {
        lastFailure = cause;
    }
}
```

- 루트·중첩 POJO와 지원하는 사용자 Collection/Map 하위 타입에 적용한다. 컨테이너는 생성 후 원소를 넣기 전에 beforeDeserialize를 호출한다. enum이 구현한 경우 새 인스턴스를 만들지 않고 조회한 상수 인스턴스에서 beforeDeserialize를 호출한다. null 및 인스턴스를 얻기 전 오류에는 콜백이 없다.
- beforeSerialize의 필드 변경은 출력에 반영한다. afterSerialize의 변경은 이미 기록한 값에 영향을 주지 않는다. beforeDeserialize의 초기값은 수신 필드로 덮어쓸 수 있으며, 누락된 필드는 유지한다.
- `deserializeSingle`은 EOF 확인까지 통과해야 afterDeserialize를 호출한다. 중첩 값 프레임이 손상되면 그 객체의 완료 콜백 대신 실패 콜백을 호출한다.
- 자식 오류는 자식 실패 콜백 후 부모 실패 콜백으로 전파한다. 뒤에 발생한 루트 출력 오류가 이미 완료된 자식의 콜백을 다시 호출하지는 않는다.
- 독립 field API는 새 객체를 생성하지 않고 기존 owner bean의 전/후/실패 콜백으로 감싼다.
- before/after가 던진 RuntimeException/Error도 실패다. 실패 콜백이 추가로 던진 오류는 원래 Throwable의 suppressed에 보존한다. 필드나 출력의 변경을 rollback하지 않는다. 중첩 오류는 PojoCodecException으로 포장될 수 있으며 원래 cause를 보존한다.
- 생략된 null/empty 필드의 값에는 콜백을 호출하지 않는다. 빈 사용자 컨테이너의 콜백도 필요하면 해당 필드에 `emptyPolicy = INCLUDE`를 지정한다.

### 사용자 Type Codec과 인스턴스 생성

injectable 사용자 Type Codec은 `createInstance(Class<T>, DecodeContext)`와 `readInto(InputStream, T, DecodeContext)` 경로를 사용한다. createInstance는 기본 인자 없는 생성자를 사용하며 필요하면 override한다. 라이브러리가 생성된 인스턴스의 beforeDeserialize를 호출한 뒤 그 **같은 인스턴스**를 readInto에 전달한다. readInto는 컨텍스트의 readValue로 값을 읽어 bean에 대입한다.

```java
@Override
public void readInto(InputStream stream, MyBean bean, DecodeContext context)
        throws IOException {
    bean.setValue((String) context.readValue(stream, String.class));
}
```

객체 생성과 읽기를 한꺼번에 수행하는 기존 readBody만으로는 생성 직후 훅을 보장할 수 없다. 따라서 injectable 사용자 Codec에서 readInto가 없으면 명시적으로 실패한다. DefaultTypeCodec의 readBody를 사용자 정의한 하위 클래스도 readInto를 명시적으로 override해야 한다. 비대상 타입의 기존 readBody 계약은 유지한다. 사용자 Field Codec도 중첩 POJO는 컨텍스트의 readValue/writeValue를 사용해야 공통 콜백·제한 처리가 적용된다.
