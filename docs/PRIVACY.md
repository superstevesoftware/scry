# Scry privacy policy

*Last updated: 20 October 2026*

Scry is a free, open-source Android app for opening your own PhotoPrism photo library, with its own private connection built in. This policy explains, in plain terms, what Scry does with your information.

**In short:** Scry has no accounts, no ads and no analytics. The developer receives nothing about you or your photos. Scry talks only to the PhotoPrism server that you choose, and to Tailscale's service if you choose Tailscale. Your photos and settings stay between your phone and your own server.

## Who is responsible

Scry is developed by the Scry authors and published under the GNU General Public License. The source code is at <https://github.com/superstevesoftware/scry>. Questions about this policy can be raised as an issue there.

## What Scry stores on your phone

Everything below stays in Scry's private storage on your phone:

- **Your connection settings:** your PhotoPrism address, and your WireGuard configuration or Tailscale details. The keys and secrets are **encrypted** with a key held in Android's secure hardware-backed storage.
- **Your PhotoPrism app password**, if you turn on backup. It is encrypted in the same way and used only to upload your photos to your own server.
- **Backup progress:** a record of which photos have already been sent, so they are not sent twice.
- **Tailscale identity**, if you choose Tailscale: the information that lets your phone rejoin your Tailscale network, kept in Scry's private folder.
- **PhotoPrism's own web data** (such as your login session), kept by the web view that shows your library, as in any browser.

None of this is sent to the developer. It is excluded from Android's cloud backup and device-to-device transfer, so your keys do not leave the phone that way. Uninstalling Scry, or clearing its storage in Android's settings, deletes all of it.

## What Scry sends, and to whom

- **Your PhotoPrism server.** Scry sends requests to the address you entered, so that you can see and download your library, and, if you turn on backup, upload your photos and videos to it. Where this travels depends on your setup: through your WireGuard or Tailscale connection, or directly on your home network. A direct connection to an address that starts with `http://` is not encrypted by Scry, so use it only on a network you trust, or use an `https://` address.
- **Tailscale (only if you choose Tailscale).** To join your tailnet, the embedded Tailscale library contacts Tailscale's coordination service and relay servers. What Tailscale receives is described in [Tailscale's privacy policy](https://tailscale.com/privacy-policy). Scry turns off Tailscale's upload of client logs.
- **Nothing else.** Scry does not contact analytics, advertising or crash-reporting services, and contains none.

Scry opens links (the user guide, the Tailscale keys page, the donation page) in your regular browser. What happens there is governed by those websites.

## Your photos and videos

Scry reads the photos and videos on your phone **only if you turn on backup**, and only to upload them to your own PhotoPrism server. It asks for access to all of your photos and videos, and to their location information, because Android removes the location from every photo it shares unless that access is granted. You can turn backup off, or revoke the permission, at any time.

Photos you view, download or upload through Scry go to and from your own server. The developer never receives them. Downloaded files are saved to your phone's gallery by Scry.

## Permissions Scry asks for, and why

| Permission | Why |
| --- | --- |
| Network access | To reach your server and your VPN. |
| Photos and videos, and their location | Only for backup (above). |
| Notifications | Progress and results of downloads and backups, and an optional reminder after setup. |
| Foreground service (data sync) | To keep a long download or backup running while it works, with a visible notification. |
| Run at start-up | So a scheduled backup is rescheduled after the phone restarts. |

## Children

Scry is not directed at children and does not knowingly collect any information from anyone.

## Changes to this policy

If this policy changes, the new version is published at the same address with a new date. Because the developer collects no data, changes are expected to be rare.

## Contact

Open an issue at <https://github.com/superstevesoftware/scry/issues>.
