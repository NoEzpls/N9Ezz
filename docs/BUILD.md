# Reproducible build: 0.4.4-experimental

The build runs offline. Python 3 and a complete JDK 21 are required. It compiles
the supplied custom Loader sources and applies the included, independently
implemented ASM patches to the pinned `network.1` client. The full original
Pulse client source is not available; this is a source release of the Loader
and the changes, not a claim to have reconstructed that engine.

Inputs must be supplied separately. The build validates every input SHA-256
before extraction or compilation. It never downloads or runs the comparator
clients, connects to a game server, or reads the user's configuration or logs.

| Input | SHA-256 |
| --- | --- |
| `N9Ezz-Universal-Loader-0.4.1-experimental.jar` | `b81855307752b3d20df9bff66175914d2a118e7cce688c15e24b153952c60b3b` |
| `N9Ezz-Universal-Loader-0.4.1-experimental-sources.zip` | `b5451d33b11c389cadf1b8d85879a9f625faed60022f6cc9dae87ff9864dc960` |
| The `embedded/n9ezz-client-1.5.3.jar` entry from the `network.1` Loader | `5dbb9f72359d8371b7069dbf4f421c358c28a8c11af23e06c9182ec793aada6a` |

Pinned original release files:

- [Original Loader](https://github.com/NoEzpls/N9Ezz/raw/d91f283441a4983349b6dbf6bf4385d458b4923c/N9Ezz-Universal-Loader-0.4.1-experimental.jar)
- [Original sources](https://github.com/NoEzpls/N9Ezz/raw/d91f283441a4983349b6dbf6bf4385d458b4923c/N9Ezz-Universal-Loader-0.4.1-experimental-sources.zip)
- [Network.1 Loader](https://github.com/NoEzpls/N9Ezz/raw/8d1e463e19fbc054c618e7a55ec5a36ef6eaf672/N9Ezz-Universal-Loader-0.4.1-network.1-experimental.jar)

The complete network.1 Loader has SHA-256
`bd6c983ac22f436e914f5510faa598a18c218f08e10c8b5c36504ebf31e40100`.
Extract its embedded client entry as `client.jar`; do not use the Loader itself
as `--baseline`. All 13 build dependencies are restored from the verified
original Loader and checked against its lock file. Fabric API and original
contract-test fixtures come from the verified original source archive.

The cloud build used Temurin 21.0.8+9 for Linux x64:

- [Official JDK archive](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz)
- SHA-256: `f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219`.

Before integrating the update, an isolated offline build of the original
sources passed the original test suites, packaged self-test and 11 installer
metadata checks. Both original release archives reproduced their pinned
SHA-256 values exactly. This validates the restored build environment; the
updated release has separate test results in its own `verification.json`.

Run from the `pulsevisuals-combat` directory in this source release:

```sh
python3 build.py --offline \
  --java-home /path/to/jdk-21 \
  --baseline /path/to/client.jar \
  --minecraft /path/to/minecraft-1.21.11-intermediary.jar \
  --original-loader /path/to/N9Ezz-Universal-Loader-0.4.1-experimental.jar \
  --original-sources /path/to/N9Ezz-Universal-Loader-0.4.1-experimental-sources.zip
```

The output directory defaults to `dist`. Each build gets a fresh staging
directory under `build`; compilation or tests cannot accidentally use stale
classes. The output directory must be empty. Choose a fresh `--output` for a
rebuild; existing artifacts are never overwritten. A failed build preserves
its reports in the displayed staging directory. Remove old staging directories
manually when they are no longer needed.

The build checks the four unchanged network/physics classes byte for byte,
keeps the original render-bridge checksum restrictions, runs the new behavior
tests, recompiles the Loader, runs its 16 test suites, then runs the packaged
self-test, installer metadata and migration tests and both acceptance-check harnesses. The
original merge/hitbox tests hard-code the superseded merge and are replaced by
the tests of the new patched artifact. The original base/overlay merge is never
run on the already merged network client.

`verification.json` and the test-report ZIP state what was executed. They keep
actual Minecraft startup, live server behavior and packet capture explicitly
`UNRUN`. Unit/fixture test success is not evidence of a successful real game
launch or of an anticheat bypass. `--skip-tests` is for local development and
marks results `UNVERIFIED`; it must not be described as a verified release.

The source ZIP includes original source text, licenses and required upstream
source notices under `upstream-source/`, plus the complete update under
`pulsevisuals-combat/`. Binary game/client inputs, private configurations, user logs,
competitor archives and the JDK are excluded. The input binaries remain
separately pinned because the available original source does not rebuild the
entire client from source.

## Compile-only Minecraft input for 0.4.4

Typed replacement controllers now require the exact Minecraft 1.21.11 intermediary
API JAR from the supplied installation. SHA-256:
`25810669cc30ab540f997f2af3fd3a4dae006f2275343951d874b19e8ed35d9f`.
It was read from `n9ezz-universal/.n9ezz/cache/game/5e29378f7d7aee4a7847940735bfaec31b213ae806240a5020eb090f33ef41ee/minecraft-1.21.11-intermediary.jar`
in the supplied multipart installation archive. Supply it with `--minecraft`.
The build validates its hash and does not package or publish Minecraft classes.
A differently generated/remapped JAR is not silently accepted; without this input
the exact build cannot run. This limitation is separate from the original Loader
and network-client inputs linked above.

`RewriteTest` executes real replacement controllers against a small deterministic
world fixture, then substitutes the actual patched module constructors and delegates
to verify settings and wiring. It is not a multiplayer or game launch test.
