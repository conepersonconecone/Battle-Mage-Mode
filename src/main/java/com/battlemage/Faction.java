package com.battlemage;

import java.awt.Color;

/**
 * The four god factions that can be sworn to. Whichever is in force selects the ruleset in the
 * bundled {@link ContentPack} that the plugin enforces; the side panel can change it at any time.
 *
 * <p>The name and epithet here are <b>fallbacks only</b>. The words the oath screen actually
 * shows are authored per faction in the codex under {@code profiles.<GOD>.lore} and reached through
 * {@link Rules#lore(Faction)}; these constants are what fills in for a field left blank there.
 * Colours are not authorable and live here for good.
 */
public enum Faction
{
	SARADOMIN(
		"Saradomin",
		"Order",
		new Color(0x3F87DC)),

	GUTHIX(
		"Guthix",
		"Balance",
		new Color(0x3FA96A)),

	ZAMORAK(
		"Zamorak",
		"Chaos",
		new Color(0xCE4038)),

	ZAROS(
		"Zaros",
		"Darkness",
		new Color(0x8367BE)),

	/**
	 * Not a god: the PKP system on its own, taken from the Wise Old Man by a player who was sworn
	 * to one. Every PKP rule applies and every god's spells stay barred, but nothing restricts
	 * gear, weapons or the cannon. Gold, like the toolbar icon and the shrine before any oath.
	 */
	FACTIONLESS(
		"Faction-less",
		"Unbound",
		new Color(0xE3B93B));

	/** The four gods, in the order the oath screen shows them - never {@link #FACTIONLESS}. */
	static final Faction[] GODS = {SARADOMIN, GUTHIX, ZAMORAK, ZAROS};

	private final String displayName;
	private final String epithet;
	private final Color primary;

	Faction(String displayName, String epithet, Color primary)
	{
		this.displayName = displayName;
		this.epithet = epithet;
		this.primary = primary;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/** Fallback for the one word under the name on the god card, when the codex leaves it blank. */
	public String getEpithet()
	{
		return epithet;
	}


	/** One of the four gods, rather than the faction-less PKP system. */
	public boolean isGod()
	{
		return this != FACTIONLESS;
	}

	/** The faction's crest and accent colour. */
	public Color getPrimary()
	{
		return primary;
	}


	public static Faction byName(String raw)
	{
		if (raw != null)
		{
			for (Faction f : values())
			{
				if (f.name().equalsIgnoreCase(raw.trim()))
				{
					return f;
				}
			}
		}
		return null;
	}
}
