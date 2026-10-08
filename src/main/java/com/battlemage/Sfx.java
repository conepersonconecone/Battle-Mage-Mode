package com.battlemage;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * The plugin's own sound effects, played from a bundled WAV through RuneLite's {@link AudioPlayer}.
 *
 * <p>The oath sting is not one of the game's own sounds, so it ships as a resource. The file is 16-bit
 * PCM mono at 44.1 kHz, which every JVM can decode with no extra library.
 *
 * <p><b>Nothing here is allowed to break the plugin.</b> Sound is decoration: a machine with no audio
 * device, a missing resource or a busy output line is logged at debug level and the plugin carries on
 * in silence.
 */
@Slf4j
@Singleton
class Sfx
{
	/** The oath sting: played once, when the PKP bar first appears after a faction is chosen. */
	static final String OATH_REVEAL = "/sfx/oath-reveal.wav";

	private final AudioPlayer audioPlayer;

	@Inject
	Sfx(AudioPlayer audioPlayer)
	{
		this.audioPlayer = audioPlayer;
	}

	/**
	 * Play a bundled effect.
	 *
	 * @param path   resource path of the WAV
	 * @param volume 0-100, where 0 means do not play at all and 100 is the file's own level
	 */
	void play(String path, int volume)
	{
		if (volume <= 0)
		{
			return;
		}
		// Gain is in decibels, so the 0-100 volume maps logarithmically: 100 = 0 dB (as recorded).
		int v = Math.max(1, Math.min(100, volume));
		float gainDb = (float) (20.0 * Math.log10(v / 100.0));
		try
		{
			audioPlayer.play(Sfx.class, path, gainDb);
		}
		catch (Exception e)
		{
			log.debug("[BATTLE-MAGE] could not play {}", path, e);
		}
	}
}
