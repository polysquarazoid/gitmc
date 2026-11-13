# gitmc

A small Paper plugin for pulling Git repos in game. Handy for datapacks and resource packs.
Push changes from your computer, then run `/git pull` on the server.

## Install

1. Put `gitmc-*.jar` in the server's `plugins/` folder.
2. Start the server to create `plugins/gitmc/config.yml`.
3. Set `repository` to your Git repo folder.
4. Restart the server.

## Config

`plugins/gitmc/config.yml`:

```yaml
# Path to the Git repo. Leave blank to disable pulls.
repository: ""

# Wait time between pulls, in seconds.
cooldown-seconds: 10
```

## Commands

Requires `gitmc.use` (ops by default).

- `/git pull` pulls the current branch and reloads.
- `/git pull <branch>` switches branches, then pulls.
- `/git pull -silent` pulls without notifying ops.
- `/git version [n]` shows the latest commit, or the latest 1 to 20 commits.
- `/git branches` lists available branches.
- `/git help` lists commands.

## Build

Build with Java 25:

```bash
./gradlew build
```

