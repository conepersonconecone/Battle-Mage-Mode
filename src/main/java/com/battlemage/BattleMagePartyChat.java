package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * One line of the Battle Mage party chat: plain text, nothing else.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyChat extends PartyMemberMessage
{
	/** The text, at most {@link PartyChat#MAX_LENGTH} characters. */
	String t;

	public BattleMagePartyChat()
	{
	}

	BattleMagePartyChat(String text)
	{
		this.t = text;
	}
}
