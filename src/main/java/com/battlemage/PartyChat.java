package com.battlemage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The party chat's log for this session: plain lines with the time they arrived and who sent them.
 * Kept in memory only, never saved. Safe from any thread; listeners are told on whichever thread
 * the line arrived on, and must hop to Swing themselves.
 */
final class PartyChat
{
	/** The longest line that is sent or accepted. */
	static final int MAX_LENGTH = 200;
	/** Lines kept; older ones drop off the top. */
	private static final int KEEP = 300;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	/** One line of chat. */
	static final class Line
	{
		final String time;
		final String name;
		final String text;
		/** Joins, leaves and the like, shown without a name. */
		final boolean notice;

		Line(String name, String text, boolean notice)
		{
			this.time = LocalTime.now().format(TIME);
			this.name = name;
			this.text = text;
			this.notice = notice;
		}

		@Override
		public String toString()
		{
			return "[" + time + "] " + (notice ? "* " + text : name + ": " + text);
		}
	}

	private final Deque<Line> lines = new ArrayDeque<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile int unread;

	/** The most combining marks kept on one letter: enough for any real accent, not for "Zalgo" towers. */
	private static final int MAX_MARKS = 2;

	/**
	 * Makes text safe to show and send, whatever a tampered client puts in it: one line; no control or
	 * invisible formatting characters (so no right-to-left tricks, zero-width padding or line
	 * separators); no towers of stacked accents; runs of spaces squeezed to one (so a line can't be
	 * padded out to wrap and fake a second line under someone else's name); at most MAX_LENGTH.
	 */
	static String clean(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		final StringBuilder b = new StringBuilder();
		int marks = 0;
		boolean space = true;   // drops leading spaces too
		for (int i = 0; i < raw.length() && b.length() < MAX_LENGTH; )
		{
			final int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			final int type = Character.getType(cp);
			if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp))
			{
				if (!space)
				{
					b.append(' ');
					space = true;
				}
				marks = 0;
				continue;
			}
			switch (type)
			{
				case Character.FORMAT:          // bidi overrides, zero-width joiners and spaces
				case Character.SURROGATE:       // a broken half of a pair
				case Character.PRIVATE_USE:
				case Character.UNASSIGNED:
					continue;
				case Character.NON_SPACING_MARK:
				case Character.ENCLOSING_MARK:
				case Character.COMBINING_SPACING_MARK:
					if (space || ++marks > MAX_MARKS)
					{
						continue;
					}
					break;
				default:
					marks = 0;
			}
			if (b.length() + Character.charCount(cp) > MAX_LENGTH)
			{
				break;
			}
			b.appendCodePoint(cp);
			space = false;
		}
		return b.toString().trim();
	}

	/**
	 * A sender's name as it may be shown: letters, numbers, spaces, hyphens and underscores only, as
	 * character names are, at most 12 long; null if nothing is left.
	 */
	static String cleanName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		final StringBuilder b = new StringBuilder();
		for (int i = 0; i < raw.length() && b.length() < 12; i++)
		{
			final char c = raw.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')
			{
				b.append(c);
			}
			else if (c == ' ' || c == '\u00A0')
			{
				b.append(' ');
			}
		}
		final String s = b.toString().trim();
		return s.isEmpty() ? null : s;
	}

	void add(String name, String text, boolean notice)
	{
		synchronized (lines)
		{
			lines.addLast(new Line(name == null ? "?" : name, text, notice));
			while (lines.size() > KEEP)
			{
				lines.removeFirst();
			}
		}
		if (!notice)
		{
			unread++;
		}
		for (Runnable r : listeners)
		{
			r.run();
		}
	}

	List<Line> lines()
	{
		synchronized (lines)
		{
			return new ArrayList<>(lines);
		}
	}

	int unread()
	{
		return unread;
	}

	void markRead()
	{
		if (unread == 0)
		{
			return;
		}
		unread = 0;
		for (Runnable r : listeners)
		{
			r.run();
		}
	}

	void clear()
	{
		synchronized (lines)
		{
			lines.clear();
		}
		unread = 0;
		for (Runnable r : listeners)
		{
			r.run();
		}
	}

	void listen(Runnable r)
	{
		listeners.add(r);
	}
}
