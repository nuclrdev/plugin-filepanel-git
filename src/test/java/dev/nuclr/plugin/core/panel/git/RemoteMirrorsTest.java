package dev.nuclr.plugin.core.panel.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteMirrorsTest {

	@TempDir Path temp;

	@AfterEach
	void releasePackFiles() {
		// JGit keeps packs open after use; Windows cannot delete the temp folder otherwise.
		GitRepositoryService.releasePackFiles();
	}

	@Test
	void onDemandRepositoryDownloadsOnlyWhatIsBrowsed() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		String url = origin(temp.resolve("origin"), 12);
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		GitRepositoryService service = new GitRepositoryService();

		Path root = mirrors.open(url, () -> false);
		assertEquals(mirrors.onDemandPathFor(url), root);
		var info = service.info(root);
		assertTrue(info.bare());
		assertTrue(info.onDemand());
		assertEquals(url, info.origin());
		assertTrue(service.tags(root).stream().anyMatch(ref -> ref.name().equals("v1")));
		assertTrue(service.branches(root, false).stream().anyMatch(ref -> ref.name().equals("feature")));

		// Branch tips only: no trees, no files yet.
		String head;
		ObjectId readme;
		try (Git origin = Git.open(temp.resolve("origin").toFile())) {
			head = origin.getRepository().resolve("HEAD").name();
			readme = origin.getRepository().resolve("HEAD:README.md");
		}
		assertFalse(has(root, readme));

		var entries = service.tree(root, head, "");
		var file = entries.stream().filter(entry -> entry.name().equals("README.md")).findFirst().orElseThrow();
		assertEquals(-1L, file.size(), "sizes of files not downloaded are unknown");
		assertTrue(service.tree(root, head, "src").stream().anyMatch(entry -> entry.name().equals("App.java")));
		assertFalse(has(root, readme), "listing a folder must not download its files");
		try (InputStream input = service.openBlob(root, file.objectId())) {
			assertEquals("commit 12\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertTrue(has(root, readme));
		assertTrue(service.commitDetails(root, head).contains("+commit 12"));
		assertThrows(IOException.class, () -> service.history(root, "README.md", 10));
	}

	@Test
	void historyIsFetchedPageByPageUntilTheFirstCommit() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		String url = origin(temp.resolve("origin"), 12);
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		Path root = mirrors.open(url, () -> false);
		GitRepositoryService service = new GitRepositoryService();

		assertTrue(service.loadedCommits(root, 100).isEmpty());
		int previous = 0;
		int pages = 0;
		while (service.loadNextPage(root, 4, () -> false)) {
			int loaded = service.loadedCommits(root, 100).size();
			assertTrue(loaded > previous, "every page adds commits");
			previous = loaded;
			assertTrue(++pages < 20, "paging must reach the first commit");
		}
		assertTrue(pages >= 3, "12 commits at depth 4 take several pages, got " + pages);
		assertEquals(12, previous);
		assertNull(service.nextPageStart(root));
		// Refreshing anchors the pages at the current HEAD again.
		service.refreshOnDemand(root, () -> false);
		assertTrue(service.loadedCommits(root, 100).isEmpty());
		assertNotNull(service.nextPageStart(root));
		// Removing must work right after browsing, with JGit's pack cache still warm.
		mirrors.remove(root);
		assertFalse(Files.exists(root));
	}

	@Test
	void fullHistoryReplacesTheOnDemandRepository() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		String url = origin(temp.resolve("origin"), 3);
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		GitRepositoryService service = new GitRepositoryService();
		Path onDemand = mirrors.open(url, () -> false);

		Path mirror = mirrors.downloadFullHistory(url, () -> false);
		assertEquals(mirrors.pathFor(url), mirror);
		assertFalse(Files.exists(onDemand));
		assertFalse(service.info(mirror).onDemand());
		assertTrue(service.history(mirror, "README.md", 10).contains("commit 3"));
		assertEquals(mirror, mirrors.open(url, () -> false), "an existing full mirror is reused");

		mirrors.remove(mirror);
		assertFalse(Files.exists(mirror));
	}

	@Test
	void failedOpenLeavesNothingBehind() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		String missing = temp.resolve("does-not-exist").toUri().toString();
		assertThrows(IOException.class, () -> mirrors.open(missing, () -> false));
		Path onDemand = mirrors.onDemandPathFor(missing);
		assertFalse(Files.exists(onDemand));
		assertFalse(Files.exists(onDemand.resolveSibling(onDemand.getFileName() + ".partial")));
	}

	@Test
	void refusesOptionLikeUrlsAndForeignPaths() {
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		assertThrows(IOException.class, () -> RemoteMirrors.validate("--upload-pack=evil"));
		assertThrows(IOException.class, () -> RemoteMirrors.validate("   "));
		assertThrows(IOException.class, () -> mirrors.remove(temp.resolve("elsewhere")));
		assertThrows(IOException.class, () -> mirrors.remove(temp.resolve("cache")));
	}

	@Test
	void namesMirrorsAfterTheirUrl() {
		assertEquals("plugin-filepanel-git", RemoteMirrors.displayName("https://github.com/nuclrdev/plugin-filepanel-git.git"));
		assertEquals("repo", RemoteMirrors.displayName("git@github.com:org/repo.git"));
		assertEquals("repo", RemoteMirrors.displayName("https://gitlab.example.com/group/repo/"));
		RemoteMirrors mirrors = new RemoteMirrors(temp, new NativeGitRunner());
		assertEquals(mirrors.pathFor("https://a/x.git"), mirrors.pathFor("https://a/x.git"));
		assertFalse(mirrors.pathFor("https://a/x.git").equals(mirrors.pathFor("https://b/x.git")));
		assertEquals("refs/heads/main", LazyRemote.defaultBranch("ref: refs/heads/main\tHEAD\n0123\tHEAD\n"));
	}

	/**
	 * A linear history of {@code commits} commits plus a feature branch and a
	 * tag, served over file:// with the filters GitHub allows.
	 */
	static String origin(Path root, int commits) throws Exception {
		Files.createDirectories(root.resolve("src"));
		try (Git git = Git.init().setDirectory(root.toFile()).call()) {
			PersonIdent person = new PersonIdent("Nuclr Test", "test@nuclr.dev");
			Files.writeString(root.resolve("src/App.java"), "class App {}\n");
			for (int i = 1; i <= commits; i++) {
				Files.writeString(root.resolve("README.md"), "commit " + i + "\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("commit " + i).setAuthor(person).setCommitter(person).call();
				if (i == 1) git.tag().setName("v1").call();
			}
			git.branchCreate().setName("feature").call();
			var config = git.getRepository().getConfig();
			config.setBoolean("uploadpack", null, "allowFilter", true);
			config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
			config.save();
		}
		return root.toUri().toString();
	}

	private static boolean has(Path root, ObjectId id) throws IOException {
		try (Repository repository = new FileRepositoryBuilder().setGitDir(root.toFile()).setMustExist(true).build()) {
			return repository.getObjectDatabase().has(id);
		}
	}
}
