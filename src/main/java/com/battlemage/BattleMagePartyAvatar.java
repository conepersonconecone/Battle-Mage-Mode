package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * One piece of a party member's uploaded picture. Pictures are sent in pieces (see
 * {@link PartyAvatar#CHUNK}) so no single party message is large; teammates put them back together
 * and show the picture in that member's square.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyAvatar extends PartyMemberMessage
{
	/** Which picture these pieces belong to (changes whenever the picture does); 0 with c = cleared. */
	int v;
	/** This piece's number, from 0. */
	int i;
	/** How many pieces in all. */
	int n;
	/** This piece: part of the JPEG, as Base64 text. */
	String d;
	/** The member removed their picture. */
	boolean c;

	public BattleMagePartyAvatar()
	{
	}

	BattleMagePartyAvatar(int version, int index, int count, String data, boolean cleared)
	{
		this.v = version;
		this.i = index;
		this.n = count;
		this.d = data;
		this.c = cleared;
	}
}
