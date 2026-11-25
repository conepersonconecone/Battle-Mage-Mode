package com.battlemage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.battlemage.party.PartyPlayer;
import com.battlemage.party.PrayerSprites;
import com.battlemage.party.Prayers;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.PartyMemberAvatar;
import net.runelite.client.game.ItemManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.PartyData;
import net.runelite.client.party.messages.UserSync;

/**
 * Battle Mage Mode's side of RuneLite's party. Creating, joining and leaving from the side panel
 * drive RuneLite's own party service, so it is the same party the built-in Party plugin (and any
 * other party plugin) sees: join from either place and both show it. The Party plugin itself does
 * not need to be switched on.
 *
 * <p>It also shares your faction and PKP with the party, and keeps what the other Battle Mages in it
 * have shared. An update goes out when something changes (checked once a game tick), again every
 * {@link #HEARTBEAT_TICKS} ticks so a missed message heals itself, and straight away when someone
 * joins. Party messages arrive on the websocket thread, so what is received is kept in a concurrent
 * map that the overlay and side panel read from their own threads.
 *
 * <p>For the full member views (stats, inventory, equipment, prayers, skills) it keeps a
 * {@link PartyPlayer} per member, filled from RuneLite's own party data, plus the few extras Battle
 * Mage Mode sends itself ({@link BattleMagePartyExtras}).
 */
@Singleton
class PartySync
{
	/** Ticks between repeats of an unchanged update: about 30 seconds. */
	private static final int HEARTBEAT_TICKS = 50;
	/** Ticks between side panel refreshes while in a party, for joins, names and logins. */
	private static final int PANEL_TICKS = 3;
	private static final String LAST_KEY = "partyLastPassphrase";
	/** Heard nothing for this long (well past two heartbeats): they have logged out. */
	private static final long OFFLINE_MS = 75_000L;

	/** One party member as last heard from. */
	static final class Mate
	{
		final long memberId;
		final String name;
		final boolean self;
		final boolean loggedIn;
		/** False for a member who isn't playing (or isn't sharing) Battle Mage Mode. */
		final boolean battleMage;
		final Faction faction;
		final int state;
		/** The aura they chose to show under their character. */
		final AuraMode aura;

		Mate(long memberId, String name, boolean self, boolean loggedIn, boolean battleMage,
			Faction faction, int state, AuraMode aura)
		{
			this.aura = aura == null ? AuraMode.NONE : aura;
			this.memberId = memberId;
			this.name = name;
			this.self = self;
			this.loggedIn = loggedIn;
			this.battleMage = battleMage;
			this.faction = faction;
			this.state = state;
		}

		boolean has(int flag)
		{
			return (state & flag) != 0;
		}
	}

	private final PartyService party;
	private final WSClient ws;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final Client client;
	private final PartyAvatar avatar;
	private final DeathSignposts signposts;
	private final Appearance look;
	/** Plays an oath ordination on a party member (set by the plugin, which owns the effects). */
	private volatile java.util.function.BiConsumer<Player, Faction> onOrdain = (p, f) -> { };
	private final Oath oath;
	/** Your party picture needs sending: you joined, someone joined, or you changed it. */
	private volatile boolean avatarDirty = true;

	/** Full member data for the side panel's member views, by member id. */
	private final Map<Long, PartyPlayer> players = new ConcurrentHashMap<>();
	/** Members whose banner's name line needs rebuilding on the next panel refresh. */
	private final java.util.Set<Long> rebuild = ConcurrentHashMap.newKeySet();
	/** Your own prayers' unlocked and usable state, read from the game. */
	private Prayers localPrayers;
	private BattleMagePartyExtras lastExtras;
	private boolean resendExtras;
	/** When you last logged out while in a party, for leaving it after a long time away. */
	private long loggedOutAtMs;

	private final Map<Long, BattleMagePartyUpdate> received = new ConcurrentHashMap<>();
	private final Map<Long, Long> heardAt = new ConcurrentHashMap<>();
	private volatile Runnable onChange = () -> { };

	/** What we last told the party; also how we show ourselves in our own list. */
	private volatile BattleMagePartyUpdate lastSent;
	private int ticksSinceSend;
	private int ticksSincePanel;
	private boolean resendNow;

	@Inject
	PartySync(PartyService party, WSClient ws, ClientThread clientThread, ConfigManager configManager,
		ItemManager itemManager, Client client, PartyAvatar avatar, DeathSignposts signposts, Oath oath, Appearance look,
		RespectsOverlay respects)
	{
		this.respects = respects;
		this.oath = oath;
		this.signposts = signposts;
		this.look = look;
		this.avatar = avatar;
		this.itemManager = itemManager;
		this.client = client;
		this.party = party;
		this.ws = ws;
		this.clientThread = clientThread;
		this.configManager = configManager;
	}

	void start()
	{
		ws.registerMessage(BattleMagePartyUpdate.class);
		ws.registerMessage(BattleMagePartyExtras.class);
		ws.registerMessage(BattleMagePartyBell.class);
		ws.registerMessage(BattleMagePartyAvatar.class);
		ws.registerMessage(BattleMagePartyDeath.class);
		ws.registerMessage(BattleMagePartyOath.class);
		ws.registerMessage(BattleMagePartySignDestroy.class);
		ws.registerMessage(BattleMagePartyBlessing.class);
		ws.registerMessage(BattleMagePartyChat.class);
		ws.registerMessage(BattleMagePartyRespects.class);
		ws.registerMessage(BattleMagePartyReady.class);
		ws.registerMessage(BattleMagePartyVoice.class);
		// records older builds kept (who led which party, groups and their keys): no longer kept at all
		for (String old : new String[]{"partyStanding", "groupBook", "groupKeyPrivate", "groupKeyPublic"})
		{
			configManager.unsetConfiguration(BattleMageConfig.GROUP, old);
		}
		signposts.setOnRespects(owner ->
		{
			if (owner != null && party.isInParty() && party.getLocalMember() != null)
			{
				party.send(new BattleMagePartyRespects(owner));
			}
		});
		signposts.setOnDestroy(at ->
		{
			if (party.isInParty() && party.getLocalMember() != null)
			{
				party.send(new BattleMagePartySignDestroy(at));
			}
		});
		reset();
	}

	void stop()
	{
		ws.unregisterMessage(BattleMagePartyUpdate.class);
		ws.unregisterMessage(BattleMagePartyExtras.class);
		ws.unregisterMessage(BattleMagePartyBell.class);
		ws.unregisterMessage(BattleMagePartyAvatar.class);
		ws.unregisterMessage(BattleMagePartyDeath.class);
		ws.unregisterMessage(BattleMagePartyOath.class);
		ws.unregisterMessage(BattleMagePartySignDestroy.class);
		ws.unregisterMessage(BattleMagePartyBlessing.class);
		ws.unregisterMessage(BattleMagePartyChat.class);
		ws.unregisterMessage(BattleMagePartyRespects.class);
		ws.unregisterMessage(BattleMagePartyReady.class);
		ws.unregisterMessage(BattleMagePartyVoice.class);
		signposts.setOnRespects(null);
		signposts.setOnDestroy(null);
		reset();
	}

	/** Called whenever the party or what's known about it changes. */
	void setOnChange(Runnable r)
	{
		onChange = r == null ? () -> { } : r;
	}

	private void reset()
	{
		received.clear();
		heardAt.clear();
		players.clear();
		rebuild.clear();
		lastExtras = null;
		localPrayers = null;
		avatar.forgetAll();
		avatarDirty = true;
		lastSent = null;
		ticksSinceSend = 0;
		resendNow = false;
		onChange.run();
	}

	// ------------------------------------------------------------------ party controls

	boolean inParty()
	{
		return party.isInParty();
	}

	/** The current party's passphrase, or null outside a party. */
	String passphrase()
	{
		return party.isInParty() ? party.getPartyPassphrase() : null;
	}

	/** The last party joined or created here, or empty. */
	String lastPassphrase()
	{
		String s = configManager.getConfiguration(BattleMageConfig.GROUP, LAST_KEY);
		return s == null ? "" : s;
	}

	/** Starts a new party with a fresh passphrase. Any thread. */
	void create()
	{
		clientThread.invokeLater(() ->
		{
			final String p = party.generatePassphrase();
			// you lead the party you make - for this visit only: leave it and that's gone
			createdPending = p;
			join(p);
		});
	}

	/**
	 * Joins the party with this passphrase (spaces become hyphens). Returns false, doing nothing,
	 * for a passphrase that isn't letters, digits and hyphens. Any thread.
	 */
	boolean join(String passphrase)
	{
		String p = clean(passphrase);
		if (p == null)
		{
			return false;
		}
		configManager.setConfiguration(BattleMageConfig.GROUP, LAST_KEY, p);
		party.changeParty(p);
		onChange.run();
		return true;
	}

	void leave()
	{
		party.changeParty(null);
		onChange.run();
	}

	/** The passphrase tidied the way RuneLite's party expects it, or null when it can't be used. */
	static String clean(String passphrase)
	{
		if (passphrase == null)
		{
			return null;
		}
		String p = passphrase.trim().replace(' ', '-');
		if (p.isEmpty())
		{
			return null;
		}
		for (int i = 0; i < p.length(); i++)
		{
			char c = p.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != '-')
			{
				return null;
			}
		}
		return p;
	}

	// ------------------------------------------------------------------ sharing

	/**
	 * Once a game tick, from the client thread: sends your current state if it changed, on the
	 * heartbeat, or when a newcomer needs it. While not sworn to a faction, one "stopped sharing"
	 * message goes out so teammates drop you, then nothing.
	 */
	void tick(boolean share, String name, Faction faction, int state, AuraMode aura)
	{
		if (!party.isInParty() || party.getLocalMember() == null)
		{
			lastSent = null;
			return;
		}
		if (++ticksSincePanel >= PANEL_TICKS)
		{
			ticksSincePanel = 0;
			onChange.run();
		}
		if (joinedMs == 0L)
		{
			joinedMs = System.currentTimeMillis();
		}
		BattleMagePartyUpdate now = (share
			? new BattleMagePartyUpdate(name, faction == null ? null : faction.name(), state,
				aura == null ? null : aura.name(), false)
			: new BattleMagePartyUpdate(name, null, 0, null, true)).seniority(createdThis, joinedMs);
		tickVoice();
		tickReadyCheck();
		ticksSinceSend++;
		boolean changed = !now.sameAs(lastSent);
		boolean due = share && ticksSinceSend >= HEARTBEAT_TICKS;
		if (!changed && !due && !resendNow)
		{
			return;
		}
		resendNow = false;
		ticksSinceSend = 0;
		lastSent = now;
		party.send(now);
	}

	/**
	 * Every other game tick, from the client thread: reads your world, spellbook, poison, disease,
	 * stamina and prayer unlocks, and sends them when they change (or when someone new joins).
	 */
	void tickExtras(int tickCount)
	{
		if (!party.isInParty() || party.getLocalMember() == null || client.getLocalPlayer() == null
			|| tickCount % 2 != 0)
		{
			return;
		}
		if (localPrayers == null)
		{
			localPrayers = new Prayers();
		}
		for (PrayerSprites p : PrayerSprites.values())
		{
			localPrayers.updatePrayerState(p, client);
		}
		final int[] packed = localPrayers.pack();
		final BattleMagePartyExtras now = new BattleMagePartyExtras(client.getWorld(),
			client.getVarbitValue(VarbitID.SPELLBOOK), client.getVarpValue(VarPlayerID.POISON),
			client.getVarpValue(VarPlayerID.DISEASE), client.getVarbitValue(VarbitID.STAMINA_DURATION) > 0,
			packed[0], packed[1]);
		// your own panel shows your extras straight away
		applyExtras(party.getLocalMember().getMemberId(), now);
		if (now.sameAs(lastExtras) && !resendExtras)
		{
			return;
		}
		resendExtras = false;
		lastExtras = now;
		party.send(now);
	}

	/** You logged out: teammates see world 0 ("not logged in") right away. */
	void onLoggedOut()
	{
		loggedOutAtMs = System.currentTimeMillis();
		if (!party.isInParty() || party.getLocalMember() == null || lastExtras == null)
		{
			return;
		}
		final BattleMagePartyExtras out = new BattleMagePartyExtras(0, lastExtras.sb, lastExtras.p,
			lastExtras.d, lastExtras.st, lastExtras.ap, lastExtras.up);
		lastExtras = out;
		party.send(out);
	}

	void onLoggedIn()
	{
		loggedOutAtMs = 0;
	}

	/**
	 * Called every few seconds: after half an hour logged out, leave the party, so a forgotten
	 * client doesn't sit in it for days.
	 */
	void checkIdle(GameState state)
	{
		if (state == GameState.LOGIN_SCREEN && loggedOutAtMs > 0 && party.isInParty()
			&& System.currentTimeMillis() - loggedOutAtMs > 30 * 60_000L)
		{
			loggedOutAtMs = 0;
			leave();
		}
	}

	@Subscribe
	public void onBattleMagePartyExtras(BattleMagePartyExtras e)
	{
		PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		applyExtras(e.getMemberId(), e);
		onChange.run();
	}

	private void applyExtras(long memberId, BattleMagePartyExtras e)
	{
		final PartyMember m = party.getMemberById(memberId);
		if (m == null)
		{
			return;
		}
		final PartyPlayer p = players.computeIfAbsent(memberId, k -> new PartyPlayer(m));
		if (p.applyExtras(e.w, e.sb, e.p, e.d, e.st, e.ap, e.up))
		{
			rebuild.add(memberId);
		}
	}

	/**
	 * RuneLite's own party data arrived for a member (stats, items, prayers...). RuneLite has already
	 * stored it on the member by now; this copies it into the member's view on the client thread,
	 * where item names can be looked up.
	 */
	@Subscribe
	public void onPartyData(PartyData e)
	{
		final long id = e.getMemberId();
		clientThread.invoke(() ->
		{
			final PartyMember m = party.getMemberById(id);
			if (m == null)
			{
				return;
			}
			final PartyPlayer p = players.computeIfAbsent(id, k -> new PartyPlayer(m));
			final boolean knewHp = p.hasStats();
			final int hpBefore = p.getSkillBoostedLevel(net.runelite.api.Skill.HITPOINTS);
			if (p.update(m, itemManager))
			{
				rebuild.add(id);
			}
			// a teammate not playing Battle Mage Mode: their hitpoints hitting 0 means they died (Battle
			// Mages announce their own deaths, so they aren't counted twice)
			final PartyMember me = party.getLocalMember();
			if (knewHp && hpBefore > 0 && p.getSkillBoostedLevel(net.runelite.api.Skill.HITPOINTS) == 0
				&& (me == null || me.getMemberId() != id) && !received.containsKey(id))
			{
				final String name = p.getUsername() != null ? p.getUsername() : m.getDisplayName();
				final Player who = findPlayer(name);
				if (who != null && look.partyDeathCards())
				{
					signposts.spawn(name, who.getWorldLocation(), false);
				}
			}
			onChange.run();
		});
	}

	/** You died: a signpost on your tile, while in a party, and the party is told. */
	@Subscribe
	public void onActorDeath(net.runelite.api.events.ActorDeath e)
	{
		if (e.getActor() == client.getLocalPlayer())
		{
			announceOwnDeath();
		}
	}

	/**
	 * You died (or a test build pretended you did): a signpost drops onto your tile, and the party is
	 * told so theirs do the same. Only while in a party. Client thread.
	 */
	void announceOwnDeath()
	{
		final PartyMember me = party.getLocalMember();
		if (!party.isInParty() || me == null || client.getLocalPlayer() == null)
		{
			return;
		}
		final WorldPoint at = client.getLocalPlayer().getWorldLocation();
		if (look.partyDeathCards())
		{
			signposts.spawn(client.getLocalPlayer().getName(), at, true);
		}
		party.send(new BattleMagePartyDeath(at));
	}

	/** Test builds: pretend you died (your signpost, and the party's), from any thread. */
	void testDeath()
	{
		clientThread.invoke(this::announceOwnDeath);
	}

	private Player findPlayer(String name)
	{
		if (name == null)
		{
			return null;
		}
		final String want = net.runelite.client.util.Text.standardize(name);
		for (Player p : client.getPlayers())
		{
			if (p != null && p.getName() != null && net.runelite.client.util.Text.standardize(p.getName()).equals(want))
			{
				return p;
			}
		}
		return null;
	}

	@Subscribe
	public void onBattleMagePartyDeath(BattleMagePartyDeath e)
	{
		final PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		final PartyMember m = party.getMemberById(e.getMemberId());
		if (m == null)
		{
			return;
		}
		final BattleMagePartyUpdate u = received.get(e.getMemberId());
		final String name = u != null && u.n != null ? u.n : m.getDisplayName();
		final WorldPoint at = e.x == 0 && e.y == 0 ? null : new WorldPoint(e.x, e.y, e.pl);
		clientThread.invoke(() ->
		{
			if (at != null && name != null && look.partyDeathCards())
			{
				signposts.spawn(name, at, false);
			}
		});
	}

	// ------------------------------------------------------------------ leader

	/**
	 * Leadership lasts one visit and is never saved. You lead a party you created, while you stay in
	 * it; otherwise whoever has been in it longest leads. Leave and come back and you're the newest
	 * member, creator or not.
	 */
	private volatile String createdPending;
	private volatile boolean createdThis;
	private volatile long joinedMs;

	/**
	 * Who leads the party right now: its creator if they're still in it, else whoever has been in it
	 * longest (then the lowest member id, so every client picks the same one). Only members playing
	 * Battle Mage Mode count. 0 outside a party.
	 */
	long leaderId()
	{
		final PartyMember me = party.getLocalMember();
		if (!party.isInParty() || me == null)
		{
			return 0L;
		}
		// you count only while you're sharing as a Battle Mage, as you only do on everyone else's screens
		final BattleMagePartyUpdate sent = lastSent;
		final boolean meCounts = sent != null && !sent.h;
		long best = meCounts ? me.getMemberId() : 0L;
		boolean bestCreator = meCounts && createdThis;
		long bestJoined = !meCounts || joinedMs == 0 ? Long.MAX_VALUE : joinedMs;
		for (Map.Entry<Long, BattleMagePartyUpdate> e : received.entrySet())
		{
			final BattleMagePartyUpdate u = e.getValue();
			if (party.getMemberById(e.getKey()) == null)
			{
				continue;
			}
			final long joined = u.j <= 0 ? Long.MAX_VALUE : u.j;
			final boolean better;
			if (best == 0L)
			{
				better = true;
			}
			else if (u.c != bestCreator)
			{
				better = u.c;
			}
			else if (joined != bestJoined)
			{
				better = joined < bestJoined;
			}
			else
			{
				better = e.getKey() < best;
			}
			if (better)
			{
				best = e.getKey();
				bestCreator = u.c;
				bestJoined = joined;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ voice chat link

	/** The party's voice chat link (a Discord invite), set by its leader; null for none. This visit only. */
	private volatile String voiceLink;
	private volatile boolean voiceDue;
	private long voiceSentMs;

	String voiceLink()
	{
		return voiceLink;
	}

	/**
	 * The leader sets (or, with blank, clears) the party's voice link and shares it. False if you
	 * aren't the leader or it isn't a Discord invite.
	 */
	boolean setVoiceLink(String raw)
	{
		if (!isLeader())
		{
			return false;
		}
		final String clean = raw == null || raw.trim().isEmpty() ? "" : cleanVoiceLink(raw);
		if (clean == null)
		{
			return false;
		}
		voiceLink = clean.isEmpty() ? null : clean;
		voiceSentMs = System.currentTimeMillis();
		party.send(new BattleMagePartyVoice(clean));
		onChange.run();
		return true;
	}

	/** The leader shares the link with newcomers (and when they've just taken over). */
	private void tickVoice()
	{
		if (voiceDue && voiceLink != null && isLeader() && System.currentTimeMillis() - voiceSentMs > 10_000L)
		{
			voiceDue = false;
			voiceSentMs = System.currentTimeMillis();
			party.send(new BattleMagePartyVoice(voiceLink));
		}
	}

	@Subscribe
	public void onBattleMagePartyVoice(BattleMagePartyVoice e)
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || me.getMemberId() == e.getMemberId() || e.getMemberId() != leaderId())
		{
			return;   // only the leader sets it
		}
		voiceLink = e.l == null || e.l.isEmpty() ? null : cleanVoiceLink(e.l);
		onChange.run();
	}

	/**
	 * A Discord invite made safe to open: https://discord.gg/CODE or https://discord.com/invite/CODE
	 * (the code letters, numbers and hyphens), anything else refused. Null if it isn't one.
	 */
	static String cleanVoiceLink(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String s = raw.trim();
		if (s.isEmpty() || s.length() > 100)
		{
			return null;
		}
		if (!s.startsWith("http"))
		{
			s = "https://" + s;
		}
		final java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("^https://(?:www\\.)?(discord\\.gg/|discord(?:app)?\\.com/invite/)([A-Za-z0-9-]{2,32})/?$")
			.matcher(s);
		return m.matches() ? "https://discord.gg/" + m.group(2) : null;
	}

	boolean isLeader()
	{
		final PartyMember me = party.getLocalMember();
		return me != null && leaderId() == me.getMemberId();
	}

	// ------------------------------------------------------------------ ready check

	/** How long members have to answer. */
	private static final long READY_WINDOW_MS = 30_000L;
	/** The shortest gap between two ready checks. */
	private static final long READY_COOLDOWN_MS = 10_000L;
	/** How long the answers stay on the cards once the check is over. */
	private static final long READY_LINGER_MS = 8_000L;

	private volatile long readyId;
	private volatile long readyStartMs;
	private volatile long readyEndMs;
	private volatile boolean readyReported;
	private final Map<Long, Boolean> readyAnswers = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Random random = new java.security.SecureRandom();

	/** Whether a ready check is open (still taking answers). */
	boolean readyCheckOpen()
	{
		return readyId != 0 && !readyReported && System.currentTimeMillis() - readyStartMs < READY_WINDOW_MS;
	}

	/** Whether a ready check's answers should show on the cards. */
	boolean readyCheckShowing()
	{
		return readyId != 0 && (readyCheckOpen() || System.currentTimeMillis() < readyEndMs + READY_LINGER_MS);
	}

	/** A member's answer to the current check: true, false, or null for not yet. */
	Boolean readyAnswer(long memberId)
	{
		return readyAnswers.get(memberId);
	}

	/** Whether you still owe an answer to an open check. */
	boolean awaitingMyAnswer()
	{
		final PartyMember me = party.getLocalMember();
		return me != null && readyCheckOpen() && !readyAnswers.containsKey(me.getMemberId());
	}

	/** Seconds until the leader may ask again (0 when they may). */
	int readyCooldownSeconds()
	{
		final long left = READY_COOLDOWN_MS - (System.currentTimeMillis() - readyStartMs);
		return left <= 0 || readyStartMs == 0 ? 0 : (int) Math.ceil(left / 1000.0);
	}

	/** The leader asks the party whether everyone is ready. Any thread. */
	void startReadyCheck()
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || !isLeader() || readyCheckOpen() || readyCooldownSeconds() > 0)
		{
			return;
		}
		long id;
		do
		{
			id = random.nextLong();
		}
		while (id == 0);
		openReadyCheck(id);
		readyAnswers.put(me.getMemberId(), true);   // asking counts as ready
		party.send(new BattleMagePartyReady(BattleMagePartyReady.ASK, id, true));
		say("Ready check sent. Answers show on the party cards.");
		onChange.run();
	}

	/** Your answer to the open check. Any thread. */
	void answerReady(boolean ready)
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || !awaitingMyAnswer())
		{
			return;
		}
		readyAnswers.put(me.getMemberId(), ready);
		party.send(new BattleMagePartyReady(BattleMagePartyReady.ANSWER, readyId, ready));
		onChange.run();
		checkAllAnswered();
	}

	private void openReadyCheck(long id)
	{
		readyId = id;
		readyStartMs = System.currentTimeMillis();
		readyEndMs = 0L;
		readyReported = false;
		readyAnswers.clear();
	}

	private void endReadyCheck()
	{
		readyId = 0L;
		readyReported = false;
		readyAnswers.clear();
	}

	@Subscribe
	public void onBattleMagePartyReady(BattleMagePartyReady e)
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || me.getMemberId() == e.getMemberId() || e.id == 0)
		{
			return;
		}
		if (e.k == BattleMagePartyReady.ASK)
		{
			// only the leader may ask, and not more often than the cooldown
			if (e.getMemberId() != leaderId() || readyCheckOpen()
				|| (readyStartMs != 0 && System.currentTimeMillis() - readyStartMs < READY_COOLDOWN_MS))
			{
				return;
			}
			openReadyCheck(e.id);
			readyAnswers.put(e.getMemberId(), true);
			say(nameOf(e.getMemberId()) + " started a ready check: answer Ready or Not ready in the Battle Mage side panel.");
			onChange.run();
		}
		else if (e.k == BattleMagePartyReady.ANSWER && e.id == readyId && readyCheckOpen())
		{
			readyAnswers.put(e.getMemberId(), e.r);
			onChange.run();
			checkAllAnswered();
		}
	}

	/** Once a game tick: closes a check whose time is up. */
	private void tickReadyCheck()
	{
		if (readyId != 0 && !readyReported && System.currentTimeMillis() - readyStartMs >= READY_WINDOW_MS)
		{
			report();
		}
		else if (readyId != 0 && readyReported && System.currentTimeMillis() > readyEndMs + READY_LINGER_MS)
		{
			endReadyCheck();
			onChange.run();
		}
	}

	private void checkAllAnswered()
	{
		for (Mate m : members())
		{
			if (m.battleMage && !readyAnswers.containsKey(m.memberId))
			{
				return;
			}
		}
		report();
	}

	/** The check's outcome, once, in chat. */
	private synchronized void report()
	{
		if (readyReported || readyId == 0)
		{
			return;
		}
		readyReported = true;
		readyEndMs = System.currentTimeMillis();
		final List<String> notReady = new ArrayList<>();
		final List<String> silent = new ArrayList<>();
		for (Mate m : members())
		{
			if (!m.battleMage)
			{
				continue;
			}
			final Boolean a = readyAnswers.get(m.memberId);
			if (a == null)
			{
				silent.add(m.name);
			}
			else if (!a)
			{
				notReady.add(m.name);
			}
		}
		if (notReady.isEmpty() && silent.isEmpty())
		{
			say("Ready check: <col=00ff00>everyone is ready!</col>");
		}
		else
		{
			final StringBuilder b = new StringBuilder("Ready check:");
			if (!notReady.isEmpty())
			{
				b.append(" <col=ff3030>not ready: ").append(String.join(", ", notReady)).append("</col>");
			}
			if (!silent.isEmpty())
			{
				b.append(notReady.isEmpty() ? "" : ";").append(" no answer: ").append(String.join(", ", silent));
			}
			say(b.toString());
		}
		onChange.run();
	}

	/** A Battle Mage Mode line in the game chat. Any thread. */
	void say(String text)
	{
		clientThread.invoke(() -> client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			"<col=7f1fff>Battle Mage Mode:</col> " + text, null));
	}

	@Subscribe
	public void onBattleMagePartyRespects(BattleMagePartyRespects e)
	{
		final PartyMember me = party.getLocalMember();
		if (me == null || me.getMemberId() == e.getMemberId() || e.o == null)
		{
			return;
		}
		// only the signpost's owner sees it - every one, however fast they come. A teammate can only
		// pay ten times per signpost, so more than RESPECTS_PER_MINUTE a minute from one member can only
		// be a tampered client: those are dropped
		clientThread.invoke(() ->
		{
			final Player local = client.getLocalPlayer();
			if (local == null || local.getName() == null
				|| !net.runelite.client.util.Text.standardize(local.getName()).equals(net.runelite.client.util.Text.standardize(e.o)))
			{
				return;
			}
			final long now = System.currentTimeMillis();
			final java.util.Deque<Long> recent = respectsHeard.computeIfAbsent(e.getMemberId(), k -> new java.util.ArrayDeque<>());
			while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L)
			{
				recent.removeFirst();
			}
			if (recent.size() >= RESPECTS_PER_MINUTE)
			{
				return;
			}
			recent.addLast(now);
			respects.add(nameOf(e.getMemberId()));
		});
	}

	/** Ten a signpost, with room for a second signpost in the same minute. */
	private static final int RESPECTS_PER_MINUTE = 20;
	private final RespectsOverlay respects;
	private final Map<Long, java.util.Deque<Long>> respectsHeard = new java.util.concurrent.ConcurrentHashMap<>();

	@Subscribe
	public void onBattleMagePartyChat(BattleMagePartyChat e)
	{
		final PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;   // your own line is shown as you send it
		}
		// one line a second per member at most, and a few a second from the whole party: anything
		// faster is dropped, not queued
		final long now = System.currentTimeMillis();
		final Long last = chatHeard.get(e.getMemberId());
		if (last != null && now - last < CHAT_GAP_MS)
		{
			return;
		}
		synchronized (chatWindow)
		{
			while (!chatWindow.isEmpty() && now - chatWindow.peekFirst() > 1000L)
			{
				chatWindow.removeFirst();
			}
			if (chatWindow.size() >= PARTY_CHAT_PER_SECOND)
			{
				return;
			}
			chatWindow.addLast(now);
		}
		if (muted.contains(e.getMemberId()))
		{
			return;
		}
		chatHeard.put(e.getMemberId(), now);
		final String text = PartyChat.clean(e.t);
		if (text.isEmpty())
		{
			return;
		}
		chat.add(chatName(e.getMemberId()), text, false);
	}

	/** Lines a second accepted from the whole party at once. */
	private static final int PARTY_CHAT_PER_SECOND = 6;
	private final java.util.ArrayDeque<Long> chatWindow = new java.util.ArrayDeque<>();
	/** Members whose chat is ignored, until you leave the party. */
	private final java.util.Set<Long> muted = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * A sender's name for the chat log. Anyone can claim any name, so a name that someone else in the
	 * party (or you) also goes by is flagged "(?)" - nobody can quietly speak as someone else.
	 */
	private String chatName(long memberId)
	{
		final String name = nameOf(memberId);
		final String key = net.runelite.client.util.Text.standardize(name);
		final Player local = client.getLocalPlayer();
		boolean clash = local != null && local.getName() != null
			&& net.runelite.client.util.Text.standardize(local.getName()).equals(key);
		for (Map.Entry<Long, BattleMagePartyUpdate> other : received.entrySet())
		{
			if (other.getKey() != memberId && other.getValue().n != null
				&& net.runelite.client.util.Text.standardize(other.getValue().n).equals(key))
			{
				clash = true;
			}
		}
		return clash ? name + " (?)" : name;
	}

	/** Mutes (or unmutes) a party member's chat, by the name shown in the log. True if one matched. */
	boolean toggleMute(String shownName)
	{
		final String key = net.runelite.client.util.Text.standardize(shownName.replace(" (?)", ""));
		for (Map.Entry<Long, BattleMagePartyUpdate> m : received.entrySet())
		{
			if (m.getValue().n != null && net.runelite.client.util.Text.standardize(m.getValue().n).equals(key))
			{
				if (!muted.remove(m.getKey()))
				{
					muted.add(m.getKey());
					chat.add(null, m.getValue().n + " muted", true);
				}
				else
				{
					chat.add(null, m.getValue().n + " unmuted", true);
				}
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ party chat

	/** The shortest gap between two chat lines from one member (and from you), in milliseconds. */
	private static final long CHAT_GAP_MS = 1000L;
	private final PartyChat chat = new PartyChat();
	private final Map<Long, Long> chatHeard = new java.util.concurrent.ConcurrentHashMap<>();
	private volatile long chatSentMs;

	PartyChat chat()
	{
		return chat;
	}

	/**
	 * Sends a chat line to the party. False (and nothing sent) outside a party, for an empty line, or
	 * within a second of your last one.
	 */
	boolean sendChat(String raw)
	{
		final String text = PartyChat.clean(raw);
		final long now = System.currentTimeMillis();
		if (text.isEmpty() || !party.isInParty() || party.getLocalMember() == null || now - chatSentMs < CHAT_GAP_MS)
		{
			return false;
		}
		chatSentMs = now;
		party.send(new BattleMagePartyChat(text));
		final BattleMagePartyUpdate mine = lastSent;
		final PartyMember me = party.getLocalMember();
		chat.add(mine != null && mine.n != null ? mine.n : me.getDisplayName(), text, false);
		chat.markRead();
		return true;
	}

	/** A member's character name as last heard, else their party display name. */
	/**
	 * Puts text in the game's chat box, ready for you to press Enter: in the private-message box if
	 * one is open, else the normal chat line. Nothing is sent by itself. Any thread.
	 */
	void typeIntoChat(String text)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			final int mode = client.getVarcIntValue(net.runelite.api.gameval.VarClientID.MESLAYERMODE);
			if (mode == net.runelite.api.vars.InputType.PRIVATE_MESSAGE.getType())
			{
				client.setVarcStrValue(net.runelite.api.gameval.VarClientID.MESLAYERINPUT, text);
				client.runScript(net.runelite.api.ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
			}
			else
			{
				client.setVarcStrValue(net.runelite.api.gameval.VarClientID.CHATINPUT, text);
				client.runScript(net.runelite.api.ScriptID.CHAT_PROMPT_INIT);
			}
		});
	}

	private String nameOf(long memberId)
	{
		final BattleMagePartyUpdate u = received.get(memberId);
		if (u != null && u.n != null)
		{
			return u.n;
		}
		final PartyMember m = party.getMemberById(memberId);
		final String shown = m == null ? null : PartyChat.cleanName(m.getDisplayName());
		return shown == null ? "?" : shown;
	}

	@Subscribe
	public void onBattleMagePartySignDestroy(BattleMagePartySignDestroy e)
	{
		final PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		final PartyMember m = party.getMemberById(e.getMemberId());
		final BattleMagePartyUpdate u = received.get(e.getMemberId());
		final String name = u != null && u.n != null ? u.n : m == null ? null : m.getDisplayName();
		final WorldPoint at = new WorldPoint(e.x, e.y, e.pl);
		clientThread.invoke(() -> signposts.destroyed(name, at));
	}

	// ------------------------------------------------------------------ oath ordinations

	/** The plugin's way of playing an ordination on a party member. */
	void setOnOrdain(java.util.function.BiConsumer<Player, Faction> r)
	{
		onOrdain = r == null ? (p, f) -> { } : r;
	}

	/** You were just ordained (a faction chosen, by the Wise Old Man or the side panel): tell the party. */
	void announceOrdain(Faction f)
	{
		if (f != null && party.isInParty() && party.getLocalMember() != null)
		{
			party.send(new BattleMagePartyOath(f.name()));
		}
	}

	@Subscribe
	public void onBattleMagePartyOath(BattleMagePartyOath e)
	{
		final PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		final PartyMember m = party.getMemberById(e.getMemberId());
		final Faction f = faction(e.f);
		if (m == null || f == null)
		{
			return;
		}
		final BattleMagePartyUpdate u = received.get(e.getMemberId());
		final String name = u != null && u.n != null ? u.n : m.getDisplayName();
		clientThread.invoke(() ->
		{
			final Player who = findPlayer(name);
			if (who != null)
			{
				onOrdain.accept(who, f);
			}
		});
	}

	/**
	 * Once a second, logged in or not: sends your party picture when it changed or someone joined, so
	 * it shows whenever you're in the party, even from the login screen.
	 */
	void tickAvatar()
	{
		if (!party.isInParty() || party.getLocalMember() == null)
		{
			return;
		}
		avatar.setShowTheirs(look.partyPictures());
		if (avatarDirty)
		{
			avatarDirty = false;
			avatar.send();
			avatar.tidy();
		}
	}

	/** Your picture changed (chosen or removed): send it within a second. */
	void avatarChanged()
	{
		avatarDirty = true;
	}

	@Subscribe
	public void onBattleMagePartyAvatar(BattleMagePartyAvatar e)
	{
		PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		avatar.receive(e);
	}

	@Subscribe
	public void onPartyMemberAvatar(PartyMemberAvatar e)
	{
		// a teammate's uploaded picture wins over a Discord one: put it back (which comes round again)
		if (avatar.reassert(e.getMemberId(), e.getImage()))
		{
			return;
		}
		rebuild.add(e.getMemberId());
		onChange.run();
	}

	/** The member's full view data, or null until something has been heard about them. */
	PartyPlayer player(long memberId)
	{
		// anyone in the party gets a view, even before (or without) any data: name and "not logged in"
		final PartyMember m = party.getMemberById(memberId);
		return m == null ? players.get(memberId) : players.computeIfAbsent(memberId, k -> new PartyPlayer(m));
	}

	/** True once for each member whose banner needs rebuilding since the last call. */
	boolean takeRebuild(long memberId)
	{
		return rebuild.remove(memberId);
	}

	@Subscribe
	public void onBattleMagePartyUpdate(BattleMagePartyUpdate e)
	{
		// a character name is at most 12 letters, numbers, spaces, hyphens or underscores: anything else
		// came from a tampered client (and could be HTML for the side panel's labels), so it's stripped
		e.n = PartyChat.cleanName(e.n);
		PartyMember me = party.getLocalMember();
		if (me != null && me.getMemberId() == e.getMemberId())
		{
			return;
		}
		if (e.h)
		{
			received.remove(e.getMemberId());
		}
		else
		{
			received.put(e.getMemberId(), e);
			heardAt.put(e.getMemberId(), System.currentTimeMillis());
		}
		onChange.run();
	}

	/** Someone joined (or asked for everyone's state): send ours again on the next tick. */
	@Subscribe
	public void onUserSync(UserSync e)
	{
		resendNow = true;
		resendExtras = true;
		avatarDirty = true;
	}

	@Subscribe
	public void onUserJoin(UserJoin e)
	{
		voiceDue = true;
		onChange.run();
	}

	@Subscribe
	public void onUserPart(UserPart e)
	{
		voiceDue = true;   // if the leader left, whoever takes over passes the link on
		if (received.containsKey(e.getMemberId()))
		{
			chat.add(null, nameOf(e.getMemberId()) + " left the party", true);
		}
		chatHeard.remove(e.getMemberId());
		received.remove(e.getMemberId());
		heardAt.remove(e.getMemberId());
		players.remove(e.getMemberId());
		avatar.forget(e.getMemberId());
		onChange.run();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged e)
	{
		reset();
		chat.clear();
		muted.clear();
		endReadyCheck();
		// a new visit: you lead it only if you just made it; nothing about it is kept afterwards
		createdThis = e.getPassphrase() != null && e.getPassphrase().equals(createdPending);
		createdPending = null;
		joinedMs = e.getPassphrase() == null ? 0L : System.currentTimeMillis();
		voiceLink = null;
		if (e.getPartyId() != null)
		{
			chat.add(null, "Joined the party", true);
		}
	}

	// ------------------------------------------------------------------ reading

	/** Everyone in your party, you first, in party order. Empty outside a party. Any thread. */
	List<Mate> members()
	{
		List<Mate> out = new ArrayList<>();
		if (!party.isInParty())
		{
			return out;
		}
		PartyMember me = party.getLocalMember();
		long myId = me == null ? -1 : me.getMemberId();
		BattleMagePartyUpdate mine = lastSent;
		if (me != null)
		{
			out.add(mate(me, true, mine == null || mine.h ? null : mine, true));
		}
		for (PartyMember m : party.getMembers())
		{
			if (m.getMemberId() != myId)
			{
				Long heard = heardAt.get(m.getMemberId());
				boolean recent = heard != null && System.currentTimeMillis() - heard < OFFLINE_MS;
				out.add(mate(m, false, received.get(m.getMemberId()), recent || m.isLoggedIn()));
			}
		}
		return out;
	}

	/** The other Battle Mages in your party who are sharing. Any thread. */
	List<Mate> mates()
	{
		List<Mate> out = new ArrayList<>();
		if (received.isEmpty())
		{
			return out;
		}
		for (Mate m : members())
		{
			if (!m.self && m.battleMage)
			{
				out.add(m);
			}
		}
		return out;
	}

	private static Mate mate(PartyMember m, boolean self, BattleMagePartyUpdate u, boolean loggedIn)
	{
		String name = u != null && u.n != null ? u.n : m.getDisplayName();
		if (name == null || "<unknown>".equals(name))
		{
			name = self ? "You" : "Unknown";
		}
		if (u == null)
		{
			return new Mate(m.getMemberId(), name, self, loggedIn, false, null, 0, AuraMode.NONE);
		}
		return new Mate(m.getMemberId(), name, self, loggedIn, true, faction(u.f), u.s, aura(u.a));
	}

	/**
	 * The sharing Battle Mages in your party who are logged in, keyed by their standardised
	 * character name, for matching against the players in the scene. Any thread.
	 */
	Map<String, Mate> matesByName()
	{
		Map<String, Mate> out = new java.util.HashMap<>();
		for (Mate m : mates())
		{
			if (m.loggedIn && m.name != null)
			{
				out.put(net.runelite.client.util.Text.standardize(m.name), m);
			}
		}
		return out;
	}

	private static AuraMode aura(String name)
	{
		if (name == null)
		{
			return AuraMode.NONE;
		}
		try
		{
			return AuraMode.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return AuraMode.NONE;
		}
	}

	private static Faction faction(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return Faction.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}
}
