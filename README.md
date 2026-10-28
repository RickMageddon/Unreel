# Unreel

An Android app that removes Instagram Reels:

- **Hides the Reels tab button.** An opaque overlay covers the button in Instagram's bottom bar and swallows taps on it.
- **Closes Reels when they open.** If a Reels viewer opens anyway (from the feed, Explore, a profile or a DM), Unreel immediately goes back. On the Reels tab itself it switches to the home feed.

Both features can be switched on and off in the app.

## How it works

Unreel is an [Accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service). It reads Instagram's on-screen view tree, looks for the Reels tab (`clips_tab`) and the Reels viewer (`clips_viewer_*`), and reacts to them. It needs no root, does not modify Instagram, and has no internet permission, so it can't send any data anywhere.

## Install

1. Download `Unreel.apk` from the [latest release](../../releases/latest). Every push builds and publishes a new release automatically. Or build it yourself:
   ```sh
   ./gradlew assembleRelease
   # → app/build/outputs/apk/release/app-release.apk
   ```
2. Install the APK on your phone (allow installing from unknown sources).
3. Open Unreel → **Open Accessibility settings** → *Installed/Downloaded apps* → **Unreel – Reels blocker** → turn it on.
   - On Android 13+ the switch can be greyed out for sideloaded apps. Open **Settings → Apps → Unreel → ⋮ → Allow restricted settings**, then try again.
4. Open Instagram: the Reels button is gone.

Some phones (Xiaomi, Huawei, Samsung with aggressive battery saving) kill accessibility services in the background. If Unreel stops working, set its battery usage to *Unrestricted*.

## Updating without uninstalling

Android only installs an update if it is signed with the same key as the installed app. Without setup, each automatic build gets a new throwaway key, so you have to uninstall the old version first. To fix that, create a key once and store it as repository secrets (Settings → Secrets and variables → Actions):

```sh
keytool -genkeypair -keystore release.keystore -alias unreel -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Unreel"
base64 -w0 release.keystore   # → value for KEYSTORE_BASE64
```

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | output of the `base64` command |
| `KEYSTORE_PASSWORD` | the password you chose in `keytool` |
| `KEY_ALIAS` | `unreel` |

## Limitations

- Instagram changes its internal view ids from time to time. If the Reels tab shows up again after an Instagram update, the ids in `ReelsBlockerService.kt` need updating (there is a fallback that looks for a bottom-bar button labelled "Reels", which only works with an English UI).
- Videos that play inline in the home feed are not blocked, only the full-screen Reels viewer.
