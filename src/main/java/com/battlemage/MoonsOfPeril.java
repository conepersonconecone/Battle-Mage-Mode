package com.battlemage;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * The Moons of Peril (Neypotzli). While you are in a Moon's chamber, your PKP follows that Moon:
 * <ul>
 * <li>Eclipse Moon: PKP gains land at a fifth of their size and other losses at a quarter; spell
 * costs are paid in full.</li>
 * <li>Blood Moon: PKP drains away, 5 a second, and every hit you take costs that much PKP too;
 * both stop at empty (they never deplete you).</li>
 * <li>Blue Moon: PKP is frozen for as long as you are there.</li>
 * </ul>
 *
 * <p>The Eclipse Moon's chamber is a map region of its own. The Blood and Blue chambers share their
 * regions with the caverns next to them, so those two are found by their Moon standing nearby
 * instead. Everything here runs on the client thread.
 */
@Singleton
class MoonsOfPeril
{
	/** The Eclipse Moon's chamber: a whole map region of its own. */
	private static final int ECLIPSE_REGION = 6038;
	/** How close (in tiles) a Moon has to be for you to count as in its chamber. */
	private static final int CHAMBER_REACH = 20;

	/** Blood Moon drain, in PKP a second. */
	static final double BLOOD_DRAIN_PER_SECOND = 5.0;
	/** Eclipse Moon: the share of each gain that lands. */
	static final double ECLIPSE_GAIN_SCALE = 0.20;
	/** Eclipse Moon: the share of each loss that lands, except spell costs, which are paid in full. */
	static final double ECLIPSE_LOSS_SCALE = 0.25;

	enum Moon
	{
		NONE(null, null, null),
		BLOOD("Blood Moon", new Color(170, 12, 24), new Color(255, 70, 70)),
		BLUE("Blue Moon", new Color(70, 150, 255), new Color(225, 245, 255)),
		ECLIPSE("Eclipse Moon", new Color(20, 14, 30), new Color(255, 196, 70));

		/** The boss's name in game. */
		final String npcName;
		/** The border's base colour and its highlight. */
		final Color base;
		final Color glow;

		Moon(String npcName, Color base, Color glow)
		{
			this.npcName = npcName;
			this.base = base;
			this.glow = glow;
		}
	}

	private final Client client;

	@Inject
	MoonsOfPeril(Client client)
	{
		this.client = client;
	}

	/** Which Moon's chamber you are in right now, or NONE. */
	Moon detect()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return Moon.NONE;
		}
		WorldPoint at = me.getWorldLocation();
		if (at == null)
		{
			return Moon.NONE;
		}
		if (at.getRegionID() == ECLIPSE_REGION)
		{
			return Moon.ECLIPSE;
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || npc.getName() == null)
			{
				continue;
			}
			Moon m = byName(npc.getName());
			if (m == Moon.NONE)
			{
				continue;
			}
			WorldPoint there = npc.getWorldLocation();
			if (there != null && there.getPlane() == at.getPlane()
				&& Math.max(Math.abs(there.getX() - at.getX()), Math.abs(there.getY() - at.getY())) <= CHAMBER_REACH)
			{
				return m;
			}
		}
		return Moon.NONE;
	}

	private static Moon byName(String name)
	{
		for (Moon m : Moon.values())
		{
			if (m.npcName != null && m.npcName.equalsIgnoreCase(name))
			{
				return m;
			}
		}
		return Moon.NONE;
	}
}
