![Thunder](etc/banner.png)

# Thunder Client

Thunder is a custom client for the wonderful game Haven & Hearth, based on the
Hurricane client (which itself builds on the vanilla client). The main reason it
exists is Cookbook integration, which Hurricane doesn't have, but the goal is
bigger than that: faster patches, more player suggestions actually making it
into the client, and general convenience.

The client can be played standalone, or through Steam by subscribing to the
Steam Workshop item: https://steamcommunity.com/sharedfiles/filedetails/?id=3786449280

## Features

- Integration with the public Cookbook: https://cookbook.kittenrider.com/
- Everything Hurricane has
- Faster patches, and suggestions are welcome
- Crashes get reported to the developer automatically (stack trace and system info), so bugs get fixed without anyone having to file them
- A launcher that installs the client and keeps it updated automatically
- Java 25 included, nothing else to install
- Coming from Hurricane? Your keybinds and settings carry over automatically

Suggestions and support: https://discord.gg/qY9py4HvfF

Important note:
- This client does not send any data to any place besides the official Seatribe server, with one exception:
  when the client crashes, a crash report (stack trace and system info, shown in the crash dialog) is sent
  to the Thunder developer so the bug can be fixed. This can be turned off by launching with
  `-Dhaven.errorurl=stderr`.

## Downloading/Updating the Thunder Client (Outside of Steam):

The easiest way is the Thunder Launcher, a small script that downloads the client,
keeps it up to date, and launches it. Put it in its own folder and run it; the client
is installed into a `Thunder` subfolder next to it.

- Windows: https://github.com/ntforg/Thunder/releases/latest/download/ThunderLauncher.bat
- Linux: https://github.com/ntforg/Thunder/releases/latest/download/ThunderLauncher.sh

You might need to add ThunderLauncher.bat to your anti-virus exceptions list.

Or download a release manually from: https://github.com/ntforg/Thunder/releases/latest

The Windows and Linux downloads come with Java 25 bundled, so no Java installation is needed.
The plain zip requires an installed Java, **any version between Java 17 and Java 25**.

## Launching the Thunder Client (Outside of Steam):

Run the Play.bat file inside the client folder, or Play_Linux.sh (for Linux/MacOS)

The launch scripts use the bundled Java 25 runtime (the `jre` folder) if present, and your
system Java otherwise. The bundled Java 25 is the recommended way to run the client.
If you run your own Java instead, the client works with **any version between Java 17 and Java 25**.

### If the client doesn't launch:
1. Make sure your installed Java version is **any version between Java 17 and Java 25**
2. You might need to add the launcher file (Play.bat or Play_Linux.sh) to your anti-virus exceptions list.

## Mapping and Cookbook

The client supports Cediner's Web Map server (you set up your own private map server, it's not a public map):
https://github.com/Cediner/hnh-map-vuetify

Or you can use dafels' Mapping service (or set up your own private map server):
https://www.havenandhearth.com/forum/viewtopic.php?f=49&t=79701

The cookbook integration is disabled by default. You can use a token from a public
cookbook such as https://cookbook.kittenrider.com/, or host your own
(for example, https://github.com/Cediner/hnh-food-book).

---

Thunder is not affiliated with Nightdawg or Hurricane.
