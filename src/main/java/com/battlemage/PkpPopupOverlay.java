package com.battlemage;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Floats +/- PKP change notifications upward just above the Report button box. Newer changes appear at the
 * bottom and push older ones up; each fades over the configured duration.
 */
class PkpPopupOverlay extends Overlay
{
	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;
	private final Rules rules;

	@Inject
	PkpPopupOverlay(Client client, BattleMagePlugin plugin, Appearance look, Rules rules)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		this.rules = rules;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!look.pkpPopupsEnabled())
		{
			return null;
		}
		List<BattleMagePlugin.PkpPopup> popups = plugin.pkpPopups();
		if (popups.isEmpty())
		{
			return null;
		}
		Widget w = client.getWidget(rules.reportWidgetGroup(), rules.reportWidgetChild());
		if (w == null || w.isHidden())
		{
			return null;
		}
		Rectangle b = w.getBounds();
		if (b == null || b.width <= 0)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
		final FontMetrics fm = g.getFontMetrics();
		final int cx = b.x + b.width / 2;
		final int baseY = b.y - 6;
		final long now = System.currentTimeMillis();
		final long life = Math.max(1, look.pkpPopupSeconds()) * 1000L;
		final int n = popups.size();

		for (int i = 0; i < n; i++)
		{
			BattleMagePlugin.PkpPopup p = popups.get(i);
			double age = (now - p.spawnMs) / (double) life;
			if (age < 0 || age >= 1.0)
			{
				continue;
			}
			int slot = n - 1 - i;                 // newest at the bottom, older pushed up
			int rise = (int) Math.round(age * 12); // gentle upward drift as it fades
			int y = baseY - slot * 18 - rise;
			int alpha = Math.max(0, Math.min(255, (int) Math.round(255 * (1.0 - age))));
			String text = plugin.fmtPkpDelta(p.delta);
			int tx = cx - fm.stringWidth(text) / 2;

			// loss is always red; gain is cyan like overcharge. When poisoned, both shift toward a sickly
			// green to show poison is affecting PKP gain/loss. Deliberately independent of the bar glow colour.
			Color col = p.delta >= 0 ? look.overchargeColor() : new Color(0xFF2A2A);
			if (plugin.isPoisoned())
			{
				col = blend(col, new Color(80, 200, 85), 0.55f);
			}

			g.setColor(new Color(0, 0, 0, (int) (alpha * 0.7)));
			g.drawString(text, tx + 1, y + 1);
			g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), alpha));
			g.drawString(text, tx, y);
		}
		return null;
	}

	private static Color blend(Color a, Color b, float f)
	{
		float g = 1f - f;
		int r = Math.round(a.getRed() * g + b.getRed() * f);
		int gr = Math.round(a.getGreen() * g + b.getGreen() * f);
		int bl = Math.round(a.getBlue() * g + b.getBlue() * f);
		return new Color(Math.min(255, r), Math.min(255, gr), Math.min(255, bl));
	}
}
