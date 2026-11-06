# 일반 multicast 서버 cluster 대응

> 파일명 정정: 이 문서의 TCP seed 예제는 현재 `hazelcast-client-member-seed.yml`로 분리되었다. `hazelcast-client-multicast.yml`은 실제 UDP Discovery SPI 검색용이다. 일반 member multicast와 client Discovery SPI의 지원 범위는 동일하다.

- 목적: 서버 `network.join.multicast.enabled=true` 구성을 변경하지 않고 monitoring Java client가 접속한다.
- 제약: Java client의 Discovery SPI multicast와 서버의 일반 member multicast join은 별도 프로토콜이다. 서버 주소 없는 일반 multicast 자동 검색은 기본 Java client에서 지원하지 않는다. 서버 member로 일시 참가하거나 내부 join 패킷을 흉내 내지 않는다.
- 방향: 고유 client 설정의 `cluster-members`에 접속 가능한 seed 주소를 지정한다. 서버가 multicast로 발견한 전체 member 목록은 client 연결 후 서버에서 받으며 monitoring 수집 대상이 된다.
- 설치: `hazelcast-client-multicast.yml` 예제 파일을 생성/보존한다. `application.yml`의 `monitor.client-config`를 해당 경로로 지정한다. 실제 cluster-name/주소와 필요 시 TLS/인증은 사용자가 설정한다.
- 검증: 새 예제 설치 테스트 실패 확인 → template/installer 구현 → 실제 일반 multicast member 2개 + 고유 client 설정으로 node/count 수집, client 비멤버 상태 및 종료 확인 → Java 17 전체 verify.
- 서버 Discovery SPI 방식도 기존 고유 설정으로 계속 지원한다. README에 두 설정을 구분해 안내한다.

## 검증 기록

- 새 profile 설치 테스트는 구현 전 파일 부재로 실패하는 것을 확인했다(`target/hzmon-multicast-red.log`).
- `-Dtest=MemberMulticastIT,InstallTest#installProvidesNativeClientProfileForRegularMulticastMembersAndPreservesEdits test`: 2개 통과. 일반 multicast member 2개를 실제로 구성하고 고유 YAML의 seed 하나로 node=2, orders count=2 수집 성공. 수집 전후 서버 member 수 2 유지 및 client registry 복원을 확인했다.
- 설치 파일 고유 YAML 파싱, Discovery SPI 미사용, 재설치 시 수정 내용 보존을 검증했다. 로그 `target/hzmon-member-multicast.log`.
- 실연결 검사는 UDP multicast를 허용하는 호스트에서 `-Dtest=MemberMulticastIT test`로 실행한다. 기본 verify에서는 제외하며 모든 IT를 포함하는 기존 postgres-tests profile에는 포함된다.
- 최종 Java 17 `mvn -f hazelcast-monitoring/pom.xml verify`: 73개, 실패/오류/건너뜀 0, BUILD SUCCESS, 실행 JAR 재생성. 로그 `target/hzmon-member-multicast-verify.log`.
- README/project.md 및 설치용 설정 예제 갱신. 주소 없는 member multicast 검색은 추가하지 않았다. PostgreSQL/원격 운영망은 이번 검증 범위 밖이다. 커밋/push 미수행.
