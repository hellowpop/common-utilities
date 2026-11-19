# CPU·리소스·NPE 점검 및 조치

점검일: 2026-11-19. 대상: `http-client-executor`의 executor/manager 수명, 공통 pool/HTTP/1.1 송수신, BodyStore, Java·HT5·Netty 연결 및 HTTP/2 송수신 경계.

## 재현한 문제와 수정

| 구분 | 원인과 영향 | 조치·회귀 검증 |
|---|---|---|
| CPU 작업량 | 실행마다 10ms interrupt 감시 작업을 등록해 동시 요청 N개에서 최대 초당 약 100N회 scheduler dispatch 발생 | 활성 요청이 있을 때 executor당 감시 작업 하나로 통합. 요청 스캔은 여전히 O(N)이지만 dispatch/queue 갱신을 줄인다. 12개 동시 실행에서 deadline 12개와 감시 최대 1개 검증 |
| 타이머 메모리 | 기본 scheduled executor는 취소 작업을 만료까지 큐에 남긴다. 준비 실패 100건 직후 큐에 200개 잔류 재현 | `ScheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true)`. 완료 후 큐 0개 검증. 취소된 task 노드의 잔류이며 모든 context 본문이 그대로 잔류한다는 의미는 아님 |
| manager 메모리 | 장수 manager가 개별 close된 executor를 계속 보관 | close 정리 뒤 unregister. 생성·종료 100회 후 등록 목록 0개 검증 |
| 임시 파일 누수 | BodyStore의 seal/출력 close가 IOException을 던지면 파일 삭제를 건너뜀 | 출력 close 실패에도 삭제 시도, 두 오류가 있으면 suppressed로 보존. 삭제 실패 시 다음 close에서 재시도. 실제 파일 출력 close 후 예외 주입으로 파일 삭제 검증 |
| NPE | 사용자 `RequestBody.prepare`가 null 헤더를 반환하면 `toBuilder()`에서 NPE | 준비 단계에서 원인을 설명하는 IOException으로 실패 기록. 네트워크 연결 전에 거부 |
| Netty 입력 NPE | poll timeout 결과 null을 내부 block에 저장해 다음 read가 `block.length`에서 NPE | 성공적으로 받은 버퍼만 block에 반영. timeout 후 재읽기도 SocketTimeoutException으로 검증 |
| Netty 종료·할당 | EOF를 소비한 뒤 재읽으면 다시 poll하며 timeout. 한 바이트 read마다 새 배열 생성. 늦게 끝난 reader가 mutable `current`를 참조할 가능성 | stream에 실행 context를 직접 바인딩, EOF 유지, 한 바이트 버퍼 재사용, 빈 inbound ByteBuf 무시. EOF 연속 읽기 검증 |

## 자원 사용 계약

- manager와 executor는 재사용하고 종료 시 close한다. executor를 개별 close하면 manager 보관 목록에서도 제거된다.
- `ExecutionContext`는 단일 실행용이며 성공·실패 모두 execute 반환 후 close한다. 응답/body/capture 보관은 호출자 소유이므로 executor close가 context 파일을 대신 삭제하지 않는다.
- `BodyStore.openStream()`으로 연 입력 스트림은 context보다 먼저 닫는다. 파일 삭제가 OS 잠금 등으로 실패하면 close 예외를 처리하고 잠금을 해제한 뒤 다시 close한다.
- custom RequestBody는 빈 헤더라도 `HttpHeaders.builder().build()`를 반환해야 한다. 본문 interceptor의 null 반환은 기존대로 입력 배열 유지라는 의미다.
- pool/reader/Netty/Apache 자원은 executor가 소유한다. pool 종료에서 예외가 발생해도 timer 종료와 manager 등록 해제를 수행한다.

## 분석 범위와 남은 한계

이번 결과는 소스 분석과 결정적 회귀 테스트에 근거한다. 운영 CPU profiler/heap dump/장시간 부하 측정 자료는 없으며 CPU 사용률 감소 수치나 모든 누수 부재를 보장하지 않는다.

- 공통 pool의 20ms 대기와 HT5 HTTP/2의 최대 10ms future 대기는 유한한 blocking 대기다. 대기 요청 수가 크면 wakeup·pool 스캔 비용이 증가한다. 공유 interrupt 감시도 활성 context 수에 비례한다.
- HTTP/2 수신의 파일 spill은 I/O callback에서 동기 파일 쓰기를 수행한다. 느린 디스크에서는 event loop 지연 가능성이 있다. 저장 상한은 store별이며 전체 프로세스 메모리·디스크 상한은 아니다.
- 사용자가 제공한 body generator/interceptor가 무한 대기하면 강제 종료할 수 없다. 명시적 timeout과 취소는 네트워크 자원 정리를 위한 장치다.
- Netty timeout 후 재읽기와 EOF 반복은 내부 스트림 계약 테스트다. 정상 execute 경로에서는 read 오류 후 연결을 폐기하므로 해당 NPE가 모든 timeout에서 사용자에게 발생한다는 의미는 아니다.

## 검증

신규 `ResourceSafetyTest` 5개와 `NettyInputSafetyTest` 2개는 수정 전 실패를 확인하고 수정 후 JDK 17에서 통과했다. 실제 socket·파일을 사용하고, 비공개 queue/registry와 입력 스트림 경계는 테스트에서만 reflection으로 검사한다. 운영 코드에는 테스트 전용 API를 추가하지 않았다.

전체 검증 명령:

```powershell
$env:JAVA_HOME = 'D:/01.app/java/jdk-17.0.10'
mvn -f http-client-executor/pom.xml clean verify -Dio.netty.leakDetection.level=paranoid
# install이 필요한 경우 테스트는 별도 verify 후 생략한다.
mvn -f http-client-executor/pom.xml install -Dmaven.test.skip=true
```

최종 전체 테스트 및 패키징 결과는 `project.md`에 기록한다.
