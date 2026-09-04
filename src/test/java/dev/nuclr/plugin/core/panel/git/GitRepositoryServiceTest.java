package dev.nuclr.plugin.core.panel.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryServiceTest {

	@TempDir Path temp;
	private final GitRepositoryService service = new GitRepositoryService();

	@Test
	void discoversRepositoryAndKeepsIndexAndWorktreeStatusSeparate() throws Exception {
		Path root = temp.resolve("repo");
		try (Git git = createRepository(root)) {
			Path tracked = root.resolve("tracked.txt");
			Files.writeString(tracked, "staged\n");
			git.add().addFilepattern("tracked.txt").call();
			Files.writeString(tracked, "working\n");
			Files.writeString(root.resolve("new file.txt"), "new\n");

			GitStatusSnapshot status = service.status(root);
			assertEquals("MM", status.entry("tracked.txt").marker());
			assertEquals("??", status.entry("new file.txt").marker());
			assertEquals(root.toRealPath(), service.findRepository(root.resolve("nested").resolve("missing.txt")));
			assertFalse(service.workingDiff(root, "tracked.txt", false).isBlank());
			assertFalse(service.workingDiff(root, "tracked.txt", true).isBlank());
		}
	}

	@Test
	void browsesCommitTreesAndReadsBlobWithoutCheckout() throws Exception {
		Path root = temp.resolve("repo-tree");
		try (Git git = createRepository(root)) {
			var commits = service.commits(root, "HEAD", 20);
			assertEquals(1, commits.size());
			var rootEntries = service.tree(root, commits.get(0).id(), "");
			var source = rootEntries.stream().filter(entry -> entry.name().equals("src")).findFirst().orElseThrow();
			assertTrue(source.directory());
			var children = service.tree(root, commits.get(0).id(), "src");
			var file = children.stream().filter(entry -> entry.name().equals("App.java")).findFirst().orElseThrow();
			assertFalse(file.directory());
			assertArrayEquals("class App {}\n".getBytes(StandardCharsets.UTF_8), service.blob(root, file.objectId()));
			assertTrue(service.commitDetails(root, commits.get(0).id()).contains("initial"));
			Files.writeString(root.resolve("src/App.java"), "class App { int changed; }\n");
			assertTrue(service.revisionToWorkingDiff(root, commits.get(0).id(), "src/App.java").contains("changed"));
		}
	}

	@Test
	void listsBranchesTagsHistoryAndBlame() throws Exception {
		Path root = temp.resolve("repo-refs");
		try (Git git = createRepository(root)) {
			git.branchCreate().setName("feature/test").call();
			git.tag().setName("v1.0").call();
			assertTrue(service.branches(root, false).stream().anyMatch(ref -> ref.name().equals("feature/test")));
			assertTrue(service.tags(root).stream().anyMatch(ref -> ref.name().equals("v1.0")));
			assertTrue(service.history(root, "src/App.java", 10).contains("initial"));
			assertTrue(service.blame(root, "src/App.java", 100).contains("class App"));
		}
	}

	private static Git createRepository(Path root) throws Exception {
		Files.createDirectories(root.resolve("src"));
		Git git = Git.init().setDirectory(root.toFile()).call();
		Files.writeString(root.resolve("tracked.txt"), "initial\n");
		Files.writeString(root.resolve("src/App.java"), "class App {}\n");
		git.add().addFilepattern(".").call();
		PersonIdent person = new PersonIdent("Nuclr Test", "test@nuclr.dev");
		assertNotNull(git.commit().setMessage("initial").setAuthor(person).setCommitter(person).call());
		return git;
	}
}
