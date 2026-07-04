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
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class GitmcPlugin extends JavaPlugin {

    private static final Pattern VALID_BRANCH = Pattern.compile("^[A-Za-z0-9._/][A-Za-z0-9._/-]*$");
    private static final List<String> SUBCOMMANDS = List.of("pull", "version", "help");
    private static final int MAX_CHANGED_FILES = 10;

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
            send(sender, "You do not have permission to use /git.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pull" -> handlePull(sender, args);
            case "version" -> handleVersion(sender, args);
            case "help" -> sendHelp(sender);
            default -> send(sender, "Unknown subcommand. Try /git help.", NamedTextColor.RED);
        }
        return true;
    }

    private void handlePull(CommandSender sender, String[] args) {
        boolean silent = false;
        String branch = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-silent")) {
                silent = true;
            } else if (branch == null) {
                branch = args[i];
            }
        }

        if (repository == null) {
            send(sender, "No repository is set in the gitmc config.", NamedTextColor.RED);
            return;
        }
        if (branch != null && !VALID_BRANCH.matcher(branch).matches()) {
            send(sender, "Invalid branch name.", NamedTextColor.RED);
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
            send(sender, "Please wait " + wait + "s before the next pull.", NamedTextColor.RED);
            return true;
        }
        lastPull.put(player.getUniqueId(), now);
        return false;
    }

    private void runPull(CommandSender sender, GitService git, String branch, boolean silent) {
        if (branch != null) {
            if (!git.branchExists(branch)) {
                sync(() -> send(sender, "Branch " + branch + " was not found.", NamedTextColor.RED));
                return;
            }
            GitService.Result checkout = git.run("checkout", branch);
            logLines("checkout", checkout);
            if (!checkout.ok()) {
                sync(() -> send(sender, "Could not switch to branch " + branch + ". See console.", NamedTextColor.RED));
                return;
            }
        }

        String current = git.currentBranch();
        String oldHead = git.head();
        GitService.Result pull = git.run("pull");
        logLines("pull", pull);
        if (!pull.ok()) {
            sync(() -> send(sender, "Sync failed on branch " + current + ". See console.", NamedTextColor.RED));
            return;
        }

        String newHead = git.head();
        int commits = git.commitCount(oldHead, newHead);
        if (commits == 0) {
            sync(() -> send(sender, "Already up to date on branch " + current + ".", NamedTextColor.GREEN));
            return;
        }

        List<String> files = git.changedFiles(oldHead, newHead);
        sync(() -> applyPull(current, commits, files, silent));
    }

    private void applyPull(String branch, int commits, List<String> files, boolean silent) {
        if (!silent) {
            String plural = commits == 1 ? "" : "s";
            broadcastToOps("Synced " + commits + " commit" + plural + " on branch " + branch + ".", NamedTextColor.GREEN);
            broadcastChangedFiles(files);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
        if (!silent) {
            broadcastToOps("Reloaded.", NamedTextColor.GREEN);
        }
    }

    private void broadcastChangedFiles(List<String> files) {
        int shown = Math.min(files.size(), MAX_CHANGED_FILES);
        for (int i = 0; i < shown; i++) {
            broadcastToOps("  " + files.get(i), NamedTextColor.GRAY);
        }
        if (files.size() > shown) {
            broadcastToOps("  and " + (files.size() - shown) + " more", NamedTextColor.GRAY);
        }
    }

    private void handleVersion(CommandSender sender, String[] args) {
        if (repository == null) {
            send(sender, "No repository is set in the gitmc config.", NamedTextColor.RED);
            return;
        }

        int count = 1;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                send(sender, "Give a number of commits between 1 and 20.", NamedTextColor.RED);
                return;
            }
            count = Math.max(1, Math.min(20, count));
        }

        GitService git = new GitService(repository);
        int limit = count;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String branch = git.currentBranch();
            GitService.Result log = git.run("log", "-n", String.valueOf(limit), "--pretty=%h %s (%cr)");
            sync(() -> {
                send(sender, "Branch " + branch, NamedTextColor.AQUA);
                if (log.lines().isEmpty()) {
                    send(sender, "No commits found.", NamedTextColor.GRAY);
                } else {
                    for (String line : log.lines()) {
                        send(sender, line, NamedTextColor.GRAY);
                    }
                }
            });
        });
    }

    private void sendHelp(CommandSender sender) {
        send(sender, "gitmc commands:", NamedTextColor.GOLD);
        send(sender, "/git pull [branch] [-silent]: pull the repo and reload", NamedTextColor.GRAY);
        send(sender, "/git version [n]: show the branch and recent commits", NamedTextColor.GRAY);
        send(sender, "/git help: show this help", NamedTextColor.GRAY);
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
        if (args.length == 2 && args[0].equalsIgnoreCase("pull")) {
            return filter(List.of("-silent"), args[1]);
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

    private void broadcastToOps(String text, NamedTextColor color) {
        Component component = Component.text(text, color);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(component);
            }
        }
        getLogger().info(text);
    }

    private void send(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
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
