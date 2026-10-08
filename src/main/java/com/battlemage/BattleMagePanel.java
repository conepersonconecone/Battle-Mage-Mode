package com.battlemage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: which god is in force, what it has cost so far, and how to change it.
 *
 * <p>The god is freely changeable and hands off to {@link OnboardingOverlay}, so it is always
 * chosen on the oath screen where the crests and the per-god detail live - there is no path that
 * sets a god without the player seeing what it means.
 */
class BattleMagePanel extends PluginPanel
{
	private static final Color WARN = new Color(214, 162, 78);
	private static final Color DANGER = new Color(200, 80, 72);

	private final Oath oath;
	private final Rules rules;
	private final Appearance look;
	private final Runnable onChooseGod;
	private final AppearanceEditor.Previews previews;

	/** The normal panel contents, swapped out while the appearance editor is open. */
	private final JPanel body = new JPanel();
	private final JPanel reselect = new JPanel();
	private final JPanel godCard = new JPanel();
	private final JLabel warning = new JLabel();

	/** The channel the Creator button opens. */
	private static final String CREATOR_URL = "https://www.youtube.com/@AConePerson";

	BattleMagePanel(Oath oath, Rules rules, Appearance look, Runnable onChooseGod, AppearanceEditor.Previews previews)
	{
		this.oath = oath;
		this.rules = rules;
		this.look = look;
		this.onChooseGod = onChooseGod;
		this.previews = previews;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Battle-Mage Mode");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(title);
		body.add(Box.createVerticalStrut(10));

		// Near the top: the way back to the oath screen should never be something you scroll to.
		reselect.setLayout(new BoxLayout(reselect, BoxLayout.Y_AXIS));
		reselect.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(reselect);
		body.add(Box.createVerticalStrut(10));

		warning.setFont(FontManager.getRunescapeSmallFont());
		warning.setForeground(WARN);
		warning.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(warning);

		godCard.setLayout(new BorderLayout(8, 0));
		godCard.setAlignmentX(Component.LEFT_ALIGNMENT);

		add(body, BorderLayout.NORTH);
		rebuild();
	}

	/** Repaints the whole panel from current state. Safe to call from any thread. */
	void rebuild()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::rebuild);
			return;
		}

		if (rules.isPackMissing())
		{
			warning.setText("<html><body style='width:170px'>The content pack is missing from the jar. "
				+ "No rules are being enforced.</body></html>");
			warning.setForeground(DANGER);
			warning.setVisible(true);
		}
		else
		{
			warning.setText(" ");
			warning.setVisible(false);
		}

		buildReselect();
		buildGodCard();
		revalidate();
		repaint();
	}

	private void buildGodCard()
	{
		godCard.removeAll();
		Faction f = oath.getFaction();

		godCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		godCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));

		if (f == null)
		{
			godCard.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
				BorderFactory.createEmptyBorder(10, 10, 10, 10)));
			JLabel none = new JLabel("<html><body style='width:150px'><b>No god chosen.</b><br>"
				+ "Nothing is restricted until you pick one.</body></html>");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			godCard.add(none, BorderLayout.CENTER);
			return;
		}

		godCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, f.getPrimary()),
			BorderFactory.createEmptyBorder(9, 9, 9, 9)));

		godCard.add(new JLabel(new ImageIcon(factionIcon(f, 38))), BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		ContentPack.Lore lore = rules.lore(f);

		JLabel name = new JLabel(lore.displayName);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		text.add(name);

		JLabel epithet = new JLabel(lore.epithet);
		epithet.setFont(FontManager.getRunescapeSmallFont());
		epithet.setForeground(f.getPrimary());
		text.add(epithet);

		godCard.add(text, BorderLayout.CENTER);
	}

	/**
	 * The button back to the oath screen, at the very top of the panel.
	 *
	 * <p>It only works once the current oath has been dropped: a faction is left, not swapped. While
	 * one is in force the button greys but stays enabled, because a disabled button swallows the
	 * click and tells the player nothing - pressing it is how they find out that
	 * <b>Denounce Faction</b> comes first.
	 *
	 * <p>Below it sit the three buttons that are always available: denouncing (while sworn), the
	 * rulebook, and the creator's channel.
	 */
	private void buildReselect()
	{
		reselect.removeAll();

		final boolean sworn = oath.isSworn();

		JButton choose = new JButton("Select faction");
		choose.setFont(FontManager.getRunescapeSmallFont());
		choose.setFocusPainted(false);
		choose.setAlignmentX(Component.LEFT_ALIGNMENT);
		choose.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		// Greyed while an oath is in force so it reads as unavailable before it is clicked - but
		// still enabled, because a disabled button swallows the click and the player learns nothing.
		// Pressing it is how they find out why.
		choose.setForeground(sworn ? ColorScheme.MEDIUM_GRAY_COLOR : Color.WHITE);
		choose.addActionListener(e ->
		{
			if (oath.isSworn())
			{
				JOptionPane.showMessageDialog(BattleMagePanel.this,
					"Denounce your faction first. A faction is left, not swapped.",
					"Select faction", JOptionPane.WARNING_MESSAGE);
				return;
			}
			onChooseGod.run();
		});
		reselect.add(choose);

		if (sworn)
		{
			reselect.add(Box.createVerticalStrut(4));
			reselect.add(denounceButton());
		}

		// the faction card sits between the oath buttons and the rest
		reselect.add(Box.createVerticalStrut(8));
		reselect.add(godCard);

		reselect.add(Box.createVerticalStrut(8));
		reselect.add(appearanceButton());
		reselect.add(Box.createVerticalStrut(4));
		reselect.add(rulesButton());
		reselect.add(Box.createVerticalStrut(4));
		reselect.add(creatorButton());
	}

	/** Opens the appearance editor in place of the normal panel. */
	private JButton appearanceButton()
	{
		JButton b = new JButton("Customize appearance");
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		b.addActionListener(e -> showEditor());
		return b;
	}

	/** Swaps the panel to the appearance editor. Also used to rebuild it after Reset. */
	private void showEditor()
	{
		removeAll();
		add(new AppearanceEditor(look, previews, this::closeEditor, this::showEditor), BorderLayout.NORTH);
		revalidate();
		repaint();
	}

	/** Puts the normal panel back after Apply or Cancel. */
	private void closeEditor()
	{
		removeAll();
		add(body, BorderLayout.NORTH);
		rebuild();
	}

	/** Leaving a faction, behind one confirmation. */
	private JButton denounceButton()
	{
		JButton clear = new JButton("Denounce Faction");
		clear.setFont(FontManager.getRunescapeSmallFont());
		clear.setForeground(DANGER);
		clear.setFocusPainted(false);
		clear.setAlignmentX(Component.LEFT_ALIGNMENT);
		clear.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		clear.addActionListener(e ->
		{
			if (!oath.isSworn())
			{
				return;
			}
			final String name = rules.lore(oath.getFaction()).displayName;
			int first = JOptionPane.showConfirmDialog(BattleMagePanel.this,
				"Denounce " + name + "? Nothing will be restricted until you choose a faction again.",
				"Denounce Faction", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (first != JOptionPane.YES_OPTION)
			{
				return;
			}

			oath.clear();
		});
		return clear;
	}

	/** Opens the bundled rulebook in the player's browser. */
	private JButton rulesButton()
	{
		JButton rules = new JButton("Rules & equipment");
		rules.setFont(FontManager.getRunescapeSmallFont());
		rules.setFocusPainted(false);
		rules.setAlignmentX(Component.LEFT_ALIGNMENT);
		rules.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		// Says what the page actually holds. It used to promise "every rule, item and effect", which
		// stopped being true when the effect tables came off the page and the item lists moved behind
		// the Spoilers button - a tooltip that oversells is the same drift as a page that misquotes
		// the codex, just in the other direction.
		rules.addActionListener(e -> Rulebook.open());
		return rules;
	}

	/** The channel the plugin came from. */
	private JButton creatorButton()
	{
		JButton creator = new JButton("Creator");
		creator.setFont(FontManager.getRunescapeSmallFont());
		creator.setFocusPainted(false);
		creator.setAlignmentX(Component.LEFT_ALIGNMENT);
		creator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		creator.addActionListener(e -> LinkBrowser.browse(CREATOR_URL));
		return creator;
	}

	// ------------------------------------------------------------------ icons

	/** Renders a faction crest into an image, so the plugin needs no bundled PNGs. */
	static BufferedImage factionIcon(Faction faction, int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		FactionSigils.drawFaction(g, faction, 0, 0, size);
		g.dispose();
		return img;
	}

	/**
	 * The toolbar icon: the partyhat in the sworn god's colour (blue Saradomin, red Zamorak, green
	 * Guthix, purple Zaros); the blue one before an oath.
	 */
	static BufferedImage navIcon(Faction faction)
	{
		String file = faction == null ? "/nav-icon.png"
			: "/nav-icon-" + faction.name().toLowerCase(java.util.Locale.ROOT) + ".png";
		java.io.InputStream found = BattleMagePanel.class.getResourceAsStream(file);
		if (found == null)
		{
			found = BattleMagePanel.class.getResourceAsStream("/nav-icon.png");
		}
		try (java.io.InputStream in = found)
		{
			if (in != null)
			{
				return javax.imageio.ImageIO.read(in);
			}
		}
		catch (java.io.IOException ignored)
		{
			// fall through to a blank icon rather than leaving the button without one
		}
		return new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
	}
}
