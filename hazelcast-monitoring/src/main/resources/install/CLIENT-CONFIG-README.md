# Hazelcast client 연결 설정 선택

설치가 아래 고유 client 설정 파일을 함께 생성한다. 서버 구성에 맞는 파일 하나를 선택하고 실제 cluster-name/주소/인증/TLS를 수정한다.

| 서버/연결 방식 | 선택 파일 | 수정할 항목 |
| --- | --- | --- |
| TCP 주소 접속(기본) | `hazelcast-client-tcp.yml` | cluster-name, cluster-members의 접속 가능한 TCP 주소 |
| 실제 UDP multicast 검색 | `hazelcast-client-multicast.yml` | cluster-name, group/port(서버도 같은 Discovery SPI 필요) |
| 서버가 일반 member multicast join으로 cluster 구성 | `hazelcast-client-member-seed.yml` | cluster-name, 접속 가능한 TCP seed 주소 하나 이상 |
| 서버/client가 Discovery SPI를 함께 사용 | `hazelcast-client-discovery-strategies.yml` | cluster-name, discovery-strategies class 및 properties |
| 제공된 hazelcast-host-udp.yml 서버 | `hazelcast-client-apim-cluster-lookup.yml` | 같은 호스트에서 127.0.0.1:16709 접속, 실제 바인딩 포트 확인 |

multicast 파일은 TCP seed 없이 실제 UDP 검색을 수행하는 MulticastDiscoveryStrategy 설정이다. 서버도 같은 Discovery SPI strategy/group/port를 사용해야 한다. discovery-strategies 파일은 SPI 확장용이며 기본 예제는 동일 multicast strategy다. 일반 member multicast join만 사용하는 서버는 주소 없이 자동 검색할 수 없으므로 별도 member-seed 파일을 사용한다.

## 적용 방법

### 제공된 APIM 서버에 접속

제공된 서버 설정은 cluster-name이 `apim-cluster-lookup`, 인터페이스가 `127.0.0.1`, 시작 포트가 `16709`다. 명시적인 multicast는 false, auto-detection은 true이며 client는 서버의 실제 TCP 주소로 접속한다. `instance-name: apim-hz-master`는 서버 프로세스 이름이므로 client에 복사할 필요가 없다.

```yaml
monitor:
  cluster-id: apim-cluster-lookup
  client-config: ./hazelcast-client-apim-cluster-lookup.yml
```

```sh
./collect.sh --monitor.cluster-id=apim-cluster-lookup --monitor.client-config=./hazelcast-client-apim-cluster-lookup.yml
```

서버가 loopback에만 바인딩하므로 모니터링을 같은 호스트에서 실행한다. 서버 포트 auto-increment가 true이므로 16709가 사용 중이면 실제 포트가 증가할 수 있다. 서버 시작 로그를 확인해 client 파일의 포트를 맞춘다. 파일 이름의 udp만으로 Discovery SPI 구성을 선택하지 않는다. 수집/보고서에 동일 cluster-id를 사용한다.

### 다른 profile 선택

`application.yml`의 `monitor.client-config`를 사용할 파일의 경로로 지정한다. 새 설치 기본값은 TCP다.

```yaml
monitor:
  cluster-id: prod-a
  client-config: ./hazelcast-client-tcp.yml
  # UDP 검색: ./hazelcast-client-multicast.yml
  # 일반 multicast 서버에 TCP seed 접속: ./hazelcast-client-member-seed.yml
  # Discovery SPI: ./hazelcast-client-discovery-strategies.yml
```

선택한 파일을 수정한 뒤 Windows는 `collect.bat`, Linux는 `./collect.sh`를 실행한다. 일회성 선택은 CLI 경로로 덮어쓸 수 있다.

```sh
./collect.sh --monitor.client-config=./hazelcast-client-multicast.yml
./collect.sh --monitor.client-config=./hazelcast-client-discovery-strategies.yml
```

Windows도 같은 인수를 collect.bat에 전달한다. 상대 경로는 script 설치 디렉터리 기준이다. report/init/install은 Hazelcast client 파일을 읽지 않는다. 환경별 monitor.cluster-id는 수집과 보고서에서 같은 값으로 사용한다.

기존 `hazelcast-client.yml`은 호환용 TCP 설정으로 계속 생성한다. application.yml에서 경로를 생략한 직접 실행의 기본 경로도 이 파일이다. 새 설치는 명시적인 TCP 파일을 선택한다. 재설치 시 모든 기존 설정/안내서를 보존하고 없는 파일만 생성하므로, 기존 application.yml의 선택 경로는 직접 변경한다.

기본 client 설정은 수정 가능한 예제이며 설치 과정에서 서버에 접속하지 않는다. Discovery SPI를 다른 strategy로 변경할 경우 필요한 plugin JAR을 포함하는 runtime 배포를 별도로 준비해야 한다.

이전 multicast 파일이 cluster-members만 포함한다면 TCP seed 예제다. 기존 파일은 보존되므로 백업/이름 변경한 후 새 설치로 UDP 예제를 생성한다. 원래 용도가 일반 member multicast 서버의 TCP 접속이었다면 새 member-seed 파일을 선택한다.
