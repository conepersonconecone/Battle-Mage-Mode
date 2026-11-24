package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The party leader's ready check, and each member's answer to it.
 *
 * <p>The class name is the message's type on the wire: renaming it stops versions hearing each other.
 */
public class BattleMagePartyReady extends PartyMemberMessage
{
	/** The leader asks: is everyone ready? */
	static final int ASK = 1;
	/** A member answers. */
	static final int ANSWER = 2;

	/** {@link #ASK} or {@link #ANSWER}. */
	int k;
	/** Which ready check this is (the answer names the one it answers). */
	long id;
	/** An answer: ready (true) or not (false). */
	boolean r;

	public BattleMagePartyReady()
	{
	}

	BattleMagePartyReady(int kind, long id, boolean ready)
	{
		this.k = kind;
		this.id = id;
		this.r = ready;
	}
}
