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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Prayer;

/**
 * Every prayer's state for one party member. Which prayers are switched on comes from RuneLite's own
 * party data; which are unlocked and usable is sent by Battle Mage Mode, as two bit-packed ints.
 */
public final class Prayers
{
	/** Client script: is this prayer usable right now (all requirements met)? Takes the prayer's item id. */
	private static final int PRAYER_IS_AVAILABLE = 10154;

	private final Map<Prayer, PrayerData> prayerData = new EnumMap<>(Prayer.class);

	public Prayers()
	{
		for (final Prayer p : Prayer.values())
		{
			prayerData.put(p, new PrayerData(p, p.ordinal() == 0, false, isUnlockedByDefault(p)));
		}
	}

	public Map<Prayer, PrayerData> getPrayerData()
	{
		return prayerData;
	}

	/** Re-reads one prayer from the game (client thread only). True when anything about it changed. */
	public boolean updatePrayerState(final PrayerSprites p, final Client client)
	{
		boolean available = false;
		boolean unlocked = false;
		if (p.isUnlocked(client))
		{
			client.runScript(PRAYER_IS_AVAILABLE, p.getItemID());
			available = client.getIntStack()[0] > 0;
			unlocked = true;
		}
		final PrayerData data = prayerData.get(p.getPrayer());
		final boolean changed = data.isAvailable() != available || data.isUnlocked() != unlocked;
		data.setAvailable(available);
		data.setUnlocked(unlocked);
		return changed;
	}

	public static boolean isUnlockedByDefault(Prayer p)
	{
		switch (p)
		{
			case DEADEYE:
			case MYSTIC_VIGOUR:
			case ZEAL:
				return false;
			default:
				return true;
		}
	}

	/** Which prayers are switched on, from RuneLite's party data. */
	public void setEnabled(final Set<Prayer> enabled)
	{
		for (final PrayerData d : prayerData.values())
		{
			d.setEnabled(enabled != null && enabled.contains(d.getPrayer()));
		}
	}

	/** Applies the bit-packed available and unlocked sets a party member sent. */
	public void applyPacked(final Integer available, final Integer unlocked)
	{
		for (final PrayerData d : prayerData.values())
		{
			if (available != null)
			{
				d.setAvailable(false);
			}
			if (unlocked != null)
			{
				d.setUnlocked(false);
			}
		}
		if (available != null)
		{
			for (final Prayer p : unpack(available))
			{
				prayerData.get(p).setAvailable(true);
			}
		}
		if (unlocked != null)
		{
			for (final Prayer p : unpack(unlocked))
			{
				prayerData.get(p).setUnlocked(true);
			}
		}
	}

	/** This member's available and unlocked prayers, bit-packed for sending. [available, unlocked] */
	public int[] pack()
	{
		final List<Prayer> available = new ArrayList<>();
		final List<Prayer> unlocked = new ArrayList<>();
		for (final PrayerSprites p : PrayerSprites.values())
		{
			final PrayerData d = prayerData.get(p.getPrayer());
			if (d.isAvailable())
			{
				available.add(p.getPrayer());
			}
			if (d.isUnlocked())
			{
				unlocked.add(p.getPrayer());
			}
		}
		return new int[]{pack(available), pack(unlocked)};
	}

	static int pack(final Collection<Prayer> items)
	{
		int i = 0;
		for (Prayer p : items)
		{
			if (p.ordinal() < 32)
			{
				i |= 1 << p.ordinal();
			}
		}
		return i;
	}

	private static List<Prayer> unpack(final int pack)
	{
		final List<Prayer> out = new ArrayList<>();
		for (Prayer p : Prayer.values())
		{
			// packed into 32 bits; later enum entries (ruinous powers) don't fit and aren't shown
			if (p.ordinal() >= 32)
			{
				break;
			}
			if ((pack & (1 << p.ordinal())) != 0)
			{
				out.add(p);
			}
		}
		return out;
	}
}
