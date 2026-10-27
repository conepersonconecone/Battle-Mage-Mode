package com.battlemage;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;

/**
 * After dying to Brutus without a Cowbell amulet: you respawn dressed as a clown, do the Jig, then
 * sit down, and stay sat until you move, click to do something, or start another animation.
 *
 * <p>Client-side only: only you see it, nothing is sent to the game, and your real outfit is put
 * back the moment it ends. Everything here runs on the client thread.
 */
@Singleton
class ClownRespawn
{
	/** The Jig emote. */
	private static final int JIG = 2106;
	/** The Sit emote: sitting down, then the held sit. */
	private static final int SIT_DOWN = 10053;
	private static final int SIT_LOOP = 10061;

	/** The Halloween clown costume, and the body parts each piece covers. */
	private static final int CLOWN_MASK = 22689;
	private static final int CLOWN_BOW_TIE = 22692;
	private static final int CLOWN_GOWN = 22695;
	private static final int CLOWN_TROUSERS = 22698;
	private static final int CLOWN_SHOES = 22701;

	/**
	 * Game ticks to wait after the respawn before starting, so the game's own respawn updates (the
	 * outfit after items are lost, the teleport) land first and don't cut it short.
	 */
	private static final int SETTLE_TICKS = 2;

	/** Waiting longer than this for the respawn means it isn't coming (logged out, say). */
	private static final long ARM_TIMEOUT_MS = 60_000L;

	private enum Stage
	{
		IDLE, ARMED, RESPAWNED, JIG, SIT_DOWN, SITTING
	}

	private final Client client;

	private Stage stage = Stage.IDLE;
	private long armedAtMs;
	private int settle;
	private WorldPoint spot;
	/** The outfit as the game last sent it, put back when this ends. */
	private int[] realOutfit;
	/** The clown outfit as applied, to tell whether the game has since sent a new one. */
	private int[] clownOutfit;

	@Inject
	ClownRespawn(Client client)
	{
		this.client = client;
	}

	/** Brutus has just killed you: play this once you respawn. */
	void arm()
	{
		stop();
		stage = Stage.ARMED;
		armedAtMs = System.currentTimeMillis();
	}

	boolean armed()
	{
		return stage == Stage.ARMED;
	}

	/** Hitpoints are back up: you have respawned. It starts a couple of game ticks later, once you have landed. */
	void respawned()
	{
		if (stage == Stage.ARMED)
		{
			stage = Stage.RESPAWNED;
			settle = SETTLE_TICKS;
		}
	}

	/** Every game tick: starts the routine a tick after the respawn, and gives up on a respawn that never came. */
	void onGameTick()
	{
		if (stage == Stage.ARMED && System.currentTimeMillis() - armedAtMs > ARM_TIMEOUT_MS)
		{
			stage = Stage.IDLE;
			return;
		}
		if (stage != Stage.RESPAWNED || --settle > 0)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null)
		{
			return;
		}
		spot = me.getWorldLocation();
		dressAsClown(me);
		play(me, JIG);
		stage = Stage.JIG;
	}

	/** Every client tick: jig, then sit down, then hold the sit, until something else happens. */
	void update()
	{
		if (stage != Stage.JIG && stage != Stage.SIT_DOWN && stage != Stage.SITTING)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			stop();
			return;
		}
		// moved off the respawn spot, or the game sent a new outfit (equipping something)
		if (spot == null || !spot.equals(me.getWorldLocation()) || outfitReplaced(me))
		{
			stop();
			return;
		}
		int expected = stage == Stage.JIG ? JIG : stage == Stage.SIT_DOWN ? SIT_DOWN : SIT_LOOP;
		int anim = me.getAnimation();
		if (anim == expected)
		{
			return;
		}
		if (anim != -1)
		{
			// any other animation (eating, an emote, an attack) ends it
			stop();
			return;
		}
		// the current part finished: on to the next
		if (stage == Stage.JIG)
		{
			play(me, SIT_DOWN);
			stage = Stage.SIT_DOWN;
		}
		else
		{
			play(me, SIT_LOOP);
			stage = Stage.SITTING;
		}
	}

	/** A click that does something in the world ends it. Opening a tab or panel does not. */
	void onMenuAction(MenuAction action)
	{
		if (stage == Stage.IDLE || stage == Stage.ARMED || stage == Stage.RESPAWNED || action == null)
		{
			return;
		}
		switch (action)
		{
			case WALK:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_PLAYER:
			case WIDGET_TARGET_ON_WIDGET:
				stop();
				break;
			default:
				break;
		}
	}

	/** Ends it now: the real outfit goes back on and the sit is dropped. */
	void stop()
	{
		Player me = client.getLocalPlayer();
		boolean showing = stage == Stage.JIG || stage == Stage.SIT_DOWN || stage == Stage.SITTING;
		if (me != null && showing)
		{
			int anim = me.getAnimation();
			if (anim == JIG || anim == SIT_DOWN || anim == SIT_LOOP)
			{
				me.setAnimation(-1);
			}
			restoreOutfit(me);
		}
		stage = Stage.IDLE;
		spot = null;
		realOutfit = null;
		clownOutfit = null;
	}

	private static void play(Player me, int anim)
	{
		me.setAnimation(anim);
		me.setAnimationFrame(0);
	}

	private void dressAsClown(Player me)
	{
		PlayerComposition look = me.getPlayerComposition();
		if (look == null || look.getEquipmentIds() == null)
		{
			return;
		}
		int[] ids = look.getEquipmentIds();
		realOutfit = ids.clone();
		wear(ids, KitType.HEAD, CLOWN_MASK);
		ids[KitType.HAIR.getIndex()] = 0;
		ids[KitType.JAW.getIndex()] = 0;
		wear(ids, KitType.AMULET, CLOWN_BOW_TIE);
		wear(ids, KitType.TORSO, CLOWN_GOWN);
		ids[KitType.ARMS.getIndex()] = 0;
		wear(ids, KitType.LEGS, CLOWN_TROUSERS);
		wear(ids, KitType.BOOTS, CLOWN_SHOES);
		look.setHash();
		clownOutfit = ids.clone();
	}

	private static void wear(int[] ids, KitType slot, int itemId)
	{
		ids[slot.getIndex()] = itemId + PlayerComposition.ITEM_OFFSET;
	}

	/** True once the game has sent an outfit of its own since the clown one went on. */
	private boolean outfitReplaced(Player me)
	{
		if (clownOutfit == null)
		{
			return false;
		}
		PlayerComposition look = me.getPlayerComposition();
		return look == null || !java.util.Arrays.equals(look.getEquipmentIds(), clownOutfit);
	}

	/** Puts the real outfit back, unless the game has already sent a newer one. */
	private void restoreOutfit(Player me)
	{
		PlayerComposition look = me.getPlayerComposition();
		if (look == null || realOutfit == null || outfitReplaced(me))
		{
			return;
		}
		int[] ids = look.getEquipmentIds();
		System.arraycopy(realOutfit, 0, ids, 0, Math.min(ids.length, realOutfit.length));
		look.setHash();
	}
}
