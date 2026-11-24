package com.battlemage;

/**
 * How PKP is shown, picked on the side panel's PKP Style slider. Each style keeps its own set of
 * appearance settings (its own profile), so switching back and forth never loses either look.
 */
public enum PkpStyle
{
	/** The on-screen HUD: the PKP bar, the big state text and the effect badges. */
	HUD("HUD"),
	/**
	 * In game: your character glows with your PKP state, state words float off the orb, a small
	 * PKP orb sits by the minimap and positive effects show as particles round your aura.
	 */
	IN_GAME("In-game");

	private final String label;

	PkpStyle(String label)
	{
		this.label = label;
	}

	String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
