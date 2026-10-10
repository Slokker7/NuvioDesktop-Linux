# NuvioDesktop Linux Port

## Project goal

This repository is a Linux port of:

https://github.com/UmbraProjects/NuvioDesktop

The upstream development branch is:

windows-tv-adaptive

Our Linux development branch is:

linux

The primary goal is to add first-class Linux support while keeping the fork as close to UmbraProjects upstream as possible.

## Core rules

1. Preserve Windows and macOS behavior.
2. Never remove or weaken existing Windows/macOS functionality just to make Linux work.
3. Keep the Linux patch set as small and isolated as reasonably possible.
4. Prefer new Linux-specific implementations/files over duplicating or rewriting large shared files.
5. Reuse existing shared desktop logic whenever possible.
6. Do not perform unrelated refactors.
7. Do not rename or reorganize upstream code unless required for Linux support.
8. Before changing shared code, determine whether the change can instead live behind a platform abstraction.
9. Every significant change must remain easy to merge with future updates from UmbraProjects/windows-tv-adaptive.
10. Do not commit generated build output, secrets, credentials, local.properties, caches, or machine-specific paths.

## Linux target

Initial supported architecture:

- Linux x86_64

Primary development/test system:

- Bazzite
- Wayland desktop

Linux support should not be unnecessarily Bazzite-specific.

## Player

The integrated desktop player is a critical requirement.

The final Linux port should support, where technically possible:

- integrated libmpv playback
- video and audio playback
- hardware decoding
- pause/play
- seeking
- playback progress
- audio track selection
- subtitle selection
- external subtitles
- subtitle styling where supported
- fullscreen
- keyboard controls
- gamepad support
- media keys
- Picture-in-Picture where practical
- stream headers
- stream failover
- existing Nuvio desktop playback logic

Linux-specific features may use Linux-native implementations when Windows APIs are not applicable.

Do not silently replace the integrated player with an external mpv process as the final implementation.

## Development order

Work in small, verifiable stages.

1. Make the unmodified upstream application build/run on Linux.
2. Identify all existing Linux blockers and platform assumptions.
3. Implement the minimum Linux native player bridge.
4. Verify actual playback.
5. Add player functionality incrementally.
6. Test application behavior on Bazzite/Wayland.
7. Only after the application is working, add packaging.

Do not start Flatpak/AppImage work before the application itself works correctly from source.

## Packaging goal

Eventually produce all three formats from the same source commit:

- DEB
- Flatpak
- AppImage

Packaging must be reproducible through GitHub Actions.

Do not create one package format by repackaging another package format if a direct source build is practical.

## Git and changes

Do not commit or push unless explicitly asked.

Before finishing a task:

- show the files changed
- summarize why they changed
- run the most relevant build/tests available
- report commands run and their results
- mention any remaining uncertainty

If a task uncovers a larger architectural issue, stop and explain it before performing a broad rewrite.

Never run live X11/XWayland/Compose/GTK/WebKit window tests against the user's active desktop session unless explicitly requested. Default automated validation must use non-display unit tests and native fixtures. Live window tests require `NUVIO_RUN_LIVE_DISPLAY_TESTS=1`; native-smoke or focus-test flags alone do not authorize them.

## External references

Other Linux implementations or upstream Nuvio repositories may be studied as references.

Do not blindly copy code.

Understand how an implementation works, compare it to the current UmbraProjects source, and adapt only what is appropriate for this codebase.

## Compatibility

Future syncs from UmbraProjects/windows-tv-adaptive are a first-class requirement.

When choosing between two implementations, prefer the one that produces fewer conflicts with future upstream changes.
