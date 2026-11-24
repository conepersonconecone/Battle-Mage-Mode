package com.battlemage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.party.PartyService;

/**
 * Your party picture: an image you choose from your computer, shown in your square in every
 * teammate's Party section (and in RuneLite's own party views, which read the same picture).
 *
 * <p>The image is cropped to a square from its middle and shrunk to at most {@link #MAX_SIZE}
 * pixels a side, then kept in {@code .runelite/battle-mage-mode/party-picture.png}, so it survives
 * restarts without going into your synced RuneLite settings. It is sent to the party as a JPEG in
 * Base64 pieces of {@link #CHUNK} characters; teammates put the pieces back together.
 *
 * <p>A teammate's picture beats a Discord profile picture for the same person: if RuneLite's Discord
 * plugin sets one, theirs is put back.
 */
@Slf4j
@Singleton
class PartyAvatar
{
	/** Largest side kept on your computer, in pixels. */
	static final int MAX_SIZE = 512;
	/**
	 * Largest side sent to the party. The square it fills is about 32 pixels, so 128 is still four
	 * times sharper than shown, at a fraction of the size of the 512 original (one or two messages).
	 */
	static final int SEND_SIZE = 128;
	/** Characters of Base64 per party message. */
	static final int CHUNK = 12_000;
	/**
	 * No picture is accepted in more pieces than this (48 KB). A 128px JPEG at this quality is about
	 * 15 KB at most, so this only ever stops pictures from a tampered client.
	 */
	private static final int MAX_CHUNKS = 4;
	/** Largest file accepted from your computer. */
	private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
	/** Largest picture accepted from your computer, in pixels (about 8000 x 5000), checked before it is opened. */
	private static final long MAX_FILE_PIXELS = 40_000_000L;
	/** A teammate's picture is taken at most this often (a tampered client can't make you decode a stream). */
	private static final long RECEIVE_GAP_MS = 20_000L;
	/** Your picture is sent again (for people joining) at most this often; your own changes go at once. */
	private static final long RESEND_GAP_MS = 60_000L;
	private static final float JPEG_QUALITY = 0.88f;

	private static final File DIR = new File(RuneLite.RUNELITE_DIR, "battle-mage-mode");
	private static final File FILE = new File(DIR, "party-picture.png");

	private final PartyService party;

	/** Your picture, or null. */
	private volatile BufferedImage mine;
	/** Your picture as sent: Base64 JPEG pieces, and its version number. */
	private List<String> pieces = new ArrayList<>();
	private int version;
	/** Your own square currently shows your picture (so removing it should clear the square). */
	private boolean showingMine;

	/** When each teammate's last picture was taken in, for {@link #RECEIVE_GAP_MS}. */
	private final Map<Long, Long> lastTaken = new ConcurrentHashMap<>();
	/** When yours was last sent, and whether it changed since. */
	private long lastSentMs;
	private boolean changedSinceSend = true;
	/** Viewers can turn teammates' pictures off. */
	private volatile boolean showTheirs = true;

	/** Teammates' finished pictures, by member id. */
	private final Map<Long, BufferedImage> theirs = new ConcurrentHashMap<>();
	/** Teammates' pictures still arriving, by member id. */
	private final Map<Long, Incoming> arriving = new HashMap<>();

	private static final class Incoming
	{
		final int version;
		final String[] parts;
		int have;

		Incoming(int version, int count)
		{
			this.version = version;
			this.parts = new String[count];
		}
	}

	@Inject
	PartyAvatar(PartyService party)
	{
		this.party = party;
	}

	/** Loads your saved picture (plugin start). */
	void load()
	{
		try
		{
			if (FILE.isFile())
			{
				setMine(readSafely(new FileInputStream(FILE), (long) MAX_SIZE * MAX_SIZE, MAX_SIZE, null));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Couldn't read the party picture", e);
		}
	}

	BufferedImage mine()
	{
		return mine;
	}

	/**
	 * Uses this image file as your picture: cropped square, shrunk to {@link #MAX_SIZE}, saved.
	 * Throws with a readable message when the file isn't an image.
	 */
	void choose(File file) throws IOException
	{
		if (file == null || !file.isFile())
		{
			throw new IOException("That isn't a file.");
		}
		if (file.length() > MAX_FILE_BYTES)
		{
			throw new IOException("That picture is too big (the limit is 15 MB).");
		}
		// size checked from the header before anything is decoded; an animated GIF gives its first frame
		final BufferedImage raw = readSafely(new FileInputStream(file), MAX_FILE_PIXELS, Integer.MAX_VALUE, null);
		if (raw == null)
		{
			throw new IOException("That file isn't a picture RuneLite can read, or it is too large (try PNG or JPG).");
		}
		final BufferedImage square = squareAndShrink(raw);
		if (!DIR.isDirectory() && !DIR.mkdirs())
		{
			throw new IOException("Couldn't create " + DIR);
		}
		ImageIO.write(square, "png", FILE);
		setMine(square);
	}

	/** Removes your picture. */
	void remove()
	{
		if (FILE.isFile() && !FILE.delete())
		{
			log.debug("Couldn't delete {}", FILE);
		}
		setMine(null);
	}

	private void setMine(BufferedImage img)
	{
		mine = img;
		changedSinceSend = true;
		pieces = new ArrayList<>();
		version = 0;
		if (img == null)
		{
			return;
		}
		try
		{
			BufferedImage sent = img;
			// halve in steps for a clean small copy
			while (sent.getWidth() / 2 >= SEND_SIZE)
			{
				sent = scale(sent, sent.getWidth() / 2);
			}
			if (sent.getWidth() > SEND_SIZE)
			{
				sent = scale(sent, SEND_SIZE);
			}
			final String b64 = Base64.getEncoder().encodeToString(jpeg(sent));
			for (int at = 0; at < b64.length(); at += CHUNK)
			{
				pieces.add(b64.substring(at, Math.min(b64.length(), at + CHUNK)));
			}
			version = b64.hashCode() | 1;
		}
		catch (IOException e)
		{
			log.debug("Couldn't encode the party picture", e);
			pieces = new ArrayList<>();
		}
	}

	/**
	 * Sends your picture (or that you have none) to the party: straight away after you change it, and
	 * otherwise (people joining) no more than once a minute, so join requests can't make it repeat.
	 */
	void send()
	{
		if (!party.isInParty() || party.getLocalMember() == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (!changedSinceSend && now - lastSentMs < RESEND_GAP_MS)
		{
			return;
		}
		changedSinceSend = false;
		lastSentMs = now;
		final long me = party.getLocalMember().getMemberId();
		if (mine == null || pieces.isEmpty())
		{
			party.send(new BattleMagePartyAvatar(0, 0, 0, null, true));
			if (showingMine)
			{
				showingMine = false;
				party.setPartyMemberAvatar(me, null);
			}
			return;
		}
		for (int i = 0; i < pieces.size(); i++)
		{
			party.send(new BattleMagePartyAvatar(version, i, pieces.size(), pieces.get(i), false));
		}
		showingMine = true;
		party.setPartyMemberAvatar(me, mine);
	}

	/** A piece of a teammate's picture arrived (websocket thread). */
	void receive(BattleMagePartyAvatar e)
	{
		final long id = e.getMemberId();
		if (!showTheirs)
		{
			return;
		}
		if (e.c)
		{
			synchronized (arriving)
			{
				arriving.remove(id);
			}
			if (theirs.remove(id) != null)
			{
				party.setPartyMemberAvatar(id, null);
			}
			return;
		}
		if (e.n <= 0 || e.n > MAX_CHUNKS || e.i < 0 || e.i >= e.n || e.d == null || e.d.length() > CHUNK)
		{
			return;
		}
		final String whole;
		synchronized (arriving)
		{
			Incoming in = arriving.get(id);
			if (in == null || in.version != e.v || in.parts.length != e.n)
			{
				in = new Incoming(e.v, e.n);
				arriving.put(id, in);
			}
			if (in.parts[e.i] == null)
			{
				in.parts[e.i] = e.d;
				in.have++;
			}
			if (in.have < in.parts.length)
			{
				return;
			}
			arriving.remove(id);
			whole = String.join("", in.parts);
		}
		final long now = System.currentTimeMillis();
		final Long last = lastTaken.get(id);
		if (last != null && now - last < RECEIVE_GAP_MS)
		{
			return;
		}
		lastTaken.put(id, now);
		try
		{
			// JPEG only, and no bigger than what Battle Mage Mode sends, checked before decoding
			final BufferedImage img = readSafely(new ByteArrayInputStream(Base64.getDecoder().decode(whole)),
				(long) SEND_SIZE * SEND_SIZE, SEND_SIZE, "jpeg");
			if (img != null)
			{
				theirs.put(id, img);
				party.setPartyMemberAvatar(id, img);
			}
		}
		catch (IOException | IllegalArgumentException ex)
		{
			log.debug("Bad party picture from {}", id, ex);
		}
	}

	/**
	 * Something else (RuneLite's Discord plugin) set a picture for a member who uploaded their own:
	 * put theirs back. Returns true if it did.
	 */
	boolean reassert(long memberId, BufferedImage shown)
	{
		final BufferedImage want = theirs.get(memberId);
		if (want != null && shown != want)
		{
			party.setPartyMemberAvatar(memberId, want);
			return true;
		}
		return false;
	}

	/** A member left, or the party changed: forget their picture. */
	void forget(long memberId)
	{
		theirs.remove(memberId);
		lastTaken.remove(memberId);
		synchronized (arriving)
		{
			arriving.remove(memberId);
		}
	}

	/** Viewer setting: show teammates' pictures. Turning it off clears the ones shown. */
	void setShowTheirs(boolean on)
	{
		if (on == showTheirs)
		{
			return;
		}
		showTheirs = on;
		if (!on)
		{
			for (Long id : new ArrayList<>(theirs.keySet()))
			{
				theirs.remove(id);
				party.setPartyMemberAvatar(id, null);
			}
			synchronized (arriving)
			{
				arriving.clear();
			}
		}
	}

	/**
	 * Reads a picture only after checking its size in the header, so a file that claims to be enormous
	 * is never decoded. {@code format} limits it to one kind ("jpeg"), or null for any. Only the first
	 * frame of an animation is read. Returns null for anything refused or unreadable.
	 */
	static BufferedImage readSafely(InputStream source, long maxPixels, int maxSide, String format) throws IOException
	{
		try (InputStream src = source; ImageInputStream in = ImageIO.createImageInputStream(src))
		{
			if (in == null)
			{
				return null;
			}
			final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext())
			{
				return null;
			}
			final ImageReader reader = readers.next();
			try
			{
				if (format != null && !format.equalsIgnoreCase(reader.getFormatName()))
				{
					return null;
				}
				reader.setInput(in, true, true);
				final int w = reader.getWidth(0);
				final int h = reader.getHeight(0);
				if (w <= 0 || h <= 0 || w > maxSide || h > maxSide || (long) w * h > maxPixels)
				{
					return null;
				}
				return reader.read(0);
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	void forgetAll()
	{
		showingMine = false;
		changedSinceSend = true;
		theirs.clear();
		lastTaken.clear();
		synchronized (arriving)
		{
			arriving.clear();
		}
	}

	/** Pieces of pictures from members who are no longer in the party. */
	void tidy()
	{
		synchronized (arriving)
		{
			for (Iterator<Long> it = arriving.keySet().iterator(); it.hasNext(); )
			{
				if (party.getMemberById(it.next()) == null)
				{
					it.remove();
				}
			}
		}
	}

	/** The middle square of the image, shrunk to at most MAX_SIZE, smoothly. */
	static BufferedImage squareAndShrink(BufferedImage raw)
	{
		final int side = Math.min(raw.getWidth(), raw.getHeight());
		final int x = (raw.getWidth() - side) / 2;
		final int y = (raw.getHeight() - side) / 2;
		final int out = Math.min(side, MAX_SIZE);
		BufferedImage cur = raw.getSubimage(x, y, side, side);
		int size = side;
		// halve in steps for a clean result from big photos, then the last step to size
		while (size / 2 >= out)
		{
			size /= 2;
			cur = scale(cur, size);
		}
		return size == out && cur.getType() == BufferedImage.TYPE_INT_ARGB ? cur : scale(cur, out);
	}

	static BufferedImage scale(BufferedImage src, int size)
	{
		final BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = dst.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, 0, 0, size, size, null);
		g.dispose();
		return dst;
	}

	/** The image as a JPEG (transparent areas filled with the panel's dark grey). */
	static byte[] jpeg(BufferedImage img) throws IOException
	{
		final BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = rgb.createGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
		g.drawImage(img, 0, 0, null);
		g.dispose();

		final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext())
		{
			throw new IOException("No JPEG writer");
		}
		final ImageWriter writer = writers.next();
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes))
		{
			writer.setOutput(out);
			final ImageWriteParam p = writer.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(rgb, null, null), p);
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}
}
