![Apricot](etc/banner.png)

# Apricot Client

Apricot is a custom client for the wonderful game Haven & Hearth, based on the
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
- Updates itself: new versions are downloaded and installed from the login screen, no launcher needed
- Java 25 included, nothing else to install
- Coming from Hurricane? Your keybinds and settings carry over automatically
- Play in your own language: the interface, item names, actions and item descriptions can be translated, and anything not translated yet just stays in English

Suggestions and support: https://discord.gg/qY9py4HvfF

Important note:
- This client does not send any data to any place besides the official Seatribe server, with one exception:
  when the client crashes, a crash report (stack trace and system info, shown in the crash dialog) is sent
  to the Apricot developer so the bug can be fixed. This can be turned off by launching with
  `-Dhaven.errorurl=stderr`.

## Languages

Pick a language under **Options -> Advanced Settings -> Interface Settings ->
Language**, then restart the client. Russian, Chinese, Korean and French ship
with the client; Polish and German are started but still need translators.

Nothing has to be complete to be useful -- any text without a translation stays
in English, so a language is playable from its first entry.

Translations live in JSON files, one per kind of text. The ones that ship with
the client are inside `hafen.jar`; anything you put in the `Translations` folder
next to it overrides them and is never touched by the updater, so corrections
survive updates. Adding a folder there adds a language to the menu; deleting it
removes it. See [Translations/README.md](Translations/README.md) for the format,
and for the built-in tool that lists everything still untranslated.

Finished work is welcome upstream -- open a pull request adding your files under
`src/l10n/<language>/`.

## Downloading/Updating the Apricot Client (Outside of Steam):

Once the client is installed, it keeps itself up to date: when a new version is
out, the login screen downloads and installs it and restarts into it. Turn that
off with the checkbox on that window, or by launching with
`-Dhaven.autoupdate=false`, and the client will only tell you that an update is
available. Steam installs are updated by Steam, as before.

For the first install, the easiest way is the Apricot Launcher, a small script that
downloads the client, keeps it up to date, and launches it. Put it in its own folder
and run it; the client is installed into an `Apricot` subfolder next to it.

- Windows: https://github.com/ntforg/Apricot/releases/latest/download/ApricotLauncher.bat
- Linux: https://github.com/ntforg/Apricot/releases/latest/download/ApricotLauncher.sh

You might need to add ApricotLauncher.bat to your anti-virus exceptions list.

Or download a release manually from: https://github.com/ntforg/Apricot/releases/latest

The Windows and Linux downloads come with Java 25 bundled, so no Java installation is needed.
The plain zip requires an installed Java, **any version between Java 17 and Java 25**.

## Launching the Apricot Client (Outside of Steam):

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
Ganhart/Aritain's updated version: https://github.com/Aritain/hnh-map-updated

Or you can use dafels' Mapping service (or set up your own private map server):
https://www.havenandhearth.com/forum/viewtopic.php?f=49&t=79701

The cookbook integration is disabled by default. You can use a token from a public
cookbook such as https://cookbook.kittenrider.com/, or host your own
(for example, https://github.com/Cediner/hnh-food-book).

---

Apricot is not affiliated with Nightdawg or Hurricane.
