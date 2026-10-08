package com.battlemage;

import com.google.inject.Provides;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;

@Slf4j
@PluginDescriptor(
	name = "Battle-Mage Mode",
	// Explicit so the enabled/disabled key stays put even if the class is renamed again.
	configName = "battlemagemode",
	description = "Magic-attack gear restrictions and the PKP melee/ranged/magic resource system",
	tags = {"battle", "mage", "battlemage", "hcim", "ironman", "pkp", "magic", "melee", "ranged", "restriction", "challenge"}
)
public class BattleMagePlugin extends Plugin
{
	private static final int WEAPON_SLOT = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int SPELLBOOK_GROUP = 218;
	private static final long COMBAT_GRACE_MS = 3000L;
	/** CRITICAL OVERLOAD runs for 10 game ticks. Every further restoring attack restarts it. */
	private static final long CRITICAL_OVERLOAD_MS = 6000L;
	private static final long OVERLOAD_FOOD_MS = 3000L;
	private static final long PENDING_CAST_WINDOW_MS = 6000L;
	private static final double TICK_SECONDS = 0.6;
	private static final long TICK_MS_L = 600L;
	/** Volume (0-100) of the sting played once when the PKP bar first appears. */
	private static final int OATH_SOUND_VOLUME = 70;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private Appearance look;
	@Inject private Rules rules;
	@Inject private Oath oath;
	@Inject private OnboardingOverlay onboardingOverlay;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ItemManager itemManager;
	@Inject private Sfx sfx;
	@Inject private OverlayManager overlayManager;
	@Inject private PkpBarOverlay barOverlay;
	@Inject private EffectTimerOverlay effectTimerOverlay;
	@Inject private PkpStateOverlay stateOverlay;
	@Inject private PkpTooltipOverlay tooltipOverlay;
	@Inject private PkpPopupOverlay popupOverlay;
	@Inject private IllegalBehaviorOverlay illegalOverlay;
	@Inject private CastCardOverlay castCardOverlay;
	@Inject private LevelUpCardOverlay levelUpCardOverlay;
	@Inject private OffLimitItemOverlay offLimitOverlay;
	@Inject private SpellBlockOverlay spellBlockOverlay;
	@Inject private CombatLockOverlay combatLockOverlay;

	/** Side panel and its toolbar button, built in startUp so the panel can reach the plugin. */
	private BattleMagePanel panel;
	private NavigationButton navButton;

	private final Map<PsiAction, String> lastSpell = new EnumMap<>(PsiAction.class);

	// gear caches
	private final Set<String> magicExceptionNames = new HashSet<>();
	private final Set<Integer> magicExceptionIds = new HashSet<>();
	private final Set<String> magicForceNames = new HashSet<>();
	private final Set<Integer> magicForceIds = new HashSet<>();
	private final Set<String> weaponExceptionNames = new HashSet<>();
	private final Set<Integer> weaponExceptionIds = new HashSet<>();
	private final Set<String> rangedNames = new HashSet<>();
	private final Set<Integer> rangedIds = new HashSet<>();
	/** Per-weapon ranged PKP amounts, overriding the base ranged restore. */
	private final Map<String, Integer> rangedAdvNames = new HashMap<>();
	private final Map<Integer, Integer> rangedAdvIds = new HashMap<>();
	/** Shields, and the PKP a block earns with each one that has its own amount. */
	private final Set<String> shieldNames = new HashSet<>();
	private final Set<Integer> shieldIds = new HashSet<>();
	private final Map<String, Integer> shieldPkpNames = new HashMap<>();
	private final Map<Integer, Integer> shieldPkpIds = new HashMap<>();
	/** Off-hand items (the god books) that earn nothing from blocking. */
	private final Set<String> noBlockPkpNames = new HashSet<>();
	private final Set<Integer> noBlockPkpIds = new HashSet<>();
	/** Off-hand items (books, the Antler guard) that cost PKP on a block. */
	private final Map<String, Integer> blockPenaltyNames = new HashMap<>();
	private final Map<Integer, Integer> blockPenaltyIds = new HashMap<>();
	private static final int SHIELD_SLOT = EquipmentInventorySlot.SHIELD.getSlotIdx();
	private final Set<Integer> rangedAnimations = new HashSet<>();

	// special attacks
	private final Map<String, Integer> specDeltaNames = new HashMap<>();
	private final Map<Integer, Integer> specDeltaIds = new HashMap<>();
	private final Map<String, SpecEffect> specEffectNames = new HashMap<>();
	private final Map<Integer, SpecEffect> specEffectIds = new HashMap<>();
	private final Map<String, Integer> consumableDeltaNames = new HashMap<>();
	private final Map<Integer, Integer> consumableDeltaIds = new HashMap<>();
	private final Map<String, SpecEffect> consumableEffectNames = new HashMap<>();
	private final Map<Integer, SpecEffect> consumableEffectIds = new HashMap<>();

	// pacifist items (attack + offensive casting disabled while worn)
	private final Set<String> pacifistNames = new HashSet<>();
	private final Set<Integer> pacifistIds = new HashSet<>();

	// overcharge sources
	private final Set<String> overchargeNames = new HashSet<>();
	private final Set<Integer> overchargeIds = new HashSet<>();

	// sustained-freeze pairings: weapon -> the spells that keep its freeze alive
	private final Map<String, Set<String>> sustainSpellsByName = new HashMap<>();
	private final Map<Integer, Set<String>> sustainSpellsById = new HashMap<>();

	// equipment that raises the ceiling while worn (the god books), and the total it is worth now
	private final Map<String, Integer> maxPkpItemNames = new HashMap<>();
	private final Map<Integer, Integer> maxPkpItemIds = new HashMap<>();
	private int wornMaxPkpBonus;

	// detection caches
	private final Set<Integer> meleeAnimations = new HashSet<>();
	private final Set<Integer> selfCastAnimations = new HashSet<>();
	private final Set<Integer> targetCastAnimations = new HashSet<>();
	/** Cast animations specific to curse / debuff spells; falls back to targetCastAnimations when empty. */
	private final Set<Integer> curseCastAnimations = new HashSet<>();
	private final Set<Integer> sitAnimations = new HashSet<>();
	private final Set<Integer> blockAnimations = new HashSet<>();
	/** Lower-cased spell names owned by the other gods; off-limits while this oath is in force. */
	private final Set<String> blockedGodSpells = new HashSet<>();
	/** Quest equipment with no quests named: exempt for the whole run. */
	private final Set<String> questItemNames = new HashSet<>();
	private final Set<Integer> questItemIds = new HashSet<>();
	/**
	 * Quest equipment that is only exempt while one of its quests is running, keyed by lower-cased
	 * item name and by item id. The value is the quests that unlock it; any one in progress is
	 * enough, which is how one item covers several quests without being listed twice.
	 */
	private final Map<String, List<Quest>> questGatedNames = new HashMap<>();
	private final Map<Integer, List<Quest>> questGatedIds = new HashMap<>();
	/** Quest names in the pack that this client does not know; logged once each, then ignored. */
	private final Set<String> unknownQuests = new HashSet<>();
	/**
	 * Which of the gating quests are in progress right now, refreshed on the game tick.
	 *
	 * <p>Cached rather than asked per lookup because the off-limit overlay walks the whole inventory
	 * every frame, and each {@code Quest.getState} is a varbit read. Only the quests actually named
	 * in the pack are polled, so this is a handful of reads a tick, not a hundred and sixty.
	 */
	private final Set<Quest> questsInProgress = new HashSet<>();
	/** Weapons the god in force bars outright, beating every allow-list. */
	private final Set<String> blockedWeaponNames = new HashSet<>();
	/** Only the sworn god's own weapon bans (a subset of blockedWeapon*), so the tooltip can name the reason. */
	private final Set<String> godBlockedWeaponNames = new HashSet<>();
	private final Set<Integer> godBlockedWeaponIds = new HashSet<>();
	private final Set<Integer> blockedWeaponIds = new HashSet<>();
	/** Lower-cased spell names from the curse-spell config list; these are charged the PK Flash cost. */
	private final Set<String> curseSpells = new HashSet<>();

	// PKP item caches
	private final Map<String, Integer> restoreNames = new HashMap<>();
	private final Map<Integer, Integer> restoreIds = new HashMap<>();
	private final Set<String> consumableExceptionNames = new HashSet<>();
	private final Set<Integer> consumableExceptionIds = new HashSet<>();
	private final Map<String, Integer> weaponAdvNames = new HashMap<>();
	private final Map<Integer, Integer> weaponAdvIds = new HashMap<>();

	// items exempt from the illegal-state restrictions (e.g. the Cowbell amulet - the one-click teleport)
	private final Set<String> restrictionExemptNames = new HashSet<>();
	private final Set<Integer> restrictionExemptIds = new HashSet<>();
	/** Menu options that count as a teleport, lower-cased. Stripped during CRITICAL OVERLOAD. */
	private final Set<String> teleportActions = new HashSet<>();

	/** Menu options that place a Dwarf multicannon, lower-cased. */
	private final Set<String> cannonSetupActions = new HashSet<>();

	/** The substring that identifies a cannon piece by name, lower-cased. */
	private String cannonKeyword = "";

	/** The worn-equipment interface group; entries from it are locked (except the exempt item) while illegal. */
	private static final int EQUIPMENT_GROUP = 387;

	// PKP state
	private double pkp;
	private boolean depletedLatch;
	private boolean overloadActive;
	private long overloadFoodUnlockMs;
	private long lastCombatMs;
	private long lastManualCastMs;
	private long lastCastMs;
	private long inCombatUntilMs;
	private int tickCounter;
	private int lastSpecPercent = Integer.MIN_VALUE;
	/** Weapon equipped on the previous tick - the spec energy drop is often only visible after a switch. */
	private int prevWeaponId = -1;
	/** Weapon held at the moment the spec was armed (spec-enabled varp went non-zero), and when. */
	private int armedSpecWeaponId = -1;
	private long armedSpecMs;
	/**
	 * The gamble's coin. A field rather than a fresh Random per roll so a run cannot be reseeded into
	 * the same sequence by firing specs at a fixed rate, and package-private so a test can replace it.
	 */
	java.util.Random rng = new java.util.Random();
	private String lastDetectedSpellName;
	private boolean wasInCombat;
	private PsiAction pendingCastFamily;
	private String pendingCastSpell;
	private long pendingCastMs;
	private int pendingTeaItemId = -1;
	private long pendingTeaMs;
	private long poisonAnimAccumMs;
	private long poisonAnimLastMs;
	private boolean poisonAnimInit;
	private final java.util.List<PkpPopup> pkpPopups = new java.util.ArrayList<>();
	private final java.util.List<CastCard> castCards = new java.util.ArrayList<>();
	private final java.util.List<LevelCard> levelCards = new java.util.ArrayList<>();
	private final java.util.Map<Skill, Integer> lastRealLevel = new java.util.HashMap<>();
	private final java.util.Map<PsiAction, Integer> lastSprite = new java.util.HashMap<>();
	private int pendingCastSprite = -1;
	private int lastDetectedSpriteId = -1;
	private boolean illegalActive;
	/**
	 * When the PKP bar began its one-time pop-in. 0 means it is still hidden -
	 * the oath has not been sworn, or it has been reset.
	 */
	private long revealStartMs;

	/** Wall-clock deadline for CRITICAL OVERLOAD; 0 when the state is not running. */
	private long criticalUntilMs;

	/** A floating +/- PKP change notification. */
	static final class PkpPopup
	{
		final int delta;
		final long spawnMs;
		final java.awt.Color color;

		PkpPopup(int delta, long spawnMs, java.awt.Color color)
		{
			this.delta = delta;
			this.spawnMs = spawnMs;
			this.color = color;
		}
	}

	/** A single dissolving cast card (used by the "Cast cards" menu mode). */
	static final class CastCard
	{
		final PsiAction family;
		final String label;
		final int spriteId;
		final long spawnMs;
		final float scale;

		CastCard(PsiAction family, String label, int spriteId, long spawnMs, float scale)
		{
			this.family = family;
			this.label = label;
			this.spriteId = spriteId;
			this.spawnMs = spawnMs;
			this.scale = scale;
		}
	}
	/** A level-up notification card (themed to the skill that levelled). Shown one at a time, in order. */
	static final class LevelCard
	{
		final String skill;
		final int level;
		final Color color;
		long startMs; // 0 until this card reaches the front of the queue and starts animating

		LevelCard(String skill, int level, Color color)
		{
			this.skill = skill;
			this.level = level;
			this.color = color;
		}
	}
	/** The icy frame colour used while the PKP bar is frozen (bar frame, border segment and chip all share it). */
	private static final Color FROZEN_FRAME = new Color(150, 220, 255, 235);

	/** The three timed PKP effects, each with its own badge symbol and border-segment colour. */
	enum EffectKind
	{
		/** Snowflake: the PKP bar is frozen in place. */
		FREEZE,
		/** Shield + up chevron: immune to being pushed into the overloaded state. */
		OVERLOAD_IMMUNE,
		/** Shield + down chevron: immune to being pushed into the depleted state. */
		DEPLETION_IMMUNE
	}

	/**
	 * A timed effect currently riding on the PKP bar - a freeze, or overload / depletion immunity. Each may
	 * have a seconds component, an attack-count component, or both (they are tracked independently). The bar
	 * border draws one segment colour per active effect and the timer overlay draws one badge per active
	 * effect, both straight off this list, so the two always agree.
	 */
	static final class ActiveEffect
	{
		final EffectKind kind;
		final Color color;
		final long remainingMs; // 0 when this effect has no timed component
		final int attacks;      // 0 when this effect has no attack-count component
		/** Armed but not started - a freeze waiting for the next attack or cast to trigger it. */
		final boolean pending;

		ActiveEffect(EffectKind kind, Color color, long remainingMs, int attacks, boolean pending)
		{
			this.kind = kind;
			this.color = color;
			this.remainingMs = remainingMs;
			this.attacks = attacks;
			this.pending = pending;
		}

		/** e.g. "4.2s", or "" when this effect has no timed component. */
		String secondsText()
		{
			return remainingMs > 0 ? String.format(Locale.ROOT, "%.1fs", remainingMs / 1000.0) : "";
		}

		/** e.g. "3a", or "" when this effect has no attack-count component. */
		String attacksText()
		{
			return attacks > 0 ? attacks + "a" : "";
		}
	}

	/** True while the player's current NPC intent is to attack (Attack option / combat cast), not pickpocket etc. */
	private boolean attackMode = true;
	private long freezeUntilMs;
	private int freezeAttacks;
	/** Freeze granted but not started yet - it begins on the next attack or cast, not when it was granted. */
	private int pendingFreezeSeconds;
	private int pendingFreezeAttacks;
	/**
	 * The sustained freeze: no clock, held up by continuing to use one weapon the way it is paired.
	 *
	 * <p>{@code pendingSustainWeaponId} is the armed stage, mirroring the pending fields above - the
	 * freeze begins on the next attack or cast rather than the instant the spec fires, which is what
	 * keeps the spec's own PKP delta from being swallowed by its own freeze.
	 * {@code sustainFreezeWeaponId} is the weapon currently holding it up, and
	 * {@code sustainFreezeActionMs} the last time a sustaining action renewed it.
	 */
	private int pendingSustainWeaponId = -1;
	private int sustainFreezeWeaponId = -1;
	private long sustainFreezeActionMs;
	private long overloadImmuneUntilMs;
	private int overloadImmuneAttacks;
	private long depletionImmuneUntilMs;
	private int depletionImmuneAttacks;

	@Provides
	BattleMageConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BattleMageConfig.class);
	}

	@Override
	protected void startUp()
	{
		// Changing god swaps the whole ruleset, so the caches and the panel both follow it.
		oath.setChangeListener(() ->
		{
			rules.refresh();
			// Standing down hides the bar again, so choosing a god later gets the pop-in a
			// second time.
			if (!oath.isSworn())
			{
				clearReveal();
			}
			clientThread.invoke(this::rebuildCaches);
			if (panel != null)
			{
				panel.rebuild();
			}
			refreshNavButton();
		});
		rules.refresh();
		rebuildCaches();
		// startUp does NOT run on the client thread, and the starting pool is read off the player's
		// Magic level, which does. Asking here would either throw - taking the plugin down with it -
		// or quietly answer "level 1" and start the bar at the unscaled maximum. So the caches are
		// rebuilt again and the bar filled on the first client tick, where both questions can
		// actually be answered.
		pkp = rules.maxPkp();
		clientThread.invoke(() ->
		{
			rebuildCaches();
			pkp = effectiveMax();
		});
		depletedLatch = false;
		overloadActive = false;
		overloadFoodUnlockMs = 0L;
		lastSpecPercent = Integer.MIN_VALUE;
		prevWeaponId = -1;
		armedSpecWeaponId = -1;
		armedSpecMs = 0L;
		lastCombatMs = System.currentTimeMillis();
		lastSpell.clear();
		wasInCombat = false;
		pendingCastFamily = null;
		pendingCastSpell = null;
		pendingTeaItemId = -1;
		poisonAnimAccumMs = 0L;
		poisonAnimInit = false;
		pkpPopups.clear();
		castCards.clear();
		levelCards.clear();
		lastRealLevel.clear();
		lastSprite.clear();
		illegalActive = false;
		criticalUntilMs = 0L;
		// An oath already sworn means the reveal happened in some earlier session; show the bar
		// straight away rather than replaying the animation on every login.
		revealStartMs = oath.isSworn() ? 1L : 0L;
		lastCastMs = 0L;
		attackMode = true;
		freezeUntilMs = 0L;
		freezeAttacks = 0;
		pendingFreezeSeconds = 0;
		pendingFreezeAttacks = 0;
		pendingSustainWeaponId = -1;
		sustainFreezeWeaponId = -1;
		sustainFreezeActionMs = 0L;
		wornMaxPkpBonus = 0;
		overloadImmuneUntilMs = 0L;
		overloadImmuneAttacks = 0;
		depletionImmuneUntilMs = 0L;
		depletionImmuneAttacks = 0;
		overlayManager.add(barOverlay);
		overlayManager.add(effectTimerOverlay);
		overlayManager.add(stateOverlay);
		overlayManager.add(tooltipOverlay);
		overlayManager.add(popupOverlay);
		overlayManager.add(illegalOverlay);
		overlayManager.add(castCardOverlay);
		overlayManager.add(levelUpCardOverlay);
		overlayManager.add(offLimitOverlay);
		overlayManager.add(spellBlockOverlay);
		overlayManager.add(combatLockOverlay);
		overlayManager.add(onboardingOverlay);
		log.info("[BATTLE-MAGE] Crests: {}", FactionSigils.crestReport());

		// Finishing the oath flow (Lock-in, plus the one-time side-panel tip) makes the bar appear.
		onboardingOverlay.setOnComplete(this::beginReveal);
		panel = new BattleMagePanel(oath, rules, look, this::openOnboarding,
			new AppearanceEditor.Previews()
			{
				@Override
				public void popups()
				{
					clientThread.invoke(BattleMagePlugin.this::previewPopups);
				}

				@Override
				public void castCard(PsiAction family)
				{
					clientThread.invoke(() -> previewCastCard(family));
				}

				@Override
				public void levelUp()
				{
					clientThread.invoke(() -> noteLevelUp("Magic", 88, skillColor(Skill.MAGIC)));
				}
			});
		refreshNavButton();

		// No god chosen yet: show the chooser rather than silently enforcing nothing.
		if (!oath.isSworn())
		{
			openOnboarding();
		}
	}

	/** Opens the oath screen over the game view. */
	void openOnboarding()
	{
		// A restart, not a nudge. Anything else drawing over the game view steps aside first, the
		// oath screen is closed before it is reopened so it always begins at the gods rather than
		// wherever it was left, and the bar is hidden again so Lock-in ends with the same pop-in a
		// first run gets. Reselecting a faction should feel identical to choosing one.
		onboardingOverlay.close();
		clearReveal();
		onboardingOverlay.open();
	}

	/** The god whose crest the toolbar icon shows, so it is only rebuilt when the god changes. */
	private Faction navIconFaction;

	/**
	 * Puts the sworn god's crest on the toolbar button. RuneLite fixes a button's icon when it is
	 * built, so changing it means swapping the button. If the side panel was open at the time it is
	 * reopened, so denouncing from the panel does not close it.
	 */
	private void refreshNavButton()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panel == null)
			{
				return;
			}
			Faction f = oath.getFaction();
			if (navButton != null && f == navIconFaction)
			{
				return;
			}
			boolean wasOpen = panel.isShowing();
			if (navButton != null)
			{
				clientToolbar.removeNavigation(navButton);
			}
			navIconFaction = f;
			navButton = NavigationButton.builder()
				.tooltip(f == null ? "Battle-Mage Mode" : "Battle-Mage Mode - " + rules.lore(f).displayName)
				.icon(BattleMagePanel.navIcon(f))
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
			if (wasOpen)
			{
				reopenPanel(navButton);
			}
		});
	}

	/**
	 * Selects the button so its panel shows again. Looked up by name because {@code openPanel} only
	 * exists on newer RuneLite builds; on an older one the panel simply stays closed.
	 */
	private void reopenPanel(NavigationButton button)
	{
		try
		{
			clientToolbar.getClass().getMethod("openPanel", NavigationButton.class).invoke(clientToolbar, button);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.debug("Could not reopen the side panel", e);
		}
	}

	@Override
	protected void shutDown()
	{
		// an editor left open must not leave its preview (or its mouse listener) behind
		look.cancel();
		overlayManager.remove(barOverlay);
		overlayManager.remove(effectTimerOverlay);
		overlayManager.remove(stateOverlay);
		overlayManager.remove(tooltipOverlay);
		overlayManager.remove(popupOverlay);
		overlayManager.remove(illegalOverlay);
		overlayManager.remove(castCardOverlay);
		overlayManager.remove(levelUpCardOverlay);
		overlayManager.remove(offLimitOverlay);
		overlayManager.remove(spellBlockOverlay);
		overlayManager.remove(combatLockOverlay);
		onboardingOverlay.close();
		overlayManager.remove(onboardingOverlay);
		oath.setChangeListener(null);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		navIconFaction = null;
		panel = null;
	}

	// ============================================================ menu filtering

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{

		final String option = event.getOption();
		final MenuEntry me = event.getMenuEntry();

		// 1. gear / weapon equip block
		if (isEquipOption(option))
		{
			int itemId = resolveItemId(event);
			if (itemId > 0 && shouldBlockEquip(itemId))
			{
				removeEntry(me);
			}
			return;
		}

		// 1a. the Dwarf multicannon window: it may be placed only while Ranged is still under the
		//     authored level. Sits with the equip block rather than below, because like that block it
		//     is a restriction on what you may do with an item and has nothing to do with the bar.
		if (isCannonSetupEntry(event, option) && cannonWindowClosed())
		{
			removeEntry(me);
			return;
		}

		// 1b. ILLEGAL: lock ALL interaction with worn equipment (Remove/Operate/Rub/Teleport/etc.) except
		//     the exempt item (the Cowbell amulet stays usable from its worn slot).
		if (illegalBehaviorActive() && isWornEquipmentEntry(me))
		{
			if (!isExemptEquipmentEntry(event, me))
			{
				removeEntry(me);
			}
			return;
		}


		final boolean attack = option != null && option.equalsIgnoreCase("Attack");
		// Both the ILLEGAL BEHAVIOR warning and CRITICAL OVERLOAD strip the same options.
		final boolean illegal = lockdownActive();

		// 2. spell blocking. While depleted OR in the ILLEGAL state, EVERY spell is disabled (combat,
		//    teleports, utility - any "Cast"). Otherwise only the combat-spell allow-list restriction applies.
		if ((isDepleted() || illegal) && isSpellEntry(me))
		{
			removeEntry(me);
			return;
		}
		// 2b. seated: the sit pose suppresses the cast animation, so block any PKP-costing cast while seated.
		if (rules.blockCastWhileSeated() && isSitting() && isPkpCostingSpellEntry(me))
		{
			removeEntry(me);
			return;
		}
		// 2b-ii. seated: the same for swinging at something. The sit pose eats the attack animation
		//        exactly as it eats the cast one, so a seated attack was a way to fight without the
		//        plugin ever seeing the animation it keys its restores off - the half of this rule
		//        that was missing. Unlike the overload block below there is no autocast escape: that
		//        exists because a selected spell is a cast rather than a swing, and casting is the
		//        thing sitting already forbids.
		if (rules.blockAttackWhileSeated() && attack && isSitting())
		{
			removeEntry(me);
			return;
		}
		// 2c. god spells: the three you did not swear to are not yours to cast. Checked before the
		//     allow-list because it applies whether or not that restriction is switched on.
		if (godSpellBlockedEntry(me))
		{
			removeEntry(me);
			return;
		}

		// 2d. CRITICAL OVERLOAD: every teleport option is removed, in the inventory and on worn
		//     equipment alike, leaving the exempt item (the Cowbell amulet) as the only way out.
		final int teleItemId = criticalOverloadActive() ? resolveItemId(event) : -1;
		if (teleItemId > 0 && isTeleportActionEntry(option) && !isRestrictionExemptItem(teleItemId))
		{
			removeEntry(me);
			return;
		}

		// 2c. pacifist gear: while any pacifist item is worn, the Attack option is removed from all
		//     enemies and every offensive (combat) cast is blocked, like during depletion.
		if (pacifistWorn() && (attack || isCombatSpellEntry(me)))
		{
			removeEntry(me);
			return;
		}

		// 4. overload (or ILLEGAL): hide NPC Attack unless a spell is selected (autocasting is disabled)
		if (rules.overloadBlocksAttack() && (isOverloaded() || illegal) && attack && !spellSelected())
		{
			removeEntry(me);
			return;
		}

		// 5. overload (or ILLEGAL): hide food (eat) while overloaded/illegal, and for a few seconds after overload clears
		if (rules.blockFoodWhenOverloaded() && (foodLockedByOverload() || illegal)
			&& option != null && option.equalsIgnoreCase("Eat")
			&& !isConsumableException(resolveItemId(event)))
		{
			removeEntry(me);
			return;
		}

		// 6. depletion (or ILLEGAL): hide potions (drink) while depleted/illegal
		if (rules.blockPotionsWhenDepleted() && (isDepleted() || illegal)
			&& option != null && option.equalsIgnoreCase("Drink")
			&& !isConsumableException(resolveItemId(event)))
		{
			removeEntry(me);
		}
	}

	/**
	 * While ILLEGAL BEHAVIOR is showing, make "Use" the default (left-click) action for every item so no
	 * item can be one-click eaten/drunk/teleported/etc. The exempt item(s) (the Cowbell amulet) instead get
	 * their Teleport promoted to the left-click, so it's the only one-click teleport. Options are only
	 * reordered, never removed - everything is still available on right-click.
	 */
	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		if (!rules.illegalDefaultUse() || !lockdownActive())
		{
			return;
		}
		MenuEntry[] entries = client.getMenuEntries();
		if (entries == null || entries.length < 2)
		{
			return;
		}
		// the default action is the last entry; find the item it belongs to
		int topItem = -1;
		for (int i = entries.length - 1; i >= 0; i--)
		{
			int id = menuItemId(entries[i]);
			if (id > 0)
			{
				topItem = id;
				break;
			}
		}
		if (topItem <= 0)
		{
			return;
		}
		final boolean exempt = isRestrictionExemptItem(topItem);
		// find the entry (for this item) we want to become the left-click default
		int promote = -1;
		for (int i = 0; i < entries.length; i++)
		{
			if (menuItemId(entries[i]) != topItem)
			{
				continue;
			}
			String opt = entries[i].getOption();
			opt = opt == null ? "" : opt.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
			boolean match = exempt ? opt.startsWith("teleport") : opt.equals("use");
			if (match)
			{
				promote = i;
				break;
			}
		}
		// move the chosen entry to the end (highest priority => left-click)
		if (promote >= 0 && promote != entries.length - 1)
		{
			MenuEntry chosen = entries[promote];
			MenuEntry[] reordered = new MenuEntry[entries.length];
			int j = 0;
			for (int i = 0; i < entries.length; i++)
			{
				if (i != promote)
				{
					reordered[j++] = entries[i];
				}
			}
			reordered[j] = chosen;
			client.setMenuEntries(reordered);
		}
	}

	/** Is the menu entry for an item in the worn-equipment interface? */
	private boolean isWornEquipmentEntry(MenuEntry me)
	{
		Widget w = me == null ? null : me.getWidget();
		return w != null && (w.getId() >>> 16) == EQUIPMENT_GROUP;
	}

	/**
	 * Is this worn-equipment entry for the exempt item? Worn "Operate/Teleport/Rub" entries often don't
	 * carry an item id, so we also match on the entry's target name (e.g. "Cowbell amulet").
	 */
	private boolean isExemptEquipmentEntry(MenuEntryAdded event, MenuEntry me)
	{
		if (isRestrictionExemptItem(resolveItemId(event)))
		{
			return true;
		}
		return me != null && exemptNameMatches(me.getTarget());
	}

	/** Does a (possibly colour-tagged) name equal or contain a configured exempt-item name? */
	private boolean exemptNameMatches(String raw)
	{
		if (raw == null)
		{
			return false;
		}
		String name = raw.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
		if (name.isEmpty())
		{
			return false;
		}
		if (restrictionExemptNames.contains(name))
		{
			return true;
		}
		for (String ex : restrictionExemptNames)
		{
			if (!ex.isEmpty() && name.contains(ex))
			{
				return true;
			}
		}
		return false;
	}

	/** The inventory/equipment item id behind a menu entry, or -1 if it isn't an item entry. */
	private static int menuItemId(MenuEntry entry)
	{
		if (entry == null)
		{
			return -1;
		}
		int id = entry.getItemId();
		if (id > 0)
		{
			return id;
		}
		Widget w = entry.getWidget();
		return w != null && w.getItemId() > 0 ? w.getItemId() : -1;
	}

	private List<String> spellCandidates(MenuEntry me)
	{
		List<String> candidates = new ArrayList<>();
		if (me.getWidget() != null)
		{
			addCandidate(candidates, me.getWidget().getName());
		}
		addCandidate(candidates, me.getTarget());
		addCandidate(candidates, me.getOption());
		return candidates;
	}

	/** Does this menu entry point at a known combat spell (regardless of allow-list)? */
	private boolean isCombatSpellEntry(MenuEntry me)
	{
		for (String c : spellCandidates(me))
		{
			if (PsiAction.isKnownCombatSpell(c))
			{
				return true;
			}
		}
		return false;
	}

	/** Any spell cast - combat, teleport or utility. "Cast" is the option for every spellbook spell. */
	private boolean isSpellEntry(MenuEntry me)
	{
		String opt = me.getOption();
		if (opt != null && opt.equalsIgnoreCase("Cast"))
		{
			return true;
		}
		return isCombatSpellEntry(me);
	}

	/** Does this spell entry cost PKP to cast (i.e. a PK combat spell, the only thing blocked while seated)? */
	private boolean isPkpCostingSpellEntry(MenuEntry me)
	{
		if (!isSpellEntry(me))
		{
			return false;
		}
		for (String c : spellCandidates(me))
		{
			if (costForSpell(c) > 0)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isEquipOption(String option)
	{
		return option != null &&
			(option.equalsIgnoreCase("Wear")
				|| option.equalsIgnoreCase("Wield")
				|| option.equalsIgnoreCase("Equip"));
	}

	/** Off-limit gear/weapon (gets the slashed red circle). Food is handled by isDisabledFood. */
	boolean isOffLimitItem(int itemId)
	{
		return itemId > 0 && shouldBlockEquip(itemId);
	}

	/** Food (eat only - never drinks) that's currently disabled by the overload lockout. */
	boolean isDisabledFood(int itemId)
	{
		if (itemId <= 0 || !isEatFood(itemId) || isConsumableException(itemId))
		{
			return false;
		}
		return foodLockedByOverload();
	}

	/** Remaining fraction (1..0) of the disabled-food circle: counts down during the overload eat-timer. */
	float foodCircleFraction()
	{
		long now = System.currentTimeMillis();
		if (!isOverloaded() && now < overloadFoodUnlockMs)
		{
			float f = (overloadFoodUnlockMs - now) / (float) OVERLOAD_FOOD_MS;
			return Math.max(0f, Math.min(1f, f));
		}
		return 1f;
	}

	/** Potions/drinks that are currently disabled because PSI is depleted. */
	boolean isDisabledPotion(int itemId)
	{
		return itemId > 0 && isDepleted() && isDrinkItem(itemId) && !isConsumableException(itemId);
	}

	/** Only items you EAT count as food here - potions/other drinks are never restricted. */
	private boolean isEatFood(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp == null)
		{
			return false;
		}
		for (String a : comp.getInventoryActions())
		{
			if (a != null && a.equalsIgnoreCase("Eat"))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isDrinkItem(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp == null)
		{
			return false;
		}
		for (String a : comp.getInventoryActions())
		{
			if (a != null && a.equalsIgnoreCase("Drink"))
			{
				return true;
			}
		}
		return false;
	}

	/** Items the player has whitelisted to always be consumable, even while overloaded or depleted. */
	private boolean isConsumableException(int itemId)
	{
		if (itemId <= 0)
		{
			return false;
		}
		return consumableExceptionIds.contains(itemId) || consumableExceptionNames.contains(itemName(itemId));
	}

	/**
	 * The single item (e.g. the Cowbell amulet) that keeps all its use-options under the universal
	 * lockdown. Matched by id, or by name equalling/containing a configured exempt name.
	 */
	boolean isRestrictionExemptItem(int itemId)
	{
		if (itemId <= 0)
		{
			return false;
		}
		if (restrictionExemptIds.contains(itemId))
		{
			return true;
		}
		String name = itemName(itemId);
		if (name.isEmpty())
		{
			return false;
		}
		if (restrictionExemptNames.contains(name))
		{
			return true;
		}
		for (String exempt : restrictionExemptNames)
		{
			if (!exempt.isEmpty() && name.contains(exempt))
			{
				return true;
			}
		}
		return false;
	}

	/** The blue X colour for individually off-limit spells (normal & overload states). */
	private static final Color SPELL_BLOCK_BLUE = new Color(60, 140, 255, 235);
	/** The orange X colour used for every spell while the ILLEGAL state is active. */
	private static final Color SPELL_BLOCK_ORANGE = new Color(0xFF, 0xB3, 0x00, 235);

	/**
	 * The "whole-book" colour for the ILLEGAL and DEPLETED states: every spell gets X'd and the spellbook
	 * gets a matching shade. Returns null in the normal and overload states (those only get the blue X on
	 * individually off-limit spells, via {@link #spellBlockColor}).
	 * <ul>
	 *   <li>ILLEGAL: orange (reverts when the state clears).</li>
	 *   <li>DEPLETED: the depletion-alert colour ({@code depletedColor}).</li>
	 * </ul>
	 */
	Color spellbookStateColor()
	{
		if (lockdownActive())
		{
			return SPELL_BLOCK_ORANGE;
		}
		if (isDepleted())
		{
			return look.depletedColor();
		}
		return null;
	}

	/**
	 * Overlay helper for the normal / overload states: the blue X colour for an individually off-limit
	 * spell, or null. (The ILLEGAL and DEPLETED states are handled wholesale by {@link #spellbookStateColor}.)
	 *
	 * @param rawName  the widget name (may carry colour tags), or null/empty for an unnamed spell icon
	 * @param castable whether the widget exposes a "Cast" action
	 */
	Color spellBlockColor(String rawName, boolean castable)
	{
		String c = rawName == null ? "" : rawName.replaceAll("<[^>]*>", "").trim();

		// seated: even an allowed PK (PKP-costing) spell can't be cast while sitting
		if (rules.blockCastWhileSeated() && isSitting() && !c.isEmpty() && costForSpell(c) > 0)
		{
			return SPELL_BLOCK_BLUE;
		}
		// pacifist gear: every combat spell is off-limits while a pacifist item is worn
		if (pacifistWorn() && !c.isEmpty() && PsiAction.isKnownCombatSpell(c))
		{
			return SPELL_BLOCK_BLUE;
		}
		// another god's spell: X'd for the whole run, not just while some state is active
		if (isBlockedGodSpell(c))
		{
			return SPELL_BLOCK_BLUE;
		}
		return null;
	}

	/** True when a menu option is one of the configured teleport actions (Rub, Break, Teleport...). */
	private boolean isTeleportActionEntry(String option)
	{
		if (option == null || teleportActions.isEmpty())
		{
			return false;
		}
		return teleportActions.contains(option.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Is this the menu option that places a Dwarf multicannon?
	 *
	 * <p>Two conditions, and both are needed. The option alone is not enough - "Set-up" is a generic
	 * enough word that another item could carry it - and the item alone is not enough either, because
	 * every other thing you can do with a cannon hangs off the same item: Fire, Pick-up, Repair,
	 * Empty. Only the pairing is the act of placing one.
	 *
	 * <p>The item is matched by a substring of its name rather than by id. The cannon comes as four
	 * separate pieces, as a packed set, and in at least one ornamental re-skin, and all of them carry
	 * "cannon" in the name; an id list would have to be right about every one of them, and a missing
	 * id is a rule that silently never fires - which is the failure mode this pack has been bitten by
	 * before.
	 */
	private boolean isCannonSetupEntry(MenuEntryAdded event, String option)
	{
		if (option == null || cannonSetupActions.isEmpty() || cannonKeyword.isEmpty())
		{
			return false;
		}
		if (!cannonSetupActions.contains(option.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT)))
		{
			return false;
		}
		// The target is the item name the client already put on the entry; the inventory id is the
		// fallback for the cases where it is blank.
		String target = event.getTarget();
		if (target != null
			&& target.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT).contains(cannonKeyword))
		{
			return true;
		}
		int itemId = resolveItemId(event);
		return itemId > 0 && itemName(itemId).contains(cannonKeyword);
	}

	/**
	 * Has the cannon window shut? True once Ranged has reached the authored level.
	 *
	 * <p>The <b>real</b> level, not the boosted one: this is a line in the progression, and a dose of
	 * ranging potion is not supposed to close a door permanently - nor should letting it wear off
	 * open one back up.
	 */
	private boolean cannonWindowClosed()
	{
		return rules.enforceCannonLevel() && rangedLevel() >= rules.cannonMaxRangedLevel();
	}

	private int rangedLevel()
	{
		if (!onClientThread())
		{
			return 0;
		}
		try
		{
			return client.getRealSkillLevel(Skill.RANGED);
		}
		catch (Exception | LinkageError | AssertionError e)
		{
			// Unknown level: leave the option alone. A rule that cannot read the level has no
			// business taking an action away.
			return 0;
		}
	}

	private int resolveItemId(MenuEntryAdded event)
	{
		MenuEntry entry = event.getMenuEntry();
		int id = entry.getItemId();
		if (id > 0)
		{
			return id;
		}
		Widget w = entry.getWidget();
		if (w != null && w.getItemId() > 0)
		{
			return w.getItemId();
		}
		return -1;
	}

	/**
	 * Why an item can't be equipped. Each one is a different rule and the tooltip names it:
	 * FACTION_* is the sworn god's own ban, POWERED_STAFF the shared ban on powered staves and
	 * sceptres, and WEAPON / ARMOR the magic-attack gate.
	 */
	private enum BlockKind { NONE, FACTION_WEAPON, FACTION_GEAR, POWERED_STAFF, WEAPON, ARMOR }

	private boolean shouldBlockEquip(int itemId)
	{
		return blockKind(itemId) != BlockKind.NONE;
	}

	/**
	 * Mirrors {@link #shouldBlockEquip} but reports <em>why</em> an item is blocked, classified by slot:
	 * the god's own ban, the powered-staff ban, or the magic-attack gate (weapon minimum / armour floor).
	 */
	private BlockKind blockKind(int itemId)
	{
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		final String name = comp == null ? "" : comp.getName().toLowerCase(Locale.ROOT);

		final ItemStats stats = itemManager.getItemStats(itemId);
		final ItemEquipmentStats eq = stats != null ? stats.getEquipment() : null;
		final boolean weaponSlot = eq != null && eq.getSlot() == WEAPON_SLOT;

		// Quest equipment first, ahead of every rule including the force-blocks below. A quest item is
		// equippable under every god, whatever else it is also listed as - the point of the list is that
		// no ruleset can leave a quest unfinishable.
		if (isQuestItem(itemId, name))
		{
			return BlockKind.NONE;
		}

		// This god's own weapon ban. Above the allow-lists, because it is a denial and they are
		// permissions: naming a weapon here bars it even if it is also a listed exception.
		if (weaponSlot && (blockedWeaponIds.contains(itemId) || blockedWeaponNames.contains(name)))
		{
			// Both lists feed blockedWeapon*: say which one it came from.
			return (godBlockedWeaponIds.contains(itemId) || godBlockedWeaponNames.contains(name))
				? BlockKind.FACTION_WEAPON : BlockKind.POWERED_STAFF;
		}

		if (magicForceIds.contains(itemId) || magicForceNames.contains(name))
		{
			return weaponSlot ? BlockKind.FACTION_WEAPON : BlockKind.FACTION_GEAR;
		}

		// Allow-listed equipment is never blocked. This must sit above BOTH the blanket weapon block and
		// the magic-attack rules: a weapon exception is a general "this weapon is legal" statement, not an
		// exception to one specific rule, so e.g. a listed Rune spear stays wieldable even though its
		// magic bonus falls under the weapon minimum.
		//
		// The spec table is deliberately NOT consulted here. Being in it says what the weapon's special
		// attack does to the PKP bar, which is a different statement from being allowed to hold it: the
		// table is shared by all four gods, so reading it as a permission would have made every spec
		// weapon legal under every god and quietly cancelled the per-god weapon lists. A spec weapon
		// still has to earn its place on an allow-list, or clear the gate, like anything else.
		if (isRangedWeapon(itemId)
			|| weaponExceptionIds.contains(itemId) || weaponExceptionNames.contains(name)
			|| pacifistIds.contains(itemId) || pacifistNames.contains(name)
			|| magicExceptionIds.contains(itemId) || magicExceptionNames.contains(name))
		{
			return BlockKind.NONE;
		}

		if (stats == null || !stats.isEquipable() || eq == null)
		{
			return BlockKind.NONE;
		}

		// Weapons and armour are judged by different magic-attack rules. A weapon must MEET a minimum
		// (default +1, so anything with a positive magic bonus is fine); armour only has to stay ABOVE a
		// floor (default -10), which keeps mildly negative pieces wearable. Either way the
		// allow-lists above have already short-circuited this.
		if (rules.enforceMagicAttack())
		{
			final int amagic = eq.getAmagic();
			if (weaponSlot)
			{
				if (amagic < rules.magicAttackMinimum())
				{
					return BlockKind.WEAPON;
				}
			}
			else if (amagic <= rules.magicAttackThreshold())
			{
				return BlockKind.ARMOR;
			}
		}

		return BlockKind.NONE;
	}

	private void removeEntry(MenuEntry target)
	{
		MenuEntry[] kept = Arrays.stream(client.getMenuEntries())
			.filter(e -> e != target)
			.toArray(MenuEntry[]::new);
		client.setMenuEntries(kept);
	}

	/** Applies a consumable's PKP restore + configured delta/effects (poison-adjusted). */
	private void applyConsumable(int itemId)
	{
		Integer amount = restoreAmount(itemId);
		if (amount != null)
		{
			double beforeFood = pkp;
			double restore = poisonAdjust(amount);
			if (rules.overchargeEnabled() && isOverchargeSource(itemId))
			{
				addPkpOver(restore);
			}
			else
			{
				addPkp(restore);
			}
			notePkpChange(pkp - beforeFood);
		}

		// configurable consumable effects (same machinery as spec weapons: PKP delta + freeze/immunity/gamble)
		Integer cDelta = consumableDeltaFor(itemId);
		SpecEffect cFx = consumableEffectFor(itemId);
		if (cDelta != null || cFx != null)
		{
			// The delta is handed to activateSpecEffect rather than applied here, so that a CO/CD
			// clear resets the pool BEFORE the gain lands on top of it. Applied first, the gain was
			// simply overwritten by the reset.
			activateSpecEffect(cFx, System.currentTimeMillis(),
				cDelta == null ? null : Double.valueOf(poisonAdjust(cDelta)),
				cDelta != null && cDelta >= 0, itemId);
		}
	}

	private boolean isTeaFlask(int itemId)
	{
		String n = itemName(itemId);
		return n != null && n.toLowerCase(Locale.ROOT).contains("tea flask");
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (pendingTeaItemId <= 0)
		{
			return;
		}
		// Game messages only: anything with a sender is another player typing, never the real sip.
		if (event.getName() != null && !event.getName().isEmpty())
		{
			return;
		}
		String msg = event.getMessage();
		if (msg == null)
		{
			return;
		}
		String plain = msg.replaceAll("<[^>]*>", "");
		// confirm a real sip; an empty Tea flask gives a different message and so never applies the effect
		if (plain.toLowerCase(Locale.ROOT).contains("tea is so refreshing")
			&& System.currentTimeMillis() - pendingTeaMs < 4000L)
		{
			int id = pendingTeaItemId;
			pendingTeaItemId = -1;
			applyConsumable(id);
		}
	}

	// ============================================================ click handling

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{

		final MenuEntry entry = event.getMenuEntry();
		final String option = entry.getOption();

		// consumable restore
		if (option != null
			&& (option.equalsIgnoreCase("Eat") || option.equalsIgnoreCase("Drink")))
		{
			int itemId = entry.getItemId();
			if (itemId <= 0 && entry.getWidget() != null)
			{
				itemId = entry.getWidget().getItemId();
			}
			// The Tea flask always offers "Drink" even when empty, so applying its effect on the click would
			// let an empty flask reset overload/depletion. Defer it and only apply once the game confirms a
			// real sip with the "Ahhh, tea is so refreshing." message (see onChatMessage).
			if (isTeaFlask(itemId))
			{
				pendingTeaItemId = itemId;
				pendingTeaMs = System.currentTimeMillis();
			}
			else
			{
				applyConsumable(itemId);
			}
		}

		// autocasting is removed: the autocast button is always inaccessible. Consume any click that would
		// open the autocast box or pick an autocast spell, so the autocast varbit can never be set.
		if (isAutocastButton(entry))
		{
			event.consume();
			return;
		}

		// track whether the player's current NPC intent is to attack (vs pickpocket / talk / steal etc.),
		// so the overload/depletion ILLEGAL warnings only fire when an attack option is actually chosen
		MenuAction act = event.getMenuAction();
		boolean npcOption = act == MenuAction.NPC_FIRST_OPTION || act == MenuAction.NPC_SECOND_OPTION
			|| act == MenuAction.NPC_THIRD_OPTION || act == MenuAction.NPC_FOURTH_OPTION
			|| act == MenuAction.NPC_FIFTH_OPTION;
		if (npcOption)
		{
			attackMode = option != null && option.equalsIgnoreCase("Attack");
		}

		// manual spell cast: record intent now; PKP is spent only when the spell actually casts
		PsiAction fam = detectCast(event);
		if (fam != null)
		{
			// seated: the sit pose suppresses the cast animation, so a PKP-costing cast would either fail
			// to animate or spend PKP with no visible cast. Consume the click outright instead.
			if (rules.blockCastWhileSeated() && isSitting() && costForSpell(lastDetectedSpellName) > 0)
			{
				event.consume();
				return;
			}
			attackMode = true;
			pendingCastFamily = fam;
			pendingCastSpell = lastDetectedSpellName;
			pendingCastSprite = lastDetectedSpriteId;
			pendingCastMs = System.currentTimeMillis();
		}
	}

	/** A click that would open the autocast box or pick an autocast spell. */
	private boolean isAutocastButton(MenuEntry entry)
	{
		if (entry == null)
		{
			return false;
		}
		String opt = entry.getOption() == null ? "" : entry.getOption().toLowerCase(java.util.Locale.ROOT);
		String tgt = entry.getTarget() == null ? "" : entry.getTarget().toLowerCase(java.util.Locale.ROOT);
		return opt.contains("auto-cast") || opt.contains("autocast")
			|| tgt.contains("auto-cast") || tgt.contains("autocast")
			|| opt.contains("choose spell") || opt.contains("choose-spell");
	}

	/** Identifies a *cast* (not a mere spell selection) and returns its PK family, or null. */
	private PsiAction detectCast(MenuOptionClicked event)
	{
		final MenuAction a = event.getMenuAction();
		final MenuEntry entry = event.getMenuEntry();

		final boolean opponentCast =
			a == MenuAction.WIDGET_TARGET_ON_NPC || a == MenuAction.WIDGET_TARGET_ON_PLAYER;

		final boolean targetCast =
			opponentCast
				|| a == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
				|| a == MenuAction.WIDGET_TARGET_ON_GROUND_ITEM
				|| a == MenuAction.WIDGET_TARGET_ON_WIDGET;

		final boolean directCast =
			(a == MenuAction.CC_OP || a == MenuAction.CC_OP_LOW_PRIORITY)
				&& entry.getWidget() != null
				&& (entry.getWidget().getId() >>> 16) == SPELLBOOK_GROUP;

		if (!targetCast && !directCast)
		{
			return null;
		}

		List<String> candidates = new ArrayList<>();
		if (targetCast)
		{
			Widget sel = client.getSelectedWidget();
			addCandidate(candidates, sel != null ? sel.getName() : null);
			addCandidate(candidates, entry.getTarget());
		}
		else
		{
			addCandidate(candidates, entry.getWidget().getName());
			addCandidate(candidates, entry.getTarget());
			addCandidate(candidates, entry.getOption());
		}

		for (String c : candidates)
		{
			PsiAction t = familyForSpell(c);
			if (t != null)
			{
				lastDetectedSpellName = c;
				lastSpell.put(t, c);
				Widget spellWidget = directCast ? entry.getWidget() : client.getSelectedWidget();
				lastDetectedSpriteId = spellWidget != null ? spellWidget.getSpriteId() : -1;
				return t;
			}
		}

		return null;
	}

	// ============================================================ animation

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local)
		{
			return;
		}
		int anim = local.getAnimation();
		if (anim <= 0)
		{
			return;
		}

		// blocking a hit: only gods with a block restore (Saradomin and Zamorak) reward it
		if (blockAnimations.contains(anim))
		{
			handleBlockRestore();
			return;
		}

		if (meleeAnimations.contains(anim))
		{
			if (isRangedEquipped())
			{
				return;
			}
			// pickpocket / thieving and similar share melee-like animations but aren't attacks; an
			// auto-retaliate swing is one, though no Attack option was clicked for it
			if (!attackingNow())
			{
				return;
			}
			handleMeleeRestore();
			return;
		}

		// a ranged shot with a weapon that has a PKP amount. Some ranged animation IDs overlap with
		// non-combat ones, so this needs both a matching animation and an actual ranged weapon in hand.
		if (rangedAnimations.contains(anim) && rules.rangedRestoreEnabled())
		{
			if (!attackingNow())
			{
				return;
			}
			Integer gain = rangedRestoreAmount(equippedWeaponId());
			if (gain != null)
			{
				handleRangedRestore(gain);
				return;
			}
			// not a listed ranged weapon: fall through, the animation may mean something else
		}

		// a self-targeted PSI spell (e.g. Charge, which hits no NPC) - charge on its own animation
		if (pendingCastFamily != null && selfCastAnimations.contains(anim)
			&& System.currentTimeMillis() - pendingCastMs < PENDING_CAST_WINDOW_MS)
		{
			chargePendingCast();
			return;
		}

		// a manually clicked target spell has now actually cast (its animation fired) -> spend PKP here
		if (pendingCastFamily != null && inCombat()
			&& System.currentTimeMillis() - pendingCastMs < PENDING_CAST_WINDOW_MS)
		{
			// Only a real spell-cast animation should charge. If the user has listed cast anim IDs,
			// require a match so block/defend/etc. animations (e.g. 424, 4117, 1156) that play between
			// targeting and the cast don't falsely spend PKP. With no list, fall back to the legacy
			// "any animation while engaged" behaviour.
			if (castAnimationsFor(pendingCastFamily).isEmpty()
				|| castAnimationsFor(pendingCastFamily).contains(anim))
			{
				chargePendingCast();
			}
			// Otherwise it's not a recognised cast animation: ignore it and keep the pending intent so
			// the spell still charges when its real cast animation fires (within the window).
			return;
		}
		// stale intent (walked off / cancelled): drop it once the window passes
		if (pendingCastFamily != null
			&& System.currentTimeMillis() - pendingCastMs >= PENDING_CAST_WINDOW_MS)
		{
			pendingCastFamily = null;
			pendingCastSpell = null;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == null)
		{
			return;
		}
		Skill skill = event.getSkill();
		int level = event.getLevel();
		Integer prev = lastRealLevel.put(skill, level);
		// only fire on a genuine level increase, and never on the initial login population
		if (prev == null || level <= prev || level <= 1)
		{
			return;
		}
		noteLevelUp(skill.getName(), level, skillColor(skill));
	}

	/** Queues a level-up card (themed to the skill); cards display one at a time from the top-right. */
	void noteLevelUp(String skillName, int level, Color color)
	{
		levelCards.add(new LevelCard(skillName, level, color));
		if (levelCards.size() > 12)
		{
			levelCards.remove(0);
		}
	}

	java.util.List<LevelCard> getLevelCards()
	{
		return levelCards;
	}

	/** Themed colour for each skill's level-up card. */
	Color skillColor(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:       return new Color(0xE0322E);
			case STRENGTH:     return new Color(0x3FAA4D);
			case DEFENCE:      return new Color(0x4A78C0);
			case RANGED:       return new Color(0x6FA825);
			case PRAYER:       return new Color(0xE8C44A);
			case MAGIC:        return new Color(0x46C8FF);
			case RUNECRAFT:    return new Color(0xD9C24A);
			case CONSTRUCTION: return new Color(0xB08D57);
			case HITPOINTS:    return new Color(0xE05656);
			case AGILITY:      return new Color(0x4A9BE0);
			case HERBLORE:     return new Color(0x4CA64C);
			case THIEVING:     return new Color(0x9A5CB4);
			case CRAFTING:     return new Color(0x9A6B3F);
			case FLETCHING:    return new Color(0x3FA08A);
			case SLAYER:       return new Color(0x7A4FA0);
			case HUNTER:       return new Color(0x8F7A3F);
			case MINING:       return new Color(0x6E7A8A);
			case SMITHING:     return new Color(0x9A9A9A);
			case FISHING:      return new Color(0x5FB6C8);
			case COOKING:      return new Color(0xC85A8A);
			case FIREMAKING:   return new Color(0xE8853A);
			case WOODCUTTING:  return new Color(0x6B8E23);
			case FARMING:      return new Color(0x5AAE5A);
			default:           return Color.WHITE;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Hitsplat h = event.getHitsplat();
		if (h == null)
		{
			return;
		}

		// any hitsplat involving the local player - dealt by you OR landing on you - means you are still in
		// combat, even if you are not fighting back (so the out-of-combat timers do not start prematurely)
		Player local = client.getLocalPlayer();
		if (h.isMine() || (local != null && event.getActor() == local))
		{
			long now = System.currentTimeMillis();
			lastCombatMs = now;
			inCombatUntilMs = now + COMBAT_GRACE_MS;
		}

	}

	/**
	 * Which animation IDs count as "this spell actually cast". Curses animate differently from damage
	 * spells, so they get their own list; an empty curse list falls back to the damage-spell one.
	 */
	private Set<Integer> castAnimationsFor(PsiAction fam)
	{
		if (fam == PsiAction.CURSE && !curseCastAnimations.isEmpty())
		{
			return curseCastAnimations;
		}
		return targetCastAnimations;
	}

	/** Spends PKP for the pending manual/self cast and fires the cast card. */
	private void chargePendingCast()
	{
		PsiAction fam = pendingCastFamily;
		String spell = pendingCastSpell;
		pendingCastFamily = null;
		pendingCastSpell = null;
		long now = System.currentTimeMillis();
		lastManualCastMs = now;
		if (spell != null)
		{
		}
		lastSprite.put(fam, pendingCastSprite);
		noteCast(fam, spell, pendingCastSprite);
		double before = pkp;
		applyCast(withPoison(costForSpell(spell)), spell);
		notePkpChange(pkp - before);
		lastCombatMs = now;
	}

	/**
	 * A block animation played. Gods with a block restore gain it, up to the maximum: blocking never
	 * overcharges, never causes an overload or depletion, and does not count as an attack.
	 */
	private void handleBlockRestore()
	{
		handleBlockRestore(equippedShieldId());
	}

	/**
	 * The block PKP with this off-hand item, first rule that matches: an empty shield slot or a two-handed weapon earns nothing; a penalty item (books, the
	 * Antler guard) LOSES its amount; a no-block item (god d'hide shields, defenders) earns nothing; a
	 * shield with its own codex amount earns that under EVERY god; anything else earns the god's
	 * usual amount (0 for gods that do not reward blocking). Negative = PKP lost.
	 */
	private int blockGain(int shieldId)
	{
		// nothing in the shield slot, or a two-handed weapon in hand: a block earns nothing
		if (shieldId <= 0 || twoHandedEquipped())
		{
			return 0;
		}
		return blockAmountForItem(shieldId);
	}

	private Integer blockPenaltyAmount(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Integer byId = blockPenaltyIds.get(itemId);
		return byId != null ? byId : blockPenaltyNames.get(itemName(itemId));
	}

	/** True when the weapon really worn is two-handed (by its item stats). */
	private boolean twoHandedEquipped()
	{
		int weapon = equippedWeaponId();
		if (weapon <= 0)
		{
			return false;
		}
		ItemStats stats = itemManager.getItemStats(weapon);
		ItemEquipmentStats eq = stats == null ? null : stats.getEquipment();
		return eq != null && eq.isTwoHanded();
	}

	/** An item the codex names here, in the shield slot, earns nothing from a block. */
	private boolean isNoBlockPkpItem(int itemId)
	{
		return itemId > 0 && (noBlockPkpIds.contains(itemId) || noBlockPkpNames.contains(itemName(itemId)));
	}

	private Integer shieldBlockAmount(int shieldId)
	{
		if (shieldId <= 0)
		{
			return null;
		}
		Integer byId = shieldPkpIds.get(shieldId);
		return byId != null ? byId : shieldPkpNames.get(itemName(shieldId));
	}

	private int equippedShieldId()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return -1;
		}
		Item shield = eq.getItem(SHIELD_SLOT);
		return (shield != null && shield.getId() > 0) ? shield.getId() : -1;
	}

	/**
	 * Blocking only ever fills toward the maximum: it never overcharges, never sets OVERLOAD and never
	 * escalates one to CRITICAL OVERLOAD (that is handleAttackRestore's job, and blocks never reach it).
	 * At or above the maximum a block simply earns nothing. A penalty item's block costs PKP instead,
	 * but only down to zero: blocking never causes depletion either.
	 */
	private void handleBlockRestore(int shieldId)
	{
		int gain = blockGain(shieldId);
		if (gain == 0 || !rules.isActive())
		{
			return;
		}
		lastCombatMs = System.currentTimeMillis();
		double before = pkp;
		if (gain < 0)
		{
			// a penalty drains only down to zero: blocking never causes depletion
			if (pkp > 0)
			{
				addPkp(-Math.min(-gain, pkp));
			}
		}
		else if (pkp < effectiveMax())
		{
			addPkp(Math.min(gain, effectiveMax() - pkp));
		}
		notePkpChange(pkp - before);
	}

	/**
	 * True when the swing now playing is an attack: the player's last NPC click was Attack, or they are
	 * fighting back against something attacking them (auto-retaliate) - their target is a combatant
	 * whose own target is them. Pickpocketing and other non-attack clicks are neither.
	 */
	private boolean attackingNow()
	{
		if (attackMode)
		{
			return true;
		}
		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		return target != null && target.getCombatLevel() > 0 && target.getInteracting() == local;
	}

	private void handleMeleeRestore()
	{
		handleAttackRestore(restoreForEquippedWeapon(), "melee");
	}

	private void handleRangedRestore(int gain)
	{
		handleAttackRestore(gain, "ranged");
	}

	/**
	 * A PKP-restoring attack landed (melee swing or ranged shot). Both routes share the overload check,
	 * the overcharge burn, the armed-freeze trigger and the immunity countdown, so a ranged build hits
	 * exactly the same rules as a melee one.
	 */
	private void handleAttackRestore(int gain, String kind)
	{
		// another restoring attack while still overloaded, before resolving by casting (the resolution condition)
		// any restoring attack while overloaded escalates - one the player clicked, or one auto-retaliate
		// threw for them
		if (rules.illegalRepeatActions() && attackingNow() && isOverloaded())
		{
			fireCriticalOverload(kind + " attack while overloaded");
		}
		double beforeAttack = pkp;
		// an armed freeze starts here, before any PKP moves, so this attack's gain is the one it cancels
		startPendingFreeze();
		// ...and then this attack either renews a sustained freeze or breaks it. A ranged shot always
		// breaks one, because it cannot have come from the staff holding the freeze up.
		noteSustainAction(false, null, equippedWeaponId());

		// overload if already at/above max, or if the gain is more than twice the PKP you were missing
		// Only a gain can overload. A ranged weapon with a NEGATIVE per-weapon amount costs PKP to
		// fire, and firing one at a full bar must drain it, not punish you for filling what you just
		// spent - without this guard `atMax` is true on the way down and the attack overloads instead.
		// The depletion side needs no guard: addPkp already latches DEPLETED when the pool goes under.
		boolean atMax = gain > 0 && pkp >= effectiveMax();
		boolean bigGain = rules.bigMoveTriggers() && gain > 2 * Math.max(0, effectiveMax() - pkp);
		boolean nowOverloaded = !isPkpFrozen() && !overloadImmune() && (atMax || bigGain);
		if (nowOverloaded)
		{
			overloadActive = true;
		}
		addPkp(gain);
		// Entering the overloaded state voids any overcharge: the pool snaps straight back down to its
		// normal max. Overcharge is otherwise permanent, so overloading is the one thing that burns it.
		if (nowOverloaded)
		{
			setPkp(effectiveMax(), false);
		}
		notePkpChange(pkp - beforeAttack);
		consumeImmunityAttacks();
		lastCombatMs = System.currentTimeMillis();
	}

	/**
	 * Enter (or restart) CRITICAL OVERLOAD: a restoring attack landed while already overloaded.
	 *
	 * <p>Runs for 10 ticks and is restarted in full by every further restoring attack, so a player
	 * who keeps swinging never leaves it. While it is up the overload AND depletion punishments both
	 * apply and teleport options are stripped, leaving the exempt item as the only way out.
	 */
	private void fireCriticalOverload(String reason)
	{
		long now = System.currentTimeMillis();
		criticalUntilMs = now + CRITICAL_OVERLOAD_MS;
		lastCombatMs = now;
		inCombatUntilMs = Math.max(inCombatUntilMs, now + COMBAT_GRACE_MS);
	}

	/** Raise the ILLEGAL BEHAVIOR warning and keep it pinned while in combat. */
	private void fireIllegal(String reason)
	{
		illegalActive = true;
		lastCombatMs = System.currentTimeMillis();
	}

	/** True for an item worn in the weapon slot (by its item stats); used only for the tooltip. */
	private boolean isMeleeWieldable(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		ItemEquipmentStats eq = stats == null ? null : stats.getEquipment();
		return stats != null && stats.isEquipable() && eq != null && eq.getSlot() == WEAPON_SLOT;
	}

	/** True for an item worn in the shield slot (by its item stats); used only for the tooltip. */
	private boolean isShieldSlotItem(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		ItemEquipmentStats eq = stats == null ? null : stats.getEquipment();
		return stats != null && stats.isEquipable() && eq != null && eq.getSlot() == SHIELD_SLOT;
	}

	/** Block PKP this off-hand item would give, by the same rules as blockGain. Negative = lost. */
	private int blockAmountForItem(int itemId)
	{
		Integer penalty = blockPenaltyAmount(itemId);
		if (penalty != null)
		{
			return -Math.abs(penalty);
		}
		if (isNoBlockPkpItem(itemId))
		{
			return 0;
		}
		Integer own = shieldBlockAmount(itemId);
		if (own != null)
		{
			return Math.max(0, own);
		}
		// Any other shield: the god's own amount, which may be negative (Zamorak drains on a block).
		return rules.blockRestore();
	}

	/** The sworn god's name for tooltips, e.g. "Saradomin". */
	private String godName()
	{
		Faction f = oath.getFaction();
		return f == null ? "your god" : f.getDisplayName();
	}

	private int restoreForEquippedWeapon()
	{
		int wId = equippedWeaponId();
		if (wId > 0)
		{
			Integer amount = weaponAdvAmount(wId);
			if (amount != null)
			{
				return amount;
			}
		}
		return rules.meleeRestore();
	}

	private boolean isRangedEquipped()
	{
		return isRangedWeapon(equippedWeaponId());
	}

	/** A weapon counts as ranged if it is in the allowed list or has its own ranged PKP amount. */
	private boolean isRangedWeapon(int itemId)
	{
		if (itemId <= 0)
		{
			return false;
		}
		String name = itemName(itemId);
		return rangedIds.contains(itemId) || rangedNames.contains(name)
			|| rangedAdvIds.containsKey(itemId) || rangedAdvNames.containsKey(name);
	}

	/**
	 * PKP a ranged hit with this weapon restores, or null if it is not a ranged weapon at all. A weapon
	 * with its own listed amount uses that; any other allowed ranged weapon uses the base ranged restore.
	 */
	private Integer rangedRestoreAmount(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		String name = itemName(itemId);
		Integer amount = rangedAdvIds.get(itemId);
		if (amount == null)
		{
			amount = rangedAdvNames.get(name);
		}
		if (amount != null)
		{
			return amount;
		}
		return (rangedIds.contains(itemId) || rangedNames.contains(name)) ? rules.rangedRestore() : null;
	}

	// ============================================================ per-tick upkeep

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final long now = System.currentTimeMillis();
		tickCounter++;

		// What the player is wearing can change at any moment, and the ceiling moves with it.
		recomputeWornMaxPkpBonus();

		// A sustained freeze lapses when nothing has renewed it for the combat grace period. Applied
		// here rather than inside the isPkpFrozen() query, which the overlays call every frame.
		if (sustainFreezeWeaponId > 0 && !sustainHeld())
		{
			endSustainFreeze("no paired action for " + rules.oocThresholdTicks() + " ticks");
		}
		// ...and once the authored ceiling runs out there is nothing left to sustain.
		if (sustainFreezeWeaponId > 0 && !isPkpFrozen())
		{
			sustainFreezeWeaponId = -1;
			sustainFreezeActionMs = 0L;
		}

		// Quest state can only be read on the client thread, and a quest can finish mid-fight, so the
		// gated quest items are re-checked every tick rather than only when the caches rebuild.
		refreshQuestProgress();

		Player local = client.getLocalPlayer();
		if (local != null && local.getInteracting() != null)
		{
			inCombatUntilMs = now + COMBAT_GRACE_MS;
			lastCombatMs = now;
		}

		// the ILLEGAL warning clears only after the configured time out of combat
		if (illegalActive && now - lastCombatMs >= rules.illegalClearSeconds() * 1000L)
		{
			illegalActive = false;
		}
		// CRITICAL OVERLOAD drops back to plain overload when its 10 ticks run out, the moment combat
		// ends, or if the overload it sits on top of is cleared (by a spec/consumable CO token).
		if (criticalUntilMs > 0L && (now >= criticalUntilMs || !inCombat() || !isOverloaded()))
		{
			criticalUntilMs = 0L;
		}
		// the PSI overload lockout ends 3 seconds after combat ends
		if (overloadActive && now - lastCombatMs >= rules.overloadClearMs())
		{
			overloadActive = false;
		}
		// drop faded PKP change popups
		if (!pkpPopups.isEmpty())
		{
			long life = look.pkpPopupSeconds() * 1000L;
			pkpPopups.removeIf(p -> now - p.spawnMs > life);
		}
		// drop dissolved cast cards
		if (!castCards.isEmpty())
		{
			castCards.removeIf(c -> now - c.spawnMs > 2600L);
		}
		// level-up cards play one at a time: start the front card, drop it once it has fully faded
		if (!levelCards.isEmpty())
		{
			LevelCard head = levelCards.get(0);
			if (head.startMs == 0L)
			{
				head.startMs = now;
			}
			else if (now - head.startMs >= 3800L)
			{
				levelCards.remove(0);
			}
		}

		// Remember which weapon was in hand while the spec was armed. The energy drop is only observed on
		// the tick AFTER the spec fires, by which point a switch may already have happened - without this,
		// equippedWeaponId() returns the new weapon, specDeltaFor() misses, and the whole effect (PKP delta
		// AND the overload/depletion immunity) is silently dropped.
		final int weaponNow = equippedWeaponId();
		if (weaponNow > 0 && specEnabled())
		{
			armedSpecWeaponId = weaponNow;
			armedSpecMs = now;
		}

		// special attack detection (energy drop while a spec weapon is equipped)
		int specPct = varp(rules.specPercentVarpId());
		if (lastSpecPercent != Integer.MIN_VALUE && specPct < lastSpecPercent)
		{
			int wId = resolveSpecWeaponId(now);
			Integer delta = specDeltaFor(wId);
			if (delta != null)
			{
				// Specs may overcharge (above max) but never trigger overload or depletion; the
				// overload punishment only fires on a regular melee swing at/above max.
				//
				// Overcharging is NOT gated on the overchargeItems list. That list names the few
				// sources whose ordinary use is allowed to push past the cap - a consumable, mostly -
				// and requiring membership here meant that of every spec weapon in the codex only the
				// two that happen to be on it could overcharge. Every other spec landed on a full bar
				// and had its gain silently clamped away, which reads as the special attack doing
				// nothing. A spec is a deliberate, once-per-bar act: it earns the headroom.
				boolean over = delta >= 0 && rules.overchargeEnabled();
				// The delta goes THROUGH activateSpecEffect, not before it: a weapon that clears an
				// overload and also pays PKP has to pay it after the reset to 50%, or the reset eats
				// the payment. activateSpecEffect owns the whole ordering - clear, then delta, then
				// gamble - so the two call sites cannot disagree about it.
				specActivation = true;
				try
				{
					activateSpecEffect(specEffectFor(wId), now, poisonAdjust(delta), over, wId);
				}
				finally
				{
					specActivation = false;
				}
				lastCombatMs = now;
			}
			armedSpecWeaponId = -1; // consumed; never credit the same armed weapon to a later drop
		}
		lastSpecPercent = specPct;
		prevWeaponId = weaponNow;

		// drop the spell symbol / glow tint once combat ends
		boolean nowCombat = inCombat();
		if (wasInCombat && !nowCombat)
		{
		}
		wasInCombat = nowCombat;

		if (rules.oocRegenEnabled() && pkp < effectiveMax())
		{
			long threshold = rules.oocThresholdMs();
			if (now - lastCombatMs > threshold)
			{
				double perTick = (double) effectiveMax() / rules.oocRestoreSeconds() * TICK_SECONDS;
				if (rules.sitDoublesRegen() && isSitting())
				{
					perTick *= 2.0;
				}
				addPkp(perTick);
			}
		}

		// NOTE: overcharge does not decay on a timer. Once PKP is pushed above its max it stays there
		// until it is spent on casts, or until entering the overloaded state voids it (see
		// handleMeleeRestore) - out-of-combat time alone no longer drains it back down to max.
	}

	/**
	 * Which weapon fired the special attack whose energy drop we just observed. Prefers what is in hand,
	 * then the weapon the spec was armed with, then the weapon held on the previous tick - so speccing and
	 * immediately switching still applies that weapon's PKP delta and its freeze / immunity effects. Only
	 * candidates that are actually configured as spec weapons are accepted, and the armed weapon is only
	 * trusted briefly so a stale arm can't be credited to an unrelated later spec.
	 */
	private int resolveSpecWeaponId(long now)
	{
		int cur = equippedWeaponId();
		if (cur > 0 && specDeltaFor(cur) != null)
		{
			return cur;
		}
		if (armedSpecWeaponId > 0 && now - armedSpecMs < 5000L && specDeltaFor(armedSpecWeaponId) != null)
		{
			return armedSpecWeaponId;
		}
		if (prevWeaponId > 0 && specDeltaFor(prevWeaponId) != null)
		{
			return prevWeaponId;
		}
		return cur;
	}

	private boolean inCombat()
	{
		if (System.currentTimeMillis() < inCombatUntilMs)
		{
			return true;
		}
		Player local = client.getLocalPlayer();
		return local != null && local.getInteracting() != null;
	}

	private boolean isSitting()
	{
		if (sitAnimations.isEmpty())
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}
		return sitAnimations.contains(local.getAnimation()) || sitAnimations.contains(local.getPoseAnimation());
	}

	Color colorFor(PsiAction action)
	{
		switch (action)
		{
			case CURSE:
				return look.curseSpellColor();
			case COMBAT:
				return look.combatSpellColor();
			case MELEE:
			default:
				return look.meleeColor();
		}
	}

	// ============================================================ spec varps

	private int varp(int id)
	{
		if (id <= 0)
		{
			return 0;
		}
		try
		{
			return client.getVarpValue(id);
		}
		catch (Exception | LinkageError | AssertionError e)
		{
			// AssertionError included deliberately: the client's own thread assertion is an Error,
			// and one escaping from here would take the whole plugin down with it.
			return 0;
		}
	}

	private boolean specEnabled()
	{
		return varp(rules.specEnabledVarpId()) != 0;
	}

	private boolean spellSelected()
	{
		Widget sel = client.getSelectedWidget();
		return sel != null && (sel.getId() >>> 16) == SPELLBOOK_GROUP;
	}

	/**
	 * Is this quest equipment - the shared list that overrides every other equipment rule?
	 *
	 * <p>Takes the name as well as the id so the hot path in {@link #blockKind} does not look the
	 * composition up twice; {@link #isQuestItem(int)} is the convenience form for everywhere else.
	 */
	private boolean isQuestItem(int itemId, String lowerName)
	{
		if (itemId <= 0)
		{
			return false;
		}
		final String name = lowerName == null ? "" : lowerName;
		// Unconditional entries first: no quest state to consult, and the common case.
		if (questItemIds.contains(itemId) || (!name.isEmpty() && questItemNames.contains(name)))
		{
			return true;
		}
		return anyInProgress(questGatedIds.get(itemId))
			|| (!name.isEmpty() && anyInProgress(questGatedNames.get(name)));
	}

	/**
	 * Is any of these quests started and not yet finished?
	 *
	 * <p>Any one is enough, deliberately: an item listed against several quests is wanted by each of
	 * them, and requiring all of them at once would mean it was never available.
	 */
	private boolean anyInProgress(List<Quest> quests)
	{
		if (quests == null || quests.isEmpty())
		{
			return false;
		}
		for (Quest q : quests)
		{
			if (questsInProgress.contains(q))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Re-reads which of the pack's gating quests are in progress.
	 *
	 * <p>Called on the game tick, on the client thread, which is where {@code Quest.getState} has to
	 * be read from. Logged out there is no quest state to read, so the set is emptied rather than
	 * left stale - a conditional item goes back to the ordinary rules, which is the safe direction.
	 */
	private void refreshQuestProgress()
	{
		if (questGatedNames.isEmpty() && questGatedIds.isEmpty())
		{
			questsInProgress.clear();
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			questsInProgress.clear();
			return;
		}
		Set<Quest> gating = new HashSet<>();
		for (List<Quest> qs : questGatedNames.values())
		{
			gating.addAll(qs);
		}
		for (List<Quest> qs : questGatedIds.values())
		{
			gating.addAll(qs);
		}
		questsInProgress.clear();
		for (Quest q : gating)
		{
			try
			{
				if (q.getState(client) == QuestState.IN_PROGRESS)
				{
					questsInProgress.add(q);
				}
			}
			catch (Exception | LinkageError | AssertionError e)
			{
				// A varbit that is not loaded yet reads as absent rather than as "started".
			}
		}
	}

	/** The {@link Quest} constant whose name matches, or null. Matched the way the game spells it. */
	private Quest questByName(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return null;
		}
		String want = name.trim();
		for (Quest q : Quest.values())
		{
			if (q.getName() != null && q.getName().equalsIgnoreCase(want))
			{
				return q;
			}
		}
		return null;
	}

	boolean isQuestItem(int itemId)
	{
		return isQuestItem(itemId, itemId > 0 ? itemName(itemId) : "");
	}

	/**
	 * Is this item in the configured pacifist item class?
	 *
	 * <p>Quest equipment never is, however it is listed. A quest item you cannot fight while holding
	 * is a quest item you cannot use, which is the thing the quest list exists to prevent.
	 */
	boolean isPacifistItem(int itemId)
	{
		return itemId > 0 && !isQuestItem(itemId)
			&& (pacifistIds.contains(itemId) || pacifistNames.contains(itemName(itemId)));
	}

	/** Is any pacifist item currently worn (any equipment slot)? */
	boolean pacifistWorn()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return false;
		}
		for (Item worn : eq.getItems())
		{
			if (worn != null && isPacifistItem(worn.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Re-add up what the player's equipment is worth to the ceiling.
	 *
	 * <p>Every slot is scanned rather than just the shield slot the god books occupy, so the list
	 * stays a general "equipment that raises the maximum" rather than a book-shaped special case.
	 * Items are matched by id when the codex gives a number and by lower-cased name otherwise, the
	 * same way every other list is.
	 *
	 * <p>The ceiling moves on its own; PKP does not follow it <b>up</b>. Equipping a book takes
	 * 100/100 to 100/150 - a bigger pool to fill, not fifty free points. Taking it off is the exact
	 * inverse: the ceiling drops and anything left above it comes off with it, so 150/150 goes to
	 * 100/100 rather than sitting at 150/100 and reading as OVERLOADED. The clamp never reaches
	 * below the new maximum, so a player at 80/150 lands on 80/100 and loses nothing at all -
	 * un-equipping can only ever cost you PKP the smaller pool could not have held anyway.
	 */
	private void recomputeWornMaxPkpBonus()
	{
		// Reading the equipment container is a client-thread-only call: RuneLite asserts on it, and
		// an AssertionError is an Error, not an Exception, so it sails through every defensive catch
		// in this file and out of whatever lifecycle method is running - which RuneLite answers by
		// switching the plugin straight back off. rebuildCaches() runs from startUp and from the
		// config listener, neither of which is the client thread, so the check is here rather than at
		// the call sites. Nothing is lost by skipping: the game tick recomputes a moment later.
		if (!onClientThread())
		{
			return;
		}
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null && (!maxPkpItemNames.isEmpty() || !maxPkpItemIds.isEmpty()))
		{
			// No container to read: on a world hop or a loading screen it is briefly absent, and
			// reading that as "wearing nothing" would drop the ceiling, clamp the bar, and hand the
			// points back one tick later as a silent loss on every hop. The cached total stands.
			return;
		}
		int total = 0;
		if (eq != null && (!maxPkpItemNames.isEmpty() || !maxPkpItemIds.isEmpty()))
		{
			for (Item item : eq.getItems())
			{
				if (item == null || item.getId() <= 0)
				{
					continue;
				}
				Integer byId = maxPkpItemIds.get(item.getId());
				if (byId != null)
				{
					total += byId;
					continue;
				}
				Integer byName = maxPkpItemNames.get(itemName(item.getId()));
				if (byName != null)
				{
					total += byName;
				}
			}
		}
		if (total == wornMaxPkpBonus)
		{
			return;
		}
		boolean fell = total < wornMaxPkpBonus;
		wornMaxPkpBonus = total;
		if (fell)
		{
			clampToCeiling();
		}
	}

	/**
	 * Brings PKP back inside the maximum after the ceiling itself dropped.
	 *
	 * <p>Deliberately not routed through {@link #setPkp}, which is the path for PKP <i>transactions</i>
	 * - a cost, a restore, a spec - and is blocked by a freeze and by depletion immunity. This is not
	 * a transaction: the points are not being spent or lost, the pool that held them has shrunk, and a
	 * frozen or immune player who took off a book would otherwise be left permanently above a ceiling
	 * they can no longer reach. It is also silent: no popup and no gain/loss note, because the bar's
	 * fill does not move - 150/150 and 100/100 are both a full bar - so announcing "-50" would
	 * describe something the player cannot see and did not do.
	 *
	 * <p>Depletion cannot be triggered from here either: the clamp only ever moves PKP down to the
	 * maximum, never below zero, so there is nothing for the punishment to fire on.
	 */
	private void clampToCeiling()
	{
		int max = effectiveMax();
		if (pkp > max)
		{
			pkp = max;
			updateLatches(false);
		}
	}

	private int equippedWeaponId()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return -1;
		}
		Item weapon = eq.getItem(WEAPON_SLOT);
		return (weapon != null && weapon.getId() > 0) ? weapon.getId() : -1;
	}

	// ============================================================ costs / scaling

	private int manualCost(PsiAction f)
	{
		switch (f)
		{
			case CURSE:
				return rules.curseSpellCost();
			case COMBAT:
				return rules.combatSpellCost();
			default:
				return 0;
		}
	}

	/**
	 * Which PSI family charges for a spell, layering the user's curse-spell list on top of the built-in
	 * table. Returns null for anything that is not a recognised combat spell.
	 *
	 * <p>The curse list wins outright, so a spell can be moved into the PK Flash group (and onto its flat
	 * curse cost) purely from the config - including ones that would otherwise be a damage family, such as
	 * the rooting Ice spells.</p>
	 */
	PsiAction familyForSpell(String name)
	{
		if (name == null)
		{
			return null;
		}
		String key = name.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty())
		{
			return null;
		}
		if (curseSpells.contains(key))
		{
			return PsiAction.CURSE;
		}
		return PsiAction.isKnownCombatSpell(key) ? PsiAction.COMBAT : null;
	}

	/**
	 * Is this spell one of the other gods' - sworn away by the oath in force?
	 *
	 * <p>Independent of the combat-spell allow-list. A god's own spells are its own whatever else is
	 * switched on, and the other three gods' are off-limits whatever else is switched off.
	 */
	private boolean isBlockedGodSpell(String name)
	{
		if (name == null || blockedGodSpells.isEmpty())
		{
			return false;
		}
		String c = name.replaceAll("<[^>]*>", "").trim();
		return !c.isEmpty() && blockedGodSpells.contains(c.toLowerCase(Locale.ROOT));
	}

	/** Does this menu entry cast one of the other gods' spells? */
	private boolean godSpellBlockedEntry(MenuEntry me)
	{
		if (blockedGodSpells.isEmpty() || !isSpellEntry(me))
		{
			return false;
		}
		for (String c : spellCandidates(me))
		{
			if (isBlockedGodSpell(c))
			{
				return true;
			}
		}
		return false;
	}

	/** Per-spell cost: the flat family cost (curses have their own). It does not change with Magic level. */
	private int costForSpell(String name)
	{
		PsiAction fam = familyForSpell(name);
		if (fam == null)
		{
			return 0;
		}
		if (fam == PsiAction.CURSE)
		{
			return rules.curseSpellCost();
		}
		return manualCost(fam);
	}

	/** Records a discrete PKP change so it can float up as a popup (ignores tiny/zero changes). */
	/** A sample +25 / -25 pair for the appearance editor's popup preview. Client thread only. */
	void previewPopups()
	{
		long now = System.currentTimeMillis();
		pkpPopups.add(new PkpPopup(25, now, new Color(120, 240, 120)));
		pkpPopups.add(new PkpPopup(-25, now, new Color(255, 95, 95)));
	}

	private void notePkpChange(double delta)
	{
		if (!look.pkpPopupsEnabled())
		{
			return;
		}
		int d = (int) Math.round(delta);
		if (d == 0)
		{
			return;
		}
		Color c;
		if (isOvercharged() || isDepleted())
		{
			c = barFrameColor();
		}
		else
		{
			c = d > 0 ? new Color(120, 240, 120) : new Color(255, 95, 95);
		}
		pkpPopups.add(new PkpPopup(d, System.currentTimeMillis(), c));
		if (pkpPopups.size() > 12)
		{
			pkpPopups.remove(0);
		}
	}

	java.util.List<PkpPopup> pkpPopups()
	{
		return pkpPopups;
	}

	/** A sample cast card for the appearance editor. Client thread only. */
	void previewCastCard(PsiAction fam)
	{
		String name = fam == PsiAction.CURSE ? "Confuse" : fam == PsiAction.COMBAT ? "Wind Wave" : "Melee";
		castCards.add(new CastCard(fam, name, -1, System.currentTimeMillis(), 1f));
		if (castCards.size() > 8)
		{
			castCards.remove(0);
		}
	}

	/** Records a cast so the "Cast cards" mode can drop a dissolving card for it. */
	private void noteCast(PsiAction fam, String spellName, int spriteId)
	{
		if (fam == null || !look.castCardsEnabled())
		{
			return;
		}
		// the card is always labelled with the spell's own spellbook name
		if (spellName == null || spellName.isEmpty())
		{
			return;
		}
		// fire strike is the weakest spell, so its card is rendered at half size
		float scale = spellName.toLowerCase(Locale.ROOT).contains("fire strike") ? 0.5f : 1f;
		castCards.add(new CastCard(fam, spellName, spriteId, System.currentTimeMillis(), scale));
		if (castCards.size() > 8)
		{
			castCards.remove(0);
		}
	}

	java.util.List<CastCard> castCards()
	{
		return castCards;
	}

	boolean illegalBehaviorActive()
	{
		return illegalActive;
	}

	/** True while the CRITICAL OVERLOAD punishment is running. */
	/**
	 * Starts the one-time pop-in for the PKP bar.
	 *
	 * <p>A no-op once they are already on screen, so changing god later does not replay the
	 * animation for something the player is already looking at.
	 */
	void beginReveal()
	{
		if (revealStartMs <= 0L)
		{
			revealStartMs = System.currentTimeMillis();
			// The sting rides the same guard as the animation, so it plays exactly when the bar
			// first appears and never again for a god swapped later - the player is already looking
			// at the bar by then, and a sound with nothing to announce is just noise.
			sfx.play(Sfx.OATH_REVEAL, OATH_SOUND_VOLUME);
		}
	}

	/** Hides the bar again, so a reset oath gets the full reveal a second time. */
	void clearReveal()
	{
		revealStartMs = 0L;
	}

	/**
	 * 0 while the bar is hidden, climbing to 1 across the pop-in, then staying at 1.
	 * Overlays treat 0 as "draw nothing".
	 */
	double revealProgress()
	{
		if (revealStartMs <= 0L)
		{
			return 0.0;
		}
		long elapsed = System.currentTimeMillis() - revealStartMs;
		if (elapsed >= RevealAnim.DURATION_MS)
		{
			return 1.0;
		}
		return Math.max(0.0, elapsed / (double) RevealAnim.DURATION_MS);
	}

	boolean criticalOverloadActive()
	{
		return criticalUntilMs > System.currentTimeMillis();
	}

	/**
	 * How much of the 10 ticks is left, 1.0 at the start down to 0.0 at expiry, for the ring drawn
	 * along the PKP bar. Returns 0 when the state is not running.
	 */
	double criticalOverloadRemaining()
	{
		if (!criticalOverloadActive())
		{
			return 0.0;
		}
		long left = criticalUntilMs - System.currentTimeMillis();
		return Math.max(0.0, Math.min(1.0, left / (double) CRITICAL_OVERLOAD_MS));
	}

	/**
	 * The universal lockdown: either the ILLEGAL BEHAVIOR warning or CRITICAL OVERLOAD is up. Both
	 * strip spells, attacks, food and potions and both wear the same yellow treatment; they differ
	 * only in what raised them and what the player sees over the game view.
	 */
	boolean lockdownActive()
	{
		return illegalActive || criticalOverloadActive();
	}

	/** Whether PKP amounts are displayed as raw numbers (true) or percentages of max (false). */
	boolean showPkpNumbers()
	{
		return look.showPkpValue();
	}

	/** A PKP amount as a percentage of max, rounded to the nearest whole number. Display only. */
	int asPercent(double amount)
	{
		int max = effectiveMax();
		return max <= 0 ? 0 : (int) Math.round(amount / max * 100.0);
	}

	/** Formats an unsigned PKP amount for display: raw number, or "N%" of max when values are hidden. */
	String fmtPkp(int amount)
	{
		return showPkpNumbers() ? Integer.toString(amount) : asPercent(amount) + "%";
	}

	/** Formats a signed PKP change for popups: "+N"/"-N", or "+N%"/"-N%" of max when values are hidden. */
	String fmtPkpDelta(int delta)
	{
		if (showPkpNumbers())
		{
			return (delta > 0 ? "+" : "") + delta;
		}
		int pct = asPercent(delta);
		return (pct > 0 ? "+" : "") + pct + "%";
	}

	/**
	 * A monotonically increasing time (ms) for the poison water animation that only advances while the bar is
	 * NOT frozen, so freezing the bar (item/spec) halts the water in place and it resumes on unfreeze. Driven
	 * by the bar overlay, which calls this once per rendered frame.
	 */
	long poisonAnimTime()
	{
		long now = System.currentTimeMillis();
		if (!poisonAnimInit)
		{
			poisonAnimLastMs = now;
			poisonAnimInit = true;
		}
		long elapsed = now - poisonAnimLastMs;
		poisonAnimLastMs = now;
		if (elapsed > 0 && !isPkpFrozen())
		{
			poisonAnimAccumMs += elapsed;
		}
		return poisonAnimAccumMs;
	}

	boolean isPoisoned()
	{
		return rules.poisonCostEnabled() && varp(rules.poisonVarpId()) > 0;
	}

	/** Scales a PKP cost up while poisoned. */
	private int withPoison(int cost)
	{
		if (cost <= 0 || !isPoisoned())
		{
			return cost;
		}
		return (int) Math.round(cost * (1.0 + rules.poisonCostPercent() / 100.0));
	}

	/**
	 * Poison penalty applied to a PKP change from any source (spell, special attack, food/consumable): gains
	 * restore less and losses cost more, by the same poison cost percentage used for spells.
	 */
	private double poisonAdjust(double delta)
	{
		if (delta == 0 || !isPoisoned())
		{
			return delta;
		}
		double pct = rules.poisonCostPercent() / 100.0;
		if (delta > 0)
		{
			return delta * Math.max(0.0, 1.0 - pct); // restore less
		}
		return delta * (1.0 + pct); // cost more (delta is negative -> more negative)
	}

	private int magicLevel()
	{
		if (!onClientThread())
		{
			return 1;
		}
		try
		{
			return client.getRealSkillLevel(Skill.MAGIC);
		}
		catch (Exception | LinkageError | AssertionError e)
		{
			return 1;
		}
	}

	/**
	 * Is the current thread the one the client allows its API to be touched from?
	 *
	 * <p>Most of RuneLite's getters assert this, and the assertion throws an {@link AssertionError} -
	 * an Error, not an Exception. A {@code catch (Exception)} does not stop one, so it escapes the
	 * plugin, and RuneLite's answer to a throw out of a lifecycle method is to disable the plugin.
	 * That is exactly what "I turn it on and it turns itself off" looks like.
	 *
	 * <p>The question itself is answered defensively, because asking it before the client exists
	 * must not be the thing that throws.
	 */
	private boolean onClientThread()
	{
		try
		{
			return client != null && client.isClientThread();
		}
		catch (Exception | LinkageError | AssertionError e)
		{
			return false;
		}
	}

	private int effectiveMax()
	{
		// The worn bonus is added to the authored pool before the magic-level scaling, so a god book
		// raises the ceiling by a flat amount whatever the player's Magic level. It is read from a
		// cached total rather than the equipment container, because this is called from the overlays
		// every frame and from the tick loop.
		int base = rules.maxPkp() + wornMaxPkpBonus;
		if (rules.magicScalingEnabled())
		{
			int lvl = magicLevel();
			if (lvl >= 50)
			{
				base += rules.magicBonus50();
			}
			if (lvl >= 75)
			{
				base += rules.magicBonus75();
			}
			if (lvl >= 85)
			{
				base += rules.magicBonus85();
			}
			if (lvl >= 88)
			{
				base += rules.magicBonus88();
			}
			if (lvl >= 90)
			{
				base += rules.magicBonus90();
			}
			if (lvl >= 95)
			{
				base += rules.magicBonus95();
			}
			if (lvl >= 99)
			{
				base += rules.magicBonus99();
			}
		}
		return Math.max(1, base);
	}

	private int overchargeCap()
	{
		return rules.overchargeEnabled() ? effectiveMax() + rules.overchargeHeadroom() : effectiveMax();
	}

	// ============================================================ state queries (overlays)

	int getMaxPkp()
	{
		return effectiveMax();
	}

	int getCurrentPkp()
	{
		return (int) Math.round(pkp);
	}

	int minSpellCost()
	{
		return Math.min(rules.combatSpellCost(), rules.curseSpellCost());
	}

	boolean isDepleted()
	{
		return depletedLatch && !depletionImmune();
	}

	boolean isOverloaded()
	{
		return overloadActive && !overloadImmune();
	}

	/** Eating is locked while overloaded, and for a short tail after the overload clears (e.g. via casting). */
	private boolean foodLockedByOverload()
	{
		return (isOverloaded() || System.currentTimeMillis() < overloadFoodUnlockMs);
	}

	boolean isOvercharged()
	{
		return pkp > effectiveMax();
	}

	/**
	 * Every timed effect currently active on the PKP bar, in a stable order (freeze, overload immunity,
	 * depletion immunity). Empty when nothing is running. Immunity and freeze state is held on the player,
	 * not the weapon, so these survive weapon switches, logouts of the interface, and gear swaps - only
	 * elapsed time and attacks spent tick them down.
	 */
	java.util.List<ActiveEffect> activeEffects()
	{
		java.util.List<ActiveEffect> out = new java.util.ArrayList<>();
		final long now = System.currentTimeMillis();

		// A sample set for the appearance editor, so badge size and the border segments can be
		// judged without having to trigger the effects.
		if (look.sampleEffects())
		{
			out.add(new ActiveEffect(EffectKind.FREEZE, FROZEN_FRAME, 4200L, 0, false));
			out.add(new ActiveEffect(EffectKind.OVERLOAD_IMMUNE, look.overloadColor(), 0L, 3, false));
			out.add(new ActiveEffect(EffectKind.DEPLETION_IMMUNE, look.depletedColor(), 6000L, 2, false));
			return out;
		}

		long freezeMs = Math.max(0L, freezeUntilMs - now);
		if (freezeMs > 0 || freezeAttacks > 0)
		{
			out.add(new ActiveEffect(EffectKind.FREEZE, FROZEN_FRAME, freezeMs, freezeAttacks, false));
		}
		else if (freezePending())
		{
			// armed by a spec or consumable, waiting for the next attack or cast to start it
			out.add(new ActiveEffect(EffectKind.FREEZE, FROZEN_FRAME,
				pendingFreezeSeconds * 1000L, pendingFreezeAttacks, true));
		}

		long overMs = Math.max(0L, overloadImmuneUntilMs - now);
		if (overMs > 0 || overloadImmuneAttacks > 0)
		{
			out.add(new ActiveEffect(EffectKind.OVERLOAD_IMMUNE, look.overloadColor(), overMs, overloadImmuneAttacks, false));
		}

		long depMs = Math.max(0L, depletionImmuneUntilMs - now);
		if (depMs > 0 || depletionImmuneAttacks > 0)
		{
			out.add(new ActiveEffect(EffectKind.DEPLETION_IMMUNE, look.depletedColor(), depMs, depletionImmuneAttacks, false));
		}
		return out;
	}

	/** True while the bank interface is open - the PKP bar steps out of its way. Client thread. */
	boolean bankOpen()
	{
		Widget bank = client.getWidget(rules.bankWidgetGroup(), rules.bankWidgetChild());
		return bank != null && !bank.isHidden();
	}

	/** The PKP bar's current frame colour, reflecting frozen / depleted / overcharged / overload state. */
	Color barFrameColor()
	{
		// the appearance editor's sample states take the frame with them
		if (look.sampleOvercharge())
		{
			return look.overchargeColor();
		}
		switch (look.sample())
		{
			case DEPLETED:
				return look.depletedColor();
			case OVERLOAD:
			case CRITICAL:
				return look.overloadColor();
			default:
				break;
		}
		if (isPkpFrozen())
		{
			return FROZEN_FRAME;
		}
		if (isDepleted())
		{
			return look.depletedColor();
		}
		if (isOvercharged())
		{
			return look.overchargeColor();
		}
		if (isOverloaded())
		{
			return look.overloadColor();
		}
		return new Color(230, 230, 230, 220);
	}

	private double baseRegenPerTick()
	{
		return (double) effectiveMax() / Math.max(1, rules.oocRestoreSeconds()) * TICK_SECONDS;
	}

	/** Current steady-state PKP regen rate per tick (OOC incl. sit bonus, plus prayer averaged). */
	double currentRegenPerTick()
	{
		if (isPkpFrozen() || pkp >= effectiveMax())
		{
			return 0;
		}
		double rate = 0;
		long now = System.currentTimeMillis();
		if (rules.oocRegenEnabled() && now - lastCombatMs > rules.oocThresholdMs())
		{
			double perTick = baseRegenPerTick();
			if (rules.sitDoublesRegen() && isSitting())
			{
				perTick *= 2.0;
			}
			rate += perTick;
		}
		return rate;
	}

	boolean regenActive()
	{
		return currentRegenPerTick() > 0.001;
	}

	/** Regen rate relative to the base out-of-combat rate (1.0 = base, 2.0 = sit-doubled, ...). */
	double regenIntensity()
	{
		double base = baseRegenPerTick();
		if (base <= 0)
		{
			return 1.0;
		}
		return Math.max(0.3, Math.min(3.5, currentRegenPerTick() / base));
	}

	String tooltipFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		final String name = itemName(itemId);

		// Blocked gear: say why it can't be used instead of a PKP value.
		switch (blockKind(itemId))
		{
			case FACTION_WEAPON:
			case FACTION_GEAR:
				return "<col=ff2a2a>(Forbidden by " + godName() + ")</col>";
			case POWERED_STAFF:
				return "<col=ff2a2a>(No powered staves or autocasting)</col>";
			case WEAPON:
				return "<col=ff2a2a>(Weapons need more than " + (rules.magicAttackMinimum() - 1) + " magic attack)</col>";
			case ARMOR:
				return "<col=ff2a2a>(Too magically detrimental: armour needs more than "
					+ rules.magicAttackThreshold() + " magic attack)</col>";
			default:
				break;
		}

		Integer restore = restoreAmount(itemId);
		Integer adv = weaponAdvAmount(itemId);
		Integer spec = specDeltaFor(itemId);
		Integer cDelta = consumableDeltaFor(itemId);
		boolean quest = isQuestItem(itemId, name);
		boolean ranged = isRangedWeapon(itemId);
		Integer rangedGain = rangedRestoreAmount(itemId);
		boolean over = isOverchargeSource(itemId);
		boolean staff = isWieldableStaff(itemId);
		boolean weaponException = weaponExceptionIds.contains(itemId) || weaponExceptionNames.contains(name);

		List<String> lines = new ArrayList<>();
		// First, because it is the reason every rule below it does not apply.
		if (quest)
		{
			lines.add("<col=9cff57>Quest item</col> - every god allows it");
		}
		if (restore != null)
		{
			lines.add("<col=46c8ff>PKP</col> " + signedPkp(restore) + " on use"
				+ (over && restore >= 0 ? " <col=9cff57>(overcharge)</col>" : ""));
		}
		// configurable consumable effect: numeric change (a pure 0 shows only its status effect below)
		if (cDelta != null && cDelta != 0)
		{
			lines.add("<col=46c8ff>PKP</col> " + signedPkp(cDelta) + " on use"
				+ (over && cDelta >= 0 ? " <col=9cff57>(overcharge)</col>" : ""));
		}
		// PKP per melee auto-attack - same wording for every weapon class. An advantageous value
		// wins; otherwise any wieldable melee weapon (staff, listed exception or one that cleared the
		// gate) shows the base melee restore, which is what it really pays. Listed alongside any spec.
		Integer perHit = null;
		if (adv != null)
		{
			perHit = adv;
		}
		else if (staff)
		{
			perHit = rules.meleeRestore();
		}
		else if (weaponException)
		{
			// What restoreForEquippedWeapon actually pays a listed weapon with no amount of its own.
			perHit = rules.meleeRestore();
		}
		else if (isMeleeWieldable(itemId) && !ranged && !isPacifistItem(itemId))
		{
			// Any other weapon that passed the gear check above (e.g. an Adamant dagger, which clears the
			// magic-attack minimum without a codex entry) earns the base melee restore - exactly what
			// restoreForEquippedWeapon pays for it.
			perHit = rules.meleeRestore();
		}
		if (perHit != null)
		{
			lines.add("<col=46c8ff>PKP</col> " + signedPkp(perHit) + " per melee attack");
		}
		// PKP per block for anything worn in the shield slot. Same rule order as blockGain, minus the
		// "what is equipped right now" checks (empty slot / two-handed weapon), which a tooltip can't know.
		if (isShieldSlotItem(itemId))
		{
			lines.add("<col=46c8ff>PKP</col> " + signedPkp(blockAmountForItem(itemId)) + " per block");
		}
		SpecEffect specFx = specEffectFor(itemId);
		boolean gamble = specFx != null && specFx.hasGamble();
		// A gamble weapon's spec is described by its Gamble line alone: a flat "+0" next to it is noise.
		if (spec != null && !(gamble && spec == 0))
		{
			// "(overcharge)" marks only weapons dedicated to overcharging (the codex's overchargeItems),
			// never a gamble weapon, even though any spec gain can overcharge in play.
			lines.add("<col=46c8ff>PKP</col> " + signedPkp(spec) + " on special attack"
				+ (rules.overchargeEnabled() && over && !gamble && spec > 0 ? " <col=9cff57>(overcharge)</col>" : ""));
		}
		// Status effects (freeze / immunity / multiplier / clear) from consumables and spec weapons.
		// Shown even with no numeric PKP change, e.g. the Tea flask (restores 0 but clears states).
		addEffectLines(lines, consumableEffectFor(itemId), itemId, " on use");
		addEffectLines(lines, specEffectFor(itemId), itemId, " on special attack");
		if (isPacifistItem(itemId))
		{
			lines.add("<col=ff2a2a>Attack options disabled</col>");
		}
		if (ranged)
		{
			if (rules.rangedRestoreEnabled() && rangedGain != null && rangedGain != 0)
			{
				lines.add("<col=46c8ff>PKP</col> " + signedPkp(rangedGain) + " per ranged hit");
			}
			else
			{
				lines.add("<col=35e36b>PKP neutral</col> (ranged)");
			}
		}
		if (lines.isEmpty())
		{
			return null;
		}
		return String.join("</br>", lines);
	}

	/** "+N"/"-N" (or "+N%/-N%"), with the sign chosen by the value so a negative never prints "+-N". */
	private String signedPkp(int v)
	{
		return (v >= 0 ? "+" : "-") + fmtPkp(Math.abs(v));
	}

	/** Appends human-readable lines for a status effect (used by both consumables and spec weapons). */
	private void addEffectLines(List<String> lines, SpecEffect fx, int sourceItemId, String when)
	{
		if (fx == null || !fx.any())
		{
			return;
		}
		if (fx.freezeSeconds > 0 || fx.freezeAttacks > 0)
		{
			if (fx.freezeSeconds > 0)
			{
				lines.add("<col=9cd6ff>Freezes PKP for " + fx.freezeSeconds + "s" + when + "</col>");
			}
			if (fx.freezeAttacks > 0)
			{
				lines.add("<col=9cd6ff>Freezes PKP for " + fx.freezeAttacks
					+ plural(fx.freezeAttacks, " attack") + when + "</col>");
			}
			Set<String> paired = sustainSpellsFor(sourceItemId);
			if (paired != null)
			{
				// The duration above is a ceiling on these: say what has to keep happening for the
				// freeze to actually reach it.
				lines.add(paired.isEmpty()
					? "<col=9cd6ff>...only while you keep attacking with this weapon</col>"
					: "<col=9cd6ff>...only while you keep casting " + String.join(", ", paired)
						+ " or swinging it</col>");
				lines.add("<col=ff2a2a>Any other spell or attack ends it early</col>");
			}
		}
		if (fx.overloadImmuneSeconds > 0)
		{
			lines.add("<col=9cff57>Overload immunity " + fx.overloadImmuneSeconds + "s" + when + "</col>");
		}
		if (fx.overloadImmuneAttacks > 0)
		{
			lines.add("<col=9cff57>Overload immunity " + fx.overloadImmuneAttacks + plural(fx.overloadImmuneAttacks, " attack") + when + "</col>");
		}
		if (fx.depletionImmuneSeconds > 0)
		{
			lines.add("<col=9cff57>Depletion immunity " + fx.depletionImmuneSeconds + "s" + when + "</col>");
		}
		if (fx.depletionImmuneAttacks > 0)
		{
			lines.add("<col=9cff57>Depletion immunity " + fx.depletionImmuneAttacks + plural(fx.depletionImmuneAttacks, " attack") + when + "</col>");
		}
		if (fx.clearOverload || fx.clearDepletion)
		{
			String what = (fx.clearOverload && fx.clearDepletion) ? "overload & depletion"
				: fx.clearOverload ? "overload" : "depletion";
			lines.add("<col=9cff57>Clears " + what + when + " (if active), sets PKP to 50%</col>");
		}
		if (fx.hasGamble())
		{
			// Plain text only: the overlay does not decode HTML entities, so no &plusmn; here.
			if (fx.randomLoss == 0)
			{
				lines.add("<col=ffd34d>Gamble: +" + fx.randomGain + " PKP / +0 PKP" + when + "</col>");
			}
			else if (fx.randomGain == fx.randomLoss)
			{
				lines.add("<col=ffd34d>Gamble: +/- " + fx.randomGain + " PKP" + when + "</col>");
			}
			else
			{
				lines.add("<col=ffd34d>Gamble: +" + fx.randomGain + " PKP / -" + fx.randomLoss + " PKP" + when + "</col>");
			}
		}
	}

	private static String plural(int n, String unit)
	{
		return unit + (n == 1 ? "" : "s");
	}

	/** A wieldable magic staff/wand (used so its tooltip can show the base melee PKP-on-hit gain). */
	private boolean isWieldableStaff(int itemId)
	{
		if (itemId <= 0)
		{
			return false;
		}
		String n = itemName(itemId);
		return n.contains("staff") || n.contains("staves") || n.contains("wand")
			|| n.contains("trident") || n.contains("sceptre") || n.contains("scepter");
	}

	// ============================================================ pkp mutation

	private void applyCast(int cost)
	{
		applyCast(cost, null);
	}

	/**
	 * Spend a cast's cost.
	 *
	 * @param spellName the spell being cast, when it is known; a sustained freeze needs the name to
	 *                  tell a paired god spell from anything else
	 */
	private void applyCast(int cost, String spellName)
	{
		// an armed freeze starts here, before any PKP moves, so this cast's cost is the one it cancels
		startPendingFreeze();
		// ...and this cast either renews a sustained freeze or breaks it
		noteSustainAction(true, spellName, equippedWeaponId());

		// another mage attack while still depleted, before recovering to full (the resolution condition)
		if (rules.illegalRepeatActions() && attackMode && isDepleted())
		{
			fireIllegal("mage attack while depleted");
		}
		lastCastMs = System.currentTimeMillis();
		double before = pkp;
		if (pkp > 0)
		{
			// a cast costing more than twice your remaining PKP triggers depletion outright
			boolean bigOverspend = rules.bigMoveTriggers() && !depletionImmune() && cost > 2 * before;
			// deduct the cost; a cast from a positive value cannot be driven below 0 (no punishment)
			setPkp(Math.max(0, pkp - cost), false);
			if (bigOverspend && !depletedLatch)
			{
				depletedLatch = true;
			}
		}
		else
		{
			// already empty: the cast goes negative and triggers the depletion punishment
			setPkp(pkp - cost, false);
		}
		// casting clears the overload lockout instantly, but eating stays locked for a few more seconds
		if (overloadActive)
		{
			overloadActive = false;
			overloadFoodUnlockMs = System.currentTimeMillis() + OVERLOAD_FOOD_MS;
		}
		consumeImmunityAttacks();
	}

	private void addPkp(double delta)
	{
		setPkp(pkp + delta, false);
	}

	private void addPkpOver(double delta)
	{
		setPkp(pkp + delta, true);
	}

	/** True while a special attack's effect is being paid (not a consumable's). */
	private boolean specActivation;

	/**
	 * Special-attack (and consumable) PKP change. May overcharge (above max), never triggers the
	 * depletion punishment. One exception for special attacks: a GAIN while already overcharged
	 * overloads - the overcharge burns off back to the maximum, exactly as a melee overload does.
	 */
	private void addPkpSpec(double delta, boolean canOvercharge)
	{
		if (specActivation && delta > 0 && pkp > effectiveMax() && !isPkpFrozen() && !overloadImmune())
		{
			overloadActive = true;
			setPkp(effectiveMax(), false);
			lastCombatMs = System.currentTimeMillis();
			return;
		}
		setPkp(pkp + delta, canOvercharge, false);
	}

	private void setPkp(double v, boolean allowOver)
	{
		setPkp(v, allowOver, true);
	}

	private void setPkp(double v, boolean allowOver, boolean canDeplete)
	{
		if (isPkpFrozen())
		{
			return;
		}
		// The immunities are directional locks, not just state suppressors: depletion immunity stops PKP
		// falling at all, overload immunity stops it rising at all. A change in the permitted direction
		// still lands normally, so e.g. a depletion-immune player can keep building PKP while spending
		// nothing.
		if (depletionImmune() && v < pkp)
		{
			return;
		}
		if (overloadImmune() && v > pkp)
		{
			return;
		}
		int max = effectiveMax();
		double hi = allowOver ? overchargeCap() : Math.max(max, pkp);
		double lo = -max;
		pkp = Math.max(lo, Math.min(hi, v));
		updateLatches(canDeplete);
	}

	private void updateLatches(boolean canDeplete)
	{
		boolean was = depletedLatch;
		if (canDeplete && pkp < 0)
		{
			depletedLatch = true;
		}
		if (pkp >= effectiveMax())
		{
			depletedLatch = false;
		}
		if (!was && depletedLatch)
		{
		}
	}

	private Integer restoreAmount(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Integer byId = restoreIds.get(itemId);
		return byId != null ? byId : restoreNames.get(itemName(itemId));
	}

	private Integer weaponAdvAmount(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Integer byId = weaponAdvIds.get(itemId);
		return byId != null ? byId : weaponAdvNames.get(itemName(itemId));
	}

	private Integer specDeltaFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Integer byId = specDeltaIds.get(itemId);
		return byId != null ? byId : specDeltaNames.get(itemName(itemId));
	}

	private SpecEffect specEffectFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		SpecEffect byId = specEffectIds.get(itemId);
		return byId != null ? byId : specEffectNames.get(itemName(itemId));
	}

	private Integer consumableDeltaFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Integer byId = consumableDeltaIds.get(itemId);
		return byId != null ? byId : consumableDeltaNames.get(itemName(itemId));
	}

	private SpecEffect consumableEffectFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		SpecEffect byId = consumableEffectIds.get(itemId);
		return byId != null ? byId : consumableEffectNames.get(itemName(itemId));
	}

	private void activateSpecEffect(SpecEffect fx, long now)
	{
		activateSpecEffect(fx, now, null, false, -1);
	}

	/**
	 * Fire a special attack's or a consumable's whole effect, in the one order that makes sense.
	 *
	 * <p>The PKP movements happen in a fixed sequence, and the sequence is the point of this method:
	 *
	 * <ol>
	 *   <li><b>arm</b> the freeze and immunity timers - no PKP moves;</li>
	 *   <li><b>clear</b> an active overload or depletion, which RESETS the pool to 50% of max;</li>
	 *   <li><b>pay</b> the flat delta;</li>
	 *   <li><b>roll</b> the gamble.</li>
	 * </ol>
	 *
	 * <p>Steps 3 and 4 come after step 2 because the clear is an assignment, not an adjustment: it
	 * puts the pool AT half, discarding whatever was there. A weapon written {@code CO+CD} with a
	 * +50 delta used to pay the 50 and then have it thrown away by the reset, so the delta was
	 * invisible on exactly the weapons that most wanted it. Now the reset is the floor the gain is
	 * paid onto.
	 *
	 * @param pkpDelta       the flat PKP change (already poison-adjusted, hence a double), or
	 *                       null when there is none
	 * @param canOvercharge  whether that flat change may push the pool past its cap
	 * @param sourceItemId   the weapon or consumable this came from, or -1 when unknown; it decides
	 *                       whether a freeze is the timed kind or the sustained kind
	 */
	private void activateSpecEffect(SpecEffect fx, long now, Double pkpDelta, boolean canOvercharge,
		int sourceItemId)
	{
		if (fx == null)
		{
			// No effects, but a delta still has to land.
			if (pkpDelta != null)
			{
				double beforeOnly = pkp;
				addPkpSpec(pkpDelta, canOvercharge);
				notePkpChange(pkp - beforeOnly);
			}
			return;
		}
		// A freeze is armed here but does not start ticking until the next attack or cast - see
		// startPendingFreeze(). That first action is itself frozen, so its PKP gain or loss is cancelled.
		// A freeze on a weapon with a sustained-freeze pairing is an ordinary timed freeze with an
		// extra way to die: the authored F number is its CEILING, and breaking the pairing ends it
		// early. So the duration machinery below is shared with every other freeze, and the pairing
		// only adds the early-out - which is why F200s on a god staff means "200 seconds at most,
		// and only while you keep using it properly".
		boolean wantsFreeze = fx.freezeSeconds > 0 || fx.freezeAttacks > 0;
		if (fx.freezeSeconds > 0)
		{
			pendingFreezeSeconds = Math.max(pendingFreezeSeconds, fx.freezeSeconds);
		}
		if (fx.freezeAttacks > 0)
		{
			pendingFreezeAttacks = Math.max(pendingFreezeAttacks, fx.freezeAttacks);
		}
		if (wantsFreeze && isSustainWeapon(sourceItemId))
		{
			pendingSustainWeaponId = sourceItemId;
		}
		if (fx.overloadImmuneSeconds > 0)
		{
			overloadImmuneUntilMs = Math.max(overloadImmuneUntilMs, now + fx.overloadImmuneSeconds * 1000L);
		}
		if (fx.overloadImmuneAttacks > 0)
		{
			overloadImmuneAttacks = Math.max(overloadImmuneAttacks, fx.overloadImmuneAttacks);
		}
		if (fx.depletionImmuneSeconds > 0)
		{
			depletionImmuneUntilMs = Math.max(depletionImmuneUntilMs, now + fx.depletionImmuneSeconds * 1000L);
		}
		if (fx.depletionImmuneAttacks > 0)
		{
			depletionImmuneAttacks = Math.max(depletionImmuneAttacks, fx.depletionImmuneAttacks);
		}
		// clear an active overload and/or depletion, resetting PKP to 50% of max
		boolean doClearO = fx.clearOverload && overloadActive;
		boolean doClearD = fx.clearDepletion && depletedLatch;
		if (doClearO || doClearD)
		{
			double before = pkp;
			if (doClearO)
			{
				overloadActive = false;
				overloadFoodUnlockMs = 0L;
			}
			if (doClearD)
			{
				depletedLatch = false;
			}
			setPkp(effectiveMax() * 0.5, false);
			notePkpChange(pkp - before);
		}

		// ---- and only now does PKP get paid, onto whatever the clear left behind ----

		if (pkpDelta != null)
		{
			double beforeDelta = pkp;
			addPkpSpec(pkpDelta, canOvercharge);
			notePkpChange(pkp - beforeDelta);
		}

		// The gamble, resolved once per activation and last of all. A win may overcharge, exactly
		// like the flat delta above it; a loss spends from the pool without latching DEPLETED,
		// because that punishment belongs to ordinary attacks and casts, not to a special attack.
		if (fx.hasGamble())
		{
			boolean win = rng.nextBoolean();
			int amount = win ? fx.randomGain : -fx.randomLoss;
			double beforeRoll = pkp;
			// a win past the maximum is an overcharge, always - never clamped away
			addPkpSpec(amount, win);
			notePkpChange(pkp - beforeRoll);
		}
	}

	/**
	 * Starts a freeze that was armed by a spec or consumable. Called at the very top of every attack and
	 * cast, before any PKP is added or spent, so the action that starts the freeze is the first one frozen
	 * and its own gain or loss is cancelled.
	 */
	private void startPendingFreeze()
	{
		if (pendingFreezeSeconds <= 0 && pendingFreezeAttacks <= 0 && pendingSustainWeaponId <= 0)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (pendingFreezeSeconds > 0)
		{
			freezeUntilMs = Math.max(freezeUntilMs, now + pendingFreezeSeconds * 1000L);
			pendingFreezeSeconds = 0;
		}
		if (pendingFreezeAttacks > 0)
		{
			freezeAttacks = Math.max(freezeAttacks, pendingFreezeAttacks);
			pendingFreezeAttacks = 0;
		}
		if (pendingSustainWeaponId > 0)
		{
			sustainFreezeWeaponId = pendingSustainWeaponId;
			sustainFreezeActionMs = now;
			pendingSustainWeaponId = -1;
		}
	}

	/** True while a freeze is armed but has not been triggered by an attack or cast yet. */
	private boolean freezePending()
	{
		return (pendingFreezeSeconds > 0 || pendingFreezeAttacks > 0 || pendingSustainWeaponId > 0);
	}

	boolean isPkpFrozen()
	{
		return (System.currentTimeMillis() < freezeUntilMs || freezeAttacks > 0);
	}

	// ============================================================ sustained freeze

	/** The spells paired with a weapon, or null when the weapon has no sustained freeze at all. */
	private Set<String> sustainSpellsFor(int itemId)
	{
		if (itemId <= 0)
		{
			return null;
		}
		Set<String> byId = sustainSpellsById.get(itemId);
		if (byId != null)
		{
			return byId;
		}
		return sustainSpellsByName.get(itemName(itemId));
	}

	private boolean isSustainWeapon(int itemId)
	{
		return sustainSpellsFor(itemId) != null;
	}

	/**
	 * Is the pairing still being kept up?
	 *
	 * <p>Pure: it only reads. The lapse is applied in {@link #onGameTick}, because this is called
	 * from the overlays' render path and a query that mutates state from there is a bug waiting to
	 * happen - the bar would clear the freeze at whatever frame rate the client happens to run.
	 *
	 * <p>This is NOT the same question as "is the pool frozen": the freeze has its own authored
	 * ceiling and can run out while the pairing is still being honoured.
	 */
	private boolean sustainHeld()
	{
		return sustainFreezeWeaponId > 0
			&& System.currentTimeMillis() - sustainFreezeActionMs < sustainIdleMs();
	}

	/** How long a sustained freeze survives with no sustaining action: the authored combat grace. */
	private long sustainIdleMs()
	{
		return Math.max(TICK_MS_L, rules.oocThresholdMs());
	}

	/**
	 * Break a sustained freeze: forget the pairing AND end the freeze it was holding up.
	 *
	 * <p>The freeze is cleared outright rather than reduced, because the two halves are not
	 * separable once merged - a second freeze armed by something else while this one was running has
	 * already been folded into the same {@code freezeUntilMs}. In practice that only costs the
	 * player a freeze they ended themselves by choosing a different action.
	 */
	private void endSustainFreeze(String why)
	{
		if (sustainFreezeWeaponId <= 0)
		{
			return;
		}
		sustainFreezeWeaponId = -1;
		sustainFreezeActionMs = 0L;
		freezeUntilMs = 0L;
		freezeAttacks = 0;
	}

	/**
	 * An attack or a cast happened: renew the sustained freeze, or break it.
	 *
	 * <p>The pairing is the whole rule. A sustaining action is this same weapon's own melee swing, or
	 * a cast of one of the spells named beside it in the codex. Anything else - a different spell, a
	 * ranged shot, a swing from another weapon - ends it on the spot, because the weapon and its
	 * spell are what is holding the freeze up.
	 *
	 * @param isCast     true for a spell, false for a melee or ranged attack
	 * @param spellName  the spell cast, when there is one
	 * @param weaponId   the weapon the action came from
	 */
	private void noteSustainAction(boolean isCast, String spellName, int weaponId)
	{
		if (sustainFreezeWeaponId <= 0)
		{
			return;
		}
		if (weaponId != sustainFreezeWeaponId)
		{
			endSustainFreeze("acted with a different weapon");
			return;
		}
		if (!isCast)
		{
			// This weapon's own attack. A ranged shot can only come from a different weapon, so
			// reaching here with the staff still in hand means it was a melee swing.
			sustainFreezeActionMs = System.currentTimeMillis();
			return;
		}
		Set<String> paired = sustainSpellsFor(weaponId);
		String spell = spellName == null ? "" : spellName.trim().toLowerCase(Locale.ROOT);
		if (paired != null && paired.contains(spell))
		{
			sustainFreezeActionMs = System.currentTimeMillis();
			return;
		}
		endSustainFreeze("cast '" + spell + "', which this weapon is not paired with");
	}

	private boolean overloadImmune()
	{
		return System.currentTimeMillis() < overloadImmuneUntilMs || overloadImmuneAttacks > 0;
	}

	private boolean depletionImmune()
	{
		return System.currentTimeMillis() < depletionImmuneUntilMs || depletionImmuneAttacks > 0;
	}

	/** Each melee hit / cast spends one attack from any active attack-count immunity or freeze. */
	private void consumeImmunityAttacks()
	{
		if (freezeAttacks > 0)
		{
			freezeAttacks--;
		}
		if (overloadImmuneAttacks > 0)
		{
			overloadImmuneAttacks--;
		}
		if (depletionImmuneAttacks > 0)
		{
			depletionImmuneAttacks--;
		}
	}

	/** Parsed optional spec effects for one weapon. */
	private static final class SpecEffect
	{
		int freezeSeconds;
		int freezeAttacks;
		int overloadImmuneSeconds;
		int overloadImmuneAttacks;
		int depletionImmuneSeconds;
		int depletionImmuneAttacks;
		boolean clearOverload;
		boolean clearDepletion;
		/** The gamble: on a win the pool gains this much. Zero means there is no gamble. */
		int randomGain;
		/** ...and on a loss it spends this much. Set from the same token as {@link #randomGain}. */
		int randomLoss;

		boolean hasGamble()
		{
			return randomGain > 0 || randomLoss > 0;
		}

		boolean any()
		{
			return freezeSeconds > 0 || freezeAttacks > 0 || overloadImmuneSeconds > 0 || overloadImmuneAttacks > 0
				|| depletionImmuneSeconds > 0 || depletionImmuneAttacks > 0
				|| clearOverload || clearDepletion || hasGamble();
		}
	}

	private boolean isOverchargeSource(int itemId)
	{
		return itemId > 0 && (overchargeIds.contains(itemId) || overchargeNames.contains(itemName(itemId)));
	}

	private String itemName(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp == null ? "" : comp.getName().toLowerCase(Locale.ROOT);
	}

	private void addCandidate(List<String> out, String raw)
	{
		if (raw == null)
		{
			return;
		}
		String clean = raw.replaceAll("<[^>]*>", "").trim();
		if (clean.isEmpty())
		{
			return;
		}
		out.add(clean);
		for (String part : clean.split("->"))
		{
			String p = part.trim();
			if (!p.isEmpty())
			{
				out.add(p);
			}
		}
	}

	// ============================================================ config parsing

	private void rebuildCaches()
	{
		parseList(rules.magicGearExceptions(), magicExceptionNames, magicExceptionIds);
		parseList(rules.magicGearForceBlock(), magicForceNames, magicForceIds);
		parseQuestItems(rules.questItems());
		parseList(rules.blockedWeapons(), blockedWeaponNames, blockedWeaponIds);
		parseList(rules.godBlockedWeapons(), godBlockedWeaponNames, godBlockedWeaponIds);
		parseList(rules.weaponExceptions(), weaponExceptionNames, weaponExceptionIds);
		parseList(rules.rangedWeapons(), rangedNames, rangedIds);
		parsePairs(rules.pkpRangedWeapons(), rangedAdvNames, rangedAdvIds);
		parseList(rules.shields(), shieldNames, shieldIds);
		parsePairs(rules.shieldBlockPkp(), shieldPkpNames, shieldPkpIds);
		parseList(rules.noBlockPkpItems(), noBlockPkpNames, noBlockPkpIds);
		parsePairs(rules.blockPenaltyItems(), blockPenaltyNames, blockPenaltyIds);
		parseIntSet(rules.rangedAnimationIds(), rangedAnimations);
		parseList(rules.pacifistItems(), pacifistNames, pacifistIds);
		parseList(rules.overchargeItems(), overchargeNames, overchargeIds);

		parseIntSet(rules.meleeAnimationIds(), meleeAnimations);
		parseIntSet(rules.selfCastAnimationIds(), selfCastAnimations);
		parseIntSet(rules.targetCastAnimationIds(), targetCastAnimations);
		parseIntSet(rules.curseCastAnimationIds(), curseCastAnimations);
		parseIntSet(rules.sitAnimationIds(), sitAnimations);
		parseIntSet(rules.blockAnimationIds(), blockAnimations);

		parsePairs(rules.pkpRestoreItems(), restoreNames, restoreIds);
		parseList(rules.consumableExceptions(), consumableExceptionNames, consumableExceptionIds);
		parseList(rules.restrictionExemptItems(), restrictionExemptNames, restrictionExemptIds);
		parseNameSet(rules.teleportActions(), teleportActions);
		parseNameSet(rules.cannonSetupActions(), cannonSetupActions);
		cannonKeyword = rules.cannonItemKeyword();
		parsePairs(rules.pkpWeapons(), weaponAdvNames, weaponAdvIds);
		parseSpecWeapons(rules.specAttackWeapons());
		parseConsumables(rules.consumableEffects());
		parseSustainedFreeze(rules.sustainedFreeze());
		parsePairs(rules.maxPkpItems(), maxPkpItemNames, maxPkpItemIds);
		// the sheet just changed, so what is worn may be worth a different amount now
		recomputeWornMaxPkpBonus();

		parseNameSet(rules.blockedGodSpells(), blockedGodSpells);
		parseNameSet(rules.curseSpells(), curseSpells);
	}

	/**
	 * Splits the quest-item entries into the two caches the lookup uses: the unconditional ones, and
	 * the ones gated on a quest being in progress.
	 *
	 * <p>Entries are keyed by item id when the codex gives a number and by lower-cased name
	 * otherwise, the same way {@link #parseList} does, so both spellings of an entry work. An entry
	 * whose quests are all unknown to this client is kept in the gated map with an empty list, which
	 * means "never allowed" rather than "always allowed" - a typo should not silently hand out a
	 * permanent exemption.
	 */
	private void parseQuestItems(List<ContentPack.QuestItem> entries)
	{
		questItemNames.clear();
		questItemIds.clear();
		questGatedNames.clear();
		questGatedIds.clear();

		for (ContentPack.QuestItem entry : entries)
		{
			if (entry == null || entry.item == null || entry.item.trim().isEmpty())
			{
				continue;
			}
			final String token = entry.item.trim();
			Integer id = null;
			try
			{
				id = Integer.valueOf(token);
			}
			catch (NumberFormatException e)
			{
				// a name, not an id
			}

			if (entry.unconditional())
			{
				if (id != null)
				{
					questItemIds.add(id);
				}
				else
				{
					questItemNames.add(token.toLowerCase(Locale.ROOT));
				}
				continue;
			}

			List<Quest> resolved = new ArrayList<>();
			for (String questName : entry.quests)
			{
				Quest q = questByName(questName);
				if (q != null)
				{
					resolved.add(q);
				}
				else if (questName != null && !questName.trim().isEmpty()
					&& unknownQuests.add(questName.trim().toLowerCase(Locale.ROOT)))
				{
					log.warn("[BATTLE-MAGE] Quest item '{}' names a quest this client does not know: {}",
						token, questName.trim());
				}
			}
			if (id != null)
			{
				questGatedIds.put(id, resolved);
			}
			else
			{
				questGatedNames.put(token.toLowerCase(Locale.ROOT), resolved);
			}
		}
		refreshQuestProgress();
	}

	private void parseList(String raw, Set<String> names, Set<Integer> ids)
	{
		names.clear();
		ids.clear();
		for (String token : split(raw))
		{
			try
			{
				ids.add(Integer.parseInt(token));
			}
			catch (NumberFormatException e)
			{
				names.add(token.toLowerCase(Locale.ROOT));
			}
		}
	}

	/** Parses "name-or-id:delta[:effects]" entries into the spec-weapon delta and effect caches. */
	private void parseSpecWeapons(String raw)
	{
		parseEffectSpec(raw, specDeltaIds, specDeltaNames, specEffectIds, specEffectNames);
	}

	/** Parses "name-or-id:delta[:effects]" entries into the consumable delta and effect caches. */
	private void parseConsumables(String raw)
	{
		parseEffectSpec(raw, consumableDeltaIds, consumableDeltaNames, consumableEffectIds, consumableEffectNames);
	}

	/**
	 * Parses "weapon-or-id[:spell+spell]" entries into the sustained-freeze caches.
	 *
	 * <p>A weapon with no spells listed still sustains on its own melee attack, so an empty spell set
	 * is meaningful rather than a parse failure - it means "melee only".
	 */
	private void parseSustainedFreeze(String raw)
	{
		sustainSpellsByName.clear();
		sustainSpellsById.clear();
		for (String token : split(raw))
		{
			String[] parts = token.split(":", 2);
			String key = parts[0].trim();
			if (key.isEmpty())
			{
				continue;
			}
			Set<String> spells = new HashSet<>();
			if (parts.length > 1)
			{
				for (String sp : parts[1].split("\\+"))
				{
					String t = sp.trim();
					if (!t.isEmpty())
					{
						spells.add(t.toLowerCase(Locale.ROOT));
					}
				}
			}
			try
			{
				sustainSpellsById.put(Integer.valueOf(key.trim()), spells);
			}
			catch (NumberFormatException e)
			{
				sustainSpellsByName.put(key.toLowerCase(Locale.ROOT), spells);
			}
		}
	}

	/** Shared parser for "name-or-id:delta[:effects]" entries (used by spec weapons and consumables). */
	private void parseEffectSpec(String raw, Map<Integer, Integer> deltaIds, Map<String, Integer> deltaNames,
		Map<Integer, SpecEffect> effectIds, Map<String, SpecEffect> effectNames)
	{
		deltaNames.clear();
		deltaIds.clear();
		effectNames.clear();
		effectIds.clear();
		for (String token : split(raw))
		{
			String[] parts = token.split(":", 3);
			if (parts.length < 2)
			{
				continue;
			}
			String key = parts[0].trim();
			if (key.isEmpty())
			{
				continue;
			}
			int delta;
			try
			{
				delta = Integer.parseInt(parts[1].trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			SpecEffect fx = parts.length >= 3 ? parseSpecEffect(parts[2].trim()) : null;
			Integer id = null;
			try
			{
				id = Integer.parseInt(key);
			}
			catch (NumberFormatException ignored)
			{
				// it's a name
			}
			if (id != null)
			{
				deltaIds.put(id, delta);
				if (fx != null && fx.any())
				{
					effectIds.put(id, fx);
				}
			}
			else
			{
				String lower = key.toLowerCase(Locale.ROOT);
				deltaNames.put(lower, delta);
				if (fx != null && fx.any())
				{
					effectNames.put(lower, fx);
				}
			}
		}
	}

	/** Parses effect codes like "F5", "O3a", "D10s" joined with '+'. */
	private SpecEffect parseSpecEffect(String raw)
	{
		SpecEffect fx = new SpecEffect();
		if (raw == null || raw.isEmpty())
		{
			return fx;
		}
		for (String codeRaw : raw.split("\\+"))
		{
			String code = codeRaw.trim();
			if (code.length() < 2)
			{
				continue;
			}
			char type = Character.toUpperCase(code.charAt(0));
			String rest = code.substring(1).trim();

			// clear tokens: CO clears an active overload, CD clears an active depletion (both reset to 50%)
			String upper = code.toUpperCase(Locale.ROOT);
			if (upper.equals("CO"))
			{
				fx.clearOverload = true;
				continue;
			}
			if (upper.equals("CD"))
			{
				fx.clearDepletion = true;
				continue;
			}

			// The gamble: R<amount> is a coin flip for plus-or-minus that amount, R<gain>/<loss> an
			// uneven one. Parsed here, ahead of the a/s suffix handling below, because its payload is
			// one or two plain numbers and a trailing 's' would otherwise be eaten as "seconds".
			//
			// Both halves are magnitudes, so they are written unsigned: R50/25 is "+50 or -25", never
			// "-50 or 25". A half that will not parse, or is not positive, makes the whole token
			// nothing rather than half a gamble - a typo must not leave a weapon that only ever loses.
			if (type == 'R')
			{
				int slash = rest.indexOf('/');
				String gainPart = slash < 0 ? rest : rest.substring(0, slash);
				String lossPart = slash < 0 ? rest : rest.substring(slash + 1);
				int gain;
				int loss;
				try
				{
					gain = Integer.parseInt(gainPart.trim());
					loss = Integer.parseInt(lossPart.trim());
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				// The GAIN must be positive: a gamble with nothing to win is a weapon that only ever
				// loses, which is what a typo looks like, so it voids the whole token. A zero LOSS is
				// a real thing to want, though - "R33/0" is a free gamble, +33 or nothing - so zero is
				// allowed on that side alone.
				if (gain <= 0 || loss < 0)
				{
					continue;
				}
				// Two R codes on one weapon take the bigger of each side rather than stacking, the
				// same way the freeze and immunity codes do.
				fx.randomGain = Math.max(fx.randomGain, gain);
				fx.randomLoss = Math.max(fx.randomLoss, loss);
				continue;
			}


			boolean attacks = rest.toLowerCase(Locale.ROOT).endsWith("a");
			boolean secs = rest.toLowerCase(Locale.ROOT).endsWith("s");
			String num = (attacks || secs) ? rest.substring(0, rest.length() - 1) : rest;
			int n;
			try
			{
				n = Integer.parseInt(num.trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			if (n <= 0)
			{
				continue;
			}
			switch (type)
			{
				case 'F':
					if (attacks)
					{
						fx.freezeAttacks = n;
					}
					else
					{
						fx.freezeSeconds = n;
					}
					break;
				case 'O':
					if (attacks)
					{
						fx.overloadImmuneAttacks = n;
					}
					else
					{
						fx.overloadImmuneSeconds = n;
					}
					break;
				case 'D':
					if (attacks)
					{
						fx.depletionImmuneAttacks = n;
					}
					else
					{
						fx.depletionImmuneSeconds = n;
					}
					break;
				default:
					break;
			}
		}
		return fx;
	}

	private void parseNameSet(String raw, Set<String> names)
	{
		names.clear();
		for (String token : split(raw))
		{
			names.add(token.toLowerCase(Locale.ROOT));
		}
	}

	private void parseIntSet(String raw, Set<Integer> out)
	{
		out.clear();
		for (String token : split(raw))
		{
			try
			{
				out.add(Integer.parseInt(token));
			}
			catch (NumberFormatException ignored)
			{
				// skip
			}
		}
	}

	private void parsePairs(String raw, Map<String, Integer> names, Map<Integer, Integer> ids)
	{
		names.clear();
		ids.clear();
		for (String token : split(raw))
		{
			int colon = token.lastIndexOf(':');
			if (colon < 0)
			{
				continue;
			}
			String key = token.substring(0, colon).trim();
			String valStr = token.substring(colon + 1).trim();
			if (key.isEmpty())
			{
				continue;
			}
			int amount;
			try
			{
				amount = Integer.parseInt(valStr);
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			try
			{
				ids.put(Integer.parseInt(key), amount);
			}
			catch (NumberFormatException e)
			{
				names.put(key.toLowerCase(Locale.ROOT), amount);
			}
		}
	}

	private static List<String> split(String raw)
	{
		List<String> out = new ArrayList<>();
		if (raw == null)
		{
			return out;
		}
		for (String t : raw.split(","))
		{
			String s = t.trim();
			if (!s.isEmpty())
			{
				out.add(s);
			}
		}
		return out;
	}
}
