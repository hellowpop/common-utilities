# HTTP/2 multiplex·HT5 route 확장 계획

> `writing-plans`, `executing-plans`, `test-driven-development` 절차를 적용한다. 사용자 지시에 따라 같은 단일 Maven 프로젝트에서 확인 질문 없이 구현한다.

## 설계

물리 연결 상한과 HTTP/2 stream lease를 분리한다. H1은 기존 exclusive lease를 유지하고 H2는 같은 route의 연결을 여러 context가 공유한다. 설정 `maxConcurrentStreamsPerConnection`은 기본 100으로 추가한다. Netty는 서버 SETTINGS의 상한과 GOAWAY를 관측한다. Apache는 서버 stream 제한을 native scheduler로 적용하고, 대기 중인 요청에도 공통 acquire deadline을 적용한다.

HT5는 minimal endpoint 대신 full async client와 message multiplexing connection manager를 사용한다. `HttpRoute`에 proxy와 localAddress를 명시하고 CONNECT 뒤 목적지 TLS/ALPN/mTLS를 유지한다. Apache가 연결을 실제 준비한 지점의 신호를 호출 스레드로 전달해 connectResult를 발행한다. 각 실행은 독립 producer/consumer/context와 cancel future를 가지며 shared transport 자체를 취소하지 않는다.

수동 Socket CONNECT 후 Apache에 위임하는 방식보다 Apache 공식 route 수립 경로를 채택한다. 인증·TLS·바인딩·종료 책임을 transport 내부에서 일관되게 처리하기 위함이다. 원본 HTTP/1.1 raw 전송 경로는 유지한다.

## 작업과 검증

1. [x] 실제 TLS H2 fixture에서 연결 수·동시 active stream·요청 body·source IP를 관측한다. 한 연결에서 여러 요청이 동시에 도착해야만 반환하는 테스트를 작성하고 기존 구현에서 실패를 확인한다.
2. [x] 공통 pool을 shared/exclusive lease로 변경한다. H2 stream 실패·취소는 다른 실행을 중단하지 않고 active 연결의 TTL/GOAWAY는 draining으로 처리한다.
3. [x] Netty SETTINGS/GOAWAY 관측과 shared stream 실행을 연결한다. configured/server 상한, acquire timeout, 취소 격리와 슬롯 반환을 검증한다.
4. [x] HT5 full async route·CONNECT Basic 인증·localAddress를 구현한다. direct/proxy local bind, 잘못된 proxy 인증, TLS 실패, 동시 stream과 원본 auth 비유출을 검증한다.
5. [x] README/project.md 지원표·기본값·예제를 갱신한다. 독립 리뷰 시도 및 자체 검토, 지정 formatter, JDK 17 전체 verify 및 일반/소스/문서 JAR·테스트 생략 install을 확인한다.

## Review Focus

- 첫 연결 생성 경쟁에서 물리 상한 초과/중복 stream slot/취소 전파.
- 서버 SETTINGS 변경·GOAWAY 이후 신규 lease와 active stream 수명.
- acquire timeout/cancel 시 native queue에 남은 요청의 뒤늦은 전송.
- CONNECT 인증 헤더가 TLS 목적지 요청으로 전달되지 않는지.
- proxy/localAddress/mTLS/hostname 검증과 실행 알림의 스레드·순서.

## 진행 기록

- 기존 develop의 미커밋 구현과 다른 모듈 변경을 보존한다. 자동 commit/push는 수행하지 않는다.

- RED/수정: 최초 구현에서 단일 연결 동시 요청·localAddress 실패를 확인했다. Apache 시작 상태/초기 sharing cache 및 stream 취소 dependency를 보완했다.
- HttpCore 안정 버전 5.5의 half-closed stream reset 수정을 확인하고 core/h2를 직접 의존성으로 고정했다. 대기 취소 명령은 IOSession decorator에서 전송 전에 제거한다.
- 자체 검토에서 응답 저장 한도 초과와 GOAWAY 중 취소가 sibling stream에 미치는 영향을 각각 실패 테스트로 재현하고 수정했다.
- 독립 리뷰 agent는 워크스페이스 크레딧 제한으로 실행을 완료하지 못했다. 독립 리뷰 결과가 있는 것으로 처리하지 않고 작성자 자체 검토와 회귀 테스트로 검증한다.

- 최종 JDK 17 PARANOID clean verify: 78개 테스트, 실패/오류/생략 0건, LEAK 보고 0건.
