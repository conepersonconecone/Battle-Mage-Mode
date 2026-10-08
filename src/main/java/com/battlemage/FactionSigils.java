package com.battlemage;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * Draws the crest for each god.
 *
 * <p>Each faction looks for its own image on the classpath at {@code /sigils/<faction>.png} -
 * {@code saradomin.png}, {@code guthix.png}, {@code zamorak.png}, {@code zaros.png} - and draws that
 * if it finds one. Drop a file in {@code src/main/resources/sigils/} and every crest in the plugin
 * picks it up at once: the oath cards and the side panel.
 *
 * <p>All four gods ship with artwork. A god whose image is missing shows just the shared ring.
 */
@Slf4j
final class FactionSigils
{
	/** Where a faction's artwork lives on the classpath. */
	private static final String SIGIL_PATH = "/sigils/%s.png";

	/**
	 * Loaded artwork, keyed by faction. A key present with a null value means "looked, found
	 * nothing" - the lookup happens once per faction per session, not once per frame.
	 */
	private static final Map<Faction, BufferedImage> ART = new EnumMap<>(Faction.class);

	private FactionSigils()
	{
	}

	/**
	 * The faction's artwork, or null when its image is missing.
	 *
	 * <p>Looked up under the lower-case faction name first and the upper-case one second, so a file
	 * named either way is found. A file that fails to decode is treated as absent and logged once.
	 */
	private static synchronized BufferedImage art(Faction faction)
	{
		if (ART.containsKey(faction))
		{
			return ART.get(faction);
		}
		BufferedImage loaded = null;
		for (String name : new String[]{faction.name().toLowerCase(Locale.ROOT), faction.name()})
		{
			try (InputStream in = FactionSigils.class.getResourceAsStream(
				String.format(SIGIL_PATH, name)))
			{
				if (in != null)
				{
					loaded = ImageIO.read(in);
					if (loaded != null)
					{
						break;
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Could not read the {} sigil; showing the ring only", faction, e);
			}
		}
		ART.put(faction, loaded);
		return loaded;
	}

	/**
	 * One line naming which gods found their artwork.
	 *
	 * <p>Logged once at start-up, so "did my PNG get picked up?" has an answer. Resources are packed
	 * at build time - a file dropped in beside a running client is not in its jar.
	 */
	static String crestReport()
	{
		StringBuilder sb = new StringBuilder();
		for (Faction f : Faction.values())
		{
			sb.append(sb.length() == 0 ? "" : "  ")
				.append(f.name().toLowerCase(Locale.ROOT))
				.append('=')
				.append(art(f) != null ? "found" : "MISSING");
		}
		return sb.toString();
	}

	/** Draws a faction crest centred in a square of {@code size} at {@code (x, y)}. */
	static void drawFaction(Graphics2D g, Faction faction, int x, int y, int size)
	{
		Graphics2D gg = (Graphics2D) g.create();
		gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		gg.translate(x, y);
		double s = size / 48.0;
		gg.scale(s, s);

		// Every crest sits inside the same ring, so the four read as one set.
		gg.setColor(alpha(faction.getPrimary(), 110));
		gg.setStroke(new BasicStroke(1.4f));
		gg.draw(new Ellipse2D.Double(2, 2, 44, 44));

		BufferedImage img = art(faction);
		if (img != null)
		{
			// Fitted to a 34-unit box inside the 48-unit crest space, aspect preserved.
			final double box = 34.0;
			double scale = Math.min(box / img.getWidth(), box / img.getHeight());
			int iw = (int) Math.round(img.getWidth() * scale);
			int ih = (int) Math.round(img.getHeight() * scale);
			gg.drawImage(img, 24 - iw / 2, 24 - ih / 2, iw, ih, null);
		}
		gg.dispose();
	}

	// ------------------------------------------------------------------ colour

	static Color alpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
	}
}
