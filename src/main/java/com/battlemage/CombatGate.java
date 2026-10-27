package com.battlemage;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The combat-level limits on the oath:
 * <ul>
 *   <li>Battle-Mage Mode can only be begun for the first time at combat level
 *       {@value #START_MAX_COMBAT} or lower.</li>
 *   <li>From combat level {@value #LOCK_COMBAT} a faction can no longer be denounced, and a player
 *       who has denounced one can only swear back to that same faction.</li>
 * </ul>
 * The level itself is kept up to date by the plugin on each game tick, so the side panel (which
 * runs on the Swing thread) can read it safely.
 */
@Singleton
class CombatGate
{
	static final int START_MAX_COMBAT = 33;
	static final int LOCK_COMBAT = 88;
	/** From this level up to the lock, each level-up warns that the lock is coming. */
	static final int WARN_FROM = 85;

	static final String START_NOTICE = "You must be Combat Level " + START_MAX_COMBAT
		+ " or lower to begin Battle-Mage Mode.";
	static final String CHANGE_NOTICE = "You must be Combat Level " + (LOCK_COMBAT - 1)
		+ " or lower to change factions.";
	static final String DENOUNCE_TIP = "(Must be below Combat LVL " + LOCK_COMBAT + ")";

	private final Oath oath;
	private volatile int combat;

	@Inject
	CombatGate(Oath oath)
	{
		this.oath = oath;
	}

	void setCombatLevel(int level)
	{
		combat = level;
	}

	/** The player's combat level, or 0 before it is known. */
	int combatLevel()
	{
		return combat;
	}

	/** Too high to begin the mode for the first time. */
	boolean tooHighToStart()
	{
		return !oath.hasEverSworn() && combat > START_MAX_COMBAT;
	}

	/** At or past the level where a faction can no longer be denounced. */
	boolean denounceLocked()
	{
		return combat >= LOCK_COMBAT;
	}

	/**
	 * The only faction this player may swear to, or null when any is open: past the lock, someone
	 * who has denounced a faction can only return to it.
	 */
	Faction lockedTo()
	{
		return !oath.isSworn() && denounceLocked() ? oath.getPrevious() : null;
	}

	static String warning(int level)
	{
		return "You are now Combat Level " + level + ". At Combat Level " + LOCK_COMBAT
			+ " you can no longer denounce your faction.";
	}
}
