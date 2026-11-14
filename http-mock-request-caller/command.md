http mock request caller

# 개요
http request mock 요청을 전송하고 응답에 대한 결과를 수집하는 application

# 개발제한
- spring boot 최신 버전 사용
- standalone execute web application
- http request 전송은 netty 사용
- http-payload-generator 을 사용하여 random request generate
- script 처리는 jexl 사용

# 개발 내역
- http 요청 시나리오 관리
  - endpoint base url
  - 요청 path 목록 관리
    - request url (optional)
      - 미지정시 endpoint base url 을 사용함
    - request path
    - request payload generate type 
      - http-payload-generator 에서 정의한 요소를 array 로 지정함
      - 각 지정 요소는 조합하여 사용함
  - 개별 로그 생성
    - 본 시나리오 수행하면서 발생하는 각 worker 와 control thread 에서 발생하는 로그를 별도 파일로 작성
    - 파일은 rolling 기능지원 size / max file 생성 limit / gzip 압축 여부 설정
    - 활성화 여부 지정
  - 실행 worker 설정
    - max worker size
    - max worker size 가지 ramp up 시간 (초단위)
- http 요청 시나리오 실행 현황 및 결과 조회
  - 시나리오별 결과 조회
  - 실행중인 시나리오 진행상황 조회
- 실행 level 별 전/후 처리 script 기능
  - 시나리오, path, worker, request 개별 지정
  - 스크립스 수행 절차
    - manager worker
      - 시나리오 전처리 수행
        - execute worker 생성 / 및 실행
      - 시나리오 후처리 수행
    - execute worker
      - worker 전처리 수행
        - 대상 요청 목록 iterate
          - 요청 전처리 수행
          - 요청 수행
          - 요청 후처리 수행
- script context level 및 접근 정보
  - 시나리오
    - 실행하는 시나리오 정보의 접근
  - 요청
    - 실행하는 요청에 대한 정보접근
  - worker 
    - 요청 실행 thread 정보접근
    - map 형태의 자료구조에 정보 set/get
  - 단위실행 
    - 요청,응답에 대한 header, body 정보 get/set

- ui 구성
  - 시나리오
    - CRUD
    - 실행목록
      - 시간/제목 별 검색 조건 필요
  - 시나리오 실행결과
    - 시나리오 실행 히스토리 조회
    - 실행결과 상세 조회
    - 실행결과 리포트 생성 및 download : html 형태

