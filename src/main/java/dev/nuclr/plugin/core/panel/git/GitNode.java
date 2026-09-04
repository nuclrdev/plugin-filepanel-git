/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.Serializable;

/** Serializable navigation address for one node in the Git virtual filesystem. */
record GitNode(Kind kind, String repository, String revision, String relativePath, String value)
		implements Serializable {

	private static final long serialVersionUID = 1L;

	enum Kind {
		ROOT,
		OPEN_REPOSITORY,
		REPOSITORY,
		WORKTREE,
		WORKTREE_DIRECTORY,
		WORKTREE_FILE,
		STATUS,
		STATUS_GROUP,
		STATUS_FILE,
		BRANCHES,
		BRANCH_GROUP,
		REF,
		COMMITS,
		COMMIT,
		TAGS,
		STASHES,
		STASH,
		REVISION_TREE,
		REVISION_BLOB
	}

	GitNode {
		repository = clean(repository);
		revision = clean(revision);
		relativePath = normalizeGitPath(relativePath);
		value = clean(value);
	}

	static GitNode root() {
		return new GitNode(Kind.ROOT, null, null, null, null);
	}

	static GitNode openRepository() {
		return new GitNode(Kind.OPEN_REPOSITORY, null, null, null, null);
	}

	static GitNode repository(String repository) {
		return new GitNode(Kind.REPOSITORY, repository, null, null, null);
	}

	boolean isRevisionNode() {
		return kind == Kind.REF || kind == Kind.COMMIT || kind == Kind.STASH
				|| kind == Kind.REVISION_TREE || kind == Kind.REVISION_BLOB;
	}

	String identity() {
		return String.join("|", kind.name(), clean(repository), clean(revision), clean(relativePath), clean(value));
	}

	private static String clean(String value) {
		return value == null ? "" : value;
	}

	static String normalizeGitPath(String path) {
		if (path == null || path.isBlank() || ".".equals(path)) {
			return "";
		}
		String value = path.replace('\\', '/');
		while (value.startsWith("/")) {
			value = value.substring(1);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}
}
