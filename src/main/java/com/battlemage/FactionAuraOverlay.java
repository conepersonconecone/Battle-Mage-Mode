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
import net.runelite.client.util.Text;

/**
 * The X-Ray aura: a glowing ring on the ground under your character, in your god's colour. It is
 * drawn over the scene, so it stays visible through walls and scenery (the Soul aura is the in-world
 * version, see {@link SoulAura}).
 *
 * <p>The ring is built from points around the player in the game world and projected onto the
 * screen, so it lies flat on the ground, follows the terrain and turns with the camera. The
 * character's own outline is cut out of it, so the ring reads as being underneath them rather than
 * painted across their legs. It is drawn on your own screen only; party members who also play Battle
 * Mage Mode are told which aura you chose and draw it under you on theirs (and you see theirs).
 *
 * <p>Your own ring is also your PKP gauge. The bright rim runs round as far as your PKP reaches,
 * a full circle being 100 PKP, and empties clockwise from the top as PKP is spent. Below zero it
 * turns grey and fills again with how far under you are. Past 100 PKP (or whenever a book or other
 * item raises your maximum past 100) a second ring sits just above the first: its faint track shows
 * the extra room, and it fills with the PKP above 100.
 *
 * <p>With "Effect particles" on (the in-game PKP style) and the X-Ray aura chosen, each positive
 * PKP effect running on you sends its own particles off the ring, winding round you in a slowly
 * turning helix: rising motes for overload protection, falling motes for depletion protection,
 * drifting frost for a freeze and climbing sparks for a PKP surge.
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
	/** PKP in one full ring. */
	private static final double RING_PKP = 100.0;
	/** How far above the ground the second ring sits, in local units: close, but plainly separate. */
	private static final int SECOND_RING_Z = 20;

	private final Client client;
	private final Oath oath;
	private final Appearance look;
	private final PartySync party;
	private final BattleMagePlugin plugin;

	@Inject
	FactionAuraOverlay(Client client, Oath oath, Appearance look, PartySync party, BattleMagePlugin plugin)
	{
		this.plugin = plugin;
		this.party = party;
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
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		int plane = client.getTopLevelWorldView().getPlane();
		long now = System.currentTimeMillis();
		Player me = client.getLocalPlayer();
		Faction faction = oath.getFaction();
		if (me != null && faction != null && look.shownAura() == AuraMode.XRAY)
		{
			drawAura(g, me, faction, plane, now, true);
		}
		if (me != null && faction != null && look.effectParticles() && look.shownAura() == AuraMode.XRAY)
		{
			drawParticles(g, me, plane, now, ownEffects());
		}

		// party members who chose the X-Ray aura get theirs drawn under them too - but, being a PKP ring,
		// only while their PKP status is shown in game (the Soul aura, drawn by SoulAura, doesn't depend
		// on that)
		if (!look.partyAuras() || !look.partyHeadBars())
		{
			return null;
		}
		java.util.Map<String, PartySync.Mate> mates = party.matesByName();
		if (mates.isEmpty())
		{
			return null;
		}
		final boolean mineToo = look.inGameStyle() && look.effectParticles() && look.shownAura() == AuraMode.XRAY;
		for (Player p : client.getPlayers())
		{
			if (p == null || p == me || p.getName() == null)
			{
				continue;
			}
			PartySync.Mate m = mates.get(Text.standardize(p.getName()));
			if (m != null && m.aura == AuraMode.XRAY && m.faction != null)
			{
				drawAura(g, p, m.faction, plane, now, false);
				// their positive effects' particles, when you and they both use the in-game style and X-Ray
				if (mineToo && m.has(BattleMagePartyUpdate.IN_GAME))
				{
					drawParticles(g, p, plane, now, mateEffects(m));
				}
			}
		}
		return null;
	}

	/** The ring under one player, in one god's colour; your own ({@code gauge}) also shows your PKP. */
	private void drawAura(Graphics2D g, Player who, Faction faction, int plane, long now, boolean gauge)
	{
		LocalPoint at = who.getLocalLocation();
		if (at == null)
		{
			return;
		}

		Polygon outer = ring(at, plane, RADIUS, 0.0);
		Polygon inner = ring(at, plane, (int) (RADIUS * 0.62), 0.0);
		if (outer == null || inner == null)
		{
			return;
		}

		double breath = 0.5 + 0.5 * Math.sin(now / PULSE_MS * 2 * Math.PI);
		Color c = faction.getPrimary();

		// your PKP, as the gauge reads it
		double pkp = RING_PKP;
		double max = RING_PKP;
		if (gauge)
		{
			max = Math.max(1, plugin.getMaxPkp());
			double sampleFill = look.sampleFill();
			pkp = sampleFill >= 0 ? sampleFill * max : plugin.getCurrentPkp();
		}
		final boolean negative = pkp < 0;
		if (negative)
		{
			c = grey(c);
		}

		Shape oldClip = g.getClip();
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// cut the character out, so the ring sits under them
		Shape hull = who.getConvexHull();
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

		if (!gauge)
		{
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
				Polygon arc = arc(at, plane, RADIUS, spin + k * 2 * Math.PI / 3, Math.PI / 7, 8, 0);
				if (arc != null)
				{
					g.drawPolyline(arc.xpoints, arc.ypoints, arc.npoints);
				}
			}
		}
		else
		{
			g.setStroke(new BasicStroke(1.2f));
			g.setColor(alpha(c, (int) (110 + 40 * breath)));
			g.draw(inner);

			// where "the top of the circle" is on screen, and which way clockwise runs, for this camera
			double[] top = topAndClockwise(at, plane);
			if (top != null)
			{
				// the first ring: 0-100 PKP (or how far below zero, in grey)
				double first = Math.min(1.0, Math.abs(pkp) / RING_PKP);
				gaugeRing(g, at, plane, 0, 1.0, first, c, breath, top);

				// the second ring, just above: the room past 100, and the PKP in it
				if (!negative && (max > RING_PKP || pkp > RING_PKP))
				{
					double room = Math.min(1.0, Math.max(0, max - RING_PKP) / RING_PKP);
					double extra = Math.min(1.0, Math.max(0, pkp - RING_PKP) / RING_PKP);
					Color c2 = pkp > max ? look.overchargeColor() : c;
					gaugeRing(g, at, plane, SECOND_RING_Z, Math.max(room, extra), extra, c2, breath, top);
				}
			}
		}

		g.setClip(oldClip);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
	}

	/**
	 * One ring of the PKP gauge, {@code z} above the ground: a faint track for {@code track} of the
	 * circle, then the glowing rim for {@code fill} of it, ending at the top and emptying clockwise.
	 */
	private void gaugeRing(Graphics2D g, LocalPoint at, int plane, int z, double track, double fill, Color c,
		double breath, double[] top)
	{
		final double a0 = top[0];
		final double cw = top[1];
		if (track > 0)
		{
			// the track runs clockwise from the top for as far as this ring can fill
			Polygon t = arc(at, plane, RADIUS, a0, cw * track * 2 * Math.PI, steps(track), z);
			if (t != null)
			{
				g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(alpha(c, 55));
				g.drawPolyline(t.xpoints, t.ypoints, t.npoints);
			}
		}
		if (fill <= 0.002)
		{
			return;
		}
		// full at the top; as it empties, its start creeps round clockwise towards the end of the track
		double start = a0 + cw * (track - fill) * 2 * Math.PI;
		Polygon f = arc(at, plane, RADIUS, start, cw * fill * 2 * Math.PI, steps(fill), z);
		if (f == null)
		{
			return;
		}
		g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(c, (int) (50 + 40 * breath)));
		g.drawPolyline(f.xpoints, f.ypoints, f.npoints);
		g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(c.brighter(), (int) (200 + 50 * breath)));
		g.drawPolyline(f.xpoints, f.ypoints, f.npoints);
		// a bright bead where the PKP runs out
		g.setColor(alpha(Color.WHITE, (int) (170 + 70 * breath)));
		g.fillOval(f.xpoints[0] - 3, f.ypoints[0] - 3, 6, 6);
	}

	private static int steps(double portion)
	{
		return Math.max(2, (int) Math.ceil(SEGMENTS * portion));
	}

	/**
	 * The angle of the ring's point nearest the top of the screen, and +1 or -1: the way angles run
	 * for clockwise on screen. Null when the ring is off screen.
	 */
	private double[] topAndClockwise(LocalPoint at, int plane)
	{
		Polygon r = ring(at, plane, RADIUS, 0.0);
		if (r == null)
		{
			return null;
		}
		int best = 0;
		for (int i = 1; i < SEGMENTS; i++)
		{
			if (r.ypoints[i] < r.ypoints[best])
			{
				best = i;
			}
		}
		int next = (best + 1) % SEGMENTS;
		int prev = (best + SEGMENTS - 1) % SEGMENTS;
		double dir = r.xpoints[next] >= r.xpoints[prev] ? 1 : -1;
		return new double[]{best * 2 * Math.PI / SEGMENTS, dir};
	}

	// ------------------------------------------------------------------ particles

	private static final int PARTICLES = 10;
	/** How far round the character a mote winds over its climb (or fall): a little over half a turn. */
	private static final double HELIX_TURN = Math.PI * 1.2;
	/** One slow turn of the whole set of particles, in milliseconds. */
	private static final double HELIX_SPIN_MS = 6000.0;
	private static final int PARTICLE_HEIGHT = 230;

	/** Each positive PKP effect running on you, as its own particles off the ring. */
	/**
	 * The particle effects, one per thing changing your magical energy. The protections and the freeze
	 * are calm and bright; a drink's PKP scales each look like what they do to the energy - spending
	 * less holds it close, gaining more draws it in, while gaining less (it's sapped to ash) and
	 * spending more (it bleeds away in red shards) are gritty and broken: Zamorak's kind of thing.
	 */
	enum Fx
	{
		FREEZE, OVERLOAD_PROOF, DEPLETION_PROOF,
		/** Spells cost less: energy held close, slow bright motes hugging the ring. */
		SPEND_LESS,
		/** Gains are bigger: bright motes drawn in from outside, spiralling into you. */
		GAIN_MORE,
		/** Gains are smaller: grey ash and dying embers flaking off and sinking. */
		GAIN_LESS,
		/** Spells cost more: dark red shards spitting out of you and falling away. */
		SPEND_MORE
	}

	/** Your own effects right now (the appearance editor's sample shows some of each). */
	private java.util.Set<Fx> ownEffects()
	{
		final java.util.EnumSet<Fx> out = java.util.EnumSet.noneOf(Fx.class);
		for (BattleMagePlugin.ActiveEffect fx : plugin.activeEffects())
		{
			if (fx.pending)
			{
				continue;
			}
			switch (fx.kind)
			{
				case FREEZE:
					out.add(Fx.FREEZE);
					break;
				case OVERLOAD_IMMUNE:
					out.add(Fx.OVERLOAD_PROOF);
					break;
				case DEPLETION_IMMUNE:
					out.add(Fx.DEPLETION_PROOF);
					break;
				default:
					break;
			}
		}
		if (look.sampleEffects())
		{
			out.add(Fx.SPEND_LESS);
			out.add(Fx.GAIN_LESS);
			return out;
		}
		addScales(out, plugin.gainScaleNow(), plugin.spendScaleNow());
		return out;
	}

	static void addScales(java.util.Set<Fx> out, double gain, double spend)
	{
		if (gain > 1.0)
		{
			out.add(Fx.GAIN_MORE);
		}
		else if (gain < 1.0)
		{
			out.add(Fx.GAIN_LESS);
		}
		if (spend < 1.0)
		{
			out.add(Fx.SPEND_LESS);
		}
		else if (spend > 1.0)
		{
			out.add(Fx.SPEND_MORE);
		}
	}

	/** A teammate's effects, from what they share. */
	private static java.util.Set<Fx> mateEffects(PartySync.Mate m)
	{
		final java.util.EnumSet<Fx> out = java.util.EnumSet.noneOf(Fx.class);
		if (m.has(BattleMagePartyUpdate.FX_FREEZE))
		{
			out.add(Fx.FREEZE);
		}
		if (m.has(BattleMagePartyUpdate.FX_OVERLOAD_PROOF))
		{
			out.add(Fx.OVERLOAD_PROOF);
		}
		if (m.has(BattleMagePartyUpdate.FX_DEPLETION_PROOF))
		{
			out.add(Fx.DEPLETION_PROOF);
		}
		if (m.has(BattleMagePartyUpdate.FX_SPEND_LESS))
		{
			out.add(Fx.SPEND_LESS);
		}
		if (m.has(BattleMagePartyUpdate.FX_SPEND_MORE))
		{
			out.add(Fx.SPEND_MORE);
		}
		if (m.has(BattleMagePartyUpdate.FX_GAIN_LESS))
		{
			out.add(Fx.GAIN_LESS);
		}
		if (m.has(BattleMagePartyUpdate.FX_GAIN_MORE))
		{
			out.add(Fx.GAIN_MORE);
		}
		return out;
	}

	private static final Color CONSERVE = new Color(185, 235, 255);
	private static final Color SIPHON = new Color(255, 228, 140);
	private static final Color ASH = new Color(112, 104, 96);
	private static final Color EMBER = new Color(170, 70, 30);
	private static final Color BLOOD = new Color(150, 18, 24);

	private void drawParticles(Graphics2D g, Player who, int plane, long now, java.util.Set<Fx> effects)
	{
		LocalPoint at = who.getLocalLocation();
		if (at == null || effects.isEmpty())
		{
			return;
		}
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// the whole set turns slowly, so the helix keeps moving round
		final double spin = (now % (long) HELIX_SPIN_MS) / HELIX_SPIN_MS * 2 * Math.PI;
		for (Fx fx : effects)
		{
			final int salt = fx.ordinal() * 7919;
			for (int i = 0; i < PARTICLES; i++)
			{
				final double seed = (i * 2.399963 + salt) % (2 * Math.PI);
				double angle = seed + spin;
				double phase;
				int radius = RADIUS;
				int z;
				switch (fx)
				{
					case OVERLOAD_PROOF:
						// motes lifting off the rim, winding round the character as they rise
						phase = phase(now, 1700, i);
						z = (int) (phase * PARTICLE_HEIGHT);
						angle += phase * HELIX_TURN;
						break;
					case DEPLETION_PROOF:
						// motes settling down onto the rim, winding the other way as they fall
						phase = phase(now, 1700, i);
						z = (int) ((1 - phase) * PARTICLE_HEIGHT);
						angle -= phase * HELIX_TURN;
						break;
					case FREEZE:
						// frost drifting slowly down and inward
						phase = phase(now, 3200, i);
						z = (int) ((1 - phase) * PARTICLE_HEIGHT * 0.7);
						radius = (int) (RADIUS * (1.15 - 0.35 * phase));
						angle += phase * 0.6;
						break;
					case SPEND_LESS:
						// energy held close: slow motes hugging just inside the rim, bobbing gently
						phase = phase(now, 3000, i);
						z = (int) (PARTICLE_HEIGHT * (0.12 + 0.18 * Math.sin(phase * 2 * Math.PI + i)));
						radius = (int) (RADIUS * 0.82);
						angle += phase * Math.PI * 0.5;
						break;
					case GAIN_MORE:
						// energy drawn in: from wide outside, spiralling inward and up into the character
						phase = phase(now, 1500, i);
						z = (int) (PARTICLE_HEIGHT * 0.65 * phase);
						radius = (int) (RADIUS * (1.9 - 1.6 * phase));
						angle += phase * HELIX_TURN * 1.4;
						break;
					case GAIN_LESS:
						// energy sapped to ash: flakes peeling off the character and sinking, drifting out
						phase = phase(now, 2600, i);
						z = (int) (PARTICLE_HEIGHT * (0.75 - 0.75 * phase));
						radius = (int) (RADIUS * (0.45 + 0.75 * phase));
						angle += jitter(i, now / 90) * 0.35 - phase * 0.5;
						break;
					default:
						// SPEND_MORE: energy bleeding away: shards spat outward from the chest, then falling
						phase = phase(now, 1100, i);
						radius = (int) (RADIUS * (0.3 + 1.3 * phase));
						z = (int) (PARTICLE_HEIGHT * (0.55 + 0.25 * phase - 0.95 * phase * phase));
						angle += jitter(i, now / 70) * 0.25;
						break;
				}
				LocalPoint lp = at.plus((int) Math.round(Math.cos(angle) * radius), (int) Math.round(Math.sin(angle) * radius));
				Point p = Perspective.localToCanvas(client, lp, plane, Math.max(0, z));
				if (p == null)
				{
					continue;
				}
				int a = (int) Math.round(230 * Math.sin(Math.PI * phase));
				if (a <= 4)
				{
					continue;
				}
				drawMote(g, fx, p.getX(), p.getY(), a, i, now);
			}
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
	}

	/** One particle of an effect, at (x, y), at alpha a. */
	private void drawMote(Graphics2D g, Fx fx, double x, double y, int a, int i, long now)
	{
		switch (fx)
		{
			case FREEZE:
				g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(alpha(Color.WHITE, a));
				g.draw(new java.awt.geom.Line2D.Double(x - 2, y, x + 2, y));
				g.draw(new java.awt.geom.Line2D.Double(x, y - 2, x, y + 2));
				g.setColor(alpha(plugin.effectColor(BattleMagePlugin.EffectKind.FREEZE), a / 2));
				g.fill(new java.awt.geom.Ellipse2D.Double(x - 2, y - 2, 4, 4));
				break;
			case OVERLOAD_PROOF:
				glowDot(g, look.overloadColor(), x, y, a);
				break;
			case DEPLETION_PROOF:
				glowDot(g, look.depletedColor(), x, y, a);
				break;
			case SPEND_LESS:
				glowDot(g, CONSERVE, x, y, a * 3 / 4);
				break;
			case GAIN_MORE:
				// a bright streak pointing where it's going (inward), with a hot core
				g.setColor(alpha(SIPHON, a / 3));
				g.fill(new java.awt.geom.Ellipse2D.Double(x - 3.2, y - 3.2, 6.4, 6.4));
				g.setColor(alpha(Color.WHITE, a));
				g.fill(new java.awt.geom.Ellipse2D.Double(x - 1.35, y - 1.35, 2.7, 2.7));
				break;
			case GAIN_LESS:
			{
				// a ragged flake of ash, flickering; now and then a dying ember in it
				final boolean ember = (i % 3) == 0;
				final int flick = (int) (a * (0.55 + 0.45 * jitter(i * 7, now / 60)));
				g.setColor(alpha(Color.BLACK, flick / 2));
				g.fill(shard(x + 0.6, y + 0.6, 2.6, i, now / 220));
				g.setColor(alpha(ember ? EMBER : ASH, flick));
				g.fill(shard(x, y, 2.4, i, now / 220));
				break;
			}
			default:
			{
				// SPEND_MORE: a jagged shard of dark red with a black edge, jittering
				final int flick = (int) (a * (0.6 + 0.4 * jitter(i * 5, now / 45)));
				final Shape sh = shard(x, y, 2.9, i * 3 + 1, now / 120);
				g.setColor(alpha(BLOOD, flick));
				g.fill(sh);
				g.setStroke(new BasicStroke(0.8f));
				g.setColor(alpha(Color.BLACK, flick));
				g.draw(sh);
				break;
			}
		}
	}

	/** The clean kind of mote: a soft glow round a bright core, two-thirds the old size. */
	private static void glowDot(Graphics2D g, Color c, double x, double y, int a)
	{
		g.setColor(alpha(c, a / 3));
		g.fill(new java.awt.geom.Ellipse2D.Double(x - 2.65, y - 2.65, 5.3, 5.3));
		g.setColor(alpha(c.brighter(), a));
		g.fill(new java.awt.geom.Ellipse2D.Double(x - 1.35, y - 1.35, 2.7, 2.7));
	}

	/** A small ragged triangle-ish shard, turning with {@code step}: the gritty effects' particle. */
	private static Shape shard(double x, double y, double r, int i, long step)
	{
		final java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
		final double turn = (i * 1.7 + step * 0.9) % (2 * Math.PI);
		for (int k = 0; k < 4; k++)
		{
			final double ang = turn + k * Math.PI / 2 + jitter(i * 13 + k, step) * 0.5;
			final double len = r * (k % 2 == 0 ? 1.15 : 0.55 + 0.35 * jitter(i + k, step));
			final double px = x + Math.cos(ang) * len;
			final double py = y + Math.sin(ang) * len;
			if (k == 0)
			{
				p.moveTo(px, py);
			}
			else
			{
				p.lineTo(px, py);
			}
		}
		p.closePath();
		return p;
	}

	/** A steady pseudo-random number in -1..1 for (i, step): changes only when step does. */
	private static double jitter(int i, long step)
	{
		long h = (i * 0x9E3779B97F4A7C15L) ^ (step * 0xC2B2AE3D27D4EB4FL);
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return ((h & 0xFFFF) / 32767.5) - 1.0;
	}

	/** Particle i's place in its loop, 0..1, staggered so they never move as one. */
	private static double phase(long now, double period, int i)
	{
		double p = (now / period) + i / (double) PARTICLES + (i * 0.37 % 1.0) * 0.5;
		return p - Math.floor(p);
	}

	private static Color grey(Color c)
	{
		int y = (int) Math.round(0.30 * c.getRed() + 0.59 * c.getGreen() + 0.11 * c.getBlue());
		y = Math.max(110, Math.min(220, y));
		return new Color(y, y, y);
	}

	/** A full ring of the given radius around a point, projected to the screen; null if off screen. */
	private Polygon ring(LocalPoint at, int plane, int radius, double start)
	{
		return arc(at, plane, radius, start, 2 * Math.PI, SEGMENTS, 0);
	}

	private Polygon arc(LocalPoint at, int plane, int radius, double start, double sweep, int steps, int z)
	{
		Polygon p = new Polygon();
		for (int i = 0; i <= steps; i++)
		{
			double a = start + sweep * i / steps;
			LocalPoint lp = at.plus((int) Math.round(Math.cos(a) * radius), (int) Math.round(Math.sin(a) * radius));
			Point s = Perspective.localToCanvas(client, lp, plane, z);
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
