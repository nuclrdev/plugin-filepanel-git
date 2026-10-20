/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Native-git side of on-demand ("lazy") remote repositories: a bare repository
 * whose origin is a promisor remote, holding only the objects that were
 * looked at. JGit reads what is present; this class fetches what is missing.
 *
 * <p>History is fetched in pages: a page is {@code fetch --depth=N} from a
 * start commit, and the next page starts at the parent of the oldest commit
 * on the page's first-parent line. The start commits are kept, oldest last,
 * in {@code nuclr-pages} inside the git directory.
 */
final class LazyRemote {

	static final int DEFAULT_PAGE_DEPTH = 10;
	private static final String PAGES_FILE = "nuclr-pages";
	/** Git refuses concurrent writers to one shallow file; serialise per repository. */
	private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

	private final NativeGitRunner nativeGit;

	LazyRemote(NativeGitRunner nativeGit) {
		this.nativeGit = nativeGit;
	}

	/**
	 * Fetches a missing tree or blob. A tree arrives with all of its subtrees,
	 * a blob on its own. Never call this for a commit: a lazy commit fetch has
	 * no depth limit and would download its entire history.
	 */
	void ensure(Path root, AnyObjectId id) throws IOException {
		synchronized (lock(root)) {
			nativeGit.requireSuccess("Download " + id.abbreviate(8).name(), root, null, "cat-file", "-t", id.name());
		}
	}

	/** Fetches {@code commit} and its ancestors {@code depth} steps deep, without trees or files. */
	void fetchCommits(Path root, String commit, int depth, BooleanSupplier cancelled) throws IOException {
		synchronized (lock(root)) {
			nativeGit.requireSuccess("Fetch history", root, cancelled, "fetch", "--quiet", "--no-tags",
					"--no-write-fetch-head", "--depth=" + Math.max(1, depth), "--filter=tree:0", "origin", commit);
		}
	}

	/** Branch and tag tips at depth 1, and HEAD pointed at the server's default branch. */
	void refreshRefs(Path root, BooleanSupplier cancelled) throws IOException {
		synchronized (lock(root)) {
			NativeGitRunner.Result head = nativeGit.run(root, cancelled, "ls-remote", "--symref", "origin", "HEAD");
			if (!head.succeeded()) throw new NativeGitRunner.GitCommandException("Contact remote", head);
			nativeGit.requireSuccess("Fetch branches and tags", root, cancelled, "fetch", "--quiet", "--prune",
					"--depth=1", "--filter=tree:0", "origin");
			String branch = defaultBranch(head.output());
			if (branch != null) nativeGit.requireSuccess("Set HEAD", root, cancelled, "symbolic-ref", "HEAD", branch);
			// Branch tips moved, so pages anchored at the old HEAD no longer describe it.
			Files.deleteIfExists(root.resolve(PAGES_FILE));
		}
	}

	List<String> pages(Path root) throws IOException {
		Path file = root.resolve(PAGES_FILE);
		if (!Files.isRegularFile(file)) return List.of();
		var result = new ArrayList<String>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String value = line.strip();
			if (ObjectId.isId(value)) result.add(value);
		}
		return result;
	}

	void addPage(Path root, String commit) throws IOException {
		if (!ObjectId.isId(commit)) throw new IOException("Not a commit id: " + commit);
		Files.writeString(root.resolve(PAGES_FILE), commit + "\n", StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** {@code ls-remote --symref origin HEAD} prints {@code ref: refs/heads/main	HEAD} first. */
	static String defaultBranch(String lsRemoteOutput) {
		for (String line : lsRemoteOutput.split("\\R")) {
			if (line.startsWith("ref: ") && line.endsWith("HEAD")) {
				String ref = line.substring(5).split("\\s+")[0];
				return ref.startsWith("refs/heads/") ? ref : null;
			}
		}
		return null;
	}

	private static Object lock(Path root) {
		return LOCKS.computeIfAbsent(root.toAbsolutePath().normalize(), key -> new Object());
	}
}
