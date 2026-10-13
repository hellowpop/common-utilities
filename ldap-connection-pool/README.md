# LDAP Connection Pool 사용법

Java 21 기반의 LDAP 연결 풀입니다. `Properties`로 LDAP 접속 정보와 풀 크기를 설정하고,
`LdapContext` 프록시를 대여해 사용합니다. 실행 시 외부 라이브러리가 필요하지 않습니다.

공개 클래스: `com.inzent.apim.utilities.ldap.LdapConnectionPool`

## 1. 생성 → 사용 → 종료 라이프사이클

```text
new LdapConnectionPool(properties)     설정 복사 및 검증. 아직 접속하지 않음
                 ↓
open()                                초기 접속 및 base DN 읽기 테스트
                 ↓
runWith(consumer) / borrow()           필요한 작업마다 연결 대여
                 ↓
자동 반납 / context.close()           재사용 가능한 연결은 idle 상태로 복귀
                 ↓
다음 작업에서 다시 대여                반납된 프록시 대신 새 프록시 사용
                 ↓
dispose() / pool.close()               모든 연결 종료. 풀은 다시 열 수 없음
```

풀은 애플리케이션 또는 서비스 시작 시 생성해 여러 요청에서 공유하고, 종료 시 한 번 정리합니다.
각 LDAP 작업에서는 연결을 대여하고 반납합니다.

| 단계 | 호출 | 동작 |
| --- | --- | --- |
| 생성 | 생성자 | 설정을 검증하고 복사합니다. 네트워크 접속은 수행하지 않습니다. |
| 초기화 | `open()` | `max(1, min)`개 연결을 생성하고 설정된 base DN의 읽기 권한까지 확인합니다. 성공 후 반복 호출은 무동작입니다. |
| 사용 | `runWith(consumer)` | Consumer 실행 후 자동으로 반납합니다. Consumer가 예외를 던져도 반납합니다. |
| 직접 사용 | `borrow()` | 새 대여 프록시를 반환합니다. 호출측이 `finally`에서 `context.close()`로 반납합니다. |
| 반납 | 대여한 `context.close()` | 정상 연결은 재사용하고, 상태가 변경되었거나 실패한 연결은 실제로 닫습니다. 반복 close는 무동작입니다. |
| 종료 | `dispose()` 또는 풀의 `close()` | idle 및 대여 중 연결과 파생 자원을 모두 닫고 영구 종료합니다. 반복 종료는 무동작입니다. |

`open()`을 생략하면 최초 `borrow()` 또는 `runWith()`에서 자동 초기화합니다.
초기화가 실패하면 생성한 연결을 정리하고 예외를 전달하며, 같은 풀에서 초기화를 재시도할 수 있습니다.
`min=0`이어도 최초 연결 테스트를 위해 한 개는 생성합니다.

## 2. 생성 파라미터

### 생성자

- `LdapConnectionPool(Properties properties)`: Properties의 풀 설정을 읽고, 미지정 항목은 기본값을 사용합니다.
- `LdapConnectionPool(Properties properties, int max, int min, long idleTimeoutMillis)`: 명시한 풀 설정을 사용합니다. Properties에 있는 풀 설정보다 생성자 인자가 우선합니다.

예를 들어 `new LdapConnectionPool(properties, 16, 2, 120_000L)`은 최대 16개, 최소 2개,
idle timeout 2분으로 생성합니다. `new LdapConnectionPool(properties, 8, 0, 0L)`은 최소 보존 없이
idle 만료를 비활성화합니다.

### 풀 설정

| 생성자 인자 | Properties 키 / 상수 | 기본값 | 의미 및 조건 |
| --- | --- | --- | --- |
| `properties` | LDAP 및 풀 설정 전체 | 없음 | null 불가. LDAP 설정은 JNDI에 전달합니다. |
| `max` | `ldap.pool.max` / `MAX_PROPERTY` | `8` | idle + 대여 중인 루트 Context 최대 개수. `1` 이상. |
| `min` | `ldap.pool.min` / `MIN_PROPERTY` | `1` | 초기화·idle 정리 시 유지할 최소 개수. `0 <= min <= max`. |
| `idleTimeoutMillis` | `ldap.pool.idleTimeoutMillis` / `IDLE_TIMEOUT_PROPERTY` | `300000` | 반납 후 idle 시간(ms). `0`이면 만료 비활성화. 음수 불가. |

풀 설정 값은 Properties에 문자열로 지정합니다. 일부 키만 지정하면 나머지는 기본값을 사용합니다.
예를 들어 max만 `16`으로 지정하면 min은 `1`, idle timeout은 `300000ms`입니다.
timeout의 상한은 내부 나노초 변환을 위해 `Long.MAX_VALUE / 1_000_000`ms입니다.

`min`은 대여 중인 연결을 포함한 전체 개수 기준입니다. idle 연결을 최소 min개 보장하는 설정은 아닙니다.
오류나 상태 변경으로 연결을 폐기하면 일시적으로 min보다 적을 수 있으며, 다음 대여 시 보충합니다.
idle 정리는 최대 1초 간격의 daemon 작업과 대여 시점에 수행하므로 지정 시간과 정확히 동시에 닫히지는 않습니다.

### LDAP 접속 설정 샘플

아래 값은 로컬 테스트용 예시입니다. 실제 LDAP 서버의 주소, 인증 DN, 비밀번호 및 데이터 구조로 변경합니다.

| Properties 키 | 샘플 값 | 설명 |
| --- | --- | --- |
| `java.naming.factory.initial` | `com.sun.jndi.ldap.LdapCtxFactory` | JDK LDAP 제공자 클래스. `Context.INITIAL_CONTEXT_FACTORY`. |
| `java.naming.provider.url` | `ldap://localhost:389/dc=example,dc=com` | 서버 주소와 base DN. `Context.PROVIDER_URL`. |
| `java.naming.security.authentication` | `simple` | 인증 방식. `Context.SECURITY_AUTHENTICATION`. |
| `java.naming.security.principal` | `cn=admin,dc=example,dc=com` | 인증 계정의 전체 DN. `Context.SECURITY_PRINCIPAL`. |
| `java.naming.security.credentials` | `sample-password` | 예시 비밀번호. `Context.SECURITY_CREDENTIALS`. |
| `com.sun.jndi.ldap.connect.timeout` | `5000` | JDK 제공자의 연결 timeout(ms). |
| `com.sun.jndi.ldap.read.timeout` | `5000` | JDK 제공자의 응답 읽기 timeout(ms). |
| `ldap.pool.max` | `16` | 최대 루트 Context 16개. |
| `ldap.pool.min` | `2` | 최소 유지 2개. |
| `ldap.pool.idleTimeoutMillis` | `120000` | 반납 후 2분 동안 idle이면 정리 대상. |

`connect.timeout`과 `read.timeout`은 LDAP 네트워크 작업의 timeout이며, 풀의 idle timeout과 별개입니다.
풀에서 자체 설정하는 네트워크 timeout 기본값은 없습니다. 특히 종료가 네트워크 호출을 기다릴 수 있으므로
응답 시간 제한이 필요하면 접속 Properties에 지정합니다.

### 예제가 사용하는 LDAP 데이터

| 항목 | 샘플 데이터 |
| --- | --- |
| Base DN | `dc=example,dc=com` |
| 사용자 컨테이너 DN | `ou=people,dc=example,dc=com` |
| 사용자 전체 DN | `uid=alice,ou=people,dc=example,dc=com` |
| `uid` | `alice` |
| `cn` | `Alice Kim` |
| `mail` | `alice@example.com` |

해당 엔트리는 LDAP 서버에 미리 존재해야 합니다. 아래 예제는 데이터를 생성하지 않습니다.
base DN이 접속 URL에 포함되어 있으므로 조회 이름은 `uid=alice,ou=people`,
검색 기준은 `ou=people`처럼 base DN에 상대적인 이름을 사용합니다.

## 3. 생성·사용·종료 전체 예제

다음 코드는 설정 생성, 초기 연결 테스트, 자동 반납 방식, 직접 대여 방식, 전체 종료를 포함합니다.

```java
package com.inzent.apim.utilities.ldap.examples;

import com.inzent.apim.utilities.ldap.LdapConnectionPool;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapContext;

public final class LdapPoolExample {
    public static void main(String[] args) throws NamingException {
        Properties properties = createProperties();

        // 1. 풀 생성. 아직 연결하지 않습니다.
        // 풀은 AutoCloseable이며 블록 종료 시 close() → dispose()가 호출됩니다.
        try (LdapConnectionPool pool = new LdapConnectionPool(properties)) {
            // 2. 초기 연결 및 base DN 읽기 테스트.
            pool.open();

            // 3-A. Consumer 실행 후 자동 반납. 직접 context.close()할 필요가 없습니다.
            pool.runWith(context -> {
                try {
                    Attributes attributes = context.getAttributes("uid=alice,ou=people");
                    System.out.println(attributes);
                } catch (NamingException failure) {
                    // Consumer는 checked exception을 선언할 수 없으므로 변환합니다.
                    throw new IllegalStateException("LDAP 사용자 조회 실패", failure);
                }
            });

            // 3-B. 직접 대여. 사용이 끝나면 finally에서 반드시 반납합니다.
            LdapContext context = pool.borrow();
            try {
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
                controls.setReturningAttributes(new String[]{"uid", "cn", "mail"});

                NamingEnumeration<SearchResult> results = context.search(
                        "ou=people", "(uid={0})", new Object[]{"alice"}, controls);
                try {
                    while (results.hasMore()) {
                        SearchResult result = results.next();
                        System.out.println(result.getName() + ": " + result.getAttributes());
                    }
                } finally {
                    // 검색을 중단하거나 예외가 발생한 경우에도 결과 자원을 정리합니다.
                    results.close();
                }
            } finally {
                context.close(); // 대여 연결 반납. 풀 종료와는 별개입니다.
            }
        } // 4. 풀 종료. 남은 idle/대여 중 연결까지 모두 닫습니다.
    }

    private static Properties createProperties() {
        Properties properties = new Properties();
        properties.setProperty(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        properties.setProperty(Context.PROVIDER_URL, "ldap://localhost:389/dc=example,dc=com");
        properties.setProperty(Context.SECURITY_AUTHENTICATION, "simple");
        properties.setProperty(Context.SECURITY_PRINCIPAL, "cn=admin,dc=example,dc=com");
        properties.setProperty(Context.SECURITY_CREDENTIALS, "sample-password");
        properties.setProperty("com.sun.jndi.ldap.connect.timeout", "5000");
        properties.setProperty("com.sun.jndi.ldap.read.timeout", "5000");
        properties.setProperty(LdapConnectionPool.MAX_PROPERTY, "16");
        properties.setProperty(LdapConnectionPool.MIN_PROPERTY, "2");
        properties.setProperty(LdapConnectionPool.IDLE_TIMEOUT_PROPERTY, "120000");
        return properties;
    }
}
```

서비스에서 풀을 장기간 공유할 때는 위 try 블록에 해당하는 기간을 서비스 수명으로 잡습니다.
시작 시 생성·open하고, 요청 처리에서 runWith/borrow를 사용하며, 종료 처리에서 `pool.dispose()`를 호출합니다.
풀 자체의 `close()`도 동일하게 종료합니다.

## 4. 예외와 종료 계약

| 상황 | 결과 |
| --- | --- |
| 잘못된 max/min/timeout 또는 숫자 형식 | 생성자에서 `IllegalArgumentException`. |
| null Properties 또는 null Consumer | `NullPointerException`. |
| 접속·인증·base DN 읽기 실패 | `open()` 또는 대여 시 `NamingException`. |
| max만큼 모두 대여 중 | `borrow()`에서 대기 없이 `NamingException`. 대기열/대여 대기 timeout은 제공하지 않음. |
| 반납된 Context 사용 | LDAP 호출은 `NamingException`. 반복 close는 무동작. |
| 종료된 풀 재사용 | `open()`/`borrow()`/정상 Consumer의 `runWith()`에서 `IllegalStateException`. |
| Consumer 실행 실패 | 자동 반납 후 원래 예외를 전달. 반납도 실패하면 원래 예외의 suppressed에 추가. |
| 여러 자원의 종료 실패 | 나머지 정리도 시도한 후 첫 오류를 전달하고 후속 오류는 suppressed에 추가. |

풀의 try-with-resources를 사용하면 작업 예외와 풀 종료 예외가 함께 발생했을 때 Java가 종료 예외를
작업 예외의 suppressed로 보존합니다. 직접 대여한 Context와 검색 결과는 AutoCloseable이 아니므로
예제처럼 `finally`로 정리합니다. 여러 직접 close가 실패하면 단순 finally는 앞선 예외를 덮을 수 있으므로,
원인 보존이 필요한 호출측에서는 suppressed를 처리하거나 `runWith()`를 사용합니다.

## 5. 주의사항

1. **대여 연결 반납과 풀 종료를 구분합니다.** `context.close()`는 한 대여의 반납,
   `pool.dispose()`/`pool.close()`는 풀 전체의 영구 종료입니다. 종료한 풀은 새로 생성해야 합니다.
2. **대여 프록시를 재사용하지 않습니다.** 반납 후 새 작업은 다시 borrow/runWith로 시작합니다.
   runWith의 Context를 외부에 보관해 사용하지 않습니다.
3. **파생 자원은 대여 수명 안에서 사용합니다.** newInstance/lookup/schema의 Context,
   검색 결과 열거자, iterator, TLS 응답은 루트 반납 시 함께 정리됩니다.
   일반 Attribute 값은 스냅샷이라 반납 후에도 읽을 수 있지만, 스키마 조회는 반납 후 사용할 수 없습니다.
   사용자 정의 속성 값과 byte[]는 깊은 복사하지 않습니다.
4. **풀은 공유할 수 있습니다.** 서로 다른 대여 Context의 작업은 병렬 실행되며,
   동일 대여 Context와 파생 자원의 호출은 직렬화합니다. 한 작업에서 설정한 controls를 다른 작업과
   공유하는 등 여러 호출에 걸친 작업 순서까지 자동으로 보장하지는 않습니다.
5. **연결 상태 변경은 재사용 비용에 영향을 줍니다.** 환경 변경, request controls 변경, reconnect,
   extended operation, 파생 Context 생성 또는 제공자 예외 발생 연결은 반납 시 폐기합니다.
   따라서 작업마다 controls를 설정하면 연결 생성이 잦아질 수 있습니다.
6. **idle timeout은 사용 중인 연결을 강제로 닫지 않습니다.** close 누락을 자동 반납으로 처리하지 않으며,
   대여 누락이 쌓이면 max에 도달합니다. dispose 누락 시 daemon 작업이 풀을 계속 참조할 수 있습니다.
7. **종료 전에 작업을 마칩니다.** 새 요청을 중지하고 대여 작업을 마친 뒤 풀을 종료합니다.
   dispose는 진행 중인 LDAP 호출이 끝나기를 기다리고 대여 중 연결도 닫습니다.
   네트워크 작업을 무기한 기다리지 않도록 필요에 맞게 read/connect timeout을 지정합니다.
8. **설정 복사의 범위를 확인합니다.** Properties의 키와 defaults를 복사하므로 생성 이후 원본 키 변경은
   풀에 반영되지 않습니다. 객체형 가변 값은 얕게 복사하므로 외부에서 변경하지 않습니다.
9. **실제 연결 수는 제공자에 따라 다릅니다.** max는 루트 Context 개수이며, 파생 Context나 referral로
   생기는 서버 TCP 연결의 수까지 제한하지 않습니다. 하위 JNDI 자체 풀은 중복 풀링을 막기 위해
   `com.sun.jndi.ldap.connect.pool=false`로 강제 설정합니다.
10. **샘플 설정은 실제 환경으로 교체합니다.** 비밀번호는 소스에 고정하지 않고 외부 설정에서 공급합니다.
    예제의 simple 인증은 TLS 없는 ldap:// 주소를 사용하므로 테스트 환경용입니다.
    운영 접속은 서버에 맞는 LDAPS 또는 StartTLS와 인증서 검증을 구성합니다.
    StartTLS는 extended operation이므로 해당 연결은 반납 시 폐기됩니다.
11. **서버 권한과 정리 오류를 처리합니다.** open은 인증뿐 아니라 base DN 읽기 권한도 요구합니다.
    제공자의 실제 close가 실패하면 풀은 참조를 해제하고 재사용을 막지만 서버 연결 해제 완료는 보장할 수 없습니다.

## 6. 빌드 및 프로젝트 문서

프로젝트 루트에서:

```shell
mvn -f ldap-connection-pool/pom.xml clean verify
```

모듈 디렉터리에서:

```shell
mvn clean verify
```

결과 JAR: `target/ldap-connection-pool-1.0.0-SNAPSHOT.jar`.
Java 21이 필요하며, 테스트는 대체 Context와 JNDI InitialContextFactory를 사용합니다.
예제의 실제 인증·검색 실행에는 별도의 LDAP 서버와 위 샘플 엔트리가 필요합니다.

구조 변경 및 메모리/NPE 분석은 [프로젝트 기술문서](../project.md)에 기록되어 있습니다.
