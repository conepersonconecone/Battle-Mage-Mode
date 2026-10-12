package com.battlemage;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
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
 * The "how do I start?" signpost: a flat amber exclamation point in an open amber ring, in the
 * bottom left of the game view, shown whenever the plugin is on but no god is sworn. It lightens a
 * few shades while the mouse is over it.
 *
 * <p>Clicking it opens a box above it saying where to go; clicking the ring or the box again closes
 * it. The first two openings give the short hint; from the third on it adds the exact spot. The
 * count is saved, so it carries over between sessions. It steps aside while the oath pop-up is open and disappears for good the moment
 * a god is sworn (it comes back if the faction is denounced). Like any overlay it can be Alt-dragged
 * somewhere else.
 */
@Singleton
class BeginHintOverlay extends Overlay
{
	private static final Color AMBER = new Color(0xFF, 0xB9, 0x11);
	/** A few shades lighter, for while the mouse is over it. */
	private static final Color AMBER_HOVER = new Color(0xFF, 0xD2, 0x66);
	/** A soft drop shadow, as the game gives its own icons, so it holds up on bright ground. */
	private static final Color SHADOW = new Color(0, 0, 0, 90);
	private static final Color BOX = new Color(18, 14, 10, 235);
	private static final Color BOX_TEXT = new Color(255, 236, 214);

	/** The ring's outer diameter, and a small margin around it for the shadow. */
	private static final int RING_D = 30;
	private static final int GLOW_PAD = 2;
	private static final int BUBBLE_D = RING_D + GLOW_PAD * 2;
	private static final int BUBBLE_H = BUBBLE_D;

	/** How many times the box has been opened, ever - stored beside the oath, never shown as a setting. */
	private static final String KEY_OPENS = "beginHintOpens";
	/** Openings that get the short hint before the exact spot is spelled out. */
	private static final int SHORT_HINT_OPENS = 2;
	private static final String EXACT_HINT = "Ring the Cowbell Amulet 2 steps in front of the Wise Old Man";
	private static final int BOX_PAD = 8;
	private static final int BOX_GAP = 6;
	private static final int BOX_MAX_W = 220;
	/** The box stays fully visible this long, then fades out over BOX_FADE_MS and closes itself. */
	private static final long BOX_HOLD_MS = 3500L;
	private static final long BOX_FADE_MS = 1500L;

	private final Client client;
	private final Oath oath;
	private final OnboardingOverlay onboarding;
	private final MouseManager mouseManager;
	private final ConfigManager configManager;

	private boolean boxOpen;
	private long boxOpenedMs;
	private boolean hovered;
	/** The text for the box while it is open, fixed at the moment it was opened. */
	private String boxText = BattleMagePanel.BEGIN_HINT;
	/** Where the bubble and the box were last drawn, relative to the overlay's own corner. */
	private Rectangle bubbleHit;
	private Rectangle boxHit;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (e.getButton() != MouseEvent.BUTTON1 || e.isAltDown() || !showing())
			{
				return e;
			}
			Rectangle b = getBounds();
			if (b == null)
			{
				return e;
			}
			Point p = new Point(e.getX() - b.x, e.getY() - b.y);
			if (bubbleHit != null && bubbleHit.contains(p))
			{
				if (boxOpen)
				{
					boxOpen = false;
				}
				else
				{
					openBox();
				}
				e.consume();
			}
			else if (boxOpen && boxHit != null && boxHit.contains(p))
			{
				boxOpen = false;
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent e)
		{
			Rectangle b = getBounds();
			hovered = showing() && b != null && bubbleHit != null
				&& bubbleHit.contains(e.getX() - b.x, e.getY() - b.y);
			return e;
		}
	};

	@Inject
	BeginHintOverlay(Client client, Oath oath, OnboardingOverlay onboarding, MouseManager mouseManager,
		ConfigManager configManager)
	{
		this.client = client;
		this.oath = oath;
		this.onboarding = onboarding;
		this.mouseManager = mouseManager;
		this.configManager = configManager;
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void start()
	{
		boxOpen = false;
		mouseManager.registerMouseListener(mouse);
	}

	void stop()
	{
		mouseManager.unregisterMouseListener(mouse);
		boxOpen = false;
	}

	/** Counts this opening and picks the text: short for the first two, the exact spot after that. */
	private void openBox()
	{
		Integer seen = configManager.getConfiguration(Oath.GROUP, KEY_OPENS, Integer.class);
		int opens = (seen == null ? 0 : seen) + 1;
		configManager.setConfiguration(Oath.GROUP, KEY_OPENS, opens);
		boxText = opens > SHORT_HINT_OPENS ? EXACT_HINT : BattleMagePanel.BEGIN_HINT;
		boxOpen = true;
		boxOpenedMs = System.currentTimeMillis();
	}

	private boolean showing()
	{
		return !oath.isSworn() && !onboarding.isActive() && client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!showing())
		{
			boxOpen = false;
			bubbleHit = null;
			boxHit = null;
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		// After a few seconds the box fades away on its own.
		float boxAlpha = 1f;
		if (boxOpen)
		{
			long age = System.currentTimeMillis() - boxOpenedMs;
			if (age >= BOX_HOLD_MS + BOX_FADE_MS)
			{
				boxOpen = false;
			}
			else if (age > BOX_HOLD_MS)
			{
				boxAlpha = 1f - (age - BOX_HOLD_MS) / (float) BOX_FADE_MS;
			}
		}

		// Lay the box out first: it sits above the ring, so it decides the overlay's height.
		List<String> lines = new ArrayList<>();
		int boxW = 0;
		int boxH = 0;
		Font font = FontManager.getRunescapeFont();
		if (boxOpen)
		{
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			lines = wrap(boxText, fm, BOX_MAX_W - BOX_PAD * 2);
			for (String l : lines)
			{
				boxW = Math.max(boxW, fm.stringWidth(l));
			}
			boxW += BOX_PAD * 2;
			boxH = lines.size() * fm.getHeight() + BOX_PAD * 2;
		}

		int width = Math.max(BUBBLE_D + 2, boxW);
		int height = BUBBLE_H + (boxOpen ? boxH + BOX_GAP : 0);

		if (boxOpen)
		{
			boxHit = new Rectangle(0, 0, boxW, boxH);
			Composite before = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, boxAlpha)));
			g.setColor(BOX);
			g.fill(new RoundRectangle2D.Double(0, 0, boxW, boxH, 8, 8));
			g.setColor(AMBER);
			g.setStroke(new BasicStroke(1.5f));
			g.draw(new RoundRectangle2D.Double(0.75, 0.75, boxW - 1.5, boxH - 1.5, 8, 8));
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			int y = BOX_PAD + fm.getAscent();
			for (String l : lines)
			{
				// a shadow, as the game draws its own text
				g.setColor(Color.BLACK);
				g.drawString(l, BOX_PAD + 1, y + 1);
				g.setColor(BOX_TEXT);
				g.drawString(l, BOX_PAD, y);
				y += fm.getHeight();
			}
			g.setComposite(before);
		}
		else
		{
			boxHit = null;
		}

		drawRing(g, 1, height - BUBBLE_H);
		bubbleHit = new Rectangle(0, height - BUBBLE_H, BUBBLE_D + 2, BUBBLE_H);
		return new Dimension(width, height);
	}

	/**
	 * The amber ring and its "!", drawn flat: an open ring, a bar that tapers from a rounded top to a
	 * narrow foot, and a round dot. Proportions follow the reference icon, as fractions of the
	 * ring's outer radius.
	 */
	private void drawRing(Graphics2D g, int x, int y)
	{
		double cx = x + BUBBLE_D / 2.0;
		double cy = y + BUBBLE_D / 2.0;
		double r = RING_D / 2.0;
		float stroke = (float) Math.max(2.2, r * 0.15);

		Ellipse2D ring = new Ellipse2D.Double(cx - r + stroke / 2, cy - r + stroke / 2, RING_D - stroke, RING_D - stroke);

		double topW = r * 0.27;
		double footW = r * 0.11;
		double top = cy - r * 0.60;
		double foot = cy + r * 0.28;
		Area mark = new Area(new Ellipse2D.Double(cx - topW / 2, top, topW, topW));
		Path2D taper = new Path2D.Double();
		taper.moveTo(cx - topW / 2, top + topW / 2);
		taper.lineTo(cx + topW / 2, top + topW / 2);
		taper.lineTo(cx + footW / 2, foot);
		taper.lineTo(cx - footW / 2, foot);
		taper.closePath();
		mark.add(new Area(taper));
		mark.add(new Area(new Ellipse2D.Double(cx - footW / 2, foot - footW / 2, footW, footW)));
		double dotR = r * 0.14;
		mark.add(new Area(new Ellipse2D.Double(cx - dotR, cy + r * 0.47 - dotR, dotR * 2, dotR * 2)));

		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(stroke));

		// shadow, one pixel down and right
		g.translate(1, 1);
		g.setColor(SHADOW);
		g.draw(ring);
		g.fill(mark);
		g.translate(-1, -1);

		g.setColor(hovered ? AMBER_HOVER : AMBER);
		g.draw(ring);
		g.fill(mark);

		g.setStroke(old);
	}

	private static List<String> wrap(String text, FontMetrics fm, int maxW)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String next = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(next) > maxW && line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(next);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out;
	}
}
