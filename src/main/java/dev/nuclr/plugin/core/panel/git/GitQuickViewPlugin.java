/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;

/** Theme-aware quick viewer for Git status entries, commits, refs and historical blobs. */
public final class GitQuickViewPlugin implements QuickViewNuclrPlugin {

	private static final int MAX_BLOB_BYTES = 2 * 1024 * 1024;
	private final GitRepositoryService repositories = new GitRepositoryService();
	private final JPanel panel = new JPanel(new BorderLayout());
	private final JLabel title = new JLabel("Git");
	private final JTextArea text = new JTextArea();
	private volatile NuclrPluginContext context;
	private volatile NuclrResource currentResource;
	private volatile AtomicBoolean currentCancelled;
	private volatile long generation;

	public GitQuickViewPlugin() {
		text.setEditable(false);
		text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		text.setTabSize(4);
		text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		title.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		panel.add(title, BorderLayout.NORTH);
		panel.add(new JScrollPane(text), BorderLayout.CENTER);
	}

	@Override public JComponent panel() { return panel; }

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		updateTheme(context != null ? context.getTheme() : null);
	}

	@Override public void init() { }

	@Override
	public boolean supports(NuclrResource resource) {
		if (!(resource instanceof GitResource git)) return false;
		return switch (git.node().kind()) {
			case WORKTREE_FILE -> !git.getMetadata("Git", "").isBlank();
			case STATUS_FILE, REVISION_BLOB, COMMIT, REF, STASH -> true;
			default -> false;
		};
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (!(resource instanceof GitResource git) || !supports(resource)) return false;
		AtomicBoolean previous = currentCancelled;
		if (previous != null) previous.set(true);
		currentCancelled = cancelled;
		currentResource = resource;
		long request = ++generation;
		SwingUtilities.invokeLater(() -> {
			title.setText(resource.getName());
			text.setText("Loading Git information…");
		});
		Thread.ofVirtual().name("git-quick-view").start(() -> {
			String content;
			try {
				content = render(git, cancelled);
			} catch (Exception e) {
				content = "Unable to load Git information.\n\n" + e.getMessage();
			}
			String rendered = content == null || content.isBlank() ? "No changes." : content;
			SwingUtilities.invokeLater(() -> {
				if (request == generation && !isCancelled(cancelled)) {
					text.setText(rendered);
					text.setCaretPosition(0);
				}
			});
		});
		return true;
	}

	private String render(GitResource resource, AtomicBoolean cancelled) throws Exception {
		if (isCancelled(cancelled)) return "";
		GitNode node = resource.node();
		Path root = Path.of(node.repository());
		return switch (node.kind()) {
			case WORKTREE_FILE, STATUS_FILE -> {
				String staged = repositories.workingDiff(root, node.relativePath(), true);
				if (isCancelled(cancelled)) yield "";
				String working = repositories.workingDiff(root, node.relativePath(), false);
				StringBuilder result = new StringBuilder();
				if (!staged.isBlank()) result.append("STAGED\n======\n").append(staged);
				if (!working.isBlank()) {
					if (!result.isEmpty()) result.append("\n");
					result.append("WORKING TREE\n============\n").append(working);
				}
				yield result.toString();
			}
			case COMMIT, REF, STASH -> repositories.commitDetails(root, node.revision());
			case REVISION_BLOB -> "submodule".equals(resource.getMetadata("Git", ""))
					? "Git submodule entry\n\nCommit: " + node.value()
					: renderBlob(repositories.blob(root, node.value(), MAX_BLOB_BYTES));
			default -> "";
		};
	}

	private static String renderBlob(byte[] bytes) {
		int length = Math.min(bytes.length, MAX_BLOB_BYTES);
		for (int i = 0; i < Math.min(length, 8192); i++) {
			if (bytes[i] == 0) return "Binary file — " + GitResource.displaySize(bytes.length);
		}
		String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
		return bytes.length > MAX_BLOB_BYTES ? content + "\n\n… file truncated …\n" : content;
	}

	@Override
	public void updateTheme(NuclrThemeScheme theme) {
		Color background = theme != null ? theme.color("Panel.background", UIManager.getColor("Panel.background"))
				: UIManager.getColor("Panel.background");
		Color foreground = theme != null ? theme.color("Panel.foreground", UIManager.getColor("Panel.foreground"))
				: UIManager.getColor("Panel.foreground");
		if (background == null) background = Color.BLACK;
		if (foreground == null) foreground = Color.WHITE;
		panel.setBackground(background);
		title.setBackground(background);
		title.setForeground(foreground);
		text.setBackground(background);
		text.setForeground(foreground);
		text.setCaretColor(foreground);
	}

	@Override
	public void closeResource() {
		AtomicBoolean cancelled = currentCancelled;
		if (cancelled != null) cancelled.set(true);
		currentCancelled = null;
		currentResource = null;
		generation++;
		text.setText("");
	}

	@Override public void unload() { closeResource(); context = null; }
	@Override public NuclrPluginContext getContext() { return context; }
	@Override public NuclrResource getCurrentResource() { return currentResource; }
	@Override public String uuid() { return PLUGIN_ID; }
	@Override public boolean onFocusGained() { return false; }
	@Override public void onFocusLost() { }
	@Override public boolean isFocused() { return false; }
	@Override public String getWindowTitle() { return currentResource == null ? "Git Quick View" : "Git: " + currentResource.getName(); }

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return Thread.currentThread().isInterrupted() || cancelled != null && cancelled.get();
	}

	private static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.git.quickview";
}
