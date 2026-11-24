package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Full-canvas state washes. While depleted, a grey wash (its own setting, in either style); in the HUD
 * style, a dark wash in the overload or critical colour while overloaded or in Critical Overload. All
 * pulse gently. The state's name shows on the PKP bar (HUD) or floats off your character (in-game).
 */
class PkpStateOverlay extends Overlay
{
	/** How strong the overload and critical washes are at their peak, out of 255. */
	private static final int WASH_ALPHA = 50;

	private final Client client;
	private final BattleMagePlugin plugin;
	private final Appearance look;

	@Inject
	PkpStateOverlay(Client client, BattleMagePlugin plugin, Appearance look)
	{
		this.client = client;
		this.plugin = plugin;
		this.look = look;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// The appearance editor can ask for a sample state, so its settings can be judged on screen.
		final Appearance.Sample sample = look.sample();
		final boolean previewing = sample != Appearance.Sample.NONE;

		// the ILLEGAL warning takes over the screen (hazard tape + orange inventory); don't also show
		// the PSI OVERLOAD / PSI DEPLETED titles or wash underneath it.
		if (!previewing && plugin.illegalBehaviorActive())
		{
			return null;
		}

		final boolean critical = previewing ? sample == Appearance.Sample.CRITICAL : plugin.criticalOverloadActive();
		final boolean depleted = previewing ? sample == Appearance.Sample.DEPLETED : !critical && plugin.isDepleted();
		final boolean overloaded = previewing ? sample == Appearance.Sample.OVERLOAD
			: !critical && !depleted && plugin.isOverloaded();
		if (!critical && !depleted && !overloaded)
		{
			look.setStateTextBounds(null);
			return null;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		if (cw <= 0 || ch <= 0)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// gentle continuous pulse (0..1)
		double blink = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0);

		if (depleted && look.desaturateScreen())
		{
			int a = clamp((int) Math.round(look.desaturationStrength() * (0.7 + 0.3 * blink)));
			g.setColor(new Color(128, 128, 128, a));
			g.fillRect(0, 0, cw, ch);
		}
		else if (look.inGameStyle())
		{
			// the overload and critical washes are the HUD style's; in-game the orb's rim shows them
			look.setStateTextBounds(null);
			return null;
		}
		else if (overloaded)
		{
			Color o = look.overloadColor();
			int a = clamp((int) Math.round(WASH_ALPHA * (0.7 + 0.3 * blink)));
			g.setColor(new Color(o.getRed() / 3, 0, o.getBlue() / 3, a));
			g.fillRect(0, 0, cw, ch);
		}
		else if (critical)
		{
			// CRITICAL OVERLOAD keeps the centre of the screen clear - no hazard tape - so the yellow
			// reads as a wash at exactly the opacity the overload and depletion states use.
			Color c = look.criticalColor();
			int a = clamp((int) Math.round(WASH_ALPHA * (0.7 + 0.3 * blink)));
			g.setColor(new Color(c.getRed() / 2, c.getGreen() / 3, 0, a));
			g.fillRect(0, 0, cw, ch);
		}

		// the state's name now shows on the PKP bar itself: no words over the game
		look.setStateTextBounds(null);
		return null;
	}

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}
}
