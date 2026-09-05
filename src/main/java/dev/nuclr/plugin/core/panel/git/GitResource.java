/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import dev.nuclr.platform.plugin.NuclrResource;

/** One working-tree or virtual Git entry shown by {@link GitFilePanelPlugin}. */
final class GitResource extends NuclrResource {

	private static final long serialVersionUID = 1L;
	static final String META_KIND = "git.kind";
	static final String META_REPOSITORY = "git.repository";
	static final String META_REVISION = "git.revision";
	static final String META_PATH = "git.relativePath";
	static final String META_MARKER = "Git";

	private final GitNode node;
	private final GitNode parentNode;

	GitResource(GitNode node, String name, boolean folder, Path localPath, long size) {
		this(node, null, name, folder, localPath, size);
	}

	GitResource(GitNode node, GitNode parentNode, String name, boolean folder, Path localPath, long size) {
		super(folder ? Path.of(".nuclr-git", node.kind().name().toLowerCase(Locale.ROOT)) : localPath);
		this.node = node;
		this.parentNode = parentNode;
		this.name = name;
		this.folder = folder;
		this.length = Math.max(0L, size);
		this.uuid = "git://" + node.identity();
		this.fullPath = displayPath(node);
		metadata.put(META_KIND, node.kind().name());
		metadata.put(META_REPOSITORY, node.repository());
		metadata.put(META_REVISION, node.revision());
		metadata.put(META_PATH, node.relativePath());
		metadata.put("git.localPath", localPath == null ? "" : localPath.toAbsolutePath().normalize().toString());
		metadata.put("Name", name);
		metadata.put(META_MARKER, "");
		metadata.put("Size", folder ? "" : displaySize(length));
		metadata.put("Author", "");
		metadata.put("Date", "");
		metadata.put("Message", "");
	}

	GitNode node() {
		return node;
	}

	GitNode parentNode() {
		return parentNode;
	}

	GitResource marker(String marker) {
		metadata.put(META_MARKER, marker == null ? "" : marker);
		return this;
	}

	GitResource link(boolean link) {
		setLink(link);
		return this;
	}

	GitResource details(String author, Instant instant, String message) {
		metadata.put("Author", author == null ? "" : author);
		metadata.put("Date", instant == null ? "" : instant.toString());
		metadata.put("Message", message == null ? "" : message);
		if (instant != null) lastModifiedDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
		return this;
	}

	GitResource column(String name, Object value) {
		metadata.put(name, value == null ? "" : value);
		return this;
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if ((node.kind() == GitNode.Kind.COMMIT || node.kind() == GitNode.Kind.REF || node.kind() == GitNode.Kind.STASH)
				&& !node.repository().isBlank() && !node.revision().isBlank()) {
			String details = new GitRepositoryService().commitDetails(Path.of(node.repository()), node.revision());
			return new ByteArrayInputStream(details.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		if (folder) throw new IOException("Cannot read a Git directory.");
		if (node.kind() == GitNode.Kind.WORKTREE_FILE && path != null) {
			return Files.newInputStream(path, options);
		}
		if (node.kind() == GitNode.Kind.STATUS_FILE) {
			Path working = Path.of(node.repository()).resolve(node.relativePath()).normalize();
			if (Files.isRegularFile(working)) return Files.newInputStream(working, options);
			return new GitRepositoryService().openBlobAtPath(Path.of(node.repository()), "HEAD", node.relativePath());
		}
		if (node.kind() == GitNode.Kind.REVISION_BLOB && !node.value().isBlank()) {
			return new GitRepositoryService().openBlob(Path.of(node.repository()), node.value());
		}
		throw new IOException("This Git resource has no directly readable content.");
	}

	static boolean isGit(NuclrResource resource) {
		return resource instanceof GitResource;
	}

	static String displaySize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		double value = bytes;
		String[] units = { "KB", "MB", "GB", "TB" };
		int unit = -1;
		do {
			value /= 1024.0;
			unit++;
		} while (value >= 1024 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	private static String displayPath(GitNode node) {
		String repo = node.repository().isBlank() ? "" : node.repository();
		String revision = node.revision().isBlank() ? "" : "@" + node.revision();
		String relative = node.relativePath().isBlank() ? "" : "/" + node.relativePath();
		return "git:" + repo + revision + relative;
	}
}
