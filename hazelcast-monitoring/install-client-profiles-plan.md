# 설치 시 연결 설정 profile 생성

> sample 정정: `hazelcast-client-multicast.yml`은 실제 UDP Discovery SPI 검색으로 변경했다. 기존 TCP seed 설정은 `hazelcast-client-member-seed.yml`로 분리해 명명과 동작을 맞췄다. 아래 최초 3-profile 생성 기록 이후 현재는 명시적 profile 4개를 제공한다. generic discovery-strategies 파일의 기본 전략은 multicast 예제와 같으며 SPI 확장용으로 별도 제공한다.

- 목적: 설치 후 TCP / 일반 서버 UDP multicast / Discovery SPI에 맞는 고유 client 설정 파일을 선택한다.
- 생성: `hazelcast-client-tcp.yml`, `hazelcast-client-multicast.yml`, `hazelcast-client-discovery-strategies.yml`, 선택 안내 `CLIENT-CONFIG-README.md`. 기존 `hazelcast-client.yml`은 호환용 TCP 파일로 계속 생성한다.
- 새 application.yml은 `monitor.client-config: ./hazelcast-client-tcp.yml`을 기본값으로 쓴다. 기존 설정은 덮어쓰지 않는다. CLI 경로 선택도 계속 지원한다.
- 일반 서버 multicast profile은 TCP seed를 사용한다. 주소 없는 member multicast 검색을 구현하거나 지원한다고 표시하지 않는다. 실제 UDP 검색은 server/client에 동일 strategy를 사용하는 discovery-strategies profile이다.
- 구현 순서: 생성/고유 YAML 파싱/기본 선택/재설치 보존·누락 복구 테스트 실패 확인 → templates/installer의 정적 Markdown 파일 지원/완료 안내 → Java 17 verify 및 실제 JAR 설치 확인 → README/project.md 갱신.

## 완료 및 검증

- sample 정정 검증: 수정 전 multicast profile에 TCP seed가 있어 `Expecting empty but was: [127.0.0.1:5701]`로 실패를 확인했다(`target/hzmon-multicast-sample-red.log`). 수정 후 설치 sample 파일 자체를 읽는 UDP 실연결과 두 설치 profile 검사 3개가 통과했다(`target/hzmon-multicast-sample-live.log`).
- 현재 명시적 파일은 TCP / UDP multicast / generic Discovery SPI / 일반 member multicast용 TCP seed의 4개다. UDP multicast는 Java client에서 Discovery SPI로 제공되므로 generic 파일도 기본 예제로 같은 strategy를 포함하며 다른 프로토콜인 것처럼 구분하지 않는다.
- 기존 multicast 파일을 자동 덮어쓰지 않으므로 백업/이름 변경 및 runtime 갱신 절차를 안내한다. 기존 일반 member multicast 접속 설정은 member-seed 파일로 전환한다.
- 정정 후 Java 17 verify: 74개, 실패/오류/건너뜀 0, BUILD SUCCESS, JAR 재생성. 로그 `target/hzmon-multicast-sample-verify.log`. 설치 sample 기반 UDP 실연결/설치 검증 3개 통과. 커밋/push 미수행.
- 신규 profile 설치 검사는 구현 전 누락 파일로 실패를 확인했다(`target/hzmon-profiles-red.log`).
- 고유 builder로 3개 profile의 cluster-name/주소/strategy/properties를 파싱했다. 사용자 수정 파일 보존과 누락된 discovery profile 복구를 검증했다.
- Java 17 `mvn -f hazelcast-monitoring/pom.xml verify`: 74개, 실패/오류/건너뜀 0, BUILD SUCCESS. 로그 `target/hzmon-profiles-verify.log`.
- 실제 배포 JAR `--install` 실행: runtime, 3개 명시적 profile, 호환용 client 파일, Markdown 안내서, application.yml 및 8개 script 생성 확인. 기본 TCP 경로와 완료 후 선택 안내 확인. data 디렉터리 생성 없이 종료 코드 0. 로그 `target/hzmon-profiles-install.log`.
- README/project.md 갱신, 실행 JAR 재생성. 이번 변경은 설치 템플릿/선택 안내이며 기존 실제 TCP/member multicast/Discovery SPI 접속 검증은 앞선 변경 기록을 따른다. 커밋/push 미수행.
