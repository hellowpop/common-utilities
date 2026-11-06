# UDP lookup 연결 설정

> 최종 방식은 `native-client-config-plan.md`로 대체되었다. UDP는 고유 Hazelcast client YAML/XML의 discovery-strategies에서 설정하며 monitor.lookup-mode/udp-* 옵션은 제거했다. 아래 내용은 이전 구현 기록이다.

- 작성일: 2026-11-04
- 목적: client가 멤버 주소를 미리 지정하지 않고 UDP multicast discovery로 클러스터를 검색한다.
- `monitor.lookup-mode`: `tcp`(기본)/`udp`. `monitor.udp-group`: `224.2.2.3`, `monitor.udp-port`: `54327`.
- UDP는 내장 `MulticastDiscoveryStrategy`와 `hazelcast.discovery.enabled`를 사용한다. 서버도 같은 Discovery SPI/group/port를 사용해야 한다. 일반 member multicast join과는 별도 프로토콜이다. 검색 이후 데이터 연결은 TCP다.
- UDP에서는 members를 사용하지 않는다(설치 YAML의 기존 기본값이 있어도 무시). 외부 client-config와 lookup/UDP 옵션 혼용은 거부한다. TCP 모드에서 명시적 UDP 설정은 거부한다.
- IPv4 multicast 리터럴과 1..65535 포트를 연결 전 검증한다. DNS 조회로 설정을 검증하지 않는다. 기존 연결/전체 수집 deadline을 유지한다.
- 소스 범위: Options(선택/검증), ClusterCollector(client config), 설치 application.yml/help, README/project.md, 테스트. 다른 모듈의 기존 변경은 수정하지 않는다.

## 실행 순서

- [x] Options/ClientConfig 테스트를 먼저 추가하고 실패 확인: 기본 TCP, UDP 설정, CLI/YAML 우선순위, 잘못된 값/혼용.
- [x] Options와 client discovery 구성 구현. SDK 교체 환경에서도 SPI 클래스 이름으로 등록한다.
- [x] 로컬 multicast cluster/client 연결·수집 확인 및 Java 17 전체 verify.
- [x] 설치 YAML과 README/project.md에 서버/client 예제, 네트워크 조건 및 검증 결과 기록.

## 실제 연결 검증

- Java 17 / Hazelcast 5.7.0, 별도 multicast group/port와 임의 TCP 포트를 사용한 `UdpDiscoveryIT` 통과. 고정 TCP seed 없이 노드 1개, `udp-orders` Map count 1 수집 성공.
- 최초 실행에서 Discovery SPI의 정수 변환기가 문자열 입력을 요구하는 오류를 확인했다. port를 문자열로 등록하도록 수정하고 실제 서버/client 생성부터 수집까지 재검증했다.
- `mvn -f hazelcast-monitoring/pom.xml -Dtest=UdpDiscoveryIT,ClientDiscoveryTest test`: 5개 통과(이후 외부 YAML 회귀 테스트 1개 추가). 로그 `target/hzmon-udp-live.log`.
- 실제 multicast 검사는 네트워크 환경에 의존하므로 `UdpDiscoveryIT`는 기본 테스트에서 제외하며 위 명령으로 명시적으로 실행한다. 기존 `postgres-tests` profile은 모든 `*IT`를 포함하므로 해당 profile에서도 실행된다.
- 최종 Java 17 `clean verify`: 56개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS. 실행 JAR 재생성. 로그 `target/hzmon-udp-verify.log`. PostgreSQL은 이번 변경에서 재검증하지 않았다. 커밋/push 미수행.
