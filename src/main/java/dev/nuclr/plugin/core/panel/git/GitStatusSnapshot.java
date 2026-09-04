/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Status;

/** Immutable, two-sided (index/worktree) repository status. */
final class GitStatusSnapshot {

	record Entry(String path, char index, char worktree, boolean conflict, boolean ignored) {
		String marker() {
			if (conflict) return "UU";
			if (ignored) return "!!";
			if (index == '?' && worktree == '?') return "??";
			return new String(new char[] { index == 0 ? ' ' : index, worktree == 0 ? ' ' : worktree });
		}

		boolean staged() {
			return index != 0 && index != '?';
		}

		boolean modified() {
			return conflict || (worktree != 0 && worktree != '?');
		}

		boolean untracked() {
			return index == '?' && worktree == '?';
		}
	}

	private final Map<String, Entry> entries;

	private GitStatusSnapshot(Map<String, Entry> entries) {
		this.entries = Map.copyOf(entries);
	}

	static GitStatusSnapshot from(Status status) {
		var values = new LinkedHashMap<String, Mutable>();
		mark(values, status.getAdded(), mutable -> mutable.index = 'A');
		mark(values, status.getChanged(), mutable -> mutable.index = 'M');
		mark(values, status.getRemoved(), mutable -> mutable.index = 'D');
		mark(values, status.getModified(), mutable -> mutable.worktree = 'M');
		mark(values, status.getMissing(), mutable -> mutable.worktree = 'D');
		mark(values, status.getUntracked(), mutable -> { mutable.index = '?'; mutable.worktree = '?'; });
		mark(values, status.getConflicting(), mutable -> mutable.conflict = true);
		mark(values, status.getIgnoredNotInIndex(), mutable -> mutable.ignored = true);

		var immutable = new LinkedHashMap<String, Entry>();
		values.forEach((path, value) -> immutable.put(path,
				new Entry(path, value.index, value.worktree, value.conflict, value.ignored)));
		return new GitStatusSnapshot(immutable);
	}

	static GitStatusSnapshot empty() {
		return new GitStatusSnapshot(Map.of());
	}

	Entry entry(String path) {
		return entries.get(GitNode.normalizeGitPath(path));
	}

	String marker(String path, boolean directory) {
		String normalized = GitNode.normalizeGitPath(path);
		Entry exact = entries.get(normalized);
		if (exact != null) return exact.marker();
		if (!directory) return "";
		String prefix = normalized.isEmpty() ? "" : normalized + "/";
		boolean staged = false;
		boolean modified = false;
		boolean untracked = false;
		for (Entry candidate : entries.values()) {
			if (!candidate.path().startsWith(prefix)) continue;
			if (candidate.conflict()) return "UU";
			staged |= candidate.staged();
			modified |= candidate.modified();
			untracked |= candidate.untracked();
		}
		if (staged && modified) return "MM";
		if (staged) return "S ";
		if (modified) return " M";
		if (untracked) return "??";
		return "";
	}

	List<Entry> staged() {
		return filter(Entry::staged);
	}

	List<Entry> modified() {
		return filter(entry -> !entry.conflict() && entry.modified());
	}

	List<Entry> untracked() {
		return filter(Entry::untracked);
	}

	List<Entry> conflicts() {
		return filter(Entry::conflict);
	}

	Collection<Entry> all() {
		return entries.values();
	}

	boolean clean() {
		return entries.isEmpty();
	}

	private List<Entry> filter(java.util.function.Predicate<Entry> predicate) {
		return entries.values().stream().filter(predicate)
				.sorted(Comparator.comparing(Entry::path, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	private static void mark(Map<String, Mutable> entries, Set<String> paths,
			java.util.function.Consumer<Mutable> update) {
		for (String raw : paths) {
			String path = GitNode.normalizeGitPath(raw);
			Mutable value = entries.computeIfAbsent(path, ignored -> new Mutable());
			update.accept(value);
		}
	}

	private static final class Mutable {
		char index;
		char worktree;
		boolean conflict;
		boolean ignored;
	}
}
