# QuietBuzz

Silences sound + vibration for every app's notifications except an allowlist, while keeping them
fully visible in the shade. Built for a Galaxy Z Fold (One UI) coming from iPhone-style
per-app notification control, without touching Do Not Disturb.

Package: `io.quietbuzz.app`. Kotlin + Jetpack Compose, no cross-platform framework: every core
capability here (CompanionDeviceManager, NotificationListenerService's channel APIs) is an
Android-only platform surface with no Flutter/React Native plugin, so a native app is the
simplest option, not a more complex one.

## How it works

Android's `NotificationChannel.IMPORTANCE_LOW` shows a notification with no sound and no
vibration -- exactly "visible but silent." Only two kinds of caller can change another app's
channel importance from outside that app: the single system-wide Notification Assistant role, or
a `NotificationListenerService` whose app also holds a `CompanionDeviceManager` association. This
is documented directly on the official API reference for
[`NotificationListenerService.updateNotificationChannel`](https://developer.android.com/reference/android/service/notification/NotificationListenerService):
the caller must have a companion device association or be the notification assistant, or the call
throws `SecurityException`.

The companion association is used purely as a permission token here -- package name + a device's
Bluetooth MAC address, recorded by the system. No Bluetooth connection is held afterwards, no
code runs on the paired device, and the device doesn't need to be present again. `AssociationRequest`
is built with no device profile (no `WATCH`, no `AUTOMOTIVE_PROJECTION`), since none of those
Samsung-defined roles are needed.

Once granted, the listener stays bound as long as notification access is on -- this is the normal
lifecycle for any notification listener (event-driven callbacks, no polling, no foreground-service
icon), not a background process in the battery-drain sense. Two things feed it work automatically,
plus a manual button for everything else:

1. **`onListenerConnected`**: runs a full silence pass over every installed app on first connect
   (covers boot, a fresh grant, or any reconnect).
2. **`PackageAddedReceiver`**: fires on new installs, silencing that one app within seconds.
3. **Run now** (Status tab): a manual full pass, for anything the above two don't catch -- most
   notably an *existing* app adding a new notification channel later (e.g. a feature update). This
   is a deliberate scope cut: catching that live would need a callback
   (`onNotificationChannelModified`) whose only non-obvious wrinkle is confirming it can't fire
   itself into a loop, which was judged not worth the added surface area versus just noticing a
   loud notification and tapping the button. There's also no background nightly job -- if you want
   periodic coverage, wire up a Samsung Routine (or similar) to open the app on a schedule; that's
   an external, optional choice rather than something the app manages itself.

## Setup

1. Open this folder in Android Studio (File > Open). Let it sync -- this repo intentionally does
   not ship a `gradlew`/Gradle wrapper jar, since I can't produce a valid binary jar by hand;
   Android Studio creates/repairs the wrapper automatically on first sync.
2. Build and install on the phone. Samsung Auto Blocker may need to be off during install of a
   sideloaded APK.
3. **Settings > Apps > QuietBuzz > (top-right menu) > Allow restricted settings** -- required
   before a sideloaded app can be granted notification access at all.
4. Open QuietBuzz, grant the Bluetooth/notification permission prompts, then tap
   **Open notification access settings** and enable QuietBuzz (Special access > Notification
   access).
5. Tap **Link a device** and pick any nearby device from the system chooser (an AirPods case in
   pairing mode works well since it's easy to spot in the list, but it doesn't matter which device
   you pick -- nothing about it is used afterwards). No hardware handy? From a computer with `adb`:
   ```
   adb shell cmd companiondevice associate 0 io.quietbuzz.app 00:11:22:33:44:55
   adb shell cmd companiondevice list 0
   ```
   (Any MAC works -- it doesn't have to be a real nearby device. This is a testing-only command
   and may not exist on every build/OEM.)
6. Tap **Run now**.
7. **Settings > Apps > QuietBuzz > Battery > Unrestricted**, so a new install isn't delayed too
   much by One UI's power management before `PackageAddedReceiver` can react.

## Allowlist

Defaults (best-effort, not independently verified on a real device -- confirm in
Settings > Apps and adjust in the Allowlist tab if any are wrong):

| App | Package |
|---|---|
| Slack | `com.Slack` |
| PagerDuty | `com.pagerduty.android` |
| Samsung Phone | `com.samsung.android.dialer` |
| Samsung Messages | `com.samsung.android.messaging` |
| KakaoTalk | `com.kakao.talk` |

The Allowlist tab lets you search installed apps and check/uncheck them directly, filtered by
default to apps that look like they have notifications turned on.

### Export / Import

The Allowlist tab's **Export** / **Import** buttons write/read a small JSON file (allowlist +
the "include system apps" toggle only, not device-local pass history) through the system file
picker. Choosing Google Drive as the destination in that picker uploads it there directly --
there's no Drive API integration in the app, just the standard Storage Access Framework, so no
Google sign-in is needed. Use this to carry your allowlist to a reinstall or a second device:
export on the source, then Import and pick the same file on the destination.

## Verification

```
adb shell dumpsys notification | grep -A 15 -i "notification listeners"   # live listeners
adb shell dumpsys activity services io.quietbuzz.app                      # binding client should be "system"
adb shell dumpsys companiondevice                                         # association present
adb shell dumpsys activity processes io.quietbuzz.app                     # process state
```

## Known tradeoffs

- An existing app adding a *new* notification channel later (e.g. after an app update) isn't
  caught automatically -- there's no live handling for that, only the next manual **Run now**.
  New app installs are still caught within seconds via `PackageAddedReceiver`.
- The very first notification from a newly-installed app can still arrive loud if its channel is
  created and posted to in the same instant the OS delivers the install broadcast. Everything
  after that first one is silenced.
- If the CompanionDeviceManager association is ever revoked (e.g. you manually remove it in
  Settings > Connected devices), the app posts a notification asking you to re-link, and passes
  no-op until you do.
- If the listener is momentarily unbound when a new install or a manual button fires, the action
  is queued as a single "pending action" slot and a rebind is requested; a second trigger arriving
  before that reconnect happens overwrites the first (last-write-wins, no merge/priority policy).
  Low-probability in practice, since the listener stays bound the overwhelming majority of the
  time -- flagged here rather than fixed, since a real fix needs a product decision about which
  action should win, not just a mechanical change.
