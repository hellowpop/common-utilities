# Linux Java 성능 튜닝 및 측정 가이드

작성일: 2026-11-06. 프로젝트의 **Java 17 / HotSpot**을 기준으로 설명한다. Java 21·25의 차이는 별도로 표기한다. OpenJ9 등 다른 JVM에는 그대로 적용하지 않는다. 아래 수치는 실험 예제이며, 운영 권장값이나 측정 결과가 아니다.

## 목차

1. [측정 기준과 튜닝 순서](#1-측정-기준과-튜닝-순서)
2. [메모리 구조와 GC 선택](#2-메모리-구조와-gc-선택)
3. [GC 설정 옵션과 값의 차이](#3-gc-설정-옵션과-값의-차이)
4. [실행 설정 예제](#4-실행-설정-예제)
5. [Linux 프로세스 점검 명령](#5-linux-프로세스-점검-명령)
6. [JVM 점검과 상세 자료 수집](#6-jvm-점검과-상세-자료-수집)
7. [증상별 분석 절차](#7-증상별-분석-절차)
8. [측정 기록 양식](#8-측정-기록-양식)
9. [출처와 검증 범위](#9-출처와-검증-범위)

## 1. 측정 기준과 튜닝 순서

성능 튜닝은 처리량, 응답 지연, CPU, 메모리 사용량 사이의 균형을 찾는 작업이다. GC 정지시간만 줄어도 CPU 경쟁 때문에 전체 응답시간이 나빠질 수 있다. 먼저 서비스의 성공 조건을 정한다.

| 지표 | 측정·비교 방법 |
| --- | --- |
| 처리량 | 성공 요청 수/초, 배치 완료 건수/초. 오류를 성공에 합산하지 않는다 |
| 응답시간 | 평균과 함께 p50·p95·p99 및 타임아웃 비율을 기록한다 |
| CPU | 프로세스·스레드 사용률, cgroup quota, throttling, 호스트 steal을 함께 본다 |
| 메모리 | 힙 사용량·GC 후 생존량, RSS/PSS, cgroup 사용량을 각각 기록한다 |
| GC | 정지시간 분포·최대값·횟수, Full GC, 할당 속도, 회수 후 힙 추이 |
| 안정성 | 오류율, OOM, 스레드·파일 디스크립터·연결 수의 장기 증가 |

권장 작업 순서:

1. JDK 배포판·정확한 버전, 앱 버전, CPU/메모리 제한, JVM 인수, 트래픽 조건을 기록한다.
2. 현재 설정으로 기준 데이터를 수집한다. 애플리케이션·DB·캐시·커넥션 풀 상태를 함께 기록한다.
3. 워밍업으로 클래스 로딩·JIT·캐시가 안정된 뒤 정상 부하, 최대 예상 부하, 순간 급증, 장시간 부하를 측정한다. 기동 성능은 별도 지표로 측정한다.
4. 같은 요청 구성·데이터·동시성·도착률·시간으로 반복한다. 외부 부하 발생기를 사용하고 발생기 자체의 CPU/연결 병목도 확인한다.
5. CPU 계산, 객체 할당, GC, 락, DB, 네트워크, 디스크 중 병목을 좁힌다.
6. 가설 하나에 대해 한 번에 한 설정을 바꾼다. 비교를 위해 바뀐 값을 모두 남긴다.
7. p99·처리량·오류율·리소스를 함께 비교해 채택하거나 이전 설정으로 되돌린다.

예: p99 200ms 이하와 오류율 0.1% 미만은 서비스가 정할 목표의 예이다. `MaxGCPauseMillis=200`이 이 응답시간을 보장하지 않는다. 단위 코드 성능은 JMH 등의 별도 실험으로 확인하고 서비스 전체 결과와 구분한다.

## 2. 메모리 구조와 GC 선택

### 2.1 힙과 프로세스 메모리의 차이

- **힙**: 일반 Java 객체와 배열. `-Xmx`는 힙 최대 크기다.
- **Metaspace**: 클래스 메타데이터. 클래스 로더 누수와 동적 클래스 생성의 영향을 받는다.
- **스레드 스택**: 플랫폼 스레드 수와 `-Xss`의 영향을 받는다. 예약량과 실제 물리 메모리 사용량은 다르다.
- **기타 네이티브 영역**: DirectBuffer, JIT code cache, GC 관리 구조, JNI/외부 라이브러리, allocator 등이 사용한다.
- **RSS**: 현재 상주하는 프로세스 페이지. 공유 페이지가 포함되며 힙 사용량과 일치하지 않는다. PSS는 공유 페이지를 비례 배분한다.
- **cgroup 메모리**: 그룹 내 프로세스·파일 캐시 등도 포함하므로 단일 JVM RSS와 일치하지 않는다.

따라서 `-Xmx4g`는 프로세스를 4GiB로 제한하는 옵션이 아니다. 컨테이너 8GiB 제한에서 힙 4GiB로 시작해도 나머지 4GiB가 충분한지는 스택·네이티브·캐시·다른 프로세스의 최대 사용량으로 검증해야 한다. 고정 비율을 모든 서비스에 적용하지 않는다.

### 2.2 GC 종류 비교

STW(Stop-The-World)는 애플리케이션 스레드를 멈추고 GC 등의 JVM 작업을 하는 구간이다. 동시 GC도 일부 STW 구간과 CPU·메모리 비용이 있다.

| GC / 선택 옵션 | 특성 | 비교할 상황 | 주의점 |
| --- | --- | --- | --- |
| Serial / `-XX:+UseSerialGC` | GC를 주로 단일 스레드로 수행 | 작은 힙·적은 CPU·작은 도구 | 큰 힙에서 정지가 길어질 수 있음 |
| Parallel / `-XX:+UseParallelGC` | 여러 GC 스레드로 STW 수집, 처리량 지향 | 배치·응답 지연 허용 작업 | 처리량 이득과 최대 정지를 함께 측정 |
| G1 / `-XX:+UseG1GC` | region 기반, 동시 마킹과 STW evacuation | 일반 서버의 우선 비교 기준 | 정지 목표는 보장값이 아니며 여유 공간 필요 |
| ZGC / `-XX:+UseZGC` | 대부분의 회수 작업을 동시 수행, 낮은 정지시간 지향 | 짧은 정지가 중요한 서비스 | 애플리케이션과 CPU 경쟁, 할당을 버틸 힙 여유 필요 |
| Shenandoah / `-XX:+UseShenandoahGC` | 동시 회수·압축 기반 저지연 GC | 해당 GC를 제공하는 JDK의 비교 후보 | 배포판·버전·플랫폼별 제공 여부 확인 |

Java 17의 일반적인 서버 환경에서는 G1이 기본 선택이지만 작은 리소스 구성 등에서는 달라질 수 있다. 실행 JVM의 `VM.flags -all` 또는 시작 로그로 확인한다. GC 선택 플래그는 하나만 사용한다. 비교 기준은 [Oracle의 Collector 선택 안내](https://docs.oracle.com/en/java/javase/17/gctuning/available-collectors.html), Shenandoah 제공 조건은 [OpenJDK Shenandoah 안내](https://wiki.openjdk.org/spaces/flyingpdf/pdfpageexport.action?pageId=25002018)를 참고한다.

### 2.3 버전 차이

| JDK | GC·로그 관련 차이 |
| --- | --- |
| 8 HotSpot | 일반적으로 Parallel 기본. 예전 GC 로그 옵션을 사용하며 G1은 명시 선택 가능 |
| 17 | G1 중심으로 시작. ZGC는 비세대 방식이며 `ZGenerational`을 사용하지 않음 |
| 21 | `-XX:+UseZGC -XX:+ZGenerational`로 세대형 ZGC 선택. `UseZGC` 단독은 비세대형 |
| 23 | ZGC 선택 시 세대형이 기본 |
| 24·25 | ZGC의 비세대형 모드 제거. 세대형 선택에 `ZGenerational`을 추가할 필요 없음 |

CMS(`UseConcMarkSweepGC`)는 JDK 14에서 제거되었으므로 Java 17에 적용할 수 없다. 과거 CMS 튜닝 설정을 G1/ZGC로 복사하지 않는다. 근거: [제거된 GC·도구](https://docs.oracle.com/en/java/javase/17/migrate/removed-tools-and-components.html). ZGC 버전 변화 근거: [JDK 21 변경사항](https://docs.oracle.com/en/java/javase/25/migrate/significant-changes-jdk-21.html), [JDK 23 변경사항](https://docs.oracle.com/en/java/javase/25/migrate/significant-changes-jdk-23.html), [JDK 24 변경사항](https://docs.oracle.com/en/java/javase/25/migrate/significant-changes-jdk-24.html). 배포판의 백포트 여부와 실제 도움말도 확인한다.

## 3. GC 설정 옵션과 값의 차이

`-XX:+이름`은 켜기, `-XX:-이름`은 끄기, `-XX:이름=값`은 수치 지정이다. 아래 `g`는 JVM 메모리 단위로 GiB에 대응하며 시간 단위는 옵션마다 다르다. 예제값을 기본값으로 해석하지 않는다.

### 3.1 힙·비힙 메모리

| 옵션 | 작은 값 / 비활성 | 큰 값 / 활성 | 값 비교 예 |
| --- | --- | --- | --- |
| `-Xms` | 초기 committed heap을 작게 시작, 확장 가능 | 초기 메모리 확보 증가, 확장 변동 감소 | `1g` 대 `4g` |
| `-Xmx` | 회수 빈도·공간 부족 위험 증가 | 더 많은 생존 객체·할당 여유, 프로세스 메모리 비용 증가 | `2g` 대 `4g`; GC 시간은 GC 종류·생존량에도 좌우됨 |
| `-XX:MaxRAMPercentage` | JVM이 인식한 메모리 대비 최대 힙 비율 감소 | 힙 여유 증가, 네이티브 영역 여유 감소 | `50` 대 `70`; `Xmx`를 명시하면 최대 힙은 `Xmx`로 결정 |
| `-XX:InitialRAMPercentage` | 초기 힙 비율 감소 | 초기 힙 비율 증가 | 명시적 `Xms`가 없을 때 사용 |
| `-XX:MaxMetaspaceSize` | 클래스 메타데이터 한도 도달이 빨라질 수 있음 | 메타데이터 여유 증가 | `256m` 대 `512m`; 기본은 명시 한도 없음 |
| `-XX:MaxDirectMemorySize` | NIO DirectBuffer 한도 감소 | DirectBuffer 여유·네이티브 사용 가능량 증가 | `256m` 대 `1g`; 전체 JNI 메모리의 제한이 아님 |
| `-Xss` | 스레드당 스택 예산 감소, 깊은 호출에서 StackOverflow 위험 | 깊은 호출 여유 증가, 스레드 수가 많으면 예약 부담 | `512k` 대 `1m`; 플랫폼별 기본값 상이 |
| `-XX:+AlwaysPreTouch` | 미사용 시 최초 페이지 접근 비용이 실행 중 발생 가능 | 초기 힙 페이지를 미리 접근, 기동시간·초기 RSS 증가 | `Xms=Xmx`와 조합 시 기동 메모리 확보량 확인 |

`Xms=Xmx`는 힙 크기 변화를 줄이지만 유휴 시 메모리를 줄이는 데 제약이 있다. 낮은 초기 메모리가 중요한 경우 `Xms<Xmx`를 비교한다. 퍼센트 기반 힙은 컨테이너 인식·작은 메모리의 ergonomics 영향이 있으므로 산출값을 직접 확인한다. 옵션 정의는 [Java 17 실행 옵션](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)에 근거한다.

### 3.2 G1의 주요 옵션

| 옵션 | 값이 작을 때 | 값이 클 때 | 해석·예제 |
| --- | --- | --- | --- |
| `-XX:MaxGCPauseMillis` | 짧은 정지를 목표로 young 크기 등을 조정, 수집 빈도·비용 증가 가능 | 정지시간을 더 허용, 처리량 이득 가능 | Java 17 G1 기본 목표 200ms. `100`과 `200`을 비교하며 실제 p99 확인 |
| `-XX:InitiatingHeapOccupancyPercent` | 동시 마킹을 일찍 시작하는 방향 | 늦게 시작하는 방향, 회수 지연 위험 | Java 17 초기 기준 45. 기본 adaptive IHOP에서는 학습 후 기준을 자동 조정 |
| `-XX:+G1UseAdaptiveIHOP` | `-`로 끄면 고정 IHOP 기준 사용 | `+`로 켜면 관측 데이터로 시작 시점 예측 | 기본은 활성. `IHOP=30`만 주어 영구 30% 고정이라고 해석하지 않음 |
| `-XX:G1ReservePercent` | 복사용 여유 비중 감소 | evacuation 실패 완화용 여유 비중 증가, 실사용 여유 감소 | Java 17 기본 10. `10` 대 `20`; 실제 예약량은 예측·힙 크기와 관계됨 |
| `-XX:G1HeapRegionSize` | region 수 증가, 더 작은 객체도 humongous 대상 | region 수 감소, 큰 객체의 humongous 분류를 줄일 수 있음 | Java 17은 1~32MiB의 2 거듭제곱. 기본 자동 선택; `2m` 대 `4m` |
| `-XX:ParallelGCThreads` | STW GC 병렬도 감소 | 병렬도 증가, CPU 경쟁·스케줄링 비용 가능 | 자동 선택 우선. CPU quota가 작은 환경에서 무작정 증설하지 않음 |
| `-XX:ConcGCThreads` | 동시 마킹이 느려질 수 있음 | 마킹 속도 개선 가능, 앱 CPU 여유 감소 | 할당 속도와 마킹 완료 시간을 함께 확인 |

G1의 humongous 객체는 region 크기의 절반 이상인 객체다. region을 키우면 분류가 달라질 수 있지만 할당 낭비·회수 단위도 달라지므로 큰 배열·버퍼 생성 패턴부터 확인한다. `Xmn`이나 `NewRatio`로 young 크기를 고정하면 G1의 정지 목표 조절을 방해할 수 있다.

설정 의미와 조절 원칙: [G1 구조·IHOP](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html), [G1 튜닝 지침](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-garbage-collector-tuning.html).

### 3.3 Parallel·ZGC 및 명시적 GC

| 옵션 / 대상 | 비교와 영향 |
| --- | --- |
| `-XX:GCTimeRatio=N` / Parallel | GC 시간 목표 비율은 `1/(N+1)`. `99`는 약 1%, `19`는 약 5%로 GC 시간을 더 허용한다. 실제 달성 보장 없음 |
| `-XX:MaxGCPauseMillis` / Parallel | 짧게 설정하면 young/old 크기 조정으로 처리량이 낮아질 수 있다. G1과 내부 조절 방식이 다름 |
| `-XX:NewRatio=N` / 세대형 GC | old:young 비율. `2`는 young이 전체의 약 1/3, `3`은 약 1/4. 적응 정책·다른 옵션에 따라 실제 크기는 달라짐. G1 기본 튜닝에 사용하지 않음 |
| `-Xmn` | young 크기 지정. 더 크게 하면 단기 객체 회수 빈도를 줄일 수 있지만 정지와 old 여유에 영향. ZGC에 전용 설정처럼 적용하지 않음 |
| `-XX:SoftMaxHeapSize` / ZGC | 예: `Xmx4g`, soft max `3g`. 3GiB를 유지하려 노력하나 필요하면 4GiB까지 사용. 강제 상한은 `Xmx` |
| `-XX:+ZUncommit` / ZGC | 미사용 힙을 OS에 반환. 끄면 재확보 변동은 줄일 수 있으나 유휴 메모리 점유 증가 |
| `-XX:ZUncommitDelay` / ZGC | 기본 300초. 짧으면 빠른 반환·재확보 증가 가능, 길면 메모리 유지 증가. `Xms` 아래로 반환하지 않음 |
| `-XX:+DisableExplicitGC` | `System.gc()` 요청을 무시. 라이브러리 동작을 확인한 뒤 비교. 일반 GC나 진단 명령의 모든 수집까지 차단하는 옵션이 아님 |
| `-XX:+ExplicitGCInvokesConcurrent` / 지원 GC | 명시적 GC 요청을 동시 수집으로 처리하도록 요청. 적용 GC·버전 확인. `DisableExplicitGC`와 함께 주면 무시된 요청을 되살리지 않음 |

Parallel 세부 근거: [Parallel Collector](https://docs.oracle.com/en/java/javase/17/gctuning/parallel-collector.html). ZGC는 생존 객체뿐 아니라 회수 중 새 할당을 수용할 공간이 필요하다. `Xms=Xmx`이면 `ZUncommit`의 반환 여지가 사라진다. [Java 17 ZGC 설명(PDF)](https://docs.oracle.com/en/java/javase/17/gctuning/hotspot-virtual-machine-garbage-collection-tuning-guide.pdf).

### 3.4 수치형 옵션 기본값과 실제 적용값 확인

앞 절의 영향 분석에 사용하는 기준값을 아래에 정리한다. **Java 17 HotSpot 기준**이며, 고정 기본값·자동 결정값·미지정 상태를 구분한다. 자동 결정값은 GC·인식 CPU 수·메모리 제한·플랫폼에 따라 달라지므로 특정 서버의 숫자를 공통 기본값으로 사용하지 않는다.

| 수치형 옵션 | 기본값 / 미지정 시 동작 | 단위·적용 조건 및 주의점 |
| --- | --- | --- |
| `-Xms` | 고정 용량 없음, ergonomics로 초기 힙 결정 | byte 또는 k/m/g. 초기 결정에 `InitialRAMPercentage` 등 사용. 실제 `InitialHeapSize` 확인 |
| `-Xmx` | 고정 용량 없음, ergonomics로 최대 힙 결정 | byte 또는 k/m/g. 일반적인 비율 외에도 최소 크기·정렬·플랫폼 제한 적용. 실제 `MaxHeapSize` 확인 |
| `-XX:MaxRAMPercentage` | `25.0` | %. 명시적 Xmx가 없을 때 최대 힙 산정에 사용 |
| `-XX:InitialRAMPercentage` | `1.5625` | %. 명시적 Xms가 없을 때 초기 힙 산정에 사용 |
| `-XX:MinRAMPercentage` | `50.0` | %. 작은 힙 환경의 **최대 힙** 산정용이며 최소 힙 비율이 아님. 작은 힙 ergonomics와 함께 적용 |
| `-XX:MaxMetaspaceSize` | 명시적 한도 없음 | 내부 flag의 매우 큰 정수는 이 상태를 나타냄. 실제로 그만큼 확보한다는 뜻이 아님 |
| `-XX:MaxDirectMemorySize` | flag `0`: JVM 자동 결정 | 0바이트로 제한한다는 뜻이 아님. NIO direct buffer 대상이며 JNI 전체 한도가 아님 |
| `-Xss` | Linux/x64에서 `1m`(1024KiB), 다른 플랫폼·빌드는 확인 필요 | Xss는 byte 또는 k/m/g. `ThreadStackSize` flag의 기본 표시 단위는 KiB |
| `-XX:MaxGCPauseMillis` / G1 | `200` | ms. 최대 정지의 목표이며 상한 보장 아님 |
| `-XX:MaxGCPauseMillis` / Parallel | 기본적으로 정지시간 목표 없음 | 내부 flag에는 매우 큰 정수가 표시될 수 있음. G1의 200ms와 구분 |
| `-XX:InitiatingHeapOccupancyPercent` | G1 초기 기준 `45` | %. adaptive IHOP 활성 시 학습 후 기준 자동 조정 |
| `-XX:G1ReservePercent` | `10` | %. evacuation을 위한 안전 여유. 별도 고정 메모리 영역 한도가 아님 |
| `-XX:G1HeapRegionSize` | 자동 결정, 약 2048개 region 목표 | Java 17에서 1~32MiB의 2 거듭제곱. flag 출력은 byte |
| `-XX:ParallelGCThreads` | GC·CPU 수에 따라 자동 결정 | G1/Parallel은 보통 CPU 8개 이하에서 인식 CPU 수, 그 이상은 증가분을 약 5/8 반영. 플랫폼별 차이 있음. ZGC에 같은 식을 적용하지 않음 |
| `-XX:ConcGCThreads` | GC별 자동 결정 | G1은 보통 ParallelGCThreads의 약 1/4, 최소 1 및 정수 보정. ZGC는 별도 정책 사용 |
| `-XX:GCTimeRatio` / Parallel | `99` | GC 시간 목표 약 1%. G1에서 관찰된 flag값은 `12`이므로 GC별 의미·기본값 구분 |
| `-XX:NewRatio` | `2` | old:young 비율. 적응 정책·다른 옵션에 따라 실제 세대 크기는 달라짐 |
| `-Xmn` | 고정된 공통 용량 없음, GC·힙 설정으로 young 크기 결정 | byte 또는 k/m/g. G1의 정지 목표 제어를 방해할 수 있으며 ZGC 튜닝에 적용하지 않음 |
| `-XX:SoftMaxHeapSize` / ZGC | 최대 힙 크기(`Xmx` 또는 자동 산정 최대 힙) | byte 또는 k/m/g. 목표 상한이며 필요하면 Xmx까지 사용 |
| `-XX:ZUncommitDelay` / ZGC | `300` | 초. 미사용 영역의 반환 자격까지의 지연이며 반환 시각 보장 아님 |

함께 사용한 불리언 기본값은 `AlwaysPreTouch=false`, `G1UseAdaptiveIHOP=true`, `ZUncommit=true`, `DisableExplicitGC=false`, `ExplicitGCInvokesConcurrent=false`다. flag가 출력되어도 선택한 GC에서 실제 사용하는 옵션인지는 별도로 확인한다.

기본값 근거: [Java 17 옵션 명세](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html), [Parallel의 기본 목표](https://docs.oracle.com/en/java/javase/17/gctuning/parallel-collector.html), [G1의 자동 조정](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html). 실행 환경 확인 예:

```bash
# 별도 JVM의 기본 선택값. 운영 JVM에 추가한 인수를 반영하지는 않는다
"$JAVA_HOME/bin/java" -XX:+PrintFlagsFinal -version 2>&1 \
  | grep -E 'InitialHeapSize|MaxHeapSize|RAMPercentage|MaxDirectMemorySize|ThreadStackSize|GCThreads|MaxGCPauseMillis|GCTimeRatio|G1HeapRegionSize|SoftMaxHeapSize|ZUncommitDelay'

# 실제 운영 JVM의 적용값을 우선 확인
"$JAVA_HOME/bin/jcmd" "$PID" VM.flags -all
```

`{default}`는 flag 기본값, `{ergonomic}`은 JVM 자동 선택값, `{command line}`은 명시 인수의 출처를 나타낸다. 실제 설정을 비교할 때는 같은 GC·힙·인식 CPU 조건을 사용한다. 미지정 기본값과 예제에 명시한 값을 섞어 기록하지 않는다.

### 3.5 값 변화에 따른 영향 분석 사례

아래는 **예상되는 방향과 확인 방법**이다. 성능 개선률을 실측한 결과가 아니며, 개선·악화의 크기는 부하와 생존 객체량에 따라 달라진다. 비교 중 다른 설정은 유지한다.

| 변경 예 | 기대 효과 | 비용·악화 가능성 | 채택 판단에 필요한 관찰 |
| --- | --- | --- | --- |
| `Xmx: 2g→4g` | 할당 여유 증가, 회수 빈도·공간 부족 완화 가능 | RSS/cgroup 사용 증가, 일부 수집의 정지 증가 가능. 누수 해결 효과는 없음 | GC 후 live set이 정착하는지, Full GC·OOM 감소, p99·처리량·메모리 한도 |
| `Xms: 1g→4g`, Xmx=4g 유지 | 힙 확장·페이지 확보에 따른 실행 중 변동 감소 가능 | 기동시간·초기 committed heap 증가, 메모리 반환 여지 감소. RSS는 pre-touch 여부에도 영향받음 | 준비 완료 시간·초기 RSS·급증 부하 p99·유휴 메모리 |
| `MaxRAMPercentage: 25→50→70` | 인식 메모리 8GiB라면 보정 전 최대 힙 약 `2→4→5.6GiB` | 남은 메모리 예산 약 `6→4→2.4GiB`. 네이티브·캐시·다른 프로세스가 이를 넘으면 커널 kill 가능 | 실제 MaxHeapSize·memory.current 최대·NMT·oom_kill 증가. Xmx 명시 시 이 계산으로 바뀌지 않음 |
| `InitialRAMPercentage: 1.5625→10` | 인식 메모리 8GiB라면 보정 전 초기 힙 약 `128MiB→819MiB`, 초기 확장 감소 가능 | 초기 메모리 부담 증가 | 실제 InitialHeapSize·워밍업 시간·기동 RSS. Xms 명시 시 우선순위 확인 |
| `MaxMetaspaceSize: 256m→512m` | 클래스 로딩 여유 증가, 한도 도달 완화 | 클래스 로더 누수가 있으면 더 늦게 실패할 뿐 원인은 유지. 즉시 512MiB를 할당하지 않음 | 로드 클래스·class loader 수, metaspace 사용·회수, OOM 사유 |
| `MaxDirectMemorySize: 256m→1g` | direct buffer 할당 한도·동시 I/O 여유 증가 | 네이티브 사용 예산 증가, 컨테이너 메모리 부족 위험 | direct BufferPool 사용·할당 대기·RSS·cgroup. 풀/JNI 등 추적 범위 확인 |
| `Xss: 1m→512k` | 플랫폼 스레드 1000개라면 스택 명목 예산 약 `1000MiB→500MiB` | 깊은 호출에서 StackOverflow 위험. 이 수치만큼 RSS가 절감된다는 뜻은 아님 | 최대 호출 깊이·오류·스레드 수·NMT Thread. guard page 등 추가 비용 고려 |
| G1 `MaxGCPauseMillis: 200→100` | 더 짧은 수집 정지를 추구 | young 축소·빈번한 회수로 GC CPU 증가·RPS 저하 가능. 실제 정지가 절반이 된다는 뜻은 아님 | GC 정지 p99와 요청 p99를 각각 비교, RPS·오류율·CPU |
| G1 `IHOP: 45→30` | 초기 또는 고정 기준에서 마킹을 앞당겨 공간 부족 완화 가능 | 동시 마킹 빈도·CPU 비용 증가 가능. adaptive 학습 후 지정 비율과 달라질 수 있음 | 마킹 시작/완료 시점·old 증가·evacuation 실패·앱 CPU |
| G1 `ReservePercent: 10→20`, Xmx=4g | 개념적인 안전 여유가 약 `0.4→0.8GiB`로 증가 | 같은 힙에서 앱이 할당에 활용할 여유 감소, 회수 빈도 증가 가능. 별도 물리 예약량으로 해석하지 않음 | evacuation 실패 감소 여부·Full GC·수집 빈도·p99 |
| G1 `RegionSize: 2m→4m` | humongous 경계가 약 `1→2MiB`, 일부 큰 객체의 분류 변경 | region 회수 단위 증가·공간 낭비 및 관리 비용 변화 | 실제 객체 헤더·정렬 포함 크기, humongous region 수·회수량·정지 |
| G1 `ParallelGCThreads: 4→8` | STW 작업의 병렬 처리 개선 가능 | 사용 가능 CPU가 적으면 동기화·스케줄링·quota throttling 증가 | STW 시간·실제 worker 수·프로세스 CPU·cpu.stat 증가량 |
| G1 `ConcGCThreads: 1→2` | 마킹 완료를 앞당겨 회수 여유 확보 가능 | 앱과 CPU 경쟁 증가, 요청 p99 악화 가능 | 마킹 소요·앱 RPS·p99·throttling. 스레드 수 2배가 속도 2배를 보장하지 않음 |
| Parallel `GCTimeRatio: 99→19` | GC 시간 목표가 `1%→5%`로 완화, 더 작은 힙 유지 가능 | GC에 더 많은 시간을 허용하므로 처리량 저하 가능 | 실제 GC 시간 비율·힙 footprint·RPS. 고정 CPU 할당 비율을 지정하는 기능은 아님 |
| Parallel `NewRatio: 2→3` | old 비중 증가, 장수 객체 여유 증가 | young 비중 약 `1/3→1/4`, young 회수 빈도 증가 가능 | young/old 실제 크기·승격량·GC 빈도. adaptive policy가 실제 크기를 조정할 수 있음 |
| Parallel `Xmn: 512m→1g`, Xmx 고정 | 단기 객체 할당 여유 증가, young GC 빈도 감소 가능 | 수집당 작업·정지 증가, old 공간 감소·승격 실패 위험 | young 정지·빈도·승격량·old 점유·Full GC |
| ZGC `SoftMaxHeapSize: 4g→3g`, Xmx=4g | 더 작은 힙 유지 노력, 메모리 사용 감소 가능 | 회수 빈도·GC CPU 증가 가능. 필요하면 여전히 4GiB 사용 | committed heap·RSS·GC 주기·할당 지연·p99. 강제 3GiB 제한으로 사용하지 않음 |
| ZGC `ZUncommitDelay: 300→60` | 유휴 힙의 OS 반환 자격이 빨라짐 | 반복 증감 부하에서 재커밋·페이지 확보 비용 증가 가능 | 유휴 RSS 감소·재유입 p99·commit/uncommit. Xms=Xmx이면 반환 여지 없음 |

증설 전에 제한의 종류를 구분한다. 힙 한도를 늘리는 것은 metaspace/direct buffer 한도를 직접 늘리는 조치가 아니며, OS/cgroup 상한이 그대로면 전체 메모리 예산을 다시 계산해야 한다.

### 3.6 로그·측정 예제의 수치 기본값과 영향

JVM 튜닝값과 진단 도구의 수집 조건을 구분한다. 수집 조건을 바꾸면 관찰 범위·부하·저장량도 바뀐다.

| 수치 | 미지정 기본값 / 예제값 | 값을 줄일 때 | 값을 늘릴 때 |
| --- | --- | --- | --- |
| GC 로그 `filecount` | 파일 출력 시 기본 `5`, 예제도 `5` | 과거 로그 보존 감소. **0은 회전 비활성**이므로 단순 축소와 다름 | 보존 범위·디스크 사용 증가 |
| GC 로그 `filesize` | 기본 `20M`, 예제도 `20M` | 회전 빈도 증가, 파일당 분석 범위 감소 | 회전 감소, 파일당 저장량 증가. 목표 크기는 근사값 |
| `jstat` interval/count | interval 미지정 시 단일 표본, interval 지정 후 count 미지정 시 종료 전까지 반복. 예제 `1000 10` | 간격 축소: 짧은 변화 포착·수집량 증가. 횟수 축소: 관찰기간 감소 | 간격 증가: 순간 변화 누락 가능. 횟수 증가: 장기 추이 확보·저장량 증가 |
| `pidstat` interval/count | 간격 미지정/0이면 부팅 이후 통계, 간격 지정 후 횟수 미지정 시 반복. 예제 `1 10` | 짧은 간격은 일시 변동 포착·수집 비용 증가 | 긴 간격은 변동 평균화, 많은 횟수는 관찰기간 증가 |
| JFR `duration` | 미지정 또는 `0s`는 자동 시간 종료 없음. 예제 `60s` | 짧은 구간은 분석 자료·저장량 감소, 드문 현상 누락 가능 | 긴 구간은 재현 포착 증가, 파일·수집 비용 증가 |
| thread dump 반복 | 자동 반복 기본값 없음. 예제 3회·5초 간격 | 횟수 감소: 지속성 판단 제한. 간격 감소: 잦은 덤프로 비용 증가 | 횟수 증가: 지속성 판단 개선·출력 증가. 간격 증가: 빠른 변화 누락 가능 |
| `perf -F` 및 수집 시간 | 예제 `99Hz`, `30초`; 기본 주파수는 perf 버전·이벤트·시스템 설정에 의존 | 표본·분석 정밀도 감소, 수집 비용 감소 | 표본·파일·CPU 비용 증가, 커널 제한에 의해 실제 주파수 조정 가능 |

근거: [통합 로깅 옵션](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#enable-logging-with-the-jvm-unified-logging-framework), [jstat 수집 간격](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jstat.html), [JFR 기록 조건](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html), [pidstat 수집 조건](https://man7.org/linux/man-pages/man1/pidstat.1.html).

## 4. 실행 설정 예제

Linux Bash 예제다. `/opt/jdk-17`, `/opt/app/app.jar`, `/var/log/myapp`는 배포 경로로 바꾼다. 앱 실행 사용자에게 로그 디렉터리 쓰기 권한과 충분한 저장 공간이 있어야 한다. JVM 옵션은 `-jar` 앞에 둔다.

### 4.1 Java 17 G1 비교 시작점

```bash
JAVA_HOME=/opt/jdk-17
"$JAVA_HOME/bin/java" \
  -Xms1g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  '-Xlog:gc*,safepoint:file=/var/log/myapp/gc-%p-%t.log:time,uptime,level,tags:filecount=5,filesize=20M' \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/myapp \
  -jar /opt/app/app.jar
```

처음에는 힙·정지 목표·관측 옵션으로 시작한다. 로그는 프로세스별 회전하며 총 디스크 상한은 정확히 `5×20M`으로 고정되지 않는다. 재기동마다 다른 파일 묶음이 생기므로 별도 보존 정책이 필요하다. Java 8의 `PrintGCDetails`, `PrintGCDateStamps`, `Xloggc` 대신 Java 9+에서는 위 통합 로깅을 사용한다. [JVM 통합 로깅](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#enable-logging-with-the-jvm-unified-logging-framework).

OOM heap dump는 Java 힙 분석 자료다. 커널 OOM kill, 모든 네이티브 메모리 부족, 디스크 부족 때 생성되는 것은 아니다. 덤프에는 요청 데이터·인증정보가 포함될 수 있으므로 접근 권한과 보존기간을 관리한다.

### 4.2 다른 GC와 비교

G1 전용 옵션을 제거한 별도 실행으로 비교한다. 로그·부하·환경은 동일하게 유지한다.

```bash
# Java 17: 처리량 중심 비교
"$JAVA_HOME/bin/java" -Xms1g -Xmx4g -XX:+UseParallelGC -jar /opt/app/app.jar

# Java 17: 비세대형 ZGC 비교
"$JAVA_HOME/bin/java" -Xms1g -Xmx4g -XX:+UseZGC -jar /opt/app/app.jar

# Java 21: 세대형 ZGC 비교. 실제 Java 21 설치 경로 사용
/opt/jdk-21/bin/java -Xms1g -Xmx4g -XX:+UseZGC -XX:+ZGenerational -jar /opt/app/app.jar

# Java 25: 세대형 ZGC
/opt/jdk-25/bin/java -Xms1g -Xmx4g -XX:+UseZGC -jar /opt/app/app.jar
```

기존 [Java JAR Control 사용법](../java-jar-control/README.md)으로 실행한다면 위 설정을 해당 `configure`의 메모리·GC·JVM 옵션 구역에 맞게 나누어 적용한다. 명령행과 환경 변수에 중복 옵션이 있는지 점검한다.

## 5. Linux 프로세스 점검 명령

### 5.1 준비 및 프로세스 식별

JDK 도구는 실행 JVM과 같은 버전·배포판을 우선 사용하고 같은 유효 UID/GID 및 해당 PID/mount namespace에서 실행한다. 컨테이너 PID와 호스트 PID는 다를 수 있다. 최소 JRE 이미지에는 진단 도구가 없을 수 있다.

```bash
JAVA_HOME=/opt/jdk-17
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/jcmd" -l
pgrep -af java

# 목록의 사용자·시작시간·명령행으로 대상을 확인하고 직접 지정
PID=12345
ps -ww -p "$PID" -o pid,ppid,user,lstart,etime,stat,nlwp,pcpu,pmem,rss,vsz,args
readlink -f "/proc/$PID/exe"
tr '\0' ' ' < "/proc/$PID/cmdline"
```

예제 PID는 실제 PID로 바꾼다. 이름 검색 결과를 그대로 여러 PID에 적용하지 않는다. `jcmd -l`은 다른 namespace의 JVM을 모두 찾지 못할 수 있다. 명령행에 민감한 인수가 있으면 수집 파일의 접근을 제한한다.

도구 패키지 예: procps/procps-ng(`ps`, `top`, `vmstat`, `free`), sysstat(`pidstat`, `mpstat`, `iostat`, `sar`), iproute2(`ss`), lsof, strace, perf, 실행 버전의 JDK. 패키지명·저장소·권한은 배포판에 따라 다르다.

### 5.2 상태·CPU·스레드

| 명령 | 점검 내용 | 해석 |
| --- | --- | --- |
| `ps -ww -p "$PID" -o pid,ppid,stat,etime,nlwp,pcpu,rss,vsz,args` | 상태·경과시간·스레드 수·CPU·메모리 | `pcpu`는 생존기간 평균. RSS/VSZ는 KiB, VSZ는 예약 가상주소도 포함 |
| `top -p "$PID"` | 프로세스의 구간별 변화 | 보통 CPU 100%가 논리 CPU 하나. 표시 모드·정규화 설정 확인 |
| `top -H -p "$PID"` | CPU가 높은 네이티브 스레드 | TID를 JVM thread dump의 nid와 대조 |
| `ps -L -p "$PID" -o pid,tid,psr,stat,pcpu,wchan:24,comm` | 스레드별 상태·대기 위치 | 한 순간의 상태이므로 여러 번 확인 |
| `pidstat -u -t -p "$PID" 1 10` | 1초 간격 스레드 CPU, 10회 | 높은 user CPU와 system CPU를 구분 |
| `pidstat -w -p "$PID" 1 10` | 자발적·비자발적 문맥 교환 | 락/대기·CPU 경쟁의 단서. 절대값만으로 원인 확정 불가 |
| `mpstat -P ALL 1 10` | CPU별 부하·iowait·steal | 특정 CPU 편중, 가상화 호스트 경쟁 확인 |
| `uptime` | 1·5·15분 load average | 실행 가능 작업과 일부 비중단 대기 포함. CPU 수·quota와 함께 판단 |

Linux `STAT`: `R` 실행/실행 대기, `S` 인터럽트 가능한 수면, `D` 비중단 수면(흔히 I/O), `T` 정지, `t` 추적 중 정지, `Z` 종료 후 부모 미회수 상태다. `S`는 정상 대기일 수 있고 `D`는 반복 관찰과 I/O 확인이 필요하다. `Z`는 JVM이 실행 중이라는 뜻이 아니다. [ps 매뉴얼](https://man7.org/linux/man-pages/man1/ps.1.html), [pidstat 매뉴얼](https://man7.org/linux/man-pages/man1/pidstat.1.html).

### 5.3 메모리·I/O·파일·네트워크

| 명령 | 점검 내용 | 해석·영향 |
| --- | --- | --- |
| `free -h` | 호스트 메모리·swap | `available` 확인. 컨테이너 제한은 별도 확인 |
| `vmstat 1 10` | 실행 대기열, swap in/out, CPU wait | 첫 줄은 부팅 이후 평균. 지속적인 `si/so` 증가와 응답 지연 대조 |
| `cat "/proc/$PID/status"` | VmRSS·VmSwap·Threads 등 | 프로세스 요약이며 힙만 나타내지 않음 |
| `cat "/proc/$PID/smaps_rollup"` | RSS·PSS·private/shared 요약 | 커널·권한에 따라 접근 불가. 큰 주소 공간에서 조회 비용 고려 |
| `pmap -x "$PID"` | 주소 매핑별 메모리 | reserved 주소 크기를 사용 중인 힙으로 해석하지 않음 |
| `pidstat -r -p "$PID" 1 10` | RSS 변화·minor/major fault | major fault는 저장장치 접근 단서이며 원인은 추가 조사 |
| `pidstat -d -p "$PID" 1 10` | 프로세스 디스크 읽기·쓰기 | 앱 로그·파일 I/O·덤프 시점과 대조 |
| `iostat -xz 1 10` | 장치별 지연·대기열·사용률 | 첫 보고는 부팅 이후. `%util=100`만으로 NVMe/병렬 장치 포화 확정 불가 |
| `df -h /var/log/myapp` / `df -i /var/log/myapp` | 용량·inode | 로그/덤프 생성 실패와 파일시스템 고갈 확인 |
| `ls "/proc/$PID/fd" \| wc -l` | 열린 FD 수의 근사치 | 시간별 증가와 `/proc/PID/limits`의 open files 한도 비교 |
| `cat "/proc/$PID/limits"` | 프로세스의 실제 자원 한도 | 현재 셸의 `ulimit`과 다를 수 있음 |
| `lsof -nP -p "$PID"` | 파일·소켓·삭제 후 열린 파일 | 출력량과 권한 확인. 삭제한 로그도 열린 동안 공간 점유 가능 |
| `ss -lntp` | TCP listen과 소유 프로세스 | 권한 부족 시 프로세스 정보 누락 |
| `ss -tinp` | TCP 연결·전송 상세 | 대상 PID/포트 확인 후 retrans·큐 증가 대조 |
| `journalctl -k --since '-30 min'` | 커널 OOM·I/O 오류 | 접근 권한 필요. 컨테이너 내부에서 호스트 로그 접근 불가 가능 |
| `journalctl -u myapp.service --since '-30 min'` | systemd 서비스 로그 | 실제 서비스명으로 변경 |

추가 해석 근거: [vmstat](https://man7.org/linux/man-pages/man8/vmstat.8.html), [iostat](https://man7.org/linux/man-pages/man1/iostat.1.html), [ss](https://man7.org/linux/man-pages/man8/ss.8.html).

### 5.4 컨테이너·cgroup v2

```bash
cat "/proc/$PID/cgroup"
findmnt -t cgroup2

# 확인한 cgroup2 마운트와 해당 프로세스 경로로 지정
# 아래는 호스트에서 /sys/fs/cgroup/myapp.slice에 배치된 예시
CGROUP_DIR=/sys/fs/cgroup/myapp.slice
cat "$CGROUP_DIR/cpu.max"
cat "$CGROUP_DIR/cpu.stat"
cat "$CGROUP_DIR/memory.current"
cat "$CGROUP_DIR/memory.max"
cat "$CGROUP_DIR/memory.events"
cat "$CGROUP_DIR/memory.stat"
cat "$CGROUP_DIR/cpuset.cpus.effective"
```

`/proc/PID/cgroup` 경로는 관찰자의 cgroup namespace와 mount root를 고려해 해석한다. 컨테이너 안에서 `/`로 보이는 경로를 호스트 경로에 단순 연결하지 않는다. cgroup v1은 파일명·단위가 다르다.

- `cpu.max`의 `200000 100000`은 주기당 CPU 시간 비율이 약 2 CPU에 해당한다. `max`는 해당 계층의 quota 제한 없음이다.
- `cpu.stat`의 `nr_throttled`, `throttled_usec`는 누적값이다. 측정 구간의 증가량을 보고 상위 계층 quota도 확인한다.
- `memory.current`/`memory.max`는 byte 기준이며 `max`는 해당 계층의 명시적 상한 없음이다. 상위 계층에 더 낮은 한도가 있을 수 있다.
- `memory.events`의 `high`, `max`, `oom`, `oom_kill` 증가를 비교한다. 그룹 통계이므로 JVM 하나의 사건으로 단정하지 않는다.
- `cpuset.cpus.effective`는 실제 사용 가능 CPU 집합이다. quota와 함께 봐야 유효 CPU 예산을 판단할 수 있다.

힙이 남는데도 응답 지연이 증가하면 CPU throttling 또는 cgroup 메모리 reclaim을 점검한다. [Linux kernel cgroup v2 문서](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html).

## 6. JVM 점검과 상세 자료 수집

명령 지원 여부와 영향은 실행 JVM에서 `jcmd "$PID" help` 및 `jcmd "$PID" help 명령`으로 먼저 확인한다. 낮은 영향도도 무부하는 아니다. attach가 꺼졌거나 권한/namespace가 다르면 실패할 수 있다.

### 6.1 우선 점검 명령

아래 표의 `jcmd`, `jstat`, `jfr`는 `$JAVA_HOME/bin`에 있는 같은 JDK 도구를 뜻한다.

| 명령 | 용도 | 영향·해석 |
| --- | --- | --- |
| `jcmd "$PID" VM.version` | 대상 JVM 버전 확인 | 낮음 |
| `jcmd "$PID" VM.command_line` | 실제 시작 인수 | 낮음. 민감정보 취급 |
| `jcmd "$PID" VM.flags -all` | 현재 JVM flag 값 | 낮음. 명시값·자동 선택값 구분 |
| `jcmd "$PID" GC.heap_info` | GC·힙 요약 | 중간, 전체 객체 분석 아님 |
| `jstat -gcutil -t "$PID" 1000 10` | 1초 간격 10회 GC 통계 | 비교적 낮음. GC별 지원·열 차이 확인 |
| `jstat -gc -t "$PID" 1000 10` | 용량·사용량 수치 확인 | 전통적 세대형 열의 capacity/used는 KiB, 시간은 초 |
| `jcmd "$PID" Thread.print -l` | 플랫폼 스레드 스택·락 | 중간, 스레드 수에 따라 출력·정지 비용 증가 |
| `jcmd "$PID" Compiler.codecache` | JIT code cache | 낮음. 코드 캐시 고갈 단서 |

`jstat -gcutil`의 `E`, `O`, `M`은 각 영역의 현재 capacity에 대한 사용률이며 `Xmx` 대비 전체 비율이 아니다. `YGC/FGC`, `YGCT/FGCT/GCT`는 누적값이므로 구간 차이로 읽는다. 예를 들어 60초 동안 GCT가 1.2초 증가했다면 해당 도구가 집계한 GC 시간 비율은 약 2%다. 동시 GC에서는 이를 그대로 전체 STW 비율이나 앱 정지 비율로 해석하지 말고 GC 로그/JFR로 확인한다. `FGC=0`도 정지가 없다는 뜻이 아니다.

`jstat` 출력은 GC·JDK에 따라 달라지고 비세대형 ZGC에 기존 young/old 열 의미를 강제로 적용할 수 없다. 고정 열 위치를 전제로 운영 파서를 만들지 않는다. [jstat 명세](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jstat.html), [jcmd 명세](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html).

### 6.2 고CPU 스레드와 thread dump 연결

```bash
top -H -p "$PID"

# 관찰한 Linux TID 예시. PID와 혼동하지 않는다
TID=12367
printf 'nid=0x%x\n' "$TID"

# 앱 사용자가 쓸 수 있는 디렉터리에서 실행
for i in 1 2 3; do
  "$JAVA_HOME/bin/jcmd" "$PID" Thread.print -l > "thread-${PID}-${i}.txt"
  sleep 5
done
```

16진수 `nid`를 검색해 지속해서 같은 계산을 하는지, 락·소켓·DB 대기인지 확인한다. Java `RUNNABLE`은 네이티브 I/O 대기를 포함할 수 있어 CPU 사용을 뜻하지 않는다. `BLOCKED`는 monitor 획득 대기이며 모든 종류의 락 대기를 포함하지 않는다. `WAITING/TIMED_WAITING`은 풀의 정상 대기일 수도 있다.

Java 21+ 가상 스레드는 OS TID와 1:1 대응하지 않는다. 위 절차는 플랫폼/캐리어 스레드를 식별하는 방법이다. 가상 스레드는 해당 JDK의 `Thread.dump_to_file` 지원과 도움말을 확인한다. [Java 21 진단 명령](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html).

### 6.3 JFR: CPU·할당·락·I/O를 같은 시간축으로 보기

```bash
# filename은 대상 JVM에서 보이고 쓰기 가능한 절대 경로
"$JAVA_HOME/bin/jcmd" "$PID" JFR.start \
  name=perf settings=profile duration=60s \
  filename=/var/log/myapp/perf-001.jfr

"$JAVA_HOME/bin/jcmd" "$PID" JFR.check

# 60초 기록 완료와 파일 생성 확인 후 실행
"$JAVA_HOME/bin/jfr" summary /var/log/myapp/perf-001.jfr
"$JAVA_HOME/bin/jfr" print \
  --events jdk.GarbageCollection,jdk.GCPhasePause,jdk.ExecutionSample \
  /var/log/myapp/perf-001.jfr
```

`settings=default`는 상대적으로 낮은 수집 비용, `profile`은 상세 분석용이다. 활성 이벤트·주기·부하에 따라 비용이 달라진다. JDK Mission Control에서 CPU sample, 할당 표본, monitor 대기, 파일·소켓 이벤트를 분석하되 활성화되지 않은 이벤트가 없는 것을 문제 부재로 해석하지 않는다. 부하 발생 구간·요청 p99·GC 시각을 맞추어 본다. [JFR 파일 도구](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jfr.html), [JFR.start 옵션](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html).

### 6.4 네이티브 메모리 추적(NMT)

```bash
# 다음 기동 때 추가. 기동 후 꺼진 NMT를 켤 수는 없다
# -XX:NativeMemoryTracking=summary

"$JAVA_HOME/bin/jcmd" "$PID" VM.native_memory summary scale=MB
"$JAVA_HOME/bin/jcmd" "$PID" VM.native_memory baseline

# 비교할 부하·시간 경과 후
"$JAVA_HOME/bin/jcmd" "$PID" VM.native_memory summary.diff scale=MB
```

`reserved`는 주소 예약, `committed`는 JVM 관점의 커밋이며 RSS와 같지 않다. `detail`은 더 세밀한 추적과 비용이 필요하다. NMT 자체에도 추적 비용이 있고 모든 외부 네이티브 라이브러리 할당을 추적하지 못한다. NMT가 안정적인데 RSS가 늘면 mmap·외부 allocator·공유 영역과 `/proc/PID/smaps_rollup`도 확인한다. [Oracle NMT 설명](https://docs.oracle.com/en/java/javase/21/vm/native-memory-tracking.html).

### 6.5 영향이 큰 상세 점검

아래 명령은 부하가 낮은 시점 또는 재현 환경에서 필요한 것만 실행한다. histogram/heap dump는 큰 힙에서 정지·디스크 사용이 클 수 있다.

```bash
"$JAVA_HOME/bin/jcmd" "$PID" help GC.class_histogram
"$JAVA_HOME/bin/jcmd" "$PID" help GC.heap_dump

# 클래스별 객체 수·크기. 전체 힙 검사 및 GC 영향 가능
"$JAVA_HOME/bin/jcmd" "$PID" GC.class_histogram > histogram-001.txt

# 기본적으로 Full GC를 요청하는 heap dump
"$JAVA_HOME/bin/jcmd" "$PID" GC.heap_dump /var/log/myapp/heap-001.hprof
```

`GC.heap_dump -all`은 사전 Full GC 요청을 생략하는 대신 도달 불가능 객체도 포함한다. 이 옵션도 덤프 정지 비용을 없애지 않는다. 힙 덤프를 Eclipse MAT 등의 도구에서 dominator tree·retained size·GC root 경로로 분석한다. 큰 객체가 많다는 사실만으로 누수라고 결론 내리지 않는다. [힙 검사 명령과 영향](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html).

추가 OS 분석이 필요하면 `strace -f -p "$PID" -c`로 syscall 통계를 잠시 수집하고 Ctrl+C로 수집을 끝낼 수 있으나 ptrace 비용이 크다. `perf record -F 99 -p "$PID" -g -- sleep 30`은 CPU 표본 분석 예이며 권한·커널 설정·JIT 심볼/스택 설정에 따라 정보가 제한된다. 기본 점검과 JFR로 좁힌 뒤 사용한다.

## 7. 증상별 분석 절차

| 증상 | 우선 수집 | 해석과 다음 조치 |
| --- | --- | --- |
| 프로세스 CPU 높음 | pidstat → top -H → 3회 thread dump → JFR | 앱 계산·직렬화·정규식·GC 스레드 구분. 할당 폭증이면 객체 생성 경로부터 개선 |
| CPU 낮고 p99 높음 | 스레드 덤프·ss·DB/풀 지표·iostat | 락, 풀 고갈, 외부 응답 대기, I/O 확인. 힙 증설이 직접 해법은 아닐 수 있음 |
| Young GC 빈번 | GC 로그·JFR 할당 표본·처리량 | 단기 객체 생성률·young 크기 확인. 요청 증가에 비례하는 정상 회수인지 비교 |
| Full GC·G1 evacuation 실패 | GC 로그의 원인·old 추이·humongous·cgroup | 생존량 증가, 마킹 지연, CPU 부족, 여유 공간 부족 구분. 캐시 한도·대형 배열·힙·IHOP를 근거로 조정 |
| 힙이 계속 증가 | 유사 부하의 회수 후 live set·histogram·heap dump | 캐시의 정상 정착과 누수 구분. 장기 유지 참조를 찾아 코드·캐시 정책 수정 |
| 힙 안정, RSS 증가 | smaps_rollup·NMT diff·스레드/DirectBuffer | 스택·메타데이터·직접 버퍼·JNI·allocator 관점 확인 |
| 컨테이너 종료·137 | memory.events·호스트 커널 로그·런타임 종료 사유 | 137만으로 OOM 확정 불가. SIGKILL 원인 확인. 커널 kill이면 Java 덤프 없을 수 있음 |
| 주기적 큰 지연 | safepoint·GC·스케줄 작업·CPU quota·swap | GC 정지와 다른 safepoint, 로깅/flush, throttling을 시간으로 대조 |

GC 로그에서 예를 들어 `1024M->256M(4096M)`은 수집 전 사용량 → 수집 후 사용량(해당 로그의 힙 용량)을 뜻한다. RSS나 네이티브 메모리 사용량이 아니다. `Pause Young`, `Concurrent Mark`, `Pause Full`, safepoint를 구분하고 동시 마킹 시간을 STW로 합산하지 않는다.

캐시·큐·배치 크기에 한도를 두고 객체 생존 기간과 할당을 줄이는 변경이 GC 옵션보다 효과적일 수 있다. 힙 부족·누수·CPU quota 문제를 GC 종류 변경만으로 해결하려 하지 않는다.

## 8. 측정 기록 양식

| 항목 | 기준 실행 A | 변경 실행 B |
| --- | --- | --- |
| 측정 시각·시간대·지속시간 | 기록 | 동일 조건 여부 기록 |
| JDK 배포판·버전 / 앱 버전 | 기록 | 기록 |
| CPU 수·quota / memory.max | 기록 | 기록 |
| GC / 전체 JVM 인수 | 기록 | 변경값 강조 |
| 워밍업·요청 구성·도착률·동시성 | 기록 | 기록 |
| 성공 RPS / 오류율 / 타임아웃 | 기록 | 기록 |
| p50 / p95 / p99 / 최대 지연 | 기록 | 기록 |
| 프로세스 CPU / quota throttling 증가량 | 기록 | 기록 |
| RSS·cgroup 사용량 최대 / GC 후 live set | 기록 | 기록 |
| GC 정지 p95·p99·최대 / Full GC 횟수 | 기록 | 기록 |
| 로그·JFR·덤프 위치 | 기록 | 기록 |
| 반복 실행 분산 / 채택·원복 사유 | 기록 | 기록 |

측정 중 생성한 `.jfr`, `.hprof`, 로그 파일은 크기와 민감정보를 고려해 운영 수집 경로에서 보관하며 소스 저장소에 추가하지 않는다.

## 9. 출처와 검증 범위

본문 링크는 Oracle JDK 도구·GC 문서와 Linux 매뉴얼·커널 문서다. 공식 문서도 개정되거나 다른 버전으로 연결될 수 있으므로 문서 제목의 버전과 실제 JVM 도움말을 함께 확인한다.

이 문서는 운영 절차와 실험 예제를 설명한다. 문서 구조·링크 경로·Bash 구문의 정적 점검 범위와 실제 운영 JVM에서의 성능 측정은 구분한다. 대상 서비스의 성능 수치, 최적 힙 크기, 정지시간 또는 무중단 진단을 보장하지 않는다.

작성 및 보완 시 Bash 예제 9개 블록의 구문, 상대 파일 링크·목차 앵커를 점검했다. WSL Ubuntu 24.04 / OpenJDK 17.0.20.1에서 축소 힙의 G1·Parallel·ZGC 옵션 인식과 기본값·자동 선택값을 비교했다. 실제 서비스 부하 측정과 Java 21·25 실행, 운영 attach·덤프 수집은 수행하지 않았다. 상세 기록은 [project.md](project.md)에 정리했다.
