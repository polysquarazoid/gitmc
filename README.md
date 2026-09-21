# gitmc

Lightweight PaperMC plugin providing simple git cmds in-game to sync repos like datapacks.
Push locally, `/git pull` on remote servers with in-game op perms, rather then the console

## Install

1. Build the jar or drop a released `gitmc-*.jar` into your server's `plugins/`.
2. Restart the server to generate `config.yml`
3. Set `repository` to the folder that holds the `.git` directory via the `config.yml`.
4. Restart again to apply config.

## Config

`plugins/gitmc/config.yml`:

```yaml
# Path to the git repo contain the .git
# Blank disables
repository: ""

# Seconds a player must wait between /git pull commands.
cooldown-seconds: 10
```

## Commands

Needs the `gitmc.use` permission (default: op).

- `/git pull` pulls the current branch and reloads
- `/git pull <branch>` switches to that branch and pulls it
- `/git pull -silent` pulls without announcing to ops
- `/git version [n]` shows the branch and the latest commit, or n commits (1 to 20)
- `/git help` lists the commands

## Build

Java 25 and the bundled Gradle wrapper:

```bash
./gradlew build
```

