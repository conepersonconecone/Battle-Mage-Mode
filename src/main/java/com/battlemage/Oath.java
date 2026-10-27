package com.battlemage;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The god currently in force. There is exactly one, and it can be changed at any time.
 *
 * <p>Deliberately not a profile system: nothing is locked and nothing is per-character. A player
 * running two accounts swaps the god by hand when they log into the other one - the honour system
 * does the rest. That keeps the whole thing to one stored value instead of a list of records with
 * identities, names and an active pointer.
 *
 * <p>Stored in its own config group, away from {@link BattleMageConfig#GROUP}, so it never shows up as
 * a setting in the RuneLite panel.
 */
@Slf4j
@Singleton
public class Oath
{
	static final String GROUP = "battlemageoath";
	private static final String KEY_FACTION = "faction";
	/** The faction most recently denounced, kept so a high-level player can only go back to it. */
	private static final String KEY_PREVIOUS = "previousFaction";

	private final ConfigManager configManager;

	private Faction faction;
	/** The faction denounced last, or null if this player has never left one. */
	private Faction previous;

	/**
	 * True between leaving a god and becoming faction-less in front of the Wise Old Man, so the
	 * "how to begin" prompts stay away for the moment the player is briefly unsworn.
	 */
	private volatile boolean ceremony;

	/** Called after any change that alters which rules apply, so the plugin can rebuild caches. */
	private Runnable changeListener = () ->
	{
	};

	@Inject
	public Oath(ConfigManager configManager)
	{
		this.configManager = configManager;
		load();
	}

	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener == null ? () ->
		{
		} : listener;
	}

	// ------------------------------------------------------------------ reading

	/** False until a god has been chosen; the plugin enforces nothing until then. */
	public boolean isSworn()
	{
		return faction != null;
	}

	boolean inCeremony()
	{
		return ceremony;
	}

	void setCeremony(boolean on)
	{
		ceremony = on;
	}

	public Faction getFaction()
	{
		return faction;
	}

	/** The faction denounced last, or null if this player has never left one. */
	public Faction getPrevious()
	{
		return previous;
	}

	/** Has this player ever sworn to a faction (the one now, or one since denounced)? */
	public boolean hasEverSworn()
	{
		// the faction pop-up's one-time tip is marked seen the first time anyone swears, so it also
		// covers players who denounced before the denounced faction was remembered
		Boolean tip = configManager.getConfiguration(GROUP, "sidePanelTipShown", Boolean.class);
		return faction != null || previous != null || Boolean.TRUE.equals(tip);
	}

	/** The {@link ContentPack#profiles} key to enforce, or null when no god is in force. */
	public String getRulesetKey()
	{
		return isSworn() ? faction.name() : null;
	}

	// ------------------------------------------------------------------ writing

	/** Swears to a god. Free to call at any time - this replaces whatever was chosen before. */
	public void swear(Faction next)
	{
		if (next == null || next == faction)
		{
			return;
		}
		faction = next;
		persist();
		log.debug("Sworn to {}", faction);
		changeListener.run();
	}

	/** Drops the oath entirely, so the oath screen reopens. */
	public void clear()
	{
		if (faction == null)
		{
			return;
		}
		previous = faction;
		faction = null;
		persist();
		changeListener.run();
	}

	// ---------------------------------------------------------------- storage

	private void load()
	{
		faction = Faction.byName(configManager.getConfiguration(GROUP, KEY_FACTION));
		previous = Faction.byName(configManager.getConfiguration(GROUP, KEY_PREVIOUS));
	}

	private void persist()
	{
		if (faction == null)
		{
			configManager.unsetConfiguration(GROUP, KEY_FACTION);
		}
		else
		{
			configManager.setConfiguration(GROUP, KEY_FACTION, faction.name());
		}
		if (previous == null)
		{
			configManager.unsetConfiguration(GROUP, KEY_PREVIOUS);
		}
		else
		{
			configManager.setConfiguration(GROUP, KEY_PREVIOUS, previous.name());
		}
	}
}
