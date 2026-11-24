package com.battlemage;

import java.awt.Color;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * "Cast cards" menu mode: instead of a persistent battle menu, each cast drops a single card in from the
 * top-centre of the screen showing the family name + symbol (with the spell's icon faded behind it), then
 * dissolves. Newer cards appear at the top and push older ones down.
 */
class CastCardOverlay extends Overlay
{
	private static final long LIFE_MS = 2500L;
	private static final int CARD_H = 30;
	private static final int GAP = 6;

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;
	private final SpriteManager spriteManager;

	@Inject
	CastCardOverlay(Client client, BattleMagePlugin plugin, Appearance look, SpriteManager spriteManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!look.castCardsEnabled())
		{
			return null;
		}
		List<BattleMagePlugin.CastCard> cards = plugin.castCards();
		if (cards.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		final int margin = 8;
		final int gap = 6;
		final long now = System.currentTimeMillis();
		final int n = cards.size();
		final Composite base = g.getComposite();

		// the game view, which the cards stay inside
		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		if (vw <= 0 || vh <= 0)
		{
			vx = 0;
			vy = 0;
			vw = client.getCanvasWidth() > 0 ? client.getCanvasWidth() : 520;
			vh = client.getCanvasHeight() > 0 ? client.getCanvasHeight() : 340;
		}

		// lay the live cards out first (left to right, wrapping), to know the block's size
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		final FontMetrics lfm = g.getFontMetrics();
		final int room = Math.max(120, vw - 2 * margin);
		final int[] relX = new int[n];
		final int[] relY = new int[n];
		int blockW = 0;
		int blockH = 0;
		{
			int fx = 0;
			int fy = 0;
			for (int i = 0; i < n; i++)
			{
				BattleMagePlugin.CastCard c = cards.get(i);
				double age = (now - c.spawnMs) / (double) LIFE_MS;
				if (age < 0 || age >= 1.0)
				{
					continue;
				}
				int w = Math.max(120, lfm.stringWidth(c.label) + 54);
				if (fx > 0 && fx + w > room)
				{
					fx = 0;
					fy += CARD_H + GAP;
				}
				relX[i] = fx;
				relY[i] = fy;
				blockW = Math.max(blockW, fx + w);
				blockH = Math.max(blockH, fy + CARD_H);
				fx += w + gap;
			}
		}
		// where the block lands, set in Customize appearance: percent across and down the game view
		final double px = Math.max(0, Math.min(100, look.castCardsX())) / 100.0;
		final double py = Math.max(0, Math.min(100, look.castCardsY())) / 100.0;
		final int originX = vx + margin + (int) Math.round(Math.max(0, vw - 2 * margin - blockW) * px);
		// the first row sits at the chosen spot; further rows push away from the nearer edge - upwards in
		// the lower half of the screen, downwards in the upper half
		final int anchorY = vy + margin + (int) Math.round(Math.max(0, vh - 2 * margin - CARD_H) * py);
		final boolean upwards = anchorY + CARD_H / 2 >= vy + vh / 2;

		for (int i = 0; i < n; i++)
		{
			BattleMagePlugin.CastCard c = cards.get(i);
			double age = (now - c.spawnMs) / (double) LIFE_MS;
			if (age < 0 || age >= 1.0)
			{
				continue;
			}
			double in = Math.min(1.0, age / 0.12);        // quick slide-in
			float alpha = (float) Math.max(0f, Math.min(1f, in * (1.0 - age) * 1.3));
			// enters from the side it pushes away from: from below when the cards stack upwards
			int slideY = (int) Math.round((1.0 - in) * (upwards ? 24 : -24));

			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
			FontMetrics fm = g.getFontMetrics();

			int cardW = Math.max(120, fm.stringWidth(c.label) + 54);
			int x = originX + relX[i];
			int y = (upwards ? anchorY - relY[i] : anchorY + relY[i]) + slideY;

			Color col = plugin.colorFor(c.family);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

			// card body + border
			RoundRectangle2D box = new RoundRectangle2D.Float(x, y, cardW, CARD_H, 12, 12);
			g.setColor(new Color(0, 0, 0, 220));
			g.fill(box);

			// spell icon faded behind, shrunk within the card for weaker spells (c.scale)
			BufferedImage icon = c.spriteId > 0 ? spriteManager.getSprite(c.spriteId, 0) : null;
			if (icon != null)
			{
				float iconScale = c.scale <= 0 ? 1f : c.scale;
				Composite cc = g.getComposite();
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.45f));
				int ih = Math.round((CARD_H - 6) * iconScale);
				int iw = icon.getWidth() * ih / Math.max(1, icon.getHeight());
				int ix = x + cardW - iw - 8;                 // right-aligned with constant padding
				int iy = y + (CARD_H - ih) / 2;              // vertically centred in the full-size card
				g.drawImage(icon, ix, iy, iw, ih, null);
				g.setComposite(cc);
			}

			// poison fog: a green ombre wash over the card while poisoned
			if (plugin.isPoisoned())
			{
				Paint oldPaint = g.getPaint();
				g.setPaint(new GradientPaint(
					x, y, new Color(80, 205, 90, 55),
					x, y + CARD_H, new Color(40, 150, 55, 150)));
				g.fill(box);
				g.setPaint(oldPaint);
			}

			g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 235));
			g.setStroke(new java.awt.BasicStroke(2f));
			g.draw(box);

			// label
			int tx = x + 12;
			int ty = y + CARD_H / 2 + fm.getAscent() / 2 - 2;
			g.setColor(new Color(0, 0, 0, 230));
			g.drawString(c.label, tx + 1, ty + 1);
			g.setColor(Color.WHITE);
			g.drawString(c.label, tx, ty);
		}

		g.setComposite(base);
		return null;
	}
}
