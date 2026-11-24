package com.battlemage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;

/**
 * Ringing the Cowbell amulet, seen.
 *
 * <ul>
 * <li>Every ring plays a hand-bell ringing on your character (the Slayer bell's), and on you on your
 * party members' screens too.</li>
 * <li>Past the faction-lock combat level, a ring anywhere summons your god's wizard (Saradomin wizard,
 * Zamorak wizard, the Brassican Mage for Guthix, the Ancient Wizard for Zaros): they appear on a free
 * tile within three of you and dance for a while, then are gone. Party members see them too. The
 * Faction-less summon no one.</li>
 * </ul>
 *
 * <p>All of it is drawn on the client only; nothing is sent to the game. Runs on the client thread.
 */
@Singleton
class CowbellRing
{
	/** Ringing a hand bell: the Slayer bell's animation (used on molanisks). */
	private static final int RING_ANIM = 6083;
	/** The Dance emote. */
	private static final int DANCE_ANIM = 866;
	/** Each god's wizard, whose model is borrowed: Saradomin wizard, Zamorak wizard, Brassican Mage, Ancient Wizard. */
	private static final java.util.Map<Faction, Integer> WIZARDS = new java.util.EnumMap<>(Faction.class);

	static
	{
		WIZARDS.put(Faction.SARADOMIN, 2955);
		WIZARDS.put(Faction.ZAMORAK, 2954);
		WIZARDS.put(Faction.GUTHIX, 7310);
		WIZARDS.put(Faction.ZAROS, 7307);
	}
	/** How far from the ringer he can appear, in tiles. */
	private static final int REACH = 3;
	/** How long he dances before vanishing. */
	private static final long DANCE_MS = 10_000L;
	/** The real Wise Old Man this close (in tiles) means no summoning: he's already here. */
	private static final int REAL_ONE_NEARBY = 6;

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService party;
	private final Oath oath;
	private final Random rng = new Random();

	/** One summoned wizard per ringer (you are ""), so ringing again moves them. */
	private final Map<String, Summon> summons = new HashMap<>();
	/** Each wizard's model, built the first time it's needed. */
	private final java.util.Map<Integer, Model> models = new HashMap<>();

	private static final class Summon
	{
		final RuneLiteObject obj;
		final WorldPoint at;
		final long untilMs;

		Summon(RuneLiteObject obj, WorldPoint at, long untilMs)
		{
			this.obj = obj;
			this.at = at;
			this.untilMs = untilMs;
		}
	}

	@Inject
	CowbellRing(Client client, ClientThread clientThread, PartyService party, Oath oath)
	{
		this.oath = oath;
		this.client = client;
		this.clientThread = clientThread;
		this.party = party;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!WiseOldMan.isRingingCowbell(event.getMenuEntry()))
		{
			return;
		}
		final Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null)
		{
			return;
		}
		ring(me);

		// a god's wizard answers; the Faction-less (and the unsworn) summon no one
		final Faction god = oath.isSworn() ? oath.getFaction() : null;
		WorldPoint spot = null;
		if (god != null && WIZARDS.containsKey(god) && me.getCombatLevel() > CombatGate.LOCK_COMBAT
			&& !realOneNearby(me.getWorldLocation()))
		{
			spot = freeTileNear(me.getWorldLocation());
			if (spot != null)
			{
				summon("", god, spot, me.getWorldLocation());
			}
		}
		if (party.isInParty() && party.getLocalMember() != null)
		{
			party.send(spot == null
				? new BattleMagePartyBell(false, null, 0, 0, 0)
				: new BattleMagePartyBell(true, god.name(), spot.getX(), spot.getY(), spot.getPlane()));
		}
	}

	/** A party member rang: play it on them here, and show their wizard if they summoned one. */
	@Subscribe
	public void onBattleMagePartyBell(BattleMagePartyBell e)
	{
		final PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		final PartyMember who = party.getMemberById(e.getMemberId());
		final String name = who == null ? null : who.getDisplayName();
		if (name == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			final Player ringer = findPlayer(name);
			if (ringer != null)
			{
				ring(ringer);
			}
			final Faction god = Faction.byName(e.f);
			if (e.w && god != null && WIZARDS.containsKey(god))
			{
				final WorldPoint spot = new WorldPoint(e.x, e.y, e.pl);
				summon(Text.standardize(name), god, spot, ringer == null ? null : ringer.getWorldLocation());
			}
		});
	}

	/** Every client tick: sends dancers home when their time is up, or when they leave the scene. */
	void update()
	{
		if (summons.isEmpty())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final List<String> done = new ArrayList<>();
		for (Map.Entry<String, Summon> s : summons.entrySet())
		{
			if (now >= s.getValue().untilMs || LocalPoint.fromWorld(client.getTopLevelWorldView(), s.getValue().at) == null)
			{
				s.getValue().obj.setActive(false);
				done.add(s.getKey());
			}
		}
		for (String k : done)
		{
			summons.remove(k);
		}
	}

	/** Plugin shutdown or logout: everyone goes home. */
	void clear()
	{
		for (Summon s : summons.values())
		{
			s.obj.setActive(false);
		}
		summons.clear();
	}

	private static void ring(Player p)
	{
		p.setAnimation(RING_ANIM);
		p.setAnimationFrame(0);
	}

	/** Shows {@code god}'s wizard dancing on {@code at}, facing {@code toward} if given. */
	private void summon(String key, Faction god, WorldPoint at, WorldPoint toward)
	{
		final WorldView wv = client.getTopLevelWorldView();
		final LocalPoint lp = wv == null ? null : LocalPoint.fromWorld(wv, at);
		if (lp == null || at.getPlane() != wv.getPlane())
		{
			return;
		}
		final int npcId = WIZARDS.get(god);
		Model model = models.get(npcId);
		if (model == null)
		{
			model = buildModel(npcId);
			if (model == null)
			{
				return;
			}
			models.put(npcId, model);
		}
		final Summon old = summons.remove(key);
		if (old != null)
		{
			old.obj.setActive(false);
		}
		final RuneLiteObject obj = client.createRuneLiteObject();
		obj.setModel(model);
		final Animation dance = client.loadAnimation(DANCE_ANIM);
		if (dance != null)
		{
			obj.setAnimation(dance);
			obj.setShouldLoop(true);
		}
		obj.setLocation(lp, at.getPlane());
		if (toward != null)
		{
			// the game's orientation: 0 faces south, 512 west, 1024 north, 1536 east
			final int dx = toward.getX() - at.getX();
			final int dy = toward.getY() - at.getY();
			if (dx != 0 || dy != 0)
			{
				obj.setOrientation((int) Math.round(Math.atan2(-dx, -dy) * 1024 / Math.PI) & 2047);
			}
		}
		obj.setActive(true);
		summons.put(key, new Summon(obj, at, System.currentTimeMillis() + DANCE_MS));
	}

	/** A random free tile within {@link #REACH} of {@code from}, not on it; null if there is none. */
	private WorldPoint freeTileNear(WorldPoint from)
	{
		final WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		final CollisionData[] maps = wv.getCollisionMaps();
		final int[][] flags = maps == null || from.getPlane() >= maps.length || maps[from.getPlane()] == null
			? null : maps[from.getPlane()].getFlags();
		final List<WorldPoint> options = new ArrayList<>();
		for (int dx = -REACH; dx <= REACH; dx++)
		{
			for (int dy = -REACH; dy <= REACH; dy++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				final WorldPoint p = from.dx(dx).dy(dy);
				final LocalPoint lp = LocalPoint.fromWorld(wv, p);
				if (lp == null)
				{
					continue;
				}
				if (flags != null)
				{
					final int f = flags[lp.getSceneX()][lp.getSceneY()];
					if ((f & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
						| CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION | CollisionDataFlag.BLOCK_MOVEMENT_OBJECT)) != 0)
					{
						continue;
					}
				}
				options.add(p);
			}
		}
		return options.isEmpty() ? null : options.get(rng.nextInt(options.size()));
	}

	/** The real Wise Old Man is standing nearby (Draynor): no need to summon him. */
	private boolean realOneNearby(WorldPoint from)
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && WiseOldMan.NAME.equals(npc.getName()) && npc.getWorldLocation() != null
				&& npc.getWorldLocation().distanceTo(from) <= REAL_ONE_NEARBY)
			{
				return true;
			}
		}
		return false;
	}

	private Player findPlayer(String name)
	{
		final String want = Text.standardize(name);
		for (Player p : client.getPlayers())
		{
			if (p != null && p.getName() != null && Text.standardize(p.getName()).equals(want))
			{
				return p;
			}
		}
		return null;
	}

	private Model buildModel(int npcId)
	{
		final NPCComposition def = client.getNpcDefinition(npcId);
		if (def == null || def.getModels() == null || def.getModels().length == 0)
		{
			return null;
		}
		final int[] ids = def.getModels();
		final ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		ModelData data = parts.length == 1 ? parts[0] : client.mergeModels(parts);
		final short[] from = def.getColorToReplace();
		final short[] to = def.getColorToReplaceWith();
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
