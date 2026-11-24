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
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The in-game style's rising state words: as Depletion, Overload or Critical Overload starts, its name
 * floats up off your character from just over its head and fades - alongside the PKP orb's rim,
 * which glows in the state's colour for as long as it lasts. Newer words start nearest and push older
 * ones up. Size, duration and how far they rise are set in Customize appearance. (The HUD style names
 * the state on the PKP bar instead.)
 */
class FloatingStateTextOverlay extends Overlay
{
	private static final int BASE_FONT = 18;
	/** At most this many words in the air at once. */
	private static final int MAX = 6;

	private static final class Word
	{
		final String text;
		final Color color;
		final long spawnMs;

		Word(String text, Color color, long spawnMs)
		{
			this.text = text;
			this.color = color;
			this.spawnMs = spawnMs;
		}
	}

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;

	private final List<Word> words = new ArrayList<>();
	private boolean wasCritical;
	private boolean wasOverloaded;
	private boolean wasDepleted;
	private Appearance.Sample lastSample = Appearance.Sample.NONE;

	@Inject
	FloatingStateTextOverlay(Client client, BattleMagePlugin plugin, Appearance look)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final long now = System.currentTimeMillis();
		final long life = Math.max(1, look.floatTextSeconds()) * 1000L;
		noteStates(now, life);
		// the in-game style's (alongside the orb's rim): the HUD style names states on the bar instead
		if (!look.floatTextEnabled() || !look.inGameStyle())
		{
			words.clear();
			return null;
		}
		words.removeIf(w -> now - w.spawnMs >= life || now < w.spawnMs);
		if (words.isEmpty())
		{
			return null;
		}
		// they come off your character: from just over its head, rising
		final Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		final net.runelite.api.Point head = me.getCanvasTextLocation(g, "", me.getLogicalHeight() + 20);
		if (head == null)
		{
			return null;
		}
		final int cx = head.getX();
		final int top = head.getY();
		final int bottom = top;
		final boolean upwards = true;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		final int size = Math.max(8, (int) Math.round(BASE_FONT * look.floatTextScalePercent() / 100.0));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
		final FontMetrics fm = g.getFontMetrics();
		final int rise = look.floatTextRise();
		final int n = words.size();
		int i = 0;

		for (Word w : words)
		{
			final double age = (now - w.spawnMs) / (double) life;
			// a quick fade in, then a slow fade out as it rises (easing off near the top)
			final double in = Math.min(1.0, age / 0.12);
			final double alphaF = in * (1.0 - age * age);
			final int up = (int) Math.round(rise * (1 - (1 - age) * (1 - age)));
			final int slot = n - 1 - i++;         // newest nearest, older pushed further away
			final int tw = fm.stringWidth(w.text);
			// centred on where it comes from, but never hanging off the left of the screen
			final int x = Math.max(2, cx - tw / 2);
			final int step = slot * (fm.getHeight() + 2) + up;
			final int y = upwards ? top - 6 - fm.getDescent() - step : bottom + 4 + fm.getAscent() + step;
			final Shape glyphs = g.getFont().createGlyphVector(g.getFontRenderContext(), w.text).getOutline(x, y);
			final int a = (int) Math.round(255 * alphaF);
			g.setColor(new Color(0, 0, 0, Math.min(255, a * 9 / 10)));
			g.setStroke(new BasicStroke(Math.max(2.5f, size / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(glyphs);
			g.setColor(new Color(w.color.getRed(), w.color.getGreen(), w.color.getBlue(), a));
			g.fill(glyphs);
		}
		return null;
	}

	/** Spawns a word for each state that has just started (or, in the editor, the previewed one). */
	private void noteStates(long now, long life)
	{
		final Appearance.Sample sample = look.sample();
		if (sample != Appearance.Sample.NONE)
		{
			// the preview: the sampled state floats up again each time the last one is done
			final boolean due = sample != lastSample || words.isEmpty()
				|| now - words.get(words.size() - 1).spawnMs > life;
			lastSample = sample;
			if (due)
			{
				spawn(sample == Appearance.Sample.CRITICAL, sample == Appearance.Sample.OVERLOAD,
					sample == Appearance.Sample.DEPLETED, now);
			}
			return;
		}
		lastSample = Appearance.Sample.NONE;

		final boolean critical = plugin.criticalOverloadActive();
		final boolean overloaded = !critical && plugin.isOverloaded();
		final boolean depleted = plugin.isDepleted();
		spawn(critical && !wasCritical, overloaded && !wasOverloaded && !wasCritical, depleted && !wasDepleted, now);
		wasCritical = critical;
		wasOverloaded = overloaded || critical;
		wasDepleted = depleted;
	}

	private void spawn(boolean critical, boolean overloaded, boolean depleted, long now)
	{
		if (critical)
		{
			add(Appearance.CRITICAL_WORD, look.criticalColor(), now);
		}
		else if (overloaded)
		{
			add(Appearance.OVERLOADED_WORD, look.overloadColor(), now);
		}
		if (depleted)
		{
			add(Appearance.DEPLETED_WORD, look.depletedColor(), now);
		}
	}

	private void add(String text, Color color, long now)
	{
		if (text == null || text.trim().isEmpty() || !look.floatTextEnabled())
		{
			return;
		}
		words.add(new Word(text.trim(), color, now));
		while (words.size() > MAX)
		{
			words.remove(0);
		}
	}
}
