package com.battlemage;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * What one Battle Mage tells the rest of their RuneLite party: their faction, aura, and which PKP
 * states they are in (no PKP numbers: only the states, which change far less often). Sent through RuneLite's own party (the built-in Party plugin), so nothing else needs to be
 * installed. Field names are kept short because every party member receives every update.
 *
 * <p>The class name is the message's type on the wire: renaming it stops older and newer versions of
 * the plugin from hearing each other.
 */
public class BattleMagePartyUpdate extends PartyMemberMessage
{
	/** The bar is in the depleted state. */
	static final int DEPLETED = 1;
	/** The bar is overloaded. */
	static final int OVERLOADED = 1 << 1;
	/** Critical overload is running. */
	static final int CRITICAL = 1 << 2;
	/** Wearing gear their faction doesn't allow: no spells, no attacking. */
	static final int BARRED_GEAR = 1 << 3;
	/** Positive effects running (for their particles on teammates' screens): frozen PKP. */
	static final int FX_FREEZE = 1 << 4;
	/** Overload protection. */
	static final int FX_OVERLOAD_PROOF = 1 << 5;
	/** Depletion protection. */
	static final int FX_DEPLETION_PROOF = 1 << 6;
	/** A drink's PKP scale: spells cost less. */
	static final int FX_SPEND_LESS = 1 << 7;
	/** Using the in-game PKP style. */
	static final int IN_GAME = 1 << 8;
	/** A drink's PKP scale: spells cost more. */
	static final int FX_SPEND_MORE = 1 << 9;
	/** A drink's PKP scale: gains are smaller. */
	static final int FX_GAIN_LESS = 1 << 10;
	/** A drink's PKP scale: gains are bigger. */
	static final int FX_GAIN_MORE = 1 << 11;

	/** The sender's character name, so the list and head bars work even without the Party plugin on. */
	String n;
	/** The sworn faction's enum name, or null for nobody sworn. */
	String f;
	/** State flags, see the constants above. */
	int s;
	/** The sender's aura (an {@link AuraMode} name), drawn under them on teammates' screens. */
	String a;
	/** True when the sender has stopped sharing: drop them from the list. */
	boolean h;
	/** The sender created this party and hasn't left it since: they lead it. */
	boolean c;
	/** When the sender joined this party, this visit (ms): after the creator, the earliest leads. */
	long j;

	public BattleMagePartyUpdate()
	{
	}

	BattleMagePartyUpdate(String name, String faction, int state, String aura, boolean hidden)
	{
		this.n = name;
		this.a = aura;
		this.f = faction;
		this.s = state;
		this.h = hidden;
	}

	/** With the sender's standing in this party: creator (this visit), and when they joined. */
	BattleMagePartyUpdate seniority(boolean creator, long firstJoined)
	{
		this.c = creator;
		this.j = firstJoined;
		return this;
	}

	/** Same content, ignoring who sent it. */
	boolean sameAs(BattleMagePartyUpdate o)
	{
		return o != null && s == o.s && h == o.h && c == o.c && j == o.j
			&& java.util.Objects.equals(f, o.f) && java.util.Objects.equals(n, o.n)
			&& java.util.Objects.equals(a, o.a);
	}
}
