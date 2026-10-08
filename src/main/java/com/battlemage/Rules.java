package com.battlemage;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The plugin's view of the bundled content pack, resolved for whichever god is in force.
 *
 * <p>Every method here replaces a setting that used to sit in {@link BattleMageConfig}, keeping the
 * old name and return type so call sites read the same as before. List-shaped values are joined
 * back into the comma-separated strings the plugin's existing parsers expect, which keeps all the
 * parsing and caching in {@code BattleMagePlugin} untouched.
 *
 * <p>With no god chosen - before the oath screen is finished - the pack is not consulted at all
 * and every rule returns its inert value, so nothing is restricted and no PKP is spent.
 */
@Slf4j
@Singleton
public class Rules
{
	private static final String RESOURCE = "/battlemage-codex.json";

	/** One game tick, in milliseconds. The client's own clock, and the unit the pack authors in. */
	private static final double TICK_MS = 600.0;

	private final Oath oath;
	private final Gson gson;

	private ContentPack pack;
	private ContentPack.Profile active;
	private boolean loadFailed;

	@Inject
	public Rules(Oath oath, Gson gson)
	{
		this.oath = oath;
		this.gson = gson;
		loadPack();
		refresh();
	}

	// ------------------------------------------------------------------ loading

	private void loadPack()
	{
		try (InputStream in = Rules.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				loadFailed = true;
				log.error("Content pack {} is missing from the jar; all rules are inert.", RESOURCE);
				pack = new ContentPack();
				return;
			}
			pack = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ContentPack.class);
			if (pack == null)
			{
				throw new IOException("empty pack");
			}
			log.debug("Loaded content pack v{} ({} profiles)", pack.formatVersion, pack.profiles.size());
		}
		catch (Exception e)
		{
			loadFailed = true;
			pack = new ContentPack();
			log.error("Could not read the content pack; all rules are inert.", e);
		}
	}

	/** Re-resolves the active ruleset. Call after the god changes. */
	public void refresh()
	{
		String key = oath.getRulesetKey();
		active = key == null ? null : pack.profile(key);
		if (key != null && active == null)
		{
			log.warn("Content pack has no ruleset for {}; rules are inert until it is added.", key);
		}
	}

	/** True when a god is chosen and the pack actually has rules for it. */
	public boolean isActive()
	{
		return active != null;
	}

	public boolean isPackMissing()
	{
		return loadFailed;
	}

	/**
	 * Any faction's pack entry, not just the active one - the oath screen needs to describe gods the
	 * player has not sworn to yet. Null when the pack has no entry for it.
	 */
	public ContentPack.Profile packEntry(Faction faction)
	{
		return faction == null ? null : pack.profile(faction.name());
	}

	/**
	 * The authored words for a god, never null. Missing fields are filled from {@link Faction} so
	 * the oath screen reads correctly against a pack whose lore has not been written yet.
	 */
	public ContentPack.Lore lore(Faction faction)
	{
		ContentPack.Lore out = new ContentPack.Lore();
		if (faction == null)
		{
			return out;
		}
		ContentPack.Profile p = packEntry(faction);
		ContentPack.Lore src = p == null ? null : p.lore;

		out.displayName = pick(src == null ? null : src.displayName, faction.getDisplayName());
		out.epithet = pick(src == null ? null : src.epithet, faction.getEpithet());
		return out;
	}

	private static String pick(String authored, String fallback)
	{
		return authored == null || authored.trim().isEmpty() ? fallback : authored.trim();
	}

	// ------------------------------------------------------------------ joining

	private static String join(List<String> values)
	{
		if (values == null || values.isEmpty())
		{
			return "";
		}
		return String.join(",", values);
	}

	private static String joinIds(List<Integer> values)
	{
		if (values == null || values.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Integer v : values)
		{
			if (v == null)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(v.intValue());
		}
		return sb.toString();
	}

	private static String joinPairs(Map<String, Integer> values)
	{
		if (values == null || values.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : values.entrySet())
		{
			if (e.getKey() == null || e.getValue() == null)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append(':').append(e.getValue().intValue());
		}
		return sb.toString();
	}

	private static String joinEffects(List<ContentPack.Effect> values)
	{
		if (values == null || values.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (ContentPack.Effect e : values)
		{
			if (e == null || e.name == null || e.name.trim().isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.name).append(':').append(e.delta);
			if (e.effects != null && !e.effects.isEmpty())
			{
				sb.append(':').append(String.join("+", e.effects));
			}
		}
		return sb.toString();
	}

	// ------------------------------------------------------- shared: magic gate

	private ContentPack.Gate gate()
	{
		return pack.shared == null ? new ContentPack.Gate() : pack.shared.gate;
	}

	private ContentPack.Anims anims()
	{
		return pack.shared == null ? new ContentPack.Anims() : pack.shared.anims;
	}

	private ContentPack.Lockdown lockdown()
	{
		return pack.shared == null ? new ContentPack.Lockdown() : pack.shared.lockdown;
	}

	private ContentPack.Varps varps()
	{
		return pack.shared == null ? new ContentPack.Varps() : pack.shared.varps;
	}

	private ContentPack.Cannon cannon()
	{
		return pack.shared == null || pack.shared.cannon == null
			? new ContentPack.Cannon() : pack.shared.cannon;
	}

	/**
	 * The magic-attack gate is identical for every god, by design. It is still gated on a god having
	 * been chosen, so the plugin stays inert before the oath.
	 */
	public boolean enforceMagicAttack()
	{
		return isActive() && gate().enforceMagicAttack;
	}

	public int magicAttackMinimum()
	{
		return gate().magicAttackMinimum;
	}

	public int magicAttackThreshold()
	{
		return gate().magicAttackThreshold;
	}

	// ------------------------------------------------------ shared: animations

	public String meleeAnimationIds()
	{
		return joinIds(anims().meleeAnimationIds);
	}

	public String rangedAnimationIds()
	{
		return joinIds(anims().rangedAnimationIds);
	}

	public String targetCastAnimationIds()
	{
		return joinIds(anims().targetCastAnimationIds);
	}

	public String curseCastAnimationIds()
	{
		return joinIds(anims().curseCastAnimationIds);
	}

	public String selfCastAnimationIds()
	{
		return joinIds(anims().selfCastAnimationIds);
	}

	public String sitAnimationIds()
	{
		return joinIds(anims().sitAnimationIds);
	}

	public String blockAnimationIds()
	{
		return joinIds(anims().blockAnimationIds);
	}

	// -------------------------------------------------------- shared: lockdown

	/** Menu options treated as teleports and stripped during CRITICAL OVERLOAD. */
	public String teleportActions()
	{
		return join(lockdown().teleportActions);
	}

	// -------------------------------------------------------- shared: the cannon

	/**
	 * Whether the Dwarf multicannon window is being enforced.
	 *
	 * <p>Gated the same way the magic gate is: a restriction belongs to the faction system, so it is
	 * inert before an oath is sworn.
	 */
	public boolean enforceCannonLevel()
	{
		return isActive() && cannon().enforceCannonLevel;
	}

	/** The first Ranged level at which a cannon may no longer be placed. */
	public int cannonMaxRangedLevel()
	{
		return cannon().maxRangedLevel;
	}

	/** The menu options that place a cannon, as a comma-separated list. */
	public String cannonSetupActions()
	{
		return join(cannon().setupActions);
	}

	/** The substring that identifies a cannon piece by name. */
	public String cannonItemKeyword()
	{
		String k = cannon().cannonItemKeyword;
		return k == null ? "" : k.trim().toLowerCase(java.util.Locale.ROOT);
	}

	// ----------------------------------------------------------- shared: varps

	public int specPercentVarpId()
	{
		return varps().specPercentVarpId;
	}

	public int specEnabledVarpId()
	{
		return varps().specEnabledVarpId;
	}

	public int poisonVarpId()
	{
		return varps().poisonVarpId;
	}

	public int reportWidgetGroup()
	{
		return varps().reportWidgetGroup;
	}

	public int bankWidgetGroup()
	{
		return varps().bankWidgetGroup;
	}

	public int bankWidgetChild()
	{
		return varps().bankWidgetChild;
	}

	public int reportWidgetChild()
	{
		return varps().reportWidgetChild;
	}

	// -------------------------------------------------------------------- gear

	/**
	 * Every piece of gear that bypasses the magic-attack gate: the shared floor plus this god's own
	 * additions.
	 *
	 * <p>Same union as {@link #rangedWeapons()}, for the same reason - gear legal under every god is
	 * named once in {@code shared} rather than copied into four profiles that then drift apart.
	 */
	public String magicGearExceptions()
	{
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		collect(all, pack.shared == null ? null : pack.shared.gearExceptions);
		collect(all, active == null ? null : active.gear.magicGearExceptions);
		// shields are armour too: listed apart only so the plugin can tell them from other gear
		collect(all, pack.shared == null ? null : pack.shared.shields);
		collect(all, active == null ? null : active.gear.shields);
		return String.join(",", all);
	}

	/** Every shield the codex names, shared and this god's, as a comma-separated list. */
	public String shields()
	{
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		collect(all, pack.shared == null ? null : pack.shared.shields);
		collect(all, active == null ? null : active.gear.shields);
		return String.join(",", all);
	}

	/** Off-hand items that earn no block PKP, as a comma-separated list. */
	public String noBlockPkpItems()
	{
		return pack.shared == null ? "" : join(pack.shared.noBlockPkpItems);
	}

	/** Off-hand items that cost PKP on a block, as name:PKP-lost pairs. */
	public String blockPenaltyItems()
	{
		return pack.shared == null ? "" : joinPairs(pack.shared.blockPenaltyItems);
	}

	/** Per-shield PKP for a block, as name:PKP pairs. */
	public String shieldBlockPkp()
	{
		return pack.shared == null ? "" : joinPairs(pack.shared.shieldBlockPkp);
	}

	public String magicGearForceBlock()
	{
		return active == null ? "" : join(active.gear.magicGearForceBlock);
	}

	// ----------------------------------------------------------------- weapons

	/**
	 * Quest equipment, which overrides every other equipment rule.
	 *
	 * <p>Read from {@code shared}, so it survives states that have no active profile: with no god
	 * chosen nothing is restricted anyway, but the list still answers truthfully, and the side
	 * panel can show it without an oath in force.
	 */
	public List<ContentPack.QuestItem> questItems()
	{
		return pack.shared == null || pack.shared.questItems == null
			? java.util.Collections.emptyList()
			: java.util.Collections.unmodifiableList(pack.shared.questItems);
	}

	/**
	 * Every weapon barred under this god: the shared ban floor plus this god's own bans.
	 *
	 * <p>The one union that <em>subtracts</em>. The shared floor is a default rather than an
	 * absolute: a weapon on it is dropped for any god that names the same weapon in its own
	 * {@code weapons.weaponExceptions} or {@code ranged.rangedWeapons}, so a god lifts the
	 * floor by allowing the weapon rather than by editing the floor. That is how Zaros keeps the
	 * charged sceptres the other three are barred from without the staff list being copied into
	 * four profiles.
	 *
	 * <p>A god's <b>own</b> bans are not subtractable - they are added after the carve-out, so a
	 * profile that both bans and allows the same weapon bans it. A denial the god wrote itself
	 * outranks its own allow-list; otherwise authoring one would silently cancel the other
	 * depending on which list you edited last.
	 */
	public String blockedWeapons()
	{
		if (active == null)
		{
			return "";
		}
		java.util.Set<String> allowed = new java.util.HashSet<>();
		addLower(allowed, active.weapons.weaponExceptions);
		addLower(allowed, active.ranged.rangedWeapons);

		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		List<String> floor = pack.shared == null ? java.util.Collections.<String>emptyList()
			: pack.shared.blockedWeapons;
		for (String s : floor)
		{
			if (s != null && !s.trim().isEmpty()
				&& !allowed.contains(s.trim().toLowerCase(java.util.Locale.ROOT)))
			{
				all.add(s.trim());
			}
		}
		collect(all, active.weapons.blockedWeapons);
		return String.join(",", all);
	}

	/** Only this god's own weapon bans, without the shared powered-staff floor. */
	public String godBlockedWeapons()
	{
		return active == null ? "" : join(active.weapons.blockedWeapons);
	}

	/** Lower-cased, blank-free view of a list, for the carve-out test above. */
	private static void addLower(java.util.Set<String> into, List<String> from)
	{
		if (from == null)
		{
			return;
		}
		for (String s : from)
		{
			if (s != null && !s.trim().isEmpty())
			{
				into.add(s.trim().toLowerCase(java.util.Locale.ROOT));
			}
		}
	}

	/**
	 * Every weapon exempt from the magic-attack gate: the shared floor plus this god's additions.
	 *
	 * <p>The third of the three unions, alongside {@link #rangedWeapons()} and
	 * {@link #magicGearExceptions()}, and for the same reason - a weapon legal under every god is
	 * named once in {@code shared} instead of copied into four profiles that then drift. The list
	 * also decides which weapons earn the exception PKP-per-hit.
	 */
	public String weaponExceptions()
	{
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		collect(all, pack.shared == null ? null : pack.shared.weaponExceptions);
		collect(all, active == null ? null : active.weapons.weaponExceptions);
		return String.join(",", all);
	}

	public int weaponExceptionDefaultPkp()
	{
		return active == null ? 0 : active.weapons.weaponExceptionDefaultPkp;
	}

	public String pkpWeapons()
	{
		return active == null ? "" : joinPairs(active.weapons.pkpWeapons);
	}

	// ------------------------------------------------------------------ ranged

	/**
	 * Every ranged weapon in force: the shared floor plus this god's own additions.
	 *
	 * <p>A union rather than an override in either direction, so a weapon legal for everyone is
	 * named once in {@code shared} and a god's list carries only what it adds. The shared half
	 * answers even with no god in force, which keeps the ranged PKP restore working before an oath
	 * is sworn.
	 */
	public String rangedWeapons()
	{
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		collect(all, pack.shared == null ? null : pack.shared.rangedWeapons);
		collect(all, active == null ? null : active.ranged.rangedWeapons);
		return String.join(",", all);
	}

	/** Adds the non-blank entries of a list, ignoring case-only repeats between the two sources. */
	private static void collect(java.util.LinkedHashSet<String> into, List<String> from)
	{
		if (from == null)
		{
			return;
		}
		for (String s : from)
		{
			if (s == null || s.trim().isEmpty())
			{
				continue;
			}
			String t = s.trim();
			boolean seen = false;
			for (String have : into)
			{
				if (have.equalsIgnoreCase(t))
				{
					seen = true;
					break;
				}
			}
			if (!seen)
			{
				into.add(t);
			}
		}
	}

	public boolean rangedRestoreEnabled()
	{
		return active != null && active.ranged.rangedRestoreEnabled;
	}

	public int rangedRestore()
	{
		return active == null ? 0 : active.ranged.rangedRestore;
	}

	/**
	 * Per-weapon ranged restores, which are the same under every god.
	 *
	 * <p>Read straight off {@code shared}, like {@link #specAttackWeapons()} and for the same
	 * reason: what a bow pays per hit is a property of the bow. Answers with no god in force too.
	 */
	public String pkpRangedWeapons()
	{
		return pack.shared == null ? "" : joinPairs(pack.shared.pkpRangedWeapons);
	}

	// -------------------------------------------------------------------- spec

	/**
	 * The spec table, which is the same under every god.
	 *
	 * <p>Read straight off {@code shared} rather than unioned with a per-god half, because there is
	 * no per-god half any more: what a special attack does is a fact about the weapon. Answers with
	 * no god in force too, like the other weapon-fact lists.
	 */
	public String specAttackWeapons()
	{
		return pack.shared == null ? "" : joinEffects(pack.shared.specAttackWeapons);
	}

	/**
	 * The sustained-freeze pairings, as {@code weapon:spell+spell,weapon2:spell}.
	 *
	 * <p>Shared and unconditional, like the spec table beside it: which spells a staff is paired
	 * with is a fact about the staff. An entry with no spells still sustains on that weapon's melee.
	 */
	public String sustainedFreeze()
	{
		if (pack.shared == null || pack.shared.sustainedFreeze == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (ContentPack.SustainedFreeze e : pack.shared.sustainedFreeze)
		{
			if (e == null || e.weapon == null || e.weapon.trim().isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.weapon.trim());
			if (e.spells != null && !e.spells.isEmpty())
			{
				sb.append(':').append(String.join("+", e.spells));
			}
		}
		return sb.toString();
	}

	public int illegalClearSeconds()
	{
		return active == null ? 6 : active.spec.illegalClearSeconds;
	}

	public boolean illegalRepeatActions()
	{
		return active != null && active.spec.illegalRepeatActions;
	}

	public boolean bigMoveTriggers()
	{
		return active != null && active.spec.bigMoveTriggers;
	}

	// -------------------------------------------------------------------- pool

	public int maxPkp()
	{
		return active == null ? 100 : Math.max(1, active.pool.maxPkp);
	}

	/**
	 * This god's ceiling-raising equipment, as {@code name:bonus} pairs.
	 *
	 * <p>Per faction by design: the pairing of a god book to a god is the whole rule. Empty with no
	 * god in force.
	 */
	public String maxPkpItems()
	{
		return active == null ? "" : joinPairs(active.pool.maxPkpItems);
	}

	public int combatSpellCost()
	{
		return active == null ? 0 : active.pool.combatSpellCost;
	}

	public int curseSpellCost()
	{
		return active == null ? 0 : active.pool.curseSpellCost;
	}

	public String curseSpells()
	{
		return active == null ? "" : join(active.pool.curseSpells);
	}

	public int meleeRestore()
	{
		return active == null ? 0 : active.pool.meleeRestore;
	}

	/** PKP for each block animation; 0 for a god that does not reward blocking. */
	public int blockRestore()
	{
		return active == null ? 0 : active.pool.blockRestore;
	}

	public boolean poisonCostEnabled()
	{
		return active != null && active.pool.poisonCostEnabled;
	}

	public int poisonCostPercent()
	{
		return active == null ? 0 : active.pool.poisonCostPercent;
	}

	// ----------------------------------------------------------------- scaling

	public boolean magicScalingEnabled()
	{
		return active != null && active.scaling.magicScalingEnabled;
	}

	public int magicBonus50()
	{
		return active == null ? 0 : active.scaling.magicBonus50;
	}

	public int magicBonus75()
	{
		return active == null ? 0 : active.scaling.magicBonus75;
	}

	public int magicBonus85()
	{
		return active == null ? 0 : active.scaling.magicBonus85;
	}

	public int magicBonus88()
	{
		return active == null ? 0 : active.scaling.magicBonus88;
	}

	public int magicBonus90()
	{
		return active == null ? 0 : active.scaling.magicBonus90;
	}

	public int magicBonus95()
	{
		return active == null ? 0 : active.scaling.magicBonus95;
	}

	public int magicBonus99()
	{
		return active == null ? 0 : active.scaling.magicBonus99;
	}

	// ------------------------------------------------------------------- regen

	public boolean oocRegenEnabled()
	{
		return active != null && active.regen.oocRegenEnabled;
	}

	/** The out-of-combat wait, in game ticks, as the pack authors it. */
	public int oocThresholdTicks()
	{
		return active == null ? 7 : Math.max(0, active.regen.oocThresholdTicks);
	}

	/** The same wait in milliseconds, which is what the clock arithmetic needs. */
	public long oocThresholdMs()
	{
		return Math.round(oocThresholdTicks() * TICK_MS);
	}

	/** How long out of combat before OVERLOAD lapses, in game ticks. */
	public int overloadClearTicks()
	{
		return active == null ? 7 : Math.max(0, active.regen.overloadClearTicks);
	}

	public long overloadClearMs()
	{
		return Math.round(overloadClearTicks() * TICK_MS);
	}

	public int oocRestoreSeconds()
	{
		return active == null ? 10 : Math.max(1, active.regen.oocRestoreSeconds);
	}

	public boolean sitDoublesRegen()
	{
		return active != null && active.regen.sitDoublesRegen;
	}

	// -------------------------------------------------------------- restrictions

	/**
	 * Every other god's spells - the ones the god in force is barred from.
	 *
	 * <p>Derived rather than authored. Each profile names only the spells it owns, and this unions
	 * the rest, so a spell appears once in the pack and the four block lists can never drift out of
	 * step with each other. A spell the active god also claims is never blocked, whoever else lists
	 * it, which makes a shared spell a matter of listing it on both sheets rather than a special
	 * case in the code.
	 *
	 * <p>Empty with no god in force: this is a faction rule.
	 */
	public String blockedGodSpells()
	{
		if (active == null)
		{
			return "";
		}
		java.util.Set<String> mine = new java.util.HashSet<>();
		for (String s : active.restrict.godSpells)
		{
			if (s != null)
			{
				mine.add(s.trim().toLowerCase(java.util.Locale.ROOT));
			}
		}
		java.util.LinkedHashSet<String> blocked = new java.util.LinkedHashSet<>();
		for (ContentPack.Profile p : pack.profiles.values())
		{
			if (p == null || p == active || p.restrict == null)
			{
				continue;
			}
			for (String s : p.restrict.godSpells)
			{
				if (s != null && !s.trim().isEmpty()
					&& !mine.contains(s.trim().toLowerCase(java.util.Locale.ROOT)))
				{
					blocked.add(s.trim());
				}
			}
		}
		return String.join(",", blocked);
	}

	public boolean overloadBlocksAttack()
	{
		return active != null && active.restrict.overloadBlocksAttack;
	}

	public boolean blockFoodWhenOverloaded()
	{
		return active != null && active.restrict.blockFoodWhenOverloaded;
	}

	public boolean blockPotionsWhenDepleted()
	{
		return active != null && active.restrict.blockPotionsWhenDepleted;
	}

	public String consumableExceptions()
	{
		return active == null ? "" : join(active.restrict.consumableExceptions);
	}

	public boolean illegalDefaultUse()
	{
		return active != null && active.restrict.illegalDefaultUse;
	}

	public boolean blockCastWhileSeated()
	{
		return active != null && active.restrict.blockCastWhileSeated;
	}

	public boolean blockAttackWhileSeated()
	{
		return active != null && active.restrict.blockAttackWhileSeated;
	}

	public String restrictionExemptItems()
	{
		return active == null ? "" : join(active.restrict.restrictionExemptItems);
	}

	// ------------------------------------------------------------- consumables

	public String pkpRestoreItems()
	{
		return active == null ? "" : joinPairs(active.consumables.pkpRestoreItems);
	}

	public String consumableEffects()
	{
		return active == null ? "" : joinEffects(active.consumables.consumableEffects);
	}

	// -------------------------------------------------------------- overcharge

	public boolean overchargeEnabled()
	{
		return active != null && active.overcharge.overchargeEnabled;
	}

	public int overchargeHeadroom()
	{
		return active == null ? 0 : active.overcharge.overchargeHeadroom;
	}

	public String overchargeItems()
	{
		return active == null ? "" : join(active.overcharge.overchargeItems);
	}

	// ---------------------------------------------------------------- pacifist

	/**
	 * Every item that disarms you while carried: the shared floor plus this god's own additions.
	 *
	 * <p>The fourth of the unions, alongside {@link #rangedWeapons()},
	 * {@link #magicGearExceptions()} and {@link #weaponExceptions()} - a skilling tool that
	 * disarms under every god is named once in {@code shared} rather than copied into four
	 * profiles that then drift apart.
	 *
	 * <p>Still gated on a god being in force, unlike the ranged floor. This one is a restriction,
	 * and nothing restricts before an oath is sworn; answering here with no god would start
	 * disarming players the rest of the plugin has decided not to govern.
	 */
	public String pacifistItems()
	{
		if (active == null)
		{
			return "";
		}
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
		collect(all, pack.shared == null ? null : pack.shared.pacifistItems);
		collect(all, active.pacifist.pacifistItems);
		return String.join(",", all);
	}

}
