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

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.party.PartyMember;

/**
 * Everything the party panel shows about one party member, apart from their Battle Mage state.
 *
 * <p>Most of it (name, stats, spec, run energy, inventory, worn items, active prayers, rune pouch,
 * quiver) is RuneLite's own party data, which every client in the party shares while any plugin on
 * it asks for it. The rest (world, spellbook, poison, disease, stamina, which prayers are unlocked and
 * usable) Battle Mage Mode sends itself, so it is only known for teammates playing Battle Mage Mode.
 */
public final class PartyPlayer
{
	public static final Set<Integer> RUNE_POUCH_IDS = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(
		java.util.Arrays.asList(ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH,
			ItemID.DIVINE_RUNE_POUCH_TROUVER)));
	public static final Set<Integer> QUIVER_IDS = quiverIds();

	private static Set<Integer> quiverIds()
	{
		final Set<Integer> ids = new java.util.HashSet<>();
		for (int base : new int[]{ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_INFINITE, ItemID.SKILLCAPE_MAX_DIZANAS})
		{
			ids.addAll(ItemVariationMapping.getVariations(ItemVariationMapping.map(base)));
		}
		return java.util.Collections.unmodifiableSet(ids);
	}

	private PartyMember member;

	private final Map<Skill, Integer> baseLevels = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> boostedLevels = new EnumMap<>(Skill.class);
	private boolean hasStats;
	private int specialPercent;
	private int runEnergy;
	private int combatLevel;

	private GameItem[] inventory = new GameItem[28];
	private GameItem[] equipment = new GameItem[EquipmentInventorySlot.AMMO.getSlotIdx() + 1];
	private GameItem[] runesInPouch = new GameItem[0];
	private GameItem quiverAmmo;
	private boolean quiverWorn;
	private boolean quiverInInventory;
	private final Prayers prayers = new Prayers();

	// sent by Battle Mage Mode; unknown (0 / -1 / false) for anyone else
	private boolean hasExtras;
	private int world;
	private int spellbook = -1;
	private int poison;
	private int disease;
	private boolean staminaActive;

	/** What the banner was last built from: a change here rebuilds it. */
	private String bannerName;
	private int bannerCombat = -1;

	public PartyPlayer(PartyMember member)
	{
		this.member = member;
		for (Skill s : Skill.values())
		{
			baseLevels.put(s, s == Skill.HITPOINTS ? 10 : 1);
			boostedLevels.put(s, s == Skill.HITPOINTS ? 10 : 1);
		}
	}

	/**
	 * Re-reads RuneLite's party data for this member. Client thread only (item names are looked up).
	 * Returns true when the banner's name line needs rebuilding.
	 */
	public boolean update(final PartyMember m, final ItemManager itemManager)
	{
		member = m;
		if (m.getInv() != null)
		{
			inventory = GameItem.convert(m.getInv(), itemManager);
			quiverInInventory = containsAny(inventory, QUIVER_IDS);
		}
		if (m.getWorn() != null)
		{
			equipment = GameItem.convert(m.getWorn(), itemManager);
			quiverWorn = containsAny(equipment, QUIVER_IDS);
		}
		if (m.getRunepouch() != null)
		{
			runesInPouch = GameItem.convert(m.getRunepouch(), itemManager);
		}
		if (m.getQuiver() != null)
		{
			quiverAmmo = m.getQuiver().getId() <= 0 ? null : new GameItem(m.getQuiver(), itemManager);
		}
		if (m.getPrayers() != null)
		{
			prayers.setEnabled(m.getPrayers());
		}

		boolean any = false;
		for (Skill s : Skill.values())
		{
			int base = m.getStatBase(s);
			if (base > 0)
			{
				any = true;
				baseLevels.put(s, base);
				boostedLevels.put(s, m.getStatBoost(s));
			}
		}
		hasStats = hasStats || any;
		combatLevel = Experience.getCombatLevel(
			Math.min(baseLevels.get(Skill.ATTACK), 99), Math.min(baseLevels.get(Skill.STRENGTH), 99),
			Math.min(baseLevels.get(Skill.DEFENCE), 99), Math.min(baseLevels.get(Skill.HITPOINTS), 99),
			Math.min(baseLevels.get(Skill.MAGIC), 99), Math.min(baseLevels.get(Skill.RANGED), 99),
			Math.min(baseLevels.get(Skill.PRAYER), 99));
		specialPercent = m.getSpecEnergy();
		runEnergy = m.getRunEnergy();

		final String name = getUsername();
		final int cb = hasStats ? combatLevel : -1;
		final boolean rebuild = !Objects.equals(name, bannerName) || cb != bannerCombat;
		bannerName = name;
		bannerCombat = cb;
		return rebuild;
	}

	/** Applies the extras a teammate's Battle Mage Mode sent. True when the banner's name line changed. */
	public boolean applyExtras(int world, int spellbook, int poison, int disease, boolean stamina,
		Integer availablePrayers, Integer unlockedPrayers)
	{
		final boolean rebuild = !hasExtras || this.world != world || this.spellbook != spellbook;
		hasExtras = true;
		this.world = world;
		this.spellbook = spellbook;
		this.poison = poison;
		this.disease = disease;
		this.staminaActive = stamina;
		prayers.applyPacked(availablePrayers, unlockedPrayers);
		return rebuild;
	}

	private static boolean containsAny(GameItem[] items, Set<Integer> ids)
	{
		for (GameItem i : items)
		{
			if (i != null && ids.contains(i.getId()))
			{
				return true;
			}
		}
		return false;
	}

	public PartyMember getMember()
	{
		return member;
	}

	/** The character name, or null when not logged in (or not yet known). */
	public String getUsername()
	{
		final String name = member == null ? null : member.getDisplayName();
		return name == null || "<unknown>".equals(name) ? null : name;
	}

	public boolean isLoggedIn()
	{
		return member != null && member.isLoggedIn();
	}

	public boolean hasStats()
	{
		return hasStats;
	}

	public int getSkillBoostedLevel(Skill s)
	{
		return boostedLevels.get(s);
	}

	public int getSkillRealLevel(Skill s, boolean virtual)
	{
		return Math.min(baseLevels.get(s), virtual ? 126 : 99);
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	public int getSpecialPercent()
	{
		return specialPercent;
	}

	public int getRunEnergy()
	{
		return runEnergy;
	}

	public GameItem[] getInventory()
	{
		return inventory;
	}

	public GameItem[] getEquipment()
	{
		return equipment;
	}

	public GameItem[] getRunesInPouch()
	{
		return runesInPouch;
	}

	public GameItem getQuiverAmmo()
	{
		return quiverAmmo;
	}

	/** The quiver slot shows when a Dizana's quiver is worn or carried. */
	public boolean isQuiverSlotVisible()
	{
		return quiverWorn || quiverInInventory;
	}

	public Prayers getPrayers()
	{
		return prayers;
	}

	public boolean hasExtras()
	{
		return hasExtras;
	}

	public int getWorld()
	{
		return world;
	}

	/** 0 standard, 1 ancient, 2 lunar, 3 arceuus, -1 unknown. */
	public int getSpellbook()
	{
		return spellbook;
	}

	public int getPoison()
	{
		return poison;
	}

	public int getDisease()
	{
		return disease;
	}

	public boolean isStaminaActive()
	{
		return staminaActive;
	}
}
