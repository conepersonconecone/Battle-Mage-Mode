package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The X-Ray aura: a glowing ring on the ground under your character, in your god's colour. It is
 * drawn over the scene, so it stays visible through walls and scenery (the Soul aura is the in-world
 * version, see {@link SoulAura}).
 *
 * <p>The ring is built from points around the player in the game world and projected onto the
 * screen, so it lies flat on the ground, follows the terrain and turns with the camera. The
 * character's own outline is cut out of it, so the ring reads as being underneath them rather than
 * painted across their legs. Only you can see it: nothing is sent anywhere or changed in the game.
 */
@Singleton
class FactionAuraOverlay extends Overlay
{
	/** Ring radius in local units (a tile is 128). */
	private static final int RADIUS = 72;
	/** Points around the ring: enough to look round at any zoom. */
	private static final int SEGMENTS = 48;
	/** One slow breath of the glow, and one full turn of the bright arcs. */
	private static final double PULSE_MS = 2400.0;
	private static final double SPIN_MS = 9000.0;

	private final Client client;
	private final Oath oath;
	private final Appearance look;

	@Inject
	FactionAuraOverlay(Client client, Oath oath, Appearance look)
	{
		this.client = client;
		this.oath = oath;
		this.look = look;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Faction faction = oath.getFaction();
		Player me = client.getLocalPlayer();
		if (look.auraMode() != AuraMode.XRAY || faction == null || me == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		LocalPoint at = me.getLocalLocation();
		if (at == null)
		{
			return null;
		}
		int plane = client.getTopLevelWorldView().getPlane();

		Polygon outer = ring(at, plane, RADIUS, 0.0);
		Polygon inner = ring(at, plane, (int) (RADIUS * 0.62), 0.0);
		if (outer == null || inner == null)
		{
			return null;
		}

		long now = System.currentTimeMillis();
		double breath = 0.5 + 0.5 * Math.sin(now / PULSE_MS * 2 * Math.PI);
		Color c = faction.getPrimary();

		Shape oldClip = g.getClip();
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// cut the character out, so the ring sits under them
		Shape hull = me.getConvexHull();
		if (hull != null)
		{
			Rectangle view = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
			Area clip = new Area(oldClip != null ? oldClip : view);
			clip.subtract(new Area(hull));
			g.setClip(clip);
		}

		// a soft disc: the band between the inner and outer ring, lightly filled
		Area band = new Area(outer);
		band.subtract(new Area(inner));
		g.setColor(alpha(c, (int) (45 + 30 * breath)));
		g.fill(band);
		g.setColor(alpha(c, (int) (18 + 12 * breath)));
		g.fill(inner);

		// the glow, then the bright rim on top of it
		g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(c, (int) (50 + 40 * breath)));
		g.draw(outer);
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(c.brighter(), (int) (190 + 50 * breath)));
		g.draw(outer);
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(alpha(c, (int) (110 + 40 * breath)));
		g.draw(inner);

		// three short bright arcs that turn slowly around the rim
		double spin = (now % (long) SPIN_MS) / SPIN_MS * 2 * Math.PI;
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(Color.WHITE, (int) (120 + 60 * breath)));
		for (int k = 0; k < 3; k++)
		{
			Polygon arc = arc(at, plane, RADIUS, spin + k * 2 * Math.PI / 3, Math.PI / 7);
			if (arc != null)
			{
				g.drawPolyline(arc.xpoints, arc.ypoints, arc.npoints);
			}
		}

		g.setClip(oldClip);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		return null;
	}

	/** A full ring of the given radius around a point, projected to the screen; null if off screen. */
	private Polygon ring(LocalPoint at, int plane, int radius, double start)
	{
		return arc(at, plane, radius, start, 2 * Math.PI, SEGMENTS);
	}

	private Polygon arc(LocalPoint at, int plane, int radius, double start, double sweep)
	{
		return arc(at, plane, radius, start, sweep, 8);
	}

	private Polygon arc(LocalPoint at, int plane, int radius, double start, double sweep, int steps)
	{
		Polygon p = new Polygon();
		for (int i = 0; i <= steps; i++)
		{
			double a = start + sweep * i / steps;
			LocalPoint lp = at.plus((int) Math.round(Math.cos(a) * radius), (int) Math.round(Math.sin(a) * radius));
			Point s = Perspective.localToCanvas(client, lp, plane);
			if (s == null)
			{
				return null;
			}
			p.addPoint(s.getX(), s.getY());
		}
		return p;
	}

	private static Color alpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
	}
}
