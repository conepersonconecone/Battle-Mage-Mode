package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Faction-less and wearing something the four gods don't all allow: the whole game goes dark, except
 * the side panel (so the gear can be taken off), with the list of what has to come off and an arrow to
 * the Equipment tab. It lifts the moment the last of it is unequipped.
 */
@Singleton
class FactionlessBlackoutOverlay extends Overlay
{
	private static final Color DARK = new Color(4, 4, 6, 246);
	private static final Color GOLD = new Color(0xD8, 0xB4, 0x5E);
	private static final Color RED = new Color(255, 90, 80);
	private static final Font TITLE = new Font(Font.SERIF, Font.BOLD, 30);
	private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
	private static final Font ITEM = new Font(Font.SANS_SERIF, Font.BOLD, 15);
	private static final int MAX_LISTED = 11;

	/** The Equipment tab button, and the side panel it opens, in each window layout. */
	private static final int[] EQUIPMENT_TAB = {
		InterfaceID.Toplevel.STONE4, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelPreEoc.STONE4};
	private static final int[] SIDE_PANEL = {
		InterfaceID.Toplevel.SIDE_PANELS, InterfaceID.ToplevelOsrsStretch.SIDE_PANELS, InterfaceID.ToplevelPreEoc.SIDE_PANELS};

	private final Client client;
	private final BattleMagePlugin plugin;

	@Inject
	FactionlessBlackoutOverlay(Client client, BattleMagePlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final List<String> barred = plugin.factionlessBarredGear();
		if (barred.isEmpty())
		{
			return null;
		}
		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		final Rectangle tab = bounds(EQUIPMENT_TAB);
		final Rectangle side = bounds(SIDE_PANEL);

		// everything dark but the side panel and the Equipment tab
		final Area dark = new Area(new Rectangle(0, 0, cw, ch));
		if (side != null)
		{
			dark.subtract(new Area(grow(side, 2)));
		}
		if (tab != null)
		{
			dark.subtract(new Area(grow(tab, 2)));
		}
		final Shape oldClip = g.getClip();
		g.setColor(DARK);
		g.fill(dark);

		// the message, centred in what's left of the game view
		final int areaRight = side != null ? Math.min(cw, side.x) : cw;
		final int cx = Math.max(160, areaRight / 2);
		int y = Math.max(60, ch / 2 - 40 - 10 * Math.min(barred.size(), MAX_LISTED));

		g.setFont(TITLE);
		centre(g, "FACTION-LESS", cx, y, GOLD);
		y += 30;
		g.setFont(BODY);
		centre(g, "Without a god, you may use only what all four gods allow.", cx, y, Color.WHITE);
		y += 22;
		centre(g, "Take off:", cx, y, new Color(210, 210, 210));
		y += 24;
		g.setFont(ITEM);
		for (int i = 0; i < barred.size() && i < MAX_LISTED; i++)
		{
			centre(g, barred.get(i), cx, y, RED);
			y += 21;
		}
		if (barred.size() > MAX_LISTED)
		{
			g.setFont(BODY);
			centre(g, "...and " + (barred.size() - MAX_LISTED) + " more", cx, y, RED);
			y += 21;
		}
		y += 8;
		g.setFont(BODY);
		centre(g, "Open the Equipment tab and unequip them to continue.", cx, y, GOLD);

		// an arrow, bobbing, to the Equipment tab, and a ring round the tab
		if (tab != null)
		{
			final double bob = Math.sin(System.currentTimeMillis() / 180.0) * 6;
			final int tx = tab.x + tab.width / 2;
			final int ty = tab.y + tab.height / 2;
			final int fromX = cx;
			final int fromY = y + 16;
			final double dx = tx - fromX;
			final double dy = ty - fromY;
			final double len = Math.max(1, Math.hypot(dx, dy));
			final double ux = dx / len;
			final double uy = dy / len;
			final double stop = Math.max(tab.width, tab.height) / 2.0 + 10 + bob;
			final int ex = (int) Math.round(tx - ux * stop);
			final int ey = (int) Math.round(ty - uy * stop);
			g.setColor(GOLD);
			g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(fromX, fromY, (int) Math.round(ex - ux * 12), (int) Math.round(ey - uy * 12));
			final Polygon head = new Polygon();
			head.addPoint(ex, ey);
			head.addPoint((int) Math.round(ex - ux * 20 - uy * 11), (int) Math.round(ey - uy * 20 + ux * 11));
			head.addPoint((int) Math.round(ex - ux * 20 + uy * 11), (int) Math.round(ey - uy * 20 - ux * 11));
			g.fillPolygon(head);
			final float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0));
			g.setColor(new Color(GOLD.getRed(), GOLD.getGreen(), GOLD.getBlue(), (int) (120 + 135 * pulse)));
			g.setStroke(new BasicStroke(3f));
			g.drawRoundRect(tab.x - 3, tab.y - 3, tab.width + 6, tab.height + 6, 8, 8);
		}
		g.setClip(oldClip);
		return null;
	}

	/** The first of these widgets that is on screen. */
	private Rectangle bounds(int[] ids)
	{
		for (int id : ids)
		{
			final Widget w = client.getWidget(id);
			if (w != null && !w.isHidden())
			{
				final Rectangle r = w.getBounds();
				if (r != null && r.width > 0 && r.height > 0)
				{
					return r;
				}
			}
		}
		return null;
	}

	private static Rectangle grow(Rectangle r, int by)
	{
		return new Rectangle(r.x - by, r.y - by, r.width + by * 2, r.height + by * 2);
	}

	private static void centre(Graphics2D g, String s, int cx, int y, Color c)
	{
		final FontMetrics fm = g.getFontMetrics();
		final int x = cx - fm.stringWidth(s) / 2;
		g.setColor(new Color(0, 0, 0, 220));
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}
}
