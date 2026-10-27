package com.battlemage;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.Hooks;

/**
 * Dying beside Brutus without a Cowbell amulet: Brutus turns into a ring of Demonic Brutuses that
 * run circles around the spot where you fell, for up to five seconds, ending the moment you respawn.
 *
 * <p>Client-side only: only you see it, and nothing is sent to the game. The real Brutus is hidden
 * while it plays, so he seems to become them. Everything here runs on the client thread.
 */
@Singleton
class DemonicBrutus
{
	/** Demonic Brutus, whose model and run cycle are borrowed from the game cache. */
	private static final int DEMONIC_NPC = 15628;
	/** Demonic Brutus's walk animation. */
	private static final int RUN_ANIM = 13782;
	/** How many circle the player. */
	private static final int COUNT = 6;
	/** How far from the player they run, in local units (128 a tile). */
	private static final int RADIUS = 320;
	/** One full lap takes this long. */
	private static final long LAP_MS = 1800L;
	/** How long the whole thing lasts. */
	private static final long DURATION_MS = 5000L;

	private final Client client;
	private final Hooks hooks;
	private final Hooks.RenderableDrawListener hideBrutus = this::shouldDraw;

	private final List<RuneLiteObject> ring = new ArrayList<>();
	private long startMs;
	private LocalPoint centre;
	private int plane;

	@Inject
	DemonicBrutus(Client client, Hooks hooks)
	{
		this.client = client;
		this.hooks = hooks;
	}

	void start()
	{
		hooks.registerRenderableDrawListener(hideBrutus);
	}

	void stop()
	{
		hooks.unregisterRenderableDrawListener(hideBrutus);
		clear();
	}

	/** Is this NPC Brutus (or Demonic Brutus)? */
	static boolean isBrutus(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}
		String name = npc.getName();
		return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("brutus");
	}

	boolean playing()
	{
		return !ring.isEmpty();
	}

	/** Starts the ring around {@code at}. Does nothing if one is already running. */
	void trigger(LocalPoint at, int level)
	{
		if (at == null || playing())
		{
			return;
		}
		Model model = buildModel();
		if (model == null)
		{
			return;
		}
		Animation run = client.loadAnimation(RUN_ANIM);
		centre = at;
		plane = level;
		startMs = System.currentTimeMillis();
		for (int i = 0; i < COUNT; i++)
		{
			RuneLiteObject o = client.createRuneLiteObject();
			o.setModel(model);
			if (run != null)
			{
				o.setAnimation(run);
				o.setShouldLoop(true);
			}
			ring.add(o);
		}
		update();
		for (RuneLiteObject o : ring)
		{
			o.setActive(true);
		}
	}

	/** Every client tick: moves each one along the circle, facing the way it runs, then ends it. */
	void update()
	{
		if (ring.isEmpty())
		{
			return;
		}
		long elapsed = System.currentTimeMillis() - startMs;
		WorldView wv = client.getTopLevelWorldView();
		if (elapsed >= DURATION_MS || centre == null || wv == null)
		{
			clear();
			return;
		}
		double spin = 2 * Math.PI * (elapsed % LAP_MS) / LAP_MS;
		for (int i = 0; i < ring.size(); i++)
		{
			double a = spin + 2 * Math.PI * i / ring.size();
			int x = centre.getX() + (int) Math.round(Math.cos(a) * RADIUS);
			int y = centre.getY() + (int) Math.round(Math.sin(a) * RADIUS);
			RuneLiteObject o = ring.get(i);
			o.setLocation(new LocalPoint(x, y, wv), plane);
			// running anticlockwise: the heading is the circle's tangent, turned into the game's
			// 0-2047 orientation (0 faces south, 512 west, 1024 north, 1536 east)
			double dx = -Math.sin(a);
			double dy = Math.cos(a);
			o.setOrientation((int) Math.round(Math.atan2(-dx, -dy) * 1024 / Math.PI) & 2047);
		}
	}

	/** Ends the ring early: the player has respawned. */
	void cancel()
	{
		clear();
	}

	private void clear()
	{
		for (RuneLiteObject o : ring)
		{
			o.setActive(false);
		}
		ring.clear();
		centre = null;
	}

	/** The real Brutus is not drawn while his demonic ring is out. */
	private boolean shouldDraw(Renderable renderable, boolean drawingUi)
	{
		return !(playing() && renderable instanceof NPC && isBrutus((NPC) renderable));
	}

	private Model buildModel()
	{
		NPCComposition def = client.getNpcDefinition(DEMONIC_NPC);
		if (def == null || def.getModels() == null || def.getModels().length == 0)
		{
			return null;
		}
		int[] ids = def.getModels();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		ModelData data = parts.length == 1 ? parts[0] : client.mergeModels(parts);
		short[] from = def.getColorToReplace();
		short[] to = def.getColorToReplaceWith();
		if (from != null && to != null)
		{
			data = data.cloneColors();
			for (int i = 0; i < Math.min(from.length, to.length); i++)
			{
				data.recolor(from[i], to[i]);
			}
		}
		return data.light();
	}
}
