package com.gitmc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs git against a single repo folder. Each call shells out to the git
 * binary, so cached credentials and ssh keys work the same as on the console.
 */
final class GitService {

    record Result(int exit, List<String> lines) {
        boolean ok() {
            return exit == 0;
        }

        String first(String fallback) {
            return lines.isEmpty() ? fallback : lines.get(0);
        }
    }

    private final File repo;

    GitService(File repo) {
        this.repo = repo;
    }

    Result run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            process.waitFor();
            return new Result(process.exitValue(), lines);
        } catch (IOException exception) {
            return new Result(-1, List.of("Failed to run git: " + exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(-1, List.of("git was interrupted"));
        }
    }

    boolean branchExists(String branch) {
        return run("rev-parse", "--verify", "refs/heads/" + branch).ok()
                || run("rev-parse", "--verify", "refs/remotes/origin/" + branch).ok();
    }

    String currentBranch() {
        return run("rev-parse", "--abbrev-ref", "HEAD").first("unknown").trim();
    }

    String head() {
        return run("rev-parse", "HEAD").first("").trim();
    }

    int commitCount(String oldHead, String newHead) {
        if (oldHead.isEmpty() || newHead.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(run("rev-list", "--count", oldHead + ".." + newHead).first("0").trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    List<String> changedFiles(String oldHead, String newHead) {
        return run("diff", "--name-status", oldHead + ".." + newHead).lines();
    }

    // Local branches followed by remote branches, deduped, origin/ stripped, no HEAD.
    List<String> branches() {
        Result result = run("for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes/origin");
        Set<String> names = new LinkedHashSet<>();
        for (String line : result.lines()) {
            String name = line.trim();
            if (name.isEmpty() || name.equals("origin") || name.endsWith("/HEAD")) {
                continue;
            }
            if (name.startsWith("origin/")) {
                name = name.substring("origin/".length());
            }
            names.add(name);
        }
        return new ArrayList<>(names);
    }
}
