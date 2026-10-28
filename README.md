# Unreel

An Android app that removes Instagram Reels:

- **Hides the Reels tab button.** An opaque overlay covers the button in Instagram's bottom bar and swallows taps on it.
- **Closes Reels when they open.** If a Reels viewer opens anyway (from the feed, Explore, a profile or a DM), Unreel immediately goes back. On the Reels tab itself it switches to the home feed.

Both features can be switched on and off in the app.

## How it works

Unreel is an [Accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service). It reads Instagram's on-screen view tree, looks for the Reels tab (`clips_tab`) and the Reels viewer (`clips_viewer_*`), and reacts to them. It needs no root, does not modify Instagram, and has no internet permission, so it can't send any data anywhere.

## Install

1. Download the APK from the **Build APK** workflow run (Actions tab → latest run → `Unreel-apk` artifact), or build it yourself:
   ```sh
   ./gradlew assembleRelease
   # → app/build/outputs/apk/release/app-release.apk
   ```
2. Install the APK on your phone (allow installing from unknown sources).
3. Open Unreel → **Open Accessibility settings** → *Installed/Downloaded apps* → **Unreel – Reels blocker** → turn it on.
   - On Android 13+ the switch can be greyed out for sideloaded apps. Open **Settings → Apps → Unreel → ⋮ → Allow restricted settings**, then try again.
4. Open Instagram: the Reels button is gone.

Some phones (Xiaomi, Huawei, Samsung with aggressive battery saving) kill accessibility services in the background. If Unreel stops working, set its battery usage to *Unrestricted*.

## Limitations

- Instagram changes its internal view ids from time to time. If the Reels tab shows up again after an Instagram update, the ids in `ReelsBlockerService.kt` need updating (there is a fallback that looks for a bottom-bar button labelled "Reels", which only works with an English UI).
- Videos that play inline in the home feed are not blocked, only the full-screen Reels viewer.
