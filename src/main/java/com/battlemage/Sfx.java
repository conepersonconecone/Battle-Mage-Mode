package com.battlemage;

import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;

/**
 * The plugin's own sound effects, played off a bundled WAV rather than the client's sound engine.
 *
 * <p>RuneLite's own sound ids are the game's, and this sting is not one of them, so it is shipped as
 * a resource and handed to {@code javax.sound.sampled}. The file is 16-bit PCM mono at 44.1 kHz,
 * which is the one format every JVM can open with no extra library: an MP3 or an Ogg would need a
 * service provider that is not on the classpath, and would fail at the worst moment - silently, on
 * someone else's machine.
 *
 * <p><b>Nothing here is allowed to break the plugin.</b> Sound is decoration: a missing mixer, a
 * machine with no audio device, a resource that failed to package, an exclusive-mode device held by
 * something else - every one of those is caught and swallowed, and the plugin carries on in silence.
 * The one thing that is NOT swallowed is a repeat: a clip already playing is restarted rather than
 * layered, so a double-fire cannot stack into noise.
 */
@Slf4j
final class Sfx
{
	/** The oath sting: played once, when the PKP bar first appears after a faction is chosen. */
	static final String OATH_REVEAL = "/sfx/oath-reveal.wav";

	private static Clip oathReveal;
	private static boolean oathRevealFailed;

	private Sfx()
	{
	}

	/**
	 * Play a bundled effect, at the volume the player configured.
	 *
	 * @param path   resource path of the WAV
	 * @param volume 0-100, where 0 means do not play at all
	 */
	static void play(String path, int volume)
	{
		if (volume <= 0)
		{
			return;
		}
		Clip clip = clipFor(path);
		if (clip == null)
		{
			return;
		}
		try
		{
			setVolume(clip, volume);
			// A clip that is still running is rewound rather than left to overlap itself, so two
			// triggers in quick succession sound like one effect instead of a smear.
			clip.stop();
			clip.setFramePosition(0);
			clip.start();
		}
		catch (Exception | LinkageError e)
		{
			log.debug("[BATTLE-MAGE] could not play {}", path, e);
		}
	}

	/** Opens and caches the clip. Returns null once, and stays null, if it cannot be opened. */
	private static synchronized Clip clipFor(String path)
	{
		if (!OATH_REVEAL.equals(path))
		{
			return null;
		}
		if (oathReveal != null || oathRevealFailed)
		{
			return oathReveal;
		}
		try (InputStream raw = Sfx.class.getResourceAsStream(path))
		{
			if (raw == null)
			{
				log.debug("[BATTLE-MAGE] sound resource missing: {}", path);
				oathRevealFailed = true;
				return null;
			}
			// AudioSystem needs a stream it can mark/reset to read the header, which the raw
			// resource stream does not promise.
			try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw)))
			{
				Clip clip = AudioSystem.getClip();
				clip.open(in);
				oathReveal = clip;
				return clip;
			}
		}
		catch (Exception | LinkageError e)
		{
			// No mixer, no audio device, an unsupported format, a locked device: all the same
			// outcome. Latch the failure so a silent machine is not retried on every reveal.
			log.debug("[BATTLE-MAGE] no audio for {}", path, e);
			oathRevealFailed = true;
			return null;
		}
	}

	/**
	 * Maps 0-100 onto the clip's own gain range.
	 *
	 * <p>Gain is in decibels, so the mapping is logarithmic rather than linear - a linear one makes
	 * the top of the slider do almost nothing and the bottom drop off a cliff. Volume 100 is the
	 * clip's natural level, not the mixer's maximum, so the sting cannot be louder than it was
	 * recorded.
	 */
	private static void setVolume(Clip clip, int volume)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		int v = Math.max(1, Math.min(100, volume));
		float db = (float) (20.0 * Math.log10(v / 100.0));
		gain.setValue(Math.max(gain.getMinimum(), Math.min(0f, db)));
	}

	/** Releases the cached clip. Called when the plugin shuts down. */
	static synchronized void dispose()
	{
		if (oathReveal != null)
		{
			try
			{
				oathReveal.stop();
				oathReveal.close();
			}
			catch (Exception e)
			{
				log.debug("[BATTLE-MAGE] could not close the oath clip", e);
			}
			oathReveal = null;
		}
		oathRevealFailed = false;
	}
}
