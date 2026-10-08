package com.battlemage;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Settings. Everything that decides what is legal lives in the codex, not here.
 *
 * <p>Nothing here is shown in RuneLite's configuration panel except a note pointing to the side panel.
 * Everything visual is stored here, but hidden: it is edited from the side panel's <b>Customize appearance</b> menu,
 * which previews each change on screen before applying it. The overlays read those values through
 * {@link Appearance}, never directly.
 */
@ConfigGroup(BattleMageConfig.GROUP)
public interface BattleMageConfig extends Config
{
	/** The RuneLite settings group. Changing it orphans every saved setting. */
	String GROUP = "battlemage";

	/**
	 * The only thing the configuration panel shows: a pointer to the side panel, where every setting
	 * lives. A section with no items still draws its header, which carries the note.
	 */
	@ConfigSection(name = "Check the side panel", description = "Every Battle-Mage setting is in the Battle-Mage side panel (the toolbar icon on the right)", position = 0)
	String note = "note";

	// ------------------------------------------------------------------ appearance (hidden)
	// Edited from the side panel and read through Appearance. Keep defaults in step with
	// Appearance.DEFAULTS.

	@ConfigItem(keyName = "castCardsEnabled", name = "castCardsEnabled", description = "", hidden = true)
	default boolean castCardsEnabled()
	{
		return false;
	}

	@ConfigItem(keyName = "levelUpCards", name = "levelUpCards", description = "", hidden = true)
	default boolean levelUpCards()
	{
		return true;
	}

	@ConfigItem(keyName = "meleeColor", name = "meleeColor", description = "", hidden = true)
	default Color meleeColor()
	{
		return new Color(0xF2F2F2);
	}

	@ConfigItem(keyName = "combatSpellColor", name = "combatSpellColor", description = "", hidden = true)
	default Color combatSpellColor()
	{
		return new Color(0x46C8FF);
	}

	@ConfigItem(keyName = "curseSpellColor", name = "curseSpellColor", description = "", hidden = true)
	default Color curseSpellColor()
	{
		return new Color(0xFF4FD8);
	}

	@ConfigItem(keyName = "illegalInventoryShade", name = "illegalInventoryShade", description = "", hidden = true)
	default Color illegalInventoryShade()
	{
		return new Color(0xFFB300);
	}

	@ConfigItem(keyName = "illegalExemptOutline", name = "illegalExemptOutline", description = "", hidden = true)
	default Color illegalExemptOutline()
	{
		return new Color(0x39FF14);
	}

	@ConfigItem(keyName = "showBar", name = "showBar", description = "", hidden = true)
	default boolean showBar()
	{
		return true;
	}

	@ConfigItem(keyName = "barLabel", name = "barLabel", description = "", hidden = true)
	default String barLabel()
	{
		return "PKP";
	}

	@ConfigItem(keyName = "showPkpValue", name = "showPkpValue", description = "", hidden = true)
	default boolean showPkpValue()
	{
		return true;
	}

	@ConfigItem(keyName = "barWidth", name = "barWidth", description = "", hidden = true)
	default int barWidth()
	{
		return 220;
	}

	@ConfigItem(keyName = "barHeight", name = "barHeight", description = "", hidden = true)
	default int barHeight()
	{
		return 24;
	}

	@ConfigItem(keyName = "barBorderThickness", name = "barBorderThickness", description = "", hidden = true)
	default int barBorderThickness()
	{
		return 2;
	}

	@ConfigItem(keyName = "barGlow", name = "barGlow", description = "", hidden = true)
	default int barGlow()
	{
		return 0;
	}

	@ConfigItem(keyName = "barPulse", name = "barPulse", description = "", hidden = true)
	default boolean barPulse()
	{
		return true;
	}

	@ConfigItem(keyName = "showCostTicks", name = "showCostTicks", description = "", hidden = true)
	default boolean showCostTicks()
	{
		return true;
	}

	@ConfigItem(keyName = "barColorFull", name = "barColorFull", description = "", hidden = true)
	default Color barColorFull()
	{
		return new Color(0x46C8FF);
	}

	@ConfigItem(keyName = "barColorLow", name = "barColorLow", description = "", hidden = true)
	default Color barColorLow()
	{
		return new Color(0xFF4040);
	}

	@ConfigItem(keyName = "overchargeColor", name = "overchargeColor", description = "", hidden = true)
	default Color overchargeColor()
	{
		return new Color(0x9CFF57);
	}

	@ConfigItem(keyName = "showEffectTimers", name = "showEffectTimers", description = "", hidden = true)
	default boolean showEffectTimers()
	{
		return true;
	}

	@ConfigItem(keyName = "effectIconSize", name = "effectIconSize", description = "", hidden = true)
	default int effectIconSize()
	{
		return 34;
	}

	@ConfigItem(keyName = "effectBorderEnabled", name = "effectBorderEnabled", description = "", hidden = true)
	default boolean effectBorderEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "desaturateScreen", name = "desaturateScreen", description = "", hidden = true)
	default boolean desaturateScreen()
	{
		return true;
	}

	@ConfigItem(keyName = "desaturationStrength", name = "desaturationStrength", description = "", hidden = true)
	default int desaturationStrength()
	{
		return 140;
	}

	@ConfigItem(keyName = "depletedTextScalePercent", name = "depletedTextScalePercent", description = "", hidden = true)
	default int depletedTextScalePercent()
	{
		return 100;
	}

	@ConfigItem(keyName = "overloadTextScalePercent", name = "overloadTextScalePercent", description = "", hidden = true)
	default int overloadTextScalePercent()
	{
		return 100;
	}

	@ConfigItem(keyName = "criticalTextScalePercent", name = "criticalTextScalePercent", description = "", hidden = true)
	default int criticalTextScalePercent()
	{
		return 100;
	}

	@ConfigItem(keyName = "depletedText", name = "depletedText", description = "", hidden = true)
	default String depletedText()
	{
		return "PSI DEPLETED";
	}

	@ConfigItem(keyName = "overloadText", name = "overloadText", description = "", hidden = true)
	default String overloadText()
	{
		return "PSI OVERLOAD";
	}

	@ConfigItem(keyName = "criticalText", name = "criticalText", description = "", hidden = true)
	default String criticalText()
	{
		return "CRITICAL OVERLOAD";
	}

	@ConfigItem(keyName = "depletedColor", name = "depletedColor", description = "", hidden = true)
	default Color depletedColor()
	{
		return new Color(0xFF2A2A);
	}

	@ConfigItem(keyName = "overloadColor", name = "overloadColor", description = "", hidden = true)
	default Color overloadColor()
	{
		return new Color(0xC04AFF);
	}

	@ConfigItem(keyName = "criticalColor", name = "criticalColor", description = "", hidden = true)
	default Color criticalColor()
	{
		return new Color(0xFFB300);
	}

	@ConfigItem(keyName = "stateTextXPercent", name = "stateTextXPercent", description = "", hidden = true)
	default double stateTextXPercent()
	{
		return 50;
	}

	@ConfigItem(keyName = "stateTextYPercent", name = "stateTextYPercent", description = "", hidden = true)
	default double stateTextYPercent()
	{
		return 50;
	}

	@ConfigItem(keyName = "criticalTextXPercent", name = "criticalTextXPercent", description = "", hidden = true)
	default double criticalTextXPercent()
	{
		return 50;
	}

	@ConfigItem(keyName = "criticalTextYPercent", name = "criticalTextYPercent", description = "", hidden = true)
	default double criticalTextYPercent()
	{
		return 88;
	}

	@ConfigItem(keyName = "pkpPopupsEnabled", name = "pkpPopupsEnabled", description = "", hidden = true)
	default boolean pkpPopupsEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "pkpPopupSeconds", name = "pkpPopupSeconds", description = "", hidden = true)
	default int pkpPopupSeconds()
	{
		return 3;
	}

	@ConfigItem(keyName = "inventoryTooltips", name = "inventoryTooltips", description = "", hidden = true)
	default boolean inventoryTooltips()
	{
		return true;
	}
}
