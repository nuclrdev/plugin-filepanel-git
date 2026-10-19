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
 * Local {@code git clone --mirror} copies of remote repositories. Git cannot
 * browse a remote without downloading its objects, so a remote URL is opened
 * as a bare mirror under the cache directory and read like any local bare
 * repository; Fetch keeps it current.
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

	/** Deterministic cache location for a URL, so reopening it reuses the mirror. */
	Path pathFor(String url) {
		return cacheRoot.resolve(slug(url) + "-" + hash(url) + ".git");
	}

	/**
	 * Clones {@code url} as a mirror, or fetches into the existing mirror when it
	 * was opened before. Returns the mirror directory.
	 */
	Path open(String url, BooleanSupplier cancelled) throws IOException {
		String clean = validate(url);
		Path mirror = pathFor(clean);
		if (Files.isRegularFile(mirror.resolve("HEAD"))) {
			nativeGit.requireSuccess("Fetch " + clean, mirror, cancelled, "fetch", "--prune");
			return mirror;
		}
		Files.createDirectories(cacheRoot);
		// Clone beside the final location and move into place afterwards, so a
		// cancelled or failed clone never leaves a half-written mirror behind.
		Path partial = mirror.resolveSibling(mirror.getFileName() + ".partial");
		delete(partial);
		try {
			nativeGit.requireSuccess("Clone " + clean, null, cancelled,
					"clone", "--mirror", "--", clean, partial.toString());
			Files.move(partial, mirror, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			delete(partial);
		}
		return mirror;
	}

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
