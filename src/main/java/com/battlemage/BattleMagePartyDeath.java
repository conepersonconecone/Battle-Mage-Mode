package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member died: teammates show their death card. (Members not playing Battle Mage Mode are
 * caught from their hitpoints reaching 0 instead.)
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyDeath extends PartyMemberMessage
{
	/** The tile they died on, for the signpost. */
	int x;
	int y;
	int pl;

	public BattleMagePartyDeath()
	{
	}

	BattleMagePartyDeath(net.runelite.api.coords.WorldPoint at)
	{
		if (at != null)
		{
			this.x = at.getX();
			this.y = at.getY();
			this.pl = at.getPlane();
		}
	}
}
