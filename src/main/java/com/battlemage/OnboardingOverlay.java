package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The oath flow: a compact pop-up over the game view.
 *
 * <ul>
 *   <li>{@code GODS} - the four gods as a diamond. Clicking a god
 *       highlights it; <b>Lock-in</b> commits, naming the doctrine rather than the god - you are
 *       choosing an alignment, and the god is who embodies it. Lock-in swears the oath.</li>
 *   <li>{@code TIP} - shown once ever, after the first Lock-in: a short note pointing at the side
 *       panel for the rulebook and for changing faction. <b>Got it</b> closes the flow. Every later
 *       Lock-in closes the flow straight away. Either way the reveal callback fires on close.</li>
 * </ul>
 *
 * <p>Nothing here is permanent - the side panel can change the god again after standing down.
 */
@Singleton
public class OnboardingOverlay extends Overlay
{
	private static final int PAD = 14;
	private static final int CARD_W = 104;
	/** Crest, name, epithet - nothing more. */
	private static final int CARD_H = 82;
	/** The gap between Saradomin and Zamorak. Guthix and Zaros sit centred in it, below. */
	private static final int SPREAD = 34;
	private static final int ROW_GAP = 8;
	private static final int BTN_H = 26;

	private static final Color SCRIM = new Color(6, 8, 11, 216);
	private static final Color PANEL = new Color(24, 27, 33, 250);
	private static final Color PANEL_LINE = new Color(70, 78, 90);
	private static final Color CARD = new Color(32, 36, 43);
	private static final Color EDGE = new Color(62, 69, 80);
	private static final Color INK = new Color(232, 236, 242);
	private static final Color INK_FAINT = new Color(104, 113, 126);

	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 19);
	private static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
	private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);
	private static final Font BTN_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);


	/** Stored beside the oath, so it never shows up as a setting. */
	private static final String KEY_TIP_SHOWN = "sidePanelTipShown";
	private static final String TIP_TITLE = "Your oath is sworn";
	private static final String[] TIP_COPY = {
		"Open the Battle-Mage Mode side panel to read the rulebook, which explains how this game mode works.",
		"",
		"To change factions, use the side panel too: denounce your faction there, then select a new one.",
	};

	private enum Screen
	{
		GODS, TIP
	}

	private final Client client;
	private final Oath oath;
	private final Rules rules;
	private final MouseManager mouseManager;
	private final ConfigManager configManager;

	/** Fired when the flow finishes - the plugin starts the PKP bar's reveal. */
	private Runnable onComplete = () ->
	{
	};

	private Screen screen = Screen.GODS;
	private Faction selected;
	private boolean active;

	private final Map<Faction, Rectangle> factionHits = new LinkedHashMap<>();
	private Rectangle lockHit;
	private Rectangle gotItHit;

	private final MouseAdapter mouseAdapter = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (!active || e.getButton() != MouseEvent.BUTTON1)
			{
				return e;
			}
			if (handleClick(e.getX(), e.getY()))
			{
				e.consume();
			}
			return e;
		}
	};

	@Inject
	private OnboardingOverlay(Client client, Oath oath, Rules rules, MouseManager mouseManager,
		ConfigManager configManager)
	{
		this.client = client;
		this.oath = oath;
		this.rules = rules;
		this.mouseManager = mouseManager;
		this.configManager = configManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGHEST);
	}

	void setOnComplete(Runnable onComplete)
	{
		this.onComplete = onComplete == null ? () ->
		{
		} : onComplete;
	}

	/** Shows the oath flow from its first screen. */
	public void open()
	{
		if (active)
		{
			return;
		}
		active = true;
		screen = Screen.GODS;
		// Start on whatever god is already in force, so changing your mind is a one-click edit
		// rather than starting from a blank screen.
		selected = oath.getFaction();
		mouseManager.registerMouseListener(mouseAdapter);
	}

	public void close()
	{
		if (!active)
		{
			return;
		}
		active = false;
		mouseManager.unregisterMouseListener(mouseAdapter);
	}

	// ------------------------------------------------------------------ input

	private boolean handleClick(int mx, int my)
	{
		if (screen == Screen.GODS)
		{
			for (Map.Entry<Faction, Rectangle> e : factionHits.entrySet())
			{
				if (e.getValue().contains(mx, my))
				{
					pickGod(e.getKey());
					return true;
				}
			}
			if (lockHit != null && lockHit.contains(mx, my) && selected != null)
			{
				lockIn();
				return true;
			}
			return false;
		}

		// TIP
		if (gotItHit != null && gotItHit.contains(mx, my))
		{
			configManager.setConfiguration(Oath.GROUP, KEY_TIP_SHOWN, true);
			finish();
		}
		// Swallow every click on this screen so it never reaches the game behind the pop-up.
		return true;
	}

	/** Closes the flow and starts the PKP bar's reveal. */
	private void finish()
	{
		close();
		onComplete.run();
	}

	private void pickGod(Faction f)
	{
		selected = f;
	}

	/** Swears to the highlighted god, then shows the one-time tip or closes the flow. */
	private void lockIn()
	{
		oath.swear(selected);
		Boolean shown = configManager.getConfiguration(Oath.GROUP, KEY_TIP_SHOWN, Boolean.class);
		if (shown == null || !shown)
		{
			screen = Screen.TIP;
			return;
		}
		finish();
	}

	// ----------------------------------------------------------------- render

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!active)
		{
			return null;
		}
		Rectangle view = viewportBounds();
		if (view == null || view.width < 80 || view.height < 80)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(SCRIM);
		g.fillRect(view.x, view.y, view.width, view.height);

		clearHits();
		if (screen == Screen.GODS)
		{
			renderGods(g, view);
		}
		else
		{
			renderTextScreen(g, view);
		}
		return new Dimension(view.width, view.height);
	}

	private void clearHits()
	{
		factionHits.clear();
		lockHit = null;
		gotItHit = null;
	}

	/**
	 * The god screen.
	 *
	 * <p>The gods are laid out as a diamond: Saradomin and Zamorak facing each other, Guthix centred
	 * between them, Zaros alone at the bottom. It reads as the relationship between them rather than
	 * as a list.
	 *
	 * <p>The panel is measured before it is drawn, walking the same arithmetic the draw pass walks,
	 * so the frame always closes below Lock-in rather than running off the bottom of the viewport.
	 */
	private void renderGods(Graphics2D g, Rectangle view)
	{
		int gridW = CARD_W * 2 + SPREAD;
		int panelW = gridW + PAD * 2;
		int innerW = panelW - PAD * 2;

		int cardBlock = CARD_H;
		int gapBeforeButton = 6;

		int panelH = PAD
			+ 24                                  // title
			+ 6
			+ cardBlock                           // Saradomin and Zamorak
			+ ROW_GAP + cardBlock                 // Guthix
			+ ROW_GAP + cardBlock                 // Zaros
			+ gapBeforeButton
			+ BTN_H                               // Lock in
			+ PAD;

		int px = view.x + (view.width - panelW) / 2;
		int py = view.y + Math.max(4, (view.height - panelH) / 2);
		drawPanel(g, px, py, panelW, panelH);

		int left = px + PAD;
		int centreX = left + (innerW - CARD_W) / 2;
		int y = py + PAD;

		g.setFont(TITLE_FONT);
		FontMetrics tfm = g.getFontMetrics();
		g.setColor(INK);
		g.drawString("Swear an oath", left + (innerW - tfm.stringWidth("Swear an oath")) / 2, y + 15);
		y += 24;

		y += 6;

		drawGodCard(g, Faction.SARADOMIN, left, y);
		drawGodCard(g, Faction.ZAMORAK, left + CARD_W + SPREAD, y);
		y += cardBlock + ROW_GAP;

		drawGodCard(g, Faction.GUTHIX, centreX, y);
		y += cardBlock + ROW_GAP;

		drawGodCard(g, Faction.ZAROS, centreX, y);
		y += cardBlock;

		y += gapBeforeButton;

		lockHit = new Rectangle(left, y, innerW, BTN_H);
		boolean ready = selected != null;
		drawButton(g, lockHit,
			ready ? "Lock-in " + rules.lore(selected).epithet : "Choose Your Alignment",
			ready ? selected.getPrimary() : null, ready);
	}

	private void renderTextScreen(Graphics2D g, Rectangle view)
	{
		String title = TIP_TITLE;
		String[] copy = TIP_COPY;
		Faction f = oath.getFaction();
		Color accent = f != null ? f.getPrimary() : new Color(0x7FA8D8);

		int panelW = CARD_W * 2 + SPREAD + PAD * 2;
		int innerW = panelW - PAD * 2;

		// Drop lines off the end until the panel fits the viewport rather than letting the button
		// slide off the bottom of the screen where it can't be clicked.
		int chrome = PAD + 26 + 14 + BTN_H + PAD;
		int bodyBudget = Math.max(Briefing.LINE, view.height - 8 - chrome);
		String[] shown = copy;
		int bodyH = Briefing.height(g, shown, innerW);
		while (bodyH > bodyBudget && shown.length > 1)
		{
			shown = java.util.Arrays.copyOf(shown, shown.length - 1);
			bodyH = Briefing.height(g, shown, innerW);
		}
		int panelH = chrome + bodyH;

		int px = view.x + (view.width - panelW) / 2;
		int py = view.y + Math.max(4, (view.height - panelH) / 2);
		drawPanel(g, px, py, panelW, panelH);

		int left = px + PAD;
		int y = py + PAD;

		// Centred, matching the god screen, so both screens share one masthead.
		g.setFont(TITLE_FONT);
		FontMetrics tfm = g.getFontMetrics();
		g.setColor(INK);
		g.drawString(title, left + (innerW - tfm.stringWidth(title)) / 2, y + 15);
		y += 26;

		y = Briefing.draw(g, left, y, innerW, shown, accent);
		y += 14;

		gotItHit = new Rectangle(left, y, innerW, BTN_H);
		drawButton(g, gotItHit, "Got it", accent, true);
	}

	// ------------------------------------------------------------- pieces

	private void drawPanel(Graphics2D g, int px, int py, int w, int h)
	{
		g.setColor(PANEL);
		g.fill(new RoundRectangle2D.Double(px, py, w, h, 10, 10));
		g.setColor(PANEL_LINE);
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Double(px, py, w - 1, h - 1, 10, 10));
	}

	private void drawGodCard(Graphics2D g, Faction faction, int x, int y)
	{
		Rectangle card = new Rectangle(x, y, CARD_W, CARD_H);
		factionHits.put(faction, card);
		drawFactionCard(g, faction, card);
	}

	private void drawButton(Graphics2D g, Rectangle r, String label, Color fill, boolean enabled)
	{
		Color bg = fill != null ? fill : CARD;
		g.setColor(enabled ? bg : new Color(40, 44, 51));
		g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 6, 6));
		g.setColor(enabled ? (fill != null ? fill : EDGE) : EDGE);
		g.setStroke(new BasicStroke(1f));
		g.draw(new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 6, 6));

		g.setFont(BTN_FONT);
		FontMetrics fm = g.getFontMetrics();
		g.setColor(!enabled ? INK_FAINT : (fill != null ? contrastOn(fill) : INK));
		g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + r.height / 2 + 5);
	}

	private void drawFactionCard(Graphics2D g, Faction faction, Rectangle r)
	{
		boolean isSelected = faction == selected;
		Color accent = faction.getPrimary();

		g.setColor(CARD);
		g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 8, 8));
		if (isSelected)
		{
			g.setColor(FactionSigils.alpha(accent, 46));
			g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 8, 8));
		}
		g.setColor(isSelected ? accent : EDGE);
		g.setStroke(new BasicStroke(isSelected ? 2f : 1f));
		g.draw(new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 8, 8));

		ContentPack.Lore lore = rules.lore(faction);

		FactionSigils.drawFaction(g, faction, r.x + (r.width - 38) / 2, r.y + 5, 38);

		g.setFont(NAME_FONT);
		FontMetrics fm = g.getFontMetrics();
		g.setColor(INK);
		centre(g, fm, lore.displayName, r, r.y + 58);

		// The one word of doctrine, and nothing else - the card is a choice, not a briefing.
		g.setFont(LABEL_FONT);
		fm = g.getFontMetrics();
		g.setColor(FactionSigils.alpha(accent, 235));
		centre(g, fm, lore.epithet.toUpperCase(), r, r.y + 72);
	}

	// ------------------------------------------------------------------ util

	/**
	 * The 3D game view, in canvas coordinates - the same resolution IllegalBehaviorOverlay uses, so
	 * both full-view overlays land in exactly the same place in fixed and resizable modes.
	 */
	private Rectangle viewportBounds()
	{
		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		if (vw <= 0 || vh <= 0)
		{
			vx = 0;
			vy = 0;
			vw = client.getCanvasWidth();
			vh = client.getCanvasHeight();
		}
		return (vw <= 0 || vh <= 0) ? null : new Rectangle(vx, vy, vw, vh);
	}

	private static void centre(Graphics2D g, FontMetrics fm, String text, Rectangle r, int baseline)
	{
		g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, baseline);
	}

	private static Color contrastOn(Color bg)
	{
		int luma = (int) (bg.getRed() * 0.299 + bg.getGreen() * 0.587 + bg.getBlue() * 0.114);
		return luma > 150 ? new Color(16, 18, 22) : Color.WHITE;
	}
}
