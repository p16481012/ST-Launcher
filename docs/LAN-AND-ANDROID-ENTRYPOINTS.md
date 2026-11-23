# Android 진입점·LAN 설정

버전·릴리스는 변경하지 않습니다. 바로가기·타일·위젯은 앱을 여는 수단이며 백그라운드에서 Termux 명령을 직접 실행하지 않습니다.

## Android 진입점 통합 계약

- `LauncherEntryIntents.consume(intent): LauncherEntryRequest?`를 MainActivity의 최초 Intent와 `onNewIntent`에서 호출합니다. ID는 요청별 UUID이며, 소비 후 Intent의 extras를 지웁니다. 알 수 없는 action·ID는 버립니다.
- `OPEN`은 런처 화면만 엽니다. 설정/서버 상태가 불완전해도 화면을 열 수 있으며 서버는 실행하지 않습니다.
- `START`·`STOP`은 앱이 전경이고 현재 Termux 권한·설치 확인이 완료된 상태에서만 사용자 확인창을 띄웁니다. 확인 직전에 `LauncherEntryPolicy.evaluate`를 다시 적용합니다. 작업 중에는 거부하고, 복구 대기 중에는 시작을 거부하되 복구 준비를 위한 종료는 허용합니다.
- 외부 앱도 exported EntryActivity에 알려진 요청을 보낼 수 있으므로 Intent 자체는 실행 권한이 아닙니다. 저장된 요청을 복원해도 자동 실행하면 안 됩니다.
- 타일은 잠금 해제를 거쳐 `OPEN`을 전달합니다. Android 14 이상은 `startActivityAndCollapse(PendingIntent)`를 사용합니다. 위젯은 시작·종료·열기 Activity PendingIntent만 가집니다. 주기적 상태 조회·서비스 실행·자동 시작이 없습니다.
- 고정 바로가기의 `REQUESTED`는 OS에 추가 요청을 보냈다는 뜻이며 사용자가 추가를 완료했다는 뜻이 아닙니다.

UI: `LauncherEntrySettingsCard()`.

## LAN 주소·허용 목록

- `LanAllowedNetwork.parse`는 숫자 IPv4/IPv6 리터럴과 CIDR만 읽습니다. DNS 조회를 하지 않습니다.
- 허용 범위: IPv4 `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`; IPv6 `::1/128`, `fc00::/7`. CIDR 전체가 이 범위에 들어야 합니다.
- 공인 주소, `*`, `0.0.0.0/0`, `::/0`, 도메인, IPv4-mapped IPv6, scope ID가 필요한 link-local 주소는 새 입력에서 거부합니다. IPv4의 모호한 선행 0 표기도 거부합니다.
- 호스트 비트가 있는 CIDR은 네트워크 주소로 정규화하고 저장 전 화면에 실제 범위를 보여줍니다. `127.0.0.1`·`::1`은 항상 유지합니다.
- 기존 비호환 허용 목록을 자동 삭제하지 않습니다. 외부 접속 ON 저장은 안전한 범위로 수정해야 합니다. OFF로 바꾸는 경우에만 `allowedNetworks=null`로 기존 목록을 보존할 수 있습니다.
- `LanEndpoint.parse(host, port)`는 사설망 주소만 URL로 만듭니다. 루프백·자격증명·URL 경로·쿼리는 공유 QR에 들어갈 수 없습니다.
- `LanAddressInspector.inspect(port)`는 인터페이스를 읽기만 합니다. IO dispatcher에서 명시적 조회에 사용하며 시작 때 자동 호출할 필요는 없습니다. 표시 주소는 Wi-Fi 여부나 실제 연결 성공을 보장하지 않습니다.

UI: `LanConnectionCard(externalEnabled, endpoints, onRefresh, onConfigure, onCopy)`.

## 인증 설정과 비밀값 전달

`LANSecuritySettingsDialog(current: LanSecuritySnapshot, busy, onDismiss, onConfirm)`는 다음 계약을 사용합니다.

1. 기본 외부 접속은 OFF입니다. 변경 범위·HTTP의 한계를 읽고 체크해야 적용할 수 있습니다.
2. 아이디·암호는 `remember`에만 보관합니다. 화면 회전/닫기 시 사라지며 SavedState·ViewModel 상태·Intent·런처 설정·진단·QR에 넣지 않습니다. Dialog는 `SecureFlagPolicy.SecureOn`입니다.
3. 인증을 새로 켤 때는 아이디·8자 이상 암호가 필요하며 공백만 있는 암호는 거부합니다. 이미 켜진 경우 두 칸을 모두 비우면 기존 값을 유지합니다. 기존 `perUserBasicAuth` 사용자는 단일 계정 설정으로 바꾸지 않습니다.
4. 적용 결과인 `LanSecurityChange`와 그 `EphemeralBasicAuthCredentials`의 소유권은 콜백 소비자에게 넘어갑니다. 소비자는 성공·실패·취소 모두 `finally`에서 `close()` 해야 합니다. 문자열화 결과는 마스킹되며 암호 CharArray는 close 시 0으로 덮습니다. 관리형 런타임의 모든 임시 String까지 메모리 소거를 보장한다는 뜻은 아닙니다.
5. 실제 인증에는 SillyTavern `config.yaml`의 `basicAuthMode`/`basicAuthUser` 저장이 필요합니다. 이 사실과 설정을 포함한 백업의 보호 필요성을 UI에 명시합니다. “암호를 설정 파일에 저장하지 않는다”는 정책은 백업 암호/런처 환경설정에 적용되며 SillyTavern의 인증 설정 자체에는 해당하지 않습니다.

Termux는 debug/verbose 설정에 따라 Intent 또는 stdin을 기록할 수 있습니다. 따라서 자격증명 JSON을 명령 인자나 RUN_COMMAND stdin으로 보내지 않습니다.

- manager 배포 및 필요한 서버 중지를 먼저 마칩니다.
- `LanSecurityPayload.encode(change)`가 최대 16KiB JSON 바이트를 만듭니다.
- `OneShotSecretInput.start(payload)`는 **127.0.0.1만** 임의 포트로 열고 256-bit 임의 토큰을 확인합니다. 헤더는 2KiB 이하, 연결 시도는 최대 16회, 유효 요청은 한 번만, 전체 수명은 15초 이하입니다. 만료·취소·종료 시 소켓과 보유 바이트를 정리합니다.
- Termux에는 포트와 단회 토큰만 보냅니다. 비밀 전달기는 `use`로 닫습니다. `delivered`는 전송 참고 상태이며 실제 저장 성공 여부는 manager의 결과를 따릅니다.
- manager wrapper는 작업 잠금·복구 상태·서버 종료를 확인하고, 입력 port가 1~65535 및 token이 64자리 소문자 hex인지 확인해야 합니다.

```sh
curl --disable --noproxy '*' --proto '=http' \
  --connect-timeout 2 --max-time 12 --max-filesize 16384 --fail --silent \
  "http://127.0.0.1:$port/$token" |
  bash "$helper" "$ST_HOME" "$LAUNCHER_HOME"
```

`pipefail`을 사용합니다. `--disable`은 첫 curl 옵션으로 두어 사용자 curlrc를 읽지 않게 하고, 프록시·리다이렉트·원격 호스트를 사용하지 않습니다. 성공한 helper 뒤에는 작업 취소가 이미 저장된 결과를 실패로 덮어쓰지 않도록 완료 경계를 기록합니다.

`lan-security.sh`는 stdin을 fd3으로 읽고 Node 스크립트와 분리합니다. 16KiB 엄격 JSON·실제 설치의 YAML 모듈·IP/CIDR를 재검사합니다. 설정 파일은 1MiB 이하의 일반 파일만, symlink/hardlink는 거부합니다. nofollow로 읽고 revision·해시를 다시 확인한 뒤 동일 폴더에서 atomic rename합니다. 먼저 `backups/network-settings/config-날짜.yaml`에 O_EXCL 사본을 작성하므로 기존 사본을 덮지 않습니다. 같은 초에 이미 사본이 있으면 안전하게 실패하고 재시도할 수 있습니다. 원시 YAML 오류·경로·자격증명은 stderr에 출력하지 않습니다.

정상 성공: `saved=1`, `durability_warning=0`. 설정 교체 후 디렉터리 fsync만 실패하면 이미 저장된 작업을 실패로 바꾸지 않고 `saved=1`, `durability_warning=1`을 반환합니다. 실패는 `error_code=LAN_SETTINGS_*` 또는 `OPERATION_CANCELLED`입니다.

helper 종료 코드는 manager의 진단·작업 내역과 같은 원인으로 분류합니다: 90 `LAN_SETTINGS_INPUT_INVALID`, 91 `LAN_SETTINGS_PATH_UNSAFE`, 92 `LAN_SETTINGS_CHANGED`, 93 `LAN_SETTINGS_TRANSFER_FAILED`, 94 `LAN_SETTINGS_CONFIG_INVALID`, 95 `LAN_SETTINGS_PER_USER_AUTH`, 96 `LAN_SETTINGS_CREDENTIALS_REQUIRED`, 97 `LAN_SETTINGS_SAVE_FAILED`. 취소는 130 `OPERATION_CANCELLED`를 유지합니다. JSON 구문 오류는 90, 빈 전송·입력 읽기 오류는 93, YAML 검사 오류는 94이며 원시 오류 내용은 출력하지 않습니다.

인증을 켠 서버의 HTTP 401은 프로세스가 응답한다는 뜻일 수 있습니다. 건강 상태 검사에서 인증 요청을 단순 서버 오류로 처리하지 않아야 하며, 이를 위해 런처에 암호를 저장하면 안 됩니다.

## QR 라이브러리

`com.google.zxing:core:3.5.4` 한 개만 사용합니다. QR은 로컬에서 생성하며 웹 QR 서비스·카메라 권한·네트워크 호출을 추가하지 않습니다. 크기는 256~1024px로 제한합니다. Apache-2.0의 공식 LICENSE/NOTICE를 `assets/licenses/zxing-3.5.4.txt`에 포함합니다.

- [Maven Central 실제 POM](https://central.sonatype.com/artifact/com.google.zxing/core?smo=true)
- [ZXing 3.5.4 소스](https://github.com/zxing/zxing/tree/zxing-3.5.4)
- [Android TileService](https://developer.android.com/reference/android/service/quicksettings/TileService)
- [Android 바로가기](https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts)
- [Android 위젯](https://developer.android.com/develop/ui/views/appwidgets)
- [SillyTavern 공식 설정 설명](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/Administration/config-yaml.md)

## 검증 기록과 남은 실제 기기 확인

- 로컬 XML 파싱·diff·Bash 구문 검사 통과.
- 실제 YAML 2.9.1을 사용한 `scripts/test-lan-security.cjs`: Windows 격리 환경 15개 회귀 그룹 통과. 잘못된 주소/JSON/YAML/자격증명, 단일 계정과 계정별 인증, 동시 변경, 하드링크, 공간 부족, 사본 충돌, 빈 전송·입력 읽기 오류, 종료 코드 일치, 취소와 원본 보존을 검사했습니다.
- Linux CI는 추가로 symlink와 저장 완료 후 fsync 경계를 검사합니다. Windows 통과와 구분해야 합니다.
- 새 JUnit 25개(진입 정책·주소/CIDR·인증 모델·QR 왕복·단회 전송·비밀 JSON)는 구현했지만 이 로컬 환경에는 Kotlin/Android 테스트 실행기가 없어 아직 실행하지 않았습니다. Android 컴파일·Lint와 함께 CI로 확인합니다.
- 실제 Android 8/12/14 이상에서 바로가기 추가 거절, 잠금 상태 타일, 위젯 삭제·복원, 작업 중 재탭, 프로세스 재시작 후 자동 실행 없음, 200% 글꼴/가로 화면, 암호 입력 중 회전·화면 캡처, 잘못된/만료된 토큰, 실제 같은 LAN에서 QR·인증 연결을 별도로 확인해야 합니다.

```sh
npm install --prefix "$RUNNER_TEMP/st-lan-yaml" --ignore-scripts --no-package-lock --no-audit --no-fund yaml@2.9.1
ST_TEST_YAML_ROOT="$RUNNER_TEMP/st-lan-yaml" node scripts/test-lan-security.cjs
```
