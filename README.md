# QuietBuzz

[English](#english) | [한국어](#한국어) | [Website](https://wonjoonseol-ws.github.io/quietbuzz/) | [Download](https://github.com/wonjoonSeol-WS/quietbuzz/releases/latest)

## English

Keep your phone in vibrate mode, and only the apps you pick will buzz. Every other app's notifications still show up, just without vibration. No Do Not Disturb needed.

<p>
  <img src="docs/images/onboarding-en.png" width="240" alt="Permission screen">
  <img src="docs/images/status-en.png" width="240" alt="Status tab">
  <img src="docs/images/allowlist-en.png" width="240" alt="Allowlist tab">
</p>

### Install

1. On your phone, download the APK from [Releases](https://github.com/wonjoonSeol-WS/quietbuzz/releases/latest) and open it.
2. Go to **Settings > Apps > QuietBuzz**, tap the top-right menu, then **Allow restricted settings**.
3. Open QuietBuzz and follow the first screen: turn on notification access and link a device.
4. Pick the apps that should still vibrate in the **Allowlist** tab, then tap **Run now**.

### Buttons

- **Run now**: turns off vibration for every app not on your allowlist.
- **Restore all**: undoes what QuietBuzz changed.
- **Reset all to defaults**: turns sound and vibration back on for every app. Use it if apps are still muted after Restore all.
- **Allowlist**: checking an app turns its vibration back on right away. Unchecking turns it off.

### Good to know

- Sound follows your phone's ringer mode. In vibrate mode no app makes sound. In sound mode every app does.
- QuietBuzz changes the categories inside each app, not the **Sound and vibration** switch at the top of Samsung's settings. That switch may still look on. That's normal.
- New apps are handled automatically when you install them.
- QuietBuzz never actually connects to the device you link.

<img src="docs/images/explainer-en.png" width="240" alt="What QuietBuzz changes">

More detail: [Technical notes](docs/TECHNICAL.md)

## 한국어

폰을 진동 모드로 두면, 직접 고른 앱만 진동합니다. 나머지 앱의 알림도 그대로 표시되고, 진동만 꺼집니다. 방해 금지 모드는 필요 없습니다.

<p>
  <img src="docs/images/onboarding-ko.png" width="240" alt="권한 설명 화면">
  <img src="docs/images/status-ko.png" width="240" alt="상태 탭">
  <img src="docs/images/allowlist-ko.png" width="240" alt="허용 목록 탭">
</p>

### 설치

1. 폰에서 [Releases](https://github.com/wonjoonSeol-WS/quietbuzz/releases/latest)의 APK를 내려받아 여세요.
2. **설정 > 애플리케이션 > QuietBuzz**에서 오른쪽 위 메뉴를 누르고 **제한된 설정 허용**을 선택하세요.
3. QuietBuzz를 열고 첫 화면의 안내대로 알림 접근 권한을 켜고 기기를 연결하세요.
4. **허용 목록** 탭에서 계속 진동할 앱을 고르고 **지금 실행**을 누르세요.

### 버튼

- **지금 실행**: 허용 목록에 없는 모든 앱의 진동을 끕니다.
- **모두 복원**: QuietBuzz가 바꾼 것을 되돌립니다.
- **모두 기본값으로 초기화**: 모든 앱의 소리와 진동을 다시 켭니다. 모두 복원 후에도 앱이 무음이라면 이걸 쓰세요.
- **허용 목록**: 앱을 체크하면 바로 진동이 돌아오고, 해제하면 바로 꺼집니다.

### 알아두면 좋은 점

- 소리는 폰의 벨소리 모드를 따릅니다. 진동 모드에서는 어떤 앱도 소리를 내지 않고, 소리 모드에서는 모든 앱이 소리를 냅니다.
- QuietBuzz는 앱 안의 카테고리를 바꿉니다. 삼성 설정 맨 위의 **소리 및 진동** 스위치는 바꾸지 않아서, 켜진 채로 보일 수 있어요. 정상입니다.
- 새로 설치한 앱은 자동으로 처리됩니다.
- 연결한 기기에 실제로 접속하지는 않습니다.

<img src="docs/images/explainer-ko.png" width="240" alt="QuietBuzz가 바꾸는 것">

자세한 내용: [Technical notes](docs/TECHNICAL.md) (영어)
