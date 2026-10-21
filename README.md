<p align="center"><img src="docs/images/icon.png" alt="Scry icon" width="140"></p>

# Scry

**Scry is an Android app for viewing your own [PhotoPrism](https://www.photoprism.app/) photo library from anywhere, with its own private connection built in.**

You install Scry, tell it where your PhotoPrism server is, and your library opens like any other app. When you are away from home, Scry reaches your server through a WireGuard or Tailscale connection that lives *inside the app*. There is no VPN app to switch on, nothing takes over your phone's VPN slot, and it works alongside a VPN you already use.

> **Status:** early release. It is in daily use on one phone against one server, so expect rough edges. See [known limitations](docs/USER_GUIDE.md#known-limitations).

## What it does

- **Opens your PhotoPrism library** as it is, with its normal web interface, login and live updates.
- **Connects privately from anywhere**, using your own WireGuard file or Tailscale key. Or connects straight to your server on your home Wi-Fi, with no VPN at all.
- **Skips the VPN at home.** Give it your home address and it connects directly when it can reach your server there, after checking it is really your server.
- **Saves downloads to your gallery.** Photos, videos and multi-photo zips are saved properly, with the zip unpacked for you.
- **Backs up your phone's photos and videos** to PhotoPrism, on a schedule or on demand, only on Wi-Fi if you like, keeping each photo's location. Backup only ever adds files.
- **Keeps your secrets safe.** Keys and passwords are encrypted on the phone, never logged, and kept out of Android's cloud backup.
- **Guides you through setup** with a short wizard in plain language. The screens also work with a remote control, ready for a television version.
- **Privacy First** Scry does not do any analytics, does not store your data in the cloud, and does not use any ads. Your data is your data.

## Get started

Read the **[user guide](docs/USER_GUIDE.md)**. It walks through the setup wizard, creating a WireGuard file or Tailscale key, setting up photo backup, and fixing common problems.

## How it works

Scry is a thin Android app on top of a Go core:

- The **Go core** (`core/`) runs the tunnel (a userspace WireGuard, or an embedded Tailscale node) entirely inside the app's process, and owns every connection to PhotoPrism. It also runs a small local proxy that the web view talks to.
- The **Android app** (`android/`) shows PhotoPrism in a web view, and adds the settings, setup wizard, downloads and backup.

Because the tunnel is in the app, Scry never uses Android's VPN service. To build it yourself, see the **[developer guide](docs/DEVELOPMENT.md)**.

## Support

Scry is free and open source. If it is useful to you, you can [buy me a coffee](https://buymeacoffee.com/supersteve) to help cover the cost of development.

## Licence

Scry is free software, released under the **GNU General Public License, version 3 or later**. See [LICENSE](LICENSE). The app includes open-source components under their own licences, which are listed inside the app (Settings, About) and in [android/app/src/main/assets/licenses.txt](android/app/src/main/assets/licenses.txt).

## Trademarks

Scry is an independent project. It is not affiliated with, sponsored by or endorsed by PhotoPrism UG, the WireGuard project or Tailscale Inc.

PhotoPrism® is a trademark of PhotoPrism UG. "WireGuard" and the "WireGuard" logo are registered trademarks of Jason A. Donenfeld. Tailscale is a registered trademark of Tailscale Inc.
