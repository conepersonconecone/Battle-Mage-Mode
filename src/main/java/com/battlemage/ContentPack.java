package com.battlemage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deserialised shape of {@code /battlemage-codex.json} - the bundled content pack that holds every
 * back-end rule the plugin enforces.
 *
 * <p>Nothing here is user-editable at runtime. The pack ships inside the jar, is authored from the
 * Battle-Mage Codex form, and is the single source of truth for equipment lists, animation IDs,
 * PKP costs and gains, spec weapons, spec effects, item effects and every allowed-equipment
 * exception. Only visual customisation remains in {@link BattleMageConfig}.
 *
 * <p>Field names match the JSON keys exactly, which in turn match the old config key names, so a
 * value can be traced from the codex form through to the call site that reads it.
 */
public class ContentPack
{
	public int formatVersion;

	public Shared shared = new Shared();

	/**
	 * Keyed by faction alone, e.g. {@code SARADOMIN}.
	 */
	public Map<String, Profile> profiles = new LinkedHashMap<>();

	public Profile profile(String key)
	{
		return profiles.get(key);
	}

	// ------------------------------------------------------------------ shared

	/** Rules that are identical for every faction. */
	public static class Shared
	{
		/**
		 * Equipment every god lets you wear, whatever else the pack says.
		 *
		 * <p>Quest gear: the flail a quest hands you, the staff a teleport needs, the amulet that
		 * lets you talk to a ghost. Shared rather than per-god because a quest does not care which
		 * god you swore to, and being unable to finish one because of a plugin rule is not a
		 * difficulty setting - it is a dead end.
		 *
		 * <p><b>This list wins.</b> It is checked before every other equipment rule: the
		 * magic-attack gate, the gear force-block, the per-god blocked weapons, blanket weapon
		 * blocking and the pacifist class. An item here is equippable, is never marked off-limits,
		 * and never triggers pacifism, whichever god is in force and whatever other list it also
		 * appears on.
		 *
		 * <p>Scope is deliberately equipment. It does not lift the CRITICAL OVERLOAD lockdown, which
		 * keeps its own one-item exemption on each profile, and it says nothing about food or
		 * potions.
		 *
		 * <p>An entry may name the quests it belongs to, in which case it is only a quest item while
		 * one of them is <b>in progress</b>. See {@link QuestItem}.
		 */
		public List<QuestItem> questItems = new ArrayList<>();

		/**
		 * Ranged weapons every god permits - the common floor.
		 *
		 * <p>Unioned with each profile's own {@code ranged.rangedWeapons}, so this is where a weapon
		 * that is legal for everyone is named once, and a profile's list is for that god's additions
		 * on top. Neither replaces the other.
		 *
		 * <p>An allow-list, not an override: it sits with the other permissions in the equipment
		 * chain, so {@link Weapons#blockedWeapons} still bars anything named on both. Being here also
		 * makes a weapon eligible for the ranged PKP restore, which is what makes ranged worth
		 * carrying at all - the per-weapon amounts stay per-god in {@code ranged.pkpRangedWeapons}.
		 */
		public List<String> rangedWeapons = new ArrayList<>();

		/**
		 * Equipment every god lets you wear, bypassing the default rules.
		 *
		 * <p>The shared twin of {@link Gear#magicGearExceptions}, and unioned with it: gear that is
		 * legal for everyone is named once here, and a profile's list carries that god's additions.
		 *
		 * <p>"Default rules" means the two that apply automatically to everything - the magic-attack
		 * gate and the blanket weapon block. It does <b>not</b> beat a god's explicitly authored
		 * denials, {@link Gear#magicGearForceBlock} and {@link Weapons#blockedWeapons}, which sit
		 * above every allow-list: a god that names a piece there has said something deliberate about
		 * it, and this list is a floor, not an override. {@link Shared#questItems} is the override.
		 */
		public List<String> gearExceptions = new ArrayList<>();

		/**
		 * Shields every god lets you wear - armour, kept in its own list so the plugin knows which
		 * pieces are shields. Legal exactly like {@link #gearExceptions}; unioned with each god's
		 * {@link Gear#shields}.
		 */
		public List<String> shields = new ArrayList<>();

		/**
		 * PKP gained per block while wearing this shield, as {@code name -> PKP}. Replaces the god's
		 * {@link Pool#blockRestore} for that block, under every god - including gods whose blockRestore
		 * is 0. A shield missing here blocks for the god's usual amount.
		 */
		public Map<String, Integer> shieldBlockPkp = new LinkedHashMap<>();

		/**
		 * Off-hand items that earn no PKP from blocking at all - god d'hide shields and defenders. Beats the god's
		 * {@link Pool#blockRestore} and any {@link #shieldBlockPkp} amount.
		 */
		public List<String> noBlockPkpItems = new ArrayList<>();

		/**
		 * Off-hand items that COST PKP when you block with them, as {@code name -> PKP lost} (the books
		 * and the Antler guard, 33 each by default). Applies under every god and beats every other
		 * block rule. Like a spell cost, it can take the bar below zero.
		 */
		public Map<String, Integer> blockPenaltyItems = new LinkedHashMap<>();

		/**
		 * Weapons every god lets you wield - the common floor.
		 *
		 * <p>The shared twin of {@link Weapons#weaponExceptions}, unioned with it exactly as
		 * {@link #gearExceptions} is with the gear list. A weapon legal for everyone is named once
		 * here; a profile's list carries that god's additions.
		 *
		 * <p>Deliberately separate from {@link #questItems}. Both make a weapon wieldable, but they
		 * are different statements: this one says "the gate does not apply to this weapon", and a
		 * god can still overrule it with {@link Weapons#blockedWeapons} or
		 * {@link Gear#magicGearForceBlock}. The quest list says "no ruleset may block this", and
		 * nothing overrules it. A weapon that is merely allowed belongs here; a weapon a quest
		 * requires belongs there.
		 */
		public List<String> weaponExceptions = new ArrayList<>();

		/**
		 * Items that bar every attack and every combat spell while carried - the common floor.
		 *
		 * <p>The shared twin of {@link Pacifist#pacifistItems}, unioned with it exactly as the three
		 * lists above are with theirs. The odd one of the four in that it is a <em>ban</em> rather
		 * than a permission: a skilling tool named here disarms you under every god, and a profile's
		 * list adds only the ones that god disarms on top.
		 *
		 * <p>Quest items still beat it, as they beat every other rule.
		 */
		public List<String> pacifistItems = new ArrayList<>();

		/**
		 * Weapons no god lets you wield - the common ban floor.
		 *
		 * <p>The powered staves live here: they pass the magic-attack gate with room to spare, so
		 * nothing else in the pack would ever stop them, and "cast with the spellbook, not with a
		 * staff that casts for you" is a rule of the mode rather than of any one god.
		 *
		 * <p><b>A god can lift it.</b> Naming a weapon in that god's own
		 * {@link Weapons#weaponExceptions} or {@link Ranged#rangedWeapons} removes it from this floor
		 * for that god only - which is how Zaros keeps the charged Thammaron's and Accursed sceptres
		 * while the other three stay barred from them, without copying the staff list into four
		 * profiles. A god's own {@link Weapons#blockedWeapons} cannot be lifted that way: a denial the
		 * god wrote itself outranks its own allow-list.
		 *
		 * <p>Quest items beat this, as they beat every other rule.
		 */
		public List<String> blockedWeapons = new ArrayList<>();

		/**
		 * Spec weapons, their PKP delta and the effects they fire - for every god.
		 *
		 * <p>Shared because a special attack is a property of the weapon, not of a doctrine: the
		 * Dragon battleaxe buys three attacks of overload immunity whoever is holding it. The four
		 * profiles carried identical copies of this table; what differs between gods is which of
		 * these weapons they let you <em>hold</em>, and that is the exception lists' job.
		 *
		 * <p><b>Being listed here does not make a weapon equippable.</b> The table says what happens
		 * when the spec fires, nothing more - a weapon still has to clear the magic-attack gate or
		 * sit on an allow-list like any other. Listing the Dragon claws' effect is not the same
		 * statement as letting Saradomin wield them.
		 */
		public List<Effect> specAttackWeapons = new ArrayList<>();

		/**
		 * Ranged weapons whose hit is worth something other than the base restore, as
		 * {@code name -> PKP}. Shared for the same reason the spec table is: how much a weapon
		 * returns per hit is a fact about the weapon, not about the god holding it.
		 *
		 * <p>Anything not named here uses {@link Ranged#rangedRestore}. A <b>negative</b> amount is
		 * allowed and means the weapon costs PKP to fire rather than paying for it.
		 *
		 * <p>Like the spec table, being named here is not a permission - the weapon still has to be
		 * on a ranged or weapon allow-list to be wielded at all.
		 */
		public Map<String, Integer> pkpRangedWeapons = new LinkedHashMap<>();

		/**
		 * Weapons whose freeze holds for as long as you keep using them, instead of for a fixed time.
		 *
		 * <p>A freeze armed by one of these weapons has no clock. It lasts while you keep attacking
		 * with that same weapon's melee or casting one of the spells named beside it, and ends the
		 * moment you cast anything else, attack with anything else, or drop out of combat for
		 * {@code regen.oocThresholdTicks}. The three god staves are what this exists for: the
		 * staff and its own god spell are a pair, and using one without the other breaks it.
		 *
		 * <p>Shared, like the spec table itself, because which spells a staff is paired with is a
		 * fact about the staff and not about anyone's doctrine. A weapon NOT named here keeps the
		 * ordinary timed freeze.
		 */
		public List<SustainedFreeze> sustainedFreeze = new ArrayList<>();

		public Gate gate = new Gate();
		public Anims anims = new Anims();
		public Varps varps = new Varps();
		public Lockdown lockdown = new Lockdown();
		public Cannon cannon = new Cannon();
	}

	/**
	 * The Dwarf multicannon window: it may be set up only while Ranged is still low.
	 *
	 * <p>Shared, because it is a rule about the run rather than about any god's doctrine, and a
	 * window rather than a requirement - the usual shape is "you need level X", this one is "you may
	 * only while you are under level X", which is why it reads as a maximum.
	 */
	public static class Cannon
	{
		public boolean enforceCannonLevel = true;

		/**
		 * The first Ranged level at which the cannon is no longer yours to place. Strictly a ceiling:
		 * 45 means 44 may set one up and 45 may not.
		 */
		public int maxRangedLevel = 45;

		/**
		 * The menu options that place a cannon. Matched case-insensitively against the whole option.
		 * Only these are removed - Fire, Pick-up, Repair and the rest are untouched, so a cannon
		 * already on the ground keeps working after the window closes.
		 */
		public List<String> setupActions = new ArrayList<>();

		/**
		 * Matched as a substring of the item's name, lower-cased. A keyword rather than a list of
		 * ids because the pieces, the packed set and every ornamental re-skin all carry "cannon" in
		 * the name, and an id list that missed one would be a rule that silently never fired.
		 */
		public String cannonItemKeyword = "cannon";
	}

	/**
	 * The magic-attack gate. Deliberately shared: the weapon minimum and armour threshold are the
	 * same whichever god a player picks. Faction difficulty comes from the exception
	 * lists, costs and punishments on each profile instead.
	 */
	public static class Gate
	{
		public boolean enforceMagicAttack = true;
		public int magicAttackMinimum = 1;
		public int magicAttackThreshold = -10;
	}

	/** Animation and pose IDs. Shared because they describe the game client, not a doctrine. */
	public static class Anims
	{
		public List<Integer> meleeAnimationIds = new ArrayList<>();
		/** PKP per block with a shield that has no codex amount of its own. Negative drains (down to zero). */
		public List<Integer> blockAnimationIds = new ArrayList<>();
		public List<Integer> rangedAnimationIds = new ArrayList<>();
		public List<Integer> targetCastAnimationIds = new ArrayList<>();
		public List<Integer> curseCastAnimationIds = new ArrayList<>();
		public List<Integer> selfCastAnimationIds = new ArrayList<>();
		public List<Integer> sitAnimationIds = new ArrayList<>();
		/**
		 * Special-attack animations, for the record only: specs are paid by the energy drop with the
		 * weapon equipped, never by animation. Nothing reads this list for PKP.
		 */
		public List<Integer> specialAnimationIds = new ArrayList<>();
	}

	/**
	 * Rules for the CRITICAL OVERLOAD lockdown. Shared so the punishment reads the same whichever god
	 * a player swore to - only the exempt item, which stays per-profile, differs.
	 */
	public static class Lockdown
	{
		/**
		 * Menu options that count as a teleport. Matched case-insensitively against the whole option,
		 * so "Rub" catches jewellery and "Break" catches tablets. Every one of them is removed during
		 * CRITICAL OVERLOAD except on an item in the profile's illegal-state exempt list.
		 */
		public List<String> teleportActions = new ArrayList<>();
	}

	/** VarPlayer and widget IDs the plugin reads. Shared for the same reason as {@link Anims}. */
	public static class Varps
	{
		public int specPercentVarpId = 300;
		public int specEnabledVarpId = 301;
		public int poisonVarpId = 102;
		public int reportWidgetGroup = 162;
		public int reportWidgetChild = 33;
		/** The bank interface; the PKP bar hides while it is open. Check with the Widget Inspector. */
		public int bankWidgetGroup = 12;
		public int bankWidgetChild = 1;
	}

	// ----------------------------------------------------------------- profile

	/** One faction's ruleset. */
	public static class Profile
	{
		public String faction;

		/** The words shown for this god on the oath screen. Authored here, not in code. */
		public Lore lore = new Lore();

		public Gear gear = new Gear();
		public Weapons weapons = new Weapons();
		public Ranged ranged = new Ranged();
		public Spec spec = new Spec();
		public Pool pool = new Pool();
		public Scaling scaling = new Scaling();
		public Regen regen = new Regen();
		public Restrict restrict = new Restrict();
		public Consumables consumables = new Consumables();
		public Overcharge overcharge = new Overcharge();
		public Pacifist pacifist = new Pacifist();
	}

	/**
	 * What the oath screen and side panel call one god. Both fields are optional: a blank one falls
	 * back to the built-in text on {@link Faction}.
	 */
	public static class Lore
	{
		/** Overrides the name on the card. Leave blank to use the faction's own name. */
		public String displayName = "";

		/** The one word under the name: ORDER, BALANCE, CHAOS, DARKNESS. Upper-cased on draw. */
		public String epithet = "";
	}

	public static class Gear
	{
		public List<String> magicGearExceptions = new ArrayList<>();
		/** This god's shields - armour like {@link #magicGearExceptions}, listed apart as shields. */
		public List<String> shields = new ArrayList<>();
		public List<String> magicGearForceBlock = new ArrayList<>();
	}

	public static class Weapons
	{
		/**
		 * Weapons this god refuses, by name or id - the weapon twin of
		 * {@link Gear#magicGearForceBlock}.
		 *
		 * <p>Beats every allow-list, so a weapon named here stays blocked even if it is also a
		 * listed exception, a ranged weapon or a spec weapon. It is how one god
		 * bars another's signature weapon without disturbing the magic-attack gate that governs
		 * everything else.
		 *
		 * <p>The one thing it loses to is {@link Shared#questItems}.
		 */
		public List<String> blockedWeapons = new ArrayList<>();

		public List<String> weaponExceptions = new ArrayList<>();
		public int weaponExceptionDefaultPkp = 50;
		public Map<String, Integer> pkpWeapons = new LinkedHashMap<>();
	}

	public static class Ranged
	{
		public List<String> rangedWeapons = new ArrayList<>();
		public boolean rangedRestoreEnabled = true;
		public int rangedRestore = 33;
	}

	public static class Spec
	{
		public int illegalClearSeconds = 6;
		public boolean illegalRepeatActions = true;
		public boolean bigMoveTriggers = true;
	}

	public static class Pool
	{
		public int maxPkp = 100;
		public int combatSpellCost = 33;
		public int curseSpellCost = 25;
		public List<String> curseSpells = new ArrayList<>();
		public int meleeRestore = 50;
		/**
		 * PKP gained each time a block animation plays (see {@link Anims#blockAnimationIds}). 0 turns
		 * it off for this god. Never overcharges and never causes an overload.
		 */
		public int blockRestore = 0;
		public boolean poisonCostEnabled = true;
		public int poisonCostPercent = 50;

		/**
		 * Equipment that raises the ceiling while it is worn, as {@code name -> extra PKP}.
		 *
		 * <p>Per profile, because the point of it is that the item has to match the god: the holy
		 * book is Saradomin's answer and nobody else's. An item not named on this god's sheet does
		 * nothing for them, which is what keeps the Book of War and the Book of Law - Bandos's and
		 * Armadyl's - worth nothing to any of the four.
		 *
		 * <p>The bonus moves the overcharge ceiling with it, since that is defined as the maximum
		 * plus the headroom. Taking the item off lowers the maximum again, and a pool left above the
		 * new maximum behaves exactly like an overcharged one.
		 */
		public Map<String, Integer> maxPkpItems = new LinkedHashMap<>();
	}

	public static class Scaling
	{
		public boolean magicScalingEnabled;
		public int magicBonus50 = 10;
		public int magicBonus75 = 10;
		public int magicBonus85 = 10;
		public int magicBonus88 = 0;
		public int magicBonus90 = 10;
		public int magicBonus95 = 5;
		public int magicBonus99 = 5;
	}

	public static class Regen
	{
		public boolean oocRegenEnabled = true;

		/**
		 * How long out of combat before the pool starts refilling, in game ticks.
		 *
		 * <p>Ticks rather than seconds because that is the unit the game runs on and the unit a
		 * player counts in; seconds could not even express it once this became 4.2s, and a
		 * fractional "4.2 seconds" is a number nothing in the client would ever show you.
		 */
		public int oocThresholdTicks = 7;

		/**
		 * How long out of combat before OVERLOAD lapses on its own, in game ticks.
		 *
		 * <p>Was a hard-coded constant in the plugin and a second hard-coded copy in the rulebook
		 * generator, which is exactly the arrangement that lets a page and the thing it documents
		 * drift apart. Authored once, read by both.
		 */
		public int overloadClearTicks = 7;

		public int oocRestoreSeconds = 10;
		public boolean sitDoublesRegen = true;
	}

	public static class Restrict
	{
		/**
		 * Spells only this god may cast. Every other god is blocked from them, automatically: a spell
		 * is named once, on the sheet of the god it belongs to, and the plugin derives each faction's
		 * block list by unioning the other three. Nothing here has to be repeated as a denial, and
		 * moving a spell between gods is a single edit.
		 *
		 * <p>A god with an empty list simply owns no spells and is blocked from every other god's.
		 * Spell names are matched case-insensitively against the spellbook name, so they must be
		 * spelled the way the game spells them - {@code Iban Blast}, not {@code Iban's Blast}.
		 */
		public List<String> godSpells = new ArrayList<>();

		public boolean overloadBlocksAttack = true;
		public boolean blockFoodWhenOverloaded = true;
		public boolean blockPotionsWhenDepleted = true;
		public List<String> consumableExceptions = new ArrayList<>();
		public boolean illegalDefaultUse = true;
		public boolean blockCastWhileSeated = true;

		/**
		 * The twin of {@link #blockCastWhileSeated}, for the other half of combat.
		 *
		 * <p>Sitting already suppressed casting, because the sit pose eats the cast animation. The
		 * same is true of swinging at something: the pose means no attack animation plays, so a
		 * seated attack was a way to act without the plugin ever seeing the animation it keys off.
		 * Separately authored rather than folded into the cast flag, because they are two rules - a
		 * pack may well want seated casting barred and seated melee left alone, or the reverse.
		 */
		public boolean blockAttackWhileSeated = true;

		public List<String> restrictionExemptItems = new ArrayList<>();
	}

	public static class Consumables
	{
		public Map<String, Integer> pkpRestoreItems = new LinkedHashMap<>();
		public List<Effect> consumableEffects = new ArrayList<>();
	}

	public static class Overcharge
	{
		public boolean overchargeEnabled;
		public int overchargeHeadroom = 25;
		public List<String> overchargeItems = new ArrayList<>();
	}

	public static class Pacifist
	{
		public List<String> pacifistItems = new ArrayList<>();
	}

	/**
	 * One piece of quest equipment, and optionally the quests that make it one.
	 *
	 * <p>With {@link #quests} empty the item is unconditional: it overrides every equipment rule for
	 * the whole run, which is right for gear a quest leaves you holding for good.
	 *
	 * <p>With quests named, the override only applies while <b>at least one of them is in
	 * progress</b> - started and not yet finished. Before you start it, and the moment you finish
	 * it, the item stops being a quest item and falls back to the ordinary rules, so a quest weapon
	 * is a tool for the quest rather than a permanent way around the gate.
	 *
	 * <p>Several quests on one item is the reason this is a list: Silverlight is wanted by Demon
	 * Slayer and again by Shadow of the Storm, and naming both on the one entry is how that is
	 * said without repeating the item. Any one of them being in progress is enough.
	 *
	 * <p>Names must match the game's own quest names, which is what {@code net.runelite.api.Quest}
	 * is keyed on. A name the client does not know is logged once and then ignored, which leaves the
	 * item conditional on the quests that did resolve - or, if none did, never allowed.
	 */
	public static class QuestItem
	{
		public String item;

		public List<String> quests = new ArrayList<>();

		public QuestItem()
		{
		}

		public QuestItem(String item, List<String> quests)
		{
			this.item = item;
			this.quests = quests == null ? new ArrayList<>() : quests;
		}

		public boolean unconditional()
		{
			return quests == null || quests.isEmpty();
		}
	}

	/**
	 * A spec weapon or consumable and what it does to the bar.
	 *
	 * <p>{@code delta} is PKP gained (+) or removed (-). {@code effects} holds the tokens the
	 * plugin's effect parser understands, joined with {@code +}: {@code F<n>s}/{@code F<n>a} freeze
	 * for n seconds / attacks, {@code O<n>s}/{@code O<n>a} overload immunity, {@code D<n>s}/
	 * {@code D<n>a} depletion immunity, {@code R<gain>/<loss>} a coin flip for +gain or -loss, and
	 * {@code CO}/{@code CD} to clear an active overload/depletion. Unknown tokens are ignored.
	 */
	public static class Effect
	{
		public String name;
		public int delta;
		public List<String> effects = new ArrayList<>();
	}

	/**
	 * One weapon and the spells that keep its freeze alive.
	 *
	 * <p>{@code weapon} is matched the way every other entry is - by item id when it is a number and
	 * by lower-cased name otherwise. {@code spells} are spell names as the client spells them; the
	 * weapon's own melee attack always sustains, so a melee-only pairing can leave the list empty.
	 */
	public static class SustainedFreeze
	{
		public String weapon;
		public List<String> spells = new ArrayList<>();
	}
}
