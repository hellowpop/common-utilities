# Maven 배포 저장소 전환

같은 `pom.xml`로 빌드하면서 Maven의 `-s`에 넘기는 설정 파일로 배포 환경을 선택합니다. Maven 3.9.11의 기본 JAR 배포 플러그인인 Maven Deploy Plugin 3.1.2를 기준으로 작성했습니다.

1. [개발용 설정](settings-dev.xml)과 [운영용 설정](settings-prod.xml)의 `example.com` 예시 주소를 해당 환경의 업로드 URL로 바꿉니다.
2. 개발용은 `MAVEN_DEV_USERNAME`과 `MAVEN_DEV_PASSWORD`, 운영용은 `MAVEN_PROD_USERNAME`과 `MAVEN_PROD_PASSWORD`를 실행 환경이나 CI 비밀 변수에 설정합니다.
3. 프로젝트 루트에서 배포할 환경의 명령을 실행합니다. 아래 예제는 같은 모듈을 사용하고 `-s` 파일만 바꿉니다.

   ```powershell
   # 개발 저장소에 배포
   mvn -s maven-deploy/settings-dev.xml -f char-sequence-similarity/pom.xml '-DskipTests' deploy

   # 운영 저장소에 배포
   mvn -s maven-deploy/settings-prod.xml -f char-sequence-similarity/pom.xml '-DskipTests' deploy
   ```

`-SNAPSHOT` 버전은 `altSnapshotDeploymentRepository`, 릴리스 버전은 `altReleaseDeploymentRepository`에 지정한 저장소로 배포됩니다. 값의 형식은 `저장소ID::URL`이며, 인증에는 같은 ID의 `<servers><server>` 항목을 사용합니다. 형식은 Maven Deploy Plugin 3.x 기준이며, 2.x에서는 `저장소ID::default::URL`을 사용합니다. [Maven Deploy Plugin 매개변수](https://maven.apache.org/plugins-archives/maven-deploy-plugin-3.2.0/deploy-mojo.html)

`-s`는 사용자 설정 파일을 지정합니다. 기존 `~/.m2/settings.xml`이나 프로젝트 루트의 `settings.xml`에서 의존성 다운로드에 사용하는 `<mirrors>`, `<proxies>` 또는 인증 항목이 있다면 선택한 설정 파일의 해당 섹션에 병합합니다. `<mirrors>`는 다운로드 경로를 제어하고, 이 샘플의 배포 URL은 프로필 속성에서 지정합니다. [Maven Settings Reference](https://maven.apache.org/settings.html)
