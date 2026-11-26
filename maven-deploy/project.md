# Maven 배포 설정 구성

- `settings-dev.xml`과 `settings-prod.xml`은 각각 개발·운영 프로필을 자동 활성화합니다. 각 프로필은 배포 플러그인에 SNAPSHOT 및 릴리스 업로드 경로를 전달합니다.
- 설정 파일마다 두 저장소 ID를 `<servers>`에 등록하고 해당 환경의 인증 정보를 환경 변수에서 읽습니다.
- 기존 `pom.xml`의 `package` 설정이 생성한 기본 산출물, 소스 JAR 등 첨부 산출물과 POM을 `deploy`가 함께 업로드합니다. [실행 방법](README.md)
