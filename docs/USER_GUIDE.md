# Scry user guide

This guide is for people who use Scry to open their own PhotoPrism library on an Android phone. You do not need to be technical, but you do need a PhotoPrism server that already works.

**Contents**

1. [What you need](#what-you-need)
2. [First-time setup](#first-time-setup)
3. [Using Scry day to day](#using-scry-day-to-day)
4. [Back up your photos](#back-up-your-photos)
5. [Downloads](#downloads)
6. [The settings screen](#the-settings-screen)
7. [If something goes wrong](#if-something-goes-wrong)
8. [Privacy and security](#privacy-and-security)
9. [Known limitations](#known-limitations)

---

## What you need

- A **PhotoPrism server** that you can already open in a web browser, and its address, for example `http://192.168.1.20:2342`.
- To use your library **away from home**, one of:
  - a **WireGuard file** (it ends in `.conf`) from your server or router, or
  - a **Tailscale account** with your server on it.
- Or nothing extra, if you only use Scry **at home** on your own Wi-Fi.

## First-time setup

The first time you open Scry, a short **setup wizard** appears. You can run it again at any time from **Settings → About → Run the setup wizard**.

The wizard asks how you reach your library. Pick the one that fits you.

### Away from home, with WireGuard

1. Choose **Away from home, with WireGuard**.
2. Tap **Choose the file** and pick your `.conf` file. Scry keeps it private on the phone and never shows your keys.
3. Enter **your library's address inside the private network**. This is the address you would type into a browser while connected to your VPN, with the port, for example `http://10.0.0.1:2342`.
4. Optionally enter your **home address**, so Scry can skip the VPN when you are at home.
5. Scry checks that it can reach your server. If it can, finish the wizard and your library opens.

### Away from home, with Tailscale

1. In the [Tailscale admin page](https://login.tailscale.com/admin/settings/keys), choose **Generate auth key**. A **one-off** key is fine. Copy it. (The wizard has a button that opens this page.)
2. Choose **Away from home, with Tailscale** in the wizard and paste the key.
3. Keep the suggested name for the phone, or pick your own. It shows up in your Tailscale device list.
4. Enter your server's **address on your Tailscale network**: its `100.x.y.z` address (or its name), with the port, for example `http://100.64.0.5:2342`.
5. Optionally enter your home address, then let Scry check the connection.

The auth key is only needed once. Scry keeps the phone's Tailscale identity after the first sign-in and removes the key from its storage. Your server's firewall must allow connections on the Tailscale interface; if you use `ufw`, for example: `sudo ufw allow in on tailscale0 to any port 2342 proto tcp`.

### Only at home

Choose **Only at home** and enter your server's address on your home network. This needs no VPN, but your library is out of reach when you are away from home.

### Signing in to PhotoPrism

When setup finishes, Scry opens your library. Sign in with your usual PhotoPrism username and password. Your phone's password manager can remember them, and you stay signed in between visits.

## Using Scry day to day

Open Scry and your library appears, as in a browser. Scry connects in the background first, which usually takes a second or two. The very first connection through a VPN can occasionally take about ten seconds.

- **Away from home:** Scry uses your WireGuard or Tailscale connection.
- **At home:** if you gave a home address, Scry checks it first. If it is your server, Scry connects directly and skips the VPN.
- **Changing networks** (Wi-Fi to mobile data and back) is handled for you. Scry reconnects without being restarted.
- Scry only holds the connection while it is open or while it is downloading or backing up, and lets it go shortly after.

## Back up your photos

Scry can send new photos and videos from your phone to your PhotoPrism library automatically. This is optional, and it takes a little setup because Scry needs a key from PhotoPrism. **Backup only adds files**: deleting a photo from your phone never deletes it from PhotoPrism, and edits are not synced. It is not a substitute for a second copy of your photos.

### Step 1: create an app password in PhotoPrism

An **app password** is a key that lets one app, here Scry, use your library without your main password.

1. Open Scry so your PhotoPrism library is showing.
2. In PhotoPrism, open **Settings → Account**, then **Apps and Devices**.
3. Create a new app password. Give it a name such as "Scry" and, if asked, a long expiry.
4. **Copy it straight away.** PhotoPrism shows it only once.

The easiest way to follow these instructions is to keep this guide open in your phone's browser, and switch back and forth between the browser and Scry.

### Step 2: turn on backup in Scry

1. In Scry, open **Settings → Backup**.
2. Paste the app password.
3. Choose **Start from**:
   - **Now** (the default): only photos taken from now on.
   - **Everything on this phone**: your whole existing collection, which can take a long time and a lot of data.
   - **A date**: everything since that date.
4. Turn on **Back up photos and videos**. Scry asks for access to all your photos and videos **and their location**. Choose **Allow all**. If you leave out location access, Android removes the GPS position from every photo it hands to Scry.
5. Choose when to back up. **Only on Wi-Fi** is on by default, which keeps large uploads off your mobile data. You can also require charging, and pick how often (every 6 or 12 hours, daily or weekly).
6. Tap **Back up now** to run it straight away. The Backup tab shows the result of the last run and when the next one is due.

### Good to know

- **Timing is approximate.** Android decides exactly when background work runs, to save battery. Some phones, notably Samsung, hold back apps you have not opened for a while ("sleeping apps"). If scheduled backups do not run, open Scry now and then, and check the phone's battery settings.
- **Android can remove permissions from apps you do not use.** If you stop opening Scry for months, it may switch off photo access. Allow it again in the phone's settings for Scry.
- **Scry never uploads its own downloads** (the folders it saves to), so photos are not sent back where they came from.
- **A photo that cannot be uploaded** is retried over a few runs, then given up on so it does not block the rest.
- **Large files** (a gigabyte or more) have not been tested. See [known limitations](#known-limitations).

## Downloads

Use PhotoPrism's own download option to save a photo or video. Scry fetches it in the background, shows a notification with progress, and saves it to your gallery:

- photos to **Pictures/PhotoPrism**, videos to **Movies/PhotoPrism**,
- and when you download several photos at once, PhotoPrism sends one zip. Scry **unpacks it into your gallery** and removes the zip, but only if every file in it was saved. If the zip holds anything else (for example RAW files), it is left as it is, in your Downloads folder. You can turn unpacking off in **Settings → Downloads**.

If you decline notifications, downloads still work, and a short message tells you where the file was saved.

## The settings screen

Open it from **Settings** in the Scry icon's long-press menu (press and hold the app icon), or from the **App info** page for Scry in Android's settings. The tabs across the top are:

| Tab | What it is for |
| --- | --- |
| **About** | Version, the setup wizard, the support link, the user guide and source code, and the open-source licences. |
| **Connection** | The connection status, a **Test connection** button, your server's address, and an optional home address. |
| **VPN** | WireGuard, Tailscale or no VPN. Import a WireGuard file or enter its values by hand. Your keys are never shown again. |
| **Backup** | Everything described in [Back up your photos](#back-up-your-photos). |
| **Downloads** | Unpacking zips, and the notification settings. |

On the **Connection** and **VPN** tabs, **Save and connect** saves your changes. If a new connection does not work, the connection screen offers **Revert to previous VPN config**.

On a television or with a keyboard, use the arrow keys: left and right move between tabs, down enters the page, up comes back to the tabs.

## If something goes wrong

**"Scry could not find PhotoPrism at that address."** Check the address, including `http://` and the port. If you are connecting at home, make sure the phone is on your home Wi-Fi.

**"Your server answered, but PhotoPrism is not listening at that address and port."** The server is there, but nothing is answering on that port. Check the port, and that PhotoPrism is listening on that network address (for example, that its Docker port mapping is not limited to one address).

**"No WireGuard handshake."** Scry could not reach your VPN server. Check the endpoint and keys in your WireGuard file, that the server is online, and that its firewall allows the WireGuard port.

**Tailscale does not connect.** Check the auth key (it starts with `tskey-`), that the server is on your Tailscale network, and your Tailscale access rules. Tailscale also needs to reach its own coordination service, which can fail briefly on a poor mobile connection. Try again in a moment.

**The first connection is slow (about ten seconds).** Some networks delay the very first WireGuard handshake. It is usually quick afterwards. Connecting directly at home is not affected.

**Uploads through PhotoPrism's own upload button sometimes stay on the upload page** even though the photo arrived. This comes from PhotoPrism's web app, not from Scry. Reload the page and your photo is there.

**Backup is not running.** Check **Settings → Backup**: is it on, is the app password saved, and is photo access allowed ("Allow all")? If it only runs on Wi-Fi, it waits for Wi-Fi. See [Good to know](#good-to-know) about battery settings.

**Start again.** You can run the wizard again from **Settings → About**, or change anything from the tabs.

## Privacy and security

- Scry has **no accounts, no ads and no analytics**. It only talks to your own server, and to Tailscale's service if you chose Tailscale.
- Your WireGuard key, Tailscale key and PhotoPrism app password are **encrypted on the phone** with a key that stays in Android's secure storage. They are never written to logs and are excluded from Android's cloud backup and device transfer.
- Scry's tunnel runs **inside the app**. It does not route any other app's traffic.
- The tunnel and the local connection are protected from other apps on the phone: Scry's internal connection point only answers to Scry's own web view.
- Scry does not upload Tailscale's client logs.

## Known limitations

- **Files of 1 GB or more** have not been tested for upload or download.
- **Downloads that are interrupted** start again from the beginning (up to three attempts).
- **Scheduled backup timing is approximate** and depends on your phone's battery settings.
- **The first WireGuard connection** on some mobile networks can take about ten seconds.
- Scry works with PhotoPrism's current web app. Because PhotoPrism's interface can change between versions, a future PhotoPrism update could affect Scry.
- Android only for now. A television version is planned.

---

Scry is free and open source under the GNU General Public License. If it is useful to you, you can [buy me a coffee](https://buymeacoffee.com/supersteve). To build Scry yourself, see the [developer guide](DEVELOPMENT.md).
