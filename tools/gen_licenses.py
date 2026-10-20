"""Builds android/app/src/main/assets/licenses.txt, the notice file shown in the app.

It collects the full licence text of every Go package compiled into the Android build
(using google/go-licenses), adds the Go toolchain and gomobile runtime, lists the Android
libraries, and puts Scry's own notices and trademark acknowledgements first.

Run it again whenever a dependency changes:
    go install github.com/google/go-licenses/v2@latest      (once)
    python tools/gen_licenses.py

The list of Android libraries below is maintained by hand. Refresh it with:
    cd android && gradlew :app:dependencies --configuration releaseRuntimeClasspath
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = os.path.join(ROOT, "core")
OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "licenses.txt")

HEADER = """SCRY: NOTICES AND OPEN-SOURCE LICENCES

Scry is free software: you can redistribute it and modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. Scry is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details: https://www.gnu.org/licenses/

Copyright (C) 2026 the Scry authors. The source code is available from the project's GitHub page.

Scry is an independent app for viewing a photo library that you host yourself. It connects to your own server through a private tunnel that runs inside the app.

PhotoPrism
PhotoPrism(R) is a trademark of PhotoPrism UG. Scry contains no PhotoPrism software and is not affiliated with, sponsored by or endorsed by PhotoPrism UG. The PhotoPrism web app is loaded at run time from the server you run, under the licence of the version you installed.

WireGuard
"WireGuard" and the "WireGuard" logo are registered trademarks of Jason A. Donenfeld. See https://www.wireguard.com/. Scry uses wireguard-go, which originates from wireguard.com, and reads WireGuard configuration files. Scry is not affiliated with or endorsed by the WireGuard project.

Tailscale
Tailscale is a registered trademark of Tailscale Inc. Scry embeds Tailscale's open-source library (tailscale.com/tsnet) to join your tailnet. Scry is not affiliated with, sponsored by or endorsed by Tailscale Inc.

The remainder of this file reproduces the licences of the software built into Scry, as those licences require.
"""

ANDROID_LIBS = """ANDROID AND KOTLIN LIBRARIES (Apache License 2.0)

Copyright The Android Open Source Project, JetBrains s.r.o. and contributors, as applicable.
Licensed under the Apache License, Version 2.0; the full text is reproduced at the end of this file.

  androidx.work:work-runtime 2.9.1
  androidx.annotation, annotation-experimental
  androidx.arch.core:core-common, core-runtime
  androidx.collection, androidx.concurrent:concurrent-futures
  androidx.core:core
  androidx.lifecycle:lifecycle-common, livedata, livedata-core, runtime, service
  androidx.room:room-common, room-ktx, room-runtime
  androidx.sqlite:sqlite, sqlite-framework
  androidx.startup:startup-runtime
  androidx.tracing:tracing
  androidx.versionedparcelable:versionedparcelable
  com.google.guava:listenablefuture 1.0
  org.jetbrains.kotlin:kotlin-stdlib (and -common, -jdk7, -jdk8)
  org.jetbrains.kotlinx:kotlinx-coroutines-core, kotlinx-coroutines-android
  org.jetbrains:annotations
"""

BAR = "=" * 78


def run(*cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout.strip()


def read(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", "replace").replace("\r\n", "\n").strip()


def main():
    env = dict(os.environ, GOOS="android", GOARCH="arm64", CGO_ENABLED="0")
    tmp = tempfile.mkdtemp(prefix="scry-lic-")
    try:
        save = os.path.join(tmp, "save")
        subprocess.run(
            ["go-licenses", "save", "./core", "--ignore", "scry", "--save_path", save],
            cwd=CORE, env=env, check=True, capture_output=True, text=True,
        )
        entries = []  # (title, text)
        for dirpath, _, files in os.walk(save):
            for name in files:
                rel = os.path.relpath(dirpath, save).replace(os.sep, "/")
                entries.append((rel, read(os.path.join(dirpath, name))))

        goroot = run("go", "env", "GOROOT", cwd=CORE)
        entries.append(("The Go programming language and standard library", read(os.path.join(goroot, "LICENSE"))))
        mobile = run("go", "list", "-m", "-f", "{{.Dir}}", "golang.org/x/mobile", cwd=CORE)
        entries.append(("golang.org/x/mobile (gomobile runtime)", read(os.path.join(mobile, "LICENSE"))))
        entries.sort(key=lambda e: e[0].lower())

        apache = next((t for n, t in entries if n.startswith("github.com/golang/groupcache")), None)
        if apache is None or "Apache License" not in apache:
            sys.exit("could not find the Apache 2.0 text to reproduce")

        parts = [HEADER, BAR, ANDROID_LIBS]
        for title, text in entries:
            parts += [BAR, title, "", text, ""]
        parts += [BAR, "APACHE LICENSE, VERSION 2.0 (full text, for the Android and Kotlin libraries above)", "", apache, ""]

        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, "wb") as f:
            f.write(("\n".join(parts) + "\n").encode("utf-8"))
        print(f"wrote {OUT}: {len(entries)} Go licence texts, {os.path.getsize(OUT) // 1024} KB")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
