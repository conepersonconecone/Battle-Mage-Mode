package com.battlemage.party;

import java.awt.Color;

/** Shared sizes and colours for the party views inside the Battle Mage side panel. */
public final class PartyUi
{
	/**
	 * Width available to a member's panel: the side panel (225) less its 10px padding each side, less
	 * the member panel's 2px frame each side.
	 */
	public static final int WIDTH = 225 - 20 - 4;

	/** The frame round each member, as in the game's own interfaces. */
	public static final Color FRAME = new Color(87, 80, 64);

	private PartyUi()
	{
	}

	/** A small coloured word on a member's banner: one of their Battle Mage PKP states. */
	public static final class Chip
	{
		public final String text;
		public final Color color;
		public final String tooltip;
		/** A small picture before the word (a group rank's), or null. */
		public final javax.swing.Icon icon;

		public Chip(String text, Color color, String tooltip)
		{
			this(text, color, tooltip, null);
		}

		public Chip(String text, Color color, String tooltip, javax.swing.Icon icon)
		{
			this.text = text;
			this.color = color;
			this.tooltip = tooltip;
			this.icon = icon;
		}
	}
}
