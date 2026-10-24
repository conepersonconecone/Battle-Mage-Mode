package com.battlemage;

import com.battlemage.ChatDialogue.Line;
import com.battlemage.ChatDialogue.Mood;
import com.battlemage.ChatDialogue.Option;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * The Wise Old Man of Draynor Village, keeper of the oath.
 *
 * <ul>
 *   <li><b>The ritual.</b> With no god sworn, ringing the Cowbell amulet (charged or not, carried or
 *       worn) from exactly two tiles directly south of him starts his introduction. Ring three
 *       times from one tile or three tiles south and he tells you which way to step instead. It ends in a
 *       choice: <i>Begin Battle-Mage Mode</i> opens the faction pop-up, <i>Reject</i> gets a telling
 *       off. This is the only way to the faction pop-up.</li>
 *   <li><b>Change of heart.</b> Ringing from the same spot while sworn asks whether to denounce;
 *       yes plays a short denouncing between him and the player, then leaves the faction.</li>
 *   <li><b>BMM.</b> Once a god is sworn he gains a <i>BMM</i> right-click option, which opens a
 *       question-and-answer conversation explaining the mode. Sworn to a god, it also offers the
 *       PKP system without one: after a warning and a yes, he denounces the player's god and then
 *       casts Vengeance Other on them, leaving them faction-less.</li>
 * </ul>
 *
 * <p>The ring itself is left alone - the game still plays it - the conversation simply starts
 * alongside it. Registered on the event bus by {@link BattleMagePlugin} for as long as the plugin
 * runs.
 */
@Singleton
public class WiseOldMan
{
	static final String NAME = "Wise Old Man";

	/** The Cowbell amulet: uncharged, charged, and the bank placeholder (harmless to include). */
	private static final int[] COWBELL_IDS = {33103, 33104, 33105};
	private static final String COWBELL_NAME = "cowbell amulet";

	static boolean isCowbellId(int itemId)
	{
		for (int id : COWBELL_IDS)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		return false;
	}
	private static final String RING = "Ring";
	private static final String BMM = "BMM";
	/** The player must stand this many tiles directly south of him. */
	private static final int TILES_SOUTH = 2;
	/** Rings from a tile too close or too far that it takes before he points out the mistake. */
	private static final int MISPLACED_RINGS = 3;

	private final Client client;
	private final ChatDialogue dialogue;
	private final Oath oath;
	private final Rules rules;

	/** Rings so far from one tile too close or too far; reset once he says something. */
	private int misplacedRings;

	/** Opens the faction pop-up. Set by the plugin. */
	private Runnable onBegin = () ->
	{
	};
	/** Plays the denouncing between him and the player, then denounces. Set by the plugin. */
	private java.util.function.Consumer<NPC> onDenounce = npc ->
	{
	};

	/** Denounces the player's god, then makes them faction-less. Set by the plugin. */
	private java.util.function.Consumer<NPC> onFactionless = npc ->
	{
	};

	@Inject
	WiseOldMan(Client client, ChatDialogue dialogue, Oath oath, Rules rules)
	{
		this.client = client;
		this.dialogue = dialogue;
		this.oath = oath;
		this.rules = rules;
	}

	void setOnBegin(Runnable onBegin)
	{
		this.onBegin = onBegin == null ? () ->
		{
		} : onBegin;
	}

	void setOnDenounce(java.util.function.Consumer<NPC> onDenounce)
	{
		this.onDenounce = onDenounce == null ? npc ->
		{
		} : onDenounce;
	}

	void setOnFactionless(java.util.function.Consumer<NPC> onFactionless)
	{
		this.onFactionless = onFactionless == null ? npc ->
		{
		} : onFactionless;
	}

	// ------------------------------------------------------------------ the ritual

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuEntry entry = event.getMenuEntry();
		if (!isRingingCowbell(entry))
		{
			return;
		}
		if (oath.isSworn())
		{
			// Already sworn: ringing from the same spot asks whether you've had a change of heart.
			Player sworn = client.getLocalPlayer();
			NPC man = sworn == null ? null : wiseOldManNorthOf(sworn.getWorldLocation(), TILES_SOUTH);
			if (man != null)
			{
				changeOfHeart(man);
			}
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		WorldPoint at = me.getWorldLocation();
		NPC man = wiseOldManNorthOf(at, TILES_SOUTH);
		if (man != null)
		{
			misplacedRings = 0;
			introduce(man.getId());
			return;
		}
		// Close, but not quite: one tile or three tiles south of him. On the third such ring he
		// tells the player which way to step.
		NPC tooClose = wiseOldManNorthOf(at, TILES_SOUTH - 1);
		NPC tooFar = tooClose == null ? wiseOldManNorthOf(at, TILES_SOUTH + 1) : null;
		if (tooClose == null && tooFar == null)
		{
			return;
		}
		if (++misplacedRings < MISPLACED_RINGS)
		{
			return;
		}
		misplacedRings = 0;
		if (tooClose != null)
		{
			dialogue.play(tooClose.getId(), NAME, Line.npc(Mood.LAUGH,
				"Whoa there, you're a bit close! Take a step back... Nyeheheheaaehee!"), null);
		}
		else
		{
			dialogue.play(tooFar.getId(), NAME, Line.npc(Mood.LAUGH,
				"Eh? You're a bit far! Take a step forward... Nyeheheheaaehee!"), null);
		}
	}

	private boolean isRingingCowbell(MenuEntry entry)
	{
		String option = entry.getOption();
		if (option == null || !RING.equalsIgnoreCase(Text.removeTags(option).trim()))
		{
			return false;
		}
		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			Widget w = entry.getWidget();
			if (w != null)
			{
				itemId = w.getItemId();
			}
		}
		for (int id : COWBELL_IDS)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		// Worn items don't always carry their id on the menu entry; the name always comes along.
		String target = entry.getTarget();
		return target != null && Text.removeTags(target).toLowerCase(Locale.ROOT).contains(COWBELL_NAME);
	}

	/** The Wise Old Man, if he stands exactly this many tiles directly north of {@code at}; otherwise null. */
	private NPC wiseOldManNorthOf(WorldPoint at, int tiles)
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || !NAME.equalsIgnoreCase(npc.getName()))
			{
				continue;
			}
			WorldPoint him = npc.getWorldLocation();
			if (him.getPlane() == at.getPlane() && him.getX() == at.getX()
				&& him.getY() - at.getY() == tiles)
			{
				return npc;
			}
		}
		return null;
	}

	private void introduce(int npcId)
	{
		dialogue.play(npcId, NAME, Arrays.asList(
			Line.npc(Mood.HAPPY, "You must be one of those travelers! I saw the Wizards talking about several from across the Bridge!"),
			Line.npc(Mood.NEUTRAL, "There's a great deal of things you've yet to experience!"),
			Line.npc(Mood.NEUTRAL, "Try to be quick on your feet..."),
			Line.npc(Mood.LAUGH, "...And don't forget to pay your tithes! Nyehehehhee"),
			Line.player(Mood.CONFUSED, "...Tithes?")
		), () -> dialogue.menu("Select an Option", Arrays.asList(
			new Option("Begin Battle-Mage Mode", () ->
			{
				dialogue.close();
				onBegin.run();
			}),
			new Option("Reject", () -> dialogue.play(npcId, NAME,
				Line.npc(Mood.ANGRY, "Watch who you're talking to!"), null))
		)));
	}

	/** Sworn and ringing in front of him: he offers to release the player from their oath. */
	private void changeOfHeart(NPC man)
	{
		final int npcId = man.getId();
		dialogue.play(npcId, NAME, Line.npc(Mood.CONFUSED, "Eh...? What's that? Change of Heart???"),
			() -> dialogue.menu("Select an Option", Arrays.asList(
				new Option("Denounce faction", () ->
				{
					dialogue.close();
					onDenounce.accept(man);
				}),
				new Option("Nevermind", () -> dialogue.play(npcId, NAME,
					Line.npc(Mood.HAPPY, "Spend some time reading from my shelves!"), null))
			)));
	}

	// ------------------------------------------------------------------ BMM

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!oath.isSworn() || !"Talk-to".equalsIgnoreCase(event.getOption()))
		{
			return;
		}
		NPC npc = event.getMenuEntry().getNpc();
		if (npc == null || !NAME.equalsIgnoreCase(npc.getName()))
		{
			return;
		}
		final int npcId = npc.getId();
		// Just under Talk-to, so his normal left-click is untouched.
		client.getMenu().createMenuEntry(-2)
			.setOption(BMM)
			.setTarget(event.getTarget())
			.setIdentifier(event.getIdentifier())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> ask(npc));
	}

	/** The NPC the current conversation is with, for the ceremony at the end of the faction-less offer. */
	private NPC talkingTo;

	private void ask(NPC npc)
	{
		talkingTo = npc;
		int npcId = npc.getId();
		dialogue.play(npcId, NAME, Line.npc(Mood.QUESTION, "What would you like to know?"), () -> questions(npcId, 0));
	}

	/** The questions, two pages of them; each answer comes back to the page it was asked from. */
	private void questions(int npcId, int page)
	{
		Runnable here = () -> questions(npcId, page);
		Faction f = oath.getFaction();
		boolean god = f != null && f.isGod();
		List<Option> options = page == 2
			? Arrays.asList(
				new Option("What if I have ideas to contribute?", () -> answer(npcId, ideas(), here)),
				new Option("That's all, thanks.", dialogue::close))
			: page == 1 && god
			? Arrays.asList(
				new Option("How does blocking work?", () -> answer(npcId, blocking(), here)),
				new Option("What about special attacks and food?", () -> answer(npcId, specsAndFood(), here)),
				new Option("How do I change my god faction?", () -> answer(npcId, changeGod(), here)),
				new Option("Can I try the PKP System without a faction?", () -> withoutFaction(npcId, here)),
				new Option("More questions...", () -> questions(npcId, 2)))
			: page == 0
			? Arrays.asList(
				new Option("What is PKP?", () -> answer(npcId, whatIsPkp(), here)),
				new Option("How do I earn and spend it?", () -> answer(npcId, earnAndSpend(), here)),
				new Option("What happens if I overdo it?", () -> answer(npcId, overdoIt(), here)),
				new Option("What can I wear and wield?", () -> answer(npcId, gear(), here)),
				new Option("More questions...", () -> questions(npcId, 1)))
			: Arrays.asList(
				new Option("How does blocking work?", () -> answer(npcId, blocking(), here)),
				new Option("What about special attacks and food?", () -> answer(npcId, specsAndFood(), here)),
				new Option("How do I change my god faction?", () -> answer(npcId, changeGod(), here)),
				new Option("That's all, thanks.", dialogue::close),
				new Option("What if I have ideas to contribute?", () -> answer(npcId, ideas(), here)));
		dialogue.menu("Select an Option", options);
	}

	private void answer(int npcId, List<Line> lines, Runnable then)
	{
		dialogue.play(npcId, NAME, lines, then);
	}

	// The answers. Numbers come from the god currently sworn, so they are always that god's rules.

	private List<Line> whatIsPkp()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "What is PKP?"),
			Line.npc(Mood.NEUTRAL, "PKP is the power behind your battle magic. Every combat spell you cast draws on it."),
			Line.npc(Mood.NEUTRAL, "Your bar holds " + rules.maxPkp() + " PKP. It sits on your screen, and you can drag it wherever suits you."),
			Line.npc(Mood.HAPPY, "For the fine print, open the Battle-Mage Mode sidebar and press Rules & equipment."));
	}

	private List<Line> earnAndSpend()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "How do I earn and spend it?"),
			Line.npc(Mood.NEUTRAL, "A combat spell costs " + rules.combatSpellCost() + " PKP, and a curse costs "
				+ rules.curseSpellCost() + ". Strike, Bolt, Blast and Wave spells get cheaper as your Magic rises."),
			Line.npc(Mood.NEUTRAL, "Land a melee hit and you earn " + rules.meleeRestore()
				+ " back. Ranged shots earn PKP too, with the weapons your god allows."),
			Line.npc(Mood.NEUTRAL, "Step out of combat for a while and your bar slowly refills on its own."
				+ (rules.sitDoublesRegen() ? " Sit down and it refills twice as fast!" : "")),
			Line.npc(Mood.HAPPY, "Some food and drinks restore PKP, and some drain it. Hover over an item and its tooltip will tell you."));
	}

	private List<Line> overdoIt()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "What happens if I overdo it?"),
			Line.npc(Mood.SHOCKED, "Earn PKP with a full bar and you'll be Overloaded: no melee, no ranged and no eating until you cast a spell."),
			Line.npc(Mood.NEUTRAL, "Spend more than you have and you'll be Depleted: no drinking until you've built your bar back up to full."),
			Line.npc(Mood.ANGRY, "Keep swinging while Overloaded and you'll hit Critical Overload, which is BIG trouble."),
			Line.npc(Mood.SHOCKED, "No sleeping at the wheel! Chop a Yew if you want to AFK."),
			Line.npc(Mood.NEUTRAL, "...only your Cowbell amulet will save you then."));
	}

	private List<Line> gear()
	{
		if (oath.getFaction() == Faction.FACTIONLESS)
		{
			return Arrays.asList(
				Line.player(Mood.QUESTION, "What can I wear and wield?"),
				Line.npc(Mood.HAPPY, "Without a god, nothing is off limits. Wear and wield whatever you like!"),
				Line.npc(Mood.NEUTRAL, "Your PKP still rises and falls with every swing, shot and spell, all the same."));
		}
		return Arrays.asList(
			Line.player(Mood.QUESTION, "What can I wear and wield?"),
			Line.npc(Mood.NEUTRAL, "Weapons need a magic attack bonus of at least +" + rules.magicAttackMinimum()
				+ ", and armour can go no lower than " + (rules.magicAttackThreshold() + 1) + "."),
			Line.npc(Mood.NEUTRAL, "On top of that, your god blesses some gear and forbids others. Anything you can't use shows a stop symbol, and its Wield option disappears."),
			Line.npc(Mood.NEUTRAL, "Quest gear is always allowed while you still need it for the quest."),
			Line.npc(Mood.HAPPY, "Every item and every god's verdict is listed at the bottom of the rulebook. Open it from the Battle-Mage Mode sidebar!"));
	}

	private List<Line> blocking()
	{
		int block = rules.blockRestore();
		String plain = block > 0 ? "restores " + block + " PKP"
			: block < 0 ? "costs you " + (-block) + " PKP"
			: "does nothing to your PKP";
		return Arrays.asList(
			Line.player(Mood.QUESTION, "How does blocking work?"),
			Line.npc(Mood.NEUTRAL, "Block a hit with a shield or off-hand equipped and your PKP shifts. Some restore it and some drain it."),
			Line.npc(Mood.NEUTRAL, (oath.getFaction() == Faction.FACTIONLESS ? "Without a god" : "Under your god")
				+ ", an ordinary shield's block " + plain + "."),
			Line.npc(Mood.HAPPY, "Blocking never Overloads or Depletes you."));
	}

	private List<Line> specsAndFood()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "What about special attacks and food?"),
			Line.npc(Mood.NEUTRAL, "Many special attacks move your PKP. Some fill it, some drain it, and some freeze your bar in place for a while."),
			Line.npc(Mood.NEUTRAL, "Some protect you from Overload or Depletion. You still earn and spend PKP, you just can't fall into that state."),
			Line.npc(Mood.SHOCKED, "And if you gain that protection while you're already Overloaded or Depleted, it pulls you straight out instead!"),
			Line.npc(Mood.HAPPY, "Hover over a weapon or a consumable in your inventory to see if it has a PKP effect."));
	}

	private List<Line> changeGod()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "How do I change my god faction?"),
			Line.npc(Mood.NEUTRAL, "You can denounce your god at any time from the Battle-Mage Mode sidebar."),
			Line.npc(Mood.NEUTRAL, "Then come back and ring your Cowbell amulet to choose again."));
	}

	/**
	 * Sworn to a god and asking for the PKP system on its own. He warns first; a yes gets the
	 * ceremony - the denouncing, then Vengeance Other - and the player comes out faction-less.
	 */
	private void withoutFaction(int npcId, Runnable back)
	{
		dialogue.play(npcId, NAME, Arrays.asList(
			Line.player(Mood.QUESTION, "Can I try the PKP System without a faction?"),
			Line.npc(Mood.CONFUSED, "Without a god? Hmm... The traveler who dreamt all this up believes the PKP system is best experienced on a fresh account..."),
			Line.npc(Mood.NEUTRAL, "...sworn to a god faction, with all the restrictions that come with it."),
			Line.npc(Mood.QUESTION, "Are you sure?")
		), () -> dialogue.menu("Are you sure?", Arrays.asList(
			new Option("Yes", () -> dialogue.play(npcId, NAME, Arrays.asList(
				Line.player(Mood.HAPPY, "Yes, I'm sure."),
				Line.npc(Mood.LAUGH, "Very well! First, your god must let you go... then I'll give you something to fight with. Hold still! Nyehehe!")
			), () ->
			{
				dialogue.close();
				NPC man = talkingTo;
				if (man != null)
				{
					onFactionless.accept(man);
				}
			})),
			new Option("No", () -> dialogue.play(npcId, NAME, Arrays.asList(
				Line.player(Mood.NEUTRAL, "No, I'll stay with my god."),
				Line.npc(Mood.HAPPY, "A wise choice! Spend some time reading from my shelves.")
			), back))
		)));
	}

	private List<Line> ideas()
	{
		return Arrays.asList(
			Line.player(Mood.QUESTION, "What if I have ideas to contribute?"),
			Line.npc(Mood.HAPPY, "Ideas? Wonderful! The traveler who dreamt all this up loves to hear them."),
			Line.npc(Mood.NEUTRAL, "Press Suggestions in the Battle-Mage Mode sidebar and share your thoughts there."),
			Line.npc(Mood.LAUGH, "And while you're in the sidebar... the tithes button is right there too. Nyehehe!"));
	}
}
