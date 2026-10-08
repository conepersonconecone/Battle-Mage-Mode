package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Spellbook restriction visuals.
 * <ul>
 *   <li>ILLEGAL or DEPLETED state: the whole spellbook gets a state-coloured shade (orange for illegal,
 *       the depletion-alert colour for depleted) and EVERY spell in the book is X'd in that colour.</li>
 *   <li>Normal / overload state: only individually off-limit spells get the classic blue X.</li>
 * </ul>
 */
class SpellBlockOverlay extends Overlay
{
	private static final int SPELLBOOK_GROUP = 218;

	private final Client client;
	private final BattleMagePlugin plugin;
	private final BattleMageConfig config;

	@Inject
	SpellBlockOverlay(Client client, BattleMagePlugin plugin, BattleMageConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return null;
		}

		Color state = plugin.spellbookStateColor();
		if (state != null)
		{
			// ILLEGAL / DEPLETED: shade the whole book and X every spell in the state colour
			Rectangle[] union = new Rectangle[1];
			List<Rectangle> spells = new ArrayList<>();
			for (Widget root : roots)
			{
				scanSpellbook(root, 0, union, spells);
			}
			if (union[0] != null)
			{
				double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0);
				int a = (int) Math.round(60 + 40 * blink);
				g.setColor(new Color(state.getRed(), state.getGreen(), state.getBlue(), a));
				g.fillRect(union[0].x, union[0].y, union[0].width, union[0].height);
			}
			for (Rectangle b : spells)
			{
				drawX(g, b, state);
			}
		}
		else
		{
			// normal / overload: blue X on individually off-limit spells only
			for (Widget root : roots)
			{
				drawBlueBlocked(g, root, 0);
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- ILLEGAL / DEPLETED

	/** Accumulate the spellbook panel bounds (union) and every spell-icon's bounds. */
	private void scanSpellbook(Widget w, int depth, Rectangle[] union, List<Rectangle> spells)
	{
		if (w == null || depth > 8 || w.isHidden())
		{
			return;
		}
		if ((w.getId() >>> 16) == SPELLBOOK_GROUP)
		{
			Rectangle b = w.getBounds();
			if (b != null && b.width > 0 && b.height > 0)
			{
				union[0] = union[0] == null ? new Rectangle(b) : union[0].union(b);
				if (isSpellIcon(w, b))
				{
					spells.add(b);
				}
			}
		}
		scanChildren(w.getStaticChildren(), depth, union, spells);
		scanChildren(w.getDynamicChildren(), depth, union, spells);
		scanChildren(w.getNestedChildren(), depth, union, spells);
	}

	private void scanChildren(Widget[] children, int depth, Rectangle[] union, List<Rectangle> spells)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			scanSpellbook(c, depth + 1, union, spells);
		}
	}

	/** A spell icon: a sprite-backed, icon-sized widget that is castable or carries a spell name. */
	private static boolean isSpellIcon(Widget w, Rectangle b)
	{
		if (w.getSpriteId() <= 0)
		{
			return false;
		}
		int min = Math.min(b.width, b.height);
		int max = Math.max(b.width, b.height);
		if (min < 14 || max > 40)
		{
			return false;
		}
		if (isCastable(w))
		{
			return true;
		}
		String n = w.getName();
		return n != null && !n.replaceAll("<[^>]*>", "").trim().isEmpty();
	}

	// ---------------------------------------------------------------- normal / overload

	private void drawBlueBlocked(Graphics2D g, Widget w, int depth)
	{
		if (w == null || depth > 8)
		{
			return;
		}
		if (!w.isHidden())
		{
			Color xColor = plugin.spellBlockColor(w.getName(), isCastable(w));
			if (xColor != null)
			{
				Rectangle b = w.getBounds();
				if (b != null && b.width > 0 && b.height > 0)
				{
					drawX(g, b, xColor);
				}
			}
		}
		walk(g, w.getStaticChildren(), depth);
		walk(g, w.getDynamicChildren(), depth);
		walk(g, w.getNestedChildren(), depth);
	}

	private void walk(Graphics2D g, Widget[] children, int depth)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			drawBlueBlocked(g, c, depth + 1);
		}
	}

	// ---------------------------------------------------------------- shared

	private static void drawX(Graphics2D g, Rectangle b, Color color)
	{
		int inset = Math.max(4, (int) Math.round(Math.min(b.width, b.height) * 0.30));
		int x1 = b.x + inset;
		int y1 = b.y + inset;
		int x2 = b.x + b.width - inset;
		int y2 = b.y + b.height - inset;
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(new Color(0, 0, 0, 200));
		g.drawLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1);
		g.drawLine(x2 + 1, y1 + 1, x1 + 1, y2 + 1);
		g.setColor(color);
		g.drawLine(x1, y1, x2, y2);
		g.drawLine(x2, y1, x1, y2);
	}

	/** A castable spell icon: any spell (combat, teleport, utility) exposes a "Cast" action. */
	private static boolean isCastable(Widget w)
	{
		String[] actions = w.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (a != null && a.equalsIgnoreCase("Cast"))
			{
				return true;
			}
		}
		return false;
	}
}
