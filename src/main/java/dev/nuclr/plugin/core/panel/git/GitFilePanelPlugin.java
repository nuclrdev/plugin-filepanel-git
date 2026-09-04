/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.GraphicsEnvironment;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;

/** Git operations projected as a mountable, navigable filesystem. */
public final class GitFilePanelPlugin implements FilePanelNuclrPlugin {

	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.git";
	private static final Logger LOG = LoggerFactory.getLogger(GitFilePanelPlugin.class);
	private static final String SETTINGS_RECENT = "recentRepositories";
	private static final int MAX_RECENT = 10;
	private static final int MAX_COMMITS = 250;
	private static final long STATUS_CACHE_NANOS = TimeUnit.SECONDS.toNanos(2);

	private static final String VIEW = "filepanel.view";
	private static final String COPY = "filepanel.copy";
	private static final String REFRESH = "refresh.panel";
	private static final String STAGE = "git.stage";
	private static final String UNSTAGE = "git.unstage";
	private static final String DISCARD = "git.discard";
	private static final String DIFF = "git.diff";
	private static final String DIFF_STAGED = "git.diff.staged";
	private static final String HISTORY = "git.history";
	private static final String BLAME = "git.blame";
	private static final String COMPARE_WORKTREE = "git.compare.worktree";
	private static final String RESTORE_VERSION = "git.restore.version";
	private static final String COMMIT = "git.commit";
	private static final String CREATE_BRANCH = "git.branch.create";
	private static final String CHECKOUT = "git.branch.checkout";
	private static final String FETCH = "git.fetch";
	private static final String PULL = "git.pull";
	private static final String PUSH = "git.push";
	private static final String STASH_CREATE = "git.stash.create";
	private static final String STASH_APPLY = "git.stash.apply";
	private static final String STASH_DROP = "git.stash.drop";
	private static final String EVENT_VIEW = "mainpanel.view";
	private static final String EVENT_REFRESH = "refresh.plugin.file.panel";

	private static final List<String> BASIC_COLUMNS = List.of("Name", "Git", "Size");
	private static final List<String> REF_COLUMNS = List.of("Name", "Revision");
	private static final List<String> COMMIT_COLUMNS = List.of("Name", "Author", "Date", "Message");

	private final String uuid = UUID.randomUUID().toString();
	private final GitRepositoryService repositories = new GitRepositoryService();
	private final NativeGitRunner nativeGit = new NativeGitRunner();
	private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
	private final Map<String, GitRepositoryService.RepositoryInfo> infoCache = new ConcurrentHashMap<>();
	private final ExecutorService actions = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("nuclr-git-action-" + uuid).factory());

	private volatile NuclrPluginContext context;
	private volatile GitResource currentFolder;
	private volatile boolean focused;
	private volatile boolean unloading;
	private volatile Boolean nativeGitAvailable;

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
		LOG.info("Git panel plugin loaded");
		Thread.ofVirtual().name("nuclr-git-probe").start(() -> nativeGitAvailable = nativeGit.available());
	}

	@Override
	public void unload() {
		unloading = true;
		actions.shutdownNow();
		try {
			actions.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		statusCache.clear();
		infoCache.clear();
		currentFolder = null;
		context = null;
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		MenuItem item = new MenuItem();
		item.setText("Git");
		item.setPath(resource(GitNode.root(), null, "Git", true, null, 0));
		item.setUuid(PLUGIN_ID + ":root");
		MenuItemsHolder holder = new MenuItemsHolder();
		holder.setTitle("Git Repositories");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return !unloading && resource instanceof GitResource gitResource && gitResource.isFolder();
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (!(resource instanceof GitResource selected) || cancelled(cancelled)) return null;
		try {
			return switch (selected.node().kind()) {
				case ROOT -> listRoot(selected);
				case OPEN_REPOSITORY -> openRepository(selected, cancelled);
				case REPOSITORY -> listRepository(selected);
				case WORKTREE, WORKTREE_DIRECTORY -> listWorkingTree(selected, cancelled);
				case STATUS -> listStatus(selected, cancelled);
				case STATUS_GROUP -> listStatusGroup(selected, cancelled);
				case BRANCHES -> listBranchesRoot(selected);
				case BRANCH_GROUP -> listBranches(selected);
				case COMMITS -> listCommits(selected);
				case TAGS -> listTags(selected);
				case STASHES -> listStashes(selected);
				case REF, COMMIT, STASH, REVISION_TREE -> listRevision(selected);
				default -> null;
			};
		} catch (IOException | RuntimeException e) {
			if (!cancelled(cancelled)) {
				LOG.warn("Unable to open Git node {}: {}", selected.node(), e.getMessage(), e);
				GitDialogs.error("Git", e.getMessage());
			}
			return null;
		}
	}

	private NuclrResourceData listRoot(GitResource folder) {
		var entries = new ArrayList<NuclrResource>();
		entries.add(resource(GitNode.openRepository(), GitNode.root(), "Open Repository…", true, null, 0));
		for (Path recent : recentRepositories()) {
			try {
				GitRepositoryService.RepositoryInfo info = repositories.info(recent);
				infoCache.put(info.root().toString(), info);
				entries.add(resource(GitNode.repository(info.root().toString()), GitNode.root(),
						repositoryName(info.root()) + "  [" + info.branch() + "]", true, null, 0));
			} catch (IOException ignored) {
				// Stale entries remain in settings but are omitted until opened again.
			}
		}
		currentFolder = folder;
		return data(BASIC_COLUMNS, entries);
	}

	private NuclrResourceData openRepository(GitResource selected, AtomicBoolean cancelled) throws IOException {
		Path chosen = GitDialogs.chooseRepository(lastRepositoryParent());
		if (chosen == null || cancelled(cancelled)) return listRoot(resource(GitNode.root(), null, "Git", true, null, 0));
		Path root = repositories.findRepository(chosen);
		if (root == null) throw new IOException("No Git repository was found at or above " + chosen);
		rememberRepository(root);
		return listRepository(resource(GitNode.repository(root.toString()), GitNode.root(), repositoryName(root), true, null, 0));
	}

	private NuclrResourceData listRepository(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		GitRepositoryService.RepositoryInfo info = repositories.info(root);
		infoCache.put(root.toString(), info);
		var entries = new ArrayList<NuclrResource>();
		if (!info.bare()) {
			entries.add(resource(new GitNode(GitNode.Kind.WORKTREE, root.toString(), null, null, null),
					folder.node(), "Working Tree", true, root, 0));
			entries.add(resource(new GitNode(GitNode.Kind.STATUS, root.toString(), null, null, null),
					folder.node(), "Status", true, null, 0));
		}
		entries.add(resource(new GitNode(GitNode.Kind.BRANCHES, root.toString(), null, null, null),
				folder.node(), "Branches", true, null, 0));
		entries.add(resource(new GitNode(GitNode.Kind.COMMITS, root.toString(), "HEAD", null, null),
				folder.node(), "Commits", true, null, 0));
		entries.add(resource(new GitNode(GitNode.Kind.TAGS, root.toString(), null, null, null),
				folder.node(), "Tags", true, null, 0));
		entries.add(resource(new GitNode(GitNode.Kind.STASHES, root.toString(), null, null, null),
				folder.node(), "Stashes", true, null, 0));
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listWorkingTree(GitResource folder, AtomicBoolean cancelled) throws IOException {
		Path root = repository(folder.node());
		String relative = folder.node().relativePath();
		Path directory = safeResolve(root, relative);
		if (!Files.isDirectory(directory)) throw new IOException("Working-tree folder no longer exists: " + directory);
		GitStatusSnapshot snapshot = refreshStatus(root);
		var entries = new ArrayList<NuclrResource>();
		try (var stream = Files.list(directory)) {
			var children = stream.filter(path -> !".git".equals(path.getFileName().toString()))
					.sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
							.thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.toList();
			for (Path child : children) {
				if (cancelled(cancelled)) break;
				boolean directoryChild = Files.isDirectory(child);
				String childRelative = relative.isEmpty() ? child.getFileName().toString()
						: relative + "/" + child.getFileName();
				GitNode.Kind kind = directoryChild ? GitNode.Kind.WORKTREE_DIRECTORY : GitNode.Kind.WORKTREE_FILE;
				GitResource entry = resource(new GitNode(kind, root.toString(), null, childRelative, null),
						folder.node(), child.getFileName().toString(), directoryChild, child, safeSize(child));
				entry.marker(snapshot.marker(childRelative, directoryChild));
				entry.setHidden(entry.getName().startsWith("."));
				entries.add(entry);
			}
		}
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listStatus(GitResource folder, AtomicBoolean cancelled) throws IOException {
		Path root = repository(folder.node());
		GitStatusSnapshot snapshot = refreshStatus(root);
		var entries = new ArrayList<NuclrResource>();
		entries.add(statusGroup(folder, root, "conflicts", "Conflicts", snapshot.conflicts().size()));
		entries.add(statusGroup(folder, root, "staged", "Staged", snapshot.staged().size()));
		entries.add(statusGroup(folder, root, "modified", "Modified", snapshot.modified().size()));
		entries.add(statusGroup(folder, root, "untracked", "Untracked", snapshot.untracked().size()));
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private GitResource statusGroup(GitResource parent, Path root, String key, String label, int count) {
		return resource(new GitNode(GitNode.Kind.STATUS_GROUP, root.toString(), null, null, key),
				parent.node(), label + " (" + count + ")", true, null, 0);
	}

	private NuclrResourceData listStatusGroup(GitResource folder, AtomicBoolean cancelled) throws IOException {
		Path root = repository(folder.node());
		GitStatusSnapshot snapshot = refreshStatus(root);
		List<GitStatusSnapshot.Entry> selected = switch (folder.node().value()) {
			case "conflicts" -> snapshot.conflicts();
			case "staged" -> snapshot.staged();
			case "modified" -> snapshot.modified();
			case "untracked" -> snapshot.untracked();
			default -> List.of();
		};
		var entries = new ArrayList<NuclrResource>();
		for (GitStatusSnapshot.Entry state : selected) {
			if (cancelled(cancelled)) break;
			Path local = safeResolve(root, state.path());
			Path existing = Files.exists(local) && !Files.isDirectory(local) ? local : null;
			GitResource entry = resource(new GitNode(GitNode.Kind.STATUS_FILE, root.toString(), null,
					state.path(), state.marker()), folder.node(), state.path(), false, existing, safeSize(local));
			entries.add(entry.marker(state.marker()));
		}
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listBranchesRoot(GitResource folder) {
		Path root = repository(folder.node());
		var entries = List.<NuclrResource>of(
				resource(new GitNode(GitNode.Kind.BRANCH_GROUP, root.toString(), null, null, "local"),
						folder.node(), "Local", true, null, 0),
				resource(new GitNode(GitNode.Kind.BRANCH_GROUP, root.toString(), null, null, "remote"),
						folder.node(), "Remote", true, null, 0));
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listBranches(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		boolean remote = "remote".equals(folder.node().value());
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.RefInfo ref : repositories.branches(root, remote)) {
			GitNode node = new GitNode(GitNode.Kind.REF, root.toString(), ref.fullName(), null, ref.name());
			entries.add(resource(node, folder.node(), ref.name(), true, null, 0)
					.column("Revision", abbreviate(ref.revision(), 10)));
		}
		currentFolder = folder;
		return withParent(folder, REF_COLUMNS, entries);
	}

	private NuclrResourceData listCommits(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.CommitInfo commit : repositories.commits(root, folder.node().revision(), MAX_COMMITS)) {
			GitNode node = new GitNode(GitNode.Kind.COMMIT, root.toString(), commit.id(), null, commit.shortId());
			entries.add(resource(node, folder.node(), commit.shortId(), true, null, 0)
					.details(commit.author(), commit.instant(), commit.subject()));
		}
		currentFolder = folder;
		return withParent(folder, COMMIT_COLUMNS, entries);
	}

	private NuclrResourceData listTags(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.RefInfo ref : repositories.tags(root)) {
			entries.add(resource(new GitNode(GitNode.Kind.REF, root.toString(), ref.fullName(), null, ref.name()),
					folder.node(), ref.name(), true, null, 0).column("Revision", abbreviate(ref.revision(), 10)));
		}
		currentFolder = folder;
		return withParent(folder, REF_COLUMNS, entries);
	}

	private NuclrResourceData listStashes(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.CommitInfo stash : repositories.stashes(root)) {
			entries.add(resource(new GitNode(GitNode.Kind.STASH, root.toString(), stash.id(), null, stash.shortId()),
					folder.node(), stash.shortId(), true, null, 0)
					.details(stash.author(), stash.instant(), stash.subject()));
		}
		currentFolder = folder;
		return withParent(folder, COMMIT_COLUMNS, entries);
	}

	private NuclrResourceData listRevision(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		String revision = folder.node().revision();
		String relative = folder.node().kind() == GitNode.Kind.REVISION_TREE ? folder.node().relativePath() : "";
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.TreeEntry tree : repositories.tree(root, revision, relative)) {
			GitNode.Kind kind = tree.directory() ? GitNode.Kind.REVISION_TREE : GitNode.Kind.REVISION_BLOB;
			String value = tree.directory() ? revisionOrigin(folder.node()) : tree.objectId();
			GitNode node = new GitNode(kind, root.toString(), revision, tree.path(), value);
			GitResource entry = resource(node, folder.node(), tree.name(), tree.directory(), null, tree.size());
			if (tree.symlink()) entry.column("Git", "link");
			if (tree.gitlink()) entry.column("Git", "submodule");
			entries.add(entry);
		}
		currentFolder = folder;
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {
		var items = new ArrayList<NuclrMenuResource>();
		if (source != null && !source.isFolder()) items.add(menu("View", "F3", VIEW));
		items.add(menu("Copy", "F5", COPY));
		items.add(menu("Commit", "F2", COMMIT));
		items.add(menu("Diff", "Shift+F3", DIFF));
		items.add(menu("Stage", "Shift+F5", STAGE));
		items.add(menu("Unstage", "Shift+F6", UNSTAGE));
		items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
		items.add(menu("Date", "Ctrl+F5", "filepanel.sort:modified:Date"));
		items.add(menu("Size", "Ctrl+F6", "filepanel.sort:size:Size"));
		items.add(menu("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
		return items;
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource,
			List<NuclrResource> selectedResources) {
		GitResource resource = focusedResource instanceof GitResource git ? git : null;
		var items = new ArrayList<NuclrContextMenuItem>();
		if (resource != null && !resource.isFolder()) {
			String marker = resource.getMetadata("Git", "");
			boolean stagedChange = marker.length() >= 1 && marker.charAt(0) != ' ' && marker.charAt(0) != '?';
			boolean workingChange = "UU".equals(marker)
					|| marker.length() >= 2 && marker.charAt(1) != ' ' && marker.charAt(1) != '?';
			boolean untracked = "??".equals(marker);
			items.add(action("View", VIEW, "view", true, false));
			items.add(action("Copy to opposite panel", COPY, "copy", true, false));
			if (hasWorkingPath(resource)) {
				items.add(NuclrContextMenuItem.separator());
				items.add(action("Stage / Add", STAGE, "add", workingChange || untracked, false));
				items.add(action("Unstage", UNSTAGE, "remove", stagedChange, false));
				items.add(action("Discard working changes", DISCARD, "undo", workingChange && !untracked, true));
				items.add(action("Working diff", DIFF, "view", workingChange, false));
				items.add(action("Staged diff", DIFF_STAGED, "view", stagedChange, false));
				items.add(action("File history", HISTORY, "history", true, false));
				items.add(action("Blame", BLAME, "info", true, false));
			}
			if (resource.node().kind() == GitNode.Kind.REVISION_BLOB
					&& !"submodule".equals(resource.getMetadata("Git", ""))) {
				items.add(NuclrContextMenuItem.separator());
				items.add(action("Compare with working tree", COMPARE_WORKTREE, "view", true, false));
				items.add(action("Restore this version", RESTORE_VERSION, "undo", true, true));
			}
		}
		if (resource != null && resource.node().kind() == GitNode.Kind.REF) {
			items.add(action("Checkout branch", CHECKOUT, "refresh", true, false));
		}
		if (resource != null && resource.node().kind() == GitNode.Kind.STASH) {
			items.add(action("Apply stash", STASH_APPLY, "undo", true, false));
			items.add(action("Drop stash", STASH_DROP, "delete", true, true));
		}
		if (repositoryOf(resource) != null) {
			if (!items.isEmpty()) items.add(NuclrContextMenuItem.separator());
			items.add(action("Commit staged changes", COMMIT, "save", nativeAvailable(), false));
			items.add(action("Create and checkout branch", CREATE_BRANCH, "add", nativeAvailable(), false));
			items.add(action("Fetch all", FETCH, "refresh", nativeAvailable(), false));
			items.add(action("Pull (fast-forward only)", PULL, "download", nativeAvailable(), false));
			items.add(action("Push", PUSH, "upload", nativeAvailable(), false));
			items.add(action("Stash changes", STASH_CREATE, "save", nativeAvailable(), false));
		}
		return items;
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		GitResource focused = focusedResource instanceof GitResource git ? git : null;
		switch (actionType) {
			case VIEW -> view(focused);
			case COPY -> copyTo(other, chosen(selectedResources, focused), callback);
			case REFRESH -> refresh();
			case STAGE -> pathOperation("Stage", "add", false, chosenPaths(selectedResources, focused), callback);
			case UNSTAGE -> pathOperation("Unstage", "restore", false, chosenPaths(selectedResources, focused), callback,
					"--staged");
			case DISCARD -> discard(selectedResources, focused, callback);
			case DIFF -> showDiff(focused, false);
			case DIFF_STAGED -> showDiff(focused, true);
			case HISTORY -> showHistory(focused);
			case BLAME -> showBlame(focused);
			case COMPARE_WORKTREE -> compareWithWorkingTree(focused);
			case RESTORE_VERSION -> restoreVersion(focused, callback);
			case COMMIT -> commit(callback);
			case CREATE_BRANCH -> createBranch(callback);
			case CHECKOUT -> checkout(focused, callback);
			case FETCH -> repositoryOperation("Fetch", callback, "fetch", "--all", "--prune");
			case PULL -> repositoryOperation("Pull", callback, "pull", "--ff-only");
			case PUSH -> repositoryOperation("Push", callback, "push");
			case STASH_CREATE -> repositoryOperation("Stash", callback, "stash", "push", "-u", "-m", "Nuclr Commander");
			case STASH_APPLY -> stash(focused, false, callback);
			case STASH_DROP -> stash(focused, true, callback);
			default -> LOG.debug("Git panel ignoring action [{}]", actionType);
		}
	}

	private void view(GitResource resource) {
		if (resource == null || resource.isFolder() || context == null) return;
		context.getEventBus().emit(EVENT_VIEW, Map.of("resource", resource), null);
	}

	private void showDiff(GitResource resource, boolean staged) {
		Path root = repositoryOf(resource);
		String path = relativePath(resource);
		if (root == null) return;
		runRead(staged ? "Staged diff" : "Working diff", () -> repositories.workingDiff(root, path, staged));
	}

	private void showHistory(GitResource resource) {
		Path root = repositoryOf(resource);
		String path = relativePath(resource);
		if (root != null && path != null) runRead("History — " + path, () -> repositories.history(root, path, 250));
	}

	private void showBlame(GitResource resource) {
		Path root = repositoryOf(resource);
		String path = relativePath(resource);
		if (root != null && path != null) runRead("Blame — " + path, () -> repositories.blame(root, path, 10_000));
	}

	private void compareWithWorkingTree(GitResource resource) {
		if (resource == null || resource.node().kind() != GitNode.Kind.REVISION_BLOB) return;
		Path root = repositoryOf(resource);
		runRead("Compare with working tree — " + resource.node().relativePath(),
				() -> repositories.revisionToWorkingDiff(root, resource.node().revision(), resource.node().relativePath()));
	}

	private void restoreVersion(GitResource resource, NuclrPluginCallback callback) {
		if (resource == null || resource.node().kind() != GitNode.Kind.REVISION_BLOB) return;
		Path root = repositoryOf(resource);
		Path target;
		try {
			target = safeResolve(root, resource.node().relativePath());
		} catch (IOException e) {
			GitDialogs.error("Restore Version", e.getMessage());
			return;
		}
		if (!GitDialogs.confirm("Restore Version", "Replace the working-tree file "
				+ resource.node().relativePath() + " with this version?")) return;
		actions.submit(() -> {
			try {
				if (callback != null) callback.onStart("Restoring " + resource.getName());
				Files.createDirectories(target.getParent());
				try (InputStream input = repositories.openBlob(root, resource.node().value())) {
					Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
				}
				statusCache.remove(root.toString());
				if (callback != null) callback.onComplete();
				requestRefresh(uuid);
			} catch (IOException e) {
				if (callback != null) callback.onError("Restore version", e);
				GitDialogs.error("Restore Version", e.getMessage());
			}
		});
	}

	private void runRead(String title, IoSupplier<String> supplier) {
		actions.submit(() -> {
			try {
				GitDialogs.showText(title, supplier.get());
			} catch (IOException e) {
				LOG.warn("{} failed: {}", title, e.getMessage(), e);
				GitDialogs.error(title, e.getMessage());
			}
		});
	}

	private void commit(NuclrPluginCallback callback) {
		Path root = repositoryOf(currentFolder);
		if (root == null) return;
		String message = GitDialogs.commitMessage();
		if (message != null) runNative("Commit", root, callback, "commit", "-m", message);
	}

	private void createBranch(NuclrPluginCallback callback) {
		Path root = repositoryOf(currentFolder);
		if (root == null) return;
		String branch = GitDialogs.input("Create Branch", "New branch name:", "");
		if (branch != null) runNative("Create branch", root, callback, "switch", "-c", branch);
	}

	private void checkout(GitResource resource, NuclrPluginCallback callback) {
		if (resource == null || resource.node().kind() != GitNode.Kind.REF) return;
		Path root = repositoryOf(resource);
		String full = resource.node().revision();
		String name = resource.node().value();
		if (full.startsWith("refs/remotes/")) {
			runNative("Checkout", root, callback, "switch", "--track", name);
		} else {
			runNative("Checkout", root, callback, "switch", name);
		}
	}

	private void stash(GitResource resource, boolean drop, NuclrPluginCallback callback) {
		if (resource == null || resource.node().kind() != GitNode.Kind.STASH) return;
		String ref = resource.node().value();
		if (drop && !GitDialogs.confirm("Drop Stash", "Permanently drop " + ref + "?")) return;
		runNative(drop ? "Drop stash" : "Apply stash", repositoryOf(resource), callback,
				"stash", drop ? "drop" : "apply", ref);
	}

	private void repositoryOperation(String title, NuclrPluginCallback callback, String... command) {
		Path root = repositoryOf(currentFolder);
		if (root != null) runNative(title, root, callback, command);
	}

	private void pathOperation(String title, String command, boolean destructive, List<String> paths,
			NuclrPluginCallback callback, String... prefix) {
		Path root = repositoryOf(currentFolder);
		if (root == null || paths.isEmpty()) return;
		var args = new ArrayList<String>();
		args.add(command);
		args.addAll(List.of(prefix));
		args.add("--");
		args.addAll(paths);
		runNative(title, root, callback, args.toArray(String[]::new));
	}

	private void discard(List<NuclrResource> selected, GitResource focused, NuclrPluginCallback callback) {
		List<String> paths = chosenPaths(selected, focused);
		if (paths.isEmpty() || !GitDialogs.confirm("Discard Changes",
				"Discard working-tree changes in " + (paths.size() == 1 ? paths.get(0) : paths.size() + " files") + "?")) return;
		pathOperation("Discard changes", "restore", true, paths, callback, "--worktree");
	}

	private void runNative(String title, Path root, NuclrPluginCallback callback, String... command) {
		if (root == null) return;
		actions.submit(() -> {
			try {
				if (nativeGitAvailable == null) nativeGitAvailable = nativeGit.available();
				if (!Boolean.TRUE.equals(nativeGitAvailable)) {
					throw new IOException("The native git executable is required for this operation.");
				}
				if (callback != null) callback.onStart(title);
				nativeGit.requireSuccess(title, root, () -> unloading || callback != null && callback.isCancelled(), command);
				statusCache.remove(root.toString());
				try {
					infoCache.put(root.toString(), repositories.info(root));
				} catch (IOException e) {
					infoCache.remove(root.toString());
				}
				if (callback != null) callback.onComplete();
				requestRefresh(uuid);
			} catch (IOException e) {
				if (callback != null) callback.onError(title, e);
				if (!unloading && !e.getMessage().toLowerCase(Locale.ROOT).contains("cancel")) GitDialogs.error(title, e.getMessage());
			}
		});
	}

	private void copyTo(BaseNuclrPlugin other, List<GitResource> sources, NuclrPluginCallback callback) {
		if (other == null || other == this || other instanceof QuickViewNuclrPlugin || sources.isEmpty()) return;
		NuclrResource destinationResource = other.getCurrentResource();
		Path destination = destinationResource != null ? destinationResource.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			GitDialogs.error("Copy", "The opposite panel is not a local filesystem directory.");
			return;
		}
		Path targetRoot = destination.toAbsolutePath().normalize();
		actions.submit(() -> {
			try {
				if (callback != null) callback.onStart("Copying from Git");
				for (GitResource source : sources) {
					if (unloading || callback != null && callback.isCancelled()) throw new IOException("Copy cancelled.");
					copyResource(source, targetRoot, callback);
				}
				if (callback != null) callback.onComplete();
				requestRefresh(other.uuid());
			} catch (IOException e) {
				if (callback != null) callback.onError("Copy from Git", e);
				if (!e.getMessage().toLowerCase(Locale.ROOT).contains("cancel")) GitDialogs.error("Copy", e.getMessage());
			}
		});
	}

	private void copyResource(GitResource source, Path destination, NuclrPluginCallback callback) throws IOException {
		String name = source.getName();
		if (name == null || name.isBlank() || "..".equals(name)) return;
		Path target = destination.resolve(name).normalize();
		if (!target.startsWith(destination)) throw new IOException("Unsafe Git path: " + name);
		if (source.node().kind() == GitNode.Kind.WORKTREE_FILE) {
			copyLocal(Path.of(source.node().repository()).resolve(source.node().relativePath()), target);
			return;
		}
		if (source.node().kind() == GitNode.Kind.STATUS_FILE) {
			Path working = safeResolve(Path.of(source.node().repository()), source.node().relativePath());
			if (!Files.isRegularFile(working)) throw new IOException("The working-tree file does not exist: " + source.node().relativePath());
			copyLocal(working, target);
			return;
		}
		if (source.node().kind() == GitNode.Kind.WORKTREE_DIRECTORY) {
			copyLocal(Path.of(source.node().repository()).resolve(source.node().relativePath()), target);
			return;
		}
		if (source.node().kind() == GitNode.Kind.REVISION_BLOB) {
			if ("submodule".equals(source.getMetadata("Git", ""))) {
				throw new IOException("Submodule entries cannot be copied without the submodule repository.");
			}
			writeHistorical(source, target);
			return;
		}
		if (source.node().kind() == GitNode.Kind.REVISION_TREE) {
			copyHistoricalTree(source, target, callback);
			return;
		}
		throw new IOException("This Git node cannot be copied: " + source.getName());
	}

	private void writeHistorical(GitResource source, Path target) throws IOException {
		if (Files.exists(target) && !GitDialogs.confirm("Replace File", "Replace " + target.getFileName() + "?")) return;
		Files.createDirectories(target.getParent());
		try (InputStream input = repositories.openBlob(Path.of(source.node().repository()), source.node().value());
				OutputStream output = Files.newOutputStream(target)) {
			input.transferTo(output);
		}
	}

	private void copyHistoricalTree(GitResource source, Path target, NuclrPluginCallback callback) throws IOException {
		Files.createDirectories(target);
		for (GitRepositoryService.TreeEntry entry : repositories.tree(Path.of(source.node().repository()),
				source.node().revision(), source.node().relativePath())) {
			if (unloading || callback != null && callback.isCancelled()) throw new IOException("Copy cancelled.");
			GitNode.Kind kind = entry.directory() ? GitNode.Kind.REVISION_TREE : GitNode.Kind.REVISION_BLOB;
			String value = entry.directory() ? source.node().value() : entry.objectId();
			GitResource child = resource(new GitNode(kind, source.node().repository(), source.node().revision(),
					entry.path(), value), source.node(), entry.name(), entry.directory(), null, entry.size());
			if (entry.gitlink()) child.column("Git", "submodule");
			if (entry.directory()) copyHistoricalTree(child, target.resolve(entry.name()), callback);
			else writeHistorical(child, target.resolve(entry.name()));
		}
	}

	private static void copyLocal(Path source, Path target) throws IOException {
		if (Files.isDirectory(source)) {
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Files.createDirectories(target.resolve(source.relativize(dir).toString()));
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Path destination = target.resolve(source.relativize(file).toString());
					Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			Files.createDirectories(target.getParent());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public void walkDescendants(NuclrResource resource, Consumer<NuclrResource> visitor,
			AtomicBoolean cancelled, boolean recursive) throws IOException {
		if (!(resource instanceof GitResource git) || !git.isFolder()) throw new IOException("Not a Git directory.");
		NuclrResourceData children = openResource(git, cancelled);
		if (children == null) return;
		for (NuclrResource child : children.getEntries()) {
			if (cancelled(cancelled)) return;
			if ("..".equals(child.getName())) continue;
			visitor.accept(child);
			if (recursive && child.isFolder()) walkDescendants(child, visitor, cancelled, true);
		}
	}

	@Override public NuclrResource getCurrentResource() { return currentFolder; }
	@Override public String uuid() { return uuid; }
	@Override public boolean onFocusGained() { focused = true; return true; }
	@Override public void onFocusLost() { focused = false; }
	@Override public boolean isFocused() { return focused; }
	@Override public void closeResource() { }

	@Override
	public String getCurrentLocationDisplayText() {
		GitResource folder = currentFolder;
		if (folder == null || folder.node().kind() == GitNode.Kind.ROOT) return "Git";
		GitNode node = folder.node();
		if (node.repository().isBlank()) return "Git";
		Path root = Path.of(node.repository());
		GitRepositoryService.RepositoryInfo info = infoCache.get(root.toString());
		String branch = info != null ? info.branch() : "Git";
		if (info != null && info.state() != null && !"Safe".equalsIgnoreCase(info.state())) {
			branch += " (" + info.state().toLowerCase(Locale.ROOT) + ")";
		}
		String area = node.kind().name().replace('_', ' ').toLowerCase(Locale.ROOT);
		String suffix = node.relativePath().isBlank() ? "" : "/" + node.relativePath();
		if (node.isRevisionNode()) area = "@ " + abbreviate(node.revision(), 10) + suffix;
		return repositoryName(root) + " — " + branch + " — " + area;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) return getCurrentLocationDisplayText();
		if (selectedResources.size() == 1) {
			NuclrResource selected = selectedResources.get(0);
			String marker = selected.getMetadata("Git", "");
			return selected.getName() + (marker.isBlank() ? "" : "  |  " + marker)
					+ "  |  " + (selected.isFolder() ? "Folder" : GitResource.displaySize(selected.getLength()));
		}
		long bytes = selectedResources.stream().filter(resource -> !resource.isFolder()).mapToLong(NuclrResource::getLength).sum();
		return selectedResources.size() + " items, " + GitResource.displaySize(bytes);
	}

	@Override public String getWindowTitle() { return getCurrentLocationDisplayText(); }

	private GitStatusSnapshot refreshStatus(Path root) throws IOException {
		CachedStatus cached = statusCache.get(root.toString());
		if (cached != null && System.nanoTime() - cached.createdAtNanos() < STATUS_CACHE_NANOS) {
			return cached.snapshot();
		}
		GitStatusSnapshot snapshot = repositories.status(root);
		statusCache.put(root.toString(), new CachedStatus(snapshot, System.nanoTime()));
		return snapshot;
	}

	private void refresh() {
		Path root = repositoryOf(currentFolder);
		if (root != null) statusCache.remove(root.toString());
		requestRefresh(uuid);
	}

	private void requestRefresh(String pluginUuid) {
		NuclrPluginContext pluginContext = context;
		if (pluginContext == null || pluginUuid == null) return;
		Runnable emit = () -> pluginContext.getEventBus().emit(EVENT_REFRESH, Map.of("plugin.uuid", pluginUuid), null);
		if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) emit.run();
		else SwingUtilities.invokeLater(emit);
	}

	private boolean nativeAvailable() {
		return Boolean.TRUE.equals(nativeGitAvailable);
	}

	private NuclrResourceData withParent(GitResource folder, List<String> columns, List<? extends NuclrResource> entries) {
		var all = new ArrayList<NuclrResource>();
		GitNode parent = folder.parentNode() != null ? folder.parentNode() : parentOf(folder.node());
		if (parent != null) all.add(resource(parent, parentOf(parent), "..", true, null, 0));
		all.addAll(entries);
		return data(columns, all);
	}

	private static NuclrResourceData data(List<String> columns, List<? extends NuclrResource> entries) {
		NuclrResourceData data = new NuclrResourceData();
		data.setColumnNames(columns);
		data.setEntries(new ArrayList<>(entries));
		return data;
	}

	private static GitResource resource(GitNode node, GitNode parent, String name, boolean folder, Path local, long size) {
		return new GitResource(node, parent, name, folder, local, size);
	}

	private static GitNode parentOf(GitNode node) {
		if (node == null) return null;
		return switch (node.kind()) {
			case ROOT -> null;
			case OPEN_REPOSITORY, REPOSITORY -> GitNode.root();
			case WORKTREE, STATUS, BRANCHES, COMMITS, TAGS, STASHES -> GitNode.repository(node.repository());
			case WORKTREE_DIRECTORY -> {
				String parent = parentPath(node.relativePath());
				yield parent.isEmpty()
						? new GitNode(GitNode.Kind.WORKTREE, node.repository(), null, null, null)
						: new GitNode(GitNode.Kind.WORKTREE_DIRECTORY, node.repository(), null, parent, null);
			}
			case STATUS_GROUP -> new GitNode(GitNode.Kind.STATUS, node.repository(), null, null, null);
			case BRANCH_GROUP -> new GitNode(GitNode.Kind.BRANCHES, node.repository(), null, null, null);
			case REF -> node.revision().startsWith("refs/tags/")
					? new GitNode(GitNode.Kind.TAGS, node.repository(), null, null, null)
					: new GitNode(GitNode.Kind.BRANCH_GROUP, node.repository(), null, null,
							node.revision().startsWith("refs/remotes/") ? "remote" : "local");
			case COMMIT -> new GitNode(GitNode.Kind.COMMITS, node.repository(), "HEAD", null, null);
			case STASH -> new GitNode(GitNode.Kind.STASHES, node.repository(), null, null, null);
			case REVISION_TREE -> {
				String parent = parentPath(node.relativePath());
				yield parent.isEmpty() ? revisionOriginParent(node) : new GitNode(GitNode.Kind.REVISION_TREE,
						node.repository(), node.revision(), parent, node.value());
			}
			default -> null;
		};
	}

	private static NuclrMenuResource menu(String name, String key, String action) {
		return new NuclrMenuResource(name, key, action);
	}

	private static NuclrContextMenuItem action(String label, String action, String icon,
			boolean enabled, boolean destructive) {
		return NuclrContextMenuItem.builder().label(label).actionType(action).iconKey(icon)
				.enabled(enabled).destructive(destructive).build();
	}

	private List<Path> recentRepositories() {
		NuclrPluginContext pluginContext = context;
		if (pluginContext == null) return List.of();
		Object stored = pluginContext.getSettings().get(PLUGIN_ID, SETTINGS_RECENT);
		if (!(stored instanceof Iterable<?> iterable)) return List.of();
		var result = new ArrayList<Path>();
		for (Object item : iterable) {
			if (item != null) {
				try { result.add(Path.of(item.toString())); } catch (RuntimeException ignored) { }
			}
		}
		return result;
	}

	private void rememberRepository(Path root) {
		var paths = new LinkedHashSet<String>();
		paths.add(root.toString());
		for (Path recent : recentRepositories()) paths.add(recent.toString());
		context.getSettings().set(PLUGIN_ID, SETTINGS_RECENT, paths.stream().limit(MAX_RECENT).toList());
	}

	private Path lastRepositoryParent() {
		List<Path> recent = recentRepositories();
		return recent.isEmpty() ? Path.of(System.getProperty("user.home")) : recent.get(0);
	}

	private static Path repository(GitNode node) {
		return Path.of(node.repository()).toAbsolutePath().normalize();
	}

	private static Path repositoryOf(GitResource resource) {
		return resource == null || resource.node().repository().isBlank() ? null : repository(resource.node());
	}

	private static boolean hasWorkingPath(GitResource resource) {
		return resource != null && (resource.node().kind() == GitNode.Kind.WORKTREE_FILE
				|| resource.node().kind() == GitNode.Kind.STATUS_FILE);
	}

	private static String relativePath(GitResource resource) {
		return resource == null || resource.node().relativePath().isBlank() ? null : resource.node().relativePath();
	}

	private static List<GitResource> chosen(List<NuclrResource> selected, GitResource focused) {
		List<NuclrResource> input = selected != null && !selected.isEmpty() ? selected
				: focused != null ? List.of(focused) : List.of();
		return input.stream().filter(GitResource.class::isInstance).map(GitResource.class::cast)
				.filter(resource -> !"..".equals(resource.getName())).toList();
	}

	private static List<String> chosenPaths(List<NuclrResource> selected, GitResource focused) {
		return chosen(selected, focused).stream().map(GitFilePanelPlugin::relativePath)
				.filter(path -> path != null && !path.isBlank()).distinct().toList();
	}

	private static Path safeResolve(Path root, String relative) throws IOException {
		Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
		if (!resolved.startsWith(root.normalize())) throw new IOException("Unsafe repository path: " + relative);
		return resolved;
	}

	private static long safeSize(Path path) {
		try { return path != null && Files.isRegularFile(path) ? Files.size(path) : 0L; }
		catch (IOException e) { return 0L; }
	}

	private static boolean cancelled(AtomicBoolean flag) {
		return Thread.currentThread().isInterrupted() || flag != null && flag.get();
	}

	private static String repositoryName(Path root) {
		Path name = root.getFileName();
		return name == null ? root.toString() : name.toString();
	}

	private static String parentPath(String path) {
		String normalized = GitNode.normalizeGitPath(path);
		int slash = normalized.lastIndexOf('/');
		return slash < 0 ? "" : normalized.substring(0, slash);
	}

	private static String abbreviate(String value, int length) {
		if (value == null) return "";
		return value.length() <= length ? value : value.substring(0, length);
	}

	private static String revisionOrigin(GitNode node) {
		if (node.kind() == GitNode.Kind.REVISION_TREE && !node.value().isBlank()) return node.value();
		return node.kind().name() + "|" + node.value();
	}

	private static GitNode revisionOriginParent(GitNode node) {
		String[] origin = node.value().split("\\|", 2);
		if (origin.length == 2) {
			try {
				GitNode.Kind kind = GitNode.Kind.valueOf(origin[0]);
				if (kind == GitNode.Kind.REF || kind == GitNode.Kind.COMMIT || kind == GitNode.Kind.STASH) {
					return new GitNode(kind, node.repository(), node.revision(), null, origin[1]);
				}
			} catch (IllegalArgumentException ignored) { }
		}
		return new GitNode(GitNode.Kind.COMMIT, node.repository(), node.revision(), null,
				abbreviate(node.revision(), 8));
	}

	@FunctionalInterface
	private interface IoSupplier<T> { T get() throws IOException; }

	private record CachedStatus(GitStatusSnapshot snapshot, long createdAtNanos) { }
}
