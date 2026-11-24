package com.battlemage;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

/**
 * Party members' PKP state, shown on their characters: an outline that glows in and out in the colour
 * of their most urgent state (Critical Overload, Overload or Depletion), over a faint wash of the same
 * colour across the whole model. Critical Overload breathes faster. Nothing for a teammate in none of
 * those states. Only teammates in your RuneLite party who play Battle Mage Mode, never strangers.
 *
 * <p>With "Glow on your character" on (the in-game PKP style), your own character glows the same way.
 */
class PartyPkpOverlay extends Overlay
{
	/** One full glow in and out, in milliseconds; Critical Overload goes at twice the pace. */
	private static final double GLOW_PERIOD_MS = 1600.0;
	/** The wash across the model: about 10% opaque. */
	private static final int TINT_ALPHA = 26;

	private final Client client;
	private final PartySync party;
	private final Appearance look;
	private final ModelOutlineRenderer outlines;
	private final BattleMagePlugin plugin;

	@Inject
	PartyPkpOverlay(Client client, PartySync party, Appearance look, ModelOutlineRenderer outlines,
		BattleMagePlugin plugin)
	{
		this.plugin = plugin;
		this.client = client;
		this.party = party;
		this.look = look;
		this.outlines = outlines;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final long now = System.currentTimeMillis();
		final Player me = client.getLocalPlayer();

		// your own character, in the in-game style (or while its look is being previewed)
		if (me != null && look.selfGlow())
		{
			final Appearance.Sample sample = look.sample();
			final boolean critical;
			final boolean overloaded;
			final boolean depleted;
			if (sample != Appearance.Sample.NONE)
			{
				critical = sample == Appearance.Sample.CRITICAL;
				overloaded = sample == Appearance.Sample.OVERLOAD;
				depleted = sample == Appearance.Sample.DEPLETED;
			}
			else
			{
				critical = plugin.criticalOverloadActive();
				overloaded = plugin.isOverloaded();
				depleted = plugin.isDepleted();
			}
			final Color c = critical ? look.criticalColor() : overloaded ? look.overloadColor()
				: depleted ? look.depletedColor() : null;
			if (c != null)
			{
				glow(g, me, c, critical, now);
			}
		}

		if (!look.partyHeadBars())
		{
			return null;
		}
		final Map<String, PartySync.Mate> mates = party.matesByName();
		if (mates.isEmpty())
		{
			return null;
		}
		for (Player p : client.getPlayers())
		{
			if (p == null || p == me || p.getName() == null)
			{
				continue;
			}
			final PartySync.Mate m = mates.get(Text.standardize(p.getName()));
			if (m == null || m.state == 0)
			{
				continue;
			}
			final List<PartyStatus.Badge> states = PartyStatus.badges(m.state, look);
			if (!states.isEmpty())
			{
				glow(g, p, states.get(0).color, m.has(BattleMagePartyUpdate.CRITICAL), now);
			}
			if (m.has(BattleMagePartyUpdate.BARRED_GEAR))
			{
				crossOut(g, p);
			}
		}
		return null;
	}

	/**
	 * A teammate in gear their faction doesn't allow: a big red X over their character, drawn like the
	 * off-limit symbol on items (a dark edge, the red, a white core), pulsing gently.
	 */
	private static void crossOut(Graphics2D g, Player p)
	{
		final Shape hull = p.getConvexHull();
		if (hull == null)
		{
			return;
		}
		final java.awt.Rectangle b = hull.getBounds();
		final int cx = (int) b.getCenterX();
		final int cy = (int) b.getCenterY();
		final int r = Math.max(10, Math.min(b.width, b.height) / 2);
		final double beat = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 260.0);
		final int a = (int) (170 + 85 * beat);
		final java.awt.Stroke old = g.getStroke();
		final Color red = PartyStatus.ILLEGAL;
		g.setStroke(new java.awt.BasicStroke(Math.max(6f, r / 5f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0, 0, 0, a * 2 / 3));
		g.drawLine(cx - r, cy - r, cx + r, cy + r);
		g.drawLine(cx - r, cy + r, cx + r, cy - r);
		g.setStroke(new java.awt.BasicStroke(Math.max(4f, r / 7f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.setColor(new Color(red.getRed(), red.getGreen(), red.getBlue(), a));
		g.drawLine(cx - r, cy - r, cx + r, cy + r);
		g.drawLine(cx - r, cy + r, cx + r, cy - r);
		g.setStroke(new java.awt.BasicStroke(1.3f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, a * 3 / 4));
		g.drawLine(cx - r, cy - r, cx + r, cy + r);
		g.drawLine(cx - r, cy + r, cx + r, cy - r);
		g.setStroke(old);
	}

	/**
	 * One character's state glow: a faint wash of the colour across the whole model, under an outline
	 * that glows in and out - brighter and wider-feathered at the top of each breath.
	 */
	private void glow(Graphics2D g, Player p, Color c, boolean critical, long now)
	{
		final Shape hull = p.getConvexHull();
		if (hull != null)
		{
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), TINT_ALPHA));
			g.fill(hull);
		}
		final double period = critical ? GLOW_PERIOD_MS / 2 : GLOW_PERIOD_MS;
		final double breath = 0.5 + 0.5 * Math.sin(now * 2 * Math.PI / period);
		final int alpha = (int) (70 + 170 * breath);
		final int feather = 1 + (int) Math.round(3 * breath);
		outlines.drawOutline(p, critical ? 3 : 2, new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha), feather);
	}
}
