POJO bean export / import utilities

# 개요
POJO 를 설명할 수 있는 class / yml,json 같은 meta 를 표준형식인 definition으로 관리하고 각 meta 형식간 변환할 수 있고 meta 를 통해서 class 읽어서 활용하는 일련의 과정을 관리하는 manager 및 annotation 구현

# 개발제한
- lombok 사용
- jackson 사용 ( json, xml manage )
- 이외의 구현은 pure java 기능을 통해 구현함.
- 추가 dependency 필요시 제안

# 개발 내역
## manager
외부로 제공되는 서비스를 총괄함
- definition 관리
- class 를 통한 definition CRUD, 입력 class 를 key 로 관리함
- json, yml 을 통한 definition CRUD, string 기반 id 를 지정하여 관리함
- definition export / import
  - export java
    - class / package name 지정
  - export yml/json
    - export 형식과 output stream / file 등을 지정하여 출력함
- annotation type, annotation processor 관리
  - class 기반 definition
  - json/yml meta 기반 definition
    - class 로 등록된 annotation / annotation-process 에 대한 정보도 같이 포함한다.
  - class 기반 definition 에서 meta 기반 definition 으로  변환시 annotation 관련 정보도 포함하여 export 함
- json,xml meta 를 type 변환
```java
<T> T readAs(InputStream content,Class<T> clazz);
<T> T readAs(Reader content,Class<T> clazz);
<T> T readAs(String content,Class<T> clazz);
```
- external bean registry 를 통한 bean wire 관리
  - manager 에 external bean registry 를 통한 bean wire 관리

## definition
-
## annotation / annotation processors
class 형태의 meta에서 각 type / field 에 대한 선언과 해당 field 의 meta 변환 / serialize / deserialize 에서 injection 구조를 관리함
- field annotation processor define
- field data 형식 및 format 정의
    - field sequence
      ex) @Sequence(1)
    - data length ( string, array type)
      ex) @DataLength(10)
    - lpad / rpad
      string type 의 field 에서만 제한적 선언
      data length 와 필수로 병행 선언
      ex) @Lpad('0'), @Rpad('\0')
    - collection/map 의 max/min occurence
      ex) @Max(10), @Min(1)
- field annotation processor 지정 및 관리
    - field annotation processor 정의
    ```java
    public abstract class AnnotationAdvisor<T extends Annotation> {
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface SerializeNotify {
        String value();
    }

    public class SerializeNotifyAdvisor
            extends AnnotationAdvisor<SerializeNotify> {
    }
    ```
    AnnotationAdvisor 에는 bean 설정에 관여되는 interface 가 정의되고 bean 직렬화/역직렬화/메타변환 등의 operation 에서 injection 하는 포인트를 제공함

## external bean injection
- pojo 에서 참조하는 다른 bean 이 있을 경우 해당 bean 을 참조가능한 ExternalBeanRegistry interface 를 구현한 registry 를 통해 injection 할 수 있음
- external bean injection 을 위한 interface 정의
    ```java
  public interface ExternalBeanRegistry {
  	<T> T getBean(Class<T> clazz);
  	<T> T getBean(String name,Class<T> clazz);
  }
    ```
- class type pojo 생성에서 transient field 에 @Injection("bean-name") 으로 선언된 필드는 bean registry 에서 참조된 object wire 수행함.
    ```java
    public class OtherBean {
        @Injection
        transient InjectComponent injectComponent;
        @Injection("bean-first")
        transient NamedComponent firstNamedComponent;
        @Injection("bean-second")
        transient NamedComponent secondNamedComponent;
    }
    ```
- manager 에 ext
# 세부개발 내역
