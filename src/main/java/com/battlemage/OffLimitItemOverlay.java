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
	/** The inventory interface (group 149, child 0), by number rather than the retired WidgetInfo enum. */
	private static final int INVENTORY_GROUP = 149;

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
		if (plugin.showCowbellGauges())
		{
			renderCowbellGauges(g);
		}
		return null;
	}

	// ---------------------------------------------------------------- Cowbell gauges

	/** The two slim gauges either side of the Cowbell amulet: caught-below-empty left, caught-above-full right. */
	private void renderCowbellGauges(Graphics2D g)
	{
		List<Rectangle> bells = new ArrayList<>();
		Widget inv = client.getWidget(INVENTORY_GROUP, 0);
		if (inv != null && !inv.isHidden() && inv.getDynamicChildren() != null)
		{
			for (Widget item : inv.getDynamicChildren())
			{
				if (item != null && !item.isHidden() && BattleMagePlugin.isCowbell(item.getItemId()))
				{
					bells.add(item.getBounds());
				}
			}
		}
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				findWornBell(root, 0, bells);
			}
		}
		double under = plugin.storedUnder() / BattleMagePlugin.SPILL_STORE_MAX;
		double over = plugin.storedOver() / BattleMagePlugin.SPILL_STORE_MAX;
		boolean underCooling = plugin.cowbellCooldown(-1) > 0;
		boolean overCooling = plugin.cowbellCooldown(1) > 0;
		Rectangle source = null;
		for (Rectangle b : bells)
		{
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}
			if (source == null)
			{
				source = b;
			}
			drawGauge(g, b.x - 1, b.y + 2, b.height - 4, under, look.barColorLow(), underCooling);
			drawGauge(g, b.x + b.width - 6, b.y + 2, b.height - 4, over, look.barColorFull(), overCooling);
			drawTwinkle(g, b);
		}
		drawZap(g, source);
	}

	// ---------------------------------------------------------------- absorbing and releasing

	/** How long the amulet glints after it catches a spill. */
	private static final long SPARKLE_MS = 700L;

	/** When the amulet catches a spill, it glints in that side's colour. */
	private void drawTwinkle(Graphics2D g, Rectangle b)
	{
		long now = System.currentTimeMillis();
		long start = plugin.spillStartMs();
		int side = plugin.spillSide();
		long age = now - start;
		if (start == 0 || side == 0 || age < 0 || age >= SPARKLE_MS || !look.barExhaust())
		{
			return;
		}
		double life = 1.0 - age / (double) SPARKLE_MS;
		Color c = side > 0 ? look.barColorFull() : look.barColorLow();
		java.util.Random r = new java.util.Random(start * 17 + age / 90);
		for (int k = 0; k < 3; k++)
		{
			double phase = Math.sin((age / 90.0) + k * 2.1);
			double size = (2.5 + 2.5 * Math.abs(phase)) * life;
			if (size < 1)
			{
				continue;
			}
			int cx = b.x + 6 + r.nextInt(Math.max(1, b.width - 12));
			int cy = b.y + 6 + r.nextInt(Math.max(1, b.height - 12));
			int a = (int) Math.round(255 * life);
			g.setStroke(new BasicStroke(1.4f));
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, a)));
			g.drawLine((int) (cx - size), cy, (int) (cx + size), cy);
			g.drawLine(cx, (int) (cy - size), cx, (int) (cy + size));
			g.setColor(new Color(255, 255, 255, Math.min(255, a)));
			g.fillOval(cx - 1, cy - 1, 2, 2);
		}
	}

	private static final long ZAP_MS = 420L;
	private static final long ZAP_SPARK_MS = 650L;

	/** A release: a bolt from the amulet into the PKP bar, throwing sparks where it lands. */
	private void drawZap(Graphics2D g, Rectangle bell)
	{
		long now = System.currentTimeMillis();
		long start = plugin.zapStartMs();
		long age = now - start;
		Rectangle bar = plugin.barScreenBounds();
		if (start == 0 || age < 0 || age >= ZAP_SPARK_MS || bar == null)
		{
			return;
		}
		// a release the worn amulet made on its own leaves from the player; one made by hand from the amulet
		double sx;
		double sy;
		net.runelite.api.Point from = plugin.zapFromPlayer() ? playerPoint(g) : null;
		if (from != null)
		{
			sx = from.getX();
			sy = from.getY();
		}
		else if (bell != null && !plugin.zapFromPlayer())
		{
			sx = bell.getCenterX();
			sy = bell.getCenterY();
		}
		else
		{
			return;
		}
		int side = plugin.zapSide();
		Color c = side > 0 ? look.barColorFull() : look.barColorLow();
		double ex = bar.x + bar.width * (side > 0 ? 0.75 : 0.25);
		double ey = bar.getCenterY();
		java.awt.Stroke old = g.getStroke();

		if (age < ZAP_MS)
		{
			double life = 1.0 - age / (double) ZAP_MS;
			java.util.Random r = new java.util.Random(start * 13 + age / 40);
			java.util.List<double[]> pts = new ArrayList<>();
			pts.add(new double[]{sx, sy});
			pts.add(new double[]{ex, ey});
			double spread = Math.hypot(ex - sx, ey - sy) / 6.0;
			for (int pass = 0; pass < 5; pass++)
			{
				java.util.List<double[]> next = new ArrayList<>();
				for (int i = 0; i < pts.size() - 1; i++)
				{
					double[] p0 = pts.get(i);
					double[] p1 = pts.get(i + 1);
					next.add(p0);
					next.add(new double[]{
						(p0[0] + p1[0]) / 2 + (r.nextDouble() - 0.5) * spread,
						(p0[1] + p1[1]) / 2 + (r.nextDouble() - 0.5) * spread});
				}
				next.add(pts.get(pts.size() - 1));
				pts = next;
				spread /= 2;
			}
			java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
			path.moveTo(pts.get(0)[0], pts.get(0)[1]);
			for (int i = 1; i < pts.size(); i++)
			{
				path.lineTo(pts.get(i)[0], pts.get(i)[1]);
			}
			int a = (int) Math.round(255 * Math.min(1.0, life * 1.6));
			g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, a / 3)));
			g.draw(path);
			g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, a)));
			g.draw(path);
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(new Color(255, 255, 255, Math.min(255, a)));
			g.draw(path);
		}

		// sparks thrown from where the bolt hits
		double t = age / 1000.0;
		double life = 1.0 - age / (double) ZAP_SPARK_MS;
		java.util.Random r = new java.util.Random(start);
		for (int k = 0; k < 16; k++)
		{
			double ang = r.nextDouble() * Math.PI * 2;
			double speed = 60 + 160 * r.nextDouble();
			double px = ex + Math.cos(ang) * speed * t;
			double py = ey + Math.sin(ang) * speed * t + 260 * t * t;
			double d = 1.5 + 2.5 * life;
			g.setColor(new Color(
				k % 3 == 0 ? 255 : c.getRed(), k % 3 == 0 ? 255 : c.getGreen(), k % 3 == 0 ? 255 : c.getBlue(),
				Math.max(0, Math.min(255, (int) Math.round(255 * life)))));
			g.fill(new java.awt.geom.Ellipse2D.Double(px - d / 2, py - d / 2, d, d));
		}
		g.setStroke(old);
	}

	/** The middle of the player's character on screen, or null when it is not drawn. */
	private net.runelite.api.Point playerPoint(Graphics2D g)
	{
		net.runelite.api.Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		return me.getCanvasTextLocation(g, "", me.getLogicalHeight() / 2);
	}

	private void findWornBell(Widget w, int depth, List<Rectangle> out)
	{
		if (w == null || depth > 8 || w.isHidden())
		{
			return;
		}
		if ((w.getId() >>> 16) == EQUIPMENT_GROUP && BattleMagePlugin.isCowbell(w.getItemId()))
		{
			out.add(w.getBounds());
		}
		for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids != null)
			{
				for (Widget k : kids)
				{
					findWornBell(k, depth + 1, out);
				}
			}
		}
	}

	/** A 4px-wide vertical gauge filling from the bottom; grey with a small red X while cooling down. */
	private static void drawGauge(Graphics2D g, int x, int y, int h, double frac, Color c, boolean cooling)
	{
		if (h < 6)
		{
			return;
		}
		if (cooling)
		{
			g.setColor(new Color(0, 0, 0, 170));
			g.fillRoundRect(x, y, 4, h, 3, 3);
			g.setColor(new Color(120, 120, 120, 230));
			g.fillRoundRect(x + 1, y + 1, 2, h - 2, 2, 2);
			int cx = x + 2;
			int cy = y + h / 2;
			g.setStroke(new BasicStroke(1.3f));
			g.setColor(new Color(0, 0, 0, 200));
			g.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
			g.drawLine(cx - 3, cy + 3, cx + 3, cy - 3);
			g.setColor(new Color(230, 30, 30));
			g.drawLine(cx - 2, cy - 2, cx + 2, cy + 2);
			g.drawLine(cx - 2, cy + 2, cx + 2, cy - 2);
			return;
		}
		double f = Math.max(0, Math.min(1, frac));
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(x, y, 4, h, 3, 3);
		int fh = (int) Math.round((h - 2) * f);
		if (fh > 0)
		{
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), f >= 1 ? 255 : 220));
			g.fillRoundRect(x + 1, y + 1 + (h - 2 - fh), 2, fh, 2, 2);
		}
		if (f >= 1)
		{
			double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0);
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(60 + 120 * blink)));
			g.setStroke(new BasicStroke(1.5f));
			g.drawRoundRect(x - 1, y - 1, 6, h + 2, 4, 4);
		}
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
		Widget inv = client.getWidget(INVENTORY_GROUP, 0);
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
		Widget inv = client.getWidget(INVENTORY_GROUP, 0);
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
