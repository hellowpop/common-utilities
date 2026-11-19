# JMH 동작 원리와 벤치마크 케이스 작성·결과 도출 가이드

작성일: 2026-11-19. Java 17과 이 저장소 `pojo-codec`의 **JMH 1.36** 구성을 기준으로 한다. 명령은 저장소 루트의 Windows PowerShell에서 실행한다. 설명용 코드는 문서 예제이며, 이 문서 작성 과정에서 새 벤치마크나 성능 측정 결과를 생성하지 않았다.

## 1. JMH가 필요한 이유

JMH(Java Microbenchmark Harness)는 JVM에서 실행하는 코드의 성능을 측정하는 OpenJDK 도구다. `@Benchmark`로 표시한 측정 대상에 대해 반복 호출, 실행 시간 관리, 결과 소비 등 주변 실행 코드를 생성한다. 개발자는 어떤 작업을 한 번의 연산으로 볼지 정의한다. [공식 소개](https://github.com/openjdk/jmh), [기본 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_01_HelloWorld.java).

다음과 같은 수동 측정은 JVM의 실행 특성 때문에 쉽게 왜곡된다.

- 첫 호출에는 클래스 로딩·초기화·캐시 생성이 섞인다.
- 반복 중 JIT 컴파일과 인라이닝이 진행되어 실행 속도가 변한다.
- 사용하지 않는 계산은 제거되고 상수 입력은 미리 계산될 수 있다.
- 너무 짧은 작업은 타이머 호출과 측정 루프의 비용에 영향을 받는다.

JMH는 이런 영향을 제어할 수 있는 구조를 제공하지만, 잘못 정의한 작업이나 불공정한 비교 조건을 자동으로 수정하지는 않는다. 메서드 하나의 결과를 서비스 전체의 처리량이나 p99 응답시간으로 확대 해석하지 않는다.

## 2. 소스에서 측정 결과까지의 동작 원리

```text
@Benchmark 메서드와 @State/@Param 작성
  → Maven test-compile
  → annotation processor가 실행 코드와 벤치마크 목록 생성
  → JMH Runner가 대상·파라미터·스레드·모드 결정
  → 별도 JVM fork 시작
      → 상태 생성, 파라미터 주입, Trial Setup
      → Warmup iteration 반복: 실행하되 최종 점수에서 제외
      → Measurement iteration 반복: 연산 수와 경과시간 등 수집
      → Trial TearDown, fork 종료
  → 다음 fork에서 위 과정 반복
  → 측정값 집계, 콘솔 출력, JSON 저장
```

위 흐름은 일반적인 fork 기반 시간 측정 구성이다. Setup/TearDown은 지정한 수명주기와 State 범위에 맞춰 실행된다.

### 2.1 컴파일 시점: 측정용 코드 생성

`jmh-core`는 애너테이션·실행기·결과 모델을 제공하고, `jmh-generator-annprocess`는 컴파일 중 애너테이션을 처리한다. 프로세서는 대상 메서드 호출을 포함하는 `*_jmhTest` 등의 Java 코드와 `META-INF/BenchmarkList`, `META-INF/CompilerHints`를 생성한다. 런타임에 리플렉션으로 모든 클래스를 탐색하고 매번 호출하는 방식으로 이해하면 안 된다. [코드 생성 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java).

현재 프로젝트에서는 생성 Java 파일이 보통 `pojo-codec/target/generated-test-sources/test-annotations/`에, 컴파일된 클래스와 메타데이터는 `pojo-codec/target/test-classes/`에 만들어진다. IDE나 컴파일러 설정에 따라 생성 소스 경로는 달라질 수 있다. `@Benchmark`만 추가하고 프로세서를 실행하지 않으면 실행 목록이 만들어지지 않는다.

### 2.2 Fork: JVM 프로세스를 분리하는 이유

`@Fork(2)`는 동일한 케이스를 별도 JVM 두 개에서 각각 실행한다는 의미다. 각 JVM은 독립적으로 워밍업과 측정을 수행한다. 프로세스마다 JIT 프로파일과 힙 상태를 다시 만들므로 같은 JVM에서 앞서 실행한 다른 코드의 영향을 줄이고 실행 간 변동을 관찰할 수 있다. `-f 0`은 이 격리를 제거하므로 디버깅과 정식 비교를 구분한다. OS의 CPU 경쟁·전력 관리·온도 변화까지 격리되는 것은 아니다. [Fork 공식 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java).

### 2.3 Warmup과 Measurement

`@Warmup(iterations = 5, time = 1)`은 워밍업 구간을 1초씩 5회 실행하고, `@Measurement(iterations = 5, time = 1)`은 측정 구간을 1초씩 5회 실행한다는 뜻이다. 기본 시간 단위는 초이며 명시적으로 지정할 수도 있다. **iteration 1회는 메서드 호출 1회가 아니다.** AverageTime/Throughput에서는 해당 구간 동안 메서드를 반복 호출한다.

워밍업은 JIT·클래스 로딩·캐시가 정상 실행 상태에 가까워지도록 하는 과정이다. 5회라는 숫자가 안정화를 보장하지 않으므로 iteration별 추이를 확인한다. 측정 중에도 GC·JIT·스케줄링은 일어날 수 있다. 실제 사용에서 발생하는 GC 비용을 무조건 제거할 잡음으로 취급하지 않는다.

### 2.4 시간 측정 방식

AverageTime의 일반적인 반복 루프는 여러 번 호출한 동안의 경과시간과 연산 수를 이용해 평균 시간을 구한다. 모든 호출 앞뒤에서 타이머를 읽는 방식보다 호출당 타이머 영향을 줄일 수 있다. 생성 코드에는 루프 종료 확인과 결과 소비 등의 비용도 존재한다. SampleTime은 일부 호출 구간을 표본 측정하므로 동작이 다르다. [모드 설명](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_02_BenchmarkModes.java), [생성 루프 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java).

`us/op`는 연산당 **경과시간**이다. 스레드가 실제 CPU를 사용한 시간만 측정한 값이 아니며, 실행 구간에 발생한 대기·스케줄링·GC 등의 영향도 받을 수 있다.

## 3. 벤치마크 케이스 설계

### 3.1 먼저 한 번의 작업을 정의한다

예를 들어 직렬화 비교에서는 다음 경계를 먼저 결정한다.

| 설계 항목 | 결정할 내용 |
| --- | --- |
| 한 번의 연산 | 객체 하나를 메모리 스트림에 직렬화 |
| 입력 | 평면 객체, 중첩 객체, 숫자 배열 객체 |
| 포함 비용 | 스트림 reset, 실제 직렬화, 결과 크기 조회 |
| 제외 비용 | 최초 codec 생성, 입력 생성, 정합성 검증 |
| 자원 재사용 | codec과 출력 버퍼 재사용 여부를 양쪽에 동일하게 적용 |
| 비교 기준 | 같은 논리 데이터가 복원되는지 사전 확인 |
| 동시성 | 단일 스레드 기본 비교 후 실제 공유 조건 별도 측정 |

실제 서비스가 매번 codec이나 버퍼를 생성한다면 그 비용을 포함하는 별도 케이스가 필요하다. 정상 상태와 최초 호출 성능을 한 결과에 섞지 않는다. 정합성은 단위 테스트와 준비 단계에서 검증하고, 측정 메서드 안의 로깅·검증은 측정 대상인 경우에만 포함한다.

### 3.2 주요 애너테이션

| 애너테이션 | 역할과 사용 예 |
| --- | --- |
| `@Benchmark` | 측정할 메서드. 일반적인 예제는 public, 인수 없음 또는 JMH가 주입하는 State/Blackhole 인수 사용 |
| `@State(Scope.Thread)` | worker 스레드별 상태 인스턴스. 공유되지 않는 버퍼 등에 사용 |
| `@State(Scope.Benchmark)` | 같은 trial의 worker가 공유하는 상태. 공유 객체의 경쟁 측정에 사용 |
| `@State(Scope.Group)` | `@Group`으로 구성한 같은 worker 그룹 안에서 상태 공유 |
| `@Param({"64", "1024"})` | 실행 전 State 필드에 입력값 주입. 여러 필드는 기본적으로 값의 모든 조합을 실행 |
| `@Setup(Level.Trial)` | trial 단위 준비. 현재와 같은 fork 구성에서 해당 상태가 사용되기 전 초기화 |
| `@Setup(Level.Iteration)` | 워밍업·측정의 각 iteration 시작 전 준비 |
| `@Setup(Level.Invocation)` | 호출마다 준비. 초단기 작업에서는 타이밍·캐시 교란이 커질 수 있어 신중히 사용 |
| `@TearDown` | 지정한 Level에 맞춰 자원 정리 |
| `@BenchmarkMode` / `@OutputTimeUnit` | 측정 모드와 결과 시간 단위 지정 |
| `@Warmup` / `@Measurement` | 워밍업·측정 구간 설정 |
| `@Fork` / `@Threads` | fork 수·JVM 옵션과 worker 스레드 수 지정 |
| `@OperationsPerInvocation(N)` | 메서드 한 번이 N개 논리 연산을 수행할 때 결과 정규화 |

`@Setup`과 `@TearDown`의 기본 Level은 Trial이다. Scope는 상태 공유 범위이며 자동으로 스레드 안전성을 제공하지 않는다. 준비 코드를 측정 메서드 밖으로 옮겨도 그로 인해 만들어진 캐시·힙·GC 상태의 영향은 남는다. [State 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_03_States.java), [수명주기 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_05_StateFixtures.java), [Param 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_27_Params.java).

### 3.3 새 케이스 작성 예제

아래는 같은 입력 배열을 일반 루프와 Stream으로 합산하는 설명용 예제다. 실제 추가 위치는 `pojo-codec/src/jmh/java/com/inzent/apim/utilities/benchmark/ArraySumBenchmark.java`이며, 현재 benchmark profile에서 컴파일할 수 있는 형태다. 이 파일을 이번 문서 작업에서 생성하지는 않는다.

```java
package com.inzent.apim.utilities.benchmark;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ArraySumBenchmark {
    @Param({"64", "1024"})
    public int size;

    private int[] values;

    @Setup(Level.Trial)
    public void setup() {
        values = new int[size];
        for (int index = 0; index < size; index++) {
            values[index] = (index * 31 + 7) % 1000;
        }
    }

    @Benchmark
    public long loopSum() {
        long sum = 0L;
        for (int value : values) {
            sum += value;
        }
        return sum;
    }

    @Benchmark
    public long streamSum() {
        return Arrays.stream(values).asLongStream().sum();
    }
}
```

メ서드 2개 × size 2개 = 4개 케이스다. 배열 전체 합산이 1 op이므로 `ns/op`는 원소 하나의 시간이 아니다. 원소당 결과가 필요하면 입력 크기로 나눈 별도 파생 지표임을 명시한다. `@OperationsPerInvocation`에는 실행 중 변하는 `@Param` 값을 넣을 수 없다.

반환값은 JMH가 소비하므로 결과가 사용되지 않는 계산의 제거를 막는 데 도움이 된다. 여러 결과를 소비해야 한다면 `Blackhole` 인수를 받고 `blackhole.consume(value)`를 사용할 수 있다. 그러나 상수 입력의 계산까지 무조건 막아 주지는 않는다. 실제 작업과 맞는 State 입력을 사용하고, 반복 횟수를 늘리기 위한 인위적인 내부 루프를 추가하지 않는다. 위 배열 순회는 측정 대상 알고리즘 자체다. [결과 소비 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java), [상수 접기 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_10_ConstantFold.java).

## 4. 현재 프로젝트의 JMH 구성

### 4.1 Maven과 파일 구성

| 파일 | 역할 |
| --- | --- |
| [pojo-codec/pom.xml](../pojo-codec/pom.xml) | `benchmark` profile, JMH 1.36과 Jackson 2.20.1 test 의존성, JMH 소스 추가 |
| [CodecBenchmark.java](../pojo-codec/src/jmh/java/com/inzent/apim/utilities/pojocodec/benchmark/CodecBenchmark.java) | 케이스와 입력·준비·측정 설정 |
| [Run-Benchmarks.ps1](../pojo-codec/benchmarks/Run-Benchmarks.ps1) | 컴파일·classpath 구성·JMH 실행·판정 연결 |
| [Test-Performance.ps1](../pojo-codec/benchmarks/Test-Performance.ps1) | 저장된 JSON의 비교 조건과 성능 기준 검사 |
| [performance.md](../pojo-codec/performance.md) | 기존 측정 조건·결과·검증 기록 |

`build-helper-maven-plugin`이 `generate-test-sources` 단계에서 `src/jmh/java`를 테스트 소스에 추가한다. `test-compile`에서 JMH 프로세서가 실행 코드를 생성한다. 일반 운영 의존성에는 JMH가 포함되지 않으며, 현재 POM은 `java -jar target/benchmarks.jar` 방식의 전용 실행 JAR을 생성하지 않는다. `org.openjdk.jmh.Main`을 test classpath로 실행해야 한다.

### 4.2 현재 케이스와 측정 범위

`workload`는 `flat`, `nested`, `numeric`의 세 가지이고 측정 메서드는 다음 네 가지다.

| 메서드 | 1 op에 포함되는 작업 |
| --- | --- |
| `pojoSerialize` | 재사용 출력 버퍼 reset → POJO 직렬화 → 출력 크기 반환 |
| `jacksonSerialize` | 재사용 출력 버퍼 reset → Jackson 직렬화 → 출력 크기 반환 |
| `pojoDeserialize` | 입력 스트림 생성 → POJO 역직렬화 → 복원 객체 반환 |
| `jacksonDeserialize` | 입력 스트림 생성 → Jackson 역직렬화 → 복원 객체 반환 |

`@Setup`에서 입력 객체와 직렬화된 byte 배열을 만들고 양쪽의 복원 내용을 비교한다. 이 과정에서 codec 메타데이터와 버퍼가 미리 사용되므로 **초기화 이후의 정상 상태**를 비교한다. 직렬화 케이스는 결과를 새 byte 배열로 복사하는 비용을 포함하지 않으며, 역직렬화 케이스는 입력 스트림 생성 비용을 포함한다. 두 포맷의 wire byte 크기가 같다는 의미는 아니다.

설정은 AverageTime, MICROSECONDS, worker 1개, fork 2개, 워밍업 5회 × 1초, 측정 5회 × 1초다. 따라서 4 메서드 × 3 workload = 12개 결과, 일반적인 해당 구성에서 총 24개 측정용 JVM 실행, 케이스당 10개 측정 iteration 점수가 생긴다. 명목 구간 시간은 `12 × 2 × (5 + 5) × 1초 = 240초`이며 JVM 기동·준비·프로파일링·컴파일 등의 시간이 추가된다.

## 5. 빌드와 실행 과정

### 5.1 실행 환경 기록

```powershell
java -version
mvn -version
git rev-parse HEAD
git status --short
```

Maven과 JMH가 의도한 같은 JDK를 사용하는지 확인한다. 커밋 ID만으로 미커밋 변경을 재현할 수 없으므로 변경 내역 또는 소스 해시도 보존한다. CPU 모델·코어 수·OS·전원 모드·JVM 인수·동시 실행 부하를 기록하고, 비교 시 동일 조건을 유지한다.

### 5.2 컴파일과 classpath 구성

```powershell
mvn -f pojo-codec/pom.xml -Pbenchmark test-compile dependency:build-classpath '-Dmdep.outputFile=target/benchmark-classpath.txt' '-DincludeScope=test'
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
$dependencyClasspath = (Get-Content pojo-codec/target/benchmark-classpath.txt -Raw).Trim()
$benchmarkClasspath = "pojo-codec/target/classes;pojo-codec/target/test-classes;$dependencyClasspath"
java -cp $benchmarkClasspath org.openjdk.jmh.Main -l
java -cp $benchmarkClasspath org.openjdk.jmh.Main -h
```

`test-compile`은 테스트 코드를 컴파일하지만 Maven의 `test` 단계까지 진행하지 않는다. JMH 실행도 별도 명령에서 수행한다. `-DskipTests`는 일반 테스트 실행을 생략하면서 test compile은 유지하고, `-Dmaven.test.skip=true`는 test compile도 생략하므로 JMH 준비에 사용하지 않는다.

이 문서는 POM을 변경하지 않는다. 일반 `package`에서는 기존 설정대로 소스 JAR이 첨부되고 모듈 루트 `*.md`가 `META-INF/docs/pojo-codec/`에 포함된다. 이 가이드는 Maven 모듈 밖의 공통 문서다. 프로젝트 규칙에 따라 설치가 필요할 때는 `mvn -f pojo-codec/pom.xml -DskipTests install`을 사용한다. Maven의 install은 앞선 test 단계를 거치므로 테스트 생략 옵션을 명시해야 한다.

### 5.3 짧은 실행으로 발견·실행 가능 여부 확인

```powershell
java -cp $benchmarkClasspath org.openjdk.jmh.Main 'CodecBenchmark.pojoSerialize' -p workload=flat -t 1 -f 1 -wi 1 -i 1 -w 200ms -r 200ms -foe true
if ($LASTEXITCODE -ne 0) { throw 'JMH smoke run failed' }
```

이 실행은 한 케이스의 연결 확인용이다. 짧은 시간과 적은 표본으로 최적화 결론을 내리지 않는다. 3.3의 예제 파일을 실제 추가했다면 재컴파일 후 필터를 `ArraySumBenchmark`로 바꿔 실행할 수 있다.

### 5.4 정식 실행과 JSON 저장

기존 파일 덮어쓰기를 피하기 위해 실행마다 별도 디렉터리를 사용한다.

```powershell
$runDirectory = Join-Path 'pojo-codec/target/jmh-runs' ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$resultPath = Join-Path $runDirectory 'result.json'
$logPath = Join-Path $runDirectory 'console.log'
java -cp $benchmarkClasspath org.openjdk.jmh.Main CodecBenchmark -t 1 -prof gc -foe true -f 2 -wi 5 -i 5 -w 1s -r 1s -rf json -rff $resultPath 2>&1 | Tee-Object -FilePath $logPath
if ($LASTEXITCODE -ne 0) { throw 'JMH failed; inspect console.log' }
```

| 옵션 | 의미 |
| --- | --- |
| `CodecBenchmark` | 벤치마크 이름에 대한 정규식 필터 |
| `-p workload=flat` | 특정 파라미터만 실행. 생략하면 선언된 값 모두 실행 |
| `-t 1`, `-f 2` | worker 1개, fork 2개 |
| `-wi 5`, `-w 1s` | 워밍업 iteration 수와 구간 시간 |
| `-i 5`, `-r 1s` | 측정 iteration 수와 구간 시간 |
| `-prof gc` | GC·할당 관련 보조 지표 수집 |
| `-foe true` | 오류 발생 시 실행 실패로 처리 |
| `-rf json`, `-rff 경로` | 결과 형식과 저장 위치 |
| `-bm avgt`, `-tu us` | 필요할 때 모드와 시간 단위를 덮어씀 |

명령행 설정은 해당 애너테이션 설정을 덮어쓸 수 있으므로 최종 콘솔 헤더와 JSON을 확인한다. 옵션은 [JMH 명령행 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/runner/options/CommandLineOptions.java)을 참고한다.

기존 실행 스크립트를 사용하려면 위에서 만든 `$resultPath`를 전달한다. 다음 명령은 별도의 전체 실행을 시작하므로 수동 실행과 둘 다 수행할 필요는 없다.

```powershell
./pojo-codec/benchmarks/Run-Benchmarks.ps1 -ResultPath $resultPath
```

스크립트의 `-Quick`은 fork 1, warmup 2, measurement 3, 구간 500ms의 탐색 구성이다. 정식 결과와 직접 비교하지 않는다. 스크립트는 실행 뒤 성능 기준 검사도 수행하므로 JMH 측정이 성공해도 비교 기준 미달로 종료될 수 있다.

## 6. 결과가 도출되는 과정과 읽는 방법

### 6.1 모드별 점수

| 모드 / 콘솔 표기 | 의미 | 비교 방향 |
| --- | --- | --- |
| Throughput / `thrpt` | 단위 시간당 처리 연산 수, 예: ops/s | 높을수록 좋음 |
| AverageTime / `avgt` | 연산당 평균 경과시간, 예: us/op | 낮을수록 좋음 |
| SampleTime / `sample` | 일부 호출 시간 표본의 분포와 백분위 | 같은 조건에서 낮은 지연 확인 |
| SingleShotTime / `ss` | iteration별 단발 또는 지정 batch의 시간 | 의도한 초기 상태에서 비교 |
| All / `all` | 여러 모드 실행 | 모드별로 따로 해석 |

SingleShotTime 자체가 콜드 스타트를 보장하지는 않는다. warmup·fork·상태 초기화·클래스 로딩 범위를 별도로 설계해야 한다. SampleTime의 p99도 JMH 호출 분포이며, 외부 요청 도착률·네트워크·대기열을 포함한 서비스 p99가 아니다. [모드 공식 예제](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_02_BenchmarkModes.java).

### 6.2 AverageTime의 집계

단일 스레드, 기본 batch, 호출당 1 op인 현재 구성을 기준으로 이해하면 다음과 같다.

1. 측정 iteration에서 경과시간과 연산 수를 얻는다.
2. `iteration 점수 = 경과시간 / 연산 수`를 결과 단위로 환산한다.
3. fork별 측정 iteration 점수를 모아 최종 평균과 변동을 계산한다.
4. GC profiler 등 보조 지표는 각 지표의 집계 규칙으로 계산한다.

예를 들어 1초 동안 2,000,000 op를 수행했다면 약 `0.5 us/op`다. 실제 JMH는 측정된 경과시간과 보정된 연산 수를 사용하므로 명령의 `-r 1s`만으로 점수를 재계산하면 정확히 일치하지 않을 수 있다. 다중 스레드의 평균시간을 전체 스레드의 처리량과 같은 식으로 해석하지 않는다. [AverageTime 집계 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/results/AverageTimeResult.java).

### 6.3 Score, Error, Cnt

다음은 형식을 설명하기 위한 가상 결과다.

```text
Benchmark                    (workload)  Mode  Cnt  Score   Error  Units
CodecBenchmark.pojoSerialize       flat  avgt   10  0.500 ± 0.020  us/op
```

- `Score`: 집계한 평균 점수.
- `Cnt`: 집계 표본 수. 현재 AverageTime 구성은 2 fork × 5 measurement = 10이며, 메서드 호출 횟수는 아니다. SampleTime에서는 표본 의미가 다르다.
- `Error`: JMH 1.36의 평균 지표에서 기본 99.9% 신뢰구간 반폭. 위 예의 구간은 약 `[0.480, 0.520] us/op`다. 표준편차나 모든 호출의 최소·최대 범위를 뜻하지 않는다.

JMH는 평균과 표본 표준편차, 표본 수, Student t 분포를 이용한다. 표본이 너무 적으면 오차가 NaN으로 나올 수 있다. 신뢰구간이 좁아도 케이스 정의의 오류나 환경 편향까지 사라지는 것은 아니다. 구간이 겹치면 차이가 없다고 단정할 수 없고, 겹치지 않는 조건은 비교 정책으로 사용할 수 있다. [오차·신뢰구간 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/results/Result.java), [통계 계산 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/util/AbstractStatistics.java).

### 6.4 할당·GC 지표

| 지표 | 해석 |
| --- | --- |
| `gc.alloc.rate.norm` / B/op | 연산당 정규화한 객체 할당 byte 추정치 |
| `gc.alloc.rate` / MB/sec | 시간당 할당량. 더 빨리 처리하면 같은 B/op라도 증가할 수 있음 |
| `gc.count` | 관측 구간의 GC 횟수 |
| `gc.time` / ms | GC 관리 인터페이스에서 얻은 수집 시간 변화량 |

B/op는 직렬화 결과 byte 수, GC 후 생존량, 최대 힙 또는 RSS가 아니다. `gc.time`도 서비스 전체 정지시간 분포를 직접 뜻하지 않는다. `-prof gc`는 구간 전후의 카운터를 이용하며 JVM 지원과 스레드 생성·종료 패턴에 따라 한계가 있다. 지표 누락이나 NaN을 0으로 처리하지 않는다. 프로파일러 비용을 고려해 양쪽 조건을 동일하게 맞춘다. [GC profiler 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java).

### 6.5 JSON에서 확인할 항목

| 필드 | 확인 내용 |
| --- | --- |
| `benchmark`, `params`, `mode`, `threads` | 실제 실행 케이스 식별 |
| `jmhVersion`, `jdkVersion`, `jvm`, `jvmArgs` | 도구·JVM 조건 |
| `forks`, `warmupIterations`, `measurementIterations` | 반복 조건 |
| `primaryMetric.score`, `scoreUnit` | 본 측정 점수와 단위 |
| `scoreError`, `scoreConfidence` | 오차와 신뢰구간 |
| `primaryMetric.rawData` | 현재 AverageTime에서 fork별 measurement iteration 점수 배열 |
| `secondaryMetrics` | GC·할당 등 보조 지표 |

**AverageTime JSON의 `scorePercentiles`를 개별 호출의 p99 응답시간으로 읽지 않는다.** 현재 모드에서는 iteration 평균 점수의 분포에서 나온 값이다. SampleTime의 호출 시간 표본 분포와 구분한다. 결과 형식은 모드에 따라 달라질 수 있다. [JSON 출력 구현](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java).

기존 보관 결과 [final.json](../pojo-codec/benchmarks/results/final.json)의 첫 항목은 `jacksonDeserialize / flat`, JMH 1.36, JDK 17.0.10, 2 fork, 5 measurement다. 평균은 약 `0.626247 us/op`, 신뢰구간은 약 `[0.608116, 0.644379]`이며 `rawData`는 2 × 5 구조다. 이는 **기존 기록의 읽기 예시**이고 현재 작업 트리의 새 실측값이 아니다.

## 7. 비교 결과와 판정 보고서 만들기

동일 workload·방향·스레드·JVM 조건의 결과끼리 비교한다. 평균시간 비교의 계산 예는 다음과 같다.

```text
기준 A = 2.0 us/op, 후보 B = 1.5 us/op  (설명용 가상 수치)
속도 배수 = A / B = 1.33배
시간 감소율 = (A - B) / A × 100 = 25%
```

속도 배수와 시간 감소율은 서로 다른 표현이다. 처리량 모드의 배수는 `후보 / 기준`으로 구한다. 서로 다른 workload의 시간을 단순 평균해 전체 개선율을 만들지 말고, 실제 사용 비중을 정의하거나 케이스별로 보고한다.

현재 저장소의 판정기는 다음과 같이 사용한다.

```powershell
./pojo-codec/benchmarks/Test-Performance.ps1 -ResultPath $resultPath
```

이 스크립트는 12개 결과 존재 여부, 단일 스레드 `avgt`와 `us/op`, 정식 실행의 최소 fork·iteration 수, 비교 JVM 조건, 할당 지표 등을 검사한다. 이어서 6개 비교 쌍마다 다음 두 조건을 모두 요구한다.

```text
POJO 시간 신뢰구간 상한 < Jackson 시간 신뢰구간 하한
POJO B/op 신뢰구간 상한 < Jackson B/op 신뢰구간 하한
```

이는 프로젝트가 선택한 엄격한 판정 기준이며 JMH 자체의 자동 합격 기준이 아니다. 스크립트가 검사하지 않는 실제 구간 길이·CPU·OS·입력 정합성 등은 원본 기록과 실행 환경에서 추가 확인한다. 판정 실패는 기능 오류, 실행 오류, 성능 기준 미달 중 어느 단계인지 구분해서 보고한다.

측정 보고서에는 다음을 남긴다.

| 항목 | 기록 내용 |
| --- | --- |
| 목적 | 어떤 구현의 어떤 비용을 비교했는가 |
| 소스·환경 | 커밋과 미커밋 변경/해시, OS·CPU·JDK·JMH·JVM 옵션 |
| 케이스 | 입력 크기·분포, 포함/제외 비용, 공유·재사용 조건 |
| 실행 | 명령, fork·스레드·warmup·measurement, profiler |
| 결과 | 케이스별 점수·단위·신뢰구간·B/op·개선율 |
| 근거 | 원본 JSON과 콘솔 로그, 실행 실패·누락 여부 |
| 결론 | 적용할 범위와 추가 서비스 부하 검증 여부 |

## 8. 흔한 문제와 점검 순서

| 증상 | 우선 확인할 사항 |
| --- | --- |
| `Unable to find the resource: /META-INF/BenchmarkList` | benchmark profile 활성화, annotation processor 실행, test-classes classpath |
| `No matching benchmarks` | `-l` 목록과 이름 필터, `@Benchmark`, 새 코드 재컴파일 |
| fork에서 클래스 로딩 실패 | 외부 Java 프로세스에도 전달되는 실제 classpath와 의존성 |
| 비정상적으로 작은 시간 | 결과 미사용, 상수 계산, 빈 작업, 1 op 정의 |
| iteration 점수가 계속 변화 | 워밍업 부족, 캐시 성장, 상태 누적, JIT·GC·전력/온도 변화 |
| 반복 실행의 순위가 바뀜 | 효과 크기와 오차, 실행 순서 편향, 백그라운드 부하 |
| 처리량 증가와 함께 MB/sec 증가 | B/op와 함께 비교해 처리량 증가 효과인지 구분 |
| 메모리가 계속 증가 | State·캐시·컬렉션의 누적과 정리 여부, 별도 장시간 검증 |

점수가 이상할 때는 먼저 측정 경계와 생성 코드를 확인한다. JVM 전체 최적화를 끄거나 내부 루프를 추가해 원하는 수치에 맞추지 않는다. 기능 정합성 확인 → 짧은 실행 → 정식 반복 실행 → JSON 분석 → 동일 조건 비교 → 서비스 환경 검증 순서로 진행한다.

## 9. 문서 확인 범위

이 문서는 저장소의 POM·벤치마크 클래스·실행/판정 스크립트·기존 결과 JSON과 OpenJDK 공식 소스를 대조해 작성했다. 새 성능 수치를 주장하지 않으며, 명령 예제의 실제 실행 여부는 문서 작업의 검증 기록과 구분한다. JVM·OS 수준의 추가 진단은 [Java 성능 튜닝 가이드](README.md)를 참고한다.
