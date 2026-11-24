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
 *
 * <p>While the PKP orb is showing (the in-game style), they come off the orb instead: above it when it
 * is in the lower half of the screen and below it in the upper half, scrolling away from it; or, set
 * from the orb's right-click menu, sideways - to its right on the left half of the screen and to its
 * left on the right half. Newest nearest the orb either way.
 *
 * <p>With the HUD bar showing, its right-click menu can move them to any corner of the bar instead:
 * above or below it, lined up with its left or right end.
 */
class PkpPopupOverlay extends Overlay
{
	/** A state that was stopped ("OVERLOAD AVERTED") is set a size smaller than the PKP numbers. */
	private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;
	private final Rules rules;
	private final PkpOrbOverlay orb;

	@Inject
	PkpPopupOverlay(Client client, BattleMagePlugin plugin, Appearance look, Rules rules, PkpOrbOverlay orb)
	{
		this.orb = orb;
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
		final Rectangle ob = orb.shownBounds();
		if (ob != null)
		{
			renderFromOrb(g, popups, ob);
			return null;
		}
		if (look.pkpBarPopupsAtBar())
		{
			final Rectangle bb = plugin.pkpBarShownBounds();
			if (bb != null)
			{
				renderAtBar(g, popups, bb);
				return null;
			}
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
			if (p.label != null)
			{
				// a state that something stopped: words in that state's colour, a size smaller so a
				// long label still fits the column, with a stronger outline to read over the game
				Font old = g.getFont();
				g.setFont(LABEL_FONT);
				FontMetrics lfm = g.getFontMetrics();
				int lx = cx - lfm.stringWidth(p.label) / 2;
				Color lc = p.color;
				g.setColor(new Color(0, 0, 0, (int) (alpha * 0.85)));
				g.drawString(p.label, lx + 1, y + 1);
				g.drawString(p.label, lx - 1, y + 1);
				g.setColor(new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), alpha));
				g.drawString(p.label, lx, y);
				g.setFont(old);
				continue;
			}
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

	/** One live popup, measured: what it says, in which font and colour, how faded. */
	private static final class Line
	{
		final String text;
		final Font font;
		final Color color;
		final int alpha;
		final double age;
		final int width;

		Line(String text, Font font, Color color, int alpha, double age, int width)
		{
			this.text = text;
			this.font = font;
			this.color = color;
			this.alpha = alpha;
			this.age = age;
			this.width = width;
		}
	}

	private static final Font NUMBER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);
	/** How far each number drifts away from the orb over its life. */
	private static final int DRIFT = 12;

	/**
	 * The popups at a corner of the PKP bar, as chosen from its right-click menu: lined up with its
	 * left or right end, rising from above it or falling from below it. Newest nearest the bar.
	 */
	private void renderAtBar(Graphics2D g, List<BattleMagePlugin.PkpPopup> popups, Rectangle bb)
	{
		final List<Line> lines = lines(g, popups);
		final boolean right = look.pkpBarPopupsRight();
		final boolean below = look.pkpBarPopupsBelow();
		int offset = 0;
		for (Line l : lines)
		{
			FontMetrics fm = g.getFontMetrics(l.font);
			int drift = (int) Math.round(l.age * DRIFT);
			int x = right ? bb.x + bb.width - l.width : bb.x;
			int y = below ? bb.y + bb.height + fm.getAscent() + 2 + offset + drift : bb.y - 6 - offset - drift;
			draw(g, l, x, y);
			offset += fm.getAscent() + 2;
		}
	}

	/** The popups coming off the PKP orb: up/down by screen half, or sideways by screen side. */
	private void renderFromOrb(Graphics2D g, List<BattleMagePlugin.PkpPopup> popups, Rectangle ob)
	{
		final List<Line> lines = lines(g, popups);
		if (lines.isEmpty())
		{
			return;
		}
		renderLinesFromOrb(g, lines, ob);
	}

	/** The live popups, newest first, measured. */
	private List<Line> lines(Graphics2D g, List<BattleMagePlugin.PkpPopup> popups)
	{
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		final long now = System.currentTimeMillis();
		final long life = Math.max(1, look.pkpPopupSeconds()) * 1000L;

		// newest first: it sits nearest the orb, older ones further out
		final java.util.ArrayList<Line> lines = new java.util.ArrayList<>();
		for (int i = popups.size() - 1; i >= 0; i--)
		{
			BattleMagePlugin.PkpPopup p = popups.get(i);
			double age = (now - p.spawnMs) / (double) life;
			if (age < 0 || age >= 1.0)
			{
				continue;
			}
			int alpha = Math.max(0, Math.min(255, (int) Math.round(255 * (1.0 - age))));
			Font font = p.label != null ? LABEL_FONT : NUMBER_FONT;
			String text = p.label != null ? p.label : plugin.fmtPkpDelta(p.delta);
			Color col = p.label != null ? p.color : numberColor(p.delta);
			lines.add(new Line(text, font, col, alpha, age, g.getFontMetrics(font).stringWidth(text)));
		}
		return lines;
	}

	private void renderLinesFromOrb(Graphics2D g, List<Line> lines, Rectangle ob)
	{

		final int cx = ob.x + ob.width / 2;
		final int cy = ob.y + ob.height / 2;
		final boolean sideways = look.pkpOrbPopupsSideways();
		if (sideways)
		{
			// away from the nearer side: rightwards on the left half of the screen, leftwards on the right
			final boolean rightwards = cx < client.getCanvasWidth() / 2;
			int offset = 6;
			for (Line l : lines)
			{
				FontMetrics fm = g.getFontMetrics(l.font);
				int drift = (int) Math.round(l.age * DRIFT);
				int x = rightwards ? ob.x + ob.width + offset + drift : ob.x - offset - drift - l.width;
				int y = cy + (fm.getAscent() - fm.getDescent()) / 2;
				draw(g, l, x, y);
				offset += l.width + 8;
			}
		}
		else
		{
			// away from the nearer edge: upwards in the lower half of the screen, downwards in the upper
			final boolean upwards = cy >= client.getCanvasHeight() / 2;
			int offset = 0;
			for (Line l : lines)
			{
				FontMetrics fm = g.getFontMetrics(l.font);
				int drift = (int) Math.round(l.age * DRIFT);
				// centred on the number, not on the whole orb-and-number
				Integer numberX = orb.numberCentreX();
				int x = (numberX != null ? numberX : cx) - l.width / 2;
				int y = upwards ? ob.y - 6 - offset - drift : ob.y + ob.height + fm.getAscent() + 2 + offset + drift;
				draw(g, l, x, y);
				offset += fm.getAscent() + 2;
			}
		}
	}

	private static void draw(Graphics2D g, Line l, int x, int y)
	{
		g.setFont(l.font);
		g.setColor(new Color(0, 0, 0, (int) (l.alpha * 0.8)));
		g.drawString(l.text, x + 1, y + 1);
		g.drawString(l.text, x - 1, y + 1);
		g.setColor(new Color(l.color.getRed(), l.color.getGreen(), l.color.getBlue(), l.alpha));
		g.drawString(l.text, x, y);
	}

	/** Loss red, gain the overcharge colour; both turned sickly green while poisoned. */
	private Color numberColor(double delta)
	{
		Color col = delta >= 0 ? look.overchargeColor() : new Color(0xFF2A2A);
		if (plugin.isPoisoned())
		{
			col = blend(col, new Color(80, 200, 85), 0.55f);
		}
		return col;
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
