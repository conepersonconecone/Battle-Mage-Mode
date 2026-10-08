package com.battlemage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The side panel's appearance menu: every visual setting, previewed on screen as it changes, and
 * only saved when <b>Apply</b> is pressed.
 *
 * <p>Touching a control also puts a matching sample on screen - the state text for the text
 * settings, the badges for the effect settings, a near-empty bar for the low colour - so each
 * setting is judged by eye rather than by number. The state text can be dragged on screen to place
 * it.
 */
class AppearanceEditor extends JPanel
{
	private static final Color ACCENT = new Color(0x46C8FF);

	private final Appearance look;
	private final Previews previews;

	/** Things the plugin can put on screen on request, for the preview. */
	interface Previews
	{
		/** A sample +/- PKP popup pair. */
		void popups();

		/** A sample cast card in the given family's colour. */
		void castCard(PsiAction family);

		/** A sample level-up card. */
		void levelUp();
	}
	private final Runnable onClose;
	private final Runnable onReload;

	/** The state the text section previews; the three buttons switch it. */
	private Appearance.Sample stateSample = Appearance.Sample.DEPLETED;
	private final JToggleButton[] stateButtons = new JToggleButton[3];
	private final JLabel position = new JLabel();

	/**
	 * @param onClose  called after Apply or Cancel, to put the normal side panel back
	 * @param onReload called to rebuild this editor from the current draft (after Reset)
	 */
	AppearanceEditor(Appearance look, Previews previews, Runnable onClose, Runnable onReload)
	{
		this.look = look;
		this.previews = previews;
		this.onClose = onClose;
		this.onReload = onReload;
		// Opening the editor a second time (after Reset) keeps the draft that is already open.
		look.begin(() -> javax.swing.SwingUtilities.invokeLater(this::refreshPosition));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel title = new JLabel("Customize appearance");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(title);
		add(note("Changes show on screen straight away. Nothing is saved until you press Apply."));
		add(Box.createVerticalStrut(6));
		add(buttons());

		// ---------------------------------------------------------------- PKP bar
		add(section("PKP bar"));
		Runnable bar = () -> sample(Appearance.Sample.NONE, -1, false, false);
		add(check("Show PKP bar", "showBar", look.showBar(), bar));
		add(text("Bar label", "barLabel", look.barLabel(), bar));
		add(check("Show numbers (off = percentages)", "showPkpValue", look.showPkpValue(), bar));
		add(slider("Width", "barWidth", 80, 1000, look.barWidth(), v -> v + " px", bar));
		add(slider("Height", "barHeight", 10, 80, look.barHeight(), v -> v + " px", bar));
		add(slider("Border", "barBorderThickness", 1, 8, look.barBorderThickness(), v -> v + " px", bar));
		add(slider("Glow", "barGlow", 0, 12, look.barGlow(), v -> v == 0 ? "off" : v + " px", bar));
		add(check("Pulse", "barPulse", look.barPulse(), bar));
		add(check("Cost ticks", "showCostTicks", look.showCostTicks(), bar));
		add(colour("Full colour", "barColorFull", look.barColorFull(), () -> sample(Appearance.Sample.NONE, 1.0, false, false)));
		add(colour("Low colour", "barColorLow", look.barColorLow(), () -> sample(Appearance.Sample.NONE, 0.12, false, false)));
		add(colour("Overcharge colour", "overchargeColor", look.overchargeColor(), () -> sample(Appearance.Sample.NONE, 1.0, true, false)));

		// ---------------------------------------------------------------- effects
		add(section("Effect badges"));
		Runnable fx = () -> sample(Appearance.Sample.NONE, -1, false, true);
		add(check("Show effect timers", "showEffectTimers", look.showEffectTimers(), fx));
		add(slider("Badge size", "effectIconSize", 20, 96, look.effectIconSize(), v -> v + " px", fx));
		add(check("Effects on bar border", "effectBorderEnabled", look.effectBorderEnabled(), fx));

		// ---------------------------------------------------------------- state text
		add(section("State text"));
		add(stateButtons());
		position.setFont(FontManager.getRunescapeSmallFont());
		position.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		position.setAlignmentX(Component.LEFT_ALIGNMENT);
		refreshPosition();
		add(position);
		JButton centre = smallButton("Reset this text's position");
		centre.addActionListener(e ->
		{
			String[] keys = Appearance.positionKeys(stateSample);
			double[] home = Appearance.defaultPosition(stateSample);
			look.set(keys[0], home[0]);
			look.set(keys[1], home[1]);
			pickState(stateSample);
		});
		add(centre);
		add(Box.createVerticalStrut(4));
		Runnable dep = () -> pickState(Appearance.Sample.DEPLETED);
		Runnable over = () -> pickState(Appearance.Sample.OVERLOAD);
		Runnable crit = () -> pickState(Appearance.Sample.CRITICAL);
		add(text("Depleted text", "depletedText", look.depletedText(), dep));
		add(slider("Depleted text size", "depletedTextScalePercent", 50, 500, look.depletedTextScalePercent(), v -> v + "%", dep));
		add(colour("Depleted colour", "depletedColor", look.depletedColor(), dep));
		add(check("Grey wash while depleted", "desaturateScreen", look.desaturateScreen(), dep));
		add(slider("Grey wash strength", "desaturationStrength", 0, 255, look.desaturationStrength(),
			v -> Math.round(v * 100 / 255.0) + "%", dep));
		add(text("Overload text", "overloadText", look.overloadText(), over));
		add(slider("Overload text size", "overloadTextScalePercent", 50, 500, look.overloadTextScalePercent(), v -> v + "%", over));
		add(colour("Overload colour", "overloadColor", look.overloadColor(), over));
		add(text("Critical overload text", "criticalText", look.criticalText(), crit));
		add(slider("Critical overload text size", "criticalTextScalePercent", 50, 500, look.criticalTextScalePercent(), v -> v + "%", crit));
		add(colour("Critical overload colour", "criticalColor", look.criticalColor(), crit));
		add(note("Markers on the inventory and worn equipment during critical overload:"));
		add(stackedColour("Critical Overload: inventory shade", "illegalInventoryShade",
			look.illegalInventoryShade(), crit));
		add(stackedColour("Critical Overload: exempt-item outline", "illegalExemptOutline",
			look.illegalExemptOutline(), crit));
		add(note("The outline shows on the Cowbell amulet, if you are carrying or wearing it."));

		// ---------------------------------------------------------------- cards
		add(section("Cast & level-up cards"));
		Runnable combatCard = () ->
		{
			sample(Appearance.Sample.NONE, -1, false, false);
			previews.castCard(PsiAction.COMBAT);
		};
		add(check("Show cast cards", "castCardsEnabled", look.castCardsEnabled(), combatCard));
		add(colour("Melee colour", "meleeColor", look.meleeColor(), () ->
		{
			sample(Appearance.Sample.NONE, -1, false, false);
			previews.castCard(PsiAction.MELEE);
		}));
		add(colour("Combat spell colour", "combatSpellColor", look.combatSpellColor(), combatCard));
		add(colour("Curse spell colour", "curseSpellColor", look.curseSpellColor(), () ->
		{
			sample(Appearance.Sample.NONE, -1, false, false);
			previews.castCard(PsiAction.CURSE);
		}));
		add(note("Cast cards must be on for the colour samples to show."));
		add(check("Show level-up cards", "levelUpCards", look.levelUpCards(), () ->
		{
			sample(Appearance.Sample.NONE, -1, false, false);
			previews.levelUp();
		}));

		// ---------------------------------------------------------------- popups & tooltips
		add(section("Popups & tooltips"));
		Runnable pop = () ->
		{
			sample(Appearance.Sample.NONE, -1, false, false);
			previews.popups();
		};
		add(check("PKP change popups", "pkpPopupsEnabled", look.pkpPopupsEnabled(), pop));
		add(slider("Popup duration", "pkpPopupSeconds", 1, 10, look.pkpPopupSeconds(), v -> v + " s", pop));
		add(check("Item tooltips", "inventoryTooltips", look.inventoryTooltips(), bar));

		add(Box.createVerticalStrut(10));
		add(buttons());
		JButton defaults = smallButton("Reset all to defaults");
		defaults.addActionListener(e ->
		{
			look.resetDraftToDefaults();
			onReload.run();
		});
		add(Box.createVerticalStrut(6));
		add(defaults);
	}

	// ------------------------------------------------------------------ actions

	private void sample(Appearance.Sample state, double fill, boolean overcharge, boolean effects)
	{
		look.showSample(state);
		look.showSampleFill(fill, overcharge);
		look.showSampleEffects(effects);
	}

	private void pickState(Appearance.Sample s)
	{
		stateSample = s;
		for (int i = 0; i < stateButtons.length; i++)
		{
			stateButtons[i].setSelected(i == s.ordinal() - 1);
		}
		sample(s, -1, false, false);
		refreshPosition();
	}

	private void refreshPosition()
	{
		boolean critical = stateSample == Appearance.Sample.CRITICAL;
		String where = String.format("%s position: %.0f%% across, %.0f%% down",
			critical ? "Critical" : "Depleted / overload",
			critical ? look.criticalTextXPercent() : look.stateTextXPercent(),
			critical ? look.criticalTextYPercent() : look.stateTextYPercent());
		position.setText("<html><body style='width:170px'>" + where
			+ "<br>Drag the text on screen to move it.</body></html>");
	}

	private JPanel buttons()
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		JButton apply = smallButton("Apply");
		apply.setForeground(ACCENT);
		apply.addActionListener(e ->
		{
			look.apply();
			onClose.run();
		});
		JButton cancel = smallButton("Cancel");
		cancel.addActionListener(e ->
		{
			look.cancel();
			onClose.run();
		});
		row.add(apply);
		row.add(cancel);
		return row;
	}

	private JPanel stateButtons()
	{
		JPanel row = new JPanel(new GridLayout(1, 3, 4, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		String[] names = {"Depleted", "Overload", "Critical"};
		Appearance.Sample[] states = {Appearance.Sample.DEPLETED, Appearance.Sample.OVERLOAD, Appearance.Sample.CRITICAL};
		for (int i = 0; i < 3; i++)
		{
			final Appearance.Sample s = states[i];
			JToggleButton b = new JToggleButton(names[i]);
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setFocusPainted(false);
			b.setMargin(new java.awt.Insets(2, 1, 2, 1));
			b.addActionListener(e -> pickState(s));
			stateButtons[i] = b;
			row.add(b);
		}
		stateButtons[0].setSelected(true);
		return row;
	}

	// ---------------------------------------------------------------- controls

	private interface Format
	{
		String of(int v);
	}

	private JPanel slider(String label, String key, int min, int max, int value, Format format, Runnable onTouch)
	{
		JPanel p = row();
		JLabel name = label(label);
		JLabel shown = label(format.of(value));
		shown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(name, BorderLayout.WEST);
		top.add(shown, BorderLayout.EAST);
		p.add(top, BorderLayout.NORTH);

		JSlider s = new JSlider(min, max, Math.max(min, Math.min(max, value)));
		s.setOpaque(false);
		s.addChangeListener(e ->
		{
			shown.setText(format.of(s.getValue()));
			look.set(key, s.getValue());
			onTouch.run();
		});
		p.add(s, BorderLayout.CENTER);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
		return p;
	}

	private JPanel check(String label, String key, boolean value, Runnable onTouch)
	{
		JPanel p = row();
		JCheckBox c = new JCheckBox(label, value);
		c.setFont(FontManager.getRunescapeSmallFont());
		c.setForeground(Color.WHITE);
		c.setOpaque(false);
		c.setFocusPainted(false);
		c.addActionListener(e ->
		{
			look.set(key, c.isSelected());
			onTouch.run();
		});
		p.add(c, BorderLayout.CENTER);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return p;
	}

	private JPanel text(String label, String key, String value, Runnable onTouch)
	{
		JPanel p = row();
		p.add(label(label), BorderLayout.NORTH);
		JTextField f = new JTextField(value == null ? "" : value);
		f.getDocument().addDocumentListener(new DocumentListener()
		{
			private void changed()
			{
				look.set(key, f.getText());
				onTouch.run();
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}
		});
		f.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				onTouch.run();
			}
		});
		p.add(f, BorderLayout.CENTER);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		return p;
	}

	/** A colour whose name is too long for one row: the name on top, the swatch on its own row. */
	private JPanel stackedColour(String label, String key, Color value, Runnable onTouch)
	{
		JPanel p = row();
		p.add(label(label), BorderLayout.NORTH);
		JButton swatch = swatch(label, key, value, onTouch);
		swatch.setPreferredSize(new Dimension(40, 20));
		p.add(swatch, BorderLayout.CENTER);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
		return p;
	}

	private JButton swatch(String label, String key, Color value, Runnable onTouch)
	{
		JButton swatch = new JButton();
		swatch.setPreferredSize(new Dimension(44, 20));
		swatch.setBackground(value);
		swatch.setOpaque(true);
		swatch.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		swatch.setFocusPainted(false);
		swatch.addActionListener(e ->
		{
			onTouch.run();
			Color picked = JColorChooser.showDialog(this, label, swatch.getBackground());
			if (picked != null)
			{
				swatch.setBackground(picked);
				look.set(key, picked);
				onTouch.run();
			}
		});
		return swatch;
	}

	private JPanel colour(String label, String key, Color value, Runnable onTouch)
	{
		JPanel p = row();
		p.add(label(label), BorderLayout.CENTER);
		p.add(swatch(label, key, value, onTouch), BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return p;
	}

	// ------------------------------------------------------------------ pieces

	private static JPanel row()
	{
		JPanel p = new JPanel(new BorderLayout(6, 2));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		return p;
	}

	private static JLabel label(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(Color.WHITE);
		return l;
	}

	private static JLabel note(String text)
	{
		JLabel l = new JLabel("<html><body style='width:170px'>" + text + "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JPanel section(String title)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(12, 0, 4, 0),
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR)));
		JLabel l = new JLabel(title.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ACCENT);
		p.add(l, BorderLayout.WEST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		return p;
	}

	private static JButton smallButton(String text)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return b;
	}
}
