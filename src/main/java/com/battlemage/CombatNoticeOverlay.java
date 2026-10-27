package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A combat-level notice in the bottom left of the game view: the same amber "!" ring as the
 * how-to-begin signpost, with a box above it that stays until the player presses OK. The level
 * warnings (combat 85, 86 and 87) and the Dwarf Multicannon reminder also carry a
 * "Don't remind me again" box under OK.
 *
 * <p>Used for: "too high a level to begin", and the warnings that the combat
 * {@value CombatGate#LOCK_COMBAT} lock is coming. Like any overlay it can be Alt-dragged.
 */
@Singleton
class CombatNoticeOverlay extends Overlay
{
	/** Saved when "Don't remind me again" is ticked, beside the oath, never shown as a setting. */
	static final String KEY_WARN_OFF = "combatWarningsOff";

	private static final Color BUTTON = new Color(58, 46, 30);
	private static final Color BUTTON_HOVER = new Color(84, 66, 40);
	private static final int PAD = 8;
	private static final int GAP = 6;
	private static final int MAX_W = 240;
	private static final int OK_W = 56;
	private static final int OK_H = 20;
	private static final int TICK = 11;

	/** Read by the begin signpost so the two never sit on top of each other. */
	private static volatile boolean showing;

	private final Client client;
	private final MouseManager mouseManager;
	private final ConfigManager configManager;

	private String text;
	private boolean withCheckbox;
	private boolean ticked;
	private boolean okHover;
	private Rectangle okHit;
	private Rectangle tickHit;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (text == null || e.getButton() != MouseEvent.BUTTON1 || e.isAltDown())
			{
				return e;
			}
			Point p = local(e);
			if (p == null)
			{
				return e;
			}
			if (okHit != null && okHit.contains(p))
			{
				acknowledge();
				e.consume();
			}
			else if (tickHit != null && tickHit.contains(p))
			{
				ticked = !ticked;
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent e)
		{
			Point p = local(e);
			okHover = p != null && okHit != null && okHit.contains(p);
			return e;
		}
	};

	@Inject
	CombatNoticeOverlay(Client client, MouseManager mouseManager, ConfigManager configManager)
	{
		this.client = client;
		this.mouseManager = mouseManager;
		this.configManager = configManager;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void start()
	{
		mouseManager.registerMouseListener(mouse);
	}

	void stop()
	{
		mouseManager.unregisterMouseListener(mouse);
		dismiss();
	}

	static boolean isShowing()
	{
		return showing;
	}

	/** Shows a notice the player closes with OK. */
	void show(String message)
	{
		open(message, false);
	}

	/** Shows a combat-level warning, unless the player has asked not to be reminded. */
	void warn(String message)
	{
		remind(message, KEY_WARN_OFF);
	}

	/**
	 * Shows a notice with "Don't remind me again" under OK; ticking it and pressing OK saves
	 * {@code offKey}, and a notice whose key is saved never shows again.
	 */
	void remind(String message, String offKey)
	{
		if (Boolean.TRUE.equals(configManager.getConfiguration(Oath.GROUP, offKey, Boolean.class)))
		{
			return;
		}
		offKeyShown = offKey;
		open(message, true);
	}

	/** The "don't show again" key of the notice on screen. */
	private String offKeyShown = KEY_WARN_OFF;

	private void open(String message, boolean checkbox)
	{
		text = message;
		withCheckbox = checkbox;
		ticked = false;
		showing = true;
	}

	private void acknowledge()
	{
		if (withCheckbox && ticked)
		{
			configManager.setConfiguration(Oath.GROUP, offKeyShown, true);
		}
		dismiss();
	}

	private void dismiss()
	{
		text = null;
		showing = false;
		okHit = null;
		tickHit = null;
	}

	private Point local(MouseEvent e)
	{
		Rectangle b = getBounds();
		return b == null ? null : new Point(e.getX() - b.x, e.getY() - b.y);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (text == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Font font = FontManager.getRunescapeFont();
		Font small = FontManager.getRunescapeSmallFont();
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = BeginHintOverlay.wrap(text, fm, MAX_W - PAD * 2);
		int textW = 0;
		for (String l : lines)
		{
			textW = Math.max(textW, fm.stringWidth(l));
		}
		g.setFont(small);
		FontMetrics sm = g.getFontMetrics();
		String tickLabel = "Don't remind me again";
		int tickRowW = TICK + 5 + sm.stringWidth(tickLabel);

		int boxW = Math.max(Math.max(textW, OK_W), withCheckbox ? tickRowW : 0) + PAD * 2;
		int textH = lines.size() * fm.getHeight();
		int boxH = PAD + textH + GAP + OK_H + (withCheckbox ? GAP + TICK + 2 : 0) + PAD;
		int ring = BeginHintOverlay.BUBBLE_D;
		int width = Math.max(ring + 2, boxW);
		int height = boxH + GAP + ring;

		// the box
		g.setColor(BeginHintOverlay.BOX);
		g.fill(new RoundRectangle2D.Double(0, 0, boxW, boxH, 8, 8));
		g.setColor(BeginHintOverlay.AMBER);
		g.setStroke(new BasicStroke(1.5f));
		g.draw(new RoundRectangle2D.Double(0.75, 0.75, boxW - 1.5, boxH - 1.5, 8, 8));

		g.setFont(font);
		int y = PAD + fm.getAscent();
		for (String l : lines)
		{
			g.setColor(Color.BLACK);
			g.drawString(l, PAD + 1, y + 1);
			g.setColor(BeginHintOverlay.BOX_TEXT);
			g.drawString(l, PAD, y);
			y += fm.getHeight();
		}

		// OK
		int okY = PAD + textH + GAP;
		okHit = new Rectangle((boxW - OK_W) / 2, okY, OK_W, OK_H);
		g.setColor(okHover ? BUTTON_HOVER : BUTTON);
		g.fill(new RoundRectangle2D.Double(okHit.x, okHit.y, okHit.width, okHit.height, 6, 6));
		g.setColor(BeginHintOverlay.AMBER);
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Double(okHit.x + 0.5, okHit.y + 0.5, okHit.width - 1, okHit.height - 1, 6, 6));
		String ok = "OK";
		g.setColor(Color.BLACK);
		g.drawString(ok, okHit.x + (OK_W - fm.stringWidth(ok)) / 2 + 1, okHit.y + (OK_H + fm.getAscent()) / 2 - 1);
		g.setColor(BeginHintOverlay.BOX_TEXT);
		g.drawString(ok, okHit.x + (OK_W - fm.stringWidth(ok)) / 2, okHit.y + (OK_H + fm.getAscent()) / 2 - 2);

		// "Don't remind me again", under OK
		if (withCheckbox)
		{
			int ty = okY + OK_H + GAP;
			int tx = (boxW - tickRowW) / 2;
			tickHit = new Rectangle(tx - 2, ty - 2, tickRowW + 4, TICK + 4);
			g.setColor(new Color(0, 0, 0, 160));
			g.fillRect(tx, ty, TICK, TICK);
			g.setColor(BeginHintOverlay.AMBER);
			g.drawRect(tx, ty, TICK, TICK);
			if (ticked)
			{
				g.setStroke(new BasicStroke(2f));
				g.drawLine(tx + 2, ty + 6, tx + 5, ty + 9);
				g.drawLine(tx + 5, ty + 9, tx + 10, ty + 2);
				g.setStroke(new BasicStroke(1f));
			}
			g.setFont(small);
			g.setColor(Color.BLACK);
			g.drawString(tickLabel, tx + TICK + 6, ty + TICK);
			g.setColor(BeginHintOverlay.BOX_TEXT);
			g.drawString(tickLabel, tx + TICK + 5, ty + TICK - 1);
		}
		else
		{
			tickHit = null;
		}

		BeginHintOverlay.drawAlert(g, 1, height - ring, BeginHintOverlay.AMBER);
		return new Dimension(width, height);
	}
}
