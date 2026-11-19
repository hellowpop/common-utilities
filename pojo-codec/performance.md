# Jackson 대비 성능 요구 및 검증

## 목표와 기준

사용자 추가 요구: Jackson JSON serialize/deserialize보다 CPU 시간과 메모리 사용을 개선한다. 문자열 JSON이 아닌 UTF-8 바이트 스트림을 비교하여 불필요한 String 중간 변환으로 Jackson에 불이익을 주지 않는다. controller와 ObjectMapper/ObjectWriter/ObjectReader 모두 재사용하며 초기 메타데이터 구성은 warmup에 포함한다.

측정 대상은 flat POJO, 중첩 POJO·List, 숫자 배열의 세 workload다. 직렬화와 역직렬화를 별도로 측정한다. 단일 스레드 JMH average time(us/op)과 GC profiler의 `gc.alloc.rate.norm`(B/op)을 기록한다. B/op은 누적 힙 할당량이며 프로세스 RSS·유지 메모리와 같다고 주장하지 않는다. CPU 시간은 단일 스레드에서의 처리 시간으로 평가하며 환경·CPU·JDK·명령·원본 결과를 함께 남긴다.

합격 기준은 동일 workload·방향에서 Jackson보다 시간과 B/op이 모두 작아지는 것이다. 측정 오차 범위가 겹치면 확정적 우위로 판정하지 않는다. 전체 입력에서의 보장은 하지 않으며, 실패한 workload는 숨기거나 비교 데이터에서 제외하지 않는다. custom Codec 및 별도 comparator·컨테이너 의미 보존은 기존 기능 테스트로 검증한다.

비교 기준 버전은 Jackson databind 2.20.1, JMH 1.36으로 고정한다. 운영 의존성은 추가하지 않고 `benchmark` Maven profile의 test 의존성으로만 사용한다. 방법 참고: [OpenJDK JMH](https://github.com/openjdk/jmh), [Jackson databind](https://github.com/FasterXML/jackson-databind).

## 실행 계획

1. 기준 구현을 JMH로 측정하고 JSON 원본 결과를 보관한다.
2. 반복적인 Type/옵션/Codec 탐색을 캐시한다. 캐시는 컨트롤러 설정에 종속하며 기존 타입 허용 목록을 우회하지 않는다.
3. 중첩 프레임마다 할당·복사하는 임시 버퍼를 단일 위치 수정 가능한 버퍼로 통합한다. 스트림 소유권·경계·메모리 제한을 유지한다.
4. 기본 POJO·primitive·문자열 경로를 최적화하고 오류 시 경로를 보존한다. 사용자 Codec과 컨테이너는 기존 확장 계약을 유지한다.
5. 고정 wire fixture와 전체 기능 테스트를 통과시킨 뒤 동일 JMH 조건으로 재측정한다. 수치에 근거해 미달 병목을 계속 개선한다.
6. 결과·제약·재현 명령을 본 문서에 기록하고 package/테스트 생략 install을 검증한다.

## 재현 명령

저장소 루트에서 Java 17을 사용한다.

```powershell
mvn -f pojo-codec/pom.xml -Pbenchmark test-compile dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt -DincludeScope=test
$benchmarkClasspath = "pojo-codec/target/classes;pojo-codec/target/test-classes;" + (Get-Content pojo-codec/target/benchmark-classpath.txt -Raw).Trim()
java -cp $benchmarkClasspath org.openjdk.jmh.Main CodecBenchmark -prof gc -f 2 -wi 5 -i 5 -w 1s -r 1s -rf json -rff pojo-codec/target/performance-final.json
```

탐색용 단축 실행 결과는 정식 측정 결과와 구분한다.

아래 스크립트로 빌드·측정·합격 판정을 한 번에 재현할 수도 있다. JAVA_HOME을 측정할 JDK 17 경로로 지정한다.

```powershell
./pojo-codec/benchmarks/Run-Benchmarks.ps1
./pojo-codec/benchmarks/Test-Performance.ps1 -ResultPath pojo-codec/target/performance-final.json
```

## 최종 측정 결과 — 2026-11-18

- 환경: Windows 11 amd64, Intel Core i7-10700(8 cores / 16 logical processors), Oracle JDK 17.0.10, 단일 측정 스레드.
- JMH 1.36, Jackson databind 2.20.1. Fork 2개, fork당 warmup 5×1초 및 측정 5×1초, `-Xms512m -Xmx512m`, `-prof gc`.
- 양쪽 모두 controller/mapper 및 타입별 reader/writer, 출력 스트림을 재사용한다. 생성자·타입 계획의 초기화는 정상 상태 측정에서 제외한다. 각 setup에서 복원된 값의 JSON tree가 같은지 검증한다.
- flat: primitive 4개와 문자열 2개. nested: root scalar 및 ArrayList 안의 flat POJO 16개. numeric: long ID와 int 배열 1024개.
- 아래의 시간은 us/op, 할당량은 B/op이다. 속도 배수는 Jackson 시간 / POJO 시간이다.

| Workload | 동작 | Jackson 시간 | POJO 시간 | 속도 배수 | Jackson 할당량 | POJO 할당량 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| flat | serialize | 0.5278 | 0.2936 | 1.80x | 440.0 | <0.1 |
| flat | deserialize | 0.6262 | 0.5051 | 1.24x | 1144.0 | 344.0 |
| nested | serialize | 6.4943 | 3.8575 | 1.68x | 1616.0 | <0.1 |
| nested | deserialize | 8.3615 | 7.2316 | 1.16x | 7624.0 | 5232.0 |
| numeric | serialize | 7.6563 | 2.6604 | 2.88x | 416.0 | <0.1 |
| numeric | deserialize | 28.5266 | 4.1842 | 6.82x | 15512.0 | 8280.0 |

**판정: 6개 조합 모두 합격.** 시간과 할당량 각각에서 POJO의 신뢰구간 상한이 Jackson 하한보다 낮다. 역직렬화 할당량은 약 31.4~69.9% 감소했다. 직렬화의 미세한 B/op 수치는 profiler/측정 오버헤드를 포함하므로 문자 그대로의 영(0) 할당을 보장하지 않는다.

최초 정식 실행도 전 조합을 통과했고, 작은 버퍼 예산에서 null 루트를 처리하는 회귀 수정 후 최종 코드로 다시 측정하여 위 결과를 확인했다. [최종 원본 JSON](benchmarks/results/final.json), [첫 정식 실행](benchmarks/results/first-formal.json), [최적화 전 탐색 측정](benchmarks/results/baseline-exploratory.json), [소스 SHA-256](benchmarks/results/source-fingerprints.md)을 보관한다. 탐색 결과는 fork/시간 조건이 다르므로 최종 결과와 CPU 개선 배수를 직접 계산하지 않는다.

## 구현과 회귀 검증

- `CompiledCodec`이 controller별 ClassValue에 기본 POJO 실행 계획과 인코딩된 필드/타입 헤더를 저장한다.
- 프레임마다 새 버퍼를 만들지 않고 하나의 버퍼에서 길이를 나중에 수정한다. primitive 필드는 boxing 없이 처리하고 primitive 배열은 확보한 연속 영역에 기록한다.
- 스크래치의 재사용 바이트 배열은 controller·스레드별 최대 64 KiB다. 그 이상은 호출 후 보관하지 않는다. 재진입은 별도 스크래치를 사용한다. 스크래치가 controller/Registry를 역참조하는 누수를 회귀 테스트로 방지한다.
- 컴파일 경로의 v1 출력은 범용 Engine과 바이트 단위로 비교한다. null/empty, Unicode/엄격한 UTF-8, primitive 전체 배열, 스키마 변경, 제한된 버퍼·깊이, 재진입과 타입 등록 계약을 포함하여 **기능 테스트 60개 통과**.
- 별도 정적 코드 리뷰에서 Important/Critical 지적사항은 없었다. 성능 판정 스크립트는 최적화 전 결과를 거부하고 최종 정식 결과를 통과시키는 것도 확인했다.

## 적용 범위

위 수치는 정상 상태의 세 workload 및 두 방향에 대한 결과다. 사용자 Codec, 순환 타입 그래프, Map/정렬·불변·동시성 컨테이너, 날짜·큰 수 등 범용 Engine을 사용하는 경로까지 Jackson보다 빠르다고 주장하지 않는다. 기존 기능 지원은 그대로 유지한다.

메모리 비교는 연산당 할당량이다. 유지 힙/RSS, controller 생성 비용, 최초 호출 지연 및 출력 버퍼를 매번 새로 만드는 조건의 우위는 이 측정으로 확정하지 않는다. 하드웨어·JDK·실제 데이터가 바뀌면 동봉한 스크립트로 다시 측정한다. v1 바이너리 크기가 JSON보다 작다는 보장도 이 성능 조건에 포함하지 않는다.

## PojoInjectable 확장 후 성능 회귀 — 2026-11-19

동일 Java 17 환경과 정식 fork 2개, warmup/measurement 각각 5×1초, 단일 스레드 조건으로 다시 측정했다. 비교 데이터는 인터페이스를 구현하지 않는 기존 flat/nested/numeric 객체이며, 여섯 조합 모두 시간·할당량의 신뢰구간 기준을 통과했다.

| Workload | 동작 | Jackson us/op | POJO us/op | Jackson B/op | POJO B/op |
| --- | --- | ---: | ---: | ---: | ---: |
| flat | serialize | 0.55 | 0.31 | 440 | <0.1 |
| flat | deserialize | 0.64 | 0.52 | 1144 | 344 |
| nested | serialize | 6.45 | 3.84 | 1616 | <0.1 |
| nested | deserialize | 8.48 | 7.41 | 7624 | 5232 |
| numeric | serialize | 8.00 | 2.51 | 416 | <0.1 |
| numeric | deserialize | 28.86 | 4.24 | 15492 | 8280 |

[원본 JSON](benchmarks/results/lifecycle-2026-11-19.json)과 [소스 SHA-256](benchmarks/results/source-fingerprints-lifecycle.md)을 보관한다. 사용자 콜백을 추측 실행하지 않기 위해 injectable 타입이 정적으로 포함된 그래프는 범용 Engine을 사용한다. 콜백 구현 비용과 이 범용 경로의 성능은 위 측정에 포함하지 않는다. 기능·실패·호환성 검증은 Java 17 클린 빌드 테스트 78개가 통과했다.

```powershell
./pojo-codec/benchmarks/Test-Performance.ps1 -ResultPath pojo-codec/benchmarks/results/lifecycle-2026-11-19.json
```

이후 code_formatter.xml 적용으로 소스의 공백·줄바꿈과 주석 배치가 변경됐다. 포맷 전후 Java 구문 트리 및 POM XML 구조의 동일성을 확인했고 테스트 78개를 다시 통과했다. 위 원본 결과와 SHA-256은 포맷 적용 전 측정 소스의 기록으로 보존하며, 포맷만을 이유로 JMH를 재측정하지는 않았다.
