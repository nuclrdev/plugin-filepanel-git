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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
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
import java.util.function.Predicate;

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
	/** How many commits a local repository lists at first, and how many more each "Load more…" adds. */
	private static final int MAX_COMMITS = 250;
	/** The most commits a Commits list shows, local or on-demand. */
	private static final int MAX_LISTED_COMMITS = 10_000;
	private static final int PAGE_TARGET = 100;
	private static final int MAX_PAGE_DEPTH = 64;
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
	private static final String REMOVE_RECENT = "git.recent.remove";
	/** What Commander sends for the Delete key; in the repository list it removes the entry. */
	private static final String DELETE_KEY = "delete";
	private static final String FULL_HISTORY = "git.history.full";
	private static final String OPEN_OTHER = "git.open.other";
	/** Actions that need a working tree, refused for bare repositories and remote mirrors. */
	private static final Set<String> WORKTREE_ACTIONS = Set.of(STAGE, UNSTAGE, DISCARD, DIFF, DIFF_STAGED,
			COMPARE_WORKTREE, RESTORE_VERSION, COMMIT, CREATE_BRANCH, CHECKOUT, PULL, STASH_CREATE, STASH_APPLY,
			STASH_DROP);
	private static final String EVENT_VIEW = "mainpanel.view";
	private static final String EVENT_REFRESH = "refresh.plugin.file.panel";
	private static final String WORKSPACE_SAVE_STATE = "workspace.save.state";
	private static final String WORKSPACE_RESTORE_STATE = "workspace.restore.state";
	private static final String WORKSPACE_STATE_CHANGED = "workspace.state.changed";
	private static final String WORKSPACE_STATE_KEY = "workspace.state";
	private static final String WORKSPACE_STATE_VERSION_KEY = "workspace.state.version";
	private static final String WORKSPACE_RESTORE_RESULT_KEY = "workspace.restore.result";
	private static final String WORKSPACE_RESTORE_RESOURCE_KEY = "workspace.restore.resource";
	private static final int WORKSPACE_STATE_VERSION = 1;

	private static final List<String> BASIC_COLUMNS = List.of("Name", "Git", "Size");
	private static final List<String> REF_COLUMNS = List.of("Name", "Revision");
	private static final List<String> COMMIT_COLUMNS = List.of("Name", "Author", "Date", "Message");

	private final String uuid = UUID.randomUUID().toString();
	private final GitRepositoryService repositories = new GitRepositoryService();
	private final NativeGitRunner nativeGit = new NativeGitRunner();
	private final RemoteMirrors mirrors;
	private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
	private final Map<String, GitRepositoryService.RepositoryInfo> infoCache = new ConcurrentHashMap<>();
	private final Map<String, Integer> pageDepths = new ConcurrentHashMap<>();
	/** How many commits each local repository's Commits list shows, grown by "Load more…". */
	private final Map<String, Integer> commitLimits = new ConcurrentHashMap<>();
	/** On-demand repositories replaced by a full mirror, so folders still open in them follow it. */
	private final Map<String, String> replacedRepositories = new ConcurrentHashMap<>();
	private final ExecutorService actions = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("nuclr-git-action-" + uuid).factory());

	private volatile NuclrPluginContext context;
	private volatile GitResource currentFolder;
	private volatile boolean focused;
	private volatile boolean unloading;
	private volatile Boolean nativeGitAvailable;

	public GitFilePanelPlugin() {
		this(RemoteMirrors.defaultCacheRoot());
	}

	GitFilePanelPlugin(Path mirrorCache) {
		this.mirrors = new RemoteMirrors(mirrorCache, nativeGit);
	}

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
		// Let Commander replace or delete the plugin and its cache on Windows.
		GitRepositoryService.releasePackFiles();
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
		if (!(resource instanceof GitResource requested) || cancelled(cancelled)) return null;
		GitResource selected = followReplacement(requested);
		try {
			return switch (selected.node().kind()) {
				case ROOT -> listRoot(selected);
				case OPEN_REPOSITORY -> openRepository(selected, cancelled);
				case OPEN_REMOTE -> openRemote(selected, cancelled);
				case REPOSITORY -> listRepository(selected);
				case WORKTREE, WORKTREE_DIRECTORY -> listWorkingTree(selected, cancelled);
				case STATUS -> listStatus(selected, cancelled);
				case STATUS_GROUP -> listStatusGroup(selected, cancelled);
				case BRANCHES -> listBranchesRoot(selected);
				case BRANCH_GROUP -> listBranches(selected);
				case COMMITS -> listCommits(selected, cancelled);
				case LOAD_MORE_COMMITS -> loadMoreCommits(selected, cancelled);
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
		entries.add(resource(GitNode.openRemote(), GitNode.root(), "Open Remote URL…", true, null, 0));
		for (Path recent : recentRepositories()) {
			try {
				GitRepositoryService.RepositoryInfo info = repositories.info(recent);
				infoCache.put(info.root().toString(), info);
				entries.add(resource(GitNode.repository(info.root().toString()), GitNode.root(),
						displayName(info.root()) + "  [" + info.branch() + "]", true, null, 0)
						.marker(mirrors.isMirror(info.root()) ? "remote" : ""));
			} catch (IOException ignored) {
				// Stale entries remain in settings but are omitted until opened again.
			}
		}
		showing(folder);
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

	private NuclrResourceData openRemote(GitResource selected, AtomicBoolean cancelled) throws IOException {
		String url = GitDialogs.input("Open Remote Repository",
				"Repository URL (HTTPS or SSH). Files and history download as you browse:", "");
		if (url == null || cancelled(cancelled)) return listRoot(resource(GitNode.root(), null, "Git", true, null, 0));
		if (!isNativeGitAvailable()) {
			throw new IOException("The native git executable is required to open a remote repository.");
		}
		Path mirror = mirrors.open(url, () -> unloading || cancelled(cancelled));
		Path root = repositories.findRepository(mirror);
		if (root == null) throw new IOException("The mirror of " + url + " is not a readable Git repository.");
		infoCache.put(root.toString(), repositories.info(root));
		rememberRepository(root);
		return listRepository(resource(GitNode.repository(root.toString()), GitNode.root(), displayName(root), true, null, 0));
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
		showing(folder);
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listWorkingTree(GitResource folder, AtomicBoolean cancelled) throws IOException {
		Path root = repository(folder.node());
		String relative = folder.node().relativePath();
		Path directory = safeResolve(root, relative);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Working-tree folder no longer exists: " + directory);
		}
		GitStatusSnapshot snapshot = refreshStatus(root);
		var entries = new ArrayList<NuclrResource>();
		try (var stream = Files.list(directory)) {
			var children = stream.filter(path -> !".git".equals(path.getFileName().toString()))
					.sorted(Comparator.comparing((Path path) -> !isDirectoryNoFollow(path))
							.thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.toList();
			for (Path child : children) {
				if (cancelled(cancelled)) break;
				BasicFileAttributes attributes;
				try {
					attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				} catch (IOException unreadable) {
					// A single unreadable entry must not fail the whole listing.
					LOG.debug("Skipping unreadable work-tree entry {}", child, unreadable);
					continue;
				}
				boolean directoryChild = attributes.isDirectory();
				String childRelative = relative.isEmpty() ? child.getFileName().toString()
						: relative + "/" + child.getFileName();
				GitNode.Kind kind = directoryChild ? GitNode.Kind.WORKTREE_DIRECTORY : GitNode.Kind.WORKTREE_FILE;
				GitResource entry = resource(new GitNode(kind, root.toString(), null, childRelative, null),
						folder.node(), child.getFileName().toString(), directoryChild, child, safeSize(child));
				entry.marker(snapshot.marker(childRelative, directoryChild));
				entry.setLink(attributes.isSymbolicLink());
				entry.setHidden(entry.getName().startsWith("."));
				entries.add(entry);
			}
		}
		showing(folder);
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
		showing(folder);
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
			Path existing = Files.exists(local, LinkOption.NOFOLLOW_LINKS) && !isDirectoryNoFollow(local) ? local : null;
			GitResource entry = resource(new GitNode(GitNode.Kind.STATUS_FILE, root.toString(), null,
					state.path(), state.marker()), folder.node(), state.path(), false, existing, safeSize(local));
			entry.setLink(existing != null && Files.isSymbolicLink(existing));
			entries.add(entry.marker(state.marker()));
		}
		showing(folder);
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	private NuclrResourceData listBranchesRoot(GitResource folder) {
		Path root = repository(folder.node());
		var entries = List.<NuclrResource>of(
				resource(new GitNode(GitNode.Kind.BRANCH_GROUP, root.toString(), null, null, "local"),
						folder.node(), "Local", true, null, 0),
				resource(new GitNode(GitNode.Kind.BRANCH_GROUP, root.toString(), null, null, "remote"),
						folder.node(), "Remote", true, null, 0));
		showing(folder);
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
		showing(folder);
		return withParent(folder, REF_COLUMNS, entries);
	}

	private NuclrResourceData listCommits(GitResource folder, AtomicBoolean cancelled) throws IOException {
		Path root = repository(folder.node());
		boolean onDemand = isOnDemand(root);
		List<GitRepositoryService.CommitInfo> commits;
		String next = null;
		String nextDetails = null;
		if (onDemand) {
			if (repositories.loadedCommits(root, 1).isEmpty()) {
				repositories.loadNextPage(root, pageDepth(root), () -> unloading || cancelled(cancelled));
			}
			commits = repositories.loadedCommits(root, MAX_LISTED_COMMITS);
			next = commits.size() < MAX_LISTED_COMMITS ? repositories.nextPageStart(root) : null;
			nextDetails = commits.size() + " commits loaded; older history is downloaded page by page";
		} else {
			// One past the limit says whether there is anything older to offer.
			int limit = commitLimits.getOrDefault(root.toString(), MAX_COMMITS);
			commits = repositories.commits(root, folder.node().revision(), limit + 1);
			if (commits.size() > limit) {
				commits = commits.subList(0, limit);
				if (limit < MAX_LISTED_COMMITS) {
					// The limit it asks for, not "more": reopening the entry (a refresh) shows the same list.
					next = String.valueOf(Math.min(limit + MAX_COMMITS, MAX_LISTED_COMMITS));
					nextDetails = limit + " commits shown; older ones are listed " + MAX_COMMITS + " at a time";
				}
			}
		}
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.CommitInfo commit : commits) {
			GitNode node = new GitNode(GitNode.Kind.COMMIT, root.toString(), commit.id(), null, commit.shortId());
			entries.add(resource(node, folder.node(), commit.shortId(), true, null, 0)
					.details(commit.author(), commit.instant(), commit.subject()));
		}
		if (next != null) {
			entries.add(resource(new GitNode(GitNode.Kind.LOAD_MORE_COMMITS, root.toString(), null, null, next),
					folder.node(), "Load more…", true, null, 0)
					.details(null, null, nextDetails));
		}
		showing(folder);
		return withParent(folder, COMMIT_COLUMNS, entries);
	}

	/**
	 * Fetches the history page the entry points at, then shows the extended
	 * Commits list. The page start is part of the node, so reopening the entry
	 * (a panel refresh) does not fetch another page.
	 */
	private NuclrResourceData loadMoreCommits(GitResource selected, AtomicBoolean cancelled) throws IOException {
		Path root = repository(selected.node());
		if (isOnDemand(root)) {
			int before = repositories.loadedCommits(root, MAX_LISTED_COMMITS).size();
			repositories.loadPage(root, selected.node().value(), pageDepth(root), () -> unloading || cancelled(cancelled));
			int added = repositories.loadedCommits(root, MAX_LISTED_COMMITS).size() - before;
			adaptPageDepth(root, added);
		} else {
			// A local repository has its whole history already; the entry only raises the limit.
			int limit;
			try {
				limit = Math.min(Integer.parseInt(selected.node().value()), MAX_LISTED_COMMITS);
			} catch (NumberFormatException e) {
				limit = MAX_COMMITS;
			}
			commitLimits.merge(root.toString(), limit, Math::max);
		}
		GitNode commits = new GitNode(GitNode.Kind.COMMITS, root.toString(), "HEAD", null, null);
		return listCommits(resource(commits, GitNode.repository(root.toString()), "Commits", true, null, 0), cancelled);
	}

	private int pageDepth(Path root) {
		return pageDepths.getOrDefault(root.toString(), LazyRemote.DEFAULT_PAGE_DEPTH);
	}

	/**
	 * Depth counts steps along every parent, so the commits a page brings vary
	 * with how much a project merges. Steer towards {@link #PAGE_TARGET} commits.
	 */
	private void adaptPageDepth(Path root, int added) {
		int depth = pageDepth(root);
		if (added < PAGE_TARGET / 2) depth = Math.min(depth * 2, MAX_PAGE_DEPTH);
		else if (added > PAGE_TARGET * 3) depth = Math.max(depth / 2, 1);
		pageDepths.put(root.toString(), depth);
	}

	private NuclrResourceData listTags(GitResource folder) throws IOException {
		Path root = repository(folder.node());
		var entries = new ArrayList<NuclrResource>();
		for (GitRepositoryService.RefInfo ref : repositories.tags(root)) {
			entries.add(resource(new GitNode(GitNode.Kind.REF, root.toString(), ref.fullName(), null, ref.name()),
					folder.node(), ref.name(), true, null, 0).column("Revision", abbreviate(ref.revision(), 10)));
		}
		showing(folder);
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
		showing(folder);
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
			if (tree.symlink()) entry.link(true).column("Git", "link");
			if (tree.gitlink()) entry.column("Git", "submodule");
			if (tree.executable()) entry.column("git.executable", true);
			entries.add(entry);
		}
		showing(folder);
		return withParent(folder, BASIC_COLUMNS, entries);
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {
		var items = new ArrayList<NuclrMenuResource>();
		if (source instanceof GitResource resource && canView(resource)) items.add(menu("View", "F3", VIEW));
		items.add(menu("Copy", "F5", COPY));
		Path root = repositoryOf(currentFolder);
		if (root == null) {
			// The repository list: nothing to commit or stage here, only entries to add and remove.
			items.add(menu("From Other Panel", "F7", OPEN_OTHER));
			items.add(menu("Remove", "F8", REMOVE_RECENT));
		} else if (!isBare(root)) {
			items.add(menu("Commit", "F2", COMMIT));
			items.add(menu("Diff", "Shift+F3", DIFF));
			items.add(menu("Stage", "Shift+F5", STAGE));
			items.add(menu("Unstage", "Shift+F6", UNSTAGE));
		}
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
		List<GitResource> selection = chosen(selectedResources, resource);
		boolean selectionHasWorkingPath = selection.stream().anyMatch(GitFilePanelPlugin::hasWorkingPath);
		boolean selectionHasStagedChange = selection.stream().anyMatch(GitFilePanelPlugin::hasStagedChange);
		boolean selectionHasWorkingChange = selection.stream().anyMatch(GitFilePanelPlugin::hasWorkingChange);
		boolean selectionHasUntracked = selection.stream().anyMatch(GitFilePanelPlugin::isUntracked);
		Path root = repositoryOf(resource);
		boolean bare = isBare(root);
		if (resource != null && canView(resource)) items.add(action("View", VIEW, "view", true, false));
		if (resource != null && !resource.isFolder()) {
			boolean stagedChange = hasStagedChange(resource);
			boolean workingChange = hasWorkingChange(resource);
			items.add(action("Copy to opposite panel", COPY, "copy", true, false));
			if (selectionHasWorkingPath) {
				items.add(NuclrContextMenuItem.separator());
				items.add(action("Stage / Add", STAGE, "add", selectionHasWorkingChange || selectionHasUntracked, false));
				items.add(action("Unstage", UNSTAGE, "remove", selectionHasStagedChange, false));
				items.add(action("Discard working changes", DISCARD, "undo", selectionHasWorkingChange, true));
				items.add(action("Working diff", DIFF, "view", workingChange, false));
				items.add(action("Staged diff", DIFF_STAGED, "view", stagedChange, false));
				items.add(action("File history", HISTORY, "history", true, false));
				items.add(action("Blame", BLAME, "info", true, false));
			}
			if (!bare && resource.node().kind() == GitNode.Kind.REVISION_BLOB
					&& !"submodule".equals(resource.getMetadata("Git", ""))) {
				items.add(NuclrContextMenuItem.separator());
				items.add(action("Compare with working tree", COMPARE_WORKTREE, "view", true, false));
				items.add(action("Restore this version", RESTORE_VERSION, "undo", true, true));
			}
		}
		if (!bare && resource != null && resource.node().kind() == GitNode.Kind.REF) {
			items.add(action("Checkout branch", CHECKOUT, "refresh", true, false));
		}
		if (!bare && resource != null && resource.node().kind() == GitNode.Kind.STASH) {
			items.add(action("Apply stash", STASH_APPLY, "undo", true, false));
			items.add(action("Drop stash", STASH_DROP, "delete", true, true));
		}
		if (resource != null && resource.node().kind() == GitNode.Kind.REPOSITORY && !"..".equals(resource.getName())) {
			boolean mirror = mirrors.isMirror(root);
			items.add(action(mirror ? "Remove and delete local mirror" : "Remove from list",
					REMOVE_RECENT, "delete", true, mirror));
		}
		if (root != null && bare) {
			if (!items.isEmpty()) items.add(NuclrContextMenuItem.separator());
			items.add(action("Fetch all", FETCH, "refresh", nativeAvailable(), false));
			if (isOnDemand(root)) {
				items.add(action("Download full history", FULL_HISTORY, "download", nativeAvailable(), false));
			}
			// Pushing from a mirror forces the remote to match it, deleting refs
			// created there since the last fetch.
			if (!mirrors.isMirror(root)) items.add(action("Push", PUSH, "upload", nativeAvailable(), false));
		} else if (root != null) {
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
		if (WORKSPACE_SAVE_STATE.equals(actionType)) {
			if (data != null) data.put(WORKSPACE_STATE_KEY, workspaceState(currentFolder));
			if (data != null) data.put(WORKSPACE_STATE_VERSION_KEY, WORKSPACE_STATE_VERSION);
			return;
		}
		if (WORKSPACE_RESTORE_STATE.equals(actionType)) {
			if (data != null) {
				data.put(WORKSPACE_RESTORE_RESOURCE_KEY, restoreLocation(data.get(WORKSPACE_STATE_KEY)));
				data.put(WORKSPACE_RESTORE_RESULT_KEY, "restored");
			}
			return;
		}
		GitResource focused = focusedResource instanceof GitResource git ? git : null;
		Path target = repositoryOf(focused != null ? focused : currentFolder);
		if (isBare(target) && (WORKTREE_ACTIONS.contains(actionType) || PUSH.equals(actionType) && mirrors.isMirror(target))) {
			GitDialogs.error("Git", mirrors.isMirror(target)
					? "This is a read-only mirror of a remote repository. Clone it locally to make changes."
					: "This repository has no working tree.");
			return;
		}
		switch (actionType) {
			case VIEW -> view(focused);
			case COPY -> copyTo(other, chosen(selectedResources, focused), callback);
			case REFRESH -> refresh();
			case STAGE -> pathOperation("Stage", "add", false, chosenPaths(selectedResources, focused), callback);
			case UNSTAGE -> pathOperation("Unstage", "restore", false,
					chosenPaths(selectedResources, focused, GitFilePanelPlugin::hasStagedChange), callback,
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
			case FETCH -> fetch(callback);
			case FULL_HISTORY -> downloadFullHistory(callback);
			case PULL -> repositoryOperation("Pull", callback, "pull", "--ff-only");
			case PUSH -> repositoryOperation("Push", callback, "push");
			case STASH_CREATE -> repositoryOperation("Stash", callback, "stash", "push", "-u", "-m", "Nuclr Commander");
			case STASH_APPLY -> stash(focused, false, callback);
			case STASH_DROP -> stash(focused, true, callback);
			case REMOVE_RECENT, DELETE_KEY -> removeRecent(focused);
			case OPEN_OTHER -> addFromOtherPanel(other);
			default -> LOG.debug("Git panel ignoring action [{}]", actionType);
		}
	}

	private void view(GitResource resource) {
		if (resource == null || !canView(resource) || context == null) return;
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
		// Prefer native git: it restores permissions/modes correctly. Fall back to
		// a pure-JGit blob write (which needs no external git) so the feature keeps
		// working on hosts without the native executable.
		if (isNativeGitAvailable()) {
			runNative("Restore version", root, callback, "restore", "--source=" + resource.node().revision(),
					"--worktree", "--", resource.node().relativePath());
			return;
		}
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

	/** Records the folder on screen and tells the workspace, so a restart reopens it. */
	private void showing(GitResource folder) {
		currentFolder = folder;
		NuclrPluginContext pluginContext = context;
		if (pluginContext == null || folder == null) return;
		var payload = new HashMap<String, Object>();
		payload.put("plugin.uuid", uuid);
		payload.put(WORKSPACE_STATE_KEY, workspaceState(folder));
		payload.put(WORKSPACE_STATE_VERSION_KEY, WORKSPACE_STATE_VERSION);
		pluginContext.getEventBus().emit(this, WORKSPACE_STATE_CHANGED, payload);
	}

	/** The folder's address as plain JSON-friendly values. */
	private static Map<String, Object> workspaceState(GitResource folder) {
		GitNode node = folder == null ? GitNode.root() : folder.node();
		var state = new HashMap<String, Object>();
		state.put("kind", node.kind().name());
		state.put("repository", node.repository());
		state.put("revision", node.revision());
		state.put("path", node.relativePath());
		state.put("value", node.value());
		return state;
	}

	/**
	 * The folder to reopen after a restart. Runs off the EDT and must not prompt:
	 * entries that open a dialog or fetch a page come back as their folder, and a
	 * repository that is gone (deleted, or replaced by a full mirror) comes back
	 * as the Git root. A missing or unreadable state — saved before this plugin
	 * took part — also opens the root, never an empty panel.
	 */
	private GitResource restoreLocation(Object saved) {
		GitResource root = resource(GitNode.root(), null, "Git", true, null, 0);
		if (!(saved instanceof Map<?, ?> state)) return root;
		GitNode.Kind kind;
		try {
			kind = GitNode.Kind.valueOf(String.valueOf(state.get("kind")));
		} catch (IllegalArgumentException e) {
			return root;
		}
		String repository = state.get("repository") instanceof String value ? value : "";
		GitNode node = switch (kind) {
			case ROOT, OPEN_REPOSITORY, OPEN_REMOTE -> null;
			case LOAD_MORE_COMMITS -> new GitNode(GitNode.Kind.COMMITS, repository, "HEAD", null, null);
			default -> new GitNode(kind, repository, text(state.get("revision")), text(state.get("path")),
					text(state.get("value")));
		};
		if (node == null || node.repository().isBlank()) return root;
		try {
			// Checked first: discovery would otherwise walk up from a deleted folder
			// and could land in an enclosing repository.
			Path repositoryPath = Path.of(node.repository());
			if (!Files.isDirectory(repositoryPath)
					|| !repositoryPath.toRealPath().equals(repositories.findRepository(repositoryPath))) return root;
		} catch (IOException | RuntimeException e) {
			return root;
		}
		String name = !node.relativePath().isBlank() ? node.relativePath().substring(node.relativePath().lastIndexOf('/') + 1)
				: node.kind() == GitNode.Kind.REPOSITORY ? repositoryName(Path.of(node.repository()))
				: node.kind().name().charAt(0) + node.kind().name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
		return resource(node, parentOf(node), name, true, null, 0);
	}

	private static String text(Object value) {
		return value instanceof String string ? string : null;
	}

	private void fetch(NuclrPluginCallback callback) {
		Path root = repositoryOf(currentFolder);
		if (root == null) return;
		if (!isOnDemand(root)) {
			runNative("Fetch", root, callback, "fetch", "--all", "--prune");
			return;
		}
		// An on-demand repository refreshes its branch and tag tips only; history
		// pages are anchored at the old HEAD and start again from the new one.
		runBackground("Fetch", root, callback, cancelled -> repositories.refreshOnDemand(root, cancelled));
	}

	private void downloadFullHistory(NuclrPluginCallback callback) {
		Path root = repositoryOf(currentFolder);
		GitRepositoryService.RepositoryInfo info = root == null ? null : infoCache.get(root.toString());
		if (info == null || !info.onDemand() || info.origin() == null) return;
		if (!GitDialogs.confirm("Download Full History", "Download the complete history of " + displayName(root)
				+ "?\n\nLarge projects can take several minutes and gigabytes of disk space.")) return;
		runBackground("Download full history", root, callback, cancelled -> {
			Path mirror = repositories.findRepository(mirrors.downloadFullHistory(info.origin(), cancelled));
			replacedRepositories.put(root.toString(), mirror.toString());
			forgetRepository(root);
			rememberRepository(mirror);
			infoCache.remove(root.toString());
			infoCache.put(mirror.toString(), repositories.info(mirror));
		});
	}

	/** Runs plugin-side work (not a single native command) off the EDT, reporting to the callback. */
	private void runBackground(String title, Path root, NuclrPluginCallback callback, IoTask task) {
		if (!isNativeGitAvailable()) {
			GitDialogs.error(title, "The native git executable is required for this operation.");
			return;
		}
		actions.submit(() -> {
			try {
				if (callback != null) callback.onStart(title);
				task.run(() -> unloading || callback != null && callback.isCancelled());
				statusCache.remove(root.toString());
				if (callback != null) callback.onComplete();
				requestRefresh(uuid);
			} catch (NativeGitRunner.CancelledException cancelled) {
				if (callback != null) callback.onError(title, cancelled);
			} catch (IOException | RuntimeException e) {
				LOG.warn("{} failed: {}", title, e.getMessage(), e);
				if (callback != null) callback.onError(title, e);
				if (!unloading) GitDialogs.error(title, e.getMessage());
			}
		});
	}

	/** Re-addresses a resource of a replaced on-demand repository to its full mirror. */
	private GitResource followReplacement(GitResource resource) {
		String replacement = replacedRepositories.get(resource.node().repository());
		if (replacement == null) return resource;
		GitNode node = resource.node();
		GitNode parent = resource.parentNode();
		return resource(new GitNode(node.kind(), replacement, node.revision(), node.relativePath(), node.value()),
				parent == null ? null : new GitNode(parent.kind(), replacedRepositories.getOrDefault(parent.repository(),
						parent.repository()), parent.revision(), parent.relativePath(), parent.value()),
				resource.getName(), resource.isFolder(), resource.isFolder() ? null : resource.getPath(),
				resource.getLength());
	}

	private void removeRecent(GitResource resource) {
		if (resource == null || resource.node().kind() != GitNode.Kind.REPOSITORY) return;
		Path root = repository(resource.node());
		boolean mirror = mirrors.isMirror(root);
		if (mirror && !GitDialogs.confirm("Remove Remote Repository", "Remove " + displayName(root)
				+ " from the list and delete its local mirror?")) return;
		forgetRepository(root);
		infoCache.remove(root.toString());
		statusCache.remove(root.toString());
		if (!mirror) {
			requestRefresh(uuid);
			return;
		}
		actions.submit(() -> {
			try {
				mirrors.remove(root);
			} catch (IOException e) {
				LOG.warn("Unable to delete mirror {}: {}", root, e.getMessage(), e);
				if (!unloading) GitDialogs.error("Remove Remote Repository", e.getMessage());
			}
			requestRefresh(uuid);
		});
	}

	/**
	 * Puts the repository the opposite panel is in at the top of the list, saving the
	 * folder chooser for the repository that is usually already on screen.
	 */
	private void addFromOtherPanel(BaseNuclrPlugin other) {
		NuclrResource folder = other == null || other == this ? null : other.getCurrentResource();
		Path path = folder != null ? folder.getPath() : null;
		if (path == null || !Files.isDirectory(path)) {
			GitDialogs.error("Git", "The other panel is not showing a folder on this computer.");
			return;
		}
		try {
			Path root = repositories.findRepository(path);
			if (root == null) {
				GitDialogs.error("Git", "No Git repository was found at or above " + path);
				return;
			}
			infoCache.put(root.toString(), repositories.info(root));
			rememberRepository(root);
			requestRefresh(uuid);
		} catch (IOException e) {
			GitDialogs.error("Git", e.getMessage());
		}
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
		List<String> paths = chosenPaths(selected, focused, GitFilePanelPlugin::hasWorkingChange);
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
			} catch (NativeGitRunner.CancelledException cancelled) {
				if (callback != null) callback.onError(title, cancelled);
			} catch (IOException e) {
				if (callback != null) callback.onError(title, e);
				if (!unloading) GitDialogs.error(title, e.getMessage());
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
		// Ask once up front instead of interrupting the copy loop with a modal
		// dialog per file (a blocking dialog on this worker thread can deadlock).
		if (anyTargetExists(sources, targetRoot)
				&& !GitDialogs.confirm("Copy", "Some files already exist in the target folder. Replace them?")) return;
		actions.submit(() -> {
			try {
				if (callback != null) callback.onStart("Copying from Git");
				for (GitResource source : sources) {
					if (unloading || callback != null && callback.isCancelled()) throw new NativeGitRunner.CancelledException();
					copyResource(source, targetRoot, callback);
				}
				if (callback != null) callback.onComplete();
				requestRefresh(other.uuid());
			} catch (NativeGitRunner.CancelledException cancelled) {
				if (callback != null) callback.onError("Copy from Git", cancelled);
			} catch (IOException e) {
				if (callback != null) callback.onError("Copy from Git", e);
				GitDialogs.error("Copy", e.getMessage());
			}
		});
	}

	private static boolean anyTargetExists(List<GitResource> sources, Path targetRoot) {
		for (GitResource source : sources) {
			String name = source.getName();
			if (name == null || name.isBlank() || "..".equals(name)) continue;
			Path target = targetRoot.resolve(name).normalize();
			if (target.startsWith(targetRoot) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
		}
		return false;
	}

	private void copyResource(GitResource source, Path destination, NuclrPluginCallback callback) throws IOException {
		String name = source.getName();
		if (name == null || name.isBlank() || "..".equals(name)) return;
		Path target = destination.resolve(name).normalize();
		if (!target.startsWith(destination)) throw new IOException("Unsafe Git path: " + name);
		if (source.node().kind() == GitNode.Kind.WORKTREE_FILE) {
			copyLocal(safeResolve(Path.of(source.node().repository()), source.node().relativePath()), target, destination);
			return;
		}
		if (source.node().kind() == GitNode.Kind.STATUS_FILE) {
			Path working = safeResolve(Path.of(source.node().repository()), source.node().relativePath());
			if (!Files.exists(working, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("The working-tree file does not exist: " + source.node().relativePath());
			}
			copyLocal(working, target, destination);
			return;
		}
		if (source.node().kind() == GitNode.Kind.WORKTREE_DIRECTORY) {
			copyLocal(safeResolve(Path.of(source.node().repository()), source.node().relativePath()), target, destination);
			return;
		}
		if (source.node().kind() == GitNode.Kind.REVISION_BLOB) {
			if ("submodule".equals(source.getMetadata("Git", ""))) {
				throw new IOException("Submodule entries cannot be copied without the submodule repository.");
			}
			writeHistorical(source, target, destination);
			return;
		}
		if (source.node().kind() == GitNode.Kind.REVISION_TREE) {
			copyHistoricalTree(source, target, destination, callback);
			return;
		}
		throw new IOException("This Git node cannot be copied: " + source.getName());
	}

	private void writeHistorical(GitResource source, Path target, Path safeRoot) throws IOException {
		// Replacement conflicts are confirmed once for the whole batch in copyTo().
		if (source.isLink()) {
			String linkTarget = new String(repositories.blob(Path.of(source.node().repository()), source.node().value()),
					java.nio.charset.StandardCharsets.UTF_8);
			try {
				Path link = Path.of(linkTarget);
				prepareTarget(target, safeRoot);
				createSymbolicLink(target, link);
			} catch (java.nio.file.InvalidPathException e) {
				throw new IOException("Historical symbolic link has an invalid target.", e);
			}
			return;
		}
		prepareTarget(target, safeRoot);
		try (InputStream input = repositories.openBlob(Path.of(source.node().repository()), source.node().value());
				OutputStream output = Files.newOutputStream(target)) {
			input.transferTo(output);
		}
		applyExecutable(source, target);
	}

	private void copyHistoricalTree(GitResource source, Path target, Path safeRoot,
			NuclrPluginCallback callback) throws IOException {
		prepareDirectory(target, safeRoot);
		for (GitRepositoryService.TreeEntry entry : repositories.tree(Path.of(source.node().repository()),
				source.node().revision(), source.node().relativePath())) {
			if (unloading || callback != null && callback.isCancelled()) throw new NativeGitRunner.CancelledException();
			if (entry.gitlink()) throw new IOException("Submodule entries cannot be copied without the submodule repository.");
			GitNode.Kind kind = entry.directory() ? GitNode.Kind.REVISION_TREE : GitNode.Kind.REVISION_BLOB;
			String value = entry.directory() ? source.node().value() : entry.objectId();
			GitResource child = resource(new GitNode(kind, source.node().repository(), source.node().revision(),
					entry.path(), value), source.node(), entry.name(), entry.directory(), null, entry.size());
			if (entry.symlink()) child.link(true).column("Git", "link");
			if (entry.executable()) child.column("git.executable", true);
			if (entry.directory()) copyHistoricalTree(child, target.resolve(entry.name()), safeRoot, callback);
			else writeHistorical(child, target.resolve(entry.name()), safeRoot);
		}
	}

	private static void copyLocal(Path source, Path target, Path safeRoot) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (attributes.isSymbolicLink()) {
			prepareTarget(target, safeRoot);
			createSymbolicLink(target, Files.readSymbolicLink(source));
		} else if (attributes.isDirectory()) {
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					prepareDirectory(target.resolve(source.relativize(dir).toString()), safeRoot);
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Path destination = target.resolve(source.relativize(file).toString());
					copyLocal(file, destination, safeRoot);
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			prepareTarget(target, safeRoot);
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}

	private static void prepareTarget(Path target, Path safeRoot) throws IOException {
		prepareDirectoryParents(target.getParent(), safeRoot);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
	}

	private static void createSymbolicLink(Path target, Path linkTarget) throws IOException {
		try {
			Files.createSymbolicLink(target, linkTarget);
		} catch (UnsupportedOperationException | SecurityException e) {
			throw new IOException("Symbolic links are not supported at the copy destination.", e);
		}
	}

	private static void applyExecutable(GitResource source, Path target) throws IOException {
		if (!Boolean.TRUE.equals(source.getMetadata("git.executable", Boolean.FALSE))) return;
		PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			var permissions = new java.util.HashSet<>(view.readAttributes().permissions());
			permissions.add(PosixFilePermission.OWNER_EXECUTE);
			permissions.add(PosixFilePermission.GROUP_EXECUTE);
			permissions.add(PosixFilePermission.OTHERS_EXECUTE);
			view.setPermissions(permissions);
		} else {
			target.toFile().setExecutable(true, false);
		}
	}

	private static void prepareDirectory(Path target, Path safeRoot) throws IOException {
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isDirectory() || attributes.isSymbolicLink()) Files.delete(target);
		}
		prepareDirectoryParents(target, safeRoot);
	}

	private static void prepareDirectoryParents(Path directory, Path safeRoot) throws IOException {
		Path root = safeRoot.toAbsolutePath().normalize();
		Path target = directory.toAbsolutePath().normalize();
		if (!target.startsWith(root)) throw new IOException("Unsafe copy destination: " + target);
		if (target.equals(root)) return;
		Path current = root;
		for (Path part : root.relativize(target)) {
			if (part.toString().isEmpty()) continue;
			current = current.resolve(part);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
					throw new IOException("Copy destination contains a symbolic link or non-directory: " + current);
				}
			} else {
				Files.createDirectory(current);
			}
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

	@Override
	public NuclrResource getCurrentResource() {
		// A new instance shows the Git root rather than nothing.
		GitResource folder = currentFolder;
		return folder != null ? folder : resource(GitNode.root(), null, "Git", true, null, 0);
	}
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
		return displayName(root) + " — " + branch + " — " + area;
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
		GitStatusSnapshot snapshot = isBare(root) ? GitStatusSnapshot.empty() : nativeStatus(root);
		if (snapshot == null) snapshot = repositories.status(root);
		statusCache.put(root.toString(), new CachedStatus(snapshot, System.nanoTime()));
		return snapshot;
	}

	/**
	 * Status from native git, which keeps its own caches of what changed (stat data in the
	 * index, the untracked cache, fsmonitor) where JGit hashes its way through the whole
	 * tree each time; on a large repository that is the difference between instant and
	 * seconds. {@code null} when there is no git, or its answer is not one to trust.
	 */
	private GitStatusSnapshot nativeStatus(Path root) {
		if (!Boolean.TRUE.equals(nativeGitAvailable)) return null;
		try {
			// No optional locks: a listing must never hold up the user's own git in a terminal.
			NativeGitRunner.Result result = nativeGit.runForOutput(root, () -> unloading, "--no-optional-locks",
					"status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignored=matching");
			if (!result.succeeded() || result.truncated()) return null;
			return GitStatusSnapshot.fromPorcelain(result.output());
		} catch (IOException e) {
			LOG.debug("Native git status failed in {}, reading it with JGit", root, e);
			return null;
		}
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

	private boolean isNativeGitAvailable() {
		if (nativeGitAvailable == null) {
			nativeGitAvailable = nativeGit.available();
		}
		return Boolean.TRUE.equals(nativeGitAvailable);
	}

	private boolean nativeAvailable() {
		// Optimistic while the background probe is still running: runNative()
		// re-checks and reports a clear error if git is genuinely unavailable.
		Boolean available = nativeGitAvailable;
		return available == null || available;
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
			case OPEN_REPOSITORY, OPEN_REMOTE, REPOSITORY -> GitNode.root();
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
			case COMMIT, LOAD_MORE_COMMITS -> new GitNode(GitNode.Kind.COMMITS, node.repository(), "HEAD", null, null);
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
		NuclrPluginContext pluginContext = context;
		if (pluginContext == null) return; // plugin is unloading
		var paths = new LinkedHashSet<String>();
		paths.add(root.toString());
		for (Path recent : recentRepositories()) paths.add(recent.toString());
		pluginContext.getSettings().set(PLUGIN_ID, SETTINGS_RECENT, paths.stream().limit(MAX_RECENT).toList());
	}

	private void forgetRepository(Path root) {
		NuclrPluginContext pluginContext = context;
		if (pluginContext == null) return;
		String key = root.toString();
		pluginContext.getSettings().set(PLUGIN_ID, SETTINGS_RECENT, recentRepositories().stream()
				.map(Path::toString).filter(path -> !path.equals(key)).toList());
	}

	private Path lastRepositoryParent() {
		// Mirrors live in the plugin's cache; never start the folder chooser there.
		return recentRepositories().stream().filter(path -> !mirrors.isMirror(path)).findFirst()
				.orElse(Path.of(System.getProperty("user.home")));
	}

	private boolean isOnDemand(Path root) {
		if (root == null) return false;
		GitRepositoryService.RepositoryInfo info = infoCache.get(root.toString());
		if (info != null) return info.onDemand();
		try {
			return repositories.isOnDemand(root);
		} catch (IOException e) {
			return false;
		}
	}

	private boolean isBare(Path root) {
		if (root == null) return false;
		GitRepositoryService.RepositoryInfo info = infoCache.get(root.toString());
		return info != null ? info.bare() : mirrors.isMirror(root);
	}

	/** Remote mirrors are named after their URL rather than the hashed cache folder. */
	private String displayName(Path root) {
		GitRepositoryService.RepositoryInfo info = infoCache.get(root.toString());
		if (mirrors.isMirror(root) && info != null && info.origin() != null) return RemoteMirrors.displayName(info.origin());
		return repositoryName(root);
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

	private static boolean hasStagedChange(GitResource resource) {
		String marker = resource == null ? "" : resource.getMetadata("Git", "");
		return marker.length() >= 1 && marker.charAt(0) != ' ' && marker.charAt(0) != '?';
	}

	private static boolean hasWorkingChange(GitResource resource) {
		String marker = resource == null ? "" : resource.getMetadata("Git", "");
		return "UU".equals(marker)
				|| marker.length() >= 2 && marker.charAt(1) != ' ' && marker.charAt(1) != '?';
	}

	private static boolean isUntracked(GitResource resource) {
		return resource != null && "??".equals(resource.getMetadata("Git", ""));
	}

	private static boolean canView(GitResource resource) {
		if (resource == null) return false;
		if (!resource.isFolder()) return true;
		return switch (resource.node().kind()) {
			case COMMIT, REF, STASH -> true;
			default -> false;
		};
	}

	private static boolean isDirectoryNoFollow(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory();
		} catch (IOException e) {
			return false;
		}
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
		return chosenPaths(selected, focused, resource -> true);
	}

	private static List<String> chosenPaths(List<NuclrResource> selected, GitResource focused,
			Predicate<GitResource> filter) {
		return chosen(selected, focused).stream()
				.filter(filter)
				.map(GitFilePanelPlugin::relativePath)
				.filter(path -> path != null && !path.isBlank()).distinct().toList();
	}

	private static Path safeResolve(Path root, String relative) throws IOException {
		if (relative == null || relative.isBlank()) return root.toAbsolutePath().normalize();
		Path resolved = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
		if (!resolved.startsWith(root.toAbsolutePath().normalize())) throw new IOException("Unsafe repository path: " + relative);
		return resolved;
	}

	private static long safeSize(Path path) {
		if (path == null) return 0L;
		try {
			// Follows links so symlinked files report their real size (a NOFOLLOW read
			// would report 0 for every symlink in the listing).
			return Files.isRegularFile(path) ? Files.size(path) : 0L;
		} catch (IOException e) { return 0L; }
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

	@FunctionalInterface
	private interface IoTask { void run(java.util.function.BooleanSupplier cancelled) throws IOException; }

	private record CachedStatus(GitStatusSnapshot snapshot, long createdAtNanos) { }
}
