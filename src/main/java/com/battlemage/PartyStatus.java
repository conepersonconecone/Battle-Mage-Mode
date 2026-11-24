package com.battlemage;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * How a party member's PKP state reads: Critical Overload, Overload or Depletion, the only states
 * teammates see. The words and colours used on their side-panel banner and for their outline in
 * game, most urgent first.
 */
final class PartyStatus
{
	/** One state, as a badge. */
	static final class Badge
	{
		/** The full word, for the side panel. */
		final String word;
		/** A short word, for the badge over a character. */
		final String shortWord;
		final Color color;
		/** What it means, for tooltips. */
		final String meaning;

		Badge(String word, String shortWord, Color color, String meaning)
		{
			this.word = word;
			this.shortWord = shortWord;
			this.color = color;
			this.meaning = meaning;
		}
	}

	/** The off-limit item symbol's red, for a teammate wearing gear their faction doesn't allow. */
	static final Color ILLEGAL = new Color(230, 30, 30);

	private PartyStatus()
	{
	}

	/** The badges for these state flags, most urgent first. Empty when there's nothing to show. */
	static List<Badge> badges(int state, Appearance look)
	{
		final List<Badge> out = new ArrayList<>();
		if (has(state, BattleMagePartyUpdate.CRITICAL))
		{
			out.add(new Badge("Critical", "CRIT", look.criticalColor(), "CRITICAL OVERLOAD: blowing up soon"));
		}
		else if (has(state, BattleMagePartyUpdate.OVERLOADED))
		{
			out.add(new Badge("Overload", "OVL", look.overloadColor(), "Overloaded"));
		}
		if (has(state, BattleMagePartyUpdate.DEPLETED))
		{
			out.add(new Badge("Depleted", "DEP", look.depletedColor(), "Depleted"));
		}
		if (has(state, BattleMagePartyUpdate.BARRED_GEAR))
		{
			out.add(new Badge("Illegal gear", "ILL", ILLEGAL, "Wearing gear their faction doesn't allow: can't cast or attack"));
		}
		return out;
	}

	private static boolean has(int state, int flag)
	{
		return (state & flag) != 0;
	}
}
