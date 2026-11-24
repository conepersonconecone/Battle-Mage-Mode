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

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.AlternateSprites;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * One party member: the banner, and (opened by clicking it) Inventory, Equipment, Prayers and Skills
 * tabs, each drawn the way the game draws them.
 */
public class PlayerPanel extends JPanel
{
	/** The side panel's options for these views, read live. */
	public interface Options
	{
		boolean expandByDefault();

		boolean virtualLevels();

		boolean showWorlds();
	}

	private static final Dimension IMAGE_SIZE = new Dimension(24, 24);
	private static final Color BACKGROUND_COLOR = ColorScheme.DARK_GRAY_COLOR;
	private static final Color BACKGROUND_HOVER_COLOR = ColorScheme.DARKER_GRAY_COLOR;

	private static final int VENOM_THRESHOLD = 1000000;
	private static final BufferedImage HEART_DISEASE = ImageUtil.loadImageResource(AlternateSprites.class, AlternateSprites.DISEASE_HEART);
	private static final BufferedImage HEART_POISON = ImageUtil.loadImageResource(AlternateSprites.class, AlternateSprites.POISON_HEART);
	private static final BufferedImage HEART_VENOM = ImageUtil.loadImageResource(AlternateSprites.class, AlternateSprites.VENOM_HEART);

	private PartyPlayer player;
	private final SpriteManager spriteManager;
	private final ItemManager itemManager;
	private final Options options;

	private final PlayerBanner banner;
	private final PlayerInventoryPanel inventoryPanel;
	private final PlayerEquipmentPanel equipmentPanel;
	private final PlayerSkillsPanel skillsPanel;
	private final PlayerPrayerPanel prayersPanel;

	private boolean showInfo;
	private final Map<Integer, Boolean> tabMap = new HashMap<>();

	public PlayerPanel(final PartyPlayer player, final boolean self, final Options options,
		final SpriteManager spriteManager, final ItemManager itemManager)
	{
		this.player = player;
		this.options = options;
		this.spriteManager = spriteManager;
		this.itemManager = itemManager;
		// your own panel starts closed: the point of the list is everyone else
		this.showInfo = !self && options.expandByDefault();
		this.banner = new PlayerBanner(player, self, showInfo, options.showWorlds(), spriteManager);
		this.inventoryPanel = new PlayerInventoryPanel(player.getInventory(), player.getRunesInPouch(), itemManager);
		this.equipmentPanel = new PlayerEquipmentPanel(player.getEquipment(), player.getQuiverAmmo(),
			player.isQuiverSlotVisible(), spriteManager, itemManager);
		this.skillsPanel = new PlayerSkillsPanel(player, options.virtualLevels(), spriteManager);
		this.prayersPanel = new PlayerPrayerPanel(player, spriteManager);
		setAlignmentX(Component.LEFT_ALIGNMENT);

		// clicking anywhere on the banner opens or closes the tabs
		final JPanel statsPanel = banner.getStatsPanel();
		final Component[] targets = new Component[statsPanel.getComponentCount() + 1];
		System.arraycopy(statsPanel.getComponents(), 0, targets, 0, statsPanel.getComponentCount());
		targets[targets.length - 1] = banner;
		final MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1)
				{
					setShowInfo(!showInfo);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				banner.setBackground(BACKGROUND_HOVER_COLOR);
				statsPanel.setBackground(BACKGROUND_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				banner.setBackground(BACKGROUND_COLOR);
				statsPanel.setBackground(BACKGROUND_COLOR);
			}
		};
		for (Component comp : targets)
		{
			comp.addMouseListener(toggle);
		}

		updatePanel();
	}

	public PlayerBanner getBanner()
	{
		return banner;
	}

	public PartyPlayer getPlayer()
	{
		return player;
	}

	public void setShowInfo(boolean show)
	{
		showInfo = show;
		banner.setExpandIcon(show);
		updatePanel();
		updatePlayerData(player, false);
	}

	private void addTab(final MaterialTabGroup tabGroup, final int spriteID, final JPanel panel, final String tooltip)
	{
		spriteManager.getSpriteAsync(spriteID, 0, img -> SwingUtilities.invokeLater(() ->
		{
			final MaterialTab tab = new MaterialTab(new ImageIcon(ImageUtil.resizeImage(img, IMAGE_SIZE.width, IMAGE_SIZE.height)),
				tabGroup, panel);
			tab.setToolTipText(tooltip);
			tabGroup.addTab(tab);
			tabGroup.revalidate();
			tabGroup.repaint();

			tabMap.put(spriteID, false);
			tab.setOnSelectEvent(() ->
			{
				tabMap.replaceAll((k, v) -> false);
				tabMap.put(spriteID, true);
				updatePlayerData(player, false);
				return true;
			});

			if (spriteID == SpriteID.SideiconsInterface.INVENTORY)
			{
				tabGroup.select(tab);
				tabMap.put(spriteID, true);
			}
		}));
	}

	/** Redraws from the member's latest data. Swing thread. */
	public void updatePlayerData(final PartyPlayer newPlayer, final boolean rebuildBanner)
	{
		player = newPlayer;
		banner.setPlayer(player);
		banner.updateSpellbookIcon(player.getSpellbook(), spriteManager);
		if (rebuildBanner)
		{
			banner.recreatePanel();
		}
		banner.refreshStats();

		BufferedImage heart = null;
		if (player.getPoison() >= VENOM_THRESHOLD)
		{
			heart = HEART_VENOM;
		}
		else if (player.getPoison() > 0)
		{
			heart = HEART_POISON;
		}
		else if (player.getDisease() > 0)
		{
			heart = HEART_DISEASE;
		}
		banner.setCurrentHeart(heart, spriteManager);
		banner.setUsingStamIcon(player.isStaminaActive(), spriteManager);

		if (!showInfo)
		{
			return;
		}

		if (tabMap.getOrDefault(SpriteID.SideiconsInterface.INVENTORY, false))
		{
			inventoryPanel.updateInventory(player.getInventory(), player.getRunesInPouch());
		}

		if (tabMap.getOrDefault(SpriteID.SideiconsInterface.EQUIPMENT, false))
		{
			for (final EquipmentInventorySlot equipSlot : EquipmentInventorySlot.values())
			{
				final GameItem item = player.getEquipment().length > equipSlot.getSlotIdx()
					? player.getEquipment()[equipSlot.getSlotIdx()] : null;
				final EquipmentPanelSlot slot = equipmentPanel.getPanelMap().get(equipSlot);
				if (slot == null)
				{
					continue;
				}
				if (item != null)
				{
					final AsyncBufferedImage img = itemManager.getImage(item.getId(), item.getQty(), item.isStackable());
					slot.setGameItem(item, img);
					img.onLoaded(() -> slot.setGameItem(item, img));
				}
				else
				{
					slot.setGameItem(null, null);
				}
			}
			equipmentPanel.setQuiver(player.getQuiverAmmo(), player.isQuiverSlotVisible());
		}

		if (tabMap.getOrDefault(SpriteID.SideiconsInterface.STATS, false))
		{
			updateSkills();
		}

		if (tabMap.getOrDefault(SpriteID.SideiconsInterface.PRAYER, false))
		{
			boolean unlockChanged = false;
			for (final Map.Entry<Prayer, PrayerSlot> entry : prayersPanel.getSlotMap().entrySet())
			{
				final PrayerData data = player.getPrayers().getPrayerData().get(entry.getKey());
				if (data != null)
				{
					unlockChanged = unlockChanged || data.isUnlocked() != entry.getValue().getData().isUnlocked();
					entry.getValue().updatePrayerData(data);
				}
			}
			if (unlockChanged)
			{
				prayersPanel.updateSlots();
			}
			prayersPanel.updatePrayerRemaining(player.getSkillBoostedLevel(Skill.PRAYER), player.getSkillRealLevel(Skill.PRAYER, false));
		}
	}

	public void updatePanel()
	{
		removeAll();
		setBorder(showInfo
			? new CompoundBorder(new MatteBorder(2, 2, 2, 2, PartyUi.FRAME), new EmptyBorder(0, 0, 5, 0))
			: new MatteBorder(2, 2, 2, 2, PartyUi.FRAME));

		final JPanel view = new JPanel();
		view.setBorder(new EmptyBorder(5, 0, 0, 0));
		final MaterialTabGroup tabGroup = new MaterialTabGroup(view);
		tabGroup.setBorder(new EmptyBorder(10, 0, 4, 0));

		tabMap.clear();
		addTab(tabGroup, SpriteID.SideiconsInterface.INVENTORY, inventoryPanel, "Inventory");
		addTab(tabGroup, SpriteID.SideiconsInterface.EQUIPMENT, equipmentPanel, "Equipment");
		addTab(tabGroup, SpriteID.SideiconsInterface.PRAYER, prayersPanel, "Prayers");
		addTab(tabGroup, SpriteID.SideiconsInterface.STATS, skillsPanel, "Skills");

		setLayout(new DynamicGridLayout(0, 1));
		add(banner);
		if (showInfo)
		{
			add(tabGroup);
			add(view);
		}
		revalidate();
		repaint();
	}

	/** Skills tab, and the total level, with or without virtual levels. */
	public void updateSkills()
	{
		final boolean virtual = options.virtualLevels();
		int total = 0;
		for (final Skill s : Skill.values())
		{
			total += player.getSkillRealLevel(s, virtual);
			skillsPanel.updateSkill(player, s, virtual);
		}
		skillsPanel.getTotalLevelPanel().updateTotalLevel(total);
	}

	public void updateDisplayWorld()
	{
		banner.updateWorld(player.getWorld(), options.showWorlds());
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
