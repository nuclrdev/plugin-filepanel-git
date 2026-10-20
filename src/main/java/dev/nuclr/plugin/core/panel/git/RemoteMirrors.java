/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Local copies of remote repositories under the cache directory. Git cannot
 * browse a remote without downloading its objects, so a URL opens as a bare
 * on-demand repository (see {@link LazyRemote}) that downloads only what is
 * looked at; "Download full history" replaces it with a {@code clone --mirror}
 * copy. Both are read like any local bare repository.
 */
final class RemoteMirrors {

	private final Path cacheRoot;
	private final NativeGitRunner nativeGit;

	RemoteMirrors(Path cacheRoot, NativeGitRunner nativeGit) {
		this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
		this.nativeGit = nativeGit;
	}

	static Path defaultCacheRoot() {
		return Path.of(System.getProperty("user.home"), ".nuclr", "git", "mirrors");
	}

	/** True when {@code root} is a mirror owned by this cache. */
	boolean isMirror(Path root) {
		if (root == null) return false;
		Path normalized = root.toAbsolutePath().normalize();
		return normalized.startsWith(cacheRoot) || normalized.startsWith(realCacheRoot());
	}

	/** Deterministic cache location of the full mirror of a URL, so reopening it reuses the mirror. */
	Path pathFor(String url) {
		return cacheRoot.resolve(slug(url) + "-" + hash(url) + ".git");
	}

	/** Cache location of the on-demand repository of a URL. */
	Path onDemandPathFor(String url) {
		return cacheRoot.resolve(slug(url) + "-" + hash(url) + ".lazy.git");
	}

	/**
	 * Opens {@code url}: a full mirror downloaded earlier is fetched and reused;
	 * otherwise an on-demand repository is created (or refreshed), which holds
	 * branch and tag tips only and downloads trees, files and history pages as
	 * they are looked at. Returns the repository directory.
	 */
	Path open(String url, BooleanSupplier cancelled) throws IOException {
		String clean = validate(url);
		Path mirror = pathFor(clean);
		if (Files.isRegularFile(mirror.resolve("HEAD"))) {
			nativeGit.requireSuccess("Fetch " + clean, mirror, cancelled, "fetch", "--prune");
			return mirror;
		}
		Path onDemand = onDemandPathFor(clean);
		if (Files.isRegularFile(onDemand.resolve("HEAD"))) {
			new LazyRemote(nativeGit).refreshRefs(onDemand, cancelled);
			return onDemand;
		}
		return build(onDemand, partial -> createOnDemand(clean, partial, cancelled));
	}

	/**
	 * Replaces the on-demand repository of {@code url} with a full mirror, which
	 * file history and blame need. Returns the mirror directory.
	 */
	Path downloadFullHistory(String url, BooleanSupplier cancelled) throws IOException {
		String clean = validate(url);
		Path mirror = pathFor(clean);
		if (!Files.isRegularFile(mirror.resolve("HEAD"))) {
			build(mirror, partial -> nativeGit.requireSuccess("Clone " + clean, null, cancelled,
					"clone", "--mirror", "--", clean, partial.toString()));
		}
		delete(onDemandPathFor(clean));
		return mirror;
	}

	private void createOnDemand(String url, Path target, BooleanSupplier cancelled) throws IOException {
		nativeGit.requireSuccess("Create repository", null, cancelled, "init", "--quiet", "--bare", target.toString());
		String[][] settings = {
				{ "remote.origin.url", url },
				{ "remote.origin.fetch", "+refs/heads/*:refs/heads/*" },
				{ "remote.origin.promisor", "true" },
				{ "remote.origin.partialclonefilter", "tree:0" },
				{ "remote.origin.tagOpt", "--no-tags" },
				// Background gc races page fetches for the shallow file; the cache is small anyway.
				{ "gc.auto", "0" },
				{ "maintenance.auto", "false" },
				{ GitRepositoryService.ON_DEMAND_SECTION + "." + GitRepositoryService.ON_DEMAND_KEY, "true" } };
		for (String[] setting : settings) {
			nativeGit.requireSuccess("Configure repository", target, cancelled, "config", setting[0], setting[1]);
		}
		nativeGit.requireSuccess("Configure repository", target, cancelled,
				"config", "--add", "remote.origin.fetch", "+refs/tags/*:refs/tags/*");
		new LazyRemote(nativeGit).refreshRefs(target, cancelled);
	}

	/**
	 * Builds a repository beside its final location and moves it into place
	 * afterwards, so a cancelled or failed download never leaves a half-written
	 * repository behind.
	 */
	private Path build(Path target, IoConsumer<Path> creator) throws IOException {
		Files.createDirectories(cacheRoot);
		Path partial = target.resolveSibling(target.getFileName() + ".partial");
		delete(partial);
		try {
			creator.accept(partial);
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			delete(partial);
		}
		return target;
	}

	@FunctionalInterface
	private interface IoConsumer<T> { void accept(T value) throws IOException; }

	/** Deletes a mirror; refuses paths outside the cache. */
	void remove(Path mirror) throws IOException {
		if (!isMirror(mirror) || mirror.toAbsolutePath().normalize().equals(cacheRoot)) {
			throw new IOException("Not a cached remote mirror: " + mirror);
		}
		delete(mirror);
	}

	static String validate(String url) throws IOException {
		String value = url == null ? "" : url.strip();
		if (value.isEmpty()) throw new IOException("Enter a repository URL.");
		// "--" already stops option parsing; this rejects option-looking input early
		// with a clearer message, and keeps the value out of any other argv slot.
		if (value.startsWith("-")) throw new IOException("Not a repository URL: " + value);
		if (value.chars().anyMatch(Character::isISOControl)) throw new IOException("The URL contains control characters.");
		return value;
	}

	/** Human-readable repository name: the last URL segment without {@code .git}. */
	static String displayName(String url) {
		String value = url == null ? "" : url.strip();
		while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
		if (value.toLowerCase(Locale.ROOT).endsWith(".git")) value = value.substring(0, value.length() - 4);
		int cut = Math.max(value.lastIndexOf('/'), Math.max(value.lastIndexOf(':'), value.lastIndexOf('\\')));
		String name = cut < 0 ? value : value.substring(cut + 1);
		return name.isBlank() ? (url == null ? "" : url.strip()) : name;
	}

	private Path realCacheRoot() {
		try {
			return cacheRoot.toRealPath();
		} catch (IOException e) {
			return cacheRoot;
		}
	}

	private static String slug(String url) {
		String name = displayName(url).replaceAll("[^A-Za-z0-9._-]", "_");
		if (name.length() > 40) name = name.substring(0, 40);
		return name.isBlank() || name.startsWith(".") ? "repo" + name : name;
	}

	private static String hash(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 6);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void delete(Path directory) throws IOException {
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
		GitRepositoryService.releasePackFiles();
		Files.walkFileTree(directory, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				deleteWritable(file);
				return FileVisitResult.CONTINUE;
			}

			@Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
				if (failure != null) throw failure;
				try {
					Files.delete(dir);
				} catch (DirectoryNotEmptyException e) {
					throw new IOException("Unable to delete " + dir, e);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Git writes pack files read-only, which Windows refuses to delete. */
	private static void deleteWritable(Path file) throws IOException {
		try {
			Files.delete(file);
		} catch (IOException e) {
			DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (dos == null) throw e;
			dos.setReadOnly(false);
			Files.delete(file);
		}
	}
}
