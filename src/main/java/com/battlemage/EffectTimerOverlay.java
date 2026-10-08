package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A row of square badges for the PKP effects that are currently running, each drawn with a symbol instead of
 * a text label:
 * <ul>
 *   <li>snowflake - the PKP bar is frozen</li>
 *   <li>shield with an up chevron - overload immunity</li>
 *   <li>shield with a down chevron - depletion immunity</li>
 * </ul>
 * The countdown sits along the bottom of the badge. When an effect is running on a seconds timer AND an
 * attack count at the same time, the seconds show along the bottom and the remaining attacks move into a
 * corner badge, so neither is squeezed.
 *
 * It anchors bottom-left so it stacks with the PKP bar; drag it in overlay-edit mode to sit exactly above the
 * bar. Badge size is configurable and everything inside scales with it. Each badge uses the same colour that
 * effect paints onto the bar's border, and breathes on the same pulse as the bar frame, so a badge and its
 * border segment always read as the same effect.
 */
class EffectTimerOverlay extends Overlay
{
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	EffectTimerOverlay(BattleMagePlugin plugin, Appearance look)
	{
		this.plugin = plugin;
		this.look = look;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!look.showEffectTimers())
		{
			return null;
		}
		final List<BattleMagePlugin.ActiveEffect> fx = plugin.activeEffects();
		if (fx.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		final int size = look.effectIconSize();
		final int gap = Math.max(3, size / 8);

		// the exact pulse curve the bar frame and the state text use
		final double pulse = look.barPulse()
			? 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0))
			: 1.0;

		int x = 0;
		for (BattleMagePlugin.ActiveEffect e : fx)
		{
			Graphics2D cell = (Graphics2D) g.create();
			try
			{
				cell.translate(x, 0);
				drawBadge(cell, e, size, pulse);
			}
			finally
			{
				cell.dispose();
			}
			x += size + gap;
		}

		return new Dimension(fx.size() * size + (fx.size() - 1) * gap, size);
	}

	/** Draws one square badge into the region (0,0)-(size,size) of the supplied graphics. */
	private void drawBadge(Graphics2D g, BattleMagePlugin.ActiveEffect e, int size, double pulse)
	{
		final Color c = e.color;
		final int alpha = clamp((int) Math.round(255 * pulse));
		final float corner = size * 0.22f;
		final RoundRectangle2D box = new RoundRectangle2D.Float(1f, 1f, size - 2f, size - 2f, corner, corner);

		// dark body, then a faint tint so the whole badge carries the effect's hue
		g.setColor(new Color(0, 0, 0, 215));
		g.fill(box);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 46));
		g.fill(box);

		// pulsing border in the effect colour - same colour as this effect's segment on the bar.
		// An armed-but-not-started effect gets a dashed border and a dimmed symbol, so a freeze waiting
		// on your next attack reads differently from one that is already locking the bar.
		final float bw = Math.max(2f, size / 16f);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
		g.setStroke(e.pending
			? new BasicStroke(bw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
				new float[]{Math.max(3f, size / 7f), Math.max(3f, size / 9f)}, 0f)
			: new BasicStroke(bw));
		g.draw(box);

		final String secs = e.secondsText();
		final String atks = e.attacksText();
		// seconds take the bottom line when present; a lone attack count takes it instead
		final String bottom = !secs.isEmpty() ? secs : atks;
		// only when BOTH are running does the attack count get pushed into the corner
		final boolean cornerBadge = !secs.isEmpty() && !atks.isEmpty();
		final boolean hasBottom = !bottom.isEmpty();

		// the symbol rides higher and shrinks a little when it has to share the square with a countdown
		final double symY = size * (hasBottom ? 0.40 : 0.50);
		final double symR = size * (hasBottom ? 0.23 : 0.30);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), e.pending ? 130 : 255));
		g.setStroke(new BasicStroke(Math.max(1.8f, size / 15f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawSymbol(g, e.kind, size / 2.0, symY, symR);

		if (hasBottom)
		{
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, (int) Math.round(size * 0.27))));
			final FontMetrics fm = g.getFontMetrics();
			final int tx = (size - fm.stringWidth(bottom)) / 2;
			final int ty = size - Math.max(3, size / 12) - fm.getDescent();
			g.setColor(new Color(0, 0, 0, 235));
			g.drawString(bottom, tx + 1, ty + 1);
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), e.pending ? 160 : 255));
			g.drawString(bottom, tx, ty);
		}

		if (cornerBadge)
		{
			drawCornerCount(g, e.attacks, size, c);
		}
	}

	/** Small circular count in the badge's top-right, used when seconds and attacks run at the same time. */
	private void drawCornerCount(Graphics2D g, int count, int size, Color c)
	{
		final int d = Math.max(12, (int) Math.round(size * 0.42));
		final int bx = size - d - 1;
		final int by = 1;

		g.setColor(new Color(0, 0, 0, 240));
		g.fillOval(bx, by, d, d);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 255));
		g.setStroke(new BasicStroke(Math.max(1.5f, size / 24f)));
		g.drawOval(bx, by, d, d);

		final String t = Integer.toString(count);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, (int) Math.round(d * 0.6))));
		final FontMetrics fm = g.getFontMetrics();
		g.setColor(Color.WHITE);
		g.drawString(t, bx + (d - fm.stringWidth(t)) / 2, by + (d + fm.getAscent() - fm.getDescent()) / 2);
	}

	// ---------------------------------------------------------------- symbols

	private void drawSymbol(Graphics2D g, BattleMagePlugin.EffectKind kind, double cx, double cy, double r)
	{
		switch (kind)
		{
			case FREEZE:
				drawSnowflake(g, cx, cy, r);
				break;
			case OVERLOAD_IMMUNE:
				drawShieldChevron(g, cx, cy, r, true);
				break;
			case DEPLETION_IMMUNE:
			default:
				drawShieldChevron(g, cx, cy, r, false);
				break;
		}
	}

	/** Six-spoke snowflake with barbed tips. */
	private void drawSnowflake(Graphics2D g, double cx, double cy, double r)
	{
		for (int i = 0; i < 3; i++)
		{
			double a = Math.toRadians(i * 60 + 90);
			double dx = Math.cos(a) * r;
			double dy = Math.sin(a) * r;
			g.draw(new Line2D.Double(cx - dx, cy - dy, cx + dx, cy + dy));
		}
		final double barb = r * 0.36;
		for (int i = 0; i < 6; i++)
		{
			double a = Math.toRadians(i * 60 + 90);
			double tx = cx + Math.cos(a) * r * 0.58;
			double ty = cy + Math.sin(a) * r * 0.58;
			for (int s = -1; s <= 1; s += 2)
			{
				double ba = a + s * Math.toRadians(50);
				g.draw(new Line2D.Double(tx, ty, tx + Math.cos(ba) * barb, ty + Math.sin(ba) * barb));
			}
		}
	}

	/** Shield with a chevron inside: pointing up for overload immunity, down for depletion immunity. */
	private void drawShieldChevron(Graphics2D g, double cx, double cy, double r, boolean up)
	{
		final double w = r * 1.62;
		final double h = r * 1.92;
		final double l = cx - w / 2;
		final double rt = cx + w / 2;
		final double t = cy - h / 2;
		final double b = cy + h / 2;

		Path2D shield = new Path2D.Double();
		shield.moveTo(l, t);
		shield.lineTo(rt, t);
		shield.lineTo(rt, t + h * 0.42);
		shield.quadTo(rt, b - h * 0.08, cx, b);
		shield.quadTo(l, b - h * 0.08, l, t + h * 0.42);
		shield.closePath();
		g.draw(shield);

		final double k = r * 0.44;
		final double my = cy - h * 0.05;
		Path2D chevron = new Path2D.Double();
		if (up)
		{
			chevron.moveTo(cx - k, my + k * 0.55);
			chevron.lineTo(cx, my - k * 0.55);
			chevron.lineTo(cx + k, my + k * 0.55);
		}
		else
		{
			chevron.moveTo(cx - k, my - k * 0.55);
			chevron.lineTo(cx, my + k * 0.55);
			chevron.lineTo(cx + k, my - k * 0.55);
		}
		g.draw(chevron);
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
