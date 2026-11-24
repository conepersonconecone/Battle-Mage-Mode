package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member paid their respects at someone's death signpost: its owner sees it rise over them.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyRespects extends PartyMemberMessage
{
	/** The signpost owner's character name, as the signpost shows it. */
	String o;

	public BattleMagePartyRespects()
	{
	}

	BattleMagePartyRespects(String owner)
	{
		this.o = owner;
	}
}
