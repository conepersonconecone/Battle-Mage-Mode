package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The party details RuneLite's own party data doesn't carry, sent by Battle Mage Mode so the side
 * panel's member views match the game: world, spellbook, poison, disease, stamina, and which prayers
 * are unlocked and usable. Sent when any of it changes and when someone joins.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyExtras extends PartyMemberMessage
{
	/** World, or 0 when logged out. */
	int w;
	/** Spellbook: 0 standard, 1 ancient, 2 lunar, 3 arceuus. */
	int sb = -1;
	/** Poison (a value of 1,000,000 or more is venom). */
	int p;
	/** Disease. */
	int d;
	/** A stamina potion is working. */
	boolean st;
	/** Usable prayers, bit-packed by Prayer ordinal. */
	Integer ap;
	/** Unlocked prayers, bit-packed by Prayer ordinal. */
	Integer up;

	public BattleMagePartyExtras()
	{
	}

	BattleMagePartyExtras(int world, int spellbook, int poison, int disease, boolean stamina, Integer ap, Integer up)
	{
		this.w = world;
		this.sb = spellbook;
		this.p = poison;
		this.d = disease;
		this.st = stamina;
		this.ap = ap;
		this.up = up;
	}

	boolean sameAs(BattleMagePartyExtras o)
	{
		return o != null && w == o.w && sb == o.sb && p == o.p && d == o.d && st == o.st
			&& java.util.Objects.equals(ap, o.ap) && java.util.Objects.equals(up, o.up);
	}
}
