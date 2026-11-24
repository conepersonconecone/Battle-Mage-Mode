package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member was just ordained into a faction (chosen at the Wise Old Man or from the side
 * panel): teammates play the same ordination on them.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyOath extends PartyMemberMessage
{
	/** The faction's enum name. */
	String f;

	public BattleMagePartyOath()
	{
	}

	BattleMagePartyOath(String faction)
	{
		this.f = faction;
	}
}
