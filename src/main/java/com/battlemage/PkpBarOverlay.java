package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The horizontal PKP bar. A normal movable overlay - drag it so it sits just above the chat box.
 */
class PkpBarOverlay extends Overlay
{
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	PkpBarOverlay(BattleMagePlugin plugin, Appearance look)
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
		// Hidden while banking (the bank covers the spot it usually sits in), except while the
		// appearance editor is open and the bar is being judged.
		if (!look.showBar() || (!look.isEditing() && plugin.bankOpen()))
		{
			return null;
		}

		final int w = look.barWidth();
		final int h = look.barHeight();
		final int max = Math.max(1, plugin.getMaxPkp());
		final int cur = plugin.getCurrentPkp();
		// The appearance editor can ask for a sample fill (to judge the low/full colours) or a sample
		// overcharge; otherwise this is the real bar.
		final double sampleFill = look.sampleFill();
		final double frac = sampleFill >= 0 ? Math.min(1.0, sampleFill)
			: Math.max(0, Math.min(1.0, cur / (double) max));
		final boolean overcharged = look.sampleOvercharge() || (sampleFill < 0 && plugin.isOvercharged());

		// Hidden until the oath is sworn, then a one-time pop-in. Always shown while it is being edited.
		final double reveal = look.isEditing() ? 1.0 : plugin.revealProgress();
		if (reveal <= 0.0)
		{
			return null;
		}
		final AffineTransform savedTransform = g.getTransform();
		final Composite savedComposite = g.getComposite();
		RevealAnim.apply(g, reveal, w, h);

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(new Color(0, 0, 0, 200));
		g.fillRoundRect(0, 0, w, h, 8, 8);

		final int pad = 2;
		final int innerW = w - pad * 2;
		final int innerH = h - pad * 2;
		final int fillW = (int) Math.round(innerW * frac);
		g.setColor(overcharged ? look.overchargeColor()
			: lerp(look.barColorLow(), look.barColorFull(), frac));
		g.fillRoundRect(pad, pad, Math.max(0, fillW), innerH, 6, 6);

		if (look.showCostTicks())
		{
			g.setColor(new Color(255, 255, 255, 90));
			g.setStroke(new BasicStroke(1f));
			int cost = plugin.minSpellCost();
			if (cost > 0)
			{
				for (int v = cost; v < max; v += cost)
				{
					int x = pad + (int) Math.round(innerW * (v / (double) max));
					g.drawLine(x, pad, x, pad + innerH);
				}
			}
		}

		// regen shimmer: a glowing streak that sweeps across, brightest at the centre,
		// scaling speed + brightness with the regen rate and matching the border colour
		if (plugin.regenActive())
		{
			double intensity = plugin.regenIntensity();
			long period = (long) Math.max(280.0, 1000.0 / intensity);
			double p = (System.currentTimeMillis() % period) / (double) period; // 0..1 sweep
			double envelope = Math.sin(p * Math.PI);                            // 0 at ends, 1 at centre
			int peakAlpha = clamp((int) Math.round(130 * (0.45 + 0.55 * Math.min(1.0, intensity / 2.0))));
			int bandAlpha = clamp((int) Math.round(peakAlpha * envelope));
			if (bandAlpha > 0)
			{
				Color sc = plugin.barFrameColor();
				double bandX = pad + p * innerW;
				double sigma = Math.max(6.0, innerW / 14.0);
				int reach = (int) Math.round(sigma * 2.5);
				Shape oldClip = g.getClip();
				g.setClip(pad, pad, innerW, innerH);
				for (int dx = -reach; dx <= reach; dx++)
				{
					double falloff = Math.exp(-(dx * dx) / (2 * sigma * sigma));
					int a = clamp((int) Math.round(bandAlpha * falloff));
					if (a <= 0)
					{
						continue;
					}
					g.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), a));
					g.fillRect((int) Math.round(bandX) + dx, pad, 1, innerH);
				}
				g.setClip(oldClip);
			}
		}

		// poison water: green liquid sloshing inside the bar while poisoned/venomed, only across the
		// remaining PKP (the filled width). Its motion is paused via the plugin's freeze-aware clock
		// whenever the bar is frozen, and resumes on unfreeze.
		if (plugin.isPoisoned())
		{
			drawPoisonWater(g, pad, Math.max(0, fillW), innerH);
		}

		Color frame = plugin.barFrameColor();

		final double pulse = look.barPulse()
			? 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0))
			: 1.0;
		final int glow = look.barGlow();
		final float thick = look.barBorderThickness();
		for (int i = glow; i >= 1; i--)
		{
			int a = clamp((int) Math.round(45.0 * pulse * (glow - i + 1) / glow));
			g.setColor(new Color(frame.getRed(), frame.getGreen(), frame.getBlue(), a));
			g.setStroke(new BasicStroke(thick + i * 2f));
			g.drawRoundRect(0, 0, w, h, 8, 8);
		}
		g.setColor(new Color(frame.getRed(), frame.getGreen(), frame.getBlue(),
			clamp((int) Math.round(frame.getAlpha() * pulse))));
		g.setStroke(new BasicStroke(thick));
		g.drawRoundRect(0, 0, w, h, 8, 8);

		drawEffectBorder(g, w, h, thick, pulse);

		double criticalLeft = plugin.criticalOverloadRemaining();
		if (criticalLeft > 0)
		{
			drawCriticalCountdown(g, w, h, pad, criticalLeft);
		}

		StringBuilder sb = new StringBuilder();
		String barText = look.barLabel();
		if (barText != null && !barText.isEmpty())
		{
			sb.append(barText);
		}
		if (look.showPkpValue())
		{
			if (sb.length() > 0)
			{
				sb.append("  ");
			}
			sb.append(cur).append(" / ").append(max);
		}
		if (plugin.isPkpFrozen())
		{
			if (sb.length() > 0)
			{
				sb.append("  ");
			}
			sb.append("FROZEN");
		}
		String label = sb.toString();
		if (!label.isEmpty())
		{
			FontMetrics fm = g.getFontMetrics();
			int tx = (w - fm.stringWidth(label)) / 2;
			int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
			g.setColor(Color.BLACK);
			g.drawString(label, tx + 1, ty + 1);
			g.setColor(Color.WHITE);
			g.drawString(label, tx, ty);
		}

		RevealAnim.restore(g, savedTransform, savedComposite);
		return new Dimension(w, h);
	}

	/**
	 * Overlays the active freeze / immunity effects on the bar's border. One effect paints a solid ring in
	 * its colour; several are interleaved as equal-length dashed segments (one pass per effect, each offset
	 * by one segment) that march around the border, so every running effect stays visible at once. The
	 * colours are the same ones the effect-timer chips use.
	 */
	private void drawEffectBorder(Graphics2D g, int w, int h, float thick, double pulse)
	{
		if (!look.effectBorderEnabled())
		{
			return;
		}
		// only effects that are actually locking the bar get a border segment; an armed-but-not-started
		// freeze shows on its timer badge alone, since it is not restricting the bar yet
		final java.util.List<BattleMagePlugin.ActiveEffect> fx = new java.util.ArrayList<>();
		for (BattleMagePlugin.ActiveEffect e : plugin.activeEffects())
		{
			if (!e.pending)
			{
				fx.add(e);
			}
		}
		if (fx.isEmpty())
		{
			return;
		}

		final int k = fx.size();
		final float ringThick = thick + 2f;
		final int alpha = clamp((int) Math.round(255 * pulse));

		if (k == 1)
		{
			Color c = fx.get(0).color;
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			g.setStroke(new BasicStroke(ringThick));
			g.drawRoundRect(0, 0, w, h, 8, 8);
			return;
		}

		// segment length scales with the bar so short bars don't turn into a blur of dashes
		final float seg = Math.max(8f, (w + h) / (float) (6 * k));
		final float cycle = seg * k;
		final float march = (System.currentTimeMillis() % 2400L) / 2400f * cycle;

		for (int i = 0; i < k; i++)
		{
			Color c = fx.get(i).color;
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			g.setStroke(new BasicStroke(ringThick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
				new float[]{seg, seg * (k - 1)}, march + i * seg));
			g.drawRoundRect(0, 0, w, h, 8, 8);
		}
	}

	/**
	 * The CRITICAL OVERLOAD countdown, drawn as a bar inside the bar.
	 *
	 * <p>It lives on the bar because that is where the player is already looking. The lit span shrinks from the full width to nothing over the ten ticks, so a
	 * reset - any further attack refills it - reads as the bar snapping back to full.
	 *
	 * <p>It is drawn over the fill rather than beside it, in the critical colour, because the state
	 * it reports overrides everything the fill is saying at that moment.
	 */
	private void drawCriticalCountdown(Graphics2D g, int w, int h, int pad, double remaining)
	{
		final int innerW = w - pad * 2;
		final int lit = (int) Math.round(innerW * Math.max(0.0, Math.min(1.0, remaining)));
		final int band = Math.max(3, (h - pad * 2) / 4);
		final int y = h - pad - band;
		Color c = look.criticalColor();

		// the track, so the burned-through part stays legible against a full bar
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRoundRect(pad, y, innerW, band, band, band);

		// a hard flash at the top of each tick makes a reset unmistakable
		int alpha = 200 + (int) Math.round(55 * Math.sin(System.currentTimeMillis() / 90.0));
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp(alpha)));
		g.fillRoundRect(pad, y, Math.max(0, lit), band, band, band);
	}

	private void drawPoisonWater(Graphics2D g, int pad, int innerW, int innerH)
	{
		Shape oldClip = g.getClip();
		g.setClip(new RoundRectangle2D.Float(pad, pad, innerW, innerH, 6, 6));

		long t = plugin.poisonAnimTime();
		double phase = t / 240.0;
		double level = pad + innerH * 0.45;

		GeneralPath body = new GeneralPath();
		body.moveTo(pad - 2, pad + innerH + 2);
		for (int x = pad - 2; x <= pad + innerW + 2; x++)
		{
			body.lineTo(x, surfaceY(x, level, phase));
		}
		body.lineTo(pad + innerW + 2, pad + innerH + 2);
		body.closePath();
		g.setColor(new Color(60, 170, 70, 150));
		g.fill(body);

		GeneralPath surf = new GeneralPath();
		boolean first = true;
		for (int x = pad - 2; x <= pad + innerW + 2; x++)
		{
			double yy = surfaceY(x, level, phase);
			if (first)
			{
				surf.moveTo(x, yy);
				first = false;
			}
			else
			{
				surf.lineTo(x, yy);
			}
		}
		g.setColor(new Color(130, 235, 140, 200));
		g.setStroke(new BasicStroke(1.5f));
		g.draw(surf);

		g.setClip(oldClip);
	}

	private static double surfaceY(int x, double level, double phase)
	{
		return level + 2.0 * Math.sin(x * 0.18 + phase) + 1.0 * Math.sin(x * 0.09 - phase * 1.3);
	}

	private static Color lerp(Color a, Color b, double t)
	{
		int r = clamp((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t));
		int g = clamp((int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t));
		int bl = clamp((int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
		int al = clamp((int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
		return new Color(r, g, bl, al);
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
