package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member cast a Lunar support spell on a teammate: Heal Other ({@link #HEAL}) or Vengeance
 * Other ({@link #VENGEANCE}). The target applies the blessing to their own PKP.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyBlessing extends PartyMemberMessage
{
	static final int HEAL = 1;
	static final int VENGEANCE = 2;

	/** Which spell. */
	int k;
	/** The party member it was cast on. */
	long t;

	public BattleMagePartyBlessing()
	{
	}

	BattleMagePartyBlessing(int kind, long target)
	{
		this.k = kind;
		this.t = target;
	}
}
