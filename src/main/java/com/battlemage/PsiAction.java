package com.battlemage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The three groups an action can fall into for PKP purposes. These are cost buckets only - they carry no
 * display name, because cast cards are labelled with the spell's own spellbook name.
 *
 * <p>{@link #SPELLS} is the single source of truth for spell recognition: every combat spell in every
 * spellbook appears there once. (Each entry still carries its old Magic-level tier schedule, but costs
 * no longer change with level: every spell in a family costs the same.) Membership of {@link #CURSE} is not decided here at all - it comes from the editable
 * curse-spell list in the config, so the group can be re-scoped without touching the code.</p>
 */
public enum PsiAction
{
	/** Any melee attack. Restores PKP. */
	MELEE,
	/** Any spell that damages an opponent, in any spellbook. */
	COMBAT,
	/** Spells that target an opponent but only debuff or root it. */
	CURSE;

	// ---------------------------------------------------------------- per-spell metadata

	/**
	 * Cost-tier schedule for one spell. {@code thresholds} are ascending Magic levels and {@code tiers} the
	 * cost tier (0 = newest/base, 1 = first reduction, 2 = second reduction) that applies at and above each.
	 */
	public static final class SpellMeta
	{
		private final int[] thresholds;
		private final int[] tiers;

		SpellMeta(int[] thresholds, int[] tiers)
		{
			this.thresholds = thresholds;
			this.tiers = tiers;
		}

		/** Cost tier (0/1/2) at the given Magic level. */
		public int tierAt(int level)
		{
			int t = 0;
			for (int i = 0; i < thresholds.length; i++)
			{
				if (level >= thresholds[i])
				{
					t = tiers[i];
				}
			}
			return t;
		}
	}

	/** Tier ladders: a 5-spell elemental line steps down twice, the top spell of a line only once. */
	private static final int[] STEP = {0, 1, 2};
	private static final int[] STEP2 = {0, 1};
	private static final int[] FLAT = new int[0];

	private static final Map<String, SpellMeta> SPELLS = buildSpells();

	private static Map<String, SpellMeta> buildSpells()
	{
		Map<String, SpellMeta> m = new HashMap<>();

		// ---- standard: wind line
		put(m, "Wind Strike", new int[]{1, 17, 41}, STEP);
		put(m, "Wind Bolt", new int[]{17, 41, 62}, STEP);
		put(m, "Wind Blast", new int[]{41, 62, 81}, STEP);
		put(m, "Wind Wave", new int[]{62, 81, 99}, STEP);
		put(m, "Wind Surge", new int[]{81, 99}, STEP2);

		// ---- standard: water line
		put(m, "Water Strike", new int[]{5, 23, 47}, STEP);
		put(m, "Water Bolt", new int[]{23, 47, 65}, STEP);
		put(m, "Water Blast", new int[]{47, 65, 85}, STEP);
		put(m, "Water Wave", new int[]{65, 85, 99}, STEP);
		put(m, "Water Surge", new int[]{85, 99}, STEP2);

		// ---- standard: earth line
		put(m, "Earth Strike", new int[]{9, 29, 53}, STEP);
		put(m, "Earth Bolt", new int[]{29, 53, 70}, STEP);
		put(m, "Earth Blast", new int[]{53, 70, 90}, STEP);
		put(m, "Earth Wave", new int[]{70, 90, 99}, STEP);
		put(m, "Earth Surge", new int[]{90, 99}, STEP2);

		// ---- standard: fire line
		put(m, "Fire Strike", new int[]{13, 35, 59}, STEP);
		put(m, "Fire Bolt", new int[]{35, 59, 75}, STEP);
		put(m, "Fire Blast", new int[]{59, 75, 95}, STEP);
		put(m, "Fire Wave", new int[]{75, 95, 99}, STEP);
		put(m, "Fire Surge", new int[]{95, 99}, STEP2);

		// ---- standard: god spells + one-offs
		put(m, "Crumble Undead", FLAT, FLAT);
		put(m, "Iban Blast", FLAT, FLAT);
		put(m, "Magic Dart", FLAT, FLAT);
		put(m, "Slayer Dart", FLAT, FLAT);
		put(m, "Saradomin Strike", FLAT, FLAT);
		put(m, "Claws of Guthix", FLAT, FLAT);
		put(m, "Flames of Zamorak", FLAT, FLAT);
		put(m, "Charge", FLAT, FLAT);

		// ---- ancient: smoke line
		put(m, "Smoke Rush", new int[]{50, 62, 74}, STEP);
		put(m, "Smoke Burst", new int[]{62, 74, 86}, STEP);
		put(m, "Smoke Blitz", new int[]{74, 86, 99}, STEP);
		put(m, "Smoke Barrage", new int[]{86, 99}, STEP2);

		// ---- ancient: shadow line
		put(m, "Shadow Rush", new int[]{52, 64, 76}, STEP);
		put(m, "Shadow Burst", new int[]{64, 76, 88}, STEP);
		put(m, "Shadow Blitz", new int[]{76, 88, 99}, STEP);
		put(m, "Shadow Barrage", new int[]{88, 99}, STEP2);

		// ---- ancient: blood line
		put(m, "Blood Rush", new int[]{56, 68, 80}, STEP);
		put(m, "Blood Burst", new int[]{68, 80, 92}, STEP);
		put(m, "Blood Blitz", new int[]{80, 92, 99}, STEP);
		put(m, "Blood Barrage", new int[]{92, 99}, STEP2);

		// ---- ancient: ice line
		put(m, "Ice Rush", new int[]{58, 70, 82}, STEP);
		put(m, "Ice Burst", new int[]{70, 82, 94}, STEP);
		put(m, "Ice Blitz", new int[]{82, 94, 99}, STEP);
		put(m, "Ice Barrage", new int[]{94, 99}, STEP2);

		// ---- arceuus: grasps + demonbanes
		put(m, "Ghostly Grasp", new int[]{35, 55, 79}, STEP);
		put(m, "Skeletal Grasp", new int[]{56, 79}, STEP2);
		put(m, "Undead Grasp", FLAT, FLAT);
		put(m, "Inferior Demonbane", new int[]{44, 62, 82}, STEP);
		put(m, "Superior Demonbane", new int[]{62, 82}, STEP2);
		put(m, "Dark Demonbane", FLAT, FLAT);

		// ---- debuff / root spells. Registered so they are recognised as combat spells; the curse-spell
		// list in the config decides which of them are charged the curse cost instead of the combat cost.
		put(m, "Confuse", FLAT, FLAT);
		put(m, "Weaken", FLAT, FLAT);
		put(m, "Curse", FLAT, FLAT);
		put(m, "Vulnerability", FLAT, FLAT);
		put(m, "Enfeeble", FLAT, FLAT);
		put(m, "Stun", FLAT, FLAT);
		put(m, "Bind", FLAT, FLAT);
		put(m, "Snare", FLAT, FLAT);
		put(m, "Entangle", FLAT, FLAT);
		put(m, "Teleport Block", FLAT, FLAT);
		put(m, "Tele Block", FLAT, FLAT);

		return Collections.unmodifiableMap(m);
	}

	private static void put(Map<String, SpellMeta> m, String name, int[] th, int[] ti)
	{
		m.put(name.toLowerCase(Locale.ROOT), new SpellMeta(th, ti));
	}

	public static SpellMeta spellMeta(String name)
	{
		return name == null ? null : SPELLS.get(name.toLowerCase(Locale.ROOT).trim());
	}

	// ---------------------------------------------------------------- combat-spell recognition

	/** Every spell in the table is a combat spell except the self-cast Charge buff. */
	public static boolean isKnownCombatSpell(String name)
	{
		return spellMeta(name) != null && !"charge".equals(name.toLowerCase(Locale.ROOT).trim());
	}
}
