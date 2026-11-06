# 자원 누수 / NPE 가능성 분석 및 조치

- 기준: 2026-11-04, 현재 Hazelcast monitoring 작업 트리(고유 client 파일 설정 변경 포함).
- 범위: client/listener/수집 executor, JPA factory/transaction, JDBC/Liquibase, 파일 입출력, Snapshot null 계약.
- 기존 다른 모듈 변경은 분석/수정 대상에서 제외한다.

## 확인 항목

| 항목 | 근거 / 영향 | 조치 |
| --- | --- | --- |
| client 종료 예외가 결과를 덮어씀 | finally의 shutdown 예외가 Snapshot 반환을 취소하며 CLI에서 DB 오류로 잘못 분류될 수 있음 | 읽은 결과 보존, 종료 실패는 PARTIAL/기존 FAILED에 기록, terminate fallback, 치명적 Error 보존 |
| 설정 재사용 시 내부 listener 누적 | 같은 ClientConfig에 실행마다 ClientReadiness listener 2개를 추가 | 실행마다 ClientConfig를 복사해 원본 listener/async/속성을 변경하지 않음 |
| rollback 예외가 원인을 덮어씀 | isActive/rollback 실패 시 body/commit 예외 유실, Error 경로는 명시 rollback 없음 | RuntimeException/Error 모두 rollback 시도, 정리 실패는 suppressed |
| factory 생성 실패 정리 | 생성자 실패 시 소유 factory의 명시 destroy 경로 없음. 실제 Hibernate 누수는 미확정 | 초기화 실패에도 destroy 시도하는 방어적 소유권 보강 |
| Snapshot 자식의 필수 null 허용 | countStatus=null/count=null 허용 후 name() NPE, memberUuid=null 후 YAML toString() NPE | 자식 record 생성 시 필수 필드·count 상태 검증 |
| executor 종료 확인 없음 | shutdownNow는 중단 요청이며 완료 보장이 아님 | 짧은 유한 대기와 미종료 진단, interrupt 복원. 외부 client/중단 무시 작업은 강제 종료 불가함을 문서화 |

## 진행

- [x] 재현 테스트를 먼저 작성하고 기존 구현 실패 확인.
- [x] 소유 client/listener, worker, JPA 실패 정리 및 Snapshot 계약 수정.
- [x] 독립 재검토, Java 17 전체 verify와 반복 수집/DB·파일 검증. README/project.md 갱신.

파일 stream, JDBC connection/statement/result, Liquibase와 EntityManager는 기존 try-with-resources 경로를 확인했다. 정상적인 Hazelcast API의 non-null 계약을 깨뜨리는 임의 mock 값은 실운영 NPE로 분류하지 않는다. 실제 heap dump/운영 장기 부하로 누수를 확정한 분석은 아니다.

일반적인 단발 CLI에서는 실행마다 프로세스가 종료되므로 listener 누적은 같은 Options를 JVM 안에서 재사용할 때 발생한다. 필수 필드 null도 정상 collector보다 public Snapshot 생성 경계에서의 위험이다. 반면 client 종료/DB rollback 실패는 장애 시 원인과 관측 결과를 유실시켜 복구 진단에 영향을 준다. factory 초기화 누수는 가능성을 방어적으로 차단한 것이며 실제 Hibernate 내부 누수로 확정하지 않았다.

## 재현 및 검증 근거

- 수정 전 전용 12개 테스트에서 실패 10개/오류 2개를 확인했고, 수정 후 12개 모두 통과했다. 최초 실패 로그 `target/hzmon-resource-red.log`, 수정 후 `target/hzmon-resource-tests.log`.
- JPA 초기화 테스트는 초기화 실패/미생성 객체/정리 실패를 주입해 소유 factory의 destroy 시도를 검증한다. 실제 Hibernate가 내부 정리를 누락한다는 운영 누수 재현은 아니다.
- collector 테스트는 같은 Options 반복 실행, 사용자 listener 보존, 종료 실패 후 결과 보존·terminate, worker Error 전파, 중단된 작업의 정리 완료를 검증한다. 치명적 종료 오류/terminate 실패/interrupt 복원도 추가했다.
- 독립 읽기 전용 검토에서 추가 코드 결함은 발견되지 않았으며 listener 방식과 worker 진단 문구를 실제 구현에 맞췄다.
- 최종 Java 17 `mvn -f hazelcast-monitoring/pom.xml verify`: 72개 테스트, 실패/오류/건너뜀 0, BUILD SUCCESS, 실행 JAR 재생성. 로그 `target/hzmon-resource-verify.log`.
- 실연결 `-Dtest=ResourceLifecycleIT,UdpDiscoveryIT test`: 2개 통과. 같은 Options로 TCP 수집 3회 후 매번 client registry가 실행 전 수준으로 돌아오고 `hzmon-observer` 생존 thread가 없음을 확인했다. 원본 listener 목록도 비어 있다. 고유 YAML의 UDP 연결·수집도 성공했다. 로그 `target/hzmon-resource-live.log`.
- 회귀 검증은 H2 JPA round-trip/rollback 경로, YAML/HTML 및 install/Windows·Git Bash launcher를 포함한다. PostgreSQL, OS 전체 handle 수/네트워크 socket 계수, 장기 heap/성능 부하는 이번에 검사하지 않았다. 외부 중단 무시 코드와 native shutdown 자체의 무한 대기까지 강제 종료를 보장하지 않는다.
- README/project.md 갱신. 커밋/push 미수행.
