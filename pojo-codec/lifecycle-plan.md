# PojoInjectable 생명주기 확장

- 요청일: 2026-11-19. 사용자의 구현 요청에 따라 확인 질문 없이 진행한다.
- 목표: PojoInjectable을 구현한 POJO에 직렬화/역직렬화 전·후·실패 콜백을 호출한다.

## 계약과 선택

컨트롤러에서 루트 작업을, 범용 Engine에서 중첩 POJO 작업을 관리한다. 최적화 경로 안에서 콜백을 실행하면 추측 실행 후 fallback 시 중복 호출될 수 있으므로 injectable 타입이 정적으로 포함된 그래프는 범용 경로를 선택한다. 비대상 POJO의 최적화는 유지한다. 동적으로 나타난 injectable 하위 타입은 기존 fallback에서 처리한다.

- interface의 여섯 메서드는 default no-op으로 제공하여 필요한 이벤트만 override할 수 있다.
- beforeSerialize는 필드 조회 전, afterSerialize는 루트 문서 출력 또는 중첩 본문 기록 완료 후 호출한다.
- beforeDeserialize는 새 bean 생성 직후 필드 주입 전에 호출한다. 루트 afterDeserialize는 문서 전체 검증 후이며 deserializeSingle은 EOF 확인도 포함한다.
- 중첩 객체는 각 출현의 값 프레임 처리 단위로 전/후/실패를 호출한다. 뒤에 발생한 루트 스트림 오류는 이미 완료한 중첩 객체를 다시 실패 처리하지 않는다. null/empty 생략으로 방문하지 않은 필드 값에는 훅을 호출하지 않는다.
- 독립 field API는 기존 owner bean을 대상으로 전/후/실패를 호출하며 새 인스턴스를 만들지 않는다.
- before/after 콜백의 RuntimeException/Error도 실패로 처리한다. 실패 콜백이 던진 추가 오류는 원래 Throwable의 suppressed에 보존한다. 이미 일어난 필드/출력 변경은 rollback하지 않는다.
- 생성 전 입력 오류와 null에는 인스턴스 콜백을 호출하지 않는다.
- 사용자 Type Codec의 기존 readBody는 객체 생성·읽기가 한 메서드여서 beforeDeserialize 시점을 보장할 수 없다. injectable 사용자 Codec에는 createInstance/readInto 확장점을 제공한다. readInto 미구현은 명시적 오류로 처리한다. 기존 비대상 사용자 Codec은 readBody 경로를 유지한다.

## 구현·검증 순서

1. 콜백 순서·필드 변경·루트 I/O 실패·중첩·strict EOF·추가 실패 예외·사용자 Codec 테스트를 먼저 작성하고 실패를 확인한다.
2. interface 및 공통 실패 처리, Session의 루트 인스턴스 추적을 구현한다.
3. 컨트롤러 루트/field API와 Engine POJO 생성·주입 경로에 연결한다. injectable compiled plan을 제외하여 fallback 중복을 막는다.
4. 전체 기능 테스트, package/source/docs 및 benchmark 비교를 검증하고 README/project 문서를 갱신한다.

이번 요청에서는 commit/push를 자동 수행하지 않는다. 기존 사용자 변경 command.md와 data/는 보존한다.

2026-11-19 후속 푸시 요청으로 관련 요구사항 command.md를 포함한 모듈 변경과 포맷 설정을 커밋·푸시한다. data 및 java-performance-tuning의 별도 변경은 포함하지 않는다.

## 구현 기록

Java 17 클린 빌드 전체 테스트 78개 통과. callback-before/body/after 순서, IOException·RuntimeException·Error 및 suppressed, root/중첩/custom container/enum/field API, 생성 전 오류·null·strict EOF, 기존 readBody와 신규 readInto 계약, 동적 하위 타입의 compiled fallback 중복 방지를 검증했다. 중첩 Type Codec 내부 본문뿐 아니라 외부 값 프레임의 길이 검증 후에 완료 콜백을 실행하도록, Session에서 현재 생성 중 인스턴스를 프레임별로 저장·복원한다. 손상된 중첩 외부 프레임의 after-before-failure 오류는 실패 테스트로 재현 후 수정했다.

별도 정적 리뷰에서 enum의 callback 범위 누락을 확인하여 상수 인스턴스 조회 후 beforeDeserialize를 호출하도록 확장했다. 사용자 Map.Entry 하위 클래스의 원본 타입 복원은 기존 포맷에서 지원하지 않으므로 새로운 injectable 인스턴스를 만들어 주지는 않는다. 범용 scalar와 정규화된 JDK Entry는 대상 인스턴스가 해당 인터페이스를 구현하는 범위에서만 콜백을 적용한다.

콜백 비대상 POJO에 대해 기존과 동일한 정식 JMH 조건으로 재측정한 6개 조합도 시간·할당량 및 신뢰구간 기준을 모두 통과했다. 사용자 콜백 비용이나 injectable 그래프의 범용 경로까지 Jackson 대비 우위를 뜻하지는 않는다.
