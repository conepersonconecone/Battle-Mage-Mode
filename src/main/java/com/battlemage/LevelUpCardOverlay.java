package com.battlemage;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * EarthBound-style level-up cards. Mirrors the PSI cast cards (slide in, dwell, dissolve) but enters from the
 * TOP-RIGHT corner of the screen - exactly opposite the cast cards' top-left - sliding in from the right. Each
 * card is themed to the skill that levelled, lingers a little longer than the cast cards, and they play ONE AT
 * A TIME: a card must fully fade before the next one appears.
 */
class LevelUpCardOverlay extends Overlay
{
	private static final long LIFE_MS = 3800L;   // longer than the cast cards' 2500ms
	private static final int CARD_H = 30;
	private static final int MARGIN = 8;          // same margin the cast cards use, but from the right edge

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	LevelUpCardOverlay(Client client, BattleMagePlugin plugin, Appearance look)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!look.levelUpCards())
		{
			return null;
		}
		List<BattleMagePlugin.LevelCard> cards = plugin.getLevelCards();
		if (cards.isEmpty())
		{
			return null;
		}
		// only ever render the front card, and only once it has been started (by the game tick)
		BattleMagePlugin.LevelCard c = cards.get(0);
		if (c.startMs == 0L)
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		double age = (now - c.startMs) / (double) LIFE_MS;
		if (age < 0 || age >= 1.0)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		final FontMetrics fm = g.getFontMetrics();

		// anchor to the game viewport's top-right (the scene area), so cards sit at the top of the play
		// area and clear the minimap / side UI rather than overlapping them
		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = client.getViewportWidth();
		if (vw <= 0)
		{
			vx = 0;
			vy = 0;
			vw = client.getCanvasWidth() > 0 ? client.getCanvasWidth() : 520;
		}
		final int rightEdge = vx + vw - MARGIN;

		double in = Math.min(1.0, age / 0.12);            // quick slide-in
		float alpha = (float) Math.max(0f, Math.min(1f, in * (1.0 - age) * 1.25));
		int slideY = (int) Math.round((1.0 - in) * -24);  // enters from above its landing spot

		String label = "\u25B2 " + c.skill + "  Lv " + c.level;
		int cardW = Math.max(150, fm.stringWidth(label) + 44);
		int x = rightEdge - cardW;                         // fixed landing x at the viewport's top-right
		int y = vy + MARGIN + slideY;

		Color col = c.color != null ? c.color : Color.WHITE;
		final Composite baseComposite = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		RoundRectangle2D box = new RoundRectangle2D.Float(x, y, cardW, CARD_H, 12, 12);
		g.setColor(new Color(0, 0, 0, 220));
		g.fill(box);

		// skill-coloured wash so the card takes on the skill's vibe
		Paint oldPaint = g.getPaint();
		g.setPaint(new GradientPaint(
			x, y, new Color(col.getRed(), col.getGreen(), col.getBlue(), 30),
			x + cardW, y, new Color(col.getRed(), col.getGreen(), col.getBlue(), 110)));
		g.fill(box);
		g.setPaint(oldPaint);

		// soft pulsing glow + border in the skill colour
		double blink = 0.6 + 0.4 * Math.sin(now / 200.0);
		int glowA = Math.max(0, Math.min(255, (int) Math.round(70 * blink)));
		g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), glowA));
		g.setStroke(new BasicStroke(4f));
		g.draw(new RoundRectangle2D.Float(x - 1, y - 1, cardW + 2, CARD_H + 2, 13, 13));
		g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 240));
		g.setStroke(new BasicStroke(2f));
		g.draw(box);

		// label
		int tx = x + 12;
		int ty = y + CARD_H / 2 + fm.getAscent() / 2 - 2;
		g.setColor(new Color(0, 0, 0, 230));
		g.drawString(label, tx + 1, ty + 1);
		g.setColor(Color.WHITE);
		g.drawString(label, tx, ty);

		g.setComposite(baseComposite);
		return null;
	}
}
