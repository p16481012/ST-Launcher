# 정식 빌드와 배포

- 정식 패키지: `app.tavernbridge.stlauncher`
- 최초 버전: `versionCode 1`, `versionName 0.1.0`
- 현재 소스 버전: `versionCode 3`, `versionName 0.3.0`
- 개발용 패키지: `app.tavernbridge.stlauncher.debug`
- 기존 비공개 테스트 프로젝트의 키와 Git 이력은 이 저장소에 포함하지 않습니다.

## 빌드 검증

JDK 17과 Android SDK 36에서 실행합니다.

```sh
./gradlew --no-daemon test lint assembleDebug assembleRelease
```

CI의 `st-launcher-release-unsigned` 아티팩트는 검증용 미서명 APK이며 설치할 수 없습니다. CI에는 운영 서명키를 제공하지 않습니다.

## 서명

운영 키·비밀번호는 저장소 밖에서 보관하고 별도 위치에 백업합니다. 비밀번호를 명령 인자나 로그에 직접 기록하지 않습니다.

Android SDK Build Tools의 `apksigner`를 사용합니다. 아래 환경 변수는 로컬에서만 설정합니다.

```sh
apksigner sign --ks "$ST_RELEASE_KEYSTORE" --ks-key-alias st-launcher-release \
  --ks-pass env:ST_RELEASE_STORE_PASSWORD --key-pass env:ST_RELEASE_KEY_PASSWORD \
  --out ST-Launcher-0.3.0.apk app-release-unsigned.apk
apksigner verify --verbose --print-certs ST-Launcher-0.3.0.apk
```

Gradle release 출력은 이미 정렬된 APK입니다. 별도로 zipalign을 적용한다면 반드시 서명 전에 실행하고, 서명 후에는 APK를 수정하지 않습니다. 서명 인증서 SHA-256은 첫 정식 배포 이후 동일하게 유지해야 합니다.

## 게시 전 확인

1. CI의 셸 테스트·단위 테스트·lint·release/R8 빌드가 모두 성공했는지 확인합니다.
2. 서명 검증, 정식 패키지·버전, 디버깅 비활성화를 확인합니다.
3. 서명된 APK를 실기기에 설치해 Termux 권한·연결과 서버 시작·중지·브라우저 열기를 확인합니다.
4. GitHub 릴리스 초안에 APK와 SHA-256을 첨부한 뒤 확인이 끝나면 공개합니다.

정식판과 과거 테스트판은 다른 앱입니다. 런처 설정·권한은 다시 지정할 수 있지만 Termux 내부 설치와 데이터는 별도로 유지됩니다. 두 런처에서 동시에 관리 작업을 실행하지 마세요.
