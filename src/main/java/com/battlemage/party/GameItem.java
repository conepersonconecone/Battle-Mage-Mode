/*
 * Adapted for Battle Mage Mode from Party Panel (https://github.com/TheStonedTurtle/party-panel).
 *
 * Copyright (c) 2020, TheStonedTurtle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.battlemage.party;

import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

/** One item stack as a party member's panel shows it. Build on the client thread (it reads item names). */
public final class GameItem
{
	private final int id;
	private final int qty;
	private final String name;
	private final boolean stackable;

	public GameItem(final int id, final int qty, final ItemManager itemManager)
	{
		this.id = id;
		this.qty = qty;
		final ItemComposition c = itemManager.getItemComposition(id);
		this.name = c.getName();
		this.stackable = c.isStackable();
	}

	public GameItem(final Item item, final ItemManager itemManager)
	{
		this(item.getId(), item.getQuantity(), itemManager);
	}

	public int getId()
	{
		return id;
	}

	public int getQty()
	{
		return qty;
	}

	public String getName()
	{
		return name;
	}

	public boolean isStackable()
	{
		return stackable;
	}

	public static GameItem[] convert(final Item[] items, final ItemManager itemManager)
	{
		final GameItem[] out = new GameItem[items.length];
		for (int i = 0; i < items.length; i++)
		{
			final Item item = items[i];
			out[i] = item == null || item.getId() == -1 || item.getQuantity() <= 0 ? null : new GameItem(item, itemManager);
		}
		return out;
	}

	public String getDisplayName()
	{
		return qty <= 1 ? name : name + " x " + QuantityFormatter.formatNumber(qty);
	}
}
