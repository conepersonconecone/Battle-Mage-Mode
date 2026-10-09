package com.battlemage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
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
	private static final String TITHES_URL = "https://ko-fi.com/coneperson";
	private static final String SUGGESTIONS_URL = "https://discord.gg/mxtk4RRCmr";
	/** The rulebook, hosted with GitHub Pages from the plugin repo's docs/ folder. */
	private static final String RULEBOOK_URL = "https://conepersonconecone.github.io/Battle-Mage-Mode/";

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
	 * rulebook, the creator's channel and the tithes (Ko-fi) link.
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
		reselect.add(Box.createVerticalStrut(10));
		reselect.add(tithesShrine());
		reselect.add(Box.createVerticalStrut(22));
		reselect.add(creatorButton());
		reselect.add(Box.createVerticalStrut(4));
		reselect.add(linkButton("Suggestions", SUGGESTIONS_URL));
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
		// The rulebook is a web page (GitHub Pages), opened in the player's browser.
		rules.addActionListener(e -> LinkBrowser.browse(RULEBOOK_URL));
		return rules;
	}

	/** A plain side-panel button that opens a web page in the player's browser. */
	private JButton linkButton(String label, String url)
	{
		JButton b = new JButton(label);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		b.addActionListener(e -> LinkBrowser.browse(url));
		return b;
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

	/** Gold used for the shrine when no faction is sworn. */
	private static final Color UNSWORN_ACCENT = new Color(0xD8B45E);

	/** The sworn faction's colour, lifted a little so it reads on the dark panel; gold when unsworn. */
	private Color factionAccent()
	{
		Faction f = oath.getFaction();
		if (f == null)
		{
			return UNSWORN_ACCENT;
		}
		Color c = f.getPrimary();
		return new Color(
			c.getRed() + (255 - c.getRed()) / 4,
			c.getGreen() + (255 - c.getGreen()) / 4,
			c.getBlue() + (255 - c.getBlue()) / 4);
	}

	/**
	 * The recessed box at the bottom of the panel: the faction's figure above the TITHES button.
	 * A lighter fill with a dark top/left edge and a light bottom/right edge makes it read as set
	 * into the panel.
	 */
	private JPanel tithesShrine()
	{
		Shrine shrine = new Shrine(factionAccent());
		shrine.setLayout(new BoxLayout(shrine, BoxLayout.Y_AXIS));
		shrine.setBackground(new Color(0x34, 0x34, 0x34));
		shrine.setOpaque(true);
		shrine.setAlignmentX(Component.LEFT_ALIGNMENT);
		shrine.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(2, 2, 0, 0, new Color(0x161616)),
				BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0x4A4A4A))),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		BufferedImage img = figureFor(oath.getFaction());
		if (img != null)
		{
			JPanel holder = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
			holder.setOpaque(false);
			holder.setAlignmentX(Component.LEFT_ALIGNMENT);
			holder.add(new JLabel(new ImageIcon(img)));
			holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, img.getHeight()));
			shrine.add(holder);
			shrine.add(Box.createVerticalStrut(8));
		}
		JButton tithes = tithesButton();
		shrine.add(new GlowHolder(tithes));
		shrine.attach(tithes);
		shrine.setMaximumSize(new Dimension(Integer.MAX_VALUE, shrine.getPreferredSize().height));
		return shrine;
	}

	/** Cached figures per faction ("none" for unsworn), loaded once from the jar. */
	private final java.util.Map<String, BufferedImage> figures = new java.util.HashMap<>();

	private BufferedImage figureFor(Faction f)
	{
		String key = f == null ? "none" : f.name().toLowerCase(java.util.Locale.ROOT);
		if (figures.containsKey(key))
		{
			return figures.get(key);
		}
		BufferedImage img = null;
		try (java.io.InputStream in = BattleMagePanel.class.getResourceAsStream("/lowerniel-" + key + ".png"))
		{
			if (in != null)
			{
				img = javax.imageio.ImageIO.read(in);
			}
		}
		catch (java.io.IOException e)
		{
			img = null;
		}
		figures.put(key, img);
		return img;
	}

	/** Support link: letter-spaced serif "TITHES" in the faction's colour, with a matching border. */
	private JButton tithesButton()
	{
		Color accent = factionAccent();
		JButton tithes = new JButton("T I T H E S");
		tithes.setFont(new Font(Font.SERIF, Font.BOLD, 15));
		tithes.setForeground(accent);
		tithes.setFocusPainted(false);
		tithes.setAlignmentX(Component.LEFT_ALIGNMENT);
		tithes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		// Raised, the opposite of the recess it sits in: a lighter face, a light top/left edge, a
		// thick dark bottom/right edge as its shadow, then the faction-coloured rim.
		tithes.setContentAreaFilled(false);
		tithes.setOpaque(true);
		tithes.setBackground(new Color(0x4A, 0x4A, 0x4A));
		tithes.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 1, 0, 0, new Color(0x7A7A7A)),
				BorderFactory.createMatteBorder(0, 0, 3, 3, new Color(0x0E0E0E))),
			BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(accent.darker()),
				BorderFactory.createEmptyBorder(4, 6, 4, 6))));
		tithes.addActionListener(e ->
		{
			if (onTithesClick != null)
			{
				onTithesClick.run();
			}
			LinkBrowser.browse(TITHES_URL);
		});
		return tithes;
	}

	/** Wraps the TITHES button in a margin, so the halo the {@link Shrine} paints has room to show. */
	private static final class GlowHolder extends JPanel
	{
		private static final int PAD = 12;

		GlowHolder(JComponent child)
		{
			super(new BorderLayout());
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setBorder(BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
			add(child, BorderLayout.CENTER);
			setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getMaximumSize().height + PAD * 2));
		}
	}

	/** Told true when the mouse rests on TITHES and false when it leaves; the plugin runs the choir. */
	private java.util.function.Consumer<Boolean> onTithesHover;

	void setOnTithesHover(java.util.function.Consumer<Boolean> c)
	{
		onTithesHover = c;
	}

	/** Told when TITHES is clicked (before the link opens); the plugin plays the click sound. */
	private Runnable onTithesClick;

	void setOnTithesClick(Runnable r)
	{
		onTithesClick = r;
	}

	/**
	 * The recessed box that holds the figure and the TITHES button. On mouse-over it slowly grows a
	 * halo of the faction's colour behind the button's rim, breathing gently, and lets motes of light
	 * drift up and out of it, rising behind the figure. Everything eases back out when the mouse
	 * leaves. The animation timer only runs while something is visible.
	 */
	private final class Shrine extends JPanel
	{
		private final Color color;
		private final java.util.List<float[]> motes = new java.util.ArrayList<>(); // x, y, vx, vy, life, maxLife, size
		private final java.util.Random rng = new java.util.Random();
		private final javax.swing.Timer anim;
		private JComponent button;
		private float level;
		private float target;

		Shrine(Color color)
		{
			this.color = color;
			anim = new javax.swing.Timer(16, e -> tick());
		}

		void attach(JButton b)
		{
			button = b;
			b.addActionListener(e -> burst());
			b.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					hover(true);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					hover(false);
				}
			});
		}

		private void hover(boolean on)
		{
			target = on ? 1f : 0f;
			if (onTithesHover != null)
			{
				onTithesHover.accept(on);
			}
			if (!anim.isRunning())
			{
				anim.start();
			}
		}

		/** A click: the halo flares and a ring of motes bursts out of the button. */
		private void burst()
		{
			target = 0f;
			level = 1f;
			for (int i = 0; i < 40 && motes.size() < 140; i++)
			{
				spawn();
				float[] m = motes.get(motes.size() - 1);
				m[2] *= 3.2f;
				m[3] = m[3] * 3.2f - 0.4f;
			}
			if (!anim.isRunning())
			{
				anim.start();
			}
		}

		private void tick()
		{
			// slow swell in (~1s), quicker fade out
			level = level < target ? Math.min(target, level + 0.016f) : Math.max(target, level - 0.04f);
			if (button != null && level > 0.15f && motes.size() < 90 && rng.nextFloat() < level * 0.7f)
			{
				spawn();
			}
			for (java.util.Iterator<float[]> it = motes.iterator(); it.hasNext(); )
			{
				float[] m = it.next();
				m[0] += m[2];
				m[1] += m[3];
				m[3] -= 0.012f; // drift upward
				m[2] *= 0.985f;
				m[4] -= 1f;
				if (m[4] <= 0)
				{
					it.remove();
				}
			}
			repaint();
			if (level == 0f && target == 0f && motes.isEmpty())
			{
				anim.stop();
			}
		}

		/** A mote starts on the button's rim and heads outward from its centre. */
		private void spawn()
		{
			java.awt.Rectangle b = buttonBounds();
			float perim = 2f * (b.width + b.height);
			float d = rng.nextFloat() * perim;
			float x;
			float y;
			if (d < b.width)
			{
				x = b.x + d;
				y = b.y;
			}
			else if (d < b.width + b.height)
			{
				x = b.x + b.width;
				y = b.y + (d - b.width);
			}
			else if (d < 2 * b.width + b.height)
			{
				x = b.x + (d - b.width - b.height);
				y = b.y + b.height;
			}
			else
			{
				x = b.x;
				y = b.y + (d - 2 * b.width - b.height);
			}
			float dx = x - (float) b.getCenterX();
			float dy = y - (float) b.getCenterY();
			float len = (float) Math.max(1, Math.hypot(dx, dy));
			float speed = 0.25f + rng.nextFloat() * 0.6f;
			float life = 45 + rng.nextInt(60);
			motes.add(new float[]{x, y, dx / len * speed, dy / len * speed - 0.25f, life, life, 2f + rng.nextFloat() * 2.5f});
		}

		private java.awt.Rectangle buttonBounds()
		{
			return SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), this);
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			super.paintComponent(g);
			if (button == null || (level <= 0f && motes.isEmpty()))
			{
				return;
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				java.awt.Rectangle b = buttonBounds();
				if (level > 0f)
				{
					// The halo reaches further as the swell builds, and breathes once it is there.
					double breathe = 0.85 + 0.15 * Math.sin(System.currentTimeMillis() / 320.0);
					int extent = Math.max(1, (int) Math.round(GlowHolder.PAD * level * breathe));
					g2.setStroke(new java.awt.BasicStroke(2f));
					for (int i = extent; i >= 1; i--)
					{
						float fall = 1f - (i - 1) / (float) (extent + 1);
						int a = Math.round(level * 160 * fall * fall);
						g2.setColor(withAlpha(color, a));
						g2.drawRoundRect(b.x - i, b.y - i, b.width + i * 2 - 1, b.height + i * 2 - 1, i * 2 + 2, i * 2 + 2);
					}
				}
				Color light = new Color(
					(color.getRed() + 255) / 2, (color.getGreen() + 255) / 2, (color.getBlue() + 255) / 2);
				for (float[] m : motes)
				{
					float fade = m[4] / m[5];
					int a = Math.round(255 * fade);
					float sz = m[6];
					g2.setColor(withAlpha(color, a / 2));
					g2.fill(new java.awt.geom.Ellipse2D.Float(m[0] - sz * 1.5f, m[1] - sz * 1.5f, sz * 3f, sz * 3f));
					g2.setColor(withAlpha(light, a));
					g2.fill(new java.awt.geom.Ellipse2D.Float(m[0] - sz / 2f, m[1] - sz / 2f, sz, sz));
				}
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public void removeNotify()
		{
			anim.stop();
			if (target > 0f && onTithesHover != null)
			{
				onTithesHover.accept(false);
			}
			super.removeNotify();
		}
	}

	private static Color withAlpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
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
