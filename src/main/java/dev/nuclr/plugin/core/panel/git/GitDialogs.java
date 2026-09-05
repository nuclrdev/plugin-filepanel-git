/*
 * Copyright 2026 Sergio, Nuclr (https://nuclr.dev)
 * Licensed under the Apache License, Version 2.0.
 */
package dev.nuclr.plugin.core.panel.git;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** EDT-safe dialogs owned by the Git plugin. */
final class GitDialogs {

	private GitDialogs() {}

	static Path chooseRepository(Path initial) {
		if (GraphicsEnvironment.isHeadless()) return null;
		return onEdt(() -> {
			JFileChooser chooser = new JFileChooser(initial != null ? initial.toFile() : null);
			chooser.setDialogTitle("Open Git Repository");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setAcceptAllFileFilterUsed(false);
			return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
					? chooser.getSelectedFile().toPath() : null;
		});
	}

	static String commitMessage() {
		if (GraphicsEnvironment.isHeadless()) return null;
		return onEdt(() -> {
			JTextArea text = new JTextArea(8, 64);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			int choice = JOptionPane.showConfirmDialog(null, new JScrollPane(text), "Commit staged changes",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			String value = text.getText().strip();
			return choice == JOptionPane.OK_OPTION && !value.isBlank() ? value : null;
		});
	}

	static String input(String title, String message, String initial) {
		if (GraphicsEnvironment.isHeadless()) return null;
		return onEdt(() -> {
			Object result = JOptionPane.showInputDialog(null, message, title, JOptionPane.PLAIN_MESSAGE,
					null, null, initial == null ? "" : initial);
			if (result == null) return null;
			String value = result.toString().strip();
			return value.isBlank() ? null : value;
		});
	}

	static boolean confirm(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) return false;
		return onEdt(() -> JOptionPane.showConfirmDialog(null, message, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION);
	}

	static void showText(String title, String content) {
		if (GraphicsEnvironment.isHeadless()) return;
		var bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		int width = Math.min(1000, bounds.width * 3 / 4);
		int height = Math.min(700, bounds.height * 3 / 4);
		SwingUtilities.invokeLater(() -> {
			JTextArea text = new JTextArea(content == null || content.isBlank() ? "No output." : content);
			text.setEditable(false);
			text.setCaretPosition(0);
			text.setFont(new Font(Font.MONOSPACED, Font.PLAIN,
					UIManager.getFont("TextArea.font") != null ? UIManager.getFont("TextArea.font").getSize() : 12));
			JScrollPane scroll = new JScrollPane(text);
			scroll.setPreferredSize(new Dimension(width, height));
			JOptionPane.showMessageDialog(null, scroll, title, JOptionPane.PLAIN_MESSAGE);
		});
	}

	static void error(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) return;
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
				message == null || message.isBlank() ? "The Git operation failed." : message,
				title, JOptionPane.ERROR_MESSAGE));
	}

	private static <T> T onEdt(Callable<T> task) {
		if (SwingUtilities.isEventDispatchThread()) {
			try {
				return task.call();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		Object[] result = new Object[1];
		Throwable[] failure = new Throwable[1];
		try {
			SwingUtilities.invokeAndWait(() -> {
				try {
					result[0] = task.call();
				} catch (Throwable e) {
					failure[0] = e;
				}
			});
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e.getCause());
		}
		if (failure[0] != null) throw new IllegalStateException(failure[0]);
		@SuppressWarnings("unchecked") T value = (T) result[0];
		return value;
	}
}
