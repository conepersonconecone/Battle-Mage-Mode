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
 * Pushes PKP tooltips: item effects when hovering the inventory / worn-equipment, and a spell's PKP
 * cost when hovering it in the spellbook or the autocast list. Each kind can be switched off on its own.
 */
class PkpTooltipOverlay extends Overlay
{
	private static final int INVENTORY_GROUP = 149;
	private static final int EQUIPMENT_TAB_GROUP = 387;
	private static final int BANK_GROUP = 12;
	private static final int BANK_INVENTORY_GROUP = 15;
	/** The spellbook tab, and the autocast spell list a staff opens. */
	private static final int SPELLBOOK_GROUP = 218;
	private static final int AUTOCAST_GROUP = 201;

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

		// spell tooltips: the PKP a spell costs, when hovering it in the spellbook or the autocast list
		if (look.spellTooltips())
		{
			MenuEntry top = menu[menu.length - 1];
			Widget w = top.getWidget();
			int group = w == null ? -1 : w.getId() >>> 16;
			if (group == SPELLBOOK_GROUP || group == AUTOCAST_GROUP)
			{
				String text = plugin.spellTooltipFor(top);
				if (text != null)
				{
					tooltipManager.add(new Tooltip(text));
				}
			}
		}
		return null;
	}

}
