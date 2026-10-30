# GeyserRecipeFixPatcher

A small Paper/Folia plugin that installs [Geyser Recipe Fix](https://modrinth.com/plugin/geyser-recipe-fix)
with local Folia-support patches applied, without redistributing any of the
original plugin's code.

## WARNING: Made with AI (cuz idk how to use Java)
## PLEASE SUPPORT [THE ORIGINAL DEVELOPER](https://github.com/AnsonEyre)

I am a random dev, and you should really not trust random stuff you find on the Internet. Take a look at the source code which is only a few hundred lines long. (click on the GitHub "view source" link on the side). And if you prefer, compile the project yourself with `gradle clean shadowJar` (requires a Java 25 JDK and Gradle).

## What it actually does, on your own server, every boot

1. Asks **Modrinth** (the author's own official distribution channel) for
   the latest published build of Geyser Recipe Fix, and downloads it fresh.
2. Applies a small set of targeted bytecode edits directly to the two class
   files that need them (see `PATCHES.md` for the exact list). These edits
   never reproduce the original author's method bodies - they swap two
   collection allocations for thread-safe equivalents, and redirect two call
   sites to a couple of small helper classes that ship with **this** plugin.
3. Writes the result to `plugins/GeyserRecipeFix.jar` and asks you to
   restart the server once, exactly like installing a normal plugin update.
4. Repeats this on every startup, so you always end up running the latest
   upstream release with the same patches applied.

**This repository never contains, bundles, or distributes any of
`Geyser Recipe Fix`'s code.** The only copy of that code that ever exists is
the one downloaded straight from Modrinth onto your own server, which you
then locally and momentarily transform before running it - the same basic
idea as a ROM/game patch (`.bps`/`.xdelta`) or a Linux distro's source
patch: ship the delta, not the copyrighted base.

## Why this exists

Geyser Recipe Fix is © Sideways-Sky, all rights reserved, with no OSS
license attached. The author hasn't responded to a request to add one. This
patcher exists so a set of Folia-compatibility fixes can reach users of that
plugin without redistributing the author's copyrighted code - it fetches
the real thing from the real place, and only ships the *diff*.

**This is not a substitute for legal advice**, and it's a materially
different (lower) risk profile than distributing a patched jar yourself,
but it isn't risk-zero: default copyright also covers the right to prepare
derivative works, not just to redistribute copies. If the author ever
responds, upstreaming these fixes directly (see `PATCHES.md` for the exact
diff) would be the cleanest outcome for everyone.

## Setup

1. Install [PacketEvents](https://modrinth.com/plugin/packetevents) and
   Geyser and/or Floodgate, same as Geyser Recipe Fix normally requires.
2. Build this project (see "Building from source" below), then drop
   `build/libs/GeyserRecipeFixPatcher-<version>.jar` into your `plugins/`
   folder. (Do **not** manually download/place GeyserRecipeFix.jar
   yourself - this plugin manages that file.)
3. Start the server once. GeyserRecipeFixPatcher will download, patch, and
   install `plugins/GeyserRecipeFix.jar`, then tell you to restart.
4. Restart. Geyser Recipe Fix now runs, patched, as a normal plugin -
   GeyserRecipeFixPatcher doesn't need to do anything else until a new
   upstream release appears.

### Config (`plugins/GeyserRecipeFixPatcher/config.yml`)

- `allowed-versions`: leave empty to always track Modrinth's latest, or list
  specific version numbers you've verified, so nothing changes unexpectedly.
- `auto-update`: turn off if you want to review new versions manually via
  `/grfpatcher update` before they're installed.

### Commands

- `/grfpatcher status` - shows the currently installed upstream version.
- `/grfpatcher update` - checks Modrinth and re-patches immediately.

Permission: `geyserrecipefixpatcher.admin` (defaults to ops).

## Verifying the patch actually applied

Every patch run checks that each edit it made actually matched something in
the downloaded jar (see `JarPatcher.UnsupportedUpstreamVersionException`).
If upstream ever changes the structure these patches target, the run fails
loudly in the console/log instead of silently installing a broken jar -
watch for a GeyserRecipeFixPatcher update in that case.

## Building from source

Requires **JDK 25** (Minecraft 26.x needs Java 25, and the 26.x dev bundle is
Java 25 bytecode), Gradle 9+, and internet access to Maven Central,
repo.papermc.io and the Gradle plugin portal (for the Paper API / Mojang-mapped
NMS dev bundle used only to compile the small helper classes that get
injected - see `PATCHES.md`).

```
gradle clean shadowJar
```

The output is `build/libs/GeyserRecipeFixPatcher-<version>.jar`.

### Supported versions

Built against and verified for **Paper and Folia 26.3** (Minecraft 26.3,
Java 25). `plugin.yml` declares `api-version: '26.1'`, and the NMS calls used
are checked against the 26.3 server source, so it is also expected to load on
26.1.x/26.2 - but only 26.3 is what the current build is aimed at. PacketEvents
and Geyser must of course be builds that support the same Minecraft version.

### Updating the patcher

Bump `version` in `build.gradle` whenever the patch logic changes (the version
is what triggers an automatic re-patch - see "Re-patching").

This project was written and reviewed carefully, but hasn't been build- and
run-tested against a live Paper/Folia server in the environment it was
authored in. Test it on a dev server before relying on it in production,
and please open an issue with the console output if the compatibility
check ever fails.
