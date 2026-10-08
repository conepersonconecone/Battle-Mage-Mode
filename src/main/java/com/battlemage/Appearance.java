package com.battlemage;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

/**
 * Every visual setting the overlays read, with a draft layer on top for the side panel's
 * appearance editor.
 *
 * <p>The values are stored as hidden items in {@link BattleMageConfig}, so they persist like any
 * other setting but never appear in RuneLite's configuration panel. While the editor is open,
 * {@link #set} writes into a draft that the overlays read straight away - the change is visible on
 * screen - but nothing reaches the config until {@link #apply()}. {@link #cancel()} throws the
 * draft away.
 *
 * <p>The editor can also ask the overlays to show a <b>sample</b> of something that is not
 * currently happening - a state text, the effect badges, a low or overcharged bar - so every
 * setting can be judged by eye. While a state text is sampled it can be dragged with the mouse to
 * place it.
 */
@Singleton
public class Appearance
{
	/** Which state the overlays should pretend is active, for the preview. */
	enum Sample
	{
		NONE, DEPLETED, OVERLOAD, CRITICAL
	}

	/** Defaults, in one place, for the editor's "Reset to defaults". Match {@link BattleMageConfig}. */
	static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

	static
	{
		DEFAULTS.put("showBar", true);
		DEFAULTS.put("barLabel", "PKP");
		DEFAULTS.put("showPkpValue", true);
		DEFAULTS.put("barWidth", 220);
		DEFAULTS.put("barHeight", 24);
		DEFAULTS.put("barBorderThickness", 2);
		DEFAULTS.put("barGlow", 0);
		DEFAULTS.put("barPulse", true);
		DEFAULTS.put("showCostTicks", true);
		DEFAULTS.put("barColorFull", new Color(0x46C8FF));
		DEFAULTS.put("barColorLow", new Color(0xFF4040));
		DEFAULTS.put("overchargeColor", new Color(0x9CFF57));
		DEFAULTS.put("showEffectTimers", true);
		DEFAULTS.put("effectIconSize", 34);
		DEFAULTS.put("effectBorderEnabled", true);
		DEFAULTS.put("desaturateScreen", true);
		DEFAULTS.put("desaturationStrength", 140);
		DEFAULTS.put("depletedTextScalePercent", 100);
		DEFAULTS.put("overloadTextScalePercent", 100);
		DEFAULTS.put("criticalTextScalePercent", 100);
		DEFAULTS.put("depletedText", "PSI DEPLETED");
		DEFAULTS.put("overloadText", "PSI OVERLOAD");
		DEFAULTS.put("criticalText", "CRITICAL OVERLOAD");
		DEFAULTS.put("depletedColor", new Color(0xFF2A2A));
		DEFAULTS.put("overloadColor", new Color(0xC04AFF));
		DEFAULTS.put("criticalColor", new Color(0xFFB300));
		DEFAULTS.put("stateTextXPercent", 50.0);
		DEFAULTS.put("stateTextYPercent", 50.0);
		DEFAULTS.put("criticalTextXPercent", 50.0);
		DEFAULTS.put("criticalTextYPercent", 88.0);
		DEFAULTS.put("pkpPopupsEnabled", true);
		DEFAULTS.put("pkpPopupSeconds", 3);
		DEFAULTS.put("inventoryTooltips", true);
		DEFAULTS.put("castCardsEnabled", false);
		DEFAULTS.put("levelUpCards", true);
		DEFAULTS.put("meleeColor", new Color(0xF2F2F2));
		DEFAULTS.put("combatSpellColor", new Color(0x46C8FF));
		DEFAULTS.put("curseSpellColor", new Color(0xFF4FD8));
		DEFAULTS.put("illegalInventoryShade", new Color(0xFFB300));
		DEFAULTS.put("illegalExemptOutline", new Color(0x39FF14));
	}

	private final BattleMageConfig config;
	private final ConfigManager configManager;
	private final MouseManager mouseManager;
	private final Client client;

	private final Map<String, Object> draft = new ConcurrentHashMap<>();
	private volatile boolean editing;
	private volatile Sample sample = Sample.NONE;
	/** Bar fill to show instead of the real one, 0..1, or negative for the real value. */
	private volatile double sampleFill = -1;
	private volatile boolean sampleOvercharge;
	private volatile boolean sampleEffects;

	/** Where the state text was last drawn, in canvas pixels - what a drag has to land on. */
	private volatile Rectangle stateTextBounds;
	private volatile boolean dragging;
	private int dragDx;
	private int dragDy;
	private Runnable onDraftChange = () ->
	{
	};

	private final MouseAdapter dragHandler = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			Rectangle b = stateTextBounds;
			if (editing && draggableSample() && b != null && b.contains(e.getPoint()))
			{
				dragging = true;
				dragDx = e.getX() - (int) b.getCenterX();
				dragDy = e.getY() - (int) b.getCenterY();
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			if (dragging)
			{
				moveStateText(e.getX() - dragDx, e.getY() - dragDy);
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (dragging)
			{
				dragging = false;
				e.consume();
			}
			return e;
		}
	};

	@Inject
	Appearance(BattleMageConfig config, ConfigManager configManager, MouseManager mouseManager, Client client)
	{
		this.config = config;
		this.configManager = configManager;
		this.mouseManager = mouseManager;
		this.client = client;
	}

	// ------------------------------------------------------------------ editing

	/** Opens a draft (or keeps the open one). Nothing changes until something is {@link #set}. */
	void begin(Runnable onDraftChange)
	{
		this.onDraftChange = onDraftChange == null ? () ->
		{
		} : onDraftChange;
		if (editing)
		{
			return;
		}
		draft.clear();
		editing = true;
		mouseManager.registerMouseListener(dragHandler);
	}

	boolean isEditing()
	{
		return editing;
	}

	/** Puts a value in the draft; the overlays show it at once. */
	void set(String key, Object value)
	{
		if (editing && value != null)
		{
			draft.put(key, value);
		}
	}

	/** Writes the draft to the config and closes the editor. */
	void apply()
	{
		for (Map.Entry<String, Object> e : draft.entrySet())
		{
			configManager.setConfiguration(BattleMageConfig.GROUP, e.getKey(), e.getValue());
		}
		end();
	}

	/** Throws the draft away and closes the editor. */
	void cancel()
	{
		end();
	}

	/** Puts every default into the draft. Still needs {@link #apply()} to stick. */
	void resetDraftToDefaults()
	{
		if (editing)
		{
			draft.putAll(DEFAULTS);
		}
	}

	private void end()
	{
		if (!editing)
		{
			return;
		}
		editing = false;
		dragging = false;
		draft.clear();
		clearSamples();
		mouseManager.unregisterMouseListener(dragHandler);
	}

	// ------------------------------------------------------------------ samples

	void showSample(Sample s)
	{
		sample = s == null ? Sample.NONE : s;
	}

	void showSampleFill(double fill, boolean overcharge)
	{
		sampleFill = fill;
		sampleOvercharge = overcharge;
	}

	void showSampleEffects(boolean on)
	{
		sampleEffects = on;
	}

	void clearSamples()
	{
		sample = Sample.NONE;
		sampleFill = -1;
		sampleOvercharge = false;
		sampleEffects = false;
	}

	/** The state the overlays should draw for the preview, or NONE for the real one. */
	Sample sample()
	{
		return editing ? sample : Sample.NONE;
	}

	/** A bar fill to preview (0..1), or negative to draw the real fill. */
	double sampleFill()
	{
		return editing ? sampleFill : -1;
	}

	boolean sampleOvercharge()
	{
		return editing && sampleOvercharge;
	}

	boolean sampleEffects()
	{
		return editing && sampleEffects;
	}

	// ---------------------------------------------------------------- dragging

	void setStateTextBounds(Rectangle bounds)
	{
		stateTextBounds = bounds;
	}

	private boolean draggableSample()
	{
		return sample != Sample.NONE;
	}

	/** The config keys that hold where the given state's text sits. Critical has its own pair. */
	static String[] positionKeys(Sample s)
	{
		return s == Sample.CRITICAL
			? new String[]{"criticalTextXPercent", "criticalTextYPercent"}
			: new String[]{"stateTextXPercent", "stateTextYPercent"};
	}

	/** The default position for the given state's text, in percent of the canvas. */
	static double[] defaultPosition(Sample s)
	{
		return s == Sample.CRITICAL ? new double[]{50, 88} : new double[]{50, 50};
	}

	private void moveStateText(int cx, int cy)
	{
		int cw = client.getCanvasWidth();
		int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			return;
		}
		double x = Math.max(0, Math.min(100, cx * 100.0 / cw));
		double y = Math.max(0, Math.min(100, cy * 100.0 / ch));
		String[] keys = positionKeys(sample);
		draft.put(keys[0], Math.round(x * 10) / 10.0);
		draft.put(keys[1], Math.round(y * 10) / 10.0);
		onDraftChange.run();
	}

	// ------------------------------------------------------------------ reading

	@SuppressWarnings("unchecked")
	private <T> T get(String key, Supplier<T> stored)
	{
		if (editing)
		{
			Object v = draft.get(key);
			if (v != null)
			{
				return (T) v;
			}
		}
		return stored.get();
	}

	boolean showBar()
	{
		return get("showBar", config::showBar);
	}

	String barLabel()
	{
		return get("barLabel", config::barLabel);
	}

	boolean showPkpValue()
	{
		return get("showPkpValue", config::showPkpValue);
	}

	int barWidth()
	{
		return get("barWidth", config::barWidth);
	}

	int barHeight()
	{
		return get("barHeight", config::barHeight);
	}

	int barBorderThickness()
	{
		return get("barBorderThickness", config::barBorderThickness);
	}

	int barGlow()
	{
		return get("barGlow", config::barGlow);
	}

	boolean barPulse()
	{
		return get("barPulse", config::barPulse);
	}

	boolean showCostTicks()
	{
		return get("showCostTicks", config::showCostTicks);
	}

	Color barColorFull()
	{
		return get("barColorFull", config::barColorFull);
	}

	Color barColorLow()
	{
		return get("barColorLow", config::barColorLow);
	}

	Color overchargeColor()
	{
		return get("overchargeColor", config::overchargeColor);
	}

	boolean showEffectTimers()
	{
		return get("showEffectTimers", config::showEffectTimers);
	}

	int effectIconSize()
	{
		return get("effectIconSize", config::effectIconSize);
	}

	boolean effectBorderEnabled()
	{
		return get("effectBorderEnabled", config::effectBorderEnabled);
	}

	boolean desaturateScreen()
	{
		return get("desaturateScreen", config::desaturateScreen);
	}

	int desaturationStrength()
	{
		return get("desaturationStrength", config::desaturationStrength);
	}

	int depletedTextScalePercent()
	{
		return get("depletedTextScalePercent", config::depletedTextScalePercent);
	}

	int overloadTextScalePercent()
	{
		return get("overloadTextScalePercent", config::overloadTextScalePercent);
	}

	int criticalTextScalePercent()
	{
		return get("criticalTextScalePercent", config::criticalTextScalePercent);
	}

	String depletedText()
	{
		return get("depletedText", config::depletedText);
	}

	String overloadText()
	{
		return get("overloadText", config::overloadText);
	}

	String criticalText()
	{
		return get("criticalText", config::criticalText);
	}

	Color depletedColor()
	{
		return get("depletedColor", config::depletedColor);
	}

	Color overloadColor()
	{
		return get("overloadColor", config::overloadColor);
	}

	Color criticalColor()
	{
		return get("criticalColor", config::criticalColor);
	}

	double stateTextXPercent()
	{
		return get("stateTextXPercent", config::stateTextXPercent);
	}

	double stateTextYPercent()
	{
		return get("stateTextYPercent", config::stateTextYPercent);
	}

	double criticalTextXPercent()
	{
		return get("criticalTextXPercent", config::criticalTextXPercent);
	}

	double criticalTextYPercent()
	{
		return get("criticalTextYPercent", config::criticalTextYPercent);
	}

	boolean pkpPopupsEnabled()
	{
		return get("pkpPopupsEnabled", config::pkpPopupsEnabled);
	}

	int pkpPopupSeconds()
	{
		return get("pkpPopupSeconds", config::pkpPopupSeconds);
	}

	boolean inventoryTooltips()
	{
		return get("inventoryTooltips", config::inventoryTooltips);
	}

	boolean castCardsEnabled()
	{
		return get("castCardsEnabled", config::castCardsEnabled);
	}

	boolean levelUpCards()
	{
		return get("levelUpCards", config::levelUpCards);
	}

	Color meleeColor()
	{
		return get("meleeColor", config::meleeColor);
	}

	Color combatSpellColor()
	{
		return get("combatSpellColor", config::combatSpellColor);
	}

	Color curseSpellColor()
	{
		return get("curseSpellColor", config::curseSpellColor);
	}

	/** Wash over the inventory and worn equipment during CRITICAL OVERLOAD. */
	Color illegalInventoryShade()
	{
		return get("illegalInventoryShade", config::illegalInventoryShade);
	}

	/** Outline around the item that stays usable during CRITICAL OVERLOAD. */
	Color illegalExemptOutline()
	{
		return get("illegalExemptOutline", config::illegalExemptOutline);
	}
}
