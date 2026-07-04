# gitmc

A small Paper plugin that pulls a git repo from in game and reloads the server.
Handy for a datapack or resource pack you keep in git: push a change, then run
`/git pull` in game to sync and reload without touching the console.

## Install

1. Build the jar (see Build) or drop a released `gitmc-*.jar` into `plugins/`.
2. Start the server once so it writes `plugins/gitmc/config.yml`.
3. Set `repository` to the folder that holds the `.git` directory.
4. Restart or run `/reload`.

## Config

`plugins/gitmc/config.yml`:

```yaml
# Path to the git repo to pull (the folder that contains .git).
# Absolute, or relative to the server folder. Blank disables pulls.
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

The jar lands in `build/libs/`.
