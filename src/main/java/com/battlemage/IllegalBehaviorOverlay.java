package com.battlemage;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * When illegal behaviour is flagged (a special-attack-only weapon auto-attacked), screams a diagonal
 * hazard-tape "ILLEGAL BEHAVIOR DETECTED" warning across the 3D game viewport only (interfaces stay clear).
 * Clears once the plugin reports the player has been out of combat long enough.
 */
class IllegalBehaviorOverlay extends Overlay
{
	private final Client client;
	private final BattleMagePlugin plugin;
	private final BattleMageConfig config;

	@Inject
	IllegalBehaviorOverlay(Client client, BattleMagePlugin plugin, BattleMageConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!plugin.illegalBehaviorActive())
		{
			return null;
		}

		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		if (vw <= 0 || vh <= 0)
		{
			vx = 0;
			vy = 0;
			vw = client.getCanvasWidth();
			vh = client.getCanvasHeight();
		}
		if (vw <= 0 || vh <= 0)
		{
			return null;
		}

		final long now = System.currentTimeMillis();
		final double flash = 0.55 + 0.45 * Math.abs(Math.sin(now / 180.0));

		Graphics2D gg = (Graphics2D) g.create();
		try
		{
			gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			gg.setClip(new Rectangle(vx, vy, vw, vh));

			// red wash
			gg.setColor(new Color(150, 0, 0, (int) (90 * flash)));
			gg.fillRect(vx, vy, vw, vh);

			final int cx = vx + vw / 2;
			final int cy = vy + vh / 2;
			final int reach = (int) Math.hypot(vw, vh);

			gg.translate(cx, cy);
			gg.rotate(Math.toRadians(-45));

			// hazard stripes
			gg.setColor(new Color(0, 0, 0, (int) (150 * flash)));
			gg.fillRect(-reach, -reach, reach * 2, reach * 2);
			gg.setColor(new Color(255, 215, 0, (int) (220 * flash)));
			int band = 64;
			for (int yy = -reach; yy < reach; yy += band * 2)
			{
				gg.fillRect(-reach, yy, reach * 2, band);
			}

			// repeated warning text along the diagonals
			gg.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(20, vw / 22)));
			FontMetrics fm = gg.getFontMetrics();
			String msg = "ILLEGAL BEHAVIOR DETECTED";
			int tw = fm.stringWidth(msg);
			int rowH = band * 2;
			int row = 0;
			for (int yy = -reach; yy < reach; yy += rowH, row++)
			{
				int startX = -reach - (row % 2) * (tw / 2);
				for (int xx = startX; xx < reach; xx += tw + 60)
				{
					gg.setColor(new Color(0, 0, 0, (int) (235 * flash)));
					gg.drawString(msg, xx + 2, yy + band - 14 + 2);
					gg.setColor(new Color(255, 30, 30, (int) (255 * flash)));
					gg.drawString(msg, xx, yy + band - 14);
				}
			}
		}
		finally
		{
			gg.dispose();
		}
		return null;
	}
}
