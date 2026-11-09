# VitaLite Plugins

A plugin repository for VitaLite's **Vita Hub**. This one is the official repo, and you can fork it to host your own
plugins. Anyone can then add your repo in the Hub and install, update or uninstall your plugins from inside the client.

Only the manifest and these build tools live here. Jars go up as GitHub releases, and your plugin source stays in its
own repository.

## Install plugins from a repo

In VitaLite, open the **Vita Hub** tab, go to **Hub**, then **Repositories**, and enter the repo as `owner/repo`
(for example `Tonic-Box/VitaLitePlugins`).

## Host your own

You need JDK 11 and the [GitHub CLI](https://cli.github.com) (run `gh auth login` once).

1. **Fork** this repo and clone your fork.
2. **Name it** by editing `gradle.properties`:
   ```properties
   hubName=My Plugins
   hubSigned=false
   ```
3. **List your plugins** in `catalog.json`. Replace the entries with your own:
   ```json
   [
     {
       "id": "myplugin",
       "version": "1.0.0",
       "author": "You",
       "minVita": "1.13.6_3",
       "requires": [],
       "source": "https://github.com/you/MyPlugin",
       "project": "../MyPlugin",
       "task": "jar",
       "jar": "build/libs/MyPlugin-*.jar"
     }
   ]
   ```
4. **Publish** with `./gradlew publishRelease`. This builds each plugin, uploads the jars as releases, and writes,
   commits and pushes `plugins.json`.
5. **Share** your `owner/repo`.

To ship an update, raise the plugin's `version` and run `publishRelease` again. Only changed versions are rebuilt and
uploaded, and users see an **Update** button in the Hub.

To check everything first without uploading or pushing anything, run `./gradlew publishRelease -PdryRun`.

## Catalog fields

| Field | What it is |
|---|---|
| `id` | Lowercase id, such as `myplugin`. It never changes. |
| `version` | Numbers and dots, such as `1.2.0`. Raise it to release. |
| `author` | Shown in the Hub. |
| `minVita` | Oldest VitaLite version the plugin works on. |
| `requires` | Ids of other plugins it needs. They install with it. |
| `source` | Optional link to the plugin's code. |
| `project` | Path to the plugin's Gradle project, relative to this repo. |
| `task` | Gradle task that builds the jar, usually `jar` or `shadowJar`. |
| `jar` | Where that task puts the jar, relative to the project. `*` and `~/` work. |

The plugin's name, description and tags come from its `@PluginDescriptor`, so you don't repeat them here.

## Safety

Users get a warning the first time they add a repo other than the official one, because plugins run with full access
to their client. Every jar is checked against the SHA-256 in `plugins.json` before it loads.

Only the official repo is signed (`hubSigned=true`), with a private key that never leaves the maintainer's machine.
Forks leave signing off.
