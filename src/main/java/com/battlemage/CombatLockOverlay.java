package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Marks the combat-tab buttons that are locked out - the autocast box (autocasting is disabled) - by
 * covering the whole box with a translucent red block and a slash. Overlapping matches within one box
 * are merged so each box gets exactly one block.
 */
class CombatLockOverlay extends Overlay
{
	private final Client client;

	@Inject
	CombatLockOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		List<Rectangle> matches = new ArrayList<>();
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				collect(root, matches, 0);
			}
		}
		for (Rectangle r : merge(matches))
		{
			drawBlock(g, r);
		}
		return null;
	}

	private void collect(Widget w, List<Rectangle> out, int depth)
	{
		if (w == null || depth > 8)
		{
			return;
		}
		if (!w.isHidden() && isLocked(w))
		{
			Rectangle b = w.getBounds();
			if (b != null && b.width > 4 && b.height > 4)
			{
				out.add(b);
			}
		}
		collectAll(w.getStaticChildren(), out, depth);
		collectAll(w.getDynamicChildren(), out, depth);
		collectAll(w.getNestedChildren(), out, depth);
	}

	private void collectAll(Widget[] children, List<Rectangle> out, int depth)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			collect(c, out, depth + 1);
		}
	}

	/** Merge overlapping rectangles into their union, so each box ends up with one block. */
	private List<Rectangle> merge(List<Rectangle> rects)
	{
		List<Rectangle> merged = new ArrayList<>();
		for (Rectangle r : rects)
		{
			boolean joined = false;
			for (int i = 0; i < merged.size(); i++)
			{
				if (merged.get(i).intersects(r))
				{
					merged.set(i, merged.get(i).union(r));
					joined = true;
					break;
				}
			}
			if (!joined)
			{
				merged.add(new Rectangle(r));
			}
		}
		// a second pass catches rectangles that only overlap after a union grew
		boolean changed = true;
		while (changed)
		{
			changed = false;
			outer:
			for (int i = 0; i < merged.size(); i++)
			{
				for (int j = i + 1; j < merged.size(); j++)
				{
					if (merged.get(i).intersects(merged.get(j)))
					{
						merged.set(i, merged.get(i).union(merged.get(j)));
						merged.remove(j);
						changed = true;
						break outer;
					}
				}
			}
		}
		return merged;
	}

	private boolean isLocked(Widget w)
	{
		StringBuilder sb = new StringBuilder();
		if (w.getName() != null)
		{
			sb.append(w.getName().toLowerCase(Locale.ROOT)).append(' ');
		}
		if (w.getText() != null)
		{
			sb.append(w.getText().toLowerCase(Locale.ROOT)).append(' ');
		}
		String[] actions = w.getActions();
		if (actions != null)
		{
			for (String a : actions)
			{
				if (a != null)
				{
					sb.append(a.toLowerCase(Locale.ROOT)).append(' ');
				}
			}
		}
		String s = sb.toString();
		return s.contains("auto-cast") || s.contains("autocast") || s.contains("choose spell");
	}

	private void drawBlock(Graphics2D g, Rectangle r)
	{
		// translucent red wash over the whole box
		g.setColor(new Color(200, 0, 0, 80));
		g.fillRect(r.x, r.y, r.width, r.height);

		// dark backing for contrast
		g.setStroke(new BasicStroke(4f));
		g.setColor(new Color(0, 0, 0, 150));
		g.drawLine(r.x, r.y, r.x + r.width, r.y + r.height);
		g.drawLine(r.x + r.width, r.y, r.x, r.y + r.height);

		// red border + diagonal slash
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(new Color(230, 20, 20, 245));
		g.drawRect(r.x, r.y, r.width, r.height);
		g.drawLine(r.x, r.y, r.x + r.width, r.y + r.height);
		g.drawLine(r.x + r.width, r.y, r.x, r.y + r.height);
	}
}
