package com.battlemage;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.Player;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModelType;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.game.chatbox.ChatboxInput;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyListener;

/**
 * Game-style conversations in the chatbox: an NPC or the player speaking beside their chat head,
 * "Click here to continue", and "Select an Option" menus - drawn with RuneLite's chatbox panel, so
 * nothing is sent to the game.
 *
 * <p>A conversation is a list of {@link Line}s played in order, followed by whatever runs next
 * (usually a {@link #menu}). Space or a click moves on; Escape closes the chatbox, which simply
 * ends the conversation - nothing waits on it.
 */
@Singleton
class ChatDialogue
{
	/** Chat-head expressions. Each has four variants, one per line count; {@link #anim} picks it. */
	enum Mood
	{
		NEUTRAL(588),
		HAPPY(567),
		LAUGH(605),
		CONFUSED(575),
		QUESTION(554),
		SHOCKED(571),
		ANGRY(614);

		final int base;

		Mood(int base)
		{
			this.base = base;
		}

		/** The variant whose mouth movement lasts as long as the text: one to four lines. */
		int anim(String text)
		{
			int lines = Math.max(1, Math.min(4, (text.length() + CHARS_PER_LINE - 1) / CHARS_PER_LINE));
			return base + lines - 1;
		}
	}

	/** One line of a conversation. */
	static final class Line
	{
		final boolean player;
		final Mood mood;
		final String text;

		private Line(boolean player, Mood mood, String text)
		{
			this.player = player;
			this.mood = mood;
			this.text = text;
		}

		static Line npc(Mood mood, String text)
		{
			return new Line(false, mood, text);
		}

		static Line player(Mood mood, String text)
		{
			return new Line(true, mood, text);
		}
	}

	/** One choice in a "Select an Option" menu. */
	static final class Option
	{
		final String text;
		final Runnable action;

		Option(String text, Runnable action)
		{
			this.text = text;
			this.action = action;
		}
	}

	// The chatbox's own palette and font for dialogue.
	private static final int NAME_COLOR = 0x800000;
	private static final int TEXT_COLOR = 0x000000;
	private static final int CONTINUE_COLOR = 0x0000FF;
	private static final int HOVER_COLOR = 0xFFFFFF;
	private static final int FONT = FontID.QUILL_8;

	/** Rough characters per wrapped line of dialogue, used only to pick the chat-head animation. */
	private static final int CHARS_PER_LINE = 52;

	// Chat-head placement: the speaker's head sits at the side, the words in the space beside it.
	// The NPC is on the left and the player on the right, as in the game.
	/** Distance of each head from its own edge of the chatbox. The NPC's sits 10px further left. */
	private static final int HEAD_X_PLAYER = 46;
	private static final int HEAD_X_NPC = HEAD_X_PLAYER - 10;
	private static final int HEAD_SIZE = 32;
	private static final int HEAD_ZOOM = 796;
	private static final int HEAD_ROT_X = 40;
	private static final int HEAD_ROT_Z_NPC = 1882;
	private static final int HEAD_ROT_Z_PLAYER = 166;
	/** Where the text column starts, measured from the head's side of the chatbox. */
	private static final int TEXT_INSET = 96;
	/** Breathing room on the far side of the text column. */
	private static final int TEXT_MARGIN = 20;

	private final Client client;
	private final ChatboxPanelManager panels;

	@Inject
	ChatDialogue(Client client, ChatboxPanelManager panels)
	{
		this.client = client;
		this.panels = panels;
	}

	/**
	 * Plays these lines in order, then runs {@code after}. With no {@code after} the chatbox closes
	 * once the last line is continued past.
	 *
	 * @param npcId   whose chat head the NPC lines show
	 * @param npcName the name above the NPC lines
	 */
	void play(int npcId, String npcName, List<Line> lines, Runnable after)
	{
		playFrom(npcId, npcName, new ArrayList<>(lines), 0, after);
	}

	void play(int npcId, String npcName, Line line, Runnable after)
	{
		play(npcId, npcName, Collections.singletonList(line), after);
	}

	/** A "Select an Option" menu. Picking an option runs its action; Escape just closes the chatbox. */
	void menu(String title, List<Option> options)
	{
		panels.openInput(new MenuPanel(title, new ArrayList<>(options)));
	}

	/** Ends the conversation that is open. */
	void close()
	{
		panels.close();
	}

	/** Ends the conversation only if one of ours is showing, leaving the game's own dialogues alone. */
	void closeIfOpen()
	{
		ChatboxInput current = panels.getCurrentInput();
		if (current instanceof LinePanel || current instanceof MenuPanel)
		{
			panels.close();
		}
	}

	private void playFrom(int npcId, String npcName, List<Line> lines, int i, Runnable after)
	{
		if (i >= lines.size())
		{
			if (after != null)
			{
				after.run();
			}
			else
			{
				panels.close();
			}
			return;
		}
		Line line = lines.get(i);
		String name = line.player ? playerName() : npcName;
		panels.openInput(new LinePanel(line, npcId, name, () -> playFrom(npcId, npcName, lines, i + 1, after)));
	}

	private String playerName()
	{
		Player p = client.getLocalPlayer();
		String n = p == null ? null : p.getName();
		return n == null ? "" : n.replace(' ', ' ');
	}

	// ------------------------------------------------------------------ one spoken line

	private final class LinePanel extends ChatboxInput implements KeyListener
	{
		private final Line line;
		private final int npcId;
		private final String name;
		private final Runnable next;
		/** A line only moves on once, however many clicks and key presses arrive. */
		private boolean done;

		LinePanel(Line line, int npcId, String name, Runnable next)
		{
			this.line = line;
			this.npcId = npcId;
			this.name = name;
			this.next = next;
		}

		@Override
		protected void open()
		{
			Widget c = panels.getContainerWidget();
			int side = line.player ? WidgetPositionMode.ABSOLUTE_RIGHT : WidgetPositionMode.ABSOLUTE_LEFT;

			Widget head = c.createChild(-1, WidgetType.MODEL);
			if (line.player)
			{
				head.setModelType(WidgetModelType.LOCAL_PLAYER_CHATHEAD);
				head.setModelId(0);
			}
			else
			{
				head.setModelType(WidgetModelType.NPC_CHATHEAD);
				head.setModelId(npcId);
			}
			head.setAnimationId(line.mood.anim(line.text));
			head.setModelZoom(HEAD_ZOOM);
			head.setRotationX(HEAD_ROT_X);
			head.setRotationZ(line.player ? HEAD_ROT_Z_PLAYER : HEAD_ROT_Z_NPC);
			head.setXPositionMode(side);
			head.setOriginalX(line.player ? HEAD_X_PLAYER : HEAD_X_NPC);
			head.setYPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
			head.setOriginalY(0);
			head.setOriginalWidth(HEAD_SIZE);
			head.setOriginalHeight(HEAD_SIZE);
			head.revalidate();

			Widget title = textIn(c, side, NAME_COLOR, name);
			title.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			title.setOriginalY(8);
			title.setOriginalHeight(18);
			title.revalidate();

			Widget body = textIn(c, side, TEXT_COLOR, line.text);
			body.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			body.setOriginalY(28);
			body.setHeightMode(WidgetSizeMode.MINUS);
			body.setOriginalHeight(28 + 30);
			body.setYTextAlignment(WidgetTextAlignment.CENTER);
			body.revalidate();

			Widget cont = textIn(c, side, CONTINUE_COLOR, "Click here to continue");
			cont.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
			cont.setOriginalY(8);
			cont.setOriginalHeight(18);
			cont.setAction(0, "Continue");
			cont.setOnOpListener((JavaScriptCallback) ev -> advance());
			cont.setOnMouseOverListener((JavaScriptCallback) ev -> cont.setTextColor(HOVER_COLOR));
			cont.setOnMouseLeaveListener((JavaScriptCallback) ev -> cont.setTextColor(CONTINUE_COLOR));
			cont.setHasListener(true);
			cont.revalidate();
		}

		private void advance()
		{
			if (done)
			{
				return;
			}
			done = true;
			next.run();
		}

		@Override
		public void keyTyped(KeyEvent e)
		{
			if (!panels.shouldTakeInput())
			{
				return;
			}
			char ch = e.getKeyChar();
			if (ch == ' ')
			{
				e.consume();
				advance();
			}
			else if (ch == '\033')
			{
				e.consume();
				panels.close();
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (panels.shouldTakeInput()
				&& (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ESCAPE))
			{
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	}

	/** A centred line of dialogue text in the column beside the head. */
	private static Widget textIn(Widget c, int side, int color, String text)
	{
		Widget w = c.createChild(-1, WidgetType.TEXT);
		w.setText(text);
		w.setTextColor(color);
		w.setFontId(FONT);
		w.setXPositionMode(side);
		w.setOriginalX(TEXT_INSET);
		w.setWidthMode(WidgetSizeMode.MINUS);
		w.setOriginalWidth(TEXT_INSET + TEXT_MARGIN);
		w.setXTextAlignment(WidgetTextAlignment.CENTER);
		w.setYTextAlignment(WidgetTextAlignment.CENTER);
		return w;
	}

	// ------------------------------------------------------------------ "Select an Option"

	private final class MenuPanel extends ChatboxInput implements KeyListener
	{
		private final String title;
		private final List<Option> options;
		private boolean done;

		MenuPanel(String title, List<Option> options)
		{
			this.title = title;
			this.options = options;
		}

		@Override
		protected void open()
		{
			Widget c = panels.getContainerWidget();

			Widget head = c.createChild(-1, WidgetType.TEXT);
			head.setText(title);
			head.setTextColor(NAME_COLOR);
			head.setFontId(FONT);
			head.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
			head.setOriginalX(0);
			head.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			head.setOriginalY(8);
			head.setOriginalHeight(20);
			head.setWidthMode(WidgetSizeMode.MINUS);
			head.setOriginalWidth(0);
			head.setXTextAlignment(WidgetTextAlignment.CENTER);
			head.setYTextAlignment(WidgetTextAlignment.CENTER);
			head.revalidate();

			// Spread the options evenly down the rest of the box, no further apart than the game does.
			int top = 8 + 20 + 4;
			int room = c.getHeight() - top - 8;
			int step = Math.min(options.size() >= 3 ? 20 : 26, room / Math.max(1, options.size()));
			int y = top + Math.max(0, (room - step * options.size()) / 2);

			for (Option o : options)
			{
				Widget w = c.createChild(-1, WidgetType.TEXT);
				w.setText(o.text);
				w.setTextColor(TEXT_COLOR);
				w.setFontId(FONT);
				w.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
				w.setOriginalX(0);
				w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				w.setOriginalY(y);
				w.setOriginalHeight(step);
				w.setWidthMode(WidgetSizeMode.MINUS);
				w.setOriginalWidth(0);
				w.setXTextAlignment(WidgetTextAlignment.CENTER);
				w.setYTextAlignment(WidgetTextAlignment.CENTER);
				w.setAction(0, "Continue");
				w.setOnOpListener((JavaScriptCallback) ev -> pick(o));
				w.setOnMouseOverListener((JavaScriptCallback) ev -> w.setTextColor(HOVER_COLOR));
				w.setOnMouseLeaveListener((JavaScriptCallback) ev -> w.setTextColor(TEXT_COLOR));
				w.setHasListener(true);
				w.revalidate();
				y += step;
			}
		}

		private void pick(Option o)
		{
			if (done)
			{
				return;
			}
			done = true;
			o.action.run();
		}

		@Override
		public void keyTyped(KeyEvent e)
		{
			if (!panels.shouldTakeInput())
			{
				return;
			}
			char ch = e.getKeyChar();
			if (ch == '\033')
			{
				e.consume();
				panels.close();
				return;
			}
			int n = ch - '1';
			if (n >= 0 && n < options.size())
			{
				e.consume();
				pick(options.get(n));
			}
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (panels.shouldTakeInput() && e.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				e.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}
	}
}
