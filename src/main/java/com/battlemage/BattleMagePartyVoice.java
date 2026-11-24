package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The party leader's voice chat link (a Discord invite), or empty to clear it.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyVoice extends PartyMemberMessage
{
	/** The link, or empty for none. */
	String l;

	public BattleMagePartyVoice()
	{
	}

	BattleMagePartyVoice(String link)
	{
		this.l = link;
	}
}
