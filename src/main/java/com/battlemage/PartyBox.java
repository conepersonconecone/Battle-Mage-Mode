package com.battlemage;

import com.battlemage.party.PartyPlayer;
import com.battlemage.party.PlayerPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The side panel's Party section. Create, join, leave and copy the passphrase right here: it all goes
 * through RuneLite's own party, so friends can join from this panel, the Party plugin or Party Panel
 * and still end up together.
 *
 * <p>Below the buttons, one panel per member, you first: avatar, name, combat level, world and
 * spellbook; Hitpoints, Prayer, Special attack and Run energy; a PKP bar for Battle Mages; and, opened
 * by clicking, their inventory, equipment, prayers and skills.
 */
class PartyBox extends JPanel
{
	private final PartySync party;
	private final Appearance look;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;
	private final PartyAvatar avatar;

	private final JPanel controls = new JPanel();
	private final JPanel members = new JPanel();
	private final Map<Long, PlayerPanel> panels = new HashMap<>();
	private final PlayerPanel.Options options;

	/** What the controls were last built for, so they are only rebuilt when that changes. */
	private String builtFor = "";
	/** A refresh is queued on the Swing thread: bursts of party messages share one. */
	private volatile boolean refreshQueued;
	private boolean lastVirtual;
	private boolean lastWorlds;
	/** Each god's crest, sized for the banner beside the spellbook. */
	private final Map<Faction, javax.swing.ImageIcon> crests = new java.util.EnumMap<>(Faction.class);
	private final javax.swing.JCheckBox headBars = new javax.swing.JCheckBox("Show teammates' PKP status in game");

	PartyBox(PartySync party, Appearance look, SpriteManager spriteManager, ItemManager itemManager, PartyAvatar avatar)
	{
		this.avatar = avatar;
		this.party = party;
		this.look = look;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		this.options = new PlayerPanel.Options()
		{
			@Override
			public boolean expandByDefault()
			{
				return look.partyExpand();
			}

			@Override
			public boolean virtualLevels()
			{
				return look.partyVirtualLevels();
			}

			@Override
			public boolean showWorlds()
			{
				return look.partyWorlds();
			}
		};
		lastVirtual = look.partyVirtualLevels();
		lastWorlds = look.partyWorlds();

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JLabel title = new JLabel("Party", javax.swing.SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, title.getPreferredSize().height));
		add(title);
		add(Box.createVerticalStrut(4));

		headBars.setFont(FontManager.getRunescapeSmallFont());
		headBars.setOpaque(false);
		headBars.setFocusPainted(false);
		headBars.setToolTipText("Outline and badges on party members in game for their PKP states (Overload, Depleted, Frozen...)");
		headBars.setSelected(look.partyHeadBars());
		headBars.addActionListener(e -> look.setPartyHeadBars(headBars.isSelected()));

		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(controls);
		add(Box.createVerticalStrut(6));

		members.setLayout(new BoxLayout(members, BoxLayout.Y_AXIS));
		members.setOpaque(false);
		members.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(members);

		party.setOnChange(this::refresh);
		party.chat().listen(() -> SwingUtilities.invokeLater(() ->
		{
			if (chatButton != null)
			{
				chatButton.setText(chatLabel());
			}
		}));
		refresh();
	}

	/** Re-reads the party. Safe from any thread; bursts collapse into one redraw. */
	void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			if (!refreshQueued)
			{
				refreshQueued = true;
				SwingUtilities.invokeLater(this::refresh);
			}
			return;
		}
		refreshQueued = false;

		String pass = party.passphrase();
		String key = pass == null ? "-" : pass;
		if (!key.equals(builtFor))
		{
			builtFor = key;
			buildControls(pass);
		}
		if (headBars.isSelected() != look.partyHeadBars())
		{
			headBars.setSelected(look.partyHeadBars());
		}
		updateReadyRow();
		updateCopyButton();
		updateVoiceButton();
		syncMembers();
		revalidate();
		repaint();
	}

	/** One panel per member in party order, you first; panels for people who left are dropped. */
	private void syncMembers()
	{
		final boolean virtual = look.partyVirtualLevels();
		final boolean worlds = look.partyWorlds();
		final boolean optionsChanged = virtual != lastVirtual || worlds != lastWorlds;
		lastVirtual = virtual;
		lastWorlds = worlds;

		final List<PartySync.Mate> list = party.members();
		final Set<Long> present = new HashSet<>();
		boolean reorder = false;
		int index = 0;
		for (PartySync.Mate m : list)
		{
			present.add(m.memberId);
			final PartyPlayer data = party.player(m.memberId);
			if (data == null)
			{
				continue;
			}
			PlayerPanel panel = panels.get(m.memberId);
			if (panel == null)
			{
				panel = new PlayerPanel(data, m.self, options, spriteManager, itemManager);
				if (m.self)
				{
					panel.getBanner().setAvatarAction(this::pictureMenu);
				}
				panels.put(m.memberId, panel);
				reorder = true;
			}
			else if (index >= members.getComponentCount() || members.getComponent(index) != panel)
			{
				reorder = true;
			}
			index++;
			panel.updatePlayerData(data, party.takeRebuild(m.memberId));
			final Faction god = m.battleMage ? m.faction : null;
			panel.getBanner().setFaction(crest(god), god == null ? null : god.getDisplayName());
			panel.getBanner().setChips(chips(m));
			if (optionsChanged)
			{
				panel.updateSkills();
				panel.updateDisplayWorld();
			}
		}
		if (panels.keySet().retainAll(present))
		{
			reorder = true;
		}
		if (reorder)
		{
			members.removeAll();
			for (PartySync.Mate m : list)
			{
				final PlayerPanel panel = panels.get(m.memberId);
				if (panel != null)
				{
					members.add(panel);
					members.add(Box.createVerticalStrut(5));
				}
			}
			members.revalidate();
		}
	}

	/** A member's PKP states as banner chips (none for a member not playing Battle Mage Mode). */
	private List<com.battlemage.party.PartyUi.Chip> chips(PartySync.Mate m)
	{
		if (!m.battleMage)
		{
			return java.util.Collections.emptyList();
		}
		final List<com.battlemage.party.PartyUi.Chip> out = new java.util.ArrayList<>();
		if (m.memberId == party.leaderId())
		{
			out.add(new com.battlemage.party.PartyUi.Chip("Leader", new Color(240, 200, 90),
				"Leads the party: can start ready checks"));
		}
		if (party.readyCheckShowing())
		{
			final Boolean a = party.readyAnswer(m.memberId);
			if (a == null)
			{
				out.add(new com.battlemage.party.PartyUi.Chip("...", ColorScheme.LIGHT_GRAY_COLOR, "Hasn't answered the ready check"));
			}
			else if (a)
			{
				out.add(new com.battlemage.party.PartyUi.Chip("Ready", new Color(110, 230, 110), "Ready"));
			}
			else
			{
				out.add(new com.battlemage.party.PartyUi.Chip("Not ready", new Color(240, 90, 90), "Not ready"));
			}
		}
		for (PartyStatus.Badge b : PartyStatus.badges(m.state, look))
		{
			out.add(new com.battlemage.party.PartyUi.Chip(b.word, b.color, b.meaning));
		}
		return out;
	}

	/** The god's crest for the banner (the panel's own 20px faction icon), or null. */
	private javax.swing.ImageIcon crest(Faction f)
	{
		if (f == null)
		{
			return null;
		}
		return crests.computeIfAbsent(f, k ->
		{
			try
			{
				return new javax.swing.ImageIcon(net.runelite.client.util.ImageUtil.loadImageResource(
					PartyBox.class, "/nav-icon-" + k.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
			}
			catch (RuntimeException ex)
			{
				return null;
			}
		});
	}

	/** Clicking your own picture square: paste a picture, or remove the one you have. */
	private void pictureMenu(java.awt.event.MouseEvent e)
	{
		final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		final javax.swing.JMenuItem choose = new javax.swing.JMenuItem("Paste party picture");
		choose.setToolTipText("Right-click a picture anywhere, choose Copy image, then use this");
		choose.addActionListener(a -> pastePicture());
		menu.add(choose);
		if (avatar.mine() != null)
		{
			final javax.swing.JMenuItem remove = new javax.swing.JMenuItem("Remove party picture");
			remove.addActionListener(a ->
			{
				avatar.remove();
				party.avatarChanged();
			});
			menu.add(remove);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	/** Your party picture from the clipboard: an image copied anywhere (no files are opened). */
	private void pastePicture()
	{
		try
		{
			final java.awt.datatransfer.Clipboard clip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
			if (!clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor))
			{
				JOptionPane.showMessageDialog(this,
					"Copy a picture first: right-click an image (in your browser, Discord and so on),\n"
						+ "choose Copy image, then use Paste party picture again.",
					"Party picture", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			avatar.choose((java.awt.Image) clip.getData(java.awt.datatransfer.DataFlavor.imageFlavor));
			party.avatarChanged();
		}
		catch (java.awt.datatransfer.UnsupportedFlavorException | IllegalStateException | ClassCastException ex)
		{
			JOptionPane.showMessageDialog(this, "Couldn't read the copied picture. Try copying it again.",
				"Party picture", JOptionPane.ERROR_MESSAGE);
		}
		catch (java.io.IOException ex)
		{
			JOptionPane.showMessageDialog(this, ex.getMessage(), "Party picture", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void buildControls(String pass)
	{
		controls.removeAll();
		if (pass == null)
		{
			JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
			row.setOpaque(false);
			row.add(button("Create party", "Start a new party and get a passphrase to give your friends",
				e -> party.create()));
			row.add(button("Join party", "Join a friend's party with their passphrase", e -> askToJoin()));
			controls.add(fill(row));

			String last = party.lastPassphrase();
			if (!last.isEmpty())
			{
				controls.add(Box.createVerticalStrut(4));
				controls.add(fill(button("Rejoin last party", "Join your last party again", e -> party.join(last))));
			}

		}
		else
		{
			// the passphrase itself isn't shown (it's easy to leak on stream): Copy passphrase has it

			JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
			row.setOpaque(false);
			// once the passphrase is what's on your clipboard, the button pastes it into the game's chat box
			copyButton = button("Copy passphrase", "Copy the passphrase to send to friends", e ->
			{
				if (clipboardHas(pass))
				{
					party.typeIntoChat(pass);
				}
				else
				{
					copy(pass);
				}
				updateCopyButton();
			});
			row.add(copyButton);
			JButton leave = button("Leave", "Leave the party", e -> party.leave());
			leave.setForeground(new Color(200, 80, 72));
			row.add(leave);
			controls.add(fill(row));
			controls.add(Box.createVerticalStrut(4));
			chatButton = button(chatLabel(), "Open the party chat in its own window", e -> openChat());
			voiceButton = button("Voice chat", "", e -> openVoice());
			voiceButton.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					voiceMenu(e);
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent e)
				{
					voiceMenu(e);
				}
			});
			JPanel talk = new JPanel(new GridLayout(1, 2, 4, 0));
			talk.setOpaque(false);
			talk.add(chatButton);
			talk.add(voiceButton);
			controls.add(fill(talk));
			updateVoiceButton();
			controls.add(Box.createVerticalStrut(4));
			readyRow = new JPanel(new java.awt.GridLayout(1, 0, 4, 0));
			readyRow.setOpaque(false);
			readyShownFor = null;
			controls.add(fill(readyRow));
			controls.add(Box.createVerticalStrut(2));
			headBars.setSelected(look.partyHeadBars());
			controls.add(fill(headBars));
		}
		controls.revalidate();
	}

	// ------------------------------------------------------------------ voice chat

	private JButton voiceButton;

	/** The Battle Mage Mode voice server (its #how-to-voice channel): where Voice chat goes while the leader hasn't set their own link. */
	static final String DEFAULT_VOICE_LINK = "https://discord.gg/vyG4hj3yx5";

	/**
	 * Voice chat opens the party leader's link, or the Battle Mage Mode voice server's Lobby while there's
	 * none. The leader sets or changes theirs by right-clicking the button; whoever leads next can replace it.
	 */
	private void updateVoiceButton()
	{
		if (voiceButton == null)
		{
			return;
		}
		final String link = party.voiceLink();
		final boolean leader = party.isLeader();
		voiceButton.setEnabled(true);
		voiceButton.setToolTipText(link != null
			? "Open the party's voice chat: " + link + (leader ? ". Right-click to change it." : "")
			: "Copy the passphrase and open the Battle Mage Mode voice server: type /room there and paste it"
				+ (leader ? ". Right-click to set your own link instead." : ""));
	}

	private void openVoice()
	{
		final String link = party.voiceLink();
		if (link == null)
		{
			// headed to the voice server: the passphrase goes on the clipboard, ready for /room
			final String pass = party.passphrase();
			if (pass != null)
			{
				copy(pass);
				updateCopyButton();
			}
		}
		net.runelite.client.util.LinkBrowser.browse(link != null ? link : DEFAULT_VOICE_LINK);
	}

	/** The leader's right-click menu on Voice chat: set or clear the party's link. */
	private void voiceMenu(java.awt.event.MouseEvent e)
	{
		if (!e.isPopupTrigger() || !party.isLeader())
		{
			return;
		}
		final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		final javax.swing.JMenuItem set = new javax.swing.JMenuItem(party.voiceLink() == null ? "Set voice link..." : "Change voice link...");
		set.addActionListener(a -> askVoiceLink());
		menu.add(set);
		if (party.voiceLink() != null)
		{
			final javax.swing.JMenuItem clear = new javax.swing.JMenuItem("Remove voice link");
			clear.addActionListener(a -> party.setVoiceLink(""));
			menu.add(clear);
		}
		menu.show(e.getComponent(), e.getX(), e.getY());
	}

	private void askVoiceLink()
	{
		final String current = party.voiceLink();
		final String link = (String) JOptionPane.showInputDialog(this,
			"Discord invite for your party (discord.gg/...):", "Voice chat link", JOptionPane.PLAIN_MESSAGE,
			null, null, current == null ? "" : current);
		if (link != null && !party.setVoiceLink(link))
		{
			JOptionPane.showMessageDialog(this, party.isLeader()
				? "That isn't a Discord invite link (like https://discord.gg/abc123)."
				: "Only the party leader can set the voice chat link.");
		}
		updateVoiceButton();
	}

	// ------------------------------------------------------------------ copy / paste passphrase

	private JButton copyButton;

	/** "Paste passphrase" while it's on your clipboard, "Copy passphrase" otherwise. */
	private void updateCopyButton()
	{
		final String pass = party.passphrase();
		if (copyButton == null || pass == null)
		{
			return;
		}
		final boolean paste = clipboardHas(pass);
		final String text = paste ? "Paste passphrase" : "Copy passphrase";
		if (!text.equals(copyButton.getText()))
		{
			copyButton.setText(text);
			copyButton.setToolTipText(paste
				? "Put the passphrase in the game's chat box (or the private message you're writing): press Enter to share it"
				: "Copy the passphrase to send to friends");
		}
	}

	/** Whether the clipboard holds exactly this text (false if it can't be read just now). */
	private static boolean clipboardHas(String text)
	{
		try
		{
			final Object v = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			return v instanceof String && ((String) v).trim().equalsIgnoreCase(text);
		}
		catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException | IllegalStateException ex)
		{
			return false;
		}
	}

	// ------------------------------------------------------------------ ready check

	private JPanel readyRow;
	/** What the ready row was last built for, so it is only rebuilt when that changes. */
	private String readyShownFor;

	/**
	 * The ready-check row: Ready / Not ready while you owe an answer; for the leader, the button that
	 * asks (counting down its cooldown); for everyone else, who leads.
	 */
	private void updateReadyRow()
	{
		if (readyRow == null || party.passphrase() == null)
		{
			return;
		}
		final boolean answer = party.awaitingMyAnswer();
		final boolean leader = party.isLeader();
		final int wait = party.readyCooldownSeconds();
		final boolean open = party.readyCheckOpen();
		final String leaderName = leaderName();
		final String key = answer + "|" + leader + "|" + wait + "|" + open + "|" + leaderName;
		if (key.equals(readyShownFor))
		{
			return;
		}
		readyShownFor = key;
		readyRow.removeAll();
		if (answer)
		{
			JButton yes = button("Ready", "Tell the party you're ready", e -> party.answerReady(true));
			yes.setForeground(new Color(110, 230, 110));
			JButton no = button("Not ready", "Tell the party you're not ready", e -> party.answerReady(false));
			no.setForeground(new Color(240, 90, 90));
			readyRow.add(yes);
			readyRow.add(no);
		}
		else if (leader)
		{
			JButton ask = button(open ? "Ready check running..." : wait > 0 ? "Ready check (" + wait + ")" : "Ready check",
				"Ask everyone in the party whether they're ready", e -> party.startReadyCheck());
			ask.setEnabled(!open && wait == 0);
			readyRow.add(ask);
		}
		else
		{
			JLabel who = new JLabel(leaderName == null ? "" : "Party leader: " + leaderName, javax.swing.SwingConstants.CENTER);
			who.setFont(FontManager.getRunescapeSmallFont());
			who.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			readyRow.add(who);
		}
		readyRow.revalidate();
		readyRow.repaint();
	}

	private String leaderName()
	{
		final long id = party.leaderId();
		for (PartySync.Mate m : party.members())
		{
			if (m.memberId == id)
			{
				return m.name;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ chat

	private JButton chatButton;
	private PartyChatWindow chatWindow;

	/** "Pop-out chat", with the number of lines not yet seen. */
	private String chatLabel()
	{
		int n = party.chat().unread();
		return n > 0 ? "Pop-out chat (" + n + ")" : "Pop-out chat";
	}

	private void openChat()
	{
		if (chatWindow == null)
		{
			chatWindow = new PartyChatWindow(SwingUtilities.getWindowAncestor(this), party);
		}
		chatWindow.open();
	}

	private void askToJoin()
	{
		String in = JOptionPane.showInputDialog(this, "Party passphrase:", "Join party", JOptionPane.PLAIN_MESSAGE);
		if (in == null || in.trim().isEmpty())
		{
			return;
		}
		if (!party.join(in))
		{
			JOptionPane.showMessageDialog(this,
				"A passphrase is letters, numbers and hyphens only.", "Join party", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void copy(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private static JButton button(String label, String tip, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(label);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setToolTipText(tip);
		b.addActionListener(a);
		return b;
	}

	private static JComponent fill(JComponent c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return c;
	}
}
