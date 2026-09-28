# QuietBuzz: technical notes

How QuietBuzz works under the hood, setup details, and known tradeoffs. For the short user guide, see the [README](../README.md).


Silences vibration for every app's notifications except an allowlist, while keeping them fully
visible in the shade. Sound is left to the phone's own ringer mode rather than forced off by the
app - see "How it works" below for why. Built for a Galaxy Z Fold (One UI) coming from
iPhone-style per-app notification control, without touching Do Not Disturb.

Package: `io.quietbuzz.app`. Kotlin + Jetpack Compose, no cross-platform framework: every core
capability here (CompanionDeviceManager, NotificationListenerService's channel APIs) is an
Android-only platform surface with no Flutter/React Native plugin, so a native app is the
simplest option, not a more complex one.

## How it works

QuietBuzz only ever changes one field on another app's notification channel: whether it vibrates
(`enableVibration(false)` + clearing the vibration pattern). Importance and sound are deliberately
left alone. That's a narrower scope than an earlier version of this app had, for two concrete
reasons found the hard way:

- Forcing a channel's `importance` down to `IMPORTANCE_LOW` reliably suppresses sound (it's
  documented Android behavior, and confirmed on-device: Samsung's own per-category settings page
  shows "무음"/Silent purely from lowered importance). But some apps - observed on WeChat - serve
  their notification sound as a private `content://` URI that only they can grant read access to.
  Once QuietBuzz overwrote that field to force silence, there was no way to set the original sound
  back; the URI grant only exists when the owning app itself sets it.
- Vibration is not tied to importance at all, in either direction. `IMPORTANCE_LOW` alone does not
  stop vibration (confirmed on a real device - an earlier version relied on this and vibration
  kept happening), and separately, explicitly re-enabling vibration at `IMPORTANCE_LOW` does still
  vibrate (also confirmed on-device). The two are independent Android settings; only vibration is
  the thing this app actually needs to control, since Android has no native per-app toggle for it.

Given that, sound suppression is left entirely to the phone's own ringer mode instead of being
forced by the app: **vibrate mode already plays no notification sound system-wide, for every app,
with no channel changes needed.** If the ringer mode is Normal/Sound, non-allowlisted apps *will*
play sound - QuietBuzz does not suppress it. This is an intentional product decision, not a
limitation to fix: it avoids ever touching the `sound` field again (so the WeChat-style
irrecoverable loss can't recur for any app), at the cost of sound suppression only holding while
the phone is already in vibrate mode.

Only two kinds of caller can change another app's channel state from outside that app at all: the
single system-wide Notification Assistant role, or a `NotificationListenerService` whose app also
holds a `CompanionDeviceManager` association. This is documented directly on the official API
reference for
[`NotificationListenerService.updateNotificationChannel`](https://developer.android.com/reference/android/service/notification/NotificationListenerService):
the caller must have a companion device association or be the notification assistant, or the call
throws `SecurityException`.

### What QuietBuzz can and can't see in Samsung's Settings UI

Samsung's One UI notification settings for an app have (at least) two distinct levels, and
QuietBuzz only has access to one of them:

- **알림 카테고리 (per-category/per-channel settings)** - this is the standard Android
  `NotificationChannel` object (importance, sound, vibration), and it's the only thing QuietBuzz
  can read or write. Confirmed accurate on-device: toggling importance/vibration through the app
  is correctly reflected on this page, and vice versa.
- **App-level "알림 허용" (Allow notifications)** - this is Android 13's `POST_NOTIFICATIONS`
  runtime permission. Turning it off hides the app's notifications entirely and leaves channel
  importance untouched. QuietBuzz can read it (and skips such apps) but can't change it.
- **Per-category on/off switch** - this is the standard channel block, stored as
  `IMPORTANCE_NONE`. QuietBuzz reads it and skips those channels.
- **The app-level top "소리 및 진동" (Sound and vibration) toggle** - this is a Samsung-only UI
  element with no public API and no backing field on `NotificationChannel`. Confirmed on-device
  that it does **not** reflect real channel state: it stayed "on" even when every category was
  independently set to fully off, both via QuietBuzz and via manually editing every category by
  hand in Settings. The most likely explanation is that it's a write-only control that just
  remembers whether it was personally tapped, not a live summary of anything underneath it.
  QuietBuzz has no way to read or change this toggle, and it should not be trusted as a signal of
  whether silencing actually worked - check the per-category page instead.

The companion association is used purely as a permission token here - package name + a device's
Bluetooth MAC address, recorded by the system. No Bluetooth connection is held afterwards, no
code runs on the paired device, and the device doesn't need to be present again. `AssociationRequest`
is built with no device profile (no `WATCH`, no `AUTOMOTIVE_PROJECTION`), since none of those
Samsung-defined roles are needed.

Once granted, the listener stays bound as long as notification access is on - this is the normal
lifecycle for any notification listener (event-driven callbacks, no polling, no foreground-service
icon), not a background process in the battery-drain sense. Two things feed it work automatically,
plus a manual button for everything else:

1. **`onListenerConnected`**: runs a full silence pass over every installed app on first connect
   (covers boot, a fresh grant, or any reconnect).
2. **`PackageAddedReceiver`**: fires on new installs, silencing that one app within seconds.
3. **Run now** (Status tab): a manual full pass, for anything the above two don't catch - most
   notably an *existing* app adding a new notification channel later (e.g. a feature update). This
   is a deliberate scope cut: catching that live would need a callback
   (`onNotificationChannelModified`) whose only non-obvious wrinkle is confirming it can't fire
   itself into a loop, which was judged not worth the added surface area versus just noticing a
   loud notification and tapping the button. There's also no background nightly job - if you want
   periodic coverage, wire up a Samsung Routine (or similar) to open the app on a schedule; that's
   an external, optional choice rather than something the app manages itself.

## Setup

1. Open this folder in Android Studio (File > Open). Let it sync - this repo intentionally does
   not ship a `gradlew`/Gradle wrapper jar, since I can't produce a valid binary jar by hand;
   Android Studio creates/repairs the wrapper automatically on first sync.
2. Build and install on the phone. Samsung Auto Blocker may need to be off during install of a
   sideloaded APK.
3. **Settings > Apps > QuietBuzz > (top-right menu) > Allow restricted settings** - required
   before a sideloaded app can be granted notification access at all.
4. Open QuietBuzz. The first screen explains each permission before asking for it. Tap
   **Open notification access settings** and enable QuietBuzz (Special access > Notification
   access). This screen comes back on launch whenever a required permission is missing.
5. Still on that screen, tap **Link a device** and pick any nearby device from the system chooser (an AirPods case in
   pairing mode works well since it's easy to spot in the list, but it doesn't matter which device
   you pick - nothing about it is used afterwards). No hardware handy? From a computer with `adb`:
   ```
   adb shell cmd companiondevice associate 0 io.quietbuzz.app 00:11:22:33:44:55
   adb shell cmd companiondevice list 0
   ```
   (Any MAC works - it doesn't have to be a real nearby device. This is a testing-only command
   and may not exist on every build/OEM.)
6. Optionally allow QuietBuzz's own notifications (only used for the "re-link your device" alert),
   then tap **Continue** and **Run now** on the Status tab.
7. **Settings > Apps > QuietBuzz > Battery > Unrestricted**, so a new install isn't delayed too
   much by One UI's power management before `PackageAddedReceiver` can react.

## Allowlist

Defaults (best-effort, not independently verified on a real device - confirm in
Settings > Apps and adjust in the Allowlist tab if any are wrong):

| App | Package |
|---|---|
| Slack | `com.Slack` |
| PagerDuty | `com.pagerduty.android` |
| Samsung Phone | `com.samsung.android.dialer` |
| Samsung Messages | `com.samsung.android.messaging` |
| KakaoTalk | `com.kakao.talk` |

The Allowlist tab lets you search installed apps and check/uncheck them directly, filtered by
default to apps that look like they have notifications turned on. Checking an app restores its
original vibration from the backup right away; unchecking silences it right away, without waiting
for the next Run now. Each row's **Settings** button opens that app's notification page in
Samsung's Settings, for flipping the app-level "소리 및 진동" switch by hand (QuietBuzz can't).

The app's language (English/Korean) can be set independently of the phone's in
**Settings > Apps > QuietBuzz > Language**.

### Export / Import

The Allowlist tab's **Export** / **Import** buttons write/read a small JSON file (allowlist +
the "include system apps" toggle only, not device-local pass history) through the system file
picker. Choosing Google Drive as the destination in that picker uploads it there directly -
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

- Sound is only suppressed while the phone's ringer mode is already Vibrate or Silent - QuietBuzz
  never forces it off itself (see "How it works"). Switching the phone to Normal/Sound ringer mode
  means non-allowlisted apps will play sound again, by design.
- The app-level "소리 및 진동" toggle in Samsung's Settings does not reflect real state and can't be
  read or controlled by QuietBuzz - see "What QuietBuzz can and can't see" above. Only the
  per-category page is reliable.
- An existing app adding a *new* notification channel later (e.g. after an app update) isn't
  caught automatically - there's no live handling for that, only the next manual **Run now**.
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
  time - flagged here rather than fixed, since a real fix needs a product decision about which
  action should win, not just a mechanical change.

## Restore all vs. Reset all to defaults

Both are on the Status tab.

- **모두 복원 (Restore all)** puts every channel back to the state saved in the backup the first time
  QuietBuzz changed it, then clears the backup. A channel's backup entry is never overwritten
  afterwards, so it always holds the pre-QuietBuzz state.
- **모두 기본값으로 초기화 (Reset all to defaults)** ignores the backup. For every app, allowlisted or
  not, it raises LOW categories to DEFAULT, turns vibration back on, and gives categories with no
  sound the default notification sound. MIN (알림 최소화), turned-off categories, and custom
  ringtones are left alone. Then it clears the backup.

Use Reset when the backup itself is wrong. That happened with backups made by early builds:
they recorded LOW as the "original" for nearly every channel, so Restore all put every category
back to 무음. The catch is that categories an app made LOW on purpose (music playback, download
progress) get raised too. After either one, tap **Run now** to turn vibration off again for
non-allowlisted apps.

A sound file private to one app (WeChat's ringtone, for example) can't be written back by any
other app. If an early build cleared one, the only way to get it back is to uninstall and
reinstall that app, which deletes its local data.

## Debug tab

The Debug tab edits one app's channels directly (importance, sound, vibration), bypassing the
silence pass and its backup, and shows each channel's real importance level. It's kept as a
diagnostic tool. Changes made there are not backed up, so **Restore all** can't undo them.

## Backlog

Empty. Everything previously listed here (onboarding screen, per-app language, allowlist
restore/silence, per-app settings shortcut) is built.
