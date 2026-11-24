package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * "___ paid their respects": rises from over your head and fades when a party member pays their
 * respects at your death signpost. Several stack, newest lowest.
 */
@Singleton
class RespectsOverlay extends Overlay
{
	private static final long LIFE_MS = 3500L;
	private static final int RISE = 50;
	private static final int MAX = 10;
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
	private static final Color GOLD = new Color(240, 214, 140);

	private final Client client;
	private final List<long[]> times = new ArrayList<>();
	private final List<String> texts = new ArrayList<>();

	@Inject
	RespectsOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** Someone paid their respects. Any thread. */
	synchronized void add(String who)
	{
		texts.add(who + " paid their respects");
		times.add(new long[]{System.currentTimeMillis()});
		while (texts.size() > MAX)
		{
			texts.remove(0);
			times.remove(0);
		}
	}

	@Override
	public synchronized Dimension render(Graphics2D g)
	{
		final long now = System.currentTimeMillis();
		for (int i = texts.size() - 1; i >= 0; i--)
		{
			if (now - times.get(i)[0] >= LIFE_MS)
			{
				texts.remove(i);
				times.remove(i);
			}
		}
		final Player me = client.getLocalPlayer();
		if (texts.isEmpty() || me == null)
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(FONT);
		final FontMetrics fm = g.getFontMetrics();
		final int n = texts.size();
		for (int i = 0; i < n; i++)
		{
			final String text = texts.get(i);
			final double age = (now - times.get(i)[0]) / (double) LIFE_MS;
			final Point at = me.getCanvasTextLocation(g, text, me.getLogicalHeight() + 40);
			if (at == null)
			{
				continue;
			}
			final int slot = n - 1 - i;
			final int y = at.getY() - (int) Math.round(RISE * age) - slot * (fm.getHeight() + 2);
			final double in = Math.min(1.0, age / 0.1);
			final int a = (int) Math.round(255 * in * (1.0 - age * age));
			final Shape glyphs = FONT.createGlyphVector(g.getFontRenderContext(), text).getOutline(at.getX(), y);
			g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(0, 0, 0, a * 9 / 10));
			g.draw(glyphs);
			g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), a));
			g.fill(glyphs);
		}
		return null;
	}
}
