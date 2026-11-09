# VitaLite Plugins

The official plugin repository for VitaLite's Vita Hub. It holds no plugin source: `catalog.json` points at each plugin's
own project, the built jars are published as GitHub releases (one tag per plugin version, `<id>-<version>`), and
`plugins.json` lists them for the Hub. `plugins.json.sig` is its signature; VitaLite refuses an official manifest that
does not match it.

## Releasing

Requires JDK 11, the GitHub CLI (`gh auth login`), and the signing key at `~/.vitalite/hub-signing.pem`.

1. Bump the plugin's `version` in `catalog.json` (or add a new entry).
2. `./gradlew publishRelease`

Only plugins whose version is not in `plugins.json` yet are built and released. The task then rewrites, signs, commits
and pushes `plugins.json`.

`./gradlew publishRelease -PdryRun` does everything except the GitHub releases, the commit and the push.
`./gradlew verifyManifest` checks the signature and the jars in `build/release`.

## Adding a plugin

Add an entry to `catalog.json`:

| field | meaning |
|---|---|
| `id` | lowercase id, also the release tag prefix |
| `version` | numbers separated by dots |
| `author` | shown in the Hub |
| `minVita` | oldest VitaLite version it runs on |
| `requires` | ids of plugins it needs installed |
| `source` | the plugin's source repository, shown in the Hub |
| `project` | path to the plugin's Gradle project |
| `task` | the task that builds its jar |
| `jar` | where that task writes the jar (`~/` and `*` allowed) |

Name, description and tags come from the plugin's `@PluginDescriptor`.

## Signing key

`./gradlew generateSigningKey` creates the key once, outside this repository, and writes the public key to
`hub-public-key.txt` for VitaLite. Keep the private key safe and never commit it: whoever holds it can publish official
plugins.
