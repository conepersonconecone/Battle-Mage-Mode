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
		new Color(0x8367BE));

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
