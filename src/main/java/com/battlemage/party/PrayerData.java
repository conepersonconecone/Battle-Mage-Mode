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

import net.runelite.api.Prayer;

/** One prayer's state for a party member: whether it can be used, is switched on, and is unlocked. */
public final class PrayerData
{
	private final Prayer prayer;
	private boolean available;
	private boolean enabled;
	private boolean unlocked;

	public PrayerData(Prayer prayer, boolean available, boolean enabled, boolean unlocked)
	{
		this.prayer = prayer;
		this.available = available;
		this.enabled = enabled;
		this.unlocked = unlocked;
	}

	public Prayer getPrayer()
	{
		return prayer;
	}

	public boolean isAvailable()
	{
		return available;
	}

	public void setAvailable(boolean available)
	{
		this.available = available;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	public boolean isUnlocked()
	{
		return unlocked;
	}

	public void setUnlocked(boolean unlocked)
	{
		this.unlocked = unlocked;
	}

	public PrayerData copy()
	{
		return new PrayerData(prayer, available, enabled, unlocked);
	}
}
