package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member destroyed their own death signpost: teammates crumble it too.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartySignDestroy extends PartyMemberMessage
{
	/** The signpost's tile. */
	int x;
	int y;
	int pl;

	public BattleMagePartySignDestroy()
	{
	}

	BattleMagePartySignDestroy(net.runelite.api.coords.WorldPoint at)
	{
		this.x = at.getX();
		this.y = at.getY();
		this.pl = at.getPlane();
	}
}
