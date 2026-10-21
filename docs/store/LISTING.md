# Google Play listing for Scry

Everything to paste into Play Console, and the answers to its forms. Fields marked **DECIDE** need a choice from the account owner; fields marked **CONFIRM** should be checked against the form as Play Console words it on the day.

## 1. Main store listing

**App name** (30 characters at most)

```text
Scry: Private Photo Library
```

**Short description** (80 characters at most)

```text
Open your self-hosted photo library from anywhere. Private connection built in.
```

**Full description** (4000 characters at most)

```text
Scry opens your own PhotoPrism photo library on your Android phone, from anywhere, with its own private connection built in. There is no separate VPN app to switch on, and it works alongside a VPN you already use.

You need your own PhotoPrism server. Scry does not provide one.

HOW IT CONNECTS
- With WireGuard: import the .conf file from your server or router.
- With Tailscale: paste an auth key and your server's Tailscale address.
- At home: connect straight to your server on your home Wi-Fi, with no VPN at all.
- Or both: give Scry your home address and it skips the VPN when you are home, after checking that it really is your server.

The connection lives inside the app. It never uses Android's VPN slot and never touches any other app's traffic. It reconnects by itself when you switch between Wi-Fi and mobile data.

FEATURES
- Your whole library in PhotoPrism's own web interface: browse, search, albums, places, video with seeking, and live updates.
- A short setup wizard in plain language. No technical background needed.
- Downloads go straight to your gallery, with progress shown in a notification. Photos land in Pictures, videos in Movies, and the zip you get when you download several photos is unpacked for you.
- Optional photo backup: send new photos and videos from your phone to your PhotoPrism library, on a schedule or on demand, on Wi-Fi only if you like, keeping each photo's location. Backup only ever adds files. It never deletes anything, on your phone or on your server.
- Stays signed in between visits. Your phone's password manager works as usual.
- Works with a keyboard or a TV remote: every screen can be navigated with the arrow keys.

PRIVATE BY DESIGN
- No accounts, no ads, no analytics, no tracking. The developer receives nothing about you or your photos.
- Your keys and passwords are encrypted on the phone and kept out of Android's cloud backup.
- Scry talks only to the server you choose (and to Tailscale's service if you pick Tailscale).

FREE AND OPEN SOURCE
Scry is free software under the GNU General Public License, and the source code is on GitHub. If it is useful to you, you can support its development with a coffee, using the link inside the app.

Scry is an independent project. It is not affiliated with, sponsored by or endorsed by PhotoPrism UG, the WireGuard project or Tailscale Inc. PhotoPrism is a trademark of PhotoPrism UG. WireGuard is a registered trademark of Jason A. Donenfeld. Tailscale is a registered trademark of Tailscale Inc.
```

**Category:** Photography. **DECIDE:** Tools would also fit.

**Tags:** choose from Play's list, for example photo viewer / gallery. **CONFIRM** the wording of the list on the day.

## 2. Contact details and links

| Field | Value |
| --- | --- |
| Contact email (public, required) | **DECIDE:** an address you are happy to publish. A dedicated address is better than a personal one. |
| Website | https://github.com/superstevesoftware/scry |
| Privacy policy URL | https://github.com/superstevesoftware/scry/blob/main/docs/PRIVACY.md (the repository must be public for this link to work) |

## 3. Graphics (all in this folder)

| Asset | File | Requirement |
| --- | --- | --- |
| App icon | `play-store-icon-512.png` | 512 x 512 PNG, up to 1 MB. Supplied square; Google applies the rounding. |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 x 500 |
| Phone screenshots | `screenshots/` (7 files, 1080 x 2160, taken on an emulator against PhotoPrism's public demo; upload in numbered order) | At least 2, up to 8. Each side between 320 and 3840 px, and the long side at most twice the short side. |

## 4. App content (the Policy section of Play Console)

| Question | Answer |
| --- | --- |
| Ads | No ads. |
| Target audience | 18 and over. (Avoids the Families policy; Scry is not aimed at children.) |
| Content rating (IARC questionnaire) | Utility/productivity answers: no violence, no sexual content, no gambling, no user-to-user interaction, no sharing of the user's location with other people, no purchases. Expected rating: Everyone. **CONFIRM** after completing the questionnaire. |
| News app / government app / health app / financial features | No to all. |
| App access | Parts of the app need a PhotoPrism server. See section 6 for the reviewer instructions. |
| Donation link | The About screen links to buymeacoffee.com/supersteve. It is a voluntary tip for the developer, not a purchase of app features. **CONFIRM** against the current Payments policy; if Play objects, remove the link from the Play build. |

## 5. Data safety form (draft answers)

Scry has no servers of its own and the developer receives no data. Two things still count under Google's definition of "collected" (data that leaves the device from the app), so the conservative, accurate answers are below. **DECIDE** whether to answer more narrowly; Google's own guidance does not settle the case of a server the user runs themselves.

| Question | Draft answer | Why |
| --- | --- | --- |
| Does the app collect or share user data? | Yes | Photos and videos leave the device when backup is on. Tailscale, if chosen, receives device/network details. |
| Photos and videos | Collected: yes. Shared: no. Optional: yes (only if backup is turned on). Purpose: App functionality. | Sent to the user's own PhotoPrism server. Never to the developer or any third party. |
| Device or other IDs / network details | Collected: yes, only when the user chooses Tailscale. Shared: yes, with Tailscale (the VPN provider the user chose). Optional: yes. Purpose: App functionality. | The embedded Tailscale library registers the phone's node name and network addresses with Tailscale's coordination service. |
| Location | Not collected. | Photo GPS metadata is part of the uploaded photo file, not collected separately. **CONFIRM** that Play's form does not ask you to list it under Location. |
| Personal info, financial info, health, messages, contacts, calendar, web history, audio, app info and performance (crash logs, diagnostics) | Not collected. | |
| Is all of the user's data encrypted in transit? | No | Google allows "yes" only if it applies to all data. Connections through WireGuard or Tailscale are encrypted, but a direct connection to an `http://` address on a home network is not. |
| Can users request that data be deleted? | The developer holds no data. Users delete everything by uninstalling the app or clearing its storage. | There are no accounts. |
| Independent security review | No | |

## 6. Instructions for the Play reviewer (App access)

Scry needs a PhotoPrism server. PhotoPrism's own public demo works with no login and no VPN, so reviewers can see a working library:

```text
Scry shows a user's own PhotoPrism photo library. No account is needed; use PhotoPrism's public demo server:

1. Open Scry. A setup wizard appears.
2. Tap "Let's start", choose "Only at home", and tap Next.
3. Enter this address: https://demo.photoprism.app
4. Tap Next. Scry checks the connection, then tap Next again and "Open my library".
5. The demo library opens; no login is required. Browse, open a photo, open the side menu, and use PhotoPrism's download button to save a photo to the gallery.

VPN features (WireGuard, Tailscale) and photo backup need a private server and the user's own credentials, so they cannot be demonstrated with the public demo. Backup is optional and off by default; the permission request appears only when the user turns it on in Settings > Backup.
```

**CONFIRM** that the demo address still works on the day, and that you are comfortable pointing reviewers at a third party's public demo.

## 7. Sensitive permission and foreground service declarations

### Photo and video permissions (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION)

Google allows these only for apps whose "core functionality revolves around broad access to photos and videos" and the policy does not mention backup apps. **This is the largest approval risk.** Suggested declaration text:

```text
Scry is a client for the user's own self-hosted PhotoPrism photo library. One of its two main functions is automatic backup of the phone's photos and videos to the user's own server, as photo backup and sync apps do. Backup runs in the background on a schedule and uploads new media without the user choosing each file, which the Android photo picker cannot do. ACCESS_MEDIA_LOCATION is needed so uploaded originals keep their GPS position; Android otherwise strips it.

Access is requested only when the user turns on backup in Settings, after an in-app explanation. Viewing and downloading work fully without it. Media is read only to upload it to the server the user configured, and the developer never receives it.
```

**DECIDE** the fallback if Google rejects the declaration: ship the Play build without automatic backup (a build flavour with the media permissions removed, and an "upload photos" button using the system photo picker instead), and keep full backup in the GitHub build.

### Foreground service (FOREGROUND_SERVICE_DATA_SYNC)

```text
Scry uses a foreground service of type dataSync while it downloads photos and videos from the user's server to the phone's gallery, and while it uploads photos in a backup run. These transfers are started by the user (the download button in the library, or the Back up now / scheduled backup the user turned on), can take minutes to hours for large files, and must not be interrupted when the user leaves the app. A notification with progress is shown for the whole time, and the service stops as soon as the transfer ends.
```

Play Console also asks for a short **video** showing the feature that needs the service. Record this on a phone: open the library, download a video (the progress notification appears and then completes), then open Settings > Backup and tap Back up now (the backup progress notification appears).

## 8. Release checklist

- [ ] **Target Android 16 (API 36)**: done. Google requires it for new apps from 31 August 2026.
- [ ] **16 KB memory pages**: done. The native library is aligned for 16 KB pages.
- [ ] **Lint clean**: done (0 errors).
- [ ] **Signed release bundle** (`.aab`): needs an upload key. See `docs/DEVELOPMENT.md` for the steps. Keep the key and its passwords backed up somewhere safe; Google cannot recover them.
- [ ] **Closed test first, if the developer account is a personal one created after 13 November 2023**: Google requires a closed test with at least 12 testers opted in for 14 days in a row before production access can be requested. **CONFIRM** which account type applies.
- [ ] **Repository public** before submitting, so the privacy policy and website links work.
- [ ] **Screenshots** uploaded (section 3).
- [ ] **Complete the forms**: App content, Data safety, permission and foreground service declarations (sections 4 to 7).
- [ ] **What's new** for the first release:

```text
First release.
```
