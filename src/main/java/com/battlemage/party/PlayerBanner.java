/*
 * Adapted for Battle Mage Mode from Party Panel (https://github.com/TheStonedTurtle/party-panel).
 *
 * Copyright (c) 2020, TheStonedTurtle
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.battlemage.party;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.api.Constants;
import net.runelite.api.Skill;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * The always-visible top of a party member's panel: avatar, name and combat level, world, spellbook,
 * and (for Battle Mages) the crest of their god, then Hitpoints, Prayer, Special attack and Run
 * energy. Clicking it opens or closes the tabs below.
 */
public class PlayerBanner extends JPanel
{
	private static final Dimension STAT_ICON_SIZE = new Dimension(18, 18);
	private static final Dimension ICON_SIZE = new Dimension(Constants.ITEM_SPRITE_WIDTH - 4, Constants.ITEM_SPRITE_HEIGHT);
	private static final BufferedImage EXPAND_ICON = ImageUtil.loadImageResource(PlayerBanner.class, "expand.png");
	private static final String SPECIAL_ATTACK_NAME = "Special Attack";
	private static final String RUN_ENERGY_NAME = "Run Energy";

	private final JPanel statsPanel = new JPanel();
	private final JLabel iconLabel = new JLabel();
	private final Map<String, JLabel> statLabels = new HashMap<>();
	private final Map<String, JLabel> iconLabels = new HashMap<>();
	private final JLabel expandIcon = new JLabel();
	private final JLabel worldLabel = new JLabel();
	private final JLabel spellbookIcon = new JLabel();
	private final JLabel factionIcon = new JLabel();
	/** The member's PKP states, as chips under the stats (hidden when there are none). */
	private final JPanel chips = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 2));
	private java.util.List<PartyUi.Chip> shownChips = java.util.Collections.emptyList();

	private final ImageIcon expandIconUp;
	private final ImageIcon expandIconDown;
	private final boolean self;

	private PartyPlayer player;
	private boolean displayWorld;
	private boolean checkIcon;
	private BufferedImage currentHeart;
	private boolean usingStamIcon;
	private int shownSpellbook = Integer.MIN_VALUE;

	public PlayerBanner(final PartyPlayer player, final boolean self, final boolean expanded, final boolean displayWorld,
		final SpriteManager spriteManager)
	{
		this.player = player;
		this.self = self;
		this.displayWorld = displayWorld;

		setLayout(new GridBagLayout());
		setBorder(new EmptyBorder(2, 5, 2, 5));

		installHoverPreview();

		statsPanel.setLayout(new GridLayout(0, 4));
		statsPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
		statsPanel.setOpaque(true);

		expandIconDown = new ImageIcon(EXPAND_ICON);
		expandIconUp = new ImageIcon(ImageUtil.rotateImage(EXPAND_ICON, Math.PI));
		setExpandIcon(expanded);

		worldLabel.setHorizontalTextPosition(JLabel.LEFT);
		worldLabel.setFont(FontManager.getRunescapeSmallFont());
		worldLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		usingStamIcon = player.isStaminaActive();
		statsPanel.add(createIconPanel(spriteManager, SpriteID.Staticons.HITPOINTS, Skill.HITPOINTS.getName()));
		statsPanel.add(createIconPanel(spriteManager, SpriteID.Staticons.PRAYER, Skill.PRAYER.getName()));
		statsPanel.add(createIconPanel(spriteManager, SpriteID.OVERLAY_MULTIWAY, SPECIAL_ATTACK_NAME));
		statsPanel.add(createIconPanel(spriteManager,
			usingStamIcon ? SpriteID.OrbIcon.RUN_ICON_SLOWED_DEPLETION : SpriteID.OrbIcon.RUN, RUN_ENERGY_NAME));

		updateSpellbookIcon(player.getSpellbook(), spriteManager);
		recreatePanel();
	}

	public JPanel getStatsPanel()
	{
		return statsPanel;
	}

	public JLabel getExpandIcon()
	{
		return expandIcon;
	}

	/**
	 * Makes the picture square clickable (your own banner): {@code action} gets the click so it can
	 * offer to choose or remove your party picture.
	 */
	public void setAvatarAction(final java.util.function.Consumer<java.awt.event.MouseEvent> action)
	{
		iconLabel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		iconLabel.setToolTipText("Click to choose your party picture");
		iconLabel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				action.accept(e);
			}
		});
	}

	public void setPlayer(PartyPlayer player)
	{
		this.player = player;
	}

	/** True: arrow up (open); false: arrow down (closed). */
	public void setExpandIcon(boolean open)
	{
		expandIcon.setIcon(open ? expandIconUp : expandIconDown);
	}

	public void recreatePanel()
	{
		removeAll();

		final GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.NORTHWEST;
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 0;
		c.weighty = 0;
		c.ipady = 4;

		// the avatar frame shows whether or not there is an avatar, so every banner lines up
		iconLabel.setBorder(new MatteBorder(1, 1, 1, 1, ColorScheme.DARKER_GRAY_HOVER_COLOR));
		iconLabel.setPreferredSize(ICON_SIZE);
		iconLabel.setMinimumSize(ICON_SIZE);
		iconLabel.setOpaque(false);
		checkIcon = player.getMember() == null || player.getMember().getAvatar() == null;
		if (!checkIcon)
		{
			addIcon();
		}
		else
		{
			iconLabel.setIcon(null);
		}
		final GridBagConstraints iconConstraints = (GridBagConstraints) c.clone();
		iconConstraints.insets = new Insets(2, 0, 0, 0);
		add(iconLabel, iconConstraints);
		c.gridx++;

		final JPanel nameContainer = new JPanel(new GridLayout(2, 1));
		nameContainer.setBorder(new EmptyBorder(0, 5, 0, 0));
		nameContainer.setOpaque(false);

		final JLabel usernameLabel = new JLabel();
		final String name = player.getUsername();
		if (name == null)
		{
			usernameLabel.setText(self ? "You (not logged in)" : "Not logged in");
		}
		else
		{
			final String level = player.hasStats() ? " (level-" + player.getCombatLevel() + ")" : "";
			usernameLabel.setText(name + level + (self ? " (you)" : ""));
		}

		worldLabel.setText("");
		if (name != null && player.getWorld() > 0)
		{
			worldLabel.setText("World " + player.getWorld());
		}
		worldLabel.setVisible(displayWorld);

		final JPanel topRow = new JPanel(new BorderLayout());
		topRow.setOpaque(false);
		topRow.add(usernameLabel, BorderLayout.CENTER);
		topRow.add(expandIcon, BorderLayout.EAST);

		final JPanel bottomRow = new JPanel(new BorderLayout());
		bottomRow.setOpaque(false);
		bottomRow.add(worldLabel, BorderLayout.WEST);
		final JPanel icons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 3, 0));
		icons.setOpaque(false);
		icons.add(factionIcon);
		icons.add(spellbookIcon);
		bottomRow.add(icons, BorderLayout.EAST);

		nameContainer.add(topRow);
		nameContainer.add(bottomRow);

		c.weightx = 1.0;
		c.fill = GridBagConstraints.HORIZONTAL;
		add(nameContainer, c);

		refreshStats();
		c.gridy++;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridwidth = 2;
		add(statsPanel, c);

		c.gridy++;
		chips.setOpaque(false);
		chips.setVisible(!shownChips.isEmpty());
		add(chips, c);

		c.gridy++;
		c.weighty = 1.0;
		add(Box.createGlue(), c);

		revalidate();
		repaint();
	}

	/** How big a picture shows while the mouse rests on its square. */
	private static final int PREVIEW_SIZE = 128;
	private javax.swing.Popup preview;

	/** Hovering a picture shows it bigger, beside the side panel. */
	private void installHoverPreview()
	{
		iconLabel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				showPreview();
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				hidePreview();
			}

			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				hidePreview();
			}
		});
	}

	private void showPreview()
	{
		hidePreview();
		final BufferedImage img = player == null || player.getMember() == null ? null : player.getMember().getAvatar();
		if (img == null || !iconLabel.isShowing())
		{
			return;
		}
		final BufferedImage big = ImageUtil.resizeImage(img, PREVIEW_SIZE, PREVIEW_SIZE, true);
		final JLabel label = new JLabel(new ImageIcon(big));
		label.setOpaque(true);
		label.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		label.setBorder(new MatteBorder(2, 2, 2, 2, PartyUi.FRAME));
		final java.awt.Point at = iconLabel.getLocationOnScreen();
		final Dimension size = label.getPreferredSize();
		// to the left of the side panel, level with the square; to the right if that would leave the screen
		int x = at.x - size.width - 8;
		if (x < 0)
		{
			x = at.x + iconLabel.getWidth() + 8;
		}
		final int y = Math.max(0, at.y + iconLabel.getHeight() / 2 - size.height / 2);
		preview = javax.swing.PopupFactory.getSharedInstance().getPopup(iconLabel, label, x, y);
		preview.show();
	}

	private void hidePreview()
	{
		if (preview != null)
		{
			preview.hide();
			preview = null;
		}
	}

	@Override
	public void removeNotify()
	{
		hidePreview();
		super.removeNotify();
	}

	private void addIcon()
	{
		final BufferedImage resized = ImageUtil.resizeImage(player.getMember().getAvatar(),
			Constants.ITEM_SPRITE_WIDTH - 4, Constants.ITEM_SPRITE_HEIGHT, true);
		iconLabel.setIcon(new ImageIcon(resized));
	}

	public void refreshStats()
	{
		if (checkIcon && player.getMember() != null && player.getMember().getAvatar() != null)
		{
			addIcon();
			checkIcon = false;
		}
		statLabels.get(Skill.HITPOINTS.getName()).setText(String.valueOf(player.getSkillBoostedLevel(Skill.HITPOINTS)));
		statLabels.get(Skill.PRAYER.getName()).setText(String.valueOf(player.getSkillBoostedLevel(Skill.PRAYER)));
		statLabels.get(SPECIAL_ATTACK_NAME).setText(String.valueOf(player.getSpecialPercent()));
		statLabels.get(RUN_ENERGY_NAME).setText(String.valueOf(player.getRunEnergy()));
		statsPanel.revalidate();
		statsPanel.repaint();
	}

	/** The member's PKP states as chips under the stats; an empty list hides the row. */
	public void setChips(final java.util.List<PartyUi.Chip> list)
	{
		if (sameChips(list, shownChips))
		{
			return;
		}
		shownChips = list;
		chips.removeAll();
		for (PartyUi.Chip chip : list)
		{
			final JLabel l = new JLabel(chip.text);
			if (chip.icon != null)
			{
				l.setIcon(chip.icon);
				l.setIconTextGap(3);
			}
			l.setFont(FontManager.getRunescapeSmallFont());
			l.setForeground(chip.color);
			l.setToolTipText(chip.tooltip);
			l.setBorder(new javax.swing.border.CompoundBorder(
				new javax.swing.border.LineBorder(chip.color, 1, true), new EmptyBorder(0, 3, 0, 3)));
			chips.add(l);
		}
		chips.setVisible(!list.isEmpty());
		revalidate();
		repaint();
	}

	private static boolean sameChips(java.util.List<PartyUi.Chip> a, java.util.List<PartyUi.Chip> b)
	{
		if (a.size() != b.size())
		{
			return false;
		}
		for (int i = 0; i < a.size(); i++)
		{
			if (!a.get(i).text.equals(b.get(i).text) || !a.get(i).color.equals(b.get(i).color)
				|| a.get(i).icon != b.get(i).icon)
			{
				return false;
			}
		}
		return true;
	}

	/** The crest of the member's god, beside the spellbook; null for no faction (or not a Battle Mage). */
	public void setFaction(final ImageIcon crest, final String godName)
	{
		if (factionIcon.getIcon() != crest)
		{
			factionIcon.setIcon(crest);
		}
		factionIcon.setToolTipText(godName);
		factionIcon.setVisible(crest != null);
	}

	private JPanel createIconPanel(final SpriteManager spriteManager, final int spriteID, final String name)
	{
		final JLabel icon = new JLabel();
		icon.setPreferredSize(STAT_ICON_SIZE);
		iconLabels.put(name, icon);
		setSpriteIcon(name, spriteID, spriteManager);

		final JLabel text = new JLabel("0");
		text.setHorizontalAlignment(JLabel.CENTER);
		text.setHorizontalTextPosition(JLabel.CENTER);
		statLabels.put(name, text);

		final JPanel panel = new JPanel(new BorderLayout());
		panel.add(icon, BorderLayout.WEST);
		panel.add(text, BorderLayout.CENTER);
		panel.setOpaque(false);
		panel.setToolTipText(name);
		return panel;
	}

	private void setSpriteIcon(final String key, final int spriteID, final SpriteManager spriteManager)
	{
		final JLabel label = iconLabels.get(key);
		spriteManager.getSpriteAsync(spriteID, 0, img -> SwingUtilities.invokeLater(() ->
		{
			final int grow = spriteID == SpriteID.Staticons.PRAYER ? 2 : 0;
			label.setIcon(new ImageIcon(ImageUtil.resizeImage(img, STAT_ICON_SIZE.width + grow, STAT_ICON_SIZE.height + grow)));
			label.revalidate();
			label.repaint();
		}));
	}

	private void setBufferedIcon(final String key, final BufferedImage img)
	{
		final JLabel label = iconLabels.get(key);
		SwingUtilities.invokeLater(() ->
		{
			label.setIcon(new ImageIcon(ImageUtil.resizeImage(img, STAT_ICON_SIZE.width, STAT_ICON_SIZE.height)));
			label.revalidate();
			label.repaint();
		});
	}

	/** The Hitpoints icon turns green, dark green or orange-brown for poison, venom or disease. */
	public void setCurrentHeart(final BufferedImage img, final SpriteManager spriteManager)
	{
		if (img == currentHeart)
		{
			return;
		}
		currentHeart = img;
		if (img == null)
		{
			setSpriteIcon(Skill.HITPOINTS.getName(), SpriteID.Staticons.HITPOINTS, spriteManager);
		}
		else
		{
			setBufferedIcon(Skill.HITPOINTS.getName(), img);
		}
	}

	/** The run icon shows the stamina version while a stamina potion is working. */
	public void setUsingStamIcon(final boolean stamina, final SpriteManager spriteManager)
	{
		if (stamina == usingStamIcon)
		{
			return;
		}
		usingStamIcon = stamina;
		setSpriteIcon(RUN_ENERGY_NAME, stamina ? SpriteID.OrbIcon.RUN_ICON_SLOWED_DEPLETION : SpriteID.OrbIcon.RUN, spriteManager);
	}

	public void updateWorld(final int world, final boolean display)
	{
		displayWorld = display;
		worldLabel.setVisible(display);
		worldLabel.setText(world > 0 ? "World " + world : "");
	}

	public void updateSpellbookIcon(final int spellbook, final SpriteManager spriteManager)
	{
		if (spellbook == shownSpellbook)
		{
			return;
		}
		shownSpellbook = spellbook;
		if (spellbook < 0)
		{
			spellbookIcon.setIcon(null);
			spellbookIcon.setToolTipText(null);
			return;
		}
		final int spriteID;
		final String name;
		switch (spellbook)
		{
			case 3:
				spriteID = SpriteID.SideIcons.SPELLBOOK_ARCEUUS;
				name = "Arceuus spellbook";
				break;
			case 2:
				spriteID = SpriteID.SideIcons.SPELLBOOK_LUNAR;
				name = "Lunar spellbook";
				break;
			case 1:
				spriteID = SpriteID.SideIcons.SPELLBOOK_ANCIENT_MAGICKS;
				name = "Ancient Magicks";
				break;
			default:
				spriteID = SpriteID.SideIcons.MAGIC;
				name = "Standard spellbook";
				break;
		}
		spriteManager.getSpriteAsync(spriteID, 0, img ->
		{
			final BufferedImage resized = ImageUtil.resizeImage(img, 20, 20);
			SwingUtilities.invokeLater(() -> spellbookIcon.setIcon(new ImageIcon(resized)));
		});
		spellbookIcon.setToolTipText(name);
	}
}
