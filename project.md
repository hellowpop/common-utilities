# Common Utilities

## 프로젝트 기준 경로

- 프로젝트 루트: `C:\Develop\codex-project\common-utilities`
- 기술문서: `project.md`
- Java: 21
- 클래스 패키지: `com.inzent.apim.utilities` 하위
- 첫 번째 설계 대상: `object-recycle-container` 독립 유틸리티 모듈
- Git 저장소: `https://github.com/hellowpop/common-utilities.git`
- 원격 이름: `origin`, 초기 브랜치: `master`
- 개발 브랜치: `develop`, 추적 브랜치: `origin/develop`. 이후 개발 작업은 `develop`을 유지한다.
- Git 관리 대상: 소스, 테스트, Maven 설정, `AGENTS.md`, `project.md`, `.gitignore`
- Git 제외 대상: Maven `target/`, IntelliJ `.idea/`, `*.iml`

## 구조 변경내역

- 2026-10-19: 사용자 push 요청에 따라 curl 실행기 구현·메모리 누수/NPE 보완·회귀 테스트·기술문서를 develop에 커밋하여 origin/develop에 반영하도록 준비했다. 변경된 AGENTS.md 규칙과 사용자 추가 CallPatterns 사용 샘플을 포함하며, push 전에 기술문서를 갱신하고 실행기 clean verify를 재확인한다.

- 2026-10-19: curl 실행기 메모리 누수/NPE 분석 및 보완. 닫힌 스트림 mark/reset NPE, unchecked/Error 이후 정리 중단, stdin 작업 시작·interrupt 실패의 자원 잔존 경로를 재현하고 수정했다. null 경계와 참조 해제·정리 재시도 회귀 테스트 12개 추가, clean verify 총 42개 통과. 상세는 `curl-http-mock-executor/resource-safety-review.md`에 기록했다.

- 2026-10-19: curl 실행기 최종 `clean verify` 30개 테스트 통과 및 README 예제 javac 검증 완료. 기존 네 모듈 147개 회귀 테스트를 포함해 전체 177개 통과. 독립 리뷰 재확인 완료, 커밋/push는 수행하지 않았다.

- 2026-10-19: `curl-http-mock-executor` Java 21 Maven 모듈 구현 및 README 추가. 기존 payload 생성기/Netty API를 사용해 curl 실행, Builder 헤더, pre/post 훅, 별도 stdout/stderr 파일, 1 MiB 본문 저장, Closeable 자원 관리와 실패 기록을 구현했다. 로컬 HTTP/HTTPS 실제 curl 테스트 및 독립 코드 리뷰로 헤더 검증·빈 헤더·로그 파싱·interrupt 처리를 보강했다.

- 2026-10-19: curl 실행기 ExecuteContext Builder에 `requestHeader(CharSequence, Object)` 및 `requestHeaders(HttpHeaders)` 설계 추가. 개별 추가/일괄 교체, 복사와 컨텍스트 격리, pre 수정 및 헤더 검증 순서를 명시했다. 설계 검토 단계 유지.

- 2026-10-19: curl 실행기 설계에 HTTPS 인증서 검증 기본 비활성화, stdout/stderr 별도 파일과 컨텍스트 참조, Closeable 기반 프로세스/스트림/임시파일 정리, Netty 직접 의존성과 HTTP 상수/API 사용을 반영했다. 설계 검토 단계 유지.

- 2026-10-19: curl 실행기 설계에 `ExecuteConfigure.additionalArguments` 추가. 토큰 단위 전달, 지원 옵션과 관리 옵션 충돌 검증, 프록시/TLS 설정, 최종 인자 기록 및 민감 값 마스킹을 정의했다. 설계 검토 단계 유지.

- 2026-10-19: `curl-http-mock-executor/project.md`에 curl 기반 HTTP 더미 요청 실행기 설계 검토안 추가. 기존 HTTP Payload Generator 재사용, 실행 컨텍스트와 pre/post 훅, 전송/인코딩, 1 MiB 본문 저장 경계, 실행 기록 및 실패/자원 수명을 정의했다. 설계 확정 후 구현하며 현재 구현은 시작하지 않았다.

- 2026-10-13: 사용자 push 요청에 따라 LDAP README와 문서 추가 내역을 develop에 커밋하여 origin/develop에 반영하도록 준비. push 전에 본 기술문서를 갱신하고 README Java 예제의 Java 21 컴파일을 확인했다.

- 2026-10-13: `ldap-connection-pool/README.md` 사용 문서 추가. 풀 생성·초기화·대여/반납·종료 라이프사이클, 생성 파라미터/기본값, LDAP 설정·엔트리 샘플, 전체 Java 사용 예제, 예외 계약 및 주의사항을 정리했다.

- 2026-10-13: 사용자 push 요청에 따라 LDAP 연결 풀 구현, package-private 클래스 분리, memory leak/NPE 보완 및 회귀 테스트를 develop에 커밋하여 origin/develop에 반영하도록 준비. push 전에 본 기술문서를 갱신했다.

- 2026-10-13: LDAP 풀 memory leak/NPE 분석 반영. 닫힌/완료된 검색 자원 추적 해제, 반환 시 목록·제공자 참조 해제, 파생 자원 중복 등록 방지, 정리 재진입 보호, iterator 수명 검사, Attribute 스냅샷/스키마 참조 해제, TLS 응답 래퍼 추가. 제공자 NPE/RuntimeException/Error 연결 폐기와 null 경계·동시 종료·복합 실패 테스트를 보강했다.

- 2026-10-13: LDAP 연결 풀의 내부 Entry/Lease/Resource를 `LdapPoolEntry`/`LdapContextLease`/`LdapContextResource` package-private 최상위 클래스로 분리. 내부 함수형 인터페이스도 `LdapContextFactory`/`LdapCleanupAction` 파일로 분리. 공개 API와 동작을 유지하고 LDAP 회귀 테스트 17개 및 JAR 빌드를 검증했다.

- 2026-10-13: `ldap-connection-pool` 독립 Java 21 Maven 모듈 추가. `com.inzent.apim.utilities.ldap.LdapConnectionPool`에서 Properties 기반 LDAP 연결, max/min/idle timeout, 대여별 close 프록시, Consumer 자동 반납, 전체 종료 및 파생 자원 정리를 구현. 기존 두 모듈과 의존성 없이 빌드한다.

- 2026-10-12: 사용자 push 요청에 따라 Partial Input 구현·메모리/NPE 보강·회귀 테스트를 develop에 커밋하고 origin/develop에 반영하도록 준비. push 전에 본 기술문서를 갱신했다.

- 2026-10-12: Partial Input 메모리/NPE 분석 반영. 닫힌 스트림의 배열·구간·채널 참조 해제, 파일 생성자의 null 리스트 선검증, 스트림 생성 실패 시 채널 정리 추가. 참조 해제와 close 이후 계약 및 생성 실패 회귀 테스트 추가.

- 2026-10-12: `partial-input` 독립 Java 21 Maven 모듈 추가. `com.inzent.apim.utilities.partial` 아래 배열/파일 부분 구간 검증, shrink, 선택 구간 InputStream을 구현. 기존 `object-recycle-container` 모듈과 의존성 없이 각각 빌드한다.

- 2026-10-12: Object Recycle Container 분석 결과 반영. 정리 훅 Error 발생 시 일괄 정리와 오류 전파를 보강하고, Lease/Token/Event를 식별자 기반으로 전환하여 반환·회수 후 강한 참조를 해제. Sub 소유자는 Thread 대신 threadId로 식별. NPE 계약 및 사용·초기화·정리 복합 실패 회귀 테스트 추가. 공개 API와 timeout 기본값은 유지.
- 2026-10-12: 사용자 push 요청에 따라 분석 리포트·안전성 개선·회귀 테스트를 develop에 커밋하여 origin/develop에 반영하도록 준비. push 전에 기술문서를 갱신했으며, 검증 결과는 아래 적용 기록에 유지한다.
- 2026-10-11: Object Recycle Container 설계 초안을 simple-http-forward-proxy/project.md에서 이 프로젝트로 이관. 프로젝트 기준 경로, 패키지, Maven 좌표 및 빌드 기준 디렉터리를 수정. 설계 검토 단계이며 구현은 시작하지 않음.
- 2026-10-11: Main만 thread-safe, Sub는 소유 스레드 전용으로 변경. Main 객체 수 initial/min/max 및 Supplier 생성 정책 추가. worker 반환과 patrol은 Main 관리 상태를 통해 처리하도록 수정.
- 2026-10-11: Main 생성자에 선택적 LeakListener 추가. 미지정 시 감지 대상 forceClose, 지정 시 Listener가 처리 여부를 결정하도록 누수 감지와 강제 종료를 분리.
- 2026-10-11: Main을 abstract 클래스로 변경하고 Supplier/ObjectLifecycle을 createNewHolder/isRecycleable/onRecycle/onDestroy 추상 훅으로 대체. Sub onBorrow, 생성 후 start 초기화, 재사용 Holder와 대여 핸들 분리 계약 추가.
- 2026-10-11: Holder 반환을 소유 Sub의 LIFO 구조에 보관하도록 변경. 부족 시 Main.newObject로 충당하며 Sub 반환 시 borrow/recycle 통계 불일치 warning 추가. worker 반환도 Sub 소유를 유지하는 전달 경로로 수정.
- 2026-10-11: `object-recycle-container` Java 21 독립 Maven 모듈 구현. Main 단일 락 수명 관리, owner 전용 Sub LIFO, 대여별 HolderLease, worker 반환함, patrol/LeakListener, initial/min/max 및 종료·실패 처리를 추가.
- 2026-10-11: 독립 코드 리뷰에서 할당 rollback의 소유권 경쟁과 추가 Holder의 LIFO 순서 오류를 재현하고 수정. 실패한 min 보충을 다음 borrow에서 재시도하도록 보완.
- 2026-10-11: 사용자의 GitHub push 요청에 따라 로컬 Git 저장소와 `origin`을 연결. 비어 있는 원격 저장소에 기존 로컬 브랜치 `master`로 초기 구현을 등록하도록 구성하고, push 전에 기술문서를 갱신. IDE 설정과 빌드 산출물을 Git 관리에서 제외.
- 2026-10-11: `master`에서 `develop` 브랜치를 생성. 원격 `origin/develop`을 추적하도록 구성하고 로컬 작업 브랜치는 `develop`으로 유지한다. push 전에 브랜치 운영 정보를 이 문서에 기록.

## LDAP Connection Pool (2026-10-13)

사용법은 [LDAP Connection Pool README](ldap-connection-pool/README.md)에 정리한다.

### 구조와 구현 방침

- `ldap-connection-pool/pom.xml`: 독립 Maven 좌표 `com.inzent.apim.utilities:ldap-connection-pool:1.0.0-SNAPSHOT`.
- `ldap-connection-pool/src/main/java/com/inzent/apim/utilities/ldap/LdapConnectionPool.java`: 공개 API 및 풀 상태 관리. 실행 시 외부 라이브러리 의존성 없음.
- 같은 패키지의 `LdapPoolEntry.java`, `LdapContextLease.java`, `LdapContextResource.java`: 연결 상태, 대여별 JDK 동적 프록시, 파생 자원 프록시를 담당하는 package-private 최상위 클래스. 내부 클래스를 사용하지 않는다.
- 같은 패키지의 `LdapContextFactory.java`, `LdapCleanupAction.java`: 생성/정리 경계의 package-private 함수형 인터페이스.
- 같은 패키지의 `LdapEnumerationIterator.java`, `LdapAttributeSnapshot.java`, `LdapStartTlsResponse.java`: iterator 수명 검사, 독립 속성 값과 대여 중 스키마 조회, TLS 응답의 제공자 참조 해제를 담당하는 package-private 최상위 클래스.
- 같은 패키지의 `src/test/java/.../LdapConnectionPoolTest.java`: 연결 생성 경계의 대체 Context와 JNDI InitialContextFactory를 이용하는 수명·실패·동시성 테스트.
- 같은 테스트 패키지의 `LdapPoolSafetyTest.java`: 참조 해제, 반복 검색, 재진입 정리, null 계약, 종료 경쟁, 속성/TLS의 clone·직렬화 및 복합 실패 회귀 테스트.
- 기존 Object Recycle Container의 스레드 소유 Sub 계약과 LDAP의 직접 borrow/close 계약이 달라 독립 풀로 구현한다. develop 작업 경로를 유지한다.
- 구현 순서: API·실패 테스트 → 미구현 실패 확인 → 연결 풀/프록시 구현 → 동시성·파생 자원 테스트 → 별도 코드 리뷰 및 재현 테스트로 보강 → 전체 모듈 빌드.

### 생성자 및 설정

```java
LdapConnectionPool(Properties properties)
LdapConnectionPool(Properties properties, int max, int min, long idleTimeoutMillis)
```

| Properties 키 | 기본값 | 의미 |
| --- | --- | --- |
| `ldap.pool.max` | `8` | 풀에서 관리하는 대여 중 + idle Context 최대 개수 |
| `ldap.pool.min` | `1` | 초기 생성 및 idle 정리 시 보존할 최소 Context 개수 |
| `ldap.pool.idleTimeoutMillis` | `300000` | 반납 후 idle 유지 시간(ms). `0`은 idle 만료 비활성화 |

명시적 생성자 인자가 Properties의 풀 설정보다 우선한다. 일부 키만 지정하면 나머지는 기본값을 사용한다.
`max >= 1`, `0 <= min <= max`, `idleTimeoutMillis >= 0`이어야 한다. timeout은 나노초 변환 가능 범위까지 허용한다.
잘못된 설정은 `IllegalArgumentException`, null Properties/Consumer는 `NullPointerException`이다.
Properties의 defaults 체인과 객체형 JNDI 값을 포함해 키/값을 얕게 복사한다. 호출자가 원본 키를 변경해도 풀에는 반영되지 않지만 가변 값 자체는 공유하므로 외부에서 변경하지 않는다.
풀 설정 키는 JNDI에 전달하지 않는다. 하위 JNDI 자체 풀과 중복되지 않도록 `com.sun.jndi.ldap.connect.pool=false`를 강제로 설정한다.

### API 및 수명 계약

- `void open() throws NamingException`: 최초에 `max(1, min)`개 연결을 생성하고 각 연결에서 `getAttributes("", new String[]{"1.1"})`를 호출해 설정된 base DN 접근까지 테스트한다. min=0이어도 초기 연결 테스트는 수행한다. 성공 이후 반복 호출은 무동작이며 실패한 초기 연결은 모두 정리하고 재시도할 수 있다.
- `LdapContext borrow() throws NamingException`: 필요하면 자동 open. idle 연결을 재사용하거나 max까지 생성한다. 각 대여마다 새 프록시를 반환하며 max 도달 시 대기 없이 `NamingException`을 발생시킨다.
- 대여 프록시의 `close()`는 자동 반납이다. 반복 close는 무동작, 반납된 프록시의 LDAP 호출은 `NamingException`이다. LdapContext는 AutoCloseable이 아니므로 직접 대여 시 `finally`에서 close한다.
- `void runWith(Consumer<LdapContext>) throws NamingException`: 정상/예외 경로 모두 자동 반납한다. Consumer 예외와 반납 예외가 함께 발생하면 Consumer 예외에 정리 오류를 suppressed로 추가한다. Consumer 자체는 checked exception을 선언할 수 없으므로 LDAP 호출의 NamingException은 람다 안에서 처리/변환해야 한다.
- `void dispose() throws NamingException`: idle 및 대여 중인 연결과 파생 자원을 모두 닫고 영구 종료한다. 진행 중인 LDAP 호출이 끝날 때까지 해당 Context의 락에서 기다린다. 한 연결의 close가 실패해도 나머지 정리를 시도하며 첫 오류에 후속 오류를 suppressed로 추가한다. 반복 dispose는 무동작이다. 종료 후 open/borrow는 `IllegalStateException`이다.
- 풀은 AutoCloseable이며 풀의 `close()`는 dispose와 같다. close 실패 시 해당 제공자의 실제 자원 해제 완료는 보장할 수 없지만 풀은 종료 상태를 유지한다.

### 재사용·동시성·자원 정리

- 풀 상태 변경/신규 연결 생성은 풀 락으로 직렬화한다. 서로 다른 대여 Context의 LDAP 호출은 병렬 실행되며 동일 대여 Context와 그 파생 자원의 호출은 동일 락으로 직렬화한다.
- idle 시간은 단조 증가 시계 `System.nanoTime()`으로 측정한다. daemon 정리 작업은 최대 1초 간격으로 min을 초과한 만료 연결을 정리하고 borrow에서도 정리한다. 사용 중인 연결은 idle 만료로 닫지 않는다.
- 환경 속성 변경, request controls 변경, reconnect, extended operation 및 제공자 예외(NamingException/RuntimeException/Error) 발생 연결은 반납 시 폐기한다. 원래 예외를 유지한다. 다음 borrow에서 min을 보충하므로 실패/폐기 직후에는 일시적으로 min보다 적을 수 있다.
- Context를 반환하는 newInstance/lookup/schema 등의 결과와 NamingEnumeration을 대여 수명에 묶어 프록시로 관리한다. Binding/SearchResult 내부 Context도 추적한다. 반납/종료 시 자식 자원을 먼저 닫고, Context가 파생된 루트는 재사용하지 않는다. 명시적인 자식 close 실패도 루트 폐기로 처리한다.
- 닫힌 열거자/자식 Context는 즉시 추적 목록에서 제거한다. hasMore/hasMoreElements가 false이면 열거자를 닫고 제공자 참조를 해제한다. 완료된 열거자의 추가 hasMore는 false, next는 NoSuchElementException이다. asIterator는 제공자 대신 프록시를 사용한다. 명시적으로 닫힌 열거자의 checked 호출은 NamingException, legacy Enumeration 호출은 IllegalStateException이다.
- Attribute/Attributes 및 SearchResult의 속성은 독립 값 스냅샷으로 반환해 반납 후에도 일반 값을 읽을 수 있다. 스키마 조회는 대여 중 제공자에 위임하고 반환 Context를 추적한다. 반납/종료 후에는 스키마 조회를 NamingException으로 거부하고 제공자 참조를 해제한다. clone도 같은 수명을 적용하며 직렬화본은 제공자 참조 없이 값만 보존한다. WeakReference/ReferenceQueue로 스냅샷을 등록해 사용하지 않는 값 객체를 강하게 붙잡지 않으며 등록 때 전체 목록을 반복 순회하지 않는다.
- StartTlsResponse는 package-private 하위 클래스 래퍼를 반환한다. TLS 메서드는 동일 대여 락에서 호출하고 명시적 close/반납/종료 시 제공자 참조를 해제한다. 닫힌 응답의 negotiate는 IOException, 설정 메서드는 IllegalStateException이다. Context/열거자/TLS 및 스키마 조회는 대여 수명 안에서만 사용한다.
- 풀 max는 루트 Context 개수이다. 파생 Context가 별도 네트워크 연결을 생성하는지는 JNDI 제공자에 달려 있으며 서버의 실제 TCP 연결 수 상한을 보장하지 않는다.
- connect/read timeout은 풀 idle timeout과 별개이다. 필요하면 `com.sun.jndi.ldap.connect.timeout`, `com.sun.jndi.ldap.read.timeout`을 Properties에 지정한다. base DN 읽기 권한도 open 테스트에 필요하다.

### 사용 예제

```java
import com.inzent.apim.utilities.ldap.LdapConnectionPool;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.ldap.LdapContext;

Properties props = new Properties();
props.setProperty(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
props.setProperty(Context.PROVIDER_URL, "ldap://localhost:389/dc=example,dc=com");
props.setProperty(Context.SECURITY_AUTHENTICATION, "simple");
props.setProperty(Context.SECURITY_PRINCIPAL, "cn=admin,dc=example,dc=com");
props.setProperty(Context.SECURITY_CREDENTIALS, "password"); // 실제로는 외부 비밀 설정에서 읽는다.
props.setProperty("com.sun.jndi.ldap.connect.timeout", "5000");
props.setProperty("com.sun.jndi.ldap.read.timeout", "5000");
props.setProperty(LdapConnectionPool.MAX_PROPERTY, "16");
// 미지정 min=1, idle timeout=300000ms

try (LdapConnectionPool pool = new LdapConnectionPool(props)) {
    pool.open();
    pool.runWith(ctx -> {
        try { System.out.println(ctx.getAttributes("uid=user,ou=people")); }
        catch (NamingException ex) { throw new IllegalStateException("LDAP 조회 실패", ex); }
    });

    LdapContext context = pool.borrow();
    try { System.out.println(context.getAttributes("uid=user,ou=people")); }
    finally { context.close(); }
} // pool.dispose()
```

### Memory leak / NPE 분석 및 보완 (2026-10-13)

| 분석 경로 | 기존 위험 | 보완 및 검증 |
| --- | --- | --- |
| 장시간 대여 후 반복 검색/close | 닫힌 handler 누적, 반환된 프록시에 리스트 backing array 잔류 | 개별 close에서 등록 제거, invalidate에서 목록 자체 null 처리. 반복 500회 후 추적 0 및 반환 후 참조 null 확인 |
| 완료 열거자 / asIterator | 제공자 상태가 반납까지 유지, raw iterator가 수명 검사 우회 | 완료 시 close/참조 해제, iterator를 프록시에 연결하여 종료 후 호출 거부 |
| 같은 자원 반복 반환 / 루트 별칭 | 중복 프록시와 중복 physical close | 동일 객체 identity로 핸들 재사용, 자체 프록시 재포장 방지, 루트는 풀에서만 실제 close 1회 |
| 제공자 close의 parent.close 재진입 | invalidate 중 목록 변경, 중복 release | owner/entry/등록 목록을 콜백 전에 분리. 재진입 close는 무동작이며 나머지 자원 정리 유지 |
| Attribute/검색 결과 속성 캐시 | JDK LDAP Attribute가 내부 Context를 참조하고 schema에서 raw Context 생성 | 값을 스냅샷으로 복사, 스키마만 대여 중 위임. 반납 후 제공자·lease 참조 해제, 값 읽기 및 clone/직렬화 검증 |
| 검색 속성 복사 실패 | 속성 복사 오류가 child Context 등록 전에 발생하면 자식 정리 누락 | child를 먼저 등록하여 NPE 등 복사 실패 후에도 정리 유지 |
| TLS 응답 보관 | JDK TLS 제공자는 close 후에도 연결·스트림·소켓 필드를 유지할 수 있음 | LdapStartTlsResponse로 제공자를 숨기고 핸들 target/lease 해제. TLS close 1회 및 종료/직렬화 계약 검증 |
| 제공자 내부 NPE/RuntimeException/Error | NamingException 이외의 오류 후 연결 재사용 | 모든 제공자 실패에서 dirty 설정, 원래 오류 전파, 반납 시 폐기 |
| null 및 종료 경쟁 | 잘못된 내부 null 저장 시 뒤늦은 NPE | Properties/factory/clock/Consumer와 내부 owner/entry/context/target 생성 경계에 requireNonNull. 정상 null 응답/controls 허용. 호출·반납·dispose 경쟁 40회에서 NPE/중복 close 없음 |
| Error를 포함한 정리 실패 | 정리 오류 후 나머지 자원/풀 참조 잔류 가능 | 전체 정리 시도 및 첫 오류 + suppressed 유지. child close Error에도 root close와 handler·환경·reaper 참조 해제 확인 |

일반적인 동시 close/dispose에서는 동일 Entry 락에서 상태를 읽고 무효화하므로 owner/target을 잘못 역참조하는 NPE를 발견하지 않았다.
null Properties/Consumer/내부 의존성과 null factory 반환에 대한 NPE는 의도적인 계약 검증이다. factory null 반환 후 초기화 재시도가 정상 동작함도 확인했다.
검증은 강한 참조 필드와 추적 목록 상태를 직접 확인하여 GC 실행 시점에 의존하지 않는다. 실제 heap dump/장시간 서버 부하 테스트는 수행하지 않았다.

dispose를 누락하면 daemon 작업이 풀을 참조하고, 대여 후 close를 누락하면 max 한도를 차지하므로 호출측에서 종료/반납 계약을 지켜야 한다.
제공자 close 자체가 실패하면 실제 네트워크 자원 해제 완료는 보장할 수 없지만 풀의 참조를 해제하고 재사용을 차단한다.
속성 값의 사용자 정의 가변 객체/byte[]는 깊은 복사하지 않는다. 일반 값의 외부 보관은 호출측 소유이며 사용자 정의 객체 내부의 임의 참조 그래프까지 정리하지 않는다.

### 검증과 참고

- `mvn -f ldap-connection-pool/pom.xml clean verify`: 기본 동작 17개 + 안전성 16개, 총 33개 테스트. 초기/지연 open, 자동/중복 반납, max, idle/min, daemon 정리, 종료, 연결·정리 실패, Properties 복사/defaults, JNDI 생성 경로, 동시 호출 및 위 참조 해제/NPE 경로를 검증한다.
- 코드 리뷰에서 발견한 SearchResult 내부 Context 누수 및 명시적인 열거자 close 실패 후 재사용을 각각 실패 테스트로 재현하고 수정했다.
- 최종 `clean verify`: LDAP 33개, Object Recycle Container 58개, Partial Input 13개, 총 104개 테스트 통과 및 세 모듈 JAR 빌드 성공. 참조 해제 보완 후 재리뷰에서 추가 수정 항목 없음.
- 실제 LDAP 서버 인증/검색 통합 검증은 서버 환경이 제공되지 않아 수행하지 않았다. 테스트는 대체 Context 및 JNDI InitialContextFactory를 사용한다.
- API 기준: [Java 21 InitialLdapContext](https://docs.oracle.com/en/java/javase/21/docs/api/java.naming/javax/naming/ldap/InitialLdapContext.html), [Java 21 LdapContext](https://docs.oracle.com/en/java/javase/21/docs/api/java.naming/javax/naming/ldap/LdapContext.html).
- 수명/열거 기준: [Java 21 Context](https://docs.oracle.com/en/java/javase/21/docs/api/java.naming/javax/naming/Context.html), [Java 21 NamingEnumeration](https://docs.oracle.com/en/java/javase/21/docs/api/java.naming/javax/naming/NamingEnumeration.html). 제공자의 Context 참조와 TLS close 동작은 로컬 Java 21 클래스의 javap 결과도 확인했다.

## Partial Input 구현 계획 (2026-10-12)

- 기존 develop 작업 경로를 유지하고 독립 `partial-input/pom.xml`을 추가한다.
- `PartialByteArray(byte[], List<Long>)`, `PartialFile(Path/String, List<Long>)`을 공개 API로 제공한다.
- 각 long의 상위/하위 32비트를 signed int offset/length로 해석한다. 음수와 원본 범위 초과는 생성 시 거부한다. 덧셈은 long으로 처리한다.
- 리스트 순서를 유지하며 중복/겹침/역순을 허용한다. 0 길이와 빈 리스트를 허용한다. 리스트는 방어적으로 복사한다.
- 배열 shrink는 원본을 변경하지 않고 새 배열을 반환한다. 파일 shrink는 동일 디렉터리 임시 파일에 복사하고 atomic replace로 원본 경로를 교체한다. 교체 성공 후 객체의 구간은 축소 파일 전체를 가리킨다.
- stream은 구간 경계를 숨기며 read/skip/available/close 계약을 지킨다. 파일은 FileChannel의 위치 지정 읽기를 이용한다. 조기 EOF는 IOException으로 알린다.
- 구현 순서: ① 공개 동작/실패/경계 테스트 및 미구현 실패 확인 ② 공통 구간과 스트림 및 배열/파일 API 구현 ③ 전체 모듈 검증 및 독립 코드 리뷰 ④ 사용 예제와 검증 결과 기록.

### 구조 및 API

| 파일 (`partial-input/src/main/java/com/inzent/apim/utilities/partial/`) | 역할 |
| --- | --- |
| `PartialByteArray.java` | 배열 입력, `byte[] shrink()`, `InputStream openStream()` |
| `PartialFile.java` | Path/String 입력, `Path shrink() throws IOException`, `InputStream openStream() throws IOException` |
| `PartialBlocks.java` | 내부 불변 구간 목록 및 생성자 검증 |
| `PartialInputStream.java` | 내부 구간 연결 스트림, 독립 커서/파일 핸들 |

생성자에서 offset/length 음수 또는 `offset + length > sourceSize`는 `IllegalArgumentException`,
null 배열·리스트·리스트 원소는 `NullPointerException`으로 거부한다. 파일 접근 오류는 `IOException`이다.
파일 offset과 length 각각의 최대값은 `Integer.MAX_VALUE`지만 두 값의 합과 전체 출력 길이는 long으로 계산한다.
offset이 원본 크기와 같은 경우 length가 0일 때만 유효하다.
배열 출력 길이가 `Integer.MAX_VALUE`보다 크면 shrink는 할당 전에 `IllegalStateException`으로 거부한다.
그 이하라도 실제 배열 할당은 JVM 제한과 가용 메모리에 따른다.

### 사용 예제

```java
import com.inzent.apim.utilities.partial.PartialByteArray;
import com.inzent.apim.utilities.partial.PartialFile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

// 상위 4 byte: offset, 하위 4 byte: length
List<Long> info = List.of(
        (1L << 32) | 3L,
        (7L << 32) | 2L);

byte[] source = "0123456789".getBytes(StandardCharsets.UTF_8);
PartialByteArray array = new PartialByteArray(source, info);
byte[] compact = array.shrink(); // "12378". source는 그대로 유지된다.
try (InputStream input = array.openStream()) {
    byte[] selected = input.readAllBytes(); // "12378"
}

// 원본 파일 내용이 "0123456789"인 예제
PartialFile file = new PartialFile(Path.of("data.bin"), info);
try (InputStream input = file.openStream()) {
    // input.transferTo(output); 로 선택된 부분만 전송할 수 있다.
}
Path compactedPath = file.shrink(); // 원본 경로의 내용이 "12378"로 교체된다.
// 이후 file.openStream()/file.shrink()는 축소된 파일 전체를 대상으로 한다.
```

### 자원·변경 계약

- 리스트는 복사하지만 byte[] 내용은 공유한다. 스트림 사용 중 원본 배열/파일을 외부에서 변경하지 않는다.
- 스트림마다 커서가 독립적이다. 파일 스트림은 close가 필요하며 파일 객체 생성자 자체는 핸들을 유지하지 않는다.
- 파일 전체를 메모리에 올리지 않는다. shrink는 고정 크기 복사 버퍼와 선택 길이만큼의 임시 디스크 공간을 사용한다.
- 파일 shrink 전에 열린 스트림을 모두 닫는다. 파일 객체의 shrink/openStream 동시 호출은 지원하지 않는다.
- 파일 경로는 생성 시 real path로 해석한다. 심볼릭 링크 입력은 해석된 대상 경로를 교체한다.
- 임시 파일의 메타데이터로 교체하므로 기존 파일의 권한/시간 등 메타데이터 보존을 보장하지 않는다. 기존 hard link는 이전 파일 내용을 유지한다.
- atomic replace 미지원/교체 실패 시 IOException으로 종료한다. 비원자적 덮어쓰기로 전환하지 않는다. 복사 실패 시 임시 파일을 정리하고 원본 내용을 유지한다.
- 선택된 바이트를 읽기 전에 파일 끝에 도달하면 `EOFException`을 발생시킨다. 생성 후 외부 파일 변경을 완전히 감지하거나 스냅샷을 제공하지는 않는다.
- 0 길이 read는 0, 정상 EOF는 -1, 음수 skip은 0, available은 남은 논리 바이트 수를 int 최대값으로 제한한다. mark/reset은 미지원이다.
- 빈 선택의 shrink 결과는 빈 배열/빈 파일이다. 겹친 구간은 중복 출력되므로 선택 길이가 원본보다 커질 수 있다.

### 검증 기록

- 구현 전 공개 클래스 부재에 따른 테스트 컴파일 실패를 확인했다.
- 최초 `mvn -f partial-input/pom.xml test`: 10개 테스트 통과.
- 최종 `mvn -f partial-input/pom.xml clean verify`: 10개 테스트 통과, JAR 생성 성공.
- 기존 모듈 `mvn -f object-recycle-container/pom.xml clean verify`: 58개 테스트 통과, JAR 생성 성공. 오류/누수 시나리오의 진단 WARNING 로그가 출력되며 테스트 실패는 없다.
- 독립 코드 리뷰: 수정이 필요한 결함 없음. atomic move 실패 분기의 전용 회귀 테스트는 미추가 상태이며, 복사 실패 시 원본 보존과 임시 파일 정리는 검증했다.
- 최초 구현 시 develop의 미커밋 변경으로 유지했으며, 이후 사용자 push 요청에 따라 구현·안전성 보강·테스트·문서를 함께 커밋하여 origin/develop에 반영한다.
- 실제 임시 파일로 순서·겹침·빈 구간·범위 오류·조기 EOF·실패 시 원본 보존·2GB 경계 위치를 검증한다.
- 배열/파일 스트림의 bulk read 경계, skip, unsigned 단일 바이트, close, 독립 커서와 배열 출력 크기 초과를 검증한다.

### Partial Input 메모리 누수 / NPE 분석 및 조정 (2026-10-12)

| 분석 경로 | 판단 및 조정 |
| --- | --- |
| 닫힌 스트림 → 원본 배열/구간 목록 | close 이후에도 final 필드가 강한 참조를 유지했다. 스트림 객체를 장기 보관하면 큰 원본의 GC가 지연될 수 있어 close에서 참조를 해제한다. 채널 close 실패 시에도 참조는 이미 해제된다. |
| 파일 openStream → 채널 획득 → 스트림 생성 | 스트림 생성 중 RuntimeException/Error 발생 시 획득한 채널을 닫도록 예외 처리를 보강했다. close의 IOException은 원래 예외에 suppressed로 보존한다. 메모리 고갈 상황에서의 복구는 최선 노력이며 강제로 재현하지 않았다. |
| 파일 생성자 null 리스트 | 파일 접근 이전에 requireNonNull을 수행한다. 없는 파일 경로와 null 리스트가 함께 전달되어도 partialInfo NPE로 입력 오류를 일관되게 알린다. |
| null 원소/잘못된 구간으로 파일 생성 실패 | 기존 try-with-resources가 채널을 정리한다. 생성 실패 후 파일 삭제 테스트로 확인했다. |
| close 후 source/selection/channel null | read/skip/available은 ensureOpen을 먼저 통과해야 참조를 사용하므로 정상적인 순차 호출에서 우발적 NPE 없이 IOException을 반환한다. 중복 close는 허용한다. |
| PartialByteArray 객체 → 원본 배열 | 재사용을 위한 의도된 참조다. shrink는 새 배열을 반환하므로 원본을 회수하려면 사용자가 기존 view 참조도 놓아야 한다. 공개 API 의미는 유지했다. |
| EOF/읽기 실패 후 파일 핸들 | 자동 close하지 않는다. 호출자가 try-with-resources로 스트림을 닫는 소유권 계약을 유지한다. |
| 동시 read/close 또는 shrink/openStream | thread-safe 계약이 없으며 지원하지 않는다. 이번 수정은 동기화나 동시 사용 지원을 추가하지 않는다. |

검증: 참조 유지 및 null 검사 순서 테스트 2개의 실패를 먼저 확인했고 수정 후 신규 회귀 테스트를 포함한 13개 테스트가 통과했다.
참조 해제 검증은 GC 시간에 의존하지 않도록 실제 스트림의 소유 참조를 직접 검사한다.
최종 clean verify: partial-input 13개, object-recycle-container 58개, 총 71개 테스트 통과 및 두 JAR 생성 성공.
독립 재검토에서도 지원하는 순차 호출 계약 내에서 추가 수정이 필요한 NPE/자원 수명 결함은 발견되지 않았다.

## Object Recycle Container

### 2026-10-11 구현 계획 및 진행 기록

사용자의 “질문하지 않고 프로젝트 문서 참조하여 구현 시작” 요청에 따라 아래 설계를 구현 기준으로 채택한다.
과거 설계 검토 단계/구현 착수 금지 문구는 당시의 기록이며 이번 요청으로 구현 단계로 전환한다.
구현 당시에는 Git 저장소가 없는 디렉터리여서 현재 프로젝트에 직접 구현하였다.
이후 사용자의 push 요청으로 로컬 Git/원격 origin을 연결하고 master 초기 커밋을 구성한다.

1. Java 21 독립 Maven JAR와 JUnit 테스트를 구성한다. 기본 수명, LIFO, worker 반환,
   만료된 핸들, 용량, 소유권에 대한 테스트를 먼저 작성하고 미구현 실패를 확인한다.
2. `ContainerConfig`, `ObjectHolder`, `HolderLease`, `SubContainer`, `MainContainer` 및
   immutable 통계/누수 이벤트 타입을 `com.inzent.apim.utilities.recycle`에 구현한다.
   Main 단일 ReentrantLock으로 슬롯/세대/회계를 보호하고 모든 사용자 훅은 락 밖에서 실행한다.
3. 가짜 단조 시계와 latch 테스트로 patrol, 사용 중 회수, 생성/정리 실패, 종료 경쟁,
   min 보충/idle 축소, onBorrow 준비 및 worker 반환 경로를 검증한다.
4. 전체 `mvn -f object-recycle-container/pom.xml clean verify`를 실행하고 API 사용 예제,
   구조 변경내역과 실제 검증 결과를 이 문서에 기록한다.

설계 보완: 사용자 정의 Sub 생성은 Main의 `protected createSubContainer()` 훅으로 제공한다.
기본 구현은 일반 Sub를 반환하며, 확장 구현은 `new SubContainer<>(this)`의 하위 타입을 반환한다.
세대 소진 슬롯은 폐기한다. 예외 진단과 통계 불일치 경고에는 `System.Logger`를 사용한다.
테스트 시계 주입은 protected 생성자 `MainContainer(config, listener, LongSupplier)`로 제공한다.

진행 기록:

- 공개 API 미구현 상태의 테스트 컴파일 실패를 확인한 뒤 구현하였다.
- 기본 수명 10개, 설정·훅·정책 19개, 동시성 15개의 테스트가 `mvn ... test`에서 통과하였다.
- `patrolReportsOnlyTheCurrentScansForcedClosures` 및
  `mainCanRevokeSubFromAnotherThreadWithoutAccessingItsStack` 실패를 확인한 뒤
  patrol의 이벤트별 회수 집계와 registry 기반 Main 반환으로 수정하였다.
- `failedMinRecoveryAlsoRetriesOnNextBorrow`의 expected=3 / actual=2 실패를 확인한 뒤
  Sub/Holder 대여 시작 시 min 보충을 재시도하도록 수정하였다.
- 독립 리뷰의 `failedAllocationRollbackCannotDestroyAReassignedSlot` 실패를 확인한 뒤
  rollback에서 최초 배정의 Sub 소유자와 세대를 검사하도록 수정하였다.
  min 보충 중 생성 부분 실패의 동일 경쟁도 공개 API/latch 테스트로 검증하였다.
- `additionalAssignmentPushesToTheTopOfTheLifoStack`의 expected=id-2 / actual=id-1
  실패를 확인한 뒤 초기·추가 배정을 모두 stack.push 방식으로 통일하였다.
- 구현 당시 Git 저장소가 없어 worktree/commit/PR 생성은 적용하지 않았다.
  이후 push 요청에 따라 현재 프로젝트의 초기 커밋과 원격 등록을 진행한다.

### 상태와 목적

2026-10-11 기준 아래 설계를 채택하여 독립 라이브러리로 구현하였다.
기존 프록시 프로젝트와 별개이며 이 문서에 이 모듈의 구현·검증 기록을 유지한다.

Java 21 환경에서 객체를 재사용하고, 호출 스레드가 빌린 객체를 다른 작업 스레드에서도
반환할 수 있는 독립 유틸리티를 만든다. 성공 기준은 객체의 중복 대여·중복 반환 방지,
만료된 참조의 재사용 차단, 정상 반환 시 초기화, 누수 의심 자원의 탐지와 안전한 회수이다.
객체 풀링의 성능 이점은 전제하지 않고 실제 객체 생성 비용과 경합을 측정한다.

요청에서 정한 사항:

- Main Container가 Sub Container와 전체 Object Pool을 관리한다.
- Main Container는 thread-safe이며 객체 인스턴스의 initial/min/max를 관리한다.
- Sub Container는 non-thread-safe이며 자체 locking 없이 소유 스레드만 조작한다.
- Main은 abstract 클래스이며 `createNewHolder()`로 부족한 Holder를 생성한다.
- 반환 시 `isRecycleable(holder)`가 true이면 `onRecycle(holder)`, false이면 forceClose 경로의 `onDestroy(holder)`를 호출한다.
- Main에서 Sub Container를 대여할 때 해당 Sub의 `void onBorrow()`를 호출한다.
- Sub Container는 기본 개수의 객체를 받아 시작하고 Object Holder의 대여·반환을 관리한다.
- Holder borrow는 Sub 내부 구조체에서 pop, 정상 recycle은 같은 구조체에 push한다. 부족하면 Main의 newObject를 호출한다.
- Holder recycle 시 Main 풀로 반환하지 않는다. Sub recycle 시 borrow/recycle 통계를 비교하여 다르면 warning을 출력한다.
- Sub Container 반환 시 보유 자원은 Main Container로 돌아간다.
- 두 계층 모두 borrow time 및 last use time 제한을 감시한다. Main 생성자에서 LeakListener를 받으며 미지정 시 감지 대상을 forceClose한다.
- Managed POJO는 Holder 참조를 보유하고 caller 또는 worker thread에서 dispose한다.

구현 기준:

- 하나의 Main Container는 한 가지 객체 타입 `T`와 한 가지 생성·초기화 정책을 관리한다.
- 동기화 책임은 Main에 집중한다. Sub의 로컬 자료구조는 다른 스레드에 공개하지 않는다.
- Managed POJO 자체는 호출자가 생성한다. 풀링 대상은 `ObjectHolder<T>`와 내부의 `T`이며, POJO는 대여별 핸들을 보유한다.
- 모든 정상 Holder 반환은 소유 Sub에 보관한다. Main 풀로의 일괄 반환은 Sub recycle 단계에서 수행한다.
- 풀 부족 시 대기하지 않고 즉시 실패한다. 무제한 증설과 blocking borrow는 초기 범위에서 제외한다.

### 접근 방식 비교

| 방식 | 장점 | 비용·제약 | 판단 |
| --- | --- | --- | --- |
| thread-safe Main + 소유 스레드 전용 Sub + 대여 토큰 | Sub 자체 락 없이 비동기 반환과 patrol의 정합성 유지 | 공유 수명 전이는 Main 동기화 경유 | 요구사항 반영안 |
| Main/Sub별 락 분리 | 여러 스레드가 Sub를 직접 조작 가능 | Sub에도 locking 필요 | 요청에 따라 제외 |
| lock-free 큐 + CAS 상태 관리 | 일부 경합 경로에서 이점 가능 | ABA, 세대, 다중 자원 회수의 정합성 복잡 | 초기 제외 |

### 구성과 프로젝트 배치

```text
abstract MainContainer<T>
  ├─ ObjectPool<T>                 전체 슬롯 및 유휴 객체
  ├─ SubState registry            Main이 보호하는 상태·통계·Sub별 worker 반환함
  │    └─ SubContainer<T>          소유 스레드 전용 외부 핸들, 자체 락 없음
  │         ├─ holder stack      초기 개수로 시작하는 LIFO 구조, 슬롯/세대 토큰 보관
  │         └─ HolderLease<T>      대여마다 새로 발급하는 외부 핸들
  │              └─ slot + generation → ObjectHolder<T> → T
  ├─ createNewHolder()            Holder 생성 추상 훅
  ├─ isRecycleable(holder)        재사용 가능 여부 판정 추상 훅
  ├─ onRecycle(holder)            재사용 전 Holder 리소스 정리 추상 훅
  ├─ onDestroy(holder)            최종 리소스 해제 추상 훅
  └─ Patrol + LeakListener        만료 검사와 이벤트
```

`ObjectPool`과 `SubState`, 슬롯은 내부 구현이다. 재사용할 내부 상태와 외부에 노출할
대여 핸들을 분리한다. 핸들은 재활용하지 않아 오래된 POJO가 새로운 대여를 반환하는
문제를 막는다. `createNewHolder()`의 반환 객체는 풀에서 재사용하는 실제 Holder이다.
`borrowObjectHolder()`는 그 Holder를 감싼 `HolderLease<T>`를 반환한다. Managed POJO가
보유하는 Holder 참조는 이 대여 핸들이다. 훅에는 실제 Holder를 전달한다.
실제 Holder를 그대로 POJO에 재노출하지 않아 이전 대여자의 close가 새 대여를 종료하지 못한다.

common-utilities 프로젝트 아래에 독립 Maven 모듈을 추가하였다. 공개 패키지는 com.inzent.apim.utilities.recycle 하위이다.

```text
object-recycle-container/
  pom.xml                         Java 21, 일반 library JAR
  src/main/java/com/inzent/apim/utilities/recycle/      공개 API 및 내부 구현
  src/test/java/com/inzent/apim/utilities/recycle/      수명·경쟁·장애 검증
project.md                        이 문서에 설계와 변경내역 유지
```

좌표는 `com.inzent.apim.utilities:object-recycle-container:1.0.0-SNAPSHOT`이다.
런타임 외부 의존성 없이 JDK만 사용하고 테스트에는 JUnit을 사용한다.
유틸리티는 common-utilities 프로젝트 루트에서
`mvn -f object-recycle-container/pom.xml verify`로 별도 빌드한다.
현재 common-utilities 루트에는 pom.xml이 없으며, 모듈을 직접 지정하여 빌드한다. 프록시 연결과 Maven aggregator 구성,
JPMS `module-info.java` 추가는 이번 범위에 포함하지 않는다.

### 객체 수명과 소유권

Main만 객체 생성과 전체 용량을 관리한다. 슬롯은 언제나 Main 유휴 풀, Sub 스택/반환함,
대여 중, 생성·정리 중, 격리 중 중 정확히 한 상태에 속한다.

| 대상 | 상태 전이 | 의미 |
| --- | --- | --- |
| Main | NEW → STARTING → OPEN → CLOSED | start 성공 후 대여 가능, 시작 실패/종료는 CLOSED |
| Sub | ACTIVE → REVOKED → RELEASED | 회수 시작과 동시에 핸들 무효화, 정리 완료 후 등록 해제 |
| HolderLease | ACTIVE → RECYCLED 또는 EXPIRED | 한 번 종료한 핸들은 다시 활성화하지 않음 |
| 내부 슬롯 | IDLE → BORROWED → RECYCLING → IDLE | 판정 및 onRecycle 성공 시 재사용 |
| 내부 슬롯 | BORROWED → QUARANTINED → DESTROYED | 만료·강제 회수 경로 |

- 대여 토큰에 Main 식별자, Sub 대여 식별자, 슬롯 식별자와 세대를 포함한다.
- 같은 슬롯의 다음 대여는 새로운 세대를 사용한다. 세대 소진 시 슬롯을 폐기해 wrap-around를 방지한다.
- 이미 반환되거나 만료된 핸들의 `recycle()`은 `false`, `close()`는 no-op이다.
- 만료·반환 후 `use()` 및 `touch()`는 명시적인 예외로 실패한다.
- 다른 Main 또는 Sub에 반환하려는 호출은 소유권 오류이며 실제 소유자의 상태를 변경하지 않는다.
- Holder가 `T`를 독점 대여한다. 서로 다른 스레드가 순차적으로 사용할 수 있지만,
  동일 Holder의 중첩·동시 `use()`는 예외로 거절한다. `T` 자체의 thread safety를 전제하지 않는다.

Sub 대여 시 `initialObjectsPerSub`개의 사용 가능한 슬롯을 원자적으로 예약한다.
부족하면 Main 전체 상한 안에서 생성한다. 일부 생성이 실패하면 Sub를 외부에 공개하지 않고,
기존 풀에서 가져온 슬롯은 되돌리고 새로 만든 객체는 정리한다. 생성 예약 용량도 정확히 반환한다.
초기 슬롯 토큰은 Sub 내부 `ArrayDeque` 기반 LIFO 스택에 push한다. 초기 개수는 고정 용량이
아니며 이후 확장할 수 있다. borrow는 worker 반환함을 먼저 반영한 뒤 스택에서 pop한다.
유효한 토큰이 없으면 `Main.newObject(sub)`로 한 개를 추가 확보하여 대여한다.
newObject는 Main idle Holder를 우선 배정하고 없으면 max 범위에서 createNewHolder를 호출한다.
Sub가 createNewHolder를 직접 호출하여 Main의 용량 예약을 우회하지 않는다.

소유 스레드가 즉시 정리할 수 있는 정상 Holder 반환은 `isRecycleable(holder)`가 true이고
`onRecycle(holder)`가 성공한 뒤 소유 Sub 스택에 push한다. Main 풀에는 등록하지 않는다.
다른 worker의 반환은 같은 Sub의 반환함에 적재하고 owner가 이를 스택으로 옮긴다.
사용 완료까지 정리를 미룬 반환도 정리 완료 스레드에 따라 동일하게 처리한다.
그 사이 Sub가 recycle되었다면 Sub 회수 절차가 정리 완료 슬롯을 Main으로 반환한다.
이는 Holder 단독 반환 경로가 아닌 종료된 Sub의 일괄 회수 후속 처리이다.
Sub가 forceClose되었거나 Main이 종료되었다면 폐기한다.
판정이 false이거나 판정/onRecycle에서 예외가 발생한 Holder는 forceClose 경로로 전환하여
`onDestroy(holder)`를 호출하고 풀에 재등록하지 않는다.

`recycle()`의 boolean은 최초 유효 반환 접수 여부이다. 이는 재사용 가능 여부를 뜻하는
`isRecycleable()`의 boolean과 별개이다. 재사용 불가로 폐기하더라도 최초 반환 접수는 true이다.
사용 중 반환되면 대여를 먼저 종료하고, 마지막 use가 끝난 뒤 판정과 훅을 실행한다.
Sub 예약 유휴 Holder는 이미 정리된 상태이므로 정상 Sub 반환에서 Main으로 이동할 때
onRecycle을 중복 호출하지 않는다. Sub forceClose에서는 소유한 모든 Holder를 폐기한다.

Sub 캐시는 객체 자체가 아닌 슬롯 ID와 세대 토큰을 보유한다. Main은 모든 예약 슬롯의
실제 소유권을 관리한다. patrol이 슬롯을 회수해도 Sub 큐는 직접 수정하지 않는다.
소유 스레드가 다음에 토큰을 꺼낼 때 Main에서 세대·Sub 유효성을 검증하며 오래된 토큰은 버린다.
Main에 반환된 슬롯이 다른 Sub로 이동한 뒤에도 이전 토큰으로 접근할 수 없다.

### force reset의 정확한 의미

만료되었다는 사실만으로 사용 중인 Java 객체를 안전하게 초기화할 수는 없다.
따라서 force reset을 수행하는 `forceClose()`는 **대여 권한 즉시 취소 + 안전한 시점에 객체 정리**로 정의한다.
제한 초과 감지 자체와 종료는 분리한다. 미지정 Listener의 기본 처리 또는 사용자 Listener의
명시적 `forceClose()` 호출이 있어야 누수 감지에 따른 무효화가 발생한다.
아래에서 만료된 핸들이란 제한 초과를 감지하기만 한 핸들이 아니라 강제 종료된 핸들이다.

1. Main 메타데이터 락 안에서 대상 ID·세대와 활성 여부를 확인하고 대여를 무효화한다.
2. 아직 시작하지 않은 사용은 차단한다. 이미 실행 중인 사용은 강제 중단하지 않는다.
3. 사용 중인 슬롯은 격리한다. 실행 중인 `use()`나 재활용 훅이 종료될 때까지 onDestroy/재대여하지 않는다.
4. 강제 종료한 Holder는 재활용 판정과 onRecycle을 건너뛰고 `onDestroy(holder)` 후 풀에서 제거한다.
5. 필요하면 이후 대여에서 새 객체를 생성한다. 이전 세대 참조는 새 객체에 영향을 주지 못한다.

일반 `get(): T`는 공개하지 않고 `use(Consumer<? super T>)` 콜백으로 사용 구간을 추적한다.
콜백의 시작·종료마다 실행 상태를 관리하고 마지막 사용 시간을 갱신한다.
콜백 밖으로 `T` 또는 내부 가변 참조를 유출하거나 콜백 내부에서 비동기 사용을 시작하는 것은
API 계약 위반이다. Java 타입 시스템만으로 이러한 유출을 완전히 방지할 수는 없다.

콜백이 영원히 끝나지 않으면 물리적 회수도 끝나지 않는다. 격리 슬롯은 계속 용량에 포함하며,
상한 도달 시 신규 borrow를 실패시킨다. 따라서 강제 회수는 메모리 무조건 회수나 외부
I/O 강제 취소를 보장하지 않는다. 외부 자원 취소 기능은 타입별 별도 계약이 필요하다.

### Sub 반환과 worker dispose의 관계

`recycleContainer()`는 Sub 대여 종료이다. 유휴 슬롯은 Main으로 반환하고,
미반환 Holder는 모두 무효화하여 위 강제 회수 경로로 처리한다.
활성 `use()`가 있으면 Sub는 REVOKED로 남고 마지막 정리 후 RELEASED가 된다.
회수 시작 시 borrowCount/recycleCount를 비교하여 다르면 WARNING 로그를 한 번 출력한다.
경고 후에도 회수는 계속하며 이미 정상 반환된 스택 및 반환함의 Holder를 Main으로 일괄 이동한다.

일반적인 비동기 시나리오는 다음 순서를 따른다.

```text
caller: Main.borrowContainer()
caller: Sub.borrowObjectHolder()
caller: ManagedPojo(holder) 생성
caller: executor를 통해 POJO를 worker에 전달
worker: holder.use(...)로 객체 사용
worker: POJO.dispose() → holder.close() → 소유 Sub의 worker 반환함
caller: 다음 Sub 연산에서 반환함을 비우고 Sub 스택에 push
caller: worker 완료 확인 → sub.close()
caller: thread finish
```

다른 스레드로의 전달은 Executor 제출 등 안전한 publication 경로를 사용한다.
worker는 Sub의 borrow/recycle/patrol/close를 직접 호출하지 않는다.
Holder는 Main의 수명 관리 상태를 참조하므로 Sub의 로컬 큐를 변경하지 않고 반환할 수 있다.
worker 반환함은 Main이 보호하는 SubState에 위치하지만 그 Holder의 소유자는 계속 해당 Sub이다.
Main idle 풀에 등록하거나 다른 Sub에 배정하지 않는다. owner는 borrow와 recycleContainer 전에
반환함을 drain하여 자기 스택에 push한다. owner가 돌아오지 않으면 Main patrol이 registry와
반환함으로 회수하며 Sub 스택에는 접근하지 않는다. 중복 반환을 차단하므로 반환함 항목 수는
해당 Sub 소유 Holder 수를 넘지 않는다.
POJO는 자신의 중복 dispose를 허용하고 Holder 종료의 멱등성도 활용한다.
반환 중 콜백이 실행되고 있다면 신규 사용만 차단하고 재활용 판정과 onRecycle은 콜백 종료 후 수행한다.

caller가 worker 완료 전에 Sub를 반환하면 worker의 이후 사용은 실패한다.
조기 Sub 반환과 worker의 계속 사용을 동시에 허용하는 drain/ref-count 모드는 초기 범위에서 제외한다.
스레드 종료 자체를 자동 감지해 반환하지는 않으며, 명시적인 close 및 patrol이 수명을 관리한다.

### 동시성 및 콜백 실행

Main별 `ReentrantLock` 하나가 Main 풀, registry, 용량, 세대, 시각, 실행 중 여부,
대여별 통계 및 worker 반환함을 보호한다.
Sub는 `ArrayDeque` 등의 일반 자료구조를 사용하며 자체 synchronized/Lock을 두지 않는다.
Sub를 빌린 스레드가 owner이며 Sub의 모든 public 연산은 이 스레드만 호출할 수 있다.
다른 스레드의 직접 호출은 owner 검사로 거절한다. 소유 스레드 이전은 초기 범위에서 제외한다.

Sub는 로컬 토큰을 읽고 쓸 때 락을 사용하지 않지만, 대여 확정·반환·유효성 검사 등
공유 수명 상태의 변경은 Main API를 경유한다. 즉 Sub에 락이 없다는 의미이며,
Sub를 통한 전체 호출 경로가 lock-free라는 의미는 아니다.
Holder의 use/recycle과 worker 반환도 Main이 보호하는 상태를 사용한다.
이 관리 상태 변경과 Main의 추상 정리 훅 호출은 Main 풀로 Holder를 반환하는 연산이 아니다.
성공한 borrow, recycle, 만료의 판정 지점은 Main 락 안의 상태 전이이다.

Main의 recycleContainer/patrol/close는 자신의 registry만으로 Sub 대여를 취소하고
슬롯을 정리한다. Sub 객체의 큐나 owner 전용 필드를 읽거나 변경하지 않는다.
owner는 다음 Sub 연산에서 무효화를 확인하고 로컬 토큰을 버린다.

createNewHolder/isRecycleable/onRecycle/onDestroy 및 Sub onBorrow, `use()` 본문,
누수 리스너는 메타데이터 락 밖에서 실행한다.
콜백 전에는 슬롯을 예약 상태로 만들고, 콜백 후에는 Main/Sub의 최신 상태를 다시 확인해
게시하거나 폐기한다. 사용 중 recycle과 patrol의 승자는 한 번만 상태를 변경하며,
정리 책임자도 한 번만 지정한다. 명시적 반환보다 먼저 forceClose가 상태를 전이하면 강제 회수 경로를 따른다.

`use()` 예외는 호출자에게 전달하고 실행 중 표시를 반드시 해제한다.
부분 변경된 객체를 재사용하지 않도록 해당 Holder도 무효화하고 객체를 폐기한다.
생성 콜백 예외는 start 또는 borrow 실패로 전달한다. 재활용/폐기 훅 및 리스너의 일반 예외는
진단에 기록하고 다른 슬롯 정리를 계속한다. JVM 치명적 오류까지 복구한다고 보장하지 않는다.
onDestroy 실패 슬롯은 재사용하지 않고 실패 누적 횟수를 기록한다. 외부 자원이 실제로
해제되었는지는 보장할 수 없으며 초기 버전은 onDestroy를 자동 재시도하지 않는다.

추상 수명 훅과 onBorrow는 유한 시간에 끝나야 하며 같은 Main에 재진입하지 않는 계약으로 한다.
재진입은 예외로 거절한다. 한 작업이 다른 작업의 종료를 락을 잡고 기다리지 않는다.
thread-safe는 관리 상태의 정합성을 의미하며 콜백의 무한 대기 해결까지 의미하지 않는다.

### patrol과 누수 감지

Main이 보호하는 SubState와 Holder 대여 상태에 각각 `borrowedAtNanos`, `lastUsedAtNanos`를 기록한다.
빌린 시점에는 두 값이 같으며 borrow 시각은 해당 대여 동안 변하지 않는다.
Holder의 `use()` 진입·종료 및 `touch()`는 Holder와 소유 Sub의 last use를 갱신한다.
Sub의 borrow/recycle holder와 Sub 자체 `touch()`도 Sub의 last use를 갱신한다.
무효화된 대여의 늦은 콜백 완료는 새 대여의 시각에 영향을 주지 않는다.

```text
expired = (borrowLimit 활성 && now - borrowedAt >= borrowLimit)
       || (idleLimit 활성   && now - lastUsedAt >= idleLimit)
```

elapsed time은 `System.nanoTime()` 기반으로 계산하며 테스트에서는 단조 시계를 주입한다.
벽시계 변경의 영향을 받지 않도록 이벤트 출력 시각과 만료 판단 시계를 분리한다.
긴 실행 중인 콜백도 제한을 넘으면 무효화될 수 있다. `touch()`는 idle 제한만 연장하고
총 borrow 제한은 연장하지 않는다. 적절한 제한값은 호출 측 작업 시간에 맞춰 설정한다.

- Main `patrol()`은 자신의 registry로 활성 Sub와 Holder 만료를 모두 검사한다. Sub 메서드를 호출하지 않는다.
- Sub `patrol()`은 owner가 호출하며, Main에 해당 Sub의 Holder 검사만 위임한다. Sub 자신의 만료 검사는 Main의 책임이다.
- Sub 누수 감지를 먼저 처리한다. Sub를 forceClose했다면 자식은 부모 회수 사유로 기록하고 개별 누수 이벤트는 추가 발행하지 않는다.
- 사용자 Listener가 Sub를 종료하지 않았다면 자식 Holder의 누수 검사도 계속 수행한다.
- 스냅샷을 찍은 뒤 이벤트 발행을 확정하기 전에 Main 락 안에서 상태·세대·시간을 재검사한다.
- 반환된 Sub의 오래된 핸들로 patrol을 호출하면 거절한다.
- 최초 구현은 등록된 대여의 전체 순회다. 비용은 활성 Sub 수 + 활성 Holder 수에 비례한다.
- 제한 초과는 누수 의심이다. 정상적인 장시간 작업도 해당할 수 있으므로 실제 누수로 단정하지 않는다.
- 이벤트에는 대상 종류(Sub/Holder), 대상/부모 ID, 세대, 사유, 감지 시각, 대여 경과/유휴 시간을 포함한다.
  객체 데이터는 기록하지 않고 대여 스택 수집은 선택 기능으로 기본 비활성화한다.

자동 patrol은 Main당 선택적인 단일 daemon scheduler가 `scheduleWithFixedDelay`로 실행한다.
동시 수동 patrol은 검사 진행 중이면 skipped 결과를 반환하여 스캔을 겹치지 않는다.
일반 예외는 실행 경계에서 진단 처리해 다음 주기 실행이 유지되도록 한다.
별도 무제한 cleanup 큐는 만들지 않는다. 정리 콜백이 오래 걸리면 patrol도 늦어질 수 있으며,
만료 검출은 정밀 타이머 보장이 아닌 best-effort 주기 검사이다.

### LeakListener 생성자 파라미터와 기본 처리

Main 생성자는 다음 두 형태를 제공한다. 테스트/외부 단조 시계를 위한 LongSupplier 주입 생성자도 제공한다.

```java
protected MainContainer(ContainerConfig config);
protected MainContainer(ContainerConfig config, LeakListener leakListener);
```

Listener 타입은 `LeakListener`이며 `void onLeak(LeakEvent event)`를 제공한다.
생략하거나 null을 전달하면 기본 Listener인 `event -> event.forceClose()`를 사용한다.
Listener를 지정한 경우에는 사용자 Listener만 호출하며 반환 후 자동 forceClose를 추가하지 않는다.
로그만 남기거나, 자체 판단 후 `event.forceClose()`를 호출할 수 있다.

`LeakEvent.forceClose()`는 감지 당시의 대상과 세대에 바인딩된 Main 관리 작업이다.
Sub 감지이면 해당 Sub 대여와 그 자식을 종료하고, Holder 감지이면 해당 Holder만 종료한다.
Main 전체를 종료하지 않으며 non-thread-safe Sub의 public 메서드도 직접 호출하지 않는다.
실제로 무효화한 경우 true, 이미 반환·종료되었거나 세대가 바뀐 경우 false를 반환한다.
늦은 호출이나 중복 호출이 새 대여에 영향을 주어서는 안 된다.
명시적인 종료 요청이므로 감지 후 touch로 idle 시간이 갱신되어도 같은 대여가 활성 상태라면 종료한다.

Listener는 patrol을 실행하는 스레드에서 Main 락 밖으로 호출한다.
이벤트의 종료 작업은 thread-safe이므로 다른 worker에 전달해 나중에 호출해도 된다.
Listener에서 `event.forceClose()`를 호출하는 것은 허용한다. 추상 수명 훅의 재진입 금지와 구분한다.
중첩 patrol은 기존 skipped 규칙을 따른다. Listener 예외는 진단하고 다른 대상 검사를 계속하며,
사용자 Listener의 예외를 이유로 강제 종료 정책을 임의로 추가하지 않는다.

같은 대상·세대에 대해서는 한 patrol에서 한 번만 알린다. Listener가 종료하지 않았거나
예외를 던진 경우, 다음 patrol에서도 제한을 초과하면 다시 알린다.
이미 감지한 대여가 이벤트 전달 전에 반환될 수 있으므로 이벤트는 감지 시점의 기록이며,
종료 성공 여부는 `forceClose()` 반환값으로 판단한다.
Main/Sub의 명시적 close 및 객체 사용 실패에 따른 폐기는 누수 Listener로 지연하거나 막지 않는다.

### 공개 API 방향

공개 API 계약은 다음과 같다.

| 타입 | 메서드 | 계약 |
| --- | --- | --- |
| `abstract MainContainer<T>` | `protected MainContainer(config)` | 설정 저장, 기본 누수 처리로 감지 대상 forceClose |
| | `protected MainContainer(config, leakListener)` | 사용자 Listener 주입, null이면 기본 처리 |
| | `final start()` | initial개 Holder 생성 완료 후 OPEN으로 전환 |
| | `protected abstract ObjectHolder<T> createNewHolder()` | 독립적인 새 Holder와 내부 객체 생성 |
| | `protected abstract boolean isRecycleable(ObjectHolder<T> holder)` | 정상 반환 Holder의 재사용 가능 여부 판정 |
| | `protected abstract void onRecycle(ObjectHolder<T> holder)` | 재사용할 Holder 수준의 리소스 정리 |
| | `protected abstract void onDestroy(ObjectHolder<T> holder)` | 재사용하지 않을 Holder의 최종 리소스 해제 |
| | `protected createSubContainer()` | 새 Sub 생성 확장 지점, onBorrow 재정의에 사용 |
| `MainContainer<T>` | `borrowContainer()` | 기본 객체 수를 확보한 새 Sub 핸들 반환 |
| | `newObject(SubContainer<T>)` | 유효한 Sub에 Holder 추가 배정, idle 부족 시 createNewHolder 호출 |
| | `recycleContainer(SubContainer<T>)` | 통계 비교·불일치 경고 후 일괄 반환, 최초 유효 반환이면 true |
| | `patrol()` | 검사·만료·격리·정리·실패 수와 skipped 여부 반환 |
| | `stats()` | 락 아래 일관된 관리 상태 snapshot 반환 |
| | `close()` | 신규 대여 차단, 모든 Sub 무효화, 유휴 객체 정리, scheduler 취소 |
| `SubContainer<T>` | `protected void onBorrow()` | Main에서 이 Sub를 대여할 때 호출하는 훅, 기본 no-op |
| | `HolderLease<T> borrowObjectHolder()` | owner 전용, 반환함 drain 후 스택 pop, 부족하면 Main.newObject |
| | `recycleObjectHolder(HolderLease<T>)` | owner 전용, 판정·정리 성공 후 Sub 스택 push |
| | `stats()` | 현재 Sub 대여의 borrow/recycle 및 정리 상태 snapshot |
| | `touch()`, `patrol()`, `close()` | owner 전용, Main에 활동 기록/자식 검사/반환 위임 |
| `HolderLease<T>` | `use(Consumer<? super T>)` | 유효성 검사 후 사용 구간 추적 |
| | `touch()`, `recycle()`, `close()` | Main 경유, worker에서도 사용 가능, 멱등 반환, AutoCloseable 지원 |
| `LeakListener` | `onLeak(LeakEvent)` | 감지 이벤트 처리, 필요 시 event.forceClose 호출 |
| `LeakEvent` | `forceClose()` | 감지 대상·세대만 Main을 통해 멱등 강제 종료 |

`createNewHolder()`의 null은 생성 실패이다. `onRecycle(holder)`은 다음 대여자가 사용할 수 있도록
이전 대여의 데이터와 임시 리소스를 정리한다. `onDestroy(holder)`는 최종 해제이며 메모리 객체만
보유했다면 no-op이어도 된다. 각 실제 Holder에 대한 onDestroy는 최대 한 번 시도한다.
`use()`로 만든 불변 결과는 호출자 소유이며 내부 가변 참조를 외부에 보존해서는 안 된다.

Main `close()`는 진행 중인 사용자 콜백의 완료를 기다리지 않는다. 호출 시 정리 가능한
유휴 객체의 onDestroy는 실행하므로 항상 즉시 반환하는 API는 아니다.
나중에 끝난 작업이 자신의 정리를 완료하며 CLOSED Main에 자원을 다시 등록하지 않는다.
`stats()`는 종료 후에도 격리·정리 중 개수를 확인할 수 있다.

### 설정과 용량 제한

initial/min/max는 **Main 전체의 실제 `ObjectHolder<T>` 인스턴스 수**이다. Holder는 T 하나를 관리한다.
대여 핸들 수는 풀 용량에 중복 산입하지 않는다. Sub 개수와 Sub별 초기 배분 개수는
별도 설정이다. min은 최소 idle 개수가 아니라 최소 전체 보유 개수로 정의한다.
따라서 모든 객체가 대여 중이어도 min을 만족할 수 있으며 idle 확보를 위해 max를 넘지 않는다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `initial` | 16 | start 시 createNewHolder로 미리 생성할 Holder 수 |
| `min` | 8 | Main이 유지하려는 전체 객체 수의 하한 |
| `max` | 1,024 | 생성 예약·Main/Sub 유휴·대여·정리·격리 슬롯을 모두 포함한 상한 |
| `initialObjectsPerSub` | 8 | Sub마다 확보할 초기 객체 수, 0 이상 |
| `maxSubContainers` | 128 | ACTIVE 및 정리 미완료 REVOKED Sub 합계 상한 |
| `poolIdleLimit` | 비활성 | 활성화 시 오래 유휴인 Main 풀 객체를 min까지 축소 |
| `subBorrowLimit`, `subIdleLimit` | 비활성 | Sub 총 대여/유휴 제한 |
| `holderBorrowLimit`, `holderIdleLimit` | 비활성 | Holder 총 대여/유휴 제한 |
| `patrolInterval` | 1초 | 자동 patrol 활성 시 fixed delay |
| `automaticPatrol` | false | 명시적 활성화 필요 |
| `captureBorrowStack` | false | 진단 비용 때문에 선택 기능 |

시간 제한은 업무 시간 정보가 없으므로 임의의 강제 만료값을 기본 활성화하지 않는다.
자동 patrol은 누수 검사 외에도 min 보충 및 선택적인 Main 유휴 풀 축소를 수행할 수 있다.
활성 Duration은 양수이고 nanos로 안전하게 변환 가능해야 한다. 비활성은 별도로 표현한다.
`0 <= min <= initial <= max`, `max > 0`을 만족해야 한다.
Sub 상한은 양수이고 Sub 초기 배분 개수는 0 이상 max 이하여야 한다.
`initialObjectsPerSub × maxSubContainers` 전체를 미리 생성하지 않는다.

Main 객체 상한 또는 Sub 상한 도달은 `PoolExhaustedException`으로 즉시 실패한다.
추가 생성 실패는 createNewHolder의 원인을 보존한 예외로 구분한다.
만료로 격리한 객체를 무조건 새 객체로 대체하여 상한을 우회하지 않는다.
슬롯 수 상한은 객체 그래프의 byte 상한이나 외부 자원 수의 절대 상한을 뜻하지 않는다.

### 추상 훅과 크기 조절 절차

구체 Main 하위 클래스에서 네 개의 추상 훅을 구현한다. Supplier와 ObjectLifecycle 파라미터는 제거한다.
설정과 선택적인 Listener만 상위 생성자에 전달한다. 기본 클래스의 생성자에서는 추상 메서드를
호출하지 않는다. 하위 클래스 생성 완료 후 호출자가 `start()`를 실행하고 Main을 공유한다.
start 전의 borrow/patrol은 거절한다. OPEN에서 start 재호출은 no-op, STARTING 중 재호출은
명시적인 상태 예외, CLOSED에서는 재시작을 거절한다. 자동 patrol은 OPEN 이후 시작한다.

createNewHolder는 매 호출마다 독립적인 새 Holder 및 T를 생성한다. 공유 singleton 반환은
계약 위반이다. 다른 Holder에 대한 훅은 동시에 호출될 수 있으므로 구현체의 공유 상태는
thread-safe여야 한다. 같은 Holder의 use/재활용/폐기 훅은 겹치지 않게 Main이 보장한다.

1. 생성자에서 설정을 검증하고 start에서 `initial`개를 생성한다. 하나라도 실패하면 생성된 Holder를
   onDestroy로 정리하고 CLOSED로 전환하여 start를 실패시킨다. 부분 초기화한 Main에서는 대여하지 않는다.
2. Sub 배분 또는 추가 요청 시 Main idle 객체를 먼저 사용한다.
3. idle이 부족하면 Main 락 안에서 부족한 수만큼 생성 용량을 예약한다.
   `현재 슬롯 수 + 생성 예약 수 <= max`를 유지하고 락 밖에서 createNewHolder를 호출한다.
4. 생성 성공 시 락 안에서 OPEN 상태와 요청한 Sub의 유효성을 재검사하여 객체를 배정한다.
   Sub가 종료되었으면 Main idle로 보내고 Main이 종료되었으면 폐기한다.
5. 생성 실패 시 해당 예약을 해제하고 원인을 보존하여 호출자에게 전달한다.
   복수 객체 배분의 부분 실패는 해당 배분 전체를 rollback한다.
6. 객체 폐기 완료 후 보유 수가 min 미만이면 가능한 부족분을 같은 예약 절차로 한 번 보충한다.
   실패 시 진단하고 다음 borrow 또는 patrol에서 재시도한다. 무한 재시도는 하지 않는다.
7. min은 생성 실패와 정리 진행 중에는 일시적으로 미달할 수 있는 유지 목표이다.
   격리 객체는 용량에 포함되며, max를 우회한 보충을 하지 않는다. CLOSED에서는 보충하지 않는다.
8. poolIdleLimit이 활성화된 경우 patrol은 오래된 Main idle 객체만 폐기한다.
   min 아래로 줄이지 않도록 축소 예정 수를 Main에서 예약하고, 실패·격리 슬롯의 종료와
   경쟁해 일시적으로 min 미만이 되면 위 보충 절차를 적용한다. Sub 예약 객체는 축소 대상이 아니다.

`stats()`에는 initial/min/max, 생성 예약, Main idle, Sub 스택, Sub 반환함, 대여, 정리, 격리 수를 노출한다.
모든 상태 카운터는 서로 중복되지 않으며 합계가 max 이하인지를 확인할 수 있어야 한다.

### recycle 및 onBorrow 실행 순서

```text
HolderLease.recycle()
  → Main 관리 상태에서 대여 종료·recycleCount 증가 및 정리 책임 확정 (Main 풀 반환 아님)
  → 진행 중 use가 끝나면 실제 ObjectHolder에 isRecycleable(holder)
      true  → onRecycle(holder) → 상태 재검사 → 소유 Sub 스택에 push
                worker에서 완료하면 Sub 반환함 → owner가 스택에 push
      false → forceClose → onDestroy(holder) → 재활용 대상에서 제외

forceClose (누수, 사용 실패, Main 종료 등)
  → 즉시 대여 권한 취소 → 진행 중 작업 완료 → onDestroy(holder)

Main.borrowContainer()
  → Sub 대여 및 기본 Holder 배분 준비
  → caller/owner 스레드에서 sub.onBorrow() 한 번 호출
  → Main 상태 재검사 → Sub를 caller에게 반환

Sub.borrowObjectHolder()
  → worker 반환함 drain → 내부 스택 pop
  → 비어 있으면 Main.newObject(sub)로 충당 → borrowCount 증가 → 대여 핸들 반환

Sub.close() / Main.recycleContainer(sub)
  → Main에서 Sub 대여 종료와 통계 snapshot 확정
  → borrowCount != recycleCount 이면 warning
  → Sub 스택/반환함의 정리된 Holder를 Main으로 일괄 반환
  → 미반환 대여는 강제 종료, 정리 진행 중인 정상 반환은 완료 후 회수
```

isRecycleable은 부수 효과 없는 판정이며 false 경로에서는 onRecycle을 호출하지 않는다.
onRecycle은 Holder가 제공하는 정리 연산을 호출하는 확장 지점이다. 기본 클래스가 별도의 reset을
추가 실행하지 않는다. onDestroy도 실제 리소스 해제를 담당하며 기본 클래스는 풀 제거와 회계를 담당한다.
판정/onRecycle 예외도 onDestroy 경로로 보낸다. onRecycle 도중 forceClose 또는 Main close가
발생하면 onRecycle 종료 후 onDestroy를 실행하며 유휴 풀에 게시하지 않는다.

Sub의 onBorrow는 Holder 대여마다 실행하는 훅이 아니라 Sub 대여마다 한 번 실행하는 훅이다.
Sub의 owner와 대여 정보 및 초기 배분을 설정한 뒤 실행하되, 성공 전에는 Sub를 외부에 반환하지 않는다.
이 훅에서는 Sub의 대여별 상태를 준비한다. 준비 중 Sub의 borrow/recycle/patrol 등 공개 수명 연산은 거절한다.
훅 실패 또는 그 사이 Main 종료 시 Sub 대여를 rollback하고 해당 준비에 배정한 Holder를
onDestroy 경로로 정리한다. 호출자는 원인 예외를 받고 부분 초기화된 Sub를 받지 않는다.

### Sub borrow/recycle statistics 및 warning

통계는 Sub의 한 대여 기간을 기준으로 0부터 시작한다. worker도 반환할 수 있으므로
카운터는 Main이 보호하는 SubState에서 수명 전이와 함께 갱신한다. Sub 스택에는 락을 추가하지 않는다.

| 항목 | 증가 시점 |
| --- | --- |
| `borrowCount` | 유효한 Holder 대여가 성공할 때마다 1 증가 |
| `recycleCount` | 아직 유효한 대여의 최초 정상 recycle 접수 시 1 증가 |
| `forcedCloseCount` | 정상 recycle 없이 활성 대여를 강제로 끝낸 경우 1 증가 |
| `outstandingCount` | 현재 활성 Holder 대여 수 |
| `pendingCleanupCount` | 정상 반환 접수 후 use 종료나 정리 훅 완료를 기다리는 수 |

초기 배분·추가 생성·스택 push/pop 자체는 카운터를 중복 증가시키지 않는다.
중복 recycle, 다른 소유자 반환, 이미 강제 종료된 대여의 늦은 recycle은 recycleCount에 포함하지 않는다.
정상 recycle 후 isRecycleable=false 또는 정리 실패로 폐기한 경우에도 recycleCount는 유지한다.
이 경우는 정상 반환 접수 뒤 폐기한 것이므로 forcedCloseCount에는 중복 산입하지 않는다.

대여 회계는 `borrowCount = recycleCount + forcedCloseCount + outstandingCount`를 만족한다.
Sub recycle 시 Main 락 안에서 신규 대여를 막는 상태 전이와 통계 snapshot을 함께 확정한다.
그 직전까지 접수된 worker 반환은 포함하고, 그 이후의 미반환 대여는 회수 대상이므로
늦은 반환으로 확정한 통계나 로그를 바꾸지 않는다. 경고는 Main 락 밖에서 기록한다.

`borrowCount != recycleCount`이면 Sub ID·대여 세대·두 카운터·차이·강제 종료 수·미반환 수·정리 대기 수를
`System.Logger`의 WARNING 수준으로 한 번 기록한다. 별도 로깅 의존성은 추가하지 않는다.
Listener 지정 여부와 관계없이 통계 불일치 경고를 출력한다. 경고는 회수를 막지 않는다.
이미 patrol이 강제 종료한 대여도 불일치에 포함되므로 이를 새 누수라고 단정하지 않는다.
반환 횟수가 같아도 정리 대기가 남을 수 있으며, 이를 이유로 사용 중인 자원을 재배정하지 않는다.
두 카운터가 같으면 이 통계 경고는 출력하지 않는다. 중복 Sub recycle에서도 경고를 재출력하지 않는다.

### 구현 시 검증할 수용 조건

1. 초기 Holder 개수, 추가 대여, onRecycle 정리, Sub 반환 후 다른 Sub의 재사용을 검증한다.
2. caller borrow → worker use/dispose → caller Sub 반환을 latch로 제어해 검증한다.
3. 중복 close, 다른 소유자 반환, 만료 후 use, 오래된 핸들의 재반환이 새 대여에 영향을 주지 않는다.
4. 가짜 시계로 두 계층의 borrow/idle 경계(`>=`), touch, 비활성 제한을 검증한다.
5. 기본 Listener에서 실행 중 use와 patrol을 겹쳐 즉시 권한 취소되지만 콜백 종료 전 정리 훅이 호출되지 않음을 검증한다.
6. recycle/patrol/Sub close/Main close 경쟁에서도 슬롯의 중복 대여·중복 정리가 없다.
7. 생성 부분 실패, 판정/onRecycle/onDestroy 실패, 리스너 실패 뒤 회계와 후속 patrol을 검증한다.
8. 정리 중 Main 종료, worker 늦은 반환, 콜백 예외, 동시 use 거절을 검증한다.
9. 생성 예약과 격리까지 포함한 상한을 검증하고, latch로 막은 콜백은 테스트 종료 시 풀어준다.
10. 동시 부하 후 전체 슬롯 수가 상태별 합계와 일치하고 활성 대여·정리·격리가 0인지 확인한다.
11. Sub를 다른 스레드에서 직접 호출하면 거절되고 worker Holder 반환은 해당 Sub 반환함에 전달된다. Sub 회수 전 Main idle 수는 증가하지 않는다.
12. patrol이 Sub 예약 슬롯을 회수·재배정한 후 owner가 오래된 로컬 토큰을 사용해도 새 대여에 접근할 수 없다.
13. initial 선생성 수, min 보충, min까지의 유휴 축소, 설정 범위 오류를 검증한다.
14. 동시 createNewHolder 호출과 부분 실패·Main 종료가 겹쳐도 생성 예약 포함 max를 초과하지 않는다.
15. Listener 생략/null 시 Sub 및 Holder 감지 대상만 forceClose하고 Main은 계속 사용할 수 있다.
16. 사용자 Listener의 관찰만 수행하는 경우 자동 종료하지 않으며, 명시적 forceClose 시에만 강제 종료한다.
17. Listener 예외와 다음 patrol 재알림, 늦은 이벤트 처리, 중복 forceClose가 수명 정합성을 훼손하지 않는다.
18. 상위 생성자에서 추상 훅이 호출되지 않고 start에서 initial개 생성, 실패/종료 경쟁 시 전부 정리되는지 검증한다.
19. isRecycleable true는 onRecycle 후 재등록, false는 onRecycle 없이 onDestroy 후 제외됨을 검증한다.
20. 중복 반환과 forceClose 경쟁에서 훅 호출이 중복되지 않으며 onRecycle과 onDestroy가 동시에 실행되지 않는다.
21. Sub 대여당 onBorrow 한 번, Holder 대여에서는 미호출, 훅 실패 시 rollback을 검증한다.
22. 초기 개수로 스택이 준비되고 LIFO pop/push로 재사용되며, 스택/반환함 소진 시에만 Main.newObject를 호출한다.
23. Holder 정상 반환에서는 Main 풀로 이동하지 않으며 Sub recycle에서 일괄 반환되는지 검증한다.
24. borrow/recycle 일치 시 무경고, 불일치 시 정확한 수치의 warning 한 번, 중복/잘못된 반환 시 통계 불변을 검증한다.
25. worker 반환과 Sub 회수를 경쟁시켜 통계 snapshot·반환함·정리 대기 Holder의 중복 이동 및 누락이 없음을 검증한다.

성능 검토는 실제 유틸리티 구현 후 직접 할당과 비교한다. 단순 POJO와 생성 비용이 큰 객체,
동시 스레드 수별 처리량·지연·할당량·GC를 측정한 뒤 락 분리 필요성을 결정한다.
수용 조건은 아래 구현 검증 기록과 테스트 클래스에서 검증한다. 실제 할당/GC/경합 벤치마크는 아직 수행하지 않았다.

### 설계 판단 요약 및 참고

구현의 핵심 선택은 독립 library JAR, thread-safe Main과 락 없는 owner 전용 Sub,
Main의 추상 생성/판정/정리 훅, initial/min/max, Sub onBorrow, 대여별 새 핸들과 만료 객체 폐기이다.
이번 구현 요청에 따라 모듈 생성과 구현·수명 검증까지 수행하였다.

시간 측정과 scheduler 동작의 근거는 Java 21 공식 문서를 따른다.

- [System.nanoTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()): 경과 시간 측정용이며 벽시계와 무관하다.
- [ScheduledExecutorService](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html): 상대 지연 기반 주기 실행에 사용한다.

### 구현 파일과 반환 동작

| 파일 | 책임 |
| --- | --- |
| `MainContainer.java` | 풀/registry/용량 예약, 세대 검증, 락 밖 훅 실행, 일괄 회수, patrol와 scheduler |
| `SubContainer.java` | owner 검사와 LIFO 스택, Main 관리 상태에 대한 위임, onBorrow 확장 |
| `ObjectHolder.java`, `HolderLease.java` | 재사용하는 실제 객체와 재사용하지 않는 대여 핸들 분리 |
| `ContainerConfig.java` | 불변 설정, builder와 범위/Duration 검증 |
| `LeakEvent.java`, `LeakListener.java` | 감지 snapshot과 대상·세대에 바인딩된 thread-safe forceClose |
| `ContainerStats.java`, `SubStats.java`, `PatrolResult.java` | 불변 회계·검사 결과 |
| `PoolExhaustedException.java`, `ObjectCreationException.java` | 용량 부족과 생성 실패 구분 |

ObjectPool/SubState는 Main 내부 map/deque/slot 상태로 구현하여 별도의 공개 타입을 늘리지 않는다.
2026-10-12부터 Lease와 로컬 Token은 슬롯 객체 대신 ID·세대를 저장하고 Main registry에서 검증한다.
활성 Lease/Sub는 Main을 강하게 참조하지만, Lease 반환/회수와 Sub RELEASED 시점에는
이를 해제하고 약한 참조만 남긴다. LeakEvent의 종료 동작도 Main 약한 참조와 ID·세대만 저장한다.
`Main.recycleContainer(sub)`는 어느 스레드에서든 registry만으로 회수한다. Sub 메서드를 호출하거나
Sub 스택을 읽거나 비우지 않는다. 소유 스레드의 `Sub.close()`가 자신의 로컬 토큰을 비운다.
정상 반환 시 반환함과 스택에 해당하는 슬롯을 registry에서 직접 일괄 회수하므로
반환 직전 worker 반환함을 로컬 스택으로 옮기는 불필요한 작업은 생략한다.

Main 통계의 `total`은 현재 슬롯 수이며 `total + creating <= max`이다.
`mainIdle + subStack + subReturns + borrowed + recycling + quarantined + destroying == total`이다.
`destroying`은 사용이 끝나 onDestroy 실행 중인 슬롯이며 용량에서 아직 제외하지 않는다.
`activeSubs`에는 공개 전 PREPARING도 포함하고, `revokedSubs`는 진행 중 사용/정리/생성 때문에
등록 해제를 기다리는 Sub 수이다. `destroyedCount`는 최종 폐기 시도 수이며 실제 외부 자원 해제 성공을 보장하지 않는다.

`PatrolResult.forcedClosed`는 이번 감지 이벤트 중 리스너 처리 동안 forceClose에 성공한 대상 수이다.
Sub 회수에 딸린 자식은 별도 이벤트/대상 수로 중복 집계하지 않는다. `quarantined`는 검사 종료 시점의
격리 슬롯 수이며 `cleaned`, `failures`는 검사 전후 Main 카운터 차이이다. 동시에 처리되는 다른 작업의
정리도 이 카운터 차이에 포함될 수 있다. 저장해 둔 이벤트를 검사 종료 뒤 실행한 회수는 해당 결과에 포함하지 않는다.

### 사용 예제

```java
import com.inzent.apim.utilities.recycle.*;
import java.util.concurrent.Executors;

final class BuilderPool extends MainContainer<StringBuilder> {
    BuilderPool() {
        super(ContainerConfig.builder()
                .initial(16).min(8).max(128).initialObjectsPerSub(4).build());
    }
    @Override protected ObjectHolder<StringBuilder> createNewHolder() {
        return new ObjectHolder<>(new StringBuilder(256));
    }
    @Override protected boolean isRecycleable(ObjectHolder<StringBuilder> holder) {
        return holder.value().capacity() <= 4096;
    }
    @Override protected void onRecycle(ObjectHolder<StringBuilder> holder) {
        holder.value().setLength(0);
    }
    @Override protected void onDestroy(ObjectHolder<StringBuilder> holder) {
        // 외부 자원이 없으므로 별도 해제 불필요.
    }
}

// caller 스레드:
try (var main = new BuilderPool(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
    main.start(); // 하위 클래스 생성 완료 후 명시적으로 초기화한다.
    try (var sub = main.borrowContainer()) {
        var lease = sub.borrowObjectHolder();
        workers.submit(() -> {
            try (lease) {
                lease.use(value -> value.append("worker payload"));
            } // worker 반환은 소유 Sub의 반환함으로 전달된다.
        }).get(); // 작업 완료 후 caller가 Sub를 반환한다.
    }
}
```

Managed POJO의 dispose/close에서는 저장한 `HolderLease.close()`를 호출하면 된다.
완료 POJO가 장기 보관된다면 대여 필드도 해제한다. 여러 worker가 동시에 dispose한다면
`AtomicReference.getAndSet(null)` 등으로 필드 인계를 직렬화한다. 반환된 기본 Lease는
Main·payload를 강하게 보유하지 않지만, 사용자 POJO나 Sub 확장 클래스가 별도로 가진 참조까지 제거하지는 않는다.
중복 dispose는 안전하다. worker에게 Sub의 public 메서드를 직접 호출하게 하지 않는다.
가변 `T` 참조를 use 밖에 저장하지 않으며, 필요하면 콜백 안에서 불변 결과를 복사한다.

제한 비활성은 Duration의 `null`로 표현한다. `automaticPatrol` 기본값은 false이며,
활성화할 때 `holderBorrowLimit`/`holderIdleLimit`/`subBorrowLimit`/`subIdleLimit`을 업무 시간에 맞춰 지정한다.
제한이 없어도 자동 patrol은 min 보충과 선택적 poolIdleLimit 축소를 수행한다.
리스너를 생략하거나 null로 주면 감지 대상을 forceClose하고, 사용자 리스너를 주면 관찰만으로는 종료하지 않는다.

### 빌드와 검증

프로젝트 루트에서 실행한다.

```powershell
mvn -f object-recycle-container/pom.xml clean verify
```

런타임 외부 의존성은 없고 테스트에는 JUnit Jupiter 5.13.4를 사용한다.
빌드 산출물은 `object-recycle-container/target/object-recycle-container-1.0.0-SNAPSHOT.jar`이다.
테스트 로그의 Sub 통계 불일치 WARNING은 의도적으로 강제 회수한 시나리오의 정상 진단이다.

검증 테스트 구성:

2026-10-11 최종 검증: Java 21.0.3 / Maven 3.9.11에서
`mvn -f object-recycle-container/pom.xml clean verify` 실행 결과 **BUILD SUCCESS**.
총 44개 테스트, 실패 0, 오류 0, 건너뜀 0이며 library JAR 생성까지 확인하였다.

- `MainContainerTest`: 초기화, 정상 재사용, worker 반환, 다른 소유자, 만료 핸들,
  사용 중 반환/회수, 동시 use, 사용 실패, start 실패, patrol 집계와 Main 타 스레드 회수.
- `ContainerLifecycleTest`: 설정 오류, 부분 할당 rollback, 판정/정리/폐기 실패,
  min 보충 재시도, Main idle 축소, 두 계층 시간 제한/비활성/단조 시계 wrap,
  사용자 리스너/지연 이벤트, onBorrow, 잘못된 팩토리, 경고 횟수/수치, LIFO 추가 배정.
- `ContainerConcurrencyTest`: 생성/초기화/onBorrow/사용/정리 중 종료,
  미완료 회수의 객체·Sub 상한, 동시 생성 예약, 메타데이터 락 밖 훅, 중첩/동시/자동 patrol,
  worker 반환/일괄 회수 경쟁, rollback 이후 다른 Sub로 재배정된 슬롯 보호,
  min 생성 부분 실패와 대여의 경쟁, 8개 스레드 × 200회 부하 및 종료 회계.

동시성 테스트는 latch/barrier로 실행 순서를 제어하며 sleep에 의존하지 않는다.
공개 API 사이의 매우 짧은 예약/rollback 경쟁 한 건은 테스트에서 private 락을 교체해
예약 트랜잭션 직후에만 정지한다. 프로덕션에 테스트 전용 훅은 추가하지 않았다.

## Object Recycle Container 구현·메모리 누수·NPE 분석 리포트 (2026-10-11)

> 아래 리포트는 수정 전 기준 커밋의 분석 기록이다. 지적 사항의 현재 적용 상태는 문서 끝의
> 「2026-10-12 분석 결과 적용」을 참조한다. 아래 소스 행 번호도 수정 전 기준이다.

### 분석 범위와 결론

- 기준 커밋: `fb4863150a65a043da3580850b4a283d9909885b`.
- 대상: `object-recycle-container/src/main/java/com/inzent/apim/utilities/recycle/`의 전체 구현, 기존 테스트와 본 문서의 수명 계약.
- 방법: 정적 참조·상태 전이 분석, 기존 Maven 검증, 저장소 밖 임시 Java 프로그램을 통한 추가 재현. 리플렉션은 참조 존재를 읽는 용도로만 사용하였다.
- 변경 범위: **이 Markdown 문서만 변경**. Java 소스, 테스트 소스, POM은 변경하지 않았다. 빌드 검증에 따른 `target/` 산출물은 제외한다.
- 구조 변경내역: 2026-10-11 분석 리포트를 `project.md`에 추가. 모듈·패키지·API 구조 변경 없음.

**판정:** 일반 반환·회수의 상태 관리와 null 입력 방어는 대체로 갖춰져 있다. 그러나 정리 훅에서 `Error`가 발생하는 경우 복구되지 않는 슬롯 점유를 재현했다. 종료된 핸들·이벤트·Sub를 애플리케이션이 보관하면 풀 회계와 별개로 객체 그래프가 유지될 수 있다. 정상 계약하의 내부 null 역참조 결함은 이번 분석에서 확인하지 못했다. 이는 모든 실행에 대한 무누수·무NPE 보증은 아니다.

본 리포트의 P1은 우선 검토할 정리 불능 결함, P2는 사용 방식·설정에 따라 나타나는 잔존 참조 및 운영 위험이다. 발생 빈도는 실제 확장 훅과 서비스 코드가 없어 정량화하지 않았다.

### 1. 구현 구조와 수명 분석

이하 `MainContainer.java:행` 등은 위 대상 패키지 안의 파일과 분석 시점의 행 번호를 뜻한다.

| 구성 | 구현 및 책임 | 분석 근거 |
| --- | --- | --- |
| Main | 단일 `ReentrantLock`으로 registry, 슬롯 상태, 생성 예약, 통계를 보호. 생성·정리·리스너 훅과 use 콜백은 락 밖에서 실행 | MainContainer.java:54–67, 87–95, 292–311, 380–414 |
| Sub | 소유 스레드만 LIFO stack을 조작. Main의 타 스레드 회수는 registry만 사용 | SubContainer.java:8–27; MainContainer.java:346–360 |
| HolderLease | 매 대여마다 발급. Main·Slot·SubState·generation을 저장하며 이전 세대의 재사용을 거절 | HolderLease.java:8–20; MainContainer.java:255–265, 284–290 |
| 정상 반환 | BORROWED → RECYCLING → LOCAL/RETURNED. owner 반환은 stack, worker 반환은 returns에 적재. 다음 owner borrow가 returns를 이동 | MainContainer.java:271–281, 321–331, 394–401 |
| Sub 반환 | LOCAL/RETURNED는 Main IDLE로 이동. 미반환 BORROWED는 폐기. 실행 중 정리는 지연 | MainContainer.java:334–374 |
| 폐기 | 권한 무효화 → QUARANTINED → DESTROYING → registry 제거. 사용 중이거나 정리 중이면 완료를 기다림 | MainContainer.java:362–414 |
| patrol | Sub 만료 후 Holder 만료 검사. 기본 리스너는 forceClose, 사용자 리스너는 정책 위임. Main IDLE 축소와 min 보충 | MainContainer.java:469–564 |
| 용량 제한 | slots + creating을 max로 제한. 회수 대기 슬롯도 용량 점유. Sub는 등록 상태 기준 maxSubContainers 제한 | MainContainer.java:144, 180–190, 416–429 |

정상 cleanup에서는 `slots`, `holders`, `values`와 슬롯의 owner/lease 참조를 제거하고, 소유 슬롯·생성 예약이 없는 REVOKED Sub를 registry에서 제거한다. 따라서 순환 참조가 있다는 사실만으로 누수라고 판정할 수 없다. **GC root에서 남은 강한 참조 경로와 회수되지 않는 외부 자원**이 판단 기준이다.

### 2. 메모리 누수 및 자원 잔존 분석

#### M-01 / P1 / 재현 확인: 판정·재활용 훅의 Error 이후 정리 불능

- 위치: MainContainer.java:369–386, 567–583.
- 조건: `isRecycleable()` 또는 `onRecycle()`가 `AssertionError`, `LinkageError` 등 `Error`를 던지고 호출 환경이 계속 동작하는 경우. 일반 `RuntimeException` 경로와 구별해야 한다.
- 원인: `claimCleanup()`이 `cleaning=true`를 설정하지만, cleanup의 판정·재활용 구간은 `RuntimeException`만 잡는다. `Error`가 전파되면 cleaning 해제·폐기 전이까지 도달하지 않는다.
- 결과: 이후 Main close는 슬롯을 QUARANTINED로 바꾸지만 cleaning이 true여서 정리를 재획득하지 못한다. Sub와 Holder가 registry에 남고 용량을 계속 점유한다. 해당 경로는 failureCount도 증가시키지 않는다.
- 재현: initial=1, min=0, max=1, perSub=1. start → Sub/Holder borrow → 해당 훅에서 AssertionError → lease.close 예외 포착 → Main.close.
- 관측: 두 훅 각각 `state=CLOSED, total=1, quarantined=1, revokedSubs=1, destroyedCount=0, failureCount=0`, onDestroy 호출 0회.
- 개선 검토: Error 전파 정책과 별개로 cleanup 소유권·상태를 finally에서 일관되게 정리하는 구조가 필요하다. VM 치명적 장애까지 정상 복구할 수 있다고 가정해서는 안 된다. 이번에는 수정하지 않았다.

#### M-02 / P1 / 재현 확인: 폐기 훅의 Error가 후속 일괄 정리를 중단

- 위치: MainContainer.java:376–377, 406–413, 569–583.
- 조건: 여러 슬롯을 일괄 정리하는 중 하나의 `onDestroy()`가 Error를 던지는 경우.
- 원인: 현재 슬롯은 finally에서 registry에서 제거되지만 Error는 `runCleanup()`의 for 루프를 중단한다. 뒤의 슬롯들은 이미 claim되어 cleaning=true인 채 실행되지 않는다.
- 재현: initial=2, min=0, max=2, perSub=2. start → 첫 onDestroy에서 AssertionError → Main.close 예외 포착 → Main.close 재호출.
- 관측: `CLOSED, total=1, quarantined=1, destroyedCount=1, failureCount=0`, onDestroy 호출 1회. 두 번째 close는 CLOSED 조기 반환으로 남은 슬롯을 처리하지 않는다.
- 영향: 후속 슬롯의 heap 참조와 외부 자원 잔존. 첫 슬롯도 훅이 자원 해제 전에 실패했다면 외부 자원 해제는 불확실하다.
- 개선 검토: 일괄 정리 중 예외가 발생해도 아직 실행되지 않은 슬롯의 claim을 남기지 않는 정책, 오류 집계·전파 및 재시도 가능성 검토.

#### M-03 / P2 / 참조 경로 재현 확인: 반환·종료된 HolderLease가 객체를 계속 보유

- 위치: HolderLease.java:8–13; MainContainer.java:22–46, 411–412; ObjectHolder.java:7–9.
- 참조 경로: 애플리케이션의 완료 POJO/캐시 → HolderLease → Slot → ObjectHolder → T.
- 추가 경로: HolderLease → Main → 현재 풀 전체, HolderLease → owner SubState → ownerThread/handle/stack.
- 정상 recycle/폐기는 `slot.lease`를 지우지만 외부 Lease의 final slot/main/owner는 지우지 않는다. Slot.holder와 ObjectHolder.value도 final이다.
- 재현: 1 KiB byte[]를 대여하고 lease, Sub, Main을 모두 close한 뒤 이전 lease를 보관. Main의 total=0이지만 리플렉션으로 lease.slot.holder.value의 1,024바이트와 owner 참조 존재 확인.
- 판정: 호출자가 핸들을 놓으면 이 경로 자체는 GC를 막지 않는다. 반대로 완료 POJO가 lease를 계속 보유하면 **논리적 반환은 성공했지만 실제 메모리 도달 가능성은 남는다**. 수명이 끝난 서로 다른 슬롯의 핸들을 계속 쌓으면 잔존 객체 수는 현재 max를 넘어갈 수 있다.
- 운영 대응: Managed POJO의 dispose에서 close 후 보유 필드를 해제하고, 완료 핸들을 무제한 이력에 저장하지 않는다. 단순 `HolderLease.close()` 호출만으로 참조가 끊어진다고 가정하지 않는다.
- 설계 검토: 외부 핸들에는 식별자·세대만 두는 간접 참조 구조 또는 종료 후 참조 해제 검토. 동시 use 및 오래된 핸들 무효화 계약을 유지해야 한다.

#### M-04 / P2 / 참조 경로 재현 확인: Main 경유 회수는 Sub의 로컬 stack을 비우지 않음

- 위치: SubContainer.java:8, 27; MainContainer.java:334–360, 567–583.
- 조건: `Main.recycleContainer(sub)`, patrol 또는 Main.close로 회수한 Sub 핸들을 계속 보관하면서 owner가 `Sub.close()`를 호출하지 않는 경우.
- 원인: owner 전용 자료구조를 타 스레드가 건드리지 않도록 revoke는 stack을 변경하지 않는다. stack.clear는 owner의 Sub.close 경로에 있다.
- 경로: 보관된 Sub → stack → Token → Slot → Holder → T.
- 재현: 초기 슬롯 1개를 가진 Sub 대여 → Main.recycleContainer(sub) → Main.close → 보관된 Sub stack 크기 1 확인. 풀 registry 제거와 로컬 토큰 제거는 별개다.
- 판정: 세대·소유권 검증으로 오래된 토큰의 사용은 막지만 참조는 남는다. owner가 Sub.close하거나 외부 Sub 참조를 해제해야 한다. M-03에서 owner.handle을 통해 이 stack이 함께 보존될 수도 있다.
- 개선 검토: 강제 회수 후 owner 정리 계약을 명확히 하고, 장기적으로 로컬 토큰이 큰 payload를 직접 붙잡지 않는 구조 검토. 타 스레드의 무조건 stack.clear는 현재 스레드 안전성 계약과 충돌한다.

#### M-05 / P2 / 정적 확인: LeakEvent 장기 보관이 Main과 대여 그래프를 유지

- 위치: LeakEvent.java:17–25, 36–39; MainContainer.java:503–512, 521–523.
- closeAction 람다는 Main과 SubState 또는 HolderLease를 캡처한다. 이벤트의 forceClose 성공 이후에도 closeAction은 final로 남는다.
- 사용자 리스너가 이벤트 자체를 장기 큐·이력에 저장하면 M-03/M-04 경로까지 유지될 수 있다. 관찰만 하는 리스너는 같은 만료 대여에 대해 다음 patrol에서도 새 이벤트를 받을 수 있어 외부 큐가 계속 커질 수 있다.
- 라이브러리 내부에 무한 이벤트 저장소가 있는 것은 아니다. 이벤트 보관 정책은 호출자의 책임이며, 지연 forceClose 기능 때문에 생긴 참조 보존이다.
- 대응: 이력에는 target/id/reason/시간 등 값만 복사하고, 실행 가능한 이벤트 큐에는 용량·TTL·소비 후 제거 정책을 둔다. 이 항목은 별도 GC 재현 없이 소스 참조 경로로 판정했다.

#### M-06 / P2 / 설계상 조건부 위험: 감시 비활성·반환 누락·Sub 장기 점유

- 위치: ContainerConfig.java:27–30; MainContainer.java:350–360, 498–519, 543–558.
- 기본값은 automaticPatrol=false이고 모든 timeout은 null이다. 기본 설정만으로 반환 누락이 자동 회수되지 않는다. 자동 patrol을 켜도 관련 제한값이 null이면 만료 감지는 하지 않는다.
- 정상 반환 Holder도 Sub에 남는다. poolIdleLimit은 Main idle만 축소하므로 ACTIVE Sub가 점유한 LOCAL/RETURNED 객체는 축소 대상이 아니다. Sub 제한 또는 명시적 Sub 반환이 필요하다.
- 사용자 LeakListener는 forceClose를 직접 실행하지 않으면 대여를 계속 유지하며, RuntimeException을 던져도 기본 강제 회수로 전환되지 않는다.
- max/maxSubContainers는 등록 개수 상한으로 무한 생성은 억제하지만, 서비스의 장기 점유·PoolExhaustedException은 가능하다. T의 내부 버퍼 크기와 M-03/M-05 외부 이력 크기는 이 상한으로 제한되지 않는다.
- 정상 풀 캐싱 자체를 누수로 판정하지 않는다. 예를 들어 onRecycle에서 논리적 길이만 초기화하는 가변 버퍼는 큰 내부 저장 공간을 계속 유지할 수 있으므로 실제 T와 훅 구현을 별도 확인해야 한다.

#### M-07 / P2 / 설계상 조건부 위험: 끝나지 않는 콜백·훅·scheduler

- 위치: MainContainer.java:123–128, 189–210, 298–310, 369–414, 560–583.
- use가 끝나지 않으면 inUse 상태 때문에 폐기는 지연된다. 생성 훅 정지는 creating 예약을, 정리 훅 정지는 cleaning과 슬롯을 유지한다. close는 진행 중 작업을 강제 중단하거나 완료를 기다리는 API가 아니다.
- 단일 patrol 스레드에서 listener/onDestroy 등이 정지하면 후속 감시가 지연된다. scheduler.shutdown도 실행 중 훅 완료를 보장하지 않는다.
- automaticPatrol=true인 Main을 close하지 않고 외부 참조만 버리면 주기 작업 `this::automaticScan`이 Main과 그 풀을 계속 참조할 수 있다. daemon이라는 속성은 객체 참조 해제 수단이 아니다.
- automaticScan은 RuntimeException만 잡으므로 Error로 인한 자동 감시 지속성도 추가 시험 대상이다. 이번 실행에서는 scheduler Error 시나리오를 별도 재현하지 않았다.
- 대응: Main의 명시적 close, use/훅의 유한 실행 보장, 외부 I/O timeout 및 취소 협력. quarantined/recycling/destroying/creating/revokedSubs가 장시간 유지되는지 관측한다.

#### M-08 / P2 / 정적·기존 테스트 확인: onDestroy 실패 후 외부 자원 해제 불확실

- 위치: MainContainer.java:406–413; 기존 ContainerLifecycleTest.java:50–61.
- RuntimeException(NPE 포함)이 발생하면 로그·failureCount 증가 후 registry에서 제거하고 자동 재시도하지 않는다. 따라서 풀 total이 감소해도 소켓·파일·native 메모리가 해제되었다는 뜻은 아니다.
- destroyedCount는 폐기 경로의 registry 제거 횟수이지 자원 해제 성공 횟수가 아니다.
- 개선 검토: 실제 onDestroy를 예외에 안전하게 작성하고 자원별 해제 결과를 별도로 계측한다. 재시도는 이중 해제 위험과 멱등성을 고려해 결정해야 한다.

### 3. NPE 가능성 분석

#### 3.1 공개 입력·팩토리 경계

| 진입점/조건 | 실제 결과 | 근거 및 해석 |
| --- | --- | --- |
| Main의 config 또는 clock=null | 즉시 NPE | MainContainer.java:73, 75. requireNonNull에 의한 명시적 계약 검증 |
| ObjectHolder(null) | 즉시 NPE | ObjectHolder.java:8. 재현 확인; null T의 풀 등록을 차단 |
| SubContainer 생성자 main=null | 즉시 NPE | SubContainer.java:10. 확장 클래스의 잘못된 생성 입력 |
| lease.use(null) | 즉시 NPE, 대여는 유지 | HolderLease.java:17. Main.use 진입 전 실패; 재현에서 borrowed=1. 호출자 finally/try-with-resources 반환 필요 |
| createNewHolder가 null 반환 | ObjectCreationException, 원인은 NPE | MainContainer.java:196–197. 생성 예약 rollback 대상; 기존 factoryNull 테스트 통과 |
| createSubContainer가 null/외부 Main/이미 사용된 Sub 반환 | IllegalArgumentException | MainContainer.java:139–141. 단락 평가로 null 역참조 방지 |
| Main.newObject/recycleContainer(null) | IllegalArgumentException | MainContainer.java:171–173. checkOwned 방어 |
| Sub.recycleObjectHolder(null) | 활성·owner 조건 충족 시 IllegalArgumentException | SubContainer.java:18–21. 만료/owner 위반이면 앞선 상태 검증 예외가 우선 |
| listener=null | 기본 강제 회수 리스너 사용 | MainContainer.java:74. 오류 아님 |
| 각 timeout=null | 해당 제한 비활성 | ContainerConfig.java:19–20; MainContainer.java:514–519 |
| patrolInterval=null | IllegalArgumentException | ContainerConfig.java:17. schedule에서 NPE가 발생하기 전에 거절 |

#### 3.2 사용자 콜백·훅에서 발생하는 NPE

| 발생 지점 | 처리 | 메모리/자원 영향 |
| --- | --- | --- |
| createNewHolder | ObjectCreationException으로 래핑, 할당 rollback | 훅이 일부 자원을 만든 뒤 반환 전에 실패하면 그 자원은 훅 자체가 정리해야 함 |
| createSubContainer | NPE 전파 | Sub registry 등록 전. 확장 훅 내부 자원은 확장 구현 책임 |
| Sub.onBorrow | revoke + cleanup 후 전파 | 준비 중 Sub의 슬롯 회수, stack.clear 수행 (MainContainer.java:158–160) |
| use(action) 본문 | finally에서 markDestroy·cleanup 후 예외 전파 | 정상 정리 훅 기준 실패 객체 재사용 방지; payload 내부 필드의 null은 컨테이너가 보증하지 않음 |
| isRecycleable/onRecycle | RuntimeException으로 포착, 진단 후 폐기 | 추가 재현의 onRecycle NPE에서 total=0, failureCount=1, destroy=1 |
| onDestroy | 포착·진단 후 registry 제거 | 자원 해제 성공은 보장 불가(M-08) |
| LeakListener | 포착·진단, 기본 forceClose 대체 없음 | 다음 patrol에서 다시 감지할 수 있으나 대여는 계속 남을 수 있음 |
| 주입한 LongSupplier clock | 별도 복구 계약 없음 | 사용자 clock 예외가 상태 변경 도중 발생하면 부분 상태가 남을 가능성. production 기본 System.nanoTime 경로와 구별; 미재현 |

#### 3.3 내부 null 역참조·경쟁 검토

- `requireActive(SubState)`와 `subSnapshot`은 null 가드를 직접 두지 않지만 공개 Sub 메서드는 checkOwner, Main 공개 Sub 인자 메서드는 checkOwned를 선행한다. 정상 외부 API 사용에서 미초기화 state는 먼저 거절된다.
- BORROWED 슬롯은 락 안에서 owner·lease를 설정하고, active 검증과 회수 전이도 같은 락으로 직렬화한다. holderEvent가 처리할 Lease 목록도 락 안에서 수집한다. 회수 경쟁만으로 null lease/owner를 사용하게 되는 경로는 확인하지 못했다.
- ObjectHolder.value는 생성자에서 null을 거절하고 final이며, Slot.holder도 null 팩토리 검증 뒤 설정된다. 따라서 정상 등록된 payload 자체가 내부 회수 과정에서 null로 바뀌는 구조는 아니다.
- use는 inUse 보호로 폐기를 지연한다. 만료 핸들은 정상적으로 IllegalStateException 또는 recycle=false로 처리되며 NPE가 기대 동작은 아니다.
- 리플렉션·동일 패키지 코드의 상태 훼손, 잘못된 외부 스레드 게시, 확장 훅이 다루는 T 내부 null은 위 판정의 보장 범위 밖이다.
- **결론:** 직접 NPE 발생 지점은 존재하나 주로 입력 검증 또는 사용자 코드 실패다. 내부 NPE 결함을 확정할 증거는 발견하지 못했다. use(null) 이후 반환을 생략하는 경우에는 입력 오류가 M-06 점유 문제로 이어질 수 있다.

### 4. 실행 검증과 재현 기록

환경: Windows 11, Java 21.0.3, Maven 3.9.11. 실행일: 2026-10-11 (Asia/Seoul).

```powershell
mvn -f object-recycle-container/pom.xml verify
```

결과: **BUILD SUCCESS**, 총 44개 테스트, 실패 0, 오류 0, 건너뜀 0.
MainContainerTest 10개, ContainerLifecycleTest 19개, ContainerConcurrencyTest 15개.
이번 명령은 clean 없이 기존 빌드 산출물을 사용하는 verify이며, 로그에서 main/test class가 최신 상태임을 확인했다.

추가 검증은 저장소 밖 임시 `Audit.java`를 Java source-file launcher로 실행했다. 기존 프로덕션 class를 classpath로 사용하고, 프로젝트 소스에 테스트를 추가하지 않았다. 재현 프로그램의 Pool 클래스는 byte[1024] Holder를 생성하고 지정한 한 훅에서만 AssertionError/NPE를 발생시켰다.

```text
predicateError after close: total=1, quarantined=1, revokedSubs=1, destroys=0
recycleError after close:   total=1, quarantined=1, revokedSubs=1, destroys=0
destroyError after repeated close: total=1, quarantined=1, destroys=1
retained lease: total=0, payloadBytes=1024, ownerRetained=true
retained sub stack after main recycle/close=1
recycleNpe: total=0, failureCount=1, destroys=1
null value=NullPointerException
null callback=NullPointerException, borrowed=1
```

재현 순서는 M-01~M-04와 입력 표에 명시하였다. 리플렉션 결과는 강한 참조가 남는다는 구조적 증거이며, GC 횟수나 heap 증가량을 측정한 결과가 아니다. 힙 덤프, 장시간 soak test, 서비스 실트래픽, 외부 자원 계측은 수행하지 않았다. 기존 44개 테스트의 성공만으로 이번 Error 경로나 장기 참조 보관 문제가 검증되었다고 볼 수 없다.

### 5. 권장 후속 순서 (이번 요청에서는 미구현)

1. M-01/M-02에 대한 Error 주입 회귀 테스트와 cleanup 예외 안전성 검토. 재정리 가능 상태, 후속 슬롯 처리, failureCount 의미를 함께 결정한다.
2. M-03~M-05를 대상으로 완료 POJO/이벤트/회수 Sub의 실제 보관 기간을 확인하고 참조 해제 정책을 적용한다. 필요하면 핸들·토큰의 간접 참조 설계를 검토한다.
3. 서비스별 Main.close, Sub.close, Lease.close 책임과 patrol/timeout/리스너 정책을 확정한다. T 내부 버퍼와 외부 자원 해제도 점검한다.
4. payload 유형별 반복 생성·폐기와 완료 핸들 보관 여부를 나눠 heap dump의 GC root 경로를 비교한다. 현재 total뿐 아니라 생성 대비 해제 성공 수, 장기 격리·생성 예약, 이벤트 큐 길이를 관찰한다.
5. 추가 시험: use(null) 반환 보장, onDestroy NPE와 외부 자원 실패, 보관된 이벤트의 참조 그래프, 자동 patrol Error, 유한하지 않은 훅의 서비스 종료 정책.

현재 소스에는 위 개선을 반영하지 않았다. 이 리포트는 구현 분석과 재현 결과를 기록한 문서이며 수정 완료 보고가 아니다.

## 2026-10-12 분석 결과 적용

### 적용 범위와 설계

사용자의 “분석내용 적용” 요청에 따라 위 리포트의 실제 결함과 참조 보존 경로를 수정했다.
기존 Main 단일 락, owner 전용 Sub 스택, 세대별 대여, 사용 중 정리 지연 계약을 유지한다.
업무별 판단이 필요한 timeout·메모리 상한·외부 I/O 취소 정책은 라이브러리가 임의로 결정하지 않는다.
작업은 실패 회귀 테스트 작성 → 수정 전 실패 확인 → 소스 수정 → 전체 검증 → 독립 리뷰 순서로 진행했다.

| 리포트 항목 | 적용 내용 | 남는 책임/제약 |
| --- | --- | --- |
| M-01 판정·재활용 Error | RuntimeException뿐 아니라 Error도 진단하고 해당 슬롯 폐기 후 Error 전파 | 치명적인 VM 장애에서 완료 보장은 불가 |
| M-02 일괄 폐기 중단 | 슬롯별 Error를 집계하면서 이미 획득한 나머지 정리를 수행. 후속 Error는 suppressed로 보존 | 실제 onDestroy 자원 해제 성공은 확장 훅 책임 |
| M-03 반환 Lease 참조 | Lease는 slotId/ownerId/generation만 저장. 반환 접수 또는 강제 회수 시 Main 강한 참조 해제 | 활성 대여는 의도적으로 Main 수명을 유지. use 밖 payload 보관 금지 |
| M-04 Sub stack 참조 | Token은 slotId/generation만 저장. RELEASED Sub는 Main 강한 참조 해제·통계 snapshot 보관. owner는 threadId로 식별 | 타 스레드는 여전히 owner stack을 수정하지 않음. 사용자 Sub 확장 필드는 확장 구현 책임 |
| M-05 이벤트 참조 | 종료 동작은 Main WeakReference와 target/id/generation만 캡처, 호출 시 registry 재검증 | 외부 이력에 이벤트 자체를 무한 저장하면 이벤트·stack trace 메모리는 계속 증가. 큐 용량·TTL 필요 |
| M-06 감시·점유 정책 | 기존 기본값과 명시적 반환 계약 유지, 아래 운영 지침 보강 | 실제 업무 시간과 payload 크기를 기준으로 설정 필요 |
| M-07 훅·자동 감시 | 리스너와 자동 patrol은 일반 Error도 진단하여 후속 감시 유지. VirtualMachineError/ThreadDeath는 전파 | 끝나지 않는 use/훅 강제 중단은 하지 않음. scheduler가 있는 Main은 명시적 close 필요 |
| M-08 외부 자원 해제 실패 | 기존 로그·failureCount·registry 제거 정책 유지, Error까지 적용 | 파괴 실패 자동 재시도 없음. destroyedCount는 해제 성공 수가 아님 |
| NPE | null 입력의 기존 예외 계약 유지. use(null) 후 반환 가능성과 onRecycle NPE 후 폐기 회귀 테스트 추가 | T 내부 null과 사용자 훅의 오류는 호출자가 관리 |

공개 메서드·생성자·record 구성요소는 변경하지 않았다. 내부 `Token<T>(Slot, generation)`은
`Token(slotId, generation)`으로 변경했고, Lease의 Slot/SubState 직접 참조를 제거했다.
반환·회수 시 Holder의 borrowStack도 비워 대여 진단 메타데이터 보관 기간을 줄였다.
새 운영 의존성과 설정 필드는 추가하지 않았다.

### 오류 처리와 참조 수명 계약

- `isRecycleable`/`onRecycle` Error는 해당 Holder를 폐기하고 호출자에게 전파한다.
  `onDestroy` Error도 registry 제거를 완료한 뒤 전파한다. 일괄 정리에서는 나머지 슬롯도 처리한다.
- 복수 Error는 최초 Error에 suppressed로 추가한다. 단, 뒤에 발생한 VirtualMachineError 또는
  ThreadDeath가 일반 Error에 가려지지 않도록 치명적 오류를 우선 전파한다.
- use, start, 할당 rollback, Sub.onBorrow 실패 중 추가 정리 오류가 발생하면 최초 실패를 유지하고
  정리 오류를 suppressed로 기록한다. 정리 중 치명적 오류가 발생하면 이를 우선 전파한다.
- use 콜백의 모든 Throwable에 대해 실패 객체를 폐기한다. 예외를 우회 전파하는 checked exception도
  정상 사용 완료로 취급하지 않도록 회귀 테스트로 보호했다.
- 리스너의 일반 Error는 RuntimeException과 마찬가지로 진단한다. 관찰용 리스너가 실패했다고 해서
  강제 회수 정책으로 바뀌지는 않는다. 자동 patrol의 일반 Error는 이후 주기의 감시를 중단시키지 않는다.
- Error 처리는 이미 시작된 수명 전이를 가능한 범위에서 마무리하기 위한 방어다. 실제 메모리 고갈·VM 손상·
  스레드 강제 종료에서도 자원을 반드시 정리한다는 보장은 아니다.
- 반환된 Lease의 id/generation은 유지되고 recycle은 false, use/touch는 IllegalStateException이다.
  Main이 GC 대상이 되어도 같은 동작을 유지한다. use(null)은 기존대로 NullPointerException이다.
- RELEASED Sub는 Main이 더 이상 존재하지 않아도 owner의 stats()/close()가 안전하다.
  stats는 최종 snapshot을 반환하며, 새 대여·touch·patrol은 거절한다.
- 보관된 LeakEvent는 같은 대여에 대한 지연 forceClose를 지원하지만, 이전 세대 이벤트가 새 대여를 회수하지 못한다.
  Main이 더 이상 도달 가능하지 않거나 대상이 반환·회수된 경우 false를 반환한다.
- Sub RELEASED 전에는 진행 중 use/생성/정리를 관리해야 하므로 Main 강한 참조를 유지한다.
  콜백 종료 전에 payload를 버리거나 외부 자원을 닫는 방식으로 누수를 숨기지 않는다.

### 운영 적용 지침

1. 서비스 종료 시 Main.close, 요청 종료 시 owner의 Sub.close, 작업 종료 시 Lease.close를 보장한다.
   try-with-resources 또는 finally를 사용하고 worker가 완료된 뒤 Sub를 반환한다.
2. 자동 회수가 필요하면 automaticPatrol=true와 해당 Holder/Sub 제한을 함께 설정한다.
   기본 automaticPatrol=false·제한 null은 변경하지 않았다. 자동 감시만 켜고 제한을 비워 두면 만료 회수는 없다.
3. poolIdleLimit은 Main의 IDLE에만 적용된다. 장기 Sub 점유는 Sub 반환 또는 Sub 제한으로 관리한다.
4. use/생성/정리/리스너는 유한 시간에 끝나야 한다. 외부 I/O에는 timeout·협력적 취소를 적용한다.
   주입 clock은 단조 증가, thread-safe, non-blocking, non-throwing 계약을 따라야 한다.
5. failureCount와 장기 creating/recycling/quarantined/destroying/revokedSubs를 관측한다.
   외부 자원 해제 성공·실패는 실제 onDestroy 구현에서 별도로 계측한다.
6. 큰 payload 내부 버퍼는 isRecycleable에서 크기에 따라 폐기하거나 onRecycle에서 줄인다.
   풀 max는 객체 수 제한이며, 개별 객체의 바이트 크기나 외부 이벤트 큐의 크기 제한이 아니다.
7. 사용자 POJO/Sub 하위 클래스/리스너가 직접 저장한 payload·Main 참조는 라이브러리의 참조 해제와 별개다.
   완료 POJO 필드와 이벤트 큐 소비 정책을 함께 관리한다.

### 검증 기록

- 추가 테스트 클래스: `ContainerSafetyTest`, 14개 테스트. 기존 44개와 합계 58개.
- 첫 단계에서 기존 소스의 Error 정리 누락·참조 잔존·자동 patrol 중단 7건의 실패를 확인하고 수정했다.
- 복합 실패의 최초 예외 보존 3건과 치명적 오류 우선 전파 1건도 수정 전 실패를 확인했다.
- 리뷰 중 발견한 checked callback 예외 처리 회귀는 expected total=0 / actual=1을 재현한 뒤 수정했다.
- NPE 계약 및 Main 약한 참조 해제 후 fallback은 별도 회귀 테스트로 검증했다.
- 강한 참조 검사는 반환 객체의 실제 객체 그래프를 순회하면서 WeakReference를 제외한다.
  GC 타이밍에 의존하지 않으며, fallback 테스트는 약한 참조를 명시적으로 clear하여 해당 분기를 검증한다.
  실제 heap dump·장기 soak·성능 벤치마크를 수행한 것은 아니다.
- 기존 latch/barrier 기반 동시성 15개를 포함하여 worker 반환, Main/Sub 종료, 생성 rollback,
  지연 use/정리, 오래된 이벤트의 동일 슬롯 다음 세대 보호가 계속 통과했다.
- Java 21.0.3 / Maven 3.9.11에서 전체 `mvn -f object-recycle-container/pom.xml test`:
  **58개, 실패 0, 오류 0, 건너뜀 0, BUILD SUCCESS**.
- 독립 리뷰의 치명적 오류 집계 및 checked callback 회귀 지적을 반영했다.
  예외 주입 테스트의 WARNING은 의도된 진단이다.
- 최종 `mvn -f object-recycle-container/pom.xml clean verify`도 **BUILD SUCCESS**:
  58개 테스트, 실패 0, 오류 0, 건너뜀 0, JAR 재생성 완료. `git diff --check` 통과.
  독립 리뷰의 재확인에서 미해결 지적 없음. 커밋·push는 수행하지 않았다.
