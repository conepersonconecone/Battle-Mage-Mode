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
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Inventory restriction visuals.
 * <ul>
 *   <li>While ILLEGAL BEHAVIOR is showing: a single translucent orange wash over the whole inventory
 *       (no per-item symbols), plus a bright-green outline around the exempt item(s) - the Cowbell amulet,
 *       the only one-click teleport during that state.</li>
 *   <li>Otherwise: the usual per-item "no" symbol over off-limit gear/weapons (red) and the state circles
 *       over food/potions blocked by overload/depletion.</li>
 * </ul>
 */
class OffLimitItemOverlay extends Overlay
{
	// the worn-equipment interface group; item icons within it carry their worn item id
	private static final int EQUIPMENT_GROUP = 387;

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	OffLimitItemOverlay(Client client, BattleMagePlugin plugin, Appearance look)
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
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// The appearance editor's CRITICAL sample shows these markers too, so their colours can be
		// judged without being in the state.
		if (plugin.lockdownActive() || look.sample() == Appearance.Sample.CRITICAL)
		{
			renderIllegal(g);
		}
		else
		{
			renderNormal(g);
		}
		return null;
	}

	// ---------------------------------------------------------------- ILLEGAL state

	/** Orange wash over the inventory AND the worn-equipment panel, with the exempt item(s) outlined. */
	private void renderIllegal(Graphics2D g)
	{
		final Color s = look.illegalInventoryShade();
		final double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0);
		final Color wash = new Color(s.getRed(), s.getGreen(), s.getBlue(), (int) Math.round(60 + 40 * blink));
		final List<Rectangle> exempt = new ArrayList<>();

		// inventory tab: wash it, mark every non-exempt item, and note any exempt item carried
		Widget inv = client.getWidget(WidgetInfo.INVENTORY);
		if (inv != null && !inv.isHidden())
		{
			fill(g, wash, inv.getBounds());

			// match the spellbook X colour (orange while illegal); fall back to the shade if unavailable
			Color sym = plugin.spellbookStateColor();
			if (sym == null)
			{
				sym = s;
			}

			Widget[] items = inv.getDynamicChildren();
			if (items != null)
			{
				for (Widget item : items)
				{
					if (item == null || item.isHidden())
					{
						continue;
					}
					int id = item.getItemId();
					if (id <= 0)
					{
						continue;
					}
					if (plugin.isRestrictionExemptItem(id))
					{
						exempt.add(item.getBounds());
					}
					else
					{
						drawNoSymbol(g, item.getBounds(), sym);
					}
				}
			}
		}

		// worn-equipment tab: wash the whole panel and note the exempt item if it's worn
		Rectangle[] union = new Rectangle[1];
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				scanEquip(root, 0, union, exempt);
			}
		}
		fill(g, wash, union[0]);

		// outlines drawn last, on top of the washes
		for (Rectangle b : exempt)
		{
			drawOutline(g, b, look.illegalExemptOutline());
		}
	}

	/** Accumulate the worn-equipment panel bounds (union) and any exempt worn-item slot bounds. */
	private void scanEquip(Widget w, int depth, Rectangle[] union, List<Rectangle> exempt)
	{
		if (w == null || depth > 8 || w.isHidden())
		{
			return;
		}
		if ((w.getId() >>> 16) == EQUIPMENT_GROUP)
		{
			Rectangle b = w.getBounds();
			if (b != null && b.width > 0 && b.height > 0)
			{
				union[0] = union[0] == null ? new Rectangle(b) : union[0].union(b);
				int id = w.getItemId();
				if (id > 0 && plugin.isRestrictionExemptItem(id))
				{
					exempt.add(b);
				}
			}
		}
		scanEquipChildren(w.getStaticChildren(), depth, union, exempt);
		scanEquipChildren(w.getDynamicChildren(), depth, union, exempt);
		scanEquipChildren(w.getNestedChildren(), depth, union, exempt);
	}

	private void scanEquipChildren(Widget[] children, int depth, Rectangle[] union, List<Rectangle> exempt)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			scanEquip(c, depth + 1, union, exempt);
		}
	}

	private static void fill(Graphics2D g, Color c, Rectangle b)
	{
		if (b != null && b.width > 0 && b.height > 0)
		{
			g.setColor(c);
			g.fillRect(b.x, b.y, b.width, b.height);
		}
	}

	/** Bright outline (glow + solid) around an item's slot. */
	private void drawOutline(Graphics2D g, Rectangle b, Color base)
	{
		if (b == null || b.width <= 0)
		{
			return;
		}
		double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 260.0);
		int pad = 1;
		int x = b.x - pad;
		int y = b.y - pad;
		int w = b.width + pad * 2;
		int h = b.height + pad * 2;

		int glowA = (int) Math.round(60 + 110 * blink);
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), glowA));
		g.setStroke(new BasicStroke(5f));
		g.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 8, 8);

		g.setColor(new Color(0, 0, 0, 150));
		g.setStroke(new BasicStroke(3.5f));
		g.drawRoundRect(x, y, w, h, 6, 6);

		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 255));
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(x, y, w, h, 6, 6);
	}

	// ---------------------------------------------------------------- normal state

	private void renderNormal(Graphics2D g)
	{
		Widget inv = client.getWidget(WidgetInfo.INVENTORY);
		if (inv == null || inv.isHidden())
		{
			return;
		}
		Widget[] items = inv.getDynamicChildren();
		if (items == null)
		{
			return;
		}
		for (Widget item : items)
		{
			if (item == null || item.isHidden())
			{
				continue;
			}
			int id = item.getItemId();
			if (id <= 0)
			{
				continue;
			}
			Rectangle b = item.getBounds();
			if (b == null || b.width <= 0)
			{
				continue;
			}
			if (plugin.isDisabledFood(id))
			{
				drawStateCircle(g, b, look.overloadColor(), plugin.foodCircleFraction());
			}
			else if (plugin.isDisabledPotion(id))
			{
				drawStateCircle(g, b, look.depletedColor(), 1f);
			}
			else if (plugin.isOffLimitItem(id))
			{
				drawNoSymbol(g, b, new Color(230, 20, 20));
			}
		}
	}

	/**
	 * Colored circle + slash over a disabled item, sized/centred exactly like the gear marker. The outline
	 * glows and pulses in the given colour; while a timer is running (frac &lt; 1) the fill clears
	 * counter-clockwise to show the seconds passing.
	 */
	private void drawStateCircle(Graphics2D g, Rectangle b, Color base, float frac)
	{
		int d = Math.min(b.width, b.height) - 4;
		if (d < 6)
		{
			return;
		}
		int cx = b.x + b.width / 2;
		int cy = b.y + b.height / 2;
		int x = cx - d / 2;
		int y = cy - d / 2;
		float f = Math.max(0f, Math.min(1f, frac));
		double rr = d / 2.0;
		double off = rr * Math.cos(Math.toRadians(45));
		double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0);

		// semi-transparent fill as a pie wedge: the empty part sweeps counter-clockwise as a timer runs down
		int startAngle = Math.round(90 + (1f - f) * 360f) % 360;
		int arcAngle = Math.round(f * 360f);
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 90));
		if (f >= 0.999f)
		{
			g.fillOval(x, y, d, d);
		}
		else if (arcAngle != 0)
		{
			g.fillArc(x, y, d, d, startAngle, arcAngle);
		}

		// pulsing glow halo, matching the alert text colour
		int glowA = Math.max(0, Math.min(255, (int) Math.round(45 + 95 * blink)));
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), glowA));
		g.setStroke(new BasicStroke(5f));
		g.drawOval(x - 2, y - 2, d + 4, d + 4);
		g.setStroke(new BasicStroke(4f));
		g.drawOval(x - 1, y - 1, d + 2, d + 2);

		// dark backing slash for contrast
		g.setStroke(new BasicStroke(4f));
		g.setColor(new Color(0, 0, 0, 150));
		g.drawLine((int) (cx - off), (int) (cy - off), (int) (cx + off), (int) (cy + off));

		// opaque outline + diagonal slash
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 255));
		g.drawOval(x, y, d, d);
		g.drawLine((int) (cx - off), (int) (cy - off), (int) (cx + off), (int) (cy + off));
	}

	private void drawNoSymbol(Graphics2D g, Rectangle b, Color base)
	{
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			return;
		}
		int d = Math.min(b.width, b.height) - 4;
		if (d < 6)
		{
			return;
		}
		int cx = b.x + b.width / 2;
		int cy = b.y + b.height / 2;
		int x = cx - d / 2;
		int y = cy - d / 2;
		double r = d / 2.0;
		double off = r * Math.cos(Math.toRadians(45));

		// translucent wash inside the circle
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
		g.fillOval(x, y, d, d);

		// dark backing for contrast
		g.setStroke(new BasicStroke(4f));
		g.setColor(new Color(0, 0, 0, 170));
		g.drawOval(x, y, d, d);
		g.drawLine((int) (cx - off), (int) (cy - off), (int) (cx + off), (int) (cy + off));

		// circle + diagonal slash
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 240));
		g.drawOval(x, y, d, d);
		g.drawLine((int) (cx - off), (int) (cy - off), (int) (cx + off), (int) (cy + off));
	}
}
