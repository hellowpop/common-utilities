# Processor 중심 header binding 변경

최신 추가 요청: processor 설정 stereotype은 필요 없으므로 제거한다. 아래 최초 binding 계획의 T annotation/default proxy 계약은 폐기하며, `AnnotationProcessor.process(Channel, FullHttpRequest)`와 `HttpResponseType`만 유지한다.

**요청:** `@HttpResponseType("copy-and-send")`를 processor 클래스에 선언하여 HTTP header 값과 processor를 직접 연결한다.

**변경 계약:** `HttpResponseType.value()`만 registry key로 사용한다. 기본 scan-packages는 processor 패키지로 옮긴다. action annotation의 value와 별도 정의 클래스, HttpResponseProcessor 메타 annotation을 제거한다. `AnnotationProcessor<T>`와 typed process 계약은 유지한다.

**설정 annotation:** processor에 T annotation을 명시하면 그 인스턴스를 사용하고, 생략하면 기본값으로 만든 불변 annotation 인스턴스를 사용한다. 필수 member를 생략하거나 runtime annotation이 아니면 시작 시 실패한다. 기본값 인스턴스는 Java annotation의 equals/hashCode/배열 방어 복사 계약을 따른다. 추가 의존성은 없다.

- [x] processor 직접 등록·중복·비정상 타입/생성자·기본/명시 annotation·필수 member 누락 테스트 작성 및 실패 확인.
- [x] HttpResponseType/registry/기본 annotation 및 processor 변경, action 정의 클래스 제거.
- [x] 설정·embedded·socket/JAR 테스트를 processor 중심 등록과 copy-and-send 헤더로 변경.
- [x] README/project.md의 확장·등록·scan 설정·호환성 변경 정리.
- [x] Java 17 clean verify, library/실행 JAR의 새 binding 동작 검증 및 focused 코드 리뷰.

기존 `copy` alias는 생성하지 않는다. builtin key는 `copy-and-send`이고 커스텀 processor는 명시한 value만 사용한다. fake-close/delay-close의 응답/예약 종료 계약은 유지한다. 사용자 지침대로 확인 질문 없이 대상 모듈만 변경하며 commit/push는 하지 않는다.

## 실행 결과

- 2026-11-02 JDK 17.0.10: clean verify 성공, 총 41개/실패 0/오류 0/건너뜀 0.
- 실행 JAR와 library 소비자의 새 registry 경로 기동·응답·종료를 검증했다. focused 코드 리뷰에서 추가 지적 없음.
- 기존 binding 테스트의 미구현 실패를 먼저 확인했으며, 기본/명시 annotation, 필수 설정, 중복/잘못된 processor와 legacy copy 헤더 거부를 포함한다.

## Stereotype 제거 작업

- [x] annotationType/validate/T 없이 동작하는 processor API 테스트 실패 확인.
- [x] Response*Action annotations, ConfiguredAnnotations 및 generic binding 제거. processor 직접 호출.
- [x] registry/handler 테스트와 README/project.md의 API·확장 절차 수정.
- [x] Java 17 clean verify 및 실행/임베디드 JAR 회귀 검증, focused 코드 리뷰.

최종 결과: JDK 17.0.10 clean verify, 40개 테스트 통과(실패/오류/건너뜀 0). 간소화 API 테스트의 수정 전 실패를 확인했으며 별도 코드 리뷰에서 추가 지적 없음.
