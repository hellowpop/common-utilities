# Linux Java 성능 튜닝·측정 문서 구성

- 작성일: 2026-11-06
- 기준: 프로젝트 Java 17, Linux, HotSpot 계열 JVM
- 보완 범위: Java 8의 GC 로그 차이, Java 21·25의 ZGC 차이, cgroup v2
- 산출물: [README.md](README.md)

## 구성

| 구성 | 내용 |
| --- | --- |
| 측정 방법 | 워밍업, 부하 조건, 처리량·백분위 응답시간·오류율, 전후 비교 |
| GC 선택 | Serial·Parallel·G1·ZGC·Shenandoah의 목적과 비용 |
| 설정 비교 | 힙·세대·정지 목표·GC 스레드·메모리 반환 옵션의 기본값·자동 결정 기준, 값 변화의 효과·비용·검증 지표 |
| 실행 예제 | Java 17 G1 기본 실험, Parallel·ZGC 비교, 로그·OOM 자료 수집 |
| 상태 점검 | Linux 프로세스·CPU·메모리·I/O·네트워크·cgroup, JVM 진단 명령 |
| 분석 절차 | 고CPU, 응답 지연, 힙/RSS 증가, OOM, JFR·스레드 덤프 분석 |
| 검증 및 출처 | 공식 JDK·Linux 문서, 환경별 검증 한계 |

## 적용 원칙

보편적인 최적값을 제시하지 않고 예제값과 실제 기본값을 구분한다. 실행 JVM의 버전·배포판·리소스 한도를 먼저 확인한다. 진단 명령은 예상 영향과 출력 해석을 함께 설명한다.

독립 문서 디렉터리이며 Java 클래스·Maven 모듈·자동 실행 스크립트는 추가하지 않는다. 기존 `java-jar-control`에서 JVM 옵션을 구성할 때도 참고할 수 있다.

## 검증 기록

- 2026-11-06 push 준비: Linux Java 성능 문서와 루트 project.md의 해당 변경 이력만 develop에 반영한다. push 전 Markdown 상대 링크·목차·코드 블록, Bash 예제 9개 구문과 공백 오류 재점검을 통과했다. 다른 모듈 및 기존 stage의 POJO 문서는 이번 커밋 범위에서 제외한다.

- 2026-11-06 보완: README 3.4~3.6에 수치형 옵션 기본값, 변경 영향 사례, 로그·진단 수집 조건을 추가했다. WSL OpenJDK 17.0.20.1에서 G1·Parallel·ZGC별 `PrintFlagsFinal`을 대조했다. 비교 JVM 조건은 Xms64m/Xmx128m/ActiveProcessorCount=4이며, 해당 환경의 자동 선택값을 공통 기본값으로 사용하지 않았다. Bash 예제 9개 블록의 구문·상대 링크를 점검했다. 영향 분석은 예상 방향이며 실제 서비스 성능 측정 결과가 아니다.

- 2026-11-06: Markdown 코드 블록 짝, 상대 파일 링크, README 목차 앵커, Bash 예제 8개 블록의 `bash -n` 검사 통과.
- WSL Ubuntu 24.04 / OpenJDK 17.0.20.1에서 64/128MiB 힙의 `java -version`으로 G1·Parallel·ZGC 및 주요 비교 옵션 인식 확인. G1의 정지 목표 200ms, reserve 10%, 초기 IHOP 45%, adaptive IHOP 활성 기본값 확인.
- `git diff --check` 통과. 운영 프로세스 attach, JFR·덤프 생성, Java 21·25 실행, 서비스 부하 성능 측정은 수행하지 않았다.
