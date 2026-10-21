/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import static dev.nuclr.plugin.core.panel.git.Messages.tr;

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
import java.util.function.BooleanSupplier;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
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
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;

/** JGit-backed read model for repositories and historical trees. */
final class GitRepositoryService {

	/** Config key marking a repository created by {@link RemoteMirrors} for on-demand browsing. */
	static final String ON_DEMAND_SECTION = "nuclr";
	static final String ON_DEMAND_KEY = "ondemand";
	private static final int MAX_MISSING_RETRIES = 4;

	private final LazyRemote lazy = new LazyRemote(new NativeGitRunner());

	/**
	 * Closes every pack file JGit keeps open in its shared window cache. JGit
	 * releases cached packs lazily, after their repository is closed, and Windows
	 * refuses to delete an open file — so call this before deleting a repository.
	 * The plugin's classloader has its own JGit, so no other plugin is affected;
	 * open repositories just reopen their packs on the next read.
	 */
	static void releasePackFiles() {
		new WindowCacheConfig().install();
	}

	/** {@code onDemand}: a lazy remote repository holding only the objects looked at so far. */
	record RepositoryInfo(Path root, String branch, String state, boolean bare, String origin, boolean onDemand) {}
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
					repository.getRepositoryState().getDescription(), repository.isBare(),
					repository.getConfig().getString("remote", "origin", "url"), isOnDemand(repository));
		}
	}

	GitStatusSnapshot status(Path root) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			if (repository.isBare()) return GitStatusSnapshot.empty();
			return GitStatusSnapshot.from(git.status().call());
		} catch (GitAPIException e) {
			throw new IOException(tr("error.status"), e);
		}
	}

	List<RefInfo> branches(Path root, boolean remote) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			var command = git.branchList();
			if (remote) command.setListMode(ListMode.REMOTE);
			return refs(repository, command.call());
		} catch (GitAPIException e) {
			throw new IOException(tr("error.list.branches"), e);
		}
	}

	List<RefInfo> tags(Path root) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			return refs(repository, git.tagList().call());
		} catch (GitAPIException e) {
			throw new IOException(tr("error.list.tags"), e);
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
			throw new IOException(tr("error.list.stashes"), e);
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
			throw new IOException(tr("error.list.commits"), e);
		}
	}

	/** True for a lazy remote repository created by {@link RemoteMirrors}. */
	boolean isOnDemand(Path root) throws IOException {
		try (Repository repository = open(root)) {
			return isOnDemand(repository);
		}
	}

	/**
	 * Commits of the history pages loaded so far, newest first. The walk stops at
	 * shallow boundaries, so it never reaches for a commit that is not here.
	 */
	List<CommitInfo> loadedCommits(Path root, int maxCount) throws IOException {
		List<String> pages = lazy.pages(root);
		if (pages.isEmpty()) return List.of();
		try (Repository repository = open(root); RevWalk walk = new RevWalk(repository)) {
			walk.sort(RevSort.COMMIT_TIME_DESC);
			for (String page : pages) walk.markStart(walk.parseCommit(ObjectId.fromString(page)));
			var result = new ArrayList<CommitInfo>();
			for (RevCommit commit : walk) {
				if (result.size() >= maxCount) break;
				result.add(commit(commit, null));
			}
			return result;
		}
	}

	/** Where the next history page starts, or null when the history is complete. */
	String nextPageStart(Path root) throws IOException {
		List<String> pages = lazy.pages(root);
		try (Repository repository = open(root); RevWalk walk = new RevWalk(repository)) {
			ObjectId start = pages.isEmpty() ? repository.resolve("HEAD") : ObjectId.fromString(pages.get(pages.size() - 1));
			if (start == null) return null;
			if (pages.isEmpty()) return start.name();
			// Follow first parents to the page's shallow boundary; JGit reports a
			// shallow commit as parentless, so its real parent comes from the raw object.
			RevCommit commit = walk.parseCommit(start);
			while (commit.getParentCount() > 0) commit = walk.parseCommit(commit.getParent(0));
			List<ObjectId> parents = rawParents(repository, commit);
			return parents.isEmpty() ? null : parents.get(0).name();
		}
	}

	/** Fetches the next history page; returns false when there is none. */
	boolean loadNextPage(Path root, int depth, BooleanSupplier cancelled) throws IOException {
		String start = nextPageStart(root);
		if (start == null) return false;
		lazy.fetchCommits(root, start, depth, cancelled);
		lazy.addPage(root, start);
		return true;
	}

	/** Fetches the history page starting at {@code start} unless it is already loaded. */
	void loadPage(Path root, String start, int depth, BooleanSupplier cancelled) throws IOException {
		if (lazy.pages(root).contains(start)) return;
		lazy.fetchCommits(root, start, depth, cancelled);
		lazy.addPage(root, start);
	}

	void refreshOnDemand(Path root, BooleanSupplier cancelled) throws IOException {
		lazy.refreshRefs(root, cancelled);
	}

	List<TreeEntry> tree(Path root, String revision, String relativePath) throws IOException {
		for (int attempt = 0;; attempt++) {
			try {
				return readTree(root, revision, relativePath);
			} catch (MissingObjectException missing) {
				// Promisor repositories hold trees only once someone looked at them.
				if (attempt >= MAX_MISSING_RETRIES || !isPromisor(root)) throw missing;
				lazy.ensure(root, missing.getObjectId());
			}
		}
	}

	private List<TreeEntry> readTree(Path root, String revision, String relativePath) throws IOException {
		// One reader for the walk and every size: opening each object through the repository
		// starts a reader of its own, which a folder of thousands of files pays for thousands of times.
		try (Repository repository = open(root); ObjectReader reader = repository.newObjectReader()) {
			ObjectId tree = repository.resolve(revision + "^{tree}");
			if (tree == null) return List.of();
			String path = GitNode.normalizeGitPath(relativePath);
			if (!path.isEmpty()) {
				try (TreeWalk found = TreeWalk.forPath(repository, reader, path, tree)) {
					if (found == null || !FileMode.TREE.equals(found.getFileMode(0))) return List.of();
					tree = found.getObjectId(0);
				}
			}
			var entries = new ArrayList<TreeEntry>();
			try (TreeWalk walk = new TreeWalk(repository, reader)) {
				walk.addTree(tree);
				walk.setRecursive(false);
				while (walk.next()) {
					FileMode mode = walk.getFileMode(0);
					boolean directory = FileMode.TREE.equals(mode);
					boolean symlink = FileMode.SYMLINK.equals(mode);
					boolean gitlink = FileMode.GITLINK.equals(mode);
					boolean executable = FileMode.EXECUTABLE_FILE.equals(mode);
					ObjectId objectId = walk.getObjectId(0);
					// -1: the file has not been downloaded, and is not fetched just to be measured.
					long size = directory || gitlink ? 0L
							: reader.has(objectId) ? reader.getObjectSize(objectId, Constants.OBJ_BLOB) : -1L;
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
		try {
			ObjectId id = ObjectId.fromString(objectId);
			if (isPromisor(repository) && !repository.getObjectDatabase().has(id)) lazy.ensure(root, id);
		} catch (IOException | RuntimeException e) {
			repository.close();
			throw e;
		}
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
			if (tree == null) throw new IOException(tr("error.revision.not.found", revision));
			try (TreeWalk found = TreeWalk.forPath(repository, normalized, tree)) {
				if (found == null || FileMode.TREE.equals(found.getFileMode(0))) {
					throw new IOException(tr("error.file.not.found", revision, normalized));
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
			if (id == null) return tr("error.commit.not.found", revision);
			if (isOnDemand(repository)) return onDemandCommitDetails(root, repository, id);
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

	/**
	 * Native git diffs on-demand commits: it downloads the changed files in one
	 * batch, where JGit would fail on the first missing one. The parent is
	 * fetched first at depth 1 — left to git, a missing commit would arrive with
	 * its whole history.
	 */
	private String onDemandCommitDetails(Path root, Repository repository, ObjectId id) throws IOException {
		RevCommit commit;
		try (RevWalk walk = new RevWalk(repository)) {
			commit = walk.parseCommit(id);
		}
		StringBuilder header = new StringBuilder()
				.append("commit ").append(commit.name()).append('\n')
				.append("Author: ").append(commit.getAuthorIdent().getName()).append(" <")
				.append(commit.getAuthorIdent().getEmailAddress()).append(">\n")
				.append("Date:   ").append(commit.getAuthorIdent().getWhenAsInstant()).append("\n\n")
				.append(commit.getFullMessage()).append("\n\n");
		List<ObjectId> parents = rawParents(repository, commit);
		NativeGitRunner nativeGit = new NativeGitRunner();
		NativeGitRunner.Result diff;
		if (parents.isEmpty()) {
			diff = nativeGit.run(root, null, "-c", "core.quotepath=false", "show", "--format=", "-M", commit.name());
		} else {
			ObjectId parent = parents.get(0);
			if (!repository.getObjectDatabase().has(parent)) lazy.fetchCommits(root, parent.name(), 1, null);
			diff = nativeGit.run(root, null, "-c", "core.quotepath=false", "diff", "-M", parent.name(), commit.name());
		}
		if (!diff.succeeded()) throw new NativeGitRunner.GitCommandException("Diff", diff);
		return header + diff.output();
	}

	String revisionToWorkingDiff(Path root, String revision, String path) throws IOException {
		try (Repository repository = open(root);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				DiffFormatter formatter = formatter(repository, output);
				ObjectReader reader = repository.newObjectReader()) {
			ObjectId tree = repository.resolve(revision + "^{tree}");
			if (tree == null) return tr("error.revision.not.found", revision);
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
			requireFullHistory(repository);
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
			throw new IOException(tr("error.history"), e);
		}
	}

	String blame(Path root, String path, int maxLines) throws IOException {
		try (Repository repository = open(root); Git git = new Git(repository)) {
			requireFullHistory(repository);
			BlameResult blame = git.blame().setFilePath(GitNode.normalizeGitPath(path)).call();
			if (blame == null || blame.getResultContents() == null) return tr("blame.none");
			StringBuilder result = new StringBuilder();
			int count = Math.min(blame.getResultContents().size(), maxLines);
			for (int line = 0; line < count; line++) {
				RevCommit commit = blame.getSourceCommit(line);
				String id = commit == null ? "--------" : commit.abbreviate(8).name();
				String author = blame.getSourceAuthor(line) == null ? "unknown" : blame.getSourceAuthor(line).getName();
				result.append(String.format("%s %-18s %5d  %s%n", id, abbreviate(author, 18), line + 1,
						blame.getResultContents().getString(line)));
			}
			if (blame.getResultContents().size() > count) result.append("\n").append(tr("blame.truncated")).append("\n");
			return result.toString();
		} catch (GitAPIException e) {
			throw new IOException(tr("error.blame"), e);
		}
	}

	private static void requireFullHistory(Repository repository) throws IOException {
		if (isOnDemand(repository)) {
			throw new IOException(tr("error.full.history.needed"));
		}
	}

	private static boolean isOnDemand(Repository repository) {
		return repository.getConfig().getBoolean(ON_DEMAND_SECTION, ON_DEMAND_KEY, false);
	}

	/** Any partial clone, ours or the user's: missing objects can be fetched from the promisor remote. */
	private static boolean isPromisor(Repository repository) {
		var config = repository.getConfig();
		return config.getString("extensions", null, "partialclone") != null
				|| config.getBoolean("remote", "origin", "promisor", false);
	}

	private static boolean isPromisor(Path root) throws IOException {
		try (Repository repository = open(root)) {
			return isPromisor(repository);
		}
	}

	/** Parents as written in the commit object; JGit hides them for shallow commits. */
	private static List<ObjectId> rawParents(Repository repository, AnyObjectId commit) throws IOException {
		byte[] raw = repository.open(commit, Constants.OBJ_COMMIT).getCachedBytes();
		var parents = new ArrayList<ObjectId>();
		for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
			if (line.isEmpty()) break;
			if (line.startsWith("parent ") && ObjectId.isId(line.substring(7).strip())) {
				parents.add(ObjectId.fromString(line.substring(7).strip()));
			}
		}
		return parents;
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
		if (repository == null) throw new IOException(tr("error.not.repository", root));
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
