package com.battlemage;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * The bundled rulebook: one HTML page holding every rule, item, special attack and effect the
 * plugin enforces, opened in the player's own browser.
 *
 * <p>It exists because the side panel is the wrong shape for this. The panel is 225 pixels wide and
 * the answer to "can I wear this?" is eight hundred item names across four factions; it used to be
 * rendered there behind collapsing buttons, a dozen names at a time, which meant anyone who wanted
 * the whole picture had to open the jar and read the codex JSON. The page states all of it at once,
 * searchable, with every faction's verdict beside each item.
 *
 * <p>The file ships inside the jar, so it is copied to a temporary file before it can be handed to a
 * browser - a {@code jar:} URL is not something a browser will open. The copy is written once per
 * client session and reused, and marked delete-on-exit so nothing is left behind.
 *
 * <p><b>Not</b> through RuneLite's {@code LinkBrowser}: that one rejects every scheme but http and
 * https with "Unsupported scheme file", so it cannot open a local page at all. The JDK's
 * {@link Desktop} can. No external process is ever started. If it is unavailable the path goes to the
 * clipboard and the player is told, because a help button that does nothing and logs a line is worse
 * than no button.
 */
@Slf4j
final class Rulebook
{
	private static final String RESOURCE = "/battlemage-rules.html";

	private static Path cached;

	private Rulebook()
	{
	}

	/**
	 * Open the rulebook, extracting it first if this is the first time this session.
	 *
	 * <p>The launch runs on its own daemon thread. {@link Desktop#browse} blocks until the browser
	 * has been handed the URL, which on a cold start can be seconds, and this is called from a Swing
	 * button - doing it inline freezes the whole client while a browser boots.
	 */
	static synchronized void open()
	{
		Path file;
		try
		{
			file = extract();
		}
		catch (Exception | LinkageError e)
		{
			log.warn("[BATTLE-MAGE] could not unpack the rulebook", e);
			tell("The rulebook could not be unpacked from the plugin.", null);
			return;
		}
		if (file == null)
		{
			tell("The rulebook is missing from the plugin.", null);
			return;
		}
		final Path f = file;
		Thread t = new Thread(() -> launch(f), "battle-mage-rulebook");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Hand the file to the system browser through the JDK's desktop integration: {@link Desktop#browse}
	 * first, then {@link Desktop#open} (the file association), which is sometimes present where browse
	 * is not. If neither is available the path goes to the clipboard instead.
	 */
	private static void launch(Path file)
	{
		if (desktop(file))
		{
			return;
		}
		log.warn("[BATTLE-MAGE] no way to open {} on this machine", file);
		tell("The rulebook could not be opened automatically. Its location has been copied to your "
			+ "clipboard - paste it into your browser.", file);
	}

	/** The JDK's desktop integration: browse first, then the plain file association. */
	private static boolean desktop(Path file)
	{
		try
		{
			if (!Desktop.isDesktopSupported())
			{
				return false;
			}
			Desktop d = Desktop.getDesktop();
			if (d.isSupported(Desktop.Action.BROWSE))
			{
				d.browse(file.toUri());
				return true;
			}
			if (d.isSupported(Desktop.Action.OPEN))
			{
				d.open(file.toFile());
				return true;
			}
		}
		catch (Exception | LinkageError e)
		{
			log.debug("[BATTLE-MAGE] Desktop could not open the rulebook", e);
		}
		return false;
	}

	/**
	 * Say so, rather than failing into the log.
	 *
	 * <p>When a path is given it goes to the clipboard first, so the message is an instruction the
	 * player can actually follow. The dialog itself is pushed onto the EDT: this is reached from the
	 * launch thread, and Swing from anywhere else is its own class of bug.
	 */
	private static void tell(String message, Path file)
	{
		if (file != null)
		{
			try
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(file.toAbsolutePath().toString()), null);
			}
			catch (Exception | LinkageError e)
			{
				log.debug("[BATTLE-MAGE] could not reach the clipboard", e);
			}
		}
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				JOptionPane.showMessageDialog(null,
					"<html><body style='width:300px'>" + message + "</body></html>",
					"Battle-Mage Mode", JOptionPane.WARNING_MESSAGE);
			}
			catch (Exception | LinkageError e)
			{
				log.debug("[BATTLE-MAGE] could not show the rulebook notice", e);
			}
		});
	}

	/** Copies the page out of the jar, or returns the copy already made. */
	private static Path extract() throws IOException
	{
		if (cached != null && Files.isReadable(cached))
		{
			return cached;
		}
		try (InputStream in = Rulebook.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("[BATTLE-MAGE] the rulebook is missing from the jar: {}", RESOURCE);
				return null;
			}
			Path file = Files.createTempFile("battle-mage-rules-", ".html");
			Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
			file.toFile().deleteOnExit();
			cached = file;
			return file;
		}
	}
}
