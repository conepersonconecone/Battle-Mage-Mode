package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Full-canvas punishment overlay. While PSI is depleted it stays on screen (grey wash + PSI DEPLETED)
 * until you melee back to full; while overloaded it shows PSI OVERLOAD until you cast. Both pulse gently.
 */
class PkpStateOverlay extends Overlay
{
	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	PkpStateOverlay(Client client, BattleMagePlugin plugin, Appearance look)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// The appearance editor can ask for a sample state, so its settings can be judged on screen.
		final Appearance.Sample sample = look.sample();
		final boolean previewing = sample != Appearance.Sample.NONE;

		// the ILLEGAL warning takes over the screen (hazard tape + orange inventory); don't also show
		// the PSI OVERLOAD / PSI DEPLETED titles or wash underneath it.
		if (!previewing && plugin.illegalBehaviorActive())
		{
			return null;
		}

		final boolean critical = previewing ? sample == Appearance.Sample.CRITICAL : plugin.criticalOverloadActive();
		final boolean depleted = previewing ? sample == Appearance.Sample.DEPLETED : !critical && plugin.isDepleted();
		final boolean overloaded = previewing ? sample == Appearance.Sample.OVERLOAD
			: !critical && !depleted && plugin.isOverloaded();
		if (!critical && !depleted && !overloaded)
		{
			look.setStateTextBounds(null);
			return null;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// gentle continuous pulse (0..1)
		double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0);
		// the exact curve the PKP bar's frame uses, so the state text breathes in lockstep with the bar
		double pulse = look.barPulse() ? 0.6 + 0.4 * blink : 1.0;

		if (depleted && look.desaturateScreen())
		{
			int a = clamp((int) Math.round(look.desaturationStrength() * (0.7 + 0.3 * blink)));
			g.setColor(new Color(128, 128, 128, a));
			g.fillRect(0, 0, cw, ch);
		}
		else if (overloaded)
		{
			Color o = look.overloadColor();
			int a = clamp((int) Math.round(70 * (0.7 + 0.3 * blink)));
			g.setColor(new Color(o.getRed() / 3, 0, o.getBlue() / 3, a));
			g.fillRect(0, 0, cw, ch);
		}
		else if (critical)
		{
			// CRITICAL OVERLOAD keeps the centre of the screen clear - no hazard tape - so the yellow
			// reads as a wash at exactly the opacity the overload and depletion states use.
			Color c = look.criticalColor();
			int a = clamp((int) Math.round(70 * (0.7 + 0.3 * blink)));
			g.setColor(new Color(c.getRed() / 2, c.getGreen() / 3, 0, a));
			g.fillRect(0, 0, cw, ch);
		}

		String text = critical ? look.criticalText()
			: (depleted ? look.depletedText() : look.overloadText());
		text = text == null ? "" : text;
		if (text.isEmpty())
		{
			look.setStateTextBounds(null);
			return null;
		}
		Color base = critical ? look.criticalColor()
			: (depleted ? look.depletedColor() : look.overloadColor());

		// Each text sits wherever the player dragged it in the appearance editor. CRITICAL has its own
		// position (default: along the bottom, so the game view stays playable); depleted and
		// overload share one.
		final int cx = (int) Math.round(cw * ((critical ? look.criticalTextXPercent() : look.stateTextXPercent()) / 100.0));
		final int cy = (int) Math.round(ch * ((critical ? look.criticalTextYPercent() : look.stateTextYPercent()) / 100.0));

		int baseSize = critical ? Math.max(20, ch / 15) : Math.max(28, ch / 9);
		int scale = critical ? look.criticalTextScalePercent()
			: (depleted ? look.depletedTextScalePercent() : look.overloadTextScalePercent());
		int size = (int) Math.round(baseSize * (scale / 100.0));
		// the text itself pulses (like the bar frame) instead of being wrapped in a soft halo, so it
		// stays fully saturated at its brightest and never washes out against a busy scene
		int textAlpha = clamp((int) Math.round(255 * pulse));

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
		FontMetrics fm = g.getFontMetrics();
		int ty = cy + fm.getAscent() / 2;
		int tx = cx - fm.stringWidth(text) / 2;

		// Build the glyph outline once: stroking it gives a clean, gap-free black edge at any font
		// size, which reads far better over the 3D scene than the old offset-drawString halo.
		Shape glyphs = g.getFont()
			.createGlyphVector(g.getFontRenderContext(), text)
			.getOutline(tx, ty);

		// hard drop shadow for depth
		Graphics2D sh = (Graphics2D) g.create();
		try
		{
			sh.translate(Math.max(3, size / 22), Math.max(3, size / 22));
			sh.setColor(new Color(0, 0, 0, 210));
			sh.fill(glyphs);
		}
		finally
		{
			sh.dispose();
		}

		// solid black outline, thickness scaled to the font
		g.setColor(new Color(0, 0, 0, 240));
		g.setStroke(new BasicStroke(Math.max(3f, size / 16f),
			BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(glyphs);

		// pulsing fill
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), textAlpha));
		g.fill(glyphs);

		// Remember where the text is, so the appearance editor can drag it. While previewing a
		// movable state, a dashed box and a hint show that it can be picked up.
		java.awt.Rectangle bounds = glyphs.getBounds();
		bounds.grow(8, 8);
		look.setStateTextBounds(bounds);
		if (previewing)
		{
			g.setColor(new Color(255, 255, 255, 170));
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
				new float[]{6f, 5f}, 0f));
			g.draw(bounds);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
			String hint = "Drag to move";
			int hw = g.getFontMetrics().stringWidth(hint);
			g.setColor(new Color(0, 0, 0, 200));
			g.drawString(hint, bounds.x + (bounds.width - hw) / 2 + 1, bounds.y + bounds.height + 15);
			g.setColor(Color.WHITE);
			g.drawString(hint, bounds.x + (bounds.width - hw) / 2, bounds.y + bounds.height + 14);
		}

		return null;
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
