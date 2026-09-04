/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shell-free, cancellable adapter for compatibility-sensitive Git operations. */
final class NativeGitRunner {

	private static final int MAX_OUTPUT = 2 * 1024 * 1024;

	record Result(int exitCode, String output) {
		boolean succeeded() { return exitCode == 0; }
	}

	static final class GitCommandException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int exitCode;

		GitCommandException(String operation, Result result) {
			super(operation + " failed" + (result.output().isBlank() ? "." : ":\n" + result.output().strip()));
			this.exitCode = result.exitCode();
		}

		int exitCode() { return exitCode; }
	}

	boolean available() {
		try {
			return run(null, () -> false, "--version").succeeded();
		} catch (IOException e) {
			return false;
		}
	}

	Result run(Path repository, BooleanSupplier cancelled, String... arguments) throws IOException {
		var command = new ArrayList<String>();
		command.add("git");
		command.add("--no-pager");
		if (repository != null) {
			command.add("-C");
			command.add(repository.toAbsolutePath().normalize().toString());
		}
		command.add("-c");
		command.add("color.ui=false");
		command.addAll(List.of(arguments));

		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().put("GIT_PAGER", "cat");
		builder.environment().put("GIT_EDITOR", "true");
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		Process process = builder.start();
		var output = new ByteArrayOutputStream();
		Thread reader = Thread.ofVirtual().name("git-output").start(() -> read(process.getInputStream(), output));
		try {
			while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
				if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
					terminate(process);
					throw new IOException("Git operation cancelled.");
				}
			}
			reader.join();
			return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			terminate(process);
			throw new IOException("Git operation cancelled.", e);
		}
	}

	void requireSuccess(String operation, Path repository, BooleanSupplier cancelled, String... arguments)
			throws IOException {
		Result result = run(repository, cancelled, arguments);
		if (!result.succeeded()) throw new GitCommandException(operation, result);
	}

	private static void read(InputStream input, ByteArrayOutputStream output) {
		byte[] buffer = new byte[8192];
		try (input) {
			for (int count; (count = input.read(buffer)) >= 0;) {
				if (count == 0 || output.size() >= MAX_OUTPUT) continue;
				output.write(buffer, 0, Math.min(count, MAX_OUTPUT - output.size()));
			}
		} catch (IOException ignored) {
			// Process termination closes the stream; the caller reports cancellation.
		}
	}

	private static void terminate(Process process) {
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		try {
			if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}
}
