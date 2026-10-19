package dev.nuclr.plugin.core.panel.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteMirrorsTest {

	@TempDir Path temp;

	@Test
	void clonesMirrorFetchesOnReopenAndDeletes() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		Path origin = temp.resolve("origin");
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		GitRepositoryService service = new GitRepositoryService();
		try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
			commit(git, origin, "first");
			git.tag().setName("v1").call();

			Path mirror = mirrors.open(origin.toString(), () -> false);
			assertTrue(mirrors.isMirror(mirror));
			assertTrue(service.info(mirror).bare());
			assertEquals(origin.toString(), service.info(mirror).origin());
			assertTrue(service.tags(mirror).stream().anyMatch(ref -> ref.name().equals("v1")));
			assertEquals(1, service.commits(mirror, "HEAD", 10).size());
			assertFalse(Files.exists(mirror.resolveSibling(mirror.getFileName() + ".partial")));

			commit(git, origin, "second");
			assertEquals(mirror, mirrors.open(origin.toString(), () -> false));
			assertEquals(2, service.commits(mirror, "HEAD", 10).size());

			mirrors.remove(mirror);
			assertFalse(Files.exists(mirror));
		}
	}

	@Test
	void failedCloneLeavesNothingBehind() throws Exception {
		Assumptions.assumeTrue(new NativeGitRunner().available(), "native git is not installed");
		RemoteMirrors mirrors = new RemoteMirrors(temp.resolve("cache"), new NativeGitRunner());
		String missing = temp.resolve("does-not-exist").toString();
		assertThrows(IOException.class, () -> mirrors.open(missing, () -> false));
		Path mirror = mirrors.pathFor(missing);
		assertFalse(Files.exists(mirror));
		assertFalse(Files.exists(mirror.resolveSibling(mirror.getFileName() + ".partial")));
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
	}

	private static void commit(Git git, Path root, String message) throws Exception {
		Files.writeString(root.resolve("file.txt"), message + "\n");
		git.add().addFilepattern(".").call();
		PersonIdent person = new PersonIdent("Nuclr Test", "test@nuclr.dev");
		git.commit().setMessage(message).setAuthor(person).setCommitter(person).call();
	}
}
