# Hazelcast 고유 client 설정 파일

- 2026-11-04: 연결 설정을 `monitor.client-config` 경로 하나로 통일한다. 기본 경로 `./hazelcast-client.yml`. `.yml`/`.yaml`/`.xml`을 Hazelcast의 고유 builder로 읽는다.
- cluster-name, 주소, UDP Discovery SPI, 인증/TLS 및 client timeout은 고유 파일에서 관리한다. 기존 `monitor.cluster-name/members/lookup-mode/udp-*`는 명확한 오류로 안내한다.
- 수집 전 설정을 한 번 읽고 유효성을 검사해 DB 변경 전에 입력 오류를 반환한다. 파싱한 ClientConfig로 접속한다. cluster-id 생략 시 수집은 파일의 cluster-name, report/init은 dev를 사용하므로 환경별 cluster-id를 명시하는 사용법을 안내한다.
- report/init/install은 client 파일을 읽지 않는다. 모니터의 전체/연결 대기/개별 수집 제한과 내부 listener/statistics 설정은 기존처럼 관리한다. 고유 connection retry/invocation 설정 자체는 덮어쓰지 않는다.
- install은 `application.yml`과 `hazelcast-client.yml`을 생성하고 기존 파일을 보존한다. 실행 script가 설치 디렉터리로 이동하므로 상대 경로는 cron에서도 동일하다.
- UDP는 고유 client YAML의 discovery-strategies로 설정하며 서버의 동일 strategy/group/port가 필요하다.

## 구현 및 검증

- [x] YAML/XML, 경로 오류/파일 보존, 이전 옵션 거부, 독립 report/init 테스트를 먼저 작성하고 실패 확인.
- [x] 공통 loader, Options/collector, install/help 구현. 기존 테스트와 실제 UDP/실행 JAR 검사를 파일 설정 방식으로 전환.
- [x] 전체 Java 17 verify 및 고유 UDP 설정 실연결 검사. README/project.md를 최종 방식으로 갱신.

## 검증 결과

- Java 17 `mvn -f hazelcast-monitoring/pom.xml verify`: 57개, 실패/오류/건너뜀 0, BUILD SUCCESS, JAR 재생성. `clean`은 Windows의 target 디렉터리 핸들 잠금으로 루트 디렉터리 제거 단계에서 실패했으나 내부 파일은 비워졌으며, 이후 verify에서 소스/테스트 전체를 다시 컴파일했다. 로그 `target/hzmon-native-verify.log`.
- `-Dtest=UdpDiscoveryIT test`: 고유 client YAML의 UDP Discovery SPI로 주소 지정 없이 노드 1개와 Map count 1 수집 성공. 로그 `target/hzmon-native-udp.log`.
- 별도 Java 프로세스의 배포 JAR 검사: init → 고유 client 파일 연결·수집 3회 → report. 노드 `[2,2,1]`, orders count `[2,3,3]`, DB 조회·YAML 3개·HTML 확인. 로그 `target/hzmon-native-jar-smoke.log`.
- YAML/XML, CLI 경로 우선순위, 파싱 후 파일 제거 시 재읽기 없음, client retry/invocation 설정 유지, 파일 오류 시 DB 생성 방지, offline mode의 client 파일 독립, install 파일 생성/보존을 검증했다.
- README, project.md, 설치용 두 YAML 및 CLI help 갱신. PostgreSQL은 재검증하지 않았다. 커밋/push 미수행.
