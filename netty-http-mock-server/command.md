netty 를 사용한 http mock response server

# 개요
http 응답을 mock 하기 위한 server
응답의 패턴은 헤더를 참조하여 요청된 모의응답 전송하거나 오류 유발

# 개발제한
- java 17 기반
- executable jar 포멧
- 설정은 yml 로 읽고 dynamic 정보는 arguments 를 사용함
- lombok 사용
- netty framework 사용
- 이외의 구현은 pure java 기능을 통해 구현함.
- 잘 정리된 추가 dependency 필요시 제안

# 개발 내역
## http mock server
http server 역할 수행
요청헤더 'x-response-action' 의 값에 대응하는 annotation processor 를 실행하여 응답값을 client 로 전송한다.
- annotation type, annotation processor 관리
  - 초기 부팅시 classpath 스캔하여 action annotation 과 processor annotation 을 읽어 들임
## annotation / annotation processors
annotation 의 value 에 해단하는 값에 대응하는 annotation processor 를 선언한다.
- response action annotation 과 processor annotation 은 쌍을 이루어 생성함
- action annotation processor 지정 및 관리
```java
public abstract class AnnotationProcessor<T extends Annotation> {
   HttpFullResponse process(T annotation, io.netty.channel.Channel channel,HttpFullRequest request);
}

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpResponseProcessor {
    Class<? extends AnnotationProcessor<?>> value();
}

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@HttpResponseProcessor(ResponseCopyAndSendActionProcessor.class)
public @interface ResponseCopyAndSendAction {
    String value();
}

public class ResponseCopyAndSendActionProcessor
        extends AnnotationProcessor<ResponseCopyAndSendAction> {
}
```
## configuration
설정은 yml 로 지정가능하고 선언에서 ${...} 형태로 시스템변수/자바환경변수/argument 선언된 값 을 매핑하여 초기화 함.
- netty bootstrap 구성요소
- 서비스 포트

header value 와 processor bind 하는 방식을 아래와 같이 변경
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpResponseType {
	String value();
}

@HttpResponseType("copy-and-send")
public class ResponseCopyAndSendActionProcessor
		extends AnnotationProcessor<ResponseCopyAndSendAction> {
}
```
