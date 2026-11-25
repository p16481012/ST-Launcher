# 누를 수 있는 요소: 공통 규칙과 변경 내역

**현재 분류:** [후속 버튼 분류표 #1–48 및 단독 닫기 8곳](BUTTON-CLASSIFICATION.md). 아래 줄 번호와 변경 내역은 `ed553b1` 당시 직접 호출 85곳(일반 UI 83곳, 공통 작업 패널 2곳)의 1차 정리 기록이며, 후속 표가 우선합니다.

| 용도 | 표현 |
|---|---|
| 지금 실행 | Primary(주 동작), Outline(보조 실행), Danger(삭제). 글자가 배경·테두리 없이 남지 않게 합니다. |
| 목적지로 이동 | 제목과 ›가 있는 행. 외부라면 어떤 앱이 열리는지 안내합니다. |
| 목록이나 블록에 딸린 보조 이동 | 강조색 링크. 밑줄 없음. |
| 취소·나중에 | 주 버튼 옆의 테두리 없는 Quiet. 설명 창에서 닫기만 가능하면 Primary ‘확인’, 하위 화면은 뒤로 가기. |
| 화면 전체에 적용 | 상단 오른쪽의 굵은 본문색 글자. 밑줄 없음. 뒤로 등 상단 아이콘에는 접근성 이름을 둡니다. |

지금 할 수 없는 동작은 숨기거나 이유를 안내합니다. 입력값을 바꾸면 바로 진행할 수 있는 폼은 주 동작의 모양을 유지하고 필요한 조건을 알려줍니다. 스위치·라디오·체크박스·탭은 기본 선택 표시와 접근성 역할을 유지합니다.

## 1차 변경 이력 (현재 규격 아님)

| 원본 위치 | 동작 | 변경한 모양 |
|---|---|---|
| [AppearanceTextPreview.kt:38](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/AppearanceTextPreview.kt#L38) | 미리보기 문장 바꾸기 | 보조 링크 |
| [AppearanceTextPreview.kt:47](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/AppearanceTextPreview.kt#L47) | 기본 문장으로 | Outline |
| [BackupScopeSheet.kt:66](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/BackupScopeSheet.kt#L66) | 직접 선택 / 닫기 | 보조 링크 |
| [ExtensionManagerUi.kt:95](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ExtensionManagerUi.kt#L95) | 업데이트 | Outline |
| [ExtensionManagerUi.kt:114](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ExtensionManagerUi.kt#L114) | 휴지통으로 이동 | Danger |
| [ExtensionManagerUi.kt:135](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ExtensionManagerUi.kt#L135) | 닫기 | Outline |
| [FileManagementUi.kt:278](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L278) | 모두 선택·해제 | 상단 동작 |
| [FileManagementUi.kt:284](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L284) | 휴지통 | 상단 동작 |
| [FileManagementUi.kt:309](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L309) | 루트 경로 | 보조 링크 |
| [FileManagementUi.kt:320](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L320) | 상위 경로 | 보조 링크 |
| [FileManagementUi.kt:328](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L328) | 정렬 | 보조 링크 |
| [FileManagementUi.kt:333](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L333) | 새로 만들기 | Outline |
| [FileManagementUi.kt:349](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L349) | 가져오기 | Outline |
| [FileManagementUi.kt:364](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L364) | 선택 | Outline |
| [FileManagementUi.kt:458](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L458) | 내용 미리보기 | Outline |
| [FileManagementUi.kt:459](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L459) | 텍스트 수정 | 행 › |
| [FileManagementUi.kt:461](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L461) | 이름 변경 | 행 › |
| [FileManagementUi.kt:462](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L462) | 다른 앱으로 열기 | 외부 이동 행 › |
| [FileManagementUi.kt:463](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L463) | 휴지통으로 이동 | Danger |
| [FileManagementUi.kt:492](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L492) | 내용 미리보기 | Outline |
| [FileManagementUi.kt:557](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L557) | 닫기 | Outline |
| [FileManagementUi.kt:612](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L612) | 제안 이름 사용 | Outline |
| [FileManagementUi.kt:643](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L643) | 서버 끄고 편집 | Outline |
| [FileManagementUi.kt:644](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/FileManagementUi.kt#L644) | 닫기 | 저장 옆 Quiet / 단독 Outline |
| [LANSecuritySettingsDialog.kt:185](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LANSecuritySettingsDialog.kt#L185) | 취소 | Outline |
| [LanConnectionUi.kt:58](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LanConnectionUi.kt#L58) | QR 보기 | 행 › |
| [LanConnectionUi.kt:63](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LanConnectionUi.kt#L63) | 주소 새로고침 | Outline |
| [LauncherApp.kt:411](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L411) | 다시 시도 | Outline |
| [LauncherApp.kt:412](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L412) | 작업 기록 보기 | 행 › |
| [LauncherApp.kt:428](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L428) | 기록 보기 | 행 › |
| [LauncherApp.kt:736](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L736) | 안전 사본·휴지통 보기 | 행 › |
| [LauncherApp.kt:961](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L961) | Termux 열기 | 외부 이동 행 › |
| [LauncherApp.kt:1058](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L1058) | 설치 상태 다시 확인 | Outline |
| [LauncherApp.kt:1074](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L1074) | 설치 상태 다시 확인 | Outline |
| [LauncherApp.kt:1148](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/LauncherApp.kt#L1148) | 연결 확인 | Outline |
| [PreviousVersionUi.kt:82](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/PreviousVersionUi.kt#L82) | 다시 확인 | Outline |
| [ReviewFeatureUi.kt:41](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ReviewFeatureUi.kt#L41) | 백업 확인 | 행 › |
| [ReviewFeatureUi.kt:42](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ReviewFeatureUi.kt#L42) | 7일 뒤에 알림 | Outline |
| [ReviewFeatureUi.kt:53](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/ReviewFeatureUi.kt#L53) | 변경 내역 미리보기 | Outline (펼침/접기) |
| [RuntimeLibraryRepairSheet.kt:35](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/RuntimeLibraryRepairSheet.kt#L35) | 취소 | 동의 버튼 옆 Quiet |
| [RuntimeLibraryRepairSheet.kt:39](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/RuntimeLibraryRepairSheet.kt#L39) | 기록 보기 | 행 › |
| [RuntimeLibraryRepairSheet.kt:59](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/RuntimeLibraryRepairSheet.kt#L59) | 기술 정보 | Outline (펼침/접기) |
| [SafetyInventoryUi.kt:133](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SafetyInventoryUi.kt#L133) | 모두 선택·해제 | 상단 동작 |
| [SafetyInventoryUi.kt:157](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SafetyInventoryUi.kt#L157) | 점검·복구 | 보조 링크 |
| [SafetyInventoryUi.kt:170](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SafetyInventoryUi.kt#L170) | 다시 확인 | Outline |
| [SafetyInventoryUi.kt:221](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SafetyInventoryUi.kt#L221) | 더 보기 | Outline |
| [SelectiveRestoreUi.kt:78](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SelectiveRestoreUi.kt#L78) | 더 불러오기 | Outline |
| [SelectiveRestoreUi.kt:84](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/SelectiveRestoreUi.kt#L84) | 목록 다시 확인 | Outline |
| [StorageFailureSheet.kt:30](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/StorageFailureSheet.kt#L30) | 다시 시도 | Outline |
| [StorageFailureSheet.kt:31](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/StorageFailureSheet.kt#L31) | 작업 기록 보기 | 행 › |
| [StorageManagementUi.kt:29](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/StorageManagementUi.kt#L29) | 저장 공간·백업 정리 | Outline (펼침/접기) |
| [StorageManagementUi.kt:60](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/StorageManagementUi.kt#L60) | 고정·메모 | 행 › |
| [TavernTrashScreen.kt:64](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TavernTrashScreen.kt#L64) | 서버 끄고 복구할 항목 선택 | Outline |
| [TavernTrashScreen.kt:72](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TavernTrashScreen.kt#L72) | 다시 시도 | Outline |
| [TavernTrashScreen.kt:87](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TavernTrashScreen.kt#L87) | 복구 | Outline |
| [TermuxEnvironmentUi.kt:37](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TermuxEnvironmentUi.kt#L37) | Termux가 갑자기 종료될 때 | Outline (펼침/접기) |
| [TermuxEnvironmentUi.kt:51](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TermuxEnvironmentUi.kt#L51) | Termux 앱 정보 | 외부 이동 행 › |
| [TermuxEnvironmentUi.kt:54](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TermuxEnvironmentUi.kt#L54) | 배터리 설정 | 외부 이동 행 › |
| [TermuxEnvironmentUi.kt:58](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/TermuxEnvironmentUi.kt#L58) | Android 12 이상 공식 도움말 | 외부 이동 행 › |
| [V2BackupScreen.kt:180](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L180) | 다시 시도 | Outline |
| [V2BackupScreen.kt:219](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L219) | 전체 관리 | 보조 링크 |
| [V2BackupScreen.kt:330](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L330) | 그룹 제목 보조 이동 | 보조 링크 |
| [V2BackupScreen.kt:420](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L420) | 공유 | Outline |
| [V2BackupScreen.kt:421](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L421) | 삭제 | Danger |
| [V2BackupScreen.kt:464](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L464) | 전체 선택·해제 | Outline |
| [V2BackupScreen.kt:465](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2BackupScreen.kt#L465) | 선택 항목 삭제 | Danger |
| [V2Chrome.kt:252](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2Chrome.kt#L252) | 알림의 실행 동작 | 대비 테두리 Outline |
| [V2Chrome.kt:254](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2Chrome.kt#L254) | 알림 닫기 | 대비 테두리 Outline |
| [V2HomeDashboard.kt:94](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2HomeDashboard.kt#L94) | 최근 백업 전체 | 보조 링크 |
| [V2HomeDashboard.kt:280](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2HomeDashboard.kt#L280) | 닫기 | 주 동작 옆 Quiet |
| [V2SettingsDetails.kt:243](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L243) | 주소 새로고침 | Outline |
| [V2SettingsDetails.kt:357](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L357) | 배터리 설정 | 외부 이동 행 › |
| [V2SettingsDetails.kt:358](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L358) | Termux 앱 정보 | 외부 이동 행 › |
| [V2SettingsDetails.kt:359](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L359) | Termux 공식 안내 | 외부 이동 행 › |
| [V2SettingsDetails.kt:451](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L451) | 라이선스 목록 | 보조 링크 |
| [V2SettingsDetails.kt:454](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SettingsDetails.kt#L454) | 닫기 | Outline |
| [V2SetupScreen.kt:101](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SetupScreen.kt#L101) | 이전으로 | 상단 뒤로 화살표 |
| [V2SetupScreen.kt:111](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SetupScreen.kt#L111) | 건너뛰기 | 상단 동작 |
| [V2SetupScreen.kt:175](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SetupScreen.kt#L175) | 기존 설치 가져오기 | Outline (펼침/접기) |
| [V2SetupScreen.kt:178](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SetupScreen.kt#L178) | 전체 설치 ZIP에서 복원 | 이동 행 › |
| [V2SetupScreen.kt:262](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2SetupScreen.kt#L262) | 공통 SetupQuiet | 제거, 실행은 Outline·외부 이동은 행 › |
| [V2ToolsScreen.kt:160](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2ToolsScreen.kt#L160) | 전체 n개 | 보조 링크 |
| [V2ToolsScreen.kt:301](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/V2ToolsScreen.kt#L301) | 도구 목록 | 보조 링크 |
| [components/OperationCancelControl.kt:43](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/components/OperationCancelControl.kt#L43) | 작업 중단 | Outline (중단 요청 후 상태 안내) |
| [components/WorkProgressLog.kt:57](https://github.com/p16481012/ST-Launcher/blob/ed553b18cf5ba5889fc81b3a83c49054a69c748a/app/src/main/java/app/tavernbridge/launcher/ui/components/WorkProgressLog.kt#L57) | 기록 보기·접기 | Outline |

## 직접 호출 밖에서 함께 고친 곳

- 기존 아이콘은 글자 버튼 정리 대상이 아닙니다. 홈의 서버 종료·정보, 파일·로그의 더보기, 검색어 지우기, 로그 맨 아래 이동·복사는 원래 그림과 위치를 유지하며 화면 읽기용 이름을 제공합니다. 원래 아이콘과 글자가 함께 있던 항목은 둘 다 유지합니다.
- 파일·진단·도구·앱 정보·외부 앱 이동: 까만 글자만 남는 곳을 링크나 › 행으로 변경합니다. 삭제나 복구에 이미 있던 확인·차단 조건은 우회하지 않습니다.
- 홈의 ‘대화 열기’는 브라우저 안내를 유지하고, ‘서버 시작 중’은 누를 수 없는 진행 상태입니다. 재시작·백업·로그는 원래의 한 줄 아이콘 바로가기입니다.
- 바로가기·위젯 요청이 거절되어 확인 버튼이 없으면 ‘닫기’는 단독 Outline입니다. 시트의 드래그 손잡이·바깥 영역·시스템 뒤로 가기는 화면을 닫는 기본 탐색 동작으로 유지합니다.
- 기본 브라우저 선택은 라디오 행 하나당 한 개의 터치 대상입니다. 실행할 앱이 없으면 닫기만 표시합니다.

## 검증 재현

- 저장소 루트에서 `python scripts/check_action_affordances.py`. 지정한 래퍼 파일 밖의 호출 및 직접 import(별칭 포함)도 거부합니다.
- [Android 화면 테스트](../.github/workflows/android-visual.yml)는 원래 화면과 공통 동작 예시를 캡처한 뒤 `native-v2-phone`, `native-v2-tablet` 아티팩트의 `grayscale/`에 같은 화면의 무채색 PNG를 보관합니다. 이미지 생성만으로 모든 터치 대상이 식별된다고 판정하지 않습니다.
