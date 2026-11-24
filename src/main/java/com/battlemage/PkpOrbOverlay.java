package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The in-game style's small PKP readout, made like the game's own minimap orbs: your PKP as a number
 * beside an orb bearing your god's crest. The orb fills from the bottom up as PKP rises, in your
 * bar's colours; it turns grey below zero, gains an overcharge rim above your maximum, and sloshes
 * with green water while you're poisoned, as the bar does. In Depletion, Overload or Critical
 * Overload its rim glows in that state's colour - the in-game style's only sign of the state
 * besides your character's glow; the state words belong to the HUD style.
 *
 * <p>It starts just left of the minimap's orbs. Alt-drag it anywhere on screen (RuneLite's usual
 * overlay moving; "Reset" puts it back). Right-click it to flip the number to the other side of the
 * orb, to make it bigger or smaller (100% to 175%, in steps of 25%), or to have the PKP gain and loss
 * numbers scroll sideways instead of up or down. Hidden while the bank is open.
 *
 * <p>While the orb shows, the PKP gain and loss numbers come off it rather than the Report button:
 * above it in the lower half of the screen, below it in the upper half; or, scrolling sideways, to
 * its right on the left half of the screen and to its left on the right half.
 */
@Singleton
class PkpOrbOverlay extends Overlay
{
	private static final String TARGET = "PKP orb";
	private static final String FLIP = "Flip";
	private static final String BIGGER = "Increase size";
	private static final String SMALLER = "Decrease size";
	private static final String SIDEWAYS = "Scroll numbers sideways";
	private static final String UPWARDS = "Scroll numbers up/down";
	static final int MIN_SCALE = 100;
	static final int MAX_SCALE = 175;
	private static final int STEP = 25;

	private static final int ORB = 28;
	private static final int BOX_W = 34;
	private static final int BOX_H = 18;
	private static final int GAP = 2;
	private static final Font NUMBER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;
	private final Oath oath;
	/** What the right-click menu was last built for, so it is rebuilt only when that changes. */
	private int menuScale = -1;
	private Boolean menuSideways;
	/** Whether the orb was drawn last frame: the PKP numbers follow it only while it is. */
	private volatile boolean shown;

	@Inject
	PkpOrbOverlay(Client client, BattleMagePlugin plugin, Appearance look, Oath oath)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		this.oath = oath;
		setPosition(OverlayPosition.DETACHED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(false);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!look.pkpOrb() || !oath.isSworn() || (!look.isEditing() && (plugin.revealProgress() <= 0.0 || plugin.bankOpen())))
		{
			shown = false;
			return null;
		}
		shown = true;
		final int scalePct = Math.max(MIN_SCALE, Math.min(MAX_SCALE, look.pkpOrbScale()));
		syncMenu(scalePct, look.pkpOrbPopupsSideways());
		final double scale = scalePct / 100.0;
		final int baseW = BOX_W + 8 + GAP + ORB;
		final int w = (int) Math.ceil(baseW * scale);
		final int h = (int) Math.ceil((ORB + 4) * scale);

		// never moved: sit just left of the minimap's orbs (or near the top right without them)
		if (getPreferredLocation() == null)
		{
			final Widget orbs = client.getWidget(InterfaceID.Orbs.UNIVERSE);
			final Rectangle ob = orbs == null || orbs.isHidden() ? null : orbs.getBounds();
			if (ob != null && ob.width > 0)
			{
				getBounds().setLocation(ob.x - w - 6, ob.y + 6);
			}
			else
			{
				getBounds().setLocation(client.getCanvasWidth() - w - 220, 8);
			}
		}

		final AffineTransform old = g.getTransform();
		g.scale(scale, scale);
		draw(g, look.pkpOrbFlipped());
		g.setTransform(old);
		return new Dimension(w, h);
	}

	/** The orb and its number, at 100%, from (0, 0). Flipped: number right of the orb. */
	private void draw(Graphics2D g, boolean flipped)
	{
		final int max = Math.max(1, plugin.getMaxPkp());
		final int cur = plugin.getCurrentPkp();
		final double sampleFill = look.sampleFill();
		final double frac = sampleFill >= 0 ? Math.min(1.0, sampleFill) : Math.max(0, Math.min(1.0, cur / (double) max));
		final boolean over = look.sampleOvercharge() || (sampleFill < 0 && plugin.isOvercharged());
		final boolean negative = sampleFill < 0 && cur < 0;

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		final int y = 2;
		final int plateW = BOX_W + 8;
		final int px = flipped ? ORB + GAP : 0;
		final int ox = flipped ? 0 : plateW + GAP;

		// the number, in a dark plate like the game's orbs, coloured from the bar's low to full colour
		final int by = y + (ORB - BOX_H) / 2;
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(px, by, plateW, BOX_H, 8, 8);
		final Color fill = negative ? Color.LIGHT_GRAY
			: over ? look.overchargeColor() : mix(look.barColorLow(), look.barColorFull(), frac);
		g.setFont(NUMBER_FONT);
		final FontMetrics fm = g.getFontMetrics();
		final String text = sampleFill >= 0 ? String.valueOf((int) Math.round(frac * max)) : String.valueOf(cur);
		final int tx = px + (plateW - fm.stringWidth(text)) / 2;
		final int ty = by + (BOX_H + fm.getAscent() - fm.getDescent()) / 2;
		g.setColor(Color.BLACK);
		g.drawString(text, tx + 1, ty + 1);
		g.setColor(fill);
		g.drawString(text, tx, ty);

		// the orb: a dark well filled from the bottom up
		final Shape well = new Ellipse2D.Double(ox, y, ORB, ORB);
		g.setColor(new Color(20, 18, 14, 230));
		g.fill(well);
		final double level = negative ? Math.min(1.0, -cur / (double) max) : frac;
		final int fh = (int) Math.round(ORB * level);
		final Shape oldClip = g.getClip();
		g.clip(well);
		if (fh > 0)
		{
			final Color c = negative ? new Color(150, 150, 150) : fill;
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
			g.fillRect(ox, y + ORB - fh, ORB, fh);
		}
		// poisoned: green water sloshing in the bottom of the orb, as in the bar
		if (plugin.isPoisoned())
		{
			drawPoisonWater(g, ox, y);
		}
		g.setClip(oldClip);

		// the god's crest over the fill
		final Faction f = oath.getFaction();
		if (f != null)
		{
			FactionSigils.drawFaction(g, f, ox + 2, y + 2, ORB - 4);
		}

		// the rim: brass like the game's orbs - or, in a PKP state, that state's colour (the in-game
		// style's way of showing Depletion, Overload and Critical Overload), glowing in and out and
		// faster for Critical; plus the overcharge glow above the maximum
		final Color state = stateColor();
		g.setStroke(new BasicStroke(2f));
		g.setColor(new Color(87, 80, 64));
		g.draw(well);
		if (state != null)
		{
			final boolean critical = state.equals(look.criticalColor()) && isCritical();
			final double beat = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / (critical ? 110.0 : 260.0));
			g.setStroke(new BasicStroke(5f));
			g.setColor(new Color(state.getRed(), state.getGreen(), state.getBlue(), (int) (40 + 60 * beat)));
			g.draw(new Ellipse2D.Double(ox - 1, y - 1, ORB + 2, ORB + 2));
			g.setStroke(new BasicStroke(2.4f));
			g.setColor(new Color(state.getRed(), state.getGreen(), state.getBlue(), (int) (170 + 85 * beat)));
			g.draw(well);
		}
		if (over)
		{
			final double beat = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0);
			final Color oc = look.overchargeColor();
			g.setStroke(new BasicStroke(2.4f));
			g.setColor(new Color(oc.getRed(), oc.getGreen(), oc.getBlue(), (int) (140 + 110 * beat)));
			g.draw(new Ellipse2D.Double(ox - 1.5, y - 1.5, ORB + 3, ORB + 3));
		}
	}

	/** Critical Overload: from the editor's sample while previewing, else the real state. */
	private boolean isCritical()
	{
		final Appearance.Sample s = look.sample();
		return s != Appearance.Sample.NONE ? s == Appearance.Sample.CRITICAL : plugin.criticalOverloadActive();
	}

	/** The rim's state colour - Critical, then Overload, then Depleted - or null in no state. */
	private Color stateColor()
	{
		final Appearance.Sample s = look.sample();
		final boolean critical;
		final boolean overloaded;
		final boolean depleted;
		if (s != Appearance.Sample.NONE)
		{
			critical = s == Appearance.Sample.CRITICAL;
			overloaded = s == Appearance.Sample.OVERLOAD;
			depleted = s == Appearance.Sample.DEPLETED;
		}
		else
		{
			critical = plugin.criticalOverloadActive();
			overloaded = plugin.isOverloaded();
			depleted = plugin.isDepleted();
		}
		return critical ? look.criticalColor() : overloaded ? look.overloadColor() : depleted ? look.depletedColor() : null;
	}

	/**
	 * The middle of the number, across the screen, while the orb shows: where the PKP gain/loss
	 * numbers come from when they scroll up or down. Null while the orb isn't showing.
	 */
	Integer numberCentreX()
	{
		final Rectangle b = shownBounds();
		if (b == null)
		{
			return null;
		}
		final double scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, look.pkpOrbScale())) / 100.0;
		final int plateW = BOX_W + 8;
		final int px = look.pkpOrbFlipped() ? ORB + GAP : 0;
		return b.x + (int) Math.round((px + plateW / 2.0) * scale);
	}

	/** Where the orb is on screen right now, or null while it isn't showing. */
	Rectangle shownBounds()
	{
		final Rectangle b = getBounds();
		return shown && b != null && b.width > 0 ? new Rectangle(b) : null;
	}

	/** The bar's poison water, in the orb: a green body with a moving surface just under half way. */
	private void drawPoisonWater(Graphics2D g, int ox, int y)
	{
		final double phase = plugin.poisonAnimTime() / 240.0;
		final double level = y + ORB * 0.55;
		final GeneralPath body = new GeneralPath();
		body.moveTo(ox - 2, y + ORB + 2);
		for (int x = ox - 2; x <= ox + ORB + 2; x++)
		{
			body.lineTo(x, surfaceY(x, level, phase));
		}
		body.lineTo(ox + ORB + 2, y + ORB + 2);
		body.closePath();
		g.setColor(new Color(60, 170, 70, 150));
		g.fill(body);

		final GeneralPath surf = new GeneralPath();
		surf.moveTo(ox - 2, surfaceY(ox - 2, level, phase));
		for (int x = ox - 1; x <= ox + ORB + 2; x++)
		{
			surf.lineTo(x, surfaceY(x, level, phase));
		}
		g.setColor(new Color(130, 235, 140, 200));
		g.setStroke(new BasicStroke(1.5f));
		g.draw(surf);
	}

	private static double surfaceY(int x, double level, double phase)
	{
		return level + 1.6 * Math.sin(x * 0.30 + phase) + 0.8 * Math.sin(x * 0.15 - phase * 1.3);
	}

	/**
	 * The right-click menu: Flip always, and Increase / Decrease size only while there is room to
	 * go that way. Rebuilt only when the size changes.
	 */
	private void syncMenu(int scalePct, boolean sideways)
	{
		if (scalePct == menuScale && Boolean.valueOf(sideways).equals(menuSideways))
		{
			return;
		}
		menuScale = scalePct;
		menuSideways = sideways;
		getMenuEntries().clear();
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, FLIP, TARGET, e -> look.setNow("pkpOrbFlipped", !look.pkpOrbFlipped()));
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, sideways ? UPWARDS : SIDEWAYS, TARGET,
			e -> look.setNow("pkpOrbPopupsSideways", !look.pkpOrbPopupsSideways()));
		if (scalePct < MAX_SCALE)
		{
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, BIGGER, TARGET,
				e -> look.setNow("pkpOrbScale", Math.min(MAX_SCALE, look.pkpOrbScale() + STEP)));
		}
		if (scalePct > MIN_SCALE)
		{
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, SMALLER, TARGET,
				e -> look.setNow("pkpOrbScale", Math.max(MIN_SCALE, look.pkpOrbScale() - STEP)));
		}
	}

	private static Color mix(Color a, Color b, double t)
	{
		t = Math.max(0, Math.min(1, t));
		return new Color(
			(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}
}
