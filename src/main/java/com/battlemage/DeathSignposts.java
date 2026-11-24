package com.battlemage;

import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.Subscribe;

/**
 * Where a party member died: a signpost (the Exam Centre's) drops out of the sky onto the tile, stands
 * there for {@link #LIFE_MS five minutes}, then crumbles away. Teammates can right-click it and Pay
 * respects (up to ten times each), which its owner sees rise over their head. Right-click, Examine says who perished
 * there; your own signpost can also be destroyed, which crumbles it for the whole party. Drawn by each party member's client on their own screen; nothing is sent to the game.
 * Runs on the client thread.
 */
@Singleton
class DeathSignposts
{
	/** The Exam Centre signpost's model (loc 2371). */
	private static final int SIGNPOST_MODEL = 1449;
	/** How long a signpost stands. */
	static final long LIFE_MS = 300_000L;
	/** How long it takes to fall, and how high it falls from (scene units; 128 = one tile). */
	private static final long FALL_MS = 420L;
	private static final int FALL_HEIGHT = 900;
	/** The crumbling at the end. */
	private static final long CRUMBLE_MS = 1600L;
	private static final int CRUMBLE_STEPS = 6;
	/** No more than this many standing at once (the oldest crumbles first). */
	private static final int MAX = 12;

	private final Client client;
	private final List<Sign> signs = new ArrayList<>();
	/** Told when you destroy your own signpost, so the party's crumble too. */
	private java.util.function.Consumer<WorldPoint> onDestroy = at -> { };
	private java.util.function.Consumer<String> onRespects = name -> { };
	/** How many times you may pay your respects at one signpost. */
	private static final int MAX_RESPECTS = 10;
	/** The signpost, then the same crumbling step by step (more see-through, a little shorter). */
	private Model[] models;

	private static final class Sign
	{
		final String name;
		final WorldPoint at;
		final long bornMs;
		/** Your own signpost: only you can destroy it. */
		final boolean mine;
		/** When it starts crumbling: the end of its life, or sooner if destroyed. */
		long crumbleAtMs;
		RuneLiteObject obj;
		int step = -1;
		/** How many times you've paid your respects here (up to {@link #MAX_RESPECTS}). */
		int paid;

		Sign(String name, WorldPoint at, long bornMs, boolean mine)
		{
			this.name = name;
			this.at = at;
			this.bornMs = bornMs;
			this.mine = mine;
			this.crumbleAtMs = bornMs + LIFE_MS;
		}
	}

	@Inject
	DeathSignposts(Client client)
	{
		this.client = client;
	}

	/** How a "Pay respects" reaches the signpost's owner: given their name. */
	void setOnRespects(java.util.function.Consumer<String> r)
	{
		onRespects = r == null ? name -> { } : r;
	}

	void setOnDestroy(java.util.function.Consumer<WorldPoint> r)
	{
		onDestroy = r == null ? at -> { } : r;
	}

	/**
	 * {@code name} died on {@code at}: drop a signpost there. {@code mine} for your own death (you can
	 * destroy it). Client thread.
	 */
	void spawn(String name, WorldPoint at, boolean mine)
	{
		if (at == null || name == null)
		{
			return;
		}
		for (Sign s : signs)
		{
			if (s.at.equals(at) && s.name.equals(name))
			{
				remove(s);
				signs.remove(s);
				break;
			}
		}
		while (signs.size() >= MAX)
		{
			remove(signs.remove(0));
		}
		signs.add(new Sign(name, at, System.currentTimeMillis(), mine));
	}

	/** A teammate destroyed their signpost on {@code at}: it crumbles here too. Client thread. */
	void destroyed(String name, WorldPoint at)
	{
		for (Sign s : signs)
		{
			if (!s.mine && s.at.equals(at) && (name == null || s.name.equalsIgnoreCase(name)))
			{
				crumbleNow(s);
			}
		}
	}

	/** Starts the crumbling now (it plays the same as at the end of its life). */
	private static void crumbleNow(Sign s)
	{
		final long now = System.currentTimeMillis();
		if (s.crumbleAtMs > now)
		{
			// a signpost still falling finishes landing first
			s.crumbleAtMs = Math.max(now, s.bornMs + FALL_MS + 160);
		}
	}

	/** Every client tick: the fall, keeping each one in the scene, and the crumbling at the end. */
	void update()
	{
		if (signs.isEmpty())
		{
			return;
		}
		final WorldView wv = client.getTopLevelWorldView();
		final long now = System.currentTimeMillis();
		for (int i = signs.size() - 1; i >= 0; i--)
		{
			final Sign s = signs.get(i);
			final long age = now - s.bornMs;
			final long crumbling = now - s.crumbleAtMs;
			if (crumbling >= CRUMBLE_MS)
			{
				remove(s);
				signs.remove(i);
				continue;
			}
			final LocalPoint lp = wv == null ? null : LocalPoint.fromWorld(wv, s.at);
			if (lp == null || s.at.getPlane() != wv.getPlane())
			{
				// out of the loaded scene (or on another floor): hidden until back in view
				remove(s);
				continue;
			}
			if (models == null && (models = buildModels()) == null)
			{
				return;
			}
			if (s.obj == null)
			{
				s.obj = client.createRuneLiteObject();
				s.obj.setLocation(lp, s.at.getPlane());
				s.obj.setOrientation(0);
				s.step = -1;
			}
			final int step = crumbling < 0 ? 0
				: 1 + (int) Math.min(CRUMBLE_STEPS - 1, crumbling * CRUMBLE_STEPS / CRUMBLE_MS);
			if (step != s.step)
			{
				s.obj.setModel(models[Math.min(step, models.length - 1)]);
				s.step = step;
			}
			final int ground = Perspective.getTileHeight(client, lp, s.at.getPlane());
			int lift = 0;
			if (age < FALL_MS)
			{
				// falling fast: gravity, so it speeds up as it comes down
				final double t = age / (double) FALL_MS;
				lift = (int) Math.round(FALL_HEIGHT * (1 - t * t));
			}
			else if (age < FALL_MS + 160)
			{
				// a small bounce as it bites into the ground
				final double t = (age - FALL_MS) / 160.0;
				lift = (int) Math.round(18 * Math.sin(t * Math.PI));
			}
			if (crumbling >= 0)
			{
				// crumbling: it sinks as it goes
				lift = -(int) Math.round(60 * crumbling / (double) CRUMBLE_MS);
			}
			// heights grow downwards in the scene: higher up is a smaller number
			s.obj.setZ(ground - lift);
			if (!s.obj.isActive())
			{
				s.obj.setActive(true);
			}
		}
	}

	/** Right-clicking a signpost offers Examine: "<name> perished on this tile." */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (signs.isEmpty() || e.getType() != MenuAction.WALK.getId())
		{
			return;
		}
		final Point mouse = client.getMouseCanvasPosition();
		final WorldView wv = client.getTopLevelWorldView();
		if (mouse == null || wv == null)
		{
			return;
		}
		for (Sign s : signs)
		{
			if (s.obj == null || !s.obj.isActive() || s.obj.getModel() == null)
			{
				continue;
			}
			final Shape box = Perspective.getClickbox(client, wv, s.obj.getModel(), s.obj.getOrientation(),
				s.obj.getX(), s.obj.getY(), s.obj.getZ());
			if (box == null || !box.contains(mouse.getX(), mouse.getY()))
			{
				continue;
			}
			final String text = s.name + " perished on this tile.";
			if (s.mine && s.crumbleAtMs > System.currentTimeMillis())
			{
				final Sign sign = s;
				client.getMenu().createMenuEntry(-1)
					.setOption("Destroy")
					.setTarget("<col=00ffff>Signpost</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(c ->
					{
						crumbleNow(sign);
						onDestroy.accept(sign.at);
					});
			}
			if (!s.mine && s.paid < MAX_RESPECTS && s.crumbleAtMs > System.currentTimeMillis())
			{
				final Sign sign = s;
				client.getMenu().createMenuEntry(-1)
					.setOption("Pay respects")
					.setTarget("<col=00ffff>Signpost</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(c ->
					{
						sign.paid++;
						onRespects.accept(sign.name);
						client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "You pay your respects to " + sign.name + ".", null);
					});
			}
			final MenuEntry entry = client.getMenu().createMenuEntry(-1)
				.setOption("Examine")
				.setTarget("<col=00ffff>Signpost</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(c -> client.addChatMessage(ChatMessageType.OBJECT_EXAMINE, "", text, null));
			entry.setDeprioritized(false);
		}
	}

	/** Plugin shutdown, logout or a new party: every signpost goes. */
	void clear()
	{
		for (Sign s : signs)
		{
			remove(s);
		}
		signs.clear();
	}

	private static void remove(Sign s)
	{
		if (s.obj != null)
		{
			s.obj.setActive(false);
			s.obj = null;
			s.step = -1;
		}
	}

	/** The signpost lit, and its crumbling steps: each a little more see-through and a little shorter. */
	private Model[] buildModels()
	{
		final ModelData base = client.loadModelData(SIGNPOST_MODEL);
		if (base == null)
		{
			return null;
		}
		final Model[] out = new Model[CRUMBLE_STEPS + 1];
		out[0] = base.light();
		for (int i = 1; i <= CRUMBLE_STEPS; i++)
		{
			final double t = i / (double) (CRUMBLE_STEPS + 1);
			final ModelData d = base.shallowCopy().cloneVertices().cloneTransparencies(true);
			final float[] ys = d.getVerticesY();
			for (int v = 0; v < ys.length; v++)
			{
				ys[v] = (float) (ys[v] * (1 - 0.35 * t));
			}
			final byte[] alpha = d.getFaceTransparencies();
			for (int f = 0; f < alpha.length; f++)
			{
				alpha[f] = (byte) Math.min(254, (int) Math.round(254 * t));
			}
			out[i] = d.light();
		}
		return out;
	}
}
