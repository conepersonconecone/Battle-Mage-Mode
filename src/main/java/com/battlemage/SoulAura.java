package com.battlemage;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

/**
 * The Soul aura: the game's own Soul Wars glow (the one shown under a player who has just
 * respawned and is immune), recoloured to your god and kept under your feet. Only its opening
 * frames are played, slowly and back and forth, so it breathes rather than flares.
 *
 * <p>It is a client-side object: only you see it, and nothing is sent to the game. The glow model
 * and its animation come straight from the game cache, and only its one glow colour is swapped,
 * the same way the game itself makes its red, blue and green versions of this glow.
 *
 * <p>Everything here must run on the client thread.
 */
@Singleton
class SoulAura
{
	/** The glow disc shared by the Soul Wars immunity glow and the game's coloured attack glows. */
	private static final int GLOW_MODEL = 2318;
	/** The Soul Wars immunity glow's own animation. */
	private static final int GLOW_ANIM = 636;
	/** Only a few early frames of it are used (2 to 4, counting from 1): the glow before it flares. */
	private static final int FIRST_FRAME = 2;
	private static final int LAST_FRAME = 4;
	/** Each of those frames is held this many times its own length. */
	private static final int HOLD = 5;
	/** The one colour in the glow model, which the game swaps to make each variant. */
	private static final short GLOW_COLOUR = 11218;
	/** The Soul Wars glow's lighting: the cache's +60 contrast on top of the game's spot-anim base. */
	private static final int AMBIENT = 64;
	private static final int CONTRAST = 850 + 60;

	private final Client client;
	private final Oath oath;
	private final Appearance look;

	private RuneLiteObject glow;
	/** The god the current model was coloured for. */
	private Faction coloured;

	@Inject
	SoulAura(Client client, Oath oath, Appearance look)
	{
		this.client = client;
		this.oath = oath;
		this.look = look;
	}

	/** Called every client tick: shows, recolours, moves or hides the glow. */
	void update()
	{
		Faction faction = oath.getFaction();
		Player me = client.getLocalPlayer();
		if (look.auraMode() != AuraMode.SOUL || faction == null || me == null
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			hide();
			return;
		}
		LocalPoint at = me.getLocalLocation();
		if (at == null)
		{
			hide();
			return;
		}

		if (glow == null)
		{
			glow = client.createRuneLiteObject();
			Animation anim = client.loadAnimation(GLOW_ANIM);
			if (anim != null)
			{
				glow.setAnimationController(new GentlePulse(client, anim));
			}
		}
		if (faction != coloured)
		{
			Model m = buildModel(faction);
			if (m == null)
			{
				return;
			}
			glow.setModel(m);
			coloured = faction;
		}

		glow.setLocation(at, client.getTopLevelWorldView().getPlane());
		if (!glow.isActive())
		{
			glow.setActive(true);
		}
	}

	/** Takes the glow out of the world (it is rebuilt on the next update that wants it). */
	void hide()
	{
		if (glow != null && glow.isActive())
		{
			glow.setActive(false);
		}
	}

	/** Removes the glow for good, for plugin shutdown. */
	void remove()
	{
		hide();
		glow = null;
		coloured = null;
	}

	private Model buildModel(Faction faction)
	{
		ModelData data = client.loadModelData(GLOW_MODEL);
		if (data == null)
		{
			return null;
		}
		return data.cloneColors()
			.recolor(GLOW_COLOUR, glowColour(faction))
			.light(AMBIENT + 60, CONTRAST, -30, -50, -30);
	}

	/**
	 * The glow colour for each god. Saradomin, Zamorak and Guthix use the exact colours the game
	 * gives its own blue, red and green versions of this glow; Zaros gets a purple to match.
	 */
	private static short glowColour(Faction faction)
	{
		switch (faction)
		{
			case SARADOMIN:
				return (short) 42572;
			case ZAMORAK:
				return (short) 964;
			case GUTHIX:
				return (short) 19123;
			case FACTIONLESS:
				// gold, like the toolbar icon before an oath
				return JagexColor.packHSL(8, 6, 72);
			case ZAROS:
			default:
				return JagexColor.packHSL(47, 5, 62);
		}
	}

	/**
	 * Plays frames 2 to 4 of the glow forwards and back, each held 5 times as long:
	 * 2 -> 3 -> 4 -> 3 -> 2 -> ... - a slow, gentle breath instead of the full flare.
	 */
	private static final class GentlePulse extends AnimationController
	{
		private final Animation anim;
		private int dir = 1;
		private int elapsed;
		private int frame;

		GentlePulse(Client client, Animation anim)
		{
			super(client, anim);
			this.anim = anim;
		}

		@Override
		public void tick(int ticks)
		{
			if (anim == null)
			{
				return;
			}
			int[] lengths = anim.getFrameLengths();
			if (anim.isMayaAnim() || lengths == null || lengths.length == 0)
			{
				super.tick(ticks);
				return;
			}
			// frame numbers here count from 0, so frame 2 is index 1
			int last = Math.min(LAST_FRAME, lengths.length) - 1;
			int first = Math.min(FIRST_FRAME - 1, last);
			int f = Math.max(first, Math.min(last, frame));
			elapsed += ticks;
			while (last > first && elapsed >= HOLD * Math.max(1, lengths[f]))
			{
				elapsed -= HOLD * Math.max(1, lengths[f]);
				f += dir;
				if (f >= last)
				{
					f = last;
					dir = -1;
				}
				else if (f <= first)
				{
					f = first;
					dir = 1;
				}
			}
			frame = f;
			setFrame(f);
		}
	}
}
