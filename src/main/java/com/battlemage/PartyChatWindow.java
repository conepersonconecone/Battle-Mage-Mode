package com.battlemage;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The party chat, popped out of the side panel into its own small window: the session's lines with
 * their times, and a box to type in. Enter sends. Plain text only. Closing it just hides it; the log
 * carries on in the background.
 */
final class PartyChatWindow extends JDialog
{
	private final PartySync party;
	private final JTextArea log = new JTextArea();
	private final JTextField input = new JTextField();

	PartyChatWindow(Window owner, PartySync party)
	{
		super(owner, "Battle Mage party chat", ModalityType.MODELESS);
		this.party = party;
		setDefaultCloseOperation(HIDE_ON_CLOSE);
		setMinimumSize(new Dimension(260, 200));
		setSize(new Dimension(340, 360));

		log.setEditable(false);
		log.setLineWrap(true);
		log.setWrapStyleWord(true);
		log.setFont(FontManager.getRunescapeFont());
		log.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		log.setForeground(Color.WHITE);
		log.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		JScrollPane scroll = new JScrollPane(log);
		scroll.setBorder(BorderFactory.createEmptyBorder());

		input.setFont(FontManager.getRunescapeFont());
		input.setToolTipText("Enter to send. Up to " + PartyChat.MAX_LENGTH + " characters, one line a second."
			+ " Type /mute Name to stop seeing someone's lines (again to undo).");
		input.setDocument(new javax.swing.text.PlainDocument()
		{
			@Override
			public void insertString(int offs, String str, javax.swing.text.AttributeSet a)
				throws javax.swing.text.BadLocationException
			{
				if (str != null && getLength() + str.length() <= PartyChat.MAX_LENGTH)
				{
					super.insertString(offs, str, a);
				}
			}
		});
		input.addActionListener(e ->
		{
			final String typed = input.getText().trim();
			// "/mute Name" stops (or restarts) one member's lines reaching you, until you leave the party
			if (typed.toLowerCase().startsWith("/mute "))
			{
				if (!party.toggleMute(typed.substring(6).trim()))
				{
					party.chat().add(null, "Nobody in the party is called " + PartyChat.clean(typed.substring(6)), true);
				}
				input.setText("");
				return;
			}
			if (party.sendChat(typed))
			{
				input.setText("");
			}
		});

		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		getContentPane().setLayout(new BorderLayout(0, 4));
		// a standing reminder: everyone in the party reads this
		javax.swing.JLabel warn = new javax.swing.JLabel("Everyone in your party sees this. Do not share private info.",
			javax.swing.SwingConstants.CENTER);
		warn.setFont(FontManager.getRunescapeSmallFont());
		warn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		warn.setBorder(BorderFactory.createEmptyBorder(3, 4, 0, 4));
		getContentPane().add(warn, BorderLayout.NORTH);
		getContentPane().add(scroll, BorderLayout.CENTER);
		getContentPane().add(input, BorderLayout.SOUTH);

		addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowActivated(WindowEvent e)
			{
				party.chat().markRead();
			}
		});
		party.chat().listen(() -> SwingUtilities.invokeLater(this::refresh));
		refresh();
	}

	/** Rewrites the log from the chat's lines, keeping the view at the bottom. */
	private void refresh()
	{
		StringBuilder b = new StringBuilder();
		for (PartyChat.Line l : party.chat().lines())
		{
			if (b.length() > 0)
			{
				b.append('\n');
			}
			b.append(l);
		}
		String text = b.toString();
		if (!text.equals(log.getText()))
		{
			log.setText(text);
			log.setCaretPosition(log.getDocument().getLength());
		}
		if (isVisible() && isActive())
		{
			party.chat().markRead();
		}
	}

	/** Shows the window (beside its owner the first time) and puts the cursor in the box. */
	void open()
	{
		if (!isVisible())
		{
			if (getOwner() != null && getX() == 0 && getY() == 0)
			{
				setLocationRelativeTo(getOwner());
			}
			setVisible(true);
		}
		toFront();
		input.requestFocusInWindow();
		party.chat().markRead();
	}
}
