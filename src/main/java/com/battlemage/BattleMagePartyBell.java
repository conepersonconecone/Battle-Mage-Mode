package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party member rang their Cowbell amulet: teammates play the ringing on them and, when it summoned
 * their god's wizard, show the wizard dancing on the same tile.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyBell extends PartyMemberMessage
{
	/** A wizard came: dance at (x, y, pl). */
	boolean w;
	/** Whose wizard: the ringer's faction (enum name). */
	String f;
	int x;
	int y;
	int pl;

	public BattleMagePartyBell()
	{
	}

	BattleMagePartyBell(boolean summoned, String faction, int x, int y, int plane)
	{
		this.w = summoned;
		this.f = faction;
		this.x = x;
		this.y = y;
		this.pl = plane;
	}
}
