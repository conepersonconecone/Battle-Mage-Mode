package com.battlemage;

/**
 * How your faction's aura is shown under your character, picked on the side panel's Aura slider.
 */
public enum AuraMode
{
	/** No aura. */
	NONE("None"),
	/** A ring drawn over the scene: always visible, even through walls and scenery. */
	XRAY("X-Ray"),
	/** The game's own Soul Wars glow, recoloured to your god: a real object in the world. */
	SOUL("Soul");

	private final String label;

	AuraMode(String label)
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
