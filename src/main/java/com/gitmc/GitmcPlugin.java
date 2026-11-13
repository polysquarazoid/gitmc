package com.gitmc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class GitmcPlugin extends JavaPlugin {

    private static final String PREFIX = "[Git] ";

    private static final TextColor INFO = TextColor.color(0xFFDD21);
    private static final TextColor SUCCESS = TextColor.color(0x34E009);
    private static final TextColor ERROR = TextColor.color(0xF85149);
    private static final TextColor TEXT = NamedTextColor.WHITE;
    private static final TextColor HASH = NamedTextColor.GRAY;

    private static final Pattern VALID_BRANCH = Pattern.compile("^[A-Za-z0-9._/][A-Za-z0-9._/-]*$");
    private static final List<String> SUBCOMMANDS = List.of("pull", "branches", "version", "help");
    private final Map<UUID, Long> lastPull = new ConcurrentHashMap<>();

    private File repository;
    private long cooldownMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getLogger().info("gitmc enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        String path = getConfig().getString("repository", "").trim();
        repository = resolveRepository(path);
        cooldownMillis = Math.max(0, getConfig().getLong("cooldown-seconds", 10)) * 1000L;

        if (repository == null) {
            getLogger().warning("No repository set in config.yml. /git pull is disabled until one is set.");
        } else if (!new File(repository, ".git").exists()) {
            getLogger().warning("Configured repository has no .git folder: " + repository.getAbsolutePath());
        }
    }

    private File resolveRepository(String path) {
        if (path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            File serverRoot = getDataFolder().getParentFile().getParentFile();
            file = new File(serverRoot, path);
        }
        return file;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("git")) {
            return false;
        }
        if (!sender.hasPermission("gitmc.use")) {
            send(sender, "You do not have permission to use /git", ERROR);
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pull" -> handlePull(sender, args);
            case "branches" -> handleBranches(sender);
            case "version" -> handleVersion(sender, args);
            case "help" -> sendHelp(sender);
            default -> send(sender, "Unknown command, try /git help", ERROR);
        }
        return true;
    }

    private void handlePull(CommandSender sender, String[] args) {
        boolean silent = false;
        String branch = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-silent") || args[i].equalsIgnoreCase("-s")) {
                silent = true;
            } else if (branch == null) {
                branch = args[i];
            }
        }

        if (repository == null) {
            send(sender, "No repository is set in the config", ERROR);
            return;
        }
        if (branch != null && !VALID_BRANCH.matcher(branch).matches()) {
            send(sender, "Failed to find branch " + branch, ERROR);
            return;
        }
        if (sender instanceof Player player && isOnCooldown(player, sender)) {
            return;
        }

        GitService git = new GitService(repository);
        String targetBranch = branch;
        boolean quiet = silent;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> runPull(sender, git, targetBranch, quiet));
    }

    private boolean isOnCooldown(Player player, CommandSender sender) {
        long now = System.currentTimeMillis();
        Long last = lastPull.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            long wait = (cooldownMillis - (now - last) + 999) / 1000;
            send(sender, "Wait " + wait + "s before pulling again", ERROR);
            return true;
        }
        lastPull.put(player.getUniqueId(), now);
        return false;
    }

    private void runPull(CommandSender sender, GitService git, String branch, boolean silent) {
        // Capture HEAD before checkout so branch switches include new commits.
        String startBranch = git.currentBranch();
        String oldHead = git.head();

        boolean switched = false;
        if (branch != null) {
            if (!git.branchExists(branch)) {
                sync(() -> send(sender, "Failed to find branch " + branch, ERROR));
                return;
            }
            GitService.Result checkout = git.run("checkout", branch);
            logLines("checkout", checkout);
            if (!checkout.ok()) {
                sync(() -> send(sender, "Could not switch to " + branch + ". Check the console", ERROR));
                return;
            }
            switched = !branch.equals(startBranch);
        }

        String current = git.currentBranch();
        if (!silent && !switched) {
            sync(() -> send(sender, "Pulling " + current, INFO));
        }

        GitService.Result pull = git.run("pull");
        logLines("pull", pull);
        if (!pull.ok()) {
            sync(() -> send(sender, "Pull failed. Check the console", ERROR));
            return;
        }

        String newHead = git.head();
        int commits = git.commitCount(oldHead, newHead);
        List<String> newCommits = git.commits(oldHead, newHead);
        if (commits == 0) {
            newCommits = git.latestCommit();
        }
        boolean didSwitch = switched;
        List<String> commitsToShow = newCommits;
        sync(() -> applyResult(sender, current, didSwitch, commits, commitsToShow, silent));
    }

    private void applyResult(CommandSender sender, String branch, boolean switched, int commits,
            List<String> commitsToShow, boolean silent) {
        if (!silent) {
            if (switched) {
                broadcastToOps("Switched to branch " + branch, SUCCESS);
            } else if (commits > 0) {
                String plural = commits == 1 ? "" : "s";
                broadcastToOps("Synced " + commits + " commit" + plural + " on branch " + branch, SUCCESS);
            } else {
                broadcastToOps("Already up to date on branch " + branch, SUCCESS);
            }
            broadcastCommits(commitsToShow, commits > 0);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
        if (!silent) {
            broadcastToOps("Reloaded", SUCCESS);
        }
    }

    private void broadcastCommits(List<String> commits, boolean added) {
        for (String line : commits) {
            if (line.isBlank()) {
                continue;
            }
            Component component = added
                    ? Component.text("+ ", SUCCESS).append(commitLine(line))
                    : commitLine(line);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp()) {
                    player.sendMessage(component);
                }
            }
            getLogger().info((added ? "+ " : "") + line);
        }
    }

    private void handleVersion(CommandSender sender, String[] args) {
        if (repository == null) {
            send(sender, "No repository is set in the config", ERROR);
            return;
        }

        int count = 1;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                send(sender, "Enter a number from 1 to 20", ERROR);
                return;
            }
            count = Math.max(1, Math.min(20, count));
        }

        GitService git = new GitService(repository);
        int limit = count;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String branch = git.currentBranch();
            GitService.Result log = git.run("log", "-n", String.valueOf(limit),
                    "--pretty=%an%x1f%s%x1f%ct");
            sync(() -> {
                if (!log.ok()) {
                    logLines("log", log);
                    send(sender, "Could not read commit history. Check the console", ERROR);
                    return;
                }
                String plural = limit == 1 ? "" : "s";
                send(sender, "Branch " + branch + ", latest commit" + plural + ":", INFO);
                for (String line : log.lines()) {
                    if (!line.isBlank()) {
                        sendComponent(sender, commitLine(line));
                    }
                }
            });
        });
    }

    // Show the author and age in grey, with the commit message in white.
    private Component commitLine(String line) {
        String[] parts = line.split("\u001f", 3);
        if (parts.length < 3) {
            return Component.text(line, HASH);
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException exception) {
            return Component.text(line, HASH);
        }
        return Component.text(parts[0] + " ", HASH)
                .append(Component.text(parts[1], TEXT))
                .append(Component.text(" [" + commitAge(timestamp) + "]", HASH));
    }

    private String commitAge(long timestamp) {
        long seconds = Math.max(0, System.currentTimeMillis() / 1000 - timestamp);
        if (seconds < 10) {
            return "now";
        }
        if (seconds < 60) {
            return (seconds / 10 * 10) + "s ago";
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }

        long hours = minutes / 60;
        if (hours == 1) {
            long remainingMinutes = minutes % 60;
            return remainingMinutes == 0 ? "1h ago" : "1h " + remainingMinutes + "m ago";
        }
        if (hours < 24) {
            return hours + "h ago";
        }

        long days = hours / 24;
        if (days < 13) {
            if (days == 1) {
                long remainingHours = hours % 24;
                return remainingHours == 0 ? "1d ago" : "1d " + remainingHours + "h ago";
            }
            return days + "d ago";
        }

        long weeks = days / 7;
        return weeks + (weeks == 1 ? " week ago" : " weeks ago");
    }

    private void handleBranches(CommandSender sender) {
        if (repository == null) {
            send(sender, "No repository is set in the config", ERROR);
            return;
        }
        GitService git = new GitService(repository);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String current = git.currentBranch();
            List<String> branches = git.branches();
            sync(() -> {
                send(sender, "Branches:", INFO);
                if (branches.isEmpty()) {
                    sendRaw(sender, "none", HASH);
                    return;
                }
                for (String name : branches) {
                    if (name.equals(current)) {
                        sendRaw(sender, name + " (current)", SUCCESS);
                    } else {
                        sendRaw(sender, name, TEXT);
                    }
                }
            });
        });
    }

    private void sendHelp(CommandSender sender) {
        send(sender, "Commands:", INFO);
        helpLine(sender, "/git pull [<branch>] [-silent]", "pull a branch", "/git pull ", true);
        helpLine(sender, "/git branches", "list branches", "/git branches", false);
        helpLine(sender, "/git version [<n>]", "show recent commits", "/git version 10", false);
        helpLine(sender, "/git help", "show this list", "/git help", false);
    }

    private void helpLine(CommandSender sender, String command, String description, String action,
            boolean autofill) {
        String tooltip = autofill ? "Click to autofill" : "Click to Run";
        ClickEvent click = autofill ? ClickEvent.suggestCommand(action) : ClickEvent.runCommand(action);
        Component line = Component.text(command, TEXT)
                .append(Component.text(": " + description, HASH))
                .clickEvent(click)
                .hoverEvent(HoverEvent.showText(Component.text(tooltip, TEXT)));
        sendComponent(sender, line);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("git")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private void broadcastToOps(String text, TextColor color) {
        Component component = Component.text(PREFIX + text, color);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(component);
            }
        }
        getLogger().info(text);
    }

    private void send(CommandSender sender, String text, TextColor color) {
        sender.sendMessage(Component.text(PREFIX + text, color));
    }

    private void sendRaw(CommandSender sender, String text, TextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    private void sendComponent(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    private void logLines(String label, GitService.Result result) {
        for (String line : result.lines()) {
            getLogger().info("[" + label + "] " + line);
        }
    }

    private void sync(Runnable runnable) {
        Bukkit.getScheduler().runTask(this, runnable);
    }
}
