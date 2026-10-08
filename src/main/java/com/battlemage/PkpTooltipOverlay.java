package com.battlemage;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Pushes PKP tooltips: item effects when hovering the inventory / worn-equipment, and per-mode spell
 * costs when hovering a PK spell in the spellbook or autocast-selection menu.
 */
class PkpTooltipOverlay extends Overlay
{
	private static final int INVENTORY_GROUP = 149;
	private static final int EQUIPMENT_TAB_GROUP = 387;
	private static final int BANK_GROUP = 12;
	private static final int BANK_INVENTORY_GROUP = 15;

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;
	private final TooltipManager tooltipManager;

	@Inject
	PkpTooltipOverlay(Client client, BattleMagePlugin plugin, Appearance look, TooltipManager tooltipManager)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{

		MenuEntry[] menu = client.getMenuEntries();
		if (menu.length == 0)
		{
			return null;
		}

		// item tooltips (inventory / worn equipment). The equip option is stripped from blocked
		//    items, so the item-bearing entry may not be on top - scan all entries for it.
		if (look.inventoryTooltips())
		{
			int itemId = -1;
			for (int i = menu.length - 1; i >= 0; i--)
			{
				Widget mw = menu[i].getWidget();
				if (mw == null)
				{
					continue;
				}
				int group = mw.getId() >>> 16;
				if (group != INVENTORY_GROUP && group != EQUIPMENT_TAB_GROUP
					&& group != BANK_GROUP && group != BANK_INVENTORY_GROUP)
				{
					continue;
				}
				int id = menu[i].getItemId();
				if (id <= 0)
				{
					id = mw.getItemId();
				}
				if (id > 0)
				{
					itemId = id;
					break;
				}
			}
			if (itemId > 0)
			{
				String text = plugin.tooltipFor(itemId);
				if (text != null)
				{
					tooltipManager.add(new Tooltip(text));
				}
				return null;
			}
		}

		// spellbook hover text is deliberately not shown - spells carry no plugin-side name or cost label
		return null;
	}

}
