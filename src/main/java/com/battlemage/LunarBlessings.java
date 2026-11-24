package com.battlemage;

import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;

/**
 * Lunar support spells between party members:
 * <ul>
 * <li>Heal Other on a teammate: their overload and depletion are cleared, their PKP goes to 0, and
 * they can't be depleted for their next 2 attacks.</li>
 * <li>Vengeance Other on a teammate: they can't overload for their next 2 attacks.</li>
 * </ul>
 *
 * <p>The caster's client confirms the cast went off (the spell's effect appears on the target, or the
 * caster gains Magic experience) before telling the party; the target's own client applies it.
 * Client thread.
 */
@Singleton
class LunarBlessings
{
	/** Ticks after the click to see the cast land. */
	private static final int CONFIRM_TICKS = 8;

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService party;

	/** Applies a blessing to you: (kind, caster's name). Set by the plugin, which owns the PKP. */
	private volatile BiConsumer<Integer, String> onBlessed = (k, n) -> { };

	/** A cast clicked and not yet confirmed. */
	private int pendingKind;
	private String pendingTarget;
	private int pendingUntilTick;
	private int magicXp = -1;

	@Inject
	LunarBlessings(Client client, ClientThread clientThread, PartyService party)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.party = party;
	}

	void setOnBlessed(BiConsumer<Integer, String> r)
	{
		onBlessed = r == null ? (k, n) -> { } : r;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (e.getMenuAction() != MenuAction.WIDGET_TARGET_ON_PLAYER || !party.isInParty())
		{
			return;
		}
		final String target = Text.removeTags(e.getMenuTarget());
		final int kind = target.startsWith("Heal Other") ? BattleMagePartyBlessing.HEAL
			: target.startsWith("Vengeance Other") ? BattleMagePartyBlessing.VENGEANCE : 0;
		final Player p = e.getMenuEntry().getPlayer();
		if (kind == 0 || p == null || p.getName() == null || memberId(p.getName()) < 0)
		{
			return;
		}
		pendingKind = kind;
		pendingTarget = p.getName();
		pendingUntilTick = client.getTickCount() + CONFIRM_TICKS;
		magicXp = client.getSkillExperience(Skill.MAGIC);
	}

	/** The spell's effect appeared on the target: it went off. */
	@Subscribe
	public void onGraphicChanged(GraphicChanged e)
	{
		if (pendingKind == 0)
		{
			return;
		}
		final Actor a = e.getActor();
		if (!(a instanceof Player) || a.getName() == null || !Text.standardize(a.getName()).equals(Text.standardize(pendingTarget)))
		{
			return;
		}
		final boolean landed = pendingKind == BattleMagePartyBlessing.HEAL
			? a.hasSpotAnim(SpotanimID.QUEST_LUNAR_SPELLBOOK_ENERGY_HEAL_SPOT_ANIM)
			: a.hasSpotAnim(SpotanimID.QUEST_LUNAR_SPELLBOOK_VENGEANCE_OTHER_SPOT_ANIM)
				|| a.hasSpotAnim(SpotanimID.QUEST_LUNAR_SPELLBOOK_VENGEANCE_SPOT_ANIM);
		if (landed)
		{
			confirm();
		}
	}

	/** Magic experience for the cast: it went off. */
	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (pendingKind != 0 && e.getSkill() == Skill.MAGIC && magicXp >= 0 && e.getXp() > magicXp)
		{
			confirm();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (pendingKind != 0 && client.getTickCount() > pendingUntilTick)
		{
			pendingKind = 0;
		}
	}

	private void confirm()
	{
		final long id = memberId(pendingTarget);
		final int kind = pendingKind;
		pendingKind = 0;
		if (id >= 0 && party.isInParty() && party.getLocalMember() != null)
		{
			party.send(new BattleMagePartyBlessing(kind, id));
		}
	}

	/** A blessing was cast in the party; if it was on you, it applies. */
	@Subscribe
	public void onBattleMagePartyBlessing(BattleMagePartyBlessing e)
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || e.t != me.getMemberId() || e.getMemberId() == me.getMemberId())
		{
			return;
		}
		if (e.k != BattleMagePartyBlessing.HEAL && e.k != BattleMagePartyBlessing.VENGEANCE)
		{
			return;
		}
		final PartyMember from = party.getMemberById(e.getMemberId());
		final String name = from == null ? "A party member" : from.getDisplayName();
		clientThread.invoke(() -> onBlessed.accept(e.k, name));
	}

	/** The party member id for a character name, or -1 if they aren't in your party. */
	private long memberId(String name)
	{
		final String want = Text.standardize(name);
		for (PartyMember m : party.getMembers())
		{
			if (m.getDisplayName() != null && Text.standardize(m.getDisplayName()).equals(want))
			{
				return m.getMemberId();
			}
		}
		return -1;
	}
}
