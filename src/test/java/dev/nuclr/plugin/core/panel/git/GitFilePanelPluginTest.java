package dev.nuclr.plugin.core.panel.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrResource;

class GitFilePanelPluginTest {

	@TempDir Path temp;

	@Test
	void rootAndRepositoryAreNavigableWithoutClaimingOrdinaryFilesystemFolders() throws Exception {
		Path root = repository(temp.resolve("repo"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());

		var menuRoot = plugin.getPluginMenuItems().getMenuItems().get(0).getPath();
		assertTrue(plugin.supports(menuRoot));
		assertEquals("Open Repository…", plugin.openResource(menuRoot, null).getEntries().get(0).getName());

		GitResource repository = new GitResource(GitNode.repository(root.toString()), GitNode.root(),
				root.getFileName().toString(), true, null, 0);
		var categories = plugin.openResource(repository, null);
		assertTrue(categories.getEntries().stream().anyMatch(entry -> entry.getName().equals("Working Tree")));
		assertTrue(categories.getEntries().stream().anyMatch(entry -> entry.getName().equals("Commits")));
		assertFalse(plugin.supports(new TestLocalResource(root)));
		plugin.unload();
	}

	@Test
	void workingTreeAndCommitTreesExposeReadableFiles() throws Exception {
		Path root = repository(temp.resolve("repo-content"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());
		GitNode repoNode = GitNode.repository(root.toString());

		GitResource worktree = new GitResource(new GitNode(GitNode.Kind.WORKTREE, root.toString(), null, null, null),
				repoNode, "Working Tree", true, root, 0);
		var files = plugin.openResource(worktree, null);
		assertTrue(files.getEntries().stream().anyMatch(entry -> entry.getName().equals("tracked.txt")));

		String head;
		try (Git git = Git.open(root.toFile())) { head = git.getRepository().resolve("HEAD").name(); }
		GitResource commit = new GitResource(new GitNode(GitNode.Kind.COMMIT, root.toString(), head, null, head.substring(0, 8)),
				new GitNode(GitNode.Kind.COMMITS, root.toString(), "HEAD", null, null), head.substring(0, 8), true, null, 0);
		var tree = plugin.openResource(commit, null);
		var historical = tree.getEntries().stream().filter(entry -> entry.getName().equals("tracked.txt")).findFirst().orElseThrow();
		assertEquals("initial\n", new String(historical.openInputStream().readAllBytes()));
		plugin.unload();
	}

	@Test
	void copiesHistoricalBlobIntoOppositeLocalPanel() throws Exception {
		Path root = repository(temp.resolve("repo-copy"));
		Path destination = Files.createDirectory(temp.resolve("destination"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());
		String head;
		try (Git git = Git.open(root.toFile())) { head = git.getRepository().resolve("HEAD").name(); }
		var tree = new GitRepositoryService().tree(root, head, "");
		var tracked = tree.stream().filter(entry -> entry.name().equals("tracked.txt")).findFirst().orElseThrow();
		GitResource source = new GitResource(new GitNode(GitNode.Kind.REVISION_BLOB, root.toString(), head,
				tracked.path(), tracked.objectId()), null, tracked.name(), false, null, tracked.size());
		RecordingCallback callback = new RecordingCallback();

		plugin.act(new DestinationPanel(destination), "filepanel.copy", List.of(source), source, new HashMap<>(), callback);

		assertTrue(callback.finished.await(5, TimeUnit.SECONDS));
		assertEquals("initial\n", Files.readString(destination.resolve("tracked.txt")));
		plugin.unload();
	}

	@Test
	void copiesLocalWorktreeFileIntoOppositeLocalPanel() throws Exception {
		Path root = repository(temp.resolve("repo-copy-local"));
		Path destination = Files.createDirectory(temp.resolve("destination-local"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());

		GitResource source = new GitResource(
				new GitNode(GitNode.Kind.WORKTREE_FILE, root.toString(), null, "tracked.txt", null),
				null, "tracked.txt", false, root.resolve("tracked.txt"), 8);
		RecordingCallback callback = new RecordingCallback();

		plugin.act(new DestinationPanel(destination), "filepanel.copy", List.of(source), source, new HashMap<>(), callback);

		assertTrue(callback.finished.await(5, TimeUnit.SECONDS));
		assertEquals("initial\n", Files.readString(destination.resolve("tracked.txt")));
		plugin.unload();
	}

	@Test
	void exposesQuickViewForVirtualCommitEntries() throws Exception {
		Path root = repository(temp.resolve("repo-quick-view"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());
		String head;
		try (Git git = Git.open(root.toFile())) { head = git.getRepository().resolve("HEAD").name(); }

		GitResource commit = new GitResource(
				new GitNode(GitNode.Kind.COMMIT, root.toString(), head, null, head.substring(0, 8)),
				new GitNode(GitNode.Kind.COMMITS, root.toString(), "HEAD", null, null),
				head.substring(0, 8), true, null, 0);

		assertTrue(plugin.contextMenuItems(commit, List.of(commit)).stream()
				.anyMatch(item -> "filepanel.view".equals(item.getActionType())));

		// Verifying that opening InputStream on virtual commit entry returns commit details
		try (var input = commit.openInputStream()) {
			String details = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			assertTrue(details.contains("initial"));
			assertTrue(details.contains("Nuclr Test"));
		}

		plugin.unload();
	}

	@Test
	void contextMenuEnablesDiscardWhenSelectionContainsWorkingChangesAndUntracked() throws Exception {
		Path root = repository(temp.resolve("repo-discard-menu"));
		GitFilePanelPlugin plugin = new GitFilePanelPlugin();
		plugin.preinit(new FakeContext());

		GitResource modified = new GitResource(
				new GitNode(GitNode.Kind.WORKTREE_FILE, root.toString(), null, "tracked.txt", null),
				null, "tracked.txt", false, root.resolve("tracked.txt"), 10).marker(" M");

		GitResource untracked = new GitResource(
				new GitNode(GitNode.Kind.WORKTREE_FILE, root.toString(), null, "untracked.txt", null),
				null, "untracked.txt", false, root.resolve("untracked.txt"), 10).marker("??");

		var items = plugin.contextMenuItems(modified, List.of(modified, untracked));
		var discardItem = items.stream().filter(item -> "git.discard".equals(item.getActionType())).findFirst().orElseThrow();
		assertTrue(discardItem.isEnabled(), "Discard should be enabled when selection includes working changes alongside untracked files");

		plugin.unload();
	}

	@Test
	void historicalFilesAreLeftToContentViewersExceptSubmodules() {
		GitQuickViewPlugin quickView = new GitQuickViewPlugin();
		GitResource blob = new GitResource(new GitNode(GitNode.Kind.REVISION_BLOB, "repo", "HEAD", "src/App.java",
				"0123456789012345678901234567890123456789"), null, "App.java", false, null, 10);
		GitResource submodule = new GitResource(new GitNode(GitNode.Kind.REVISION_BLOB, "repo", "HEAD", "lib",
				"0123456789012345678901234567890123456789"), null, "lib", false, null, 0).marker("submodule");
		GitResource modified = new GitResource(new GitNode(GitNode.Kind.STATUS_FILE, "repo", null, "App.java", null),
				null, "App.java", false, null, 10).marker(" M");
		assertFalse(quickView.supports(blob));
		assertTrue(quickView.supports(submodule));
		assertTrue(quickView.supports(modified));
	}

	@Test
	void remoteMirrorIsBrowsableButOffersOnlyFetch() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		Path origin = repository(temp.resolve("origin-repo"));
		Path cache = temp.resolve("mirrors");
		Path mirror = new RemoteMirrors(cache, new NativeGitRunner()).open(origin.toString(), () -> false).toRealPath();
		GitFilePanelPlugin plugin = new GitFilePanelPlugin(cache);
		plugin.preinit(new FakeContext());

		GitResource repository = new GitResource(GitNode.repository(mirror.toString()), GitNode.root(),
				"origin-repo", true, null, 0);
		var categories = plugin.openResource(repository, null);
		assertFalse(categories.getEntries().stream().anyMatch(entry -> entry.getName().equals("Working Tree")));
		assertTrue(categories.getEntries().stream().anyMatch(entry -> entry.getName().equals("Branches")));
		assertTrue(plugin.getCurrentLocationDisplayText().startsWith("origin-repo — "));

		var actions = plugin.contextMenuItems(repository, List.of(repository)).stream()
				.map(item -> item.getActionType()).toList();
		assertTrue(actions.contains("git.fetch"));
		assertTrue(actions.contains("git.recent.remove"));
		assertFalse(actions.contains("git.commit"));
		assertFalse(actions.contains("git.push"));
		assertFalse(actions.contains("git.pull"));
		plugin.unload();
	}

	private static Path repository(Path root) throws Exception {
		Files.createDirectories(root);
		try (Git git = Git.init().setDirectory(root.toFile()).call()) {
			Files.writeString(root.resolve("tracked.txt"), "initial\n");
			git.add().addFilepattern(".").call();
			PersonIdent person = new PersonIdent("Nuclr Test", "test@nuclr.dev");
			git.commit().setMessage("initial").setAuthor(person).setCommitter(person).call();
		}
		return root.toRealPath();
	}

	private static final class TestLocalResource extends dev.nuclr.platform.plugin.NuclrResource {
		private static final long serialVersionUID = 1L;
		TestLocalResource(Path path) { super(path); setUuid(path.toString()); setName(path.getFileName().toString()); setFolder(true); }
	}

	private static final class DestinationPanel implements FilePanelNuclrPlugin {
		private final NuclrResource folder;
		DestinationPanel(Path path) { folder = new TestLocalResource(path); }
		@Override public NuclrResourceData openResource(NuclrResource resource, java.util.concurrent.atomic.AtomicBoolean cancelled) { return null; }
		@Override public String getCurrentLocationDisplayText() { return folder.getFullPath(); }
		@Override public String getSelectionSummaryText(List<NuclrResource> selectedResources) { return ""; }
		@Override public boolean onFocusGained() { return false; }
		@Override public void onFocusLost() { }
		@Override public boolean isFocused() { return false; }
		@Override public void preinit(NuclrPluginContext context) { }
		@Override public NuclrPluginContext getContext() { return null; }
		@Override public void init() { }
		@Override public String uuid() { return "destination"; }
		@Override public void unload() { }
		@Override public void closeResource() { }
		@Override public NuclrResource getCurrentResource() { return folder; }
		@Override public boolean supports(NuclrResource resource) { return false; }
	}

	private static final class RecordingCallback implements NuclrPluginCallback {
		private final CountDownLatch finished = new CountDownLatch(1);
		@Override public void onStart(String description) { }
		@Override public void onProgress(long current, long total) { }
		@Override public void onComplete() { finished.countDown(); }
		@Override public void onError(String description, Exception e) { finished.countDown(); }
		@Override public boolean isCancelled() { return false; }
	}

	private static final class FakeContext implements NuclrPluginContext {
		private final FakeSettings settings = new FakeSettings();
		private final NuclrEventBus events = new NuclrEventBus() {
			@Override public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) { }
			@Override public void emit(Object source, String type, Map<String, Object> event) { }
			@Override public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) { }
			@Override public void emit(String type, NuclrPluginCallback callback) { }
			@Override public void emit(String type) { }
			@Override public void subscribe(NuclrEventListener listener) { }
			@Override public void unsubscribe(NuclrEventListener listener) { }
		};
		@Override public NuclrEventBus getEventBus() { return events; }
		@Override public dev.nuclr.platform.NuclrCredentialStore getCredentialStore() { throw new UnsupportedOperationException(); }
		@Override public NuclrThemeScheme getTheme() { return new NuclrThemeScheme("Test", Map.of()); }
		@Override public NuclrSettings getSettings() { return settings; }
		@Override public Locale getLocale() { return Locale.ROOT; }
	}

	private static final class FakeSettings implements NuclrSettings {
		private final Map<String, Object> values = new HashMap<>();
		@Override public void set(String namespace, String key, Object value) { values.put(namespace + ":" + key, value); }
		@SuppressWarnings("unchecked") @Override public <T> T get(String namespace, String key) { return (T) values.get(namespace + ":" + key); }
		@SuppressWarnings("unchecked") @Override public <T> T getOrDefault(String namespace, String key, T fallback) {
			return (T) values.getOrDefault(namespace + ":" + key, fallback);
		}
		@Override public boolean isDeveloperModeOn() { return false; }
	}
}
