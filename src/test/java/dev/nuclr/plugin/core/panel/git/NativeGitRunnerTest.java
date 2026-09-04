package dev.nuclr.plugin.core.panel.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeGitRunnerTest {

	@TempDir Path temp;

	@Test
	void passesPathsWithSpacesAsLiteralArguments() throws Exception {
		NativeGitRunner runner = new NativeGitRunner();
		Assumptions.assumeTrue(runner.available(), "native git is not installed");
		Path root = temp.resolve("repo with spaces");
		Files.createDirectories(root);
		try (Git ignored = Git.init().setDirectory(root.toFile()).call()) {
			Files.writeString(root.resolve("new file.txt"), "content\n");
			runner.requireSuccess("Stage", root, () -> false, "add", "--", "new file.txt");
			assertEquals("A ", new GitRepositoryService().status(root).entry("new file.txt").marker());
		}
	}
}
