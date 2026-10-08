package com.battlemage;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The renderer for the oath screen's prose: line prefixes, word wrap and measuring. Everything about
 * the rules themselves lives in the rulebook ("Rules &amp; equipment" in the side panel).
 *
 * <h2>Line prefixes</h2>
 *
 * <table>
 *   <tr><td>{@code #}</td><td>a heading, drawn in the accent colour and upper-cased</td></tr>
 *   <tr><td>{@code -}</td><td>a bullet, indented with a dot in the accent colour</td></tr>
 *   <tr><td>{@code !}</td><td>a warning, drawn in amber</td></tr>
 *   <tr><td>{@code ""}</td><td>a blank line</td></tr>
 * </table>
 *
 * <p>{@link #height} measures by exactly the rules {@link #draw} draws by, so a panel is never sized
 * against different arithmetic than its contents.
 */
final class Briefing
{
	static final int LINE = 13;

	private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
	private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);
	private static final Color INK_MUTED = new Color(146, 156, 170);
	private static final Color WARN = new Color(214, 162, 78);

	private Briefing()
	{
	}

	/** Height of a copy block at the given width. */
	static int height(Graphics2D g, String[] lines, int width)
	{
		int h = 0;
		for (String raw : lines)
		{
			if (raw.isEmpty())
			{
				h += 7;
			}
			else if (raw.startsWith("#"))
			{
				h += 17;
			}
			else
			{
				boolean bullet = raw.startsWith("-");
				String text = raw.startsWith("!") || bullet ? raw.substring(1).trim() : raw;
				FontMetrics fm = g.getFontMetrics(BODY_FONT);
				h += LINE * Math.max(1, wrap(fm, text, bullet ? width - 12 : width).size());
			}
		}
		return h;
	}

	/** Draws a copy block and returns the y below it. */
	static int draw(Graphics2D g, int x, int y, int width, String[] lines, Color accent)
	{
		for (String raw : lines)
		{
			if (raw.isEmpty())
			{
				y += 7;
				continue;
			}
			if (raw.startsWith("#"))
			{
				g.setFont(LABEL_FONT);
				g.setColor(accent);
				g.drawString(raw.substring(1).toUpperCase(), x, y + 11);
				y += 17;
				continue;
			}
			boolean bullet = raw.startsWith("-");
			boolean warn = raw.startsWith("!");
			String text = bullet || warn ? raw.substring(1).trim() : raw;

			g.setFont(BODY_FONT);
			FontMetrics fm = g.getFontMetrics();
			int tx = bullet ? x + 12 : x;
			int tw = bullet ? width - 12 : width;
			boolean first = true;
			for (String line : wrap(fm, text, tw))
			{
				if (bullet && first)
				{
					g.setColor(accent);
					g.fillOval(x + 2, y + 5, 3, 3);
				}
				g.setColor(warn ? WARN : INK_MUTED);
				g.drawString(line, tx, y + 11);
				y += LINE;
				first = false;
			}
		}
		return y;
	}

	/**
	 * Greedy word wrap. Shared so that a block measured by {@link #height} and drawn by
	 * {@link #draw} always breaks in the same places.
	 */
	static List<String> wrap(FontMetrics fm, String text, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+"))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}
}
