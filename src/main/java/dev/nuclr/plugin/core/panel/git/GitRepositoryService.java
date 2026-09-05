/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;

/** JGit-backed read model for repositories and historical trees. */
final class GitRepositoryService {

	record RepositoryInfo(Path root, String branch, String state, boolean bare) {}
	record RefInfo(String name, String fullName, String revision) {}
	record CommitInfo(String id, String shortId, String subject, String author, Instant instant, int parentCount) {}
	record TreeEntry(String name, String path, String objectId, boolean directory, boolean symlink,
			boolean gitlink, boolean executable, long size) {}

	Path findRepository(Path candidate) throws IOException {
		if (candidate == null) return null;
		Path start = Files.isDirectory(candidate) ? candidate : candidate.getParent();
		if (start == null) return null;
		try (Repository repository = openDiscovered(start)) {
			if (repository == null) return null;
			return repositoryRoot(repository);
		}
	}

	RepositoryInfo info(Path root) throws IOException {
		try (Repository repository = open(root)) {
			String full = repository.getFullBranch();
			String branch;
			if (full == null) {
				branch = "unborn";
			} else if (full.startsWith("refs/heads/")) {
				branch = Repository.shortenRefName(full);
			} else {
				ObjectId head = repository.resolve("HEAD");
				branch = head == null ? "unborn" : "detached @ " + head.abbreviate(7).name();
			}
			return new RepositoryInfo(repositoryRoot(repository), branch,
					repository.getRepositoryState().getDescription(), repository.isBare());
		}
	}

	GitStatusSnapshot status(Path root) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			if (repository.isBare()) return GitStatusSnapshot.empty();
			return GitStatusSnapshot.from(git.status().call());
		} catch (GitAPIException e) {
			throw new IOException("Unable to read repository status", e);
		}
	}

	List<RefInfo> branches(Path root, boolean remote) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			var command = git.branchList();
			if (remote) command.setListMode(ListMode.REMOTE);
			return refs(repository, command.call());
		} catch (GitAPIException e) {
			throw new IOException("Unable to list branches", e);
		}
	}

	List<RefInfo> tags(Path root) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			return refs(repository, git.tagList().call());
		} catch (GitAPIException e) {
			throw new IOException("Unable to list tags", e);
		}
	}

	List<CommitInfo> stashes(Path root) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			var result = new ArrayList<CommitInfo>();
			int index = 0;
			for (RevCommit commit : git.stashList().call()) {
				result.add(commit(commit, "stash@{" + index++ + "}"));
			}
			return result;
		} catch (GitAPIException e) {
			throw new IOException("Unable to list stashes", e);
		}
	}

	List<CommitInfo> commits(Path root, String revision, int maxCount) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			ObjectId start = repository.resolve(blank(revision) ? "HEAD" : revision);
			if (start == null) return List.of();
			var result = new ArrayList<CommitInfo>();
			for (RevCommit commit : git.log().add(start).setMaxCount(maxCount).call()) {
				result.add(commit(commit, null));
			}
			return result;
		} catch (GitAPIException e) {
			throw new IOException("Unable to list commits", e);
		}
	}

	List<TreeEntry> tree(Path root, String revision, String relativePath) throws IOException {
		try (Repository repository = open(root)) {
			ObjectId tree = repository.resolve(revision + "^{tree}");
			if (tree == null) return List.of();
			String path = GitNode.normalizeGitPath(relativePath);
			if (!path.isEmpty()) {
				try (TreeWalk found = TreeWalk.forPath(repository, path, tree)) {
					if (found == null || !FileMode.TREE.equals(found.getFileMode(0))) return List.of();
					tree = found.getObjectId(0);
				}
			}
			var entries = new ArrayList<TreeEntry>();
			try (TreeWalk walk = new TreeWalk(repository)) {
				walk.addTree(tree);
				walk.setRecursive(false);
				while (walk.next()) {
					FileMode mode = walk.getFileMode(0);
					boolean directory = FileMode.TREE.equals(mode);
					boolean symlink = FileMode.SYMLINK.equals(mode);
					boolean gitlink = FileMode.GITLINK.equals(mode);
					boolean executable = FileMode.EXECUTABLE_FILE.equals(mode);
					ObjectId objectId = walk.getObjectId(0);
					long size = directory || gitlink ? 0L : repository.open(objectId).getSize();
					String childPath = path.isEmpty() ? walk.getNameString() : path + "/" + walk.getNameString();
					entries.add(new TreeEntry(walk.getNameString(), childPath, objectId.name(), directory,
							symlink, gitlink, executable, size));
				}
			}
			entries.sort(Comparator.comparing(TreeEntry::directory).reversed()
					.thenComparing(TreeEntry::name, String.CASE_INSENSITIVE_ORDER));
			return entries;
		}
	}

	byte[] blob(Path root, String objectId) throws IOException {
		try (InputStream input = openBlob(root, objectId)) {
			return input.readAllBytes();
		}
	}

	byte[] blob(Path root, String objectId, int maximumBytes) throws IOException {
		try (InputStream input = openBlob(root, objectId)) {
			return input.readNBytes(maximumBytes + 1);
		}
	}

	InputStream openBlob(Path root, String objectId) throws IOException {
		Repository repository = open(root);
		ObjectReader reader = repository.newObjectReader();
		try {
			ObjectStream stream = reader.open(ObjectId.fromString(objectId)).openStream();
			return new FilterInputStream(stream) {
				@Override public void close() throws IOException {
					try {
						super.close();
					} finally {
						reader.close();
						repository.close();
					}
				}
			};
		} catch (IOException | RuntimeException e) {
			reader.close();
			repository.close();
			throw e;
		}
	}

	InputStream openBlobAtPath(Path root, String revision, String path) throws IOException {
		String normalized = GitNode.normalizeGitPath(path);
		String objectId;
		try (Repository repository = open(root)) {
			ObjectId tree = repository.resolve(revision + "^{tree}");
			if (tree == null) throw new IOException("Revision not found: " + revision);
			try (TreeWalk found = TreeWalk.forPath(repository, normalized, tree)) {
				if (found == null || FileMode.TREE.equals(found.getFileMode(0))) {
					throw new IOException("File not found in " + revision + ": " + normalized);
				}
				objectId = found.getObjectId(0).name();
			}
		}
		return openBlob(root, objectId);
	}

	String workingDiff(Path root, String path, boolean staged) throws IOException {
		try (Repository repository = open(root);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				DiffFormatter formatter = formatter(repository, output)) {
			if (!blank(path)) formatter.setPathFilter(PathFilter.create(GitNode.normalizeGitPath(path)));
			AbstractTreeIterator oldTree;
			AbstractTreeIterator newTree;
			if (staged) {
				ObjectId head = repository.resolve("HEAD^{tree}");
				if (head == null) {
					oldTree = new EmptyTreeIterator();
				} else {
					CanonicalTreeParser parser = new CanonicalTreeParser();
					try (var reader = repository.newObjectReader()) {
						parser.reset(reader, head);
					}
					oldTree = parser;
				}
				newTree = new DirCacheIterator(repository.readDirCache());
			} else {
				oldTree = new DirCacheIterator(repository.readDirCache());
				newTree = new FileTreeIterator(repository);
			}
			for (DiffEntry entry : formatter.scan(oldTree, newTree)) formatter.format(entry);
			formatter.flush();
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	String commitDetails(Path root, String revision) throws IOException {
		try (Repository repository = open(root); RevWalk walk = new RevWalk(repository);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				DiffFormatter formatter = formatter(repository, output)) {
			ObjectId id = repository.resolve(revision + "^{commit}");
			if (id == null) return "Commit not found: " + revision;
			RevCommit commit = walk.parseCommit(id);
			StringBuilder header = new StringBuilder()
					.append("commit ").append(commit.name()).append('\n')
					.append("Author: ").append(commit.getAuthorIdent().getName()).append(" <")
					.append(commit.getAuthorIdent().getEmailAddress()).append(">\n")
					.append("Date:   ").append(commit.getAuthorIdent().getWhenAsInstant()).append("\n\n")
					.append(commit.getFullMessage()).append("\n\n");
			if (commit.getParentCount() == 0) {
				try (ObjectReader reader = repository.newObjectReader()) {
					formatter.format(new EmptyTreeIterator(), new CanonicalTreeParser(null, reader, commit.getTree()));
				}
			} else {
				RevCommit parent = walk.parseCommit(commit.getParent(0));
				formatter.format(parent.getTree(), commit.getTree());
			}
			formatter.flush();
			return header + output.toString(StandardCharsets.UTF_8);
		}
	}

	String revisionToWorkingDiff(Path root, String revision, String path) throws IOException {
		try (Repository repository = open(root);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				DiffFormatter formatter = formatter(repository, output);
				ObjectReader reader = repository.newObjectReader()) {
			ObjectId tree = repository.resolve(revision + "^{tree}");
			if (tree == null) return "Revision not found: " + revision;
			CanonicalTreeParser oldTree = new CanonicalTreeParser();
			oldTree.reset(reader, tree);
			if (!blank(path)) formatter.setPathFilter(PathFilter.create(GitNode.normalizeGitPath(path)));
			for (DiffEntry entry : formatter.scan(oldTree, new FileTreeIterator(repository))) formatter.format(entry);
			formatter.flush();
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	String history(Path root, String path, int maxCount) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			var command = git.log().setMaxCount(maxCount);
			if (!blank(path)) command.addPath(GitNode.normalizeGitPath(path));
			StringBuilder result = new StringBuilder();
			for (RevCommit commit : command.call()) {
				result.append(commit.abbreviate(8).name()).append("  ")
						.append(commit.getAuthorIdent().getWhenAsInstant()).append("  ")
						.append(commit.getAuthorIdent().getName()).append("\n    ")
						.append(commit.getShortMessage()).append("\n\n");
			}
			return result.toString();
		} catch (GitAPIException e) {
			throw new IOException("Unable to read file history", e);
		}
	}

	String blame(Path root, String path, int maxLines) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			BlameResult blame = git.blame().setFilePath(GitNode.normalizeGitPath(path)).call();
			if (blame == null || blame.getResultContents() == null) return "No blame information available.";
			StringBuilder result = new StringBuilder();
			int count = Math.min(blame.getResultContents().size(), maxLines);
			for (int line = 0; line < count; line++) {
				RevCommit commit = blame.getSourceCommit(line);
				String id = commit == null ? "--------" : commit.abbreviate(8).name();
				String author = blame.getSourceAuthor(line) == null ? "unknown" : blame.getSourceAuthor(line).getName();
				result.append(String.format("%s %-18s %5d  %s%n", id, abbreviate(author, 18), line + 1,
						blame.getResultContents().getString(line)));
			}
			if (blame.getResultContents().size() > count) result.append("\n… output truncated …\n");
			return result.toString();
		} catch (GitAPIException e) {
			throw new IOException("Unable to blame file", e);
		}
	}

	private static DiffFormatter formatter(Repository repository, ByteArrayOutputStream output) {
		DiffFormatter formatter = new DiffFormatter(output);
		formatter.setRepository(repository);
		formatter.setDiffComparator(RawTextComparator.DEFAULT);
		formatter.setDetectRenames(true);
		return formatter;
	}

	private static List<RefInfo> refs(Repository repository, List<Ref> refs) throws IOException {
		var result = new ArrayList<RefInfo>();
		for (Ref ref : refs) {
			if (ref.isSymbolic() && ref.getName().endsWith("/HEAD")) continue;
			Ref peeled = repository.getRefDatabase().peel(ref);
			ObjectId id = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
			if (id != null) result.add(new RefInfo(Repository.shortenRefName(ref.getName()), ref.getName(), id.name()));
		}
		result.sort(Comparator.comparing(RefInfo::name, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	private static CommitInfo commit(RevCommit commit, String label) {
		String shortId = label != null ? label : commit.abbreviate(8).name();
		return new CommitInfo(commit.name(), shortId, commit.getShortMessage(),
				commit.getAuthorIdent().getName(), commit.getAuthorIdent().getWhenAsInstant(), commit.getParentCount());
	}

	private static Repository open(Path root) throws IOException {
		Repository repository = openDiscovered(root);
		if (repository == null) throw new IOException("Not a Git repository: " + root);
		return repository;
	}

	private static Repository openDiscovered(Path start) throws IOException {
		Path normalized = start.toAbsolutePath().normalize();
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		if (Files.isRegularFile(normalized.resolve("HEAD")) && Files.isDirectory(normalized.resolve("objects"))) {
			builder.setGitDir(normalized.toFile());
		} else {
			builder.findGitDir(normalized.toFile());
		}
		if (builder.getGitDir() == null) return null;
		return builder.setMustExist(true).build();
	}

	private static Path repositoryRoot(Repository repository) throws IOException {
		return (repository.isBare() ? repository.getDirectory().toPath() : repository.getWorkTree().toPath())
				.toRealPath();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String abbreviate(String value, int width) {
		if (value == null) return "";
		return value.length() <= width ? value : value.substring(0, Math.max(1, width - 1)) + "…";
	}
}
