# Scry 0.1.0

The first release. Scry opens your own PhotoPrism photo library on your Android phone, from anywhere, with its own private connection built in. No separate VPN app, and it works alongside a VPN you already use.

Scry is an independent project. It is not affiliated with PhotoPrism UG, the WireGuard project or Tailscale Inc.

## What it does

- **Your library in PhotoPrism's own interface**: browse, search, albums, places, video with seeking.
- **A private connection built in**, without Android's VPN slot:
  - **WireGuard**: import the `.conf` file from your server or router.
  - **Tailscale**: paste an auth key and your server's Tailscale address.
  - **At home**: connect straight to your server on your home Wi-Fi, no VPN. With a home address set, Scry skips the VPN when you are home, after checking that it really is your server.
  - Reconnects by itself when you switch between Wi-Fi and mobile data.
- **A setup wizard** in plain language on first run, which you can run again from Settings > About.
- **Downloads** of photos, videos and multi-select zips go into your gallery, with progress in a notification. Zips are unpacked for you.
- **Optional photo backup**: sends new photos and videos to your PhotoPrism library on a schedule or on demand, on Wi-Fi only or while charging if you like, keeping each photo's location. It only adds files; it never deletes anything on your phone or your server.
- **Private by design**: no accounts, ads, analytics or tracking. Keys and passwords are encrypted on the phone and kept out of Android's cloud backup. See the [privacy policy](PRIVACY.md).
- **Works with a TV remote or keyboard**: every settings screen can be navigated with the arrow keys.

## Install

Download the APK that matches your device from the assets below:

| File | For |
| --- | --- |
| `scry-0.1.0-arm64-v8a.apk` | Almost every current phone and tablet |
| `scry-0.1.0-x86_64.apk` | Emulators and some Chromebooks |

Check the download against `SHA256SUMS.txt`. Android 10 or newer is required. You will need to allow installs from your browser or file manager the first time. Then follow the [user guide](USER_GUIDE.md).

## Known issues

- **Uploads through PhotoPrism's web interface often stay stuck on the upload page**, although the upload succeeded and a reload shows the file. This appears to be PhotoPrism's behaviour, not Scry's, but it has not been compared against a regular browser.
- **The first connection through a VPN can be slow on some networks**: the handshake sometimes takes about 10 seconds instead of 1. Seen on an IPv6-only mobile network; the cause is not established. Direct connections on your home network are not affected.
- **Scheduled backup timing is approximate.** Android decides when the job runs, and battery features (on Samsung, "sleeping apps") can hold it back for apps you have not opened in a while. If you do not use the app for months, Android's "Remove permissions if app isn't used" can revoke the photo permission, which pauses backup until you grant it again.
- **An interrupted download starts again from the beginning** (up to 3 attempts). Resuming is not implemented.

## Not yet tested

- **Uploads and downloads of files of 1 GB or more.**
- Phones other than the test device, and older Android versions (the minimum is Android 10).
- Servers other than PhotoPrism Plus build 260523.

## Tested on

One Samsung phone running Android 17, against a PhotoPrism Plus server, over WireGuard, over Tailscale and directly on the home network, with and without a second VPN running.

## Licence

GPL-3.0-or-later. The source is in this repository; notices for everything bundled in the app are under Settings > About. If Scry is useful to you, you can [buy the developer a coffee](https://buymeacoffee.com/supersteve).
