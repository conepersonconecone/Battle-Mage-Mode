package com.battlemage;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;

/**
 * The one-time pop-in the PKP bar plays when a player finishes the oath.
 *
 * <p>Both overlays draw from their own origin, so the whole effect is a scale about the centre of
 * their box plus a fade. {@link #apply} stashes nothing - the caller saves the transform and
 * composite and hands them back to {@link #restore}, which keeps this usable on the shared
 * {@code Graphics2D} an overlay is given without creating and disposing a copy each frame.
 */
final class RevealAnim
{
	/** How long the pop-in runs. Long enough to read as deliberate, short enough not to annoy. */
	static final long DURATION_MS = 700L;

	private RevealAnim()
	{
	}

	/**
	 * Scales about the box's centre and fades in.
	 *
	 * @param progress 0 at the start, 1 when finished; values &gt;= 1 are a no-op
	 */
	static void apply(Graphics2D g, double progress, int w, int h)
	{
		if (progress >= 1.0)
		{
			return;
		}
		double t = Math.max(0.0, progress);
		// A small overshoot past full size, so it lands rather than merely stopping.
		double scale = 0.55 + 0.45 * easeOutBack(t);
		float alpha = (float) Math.max(0.0, Math.min(1.0, t * 1.7));

		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		g.translate(w / 2.0, h / 2.0);
		g.scale(scale, scale);
		g.translate(-w / 2.0, -h / 2.0);
	}

	static void restore(Graphics2D g, AffineTransform savedTransform, Composite savedComposite)
	{
		g.setTransform(savedTransform);
		g.setComposite(savedComposite);
	}

	/** Overshoots ~10% then settles. */
	private static double easeOutBack(double t)
	{
		double c1 = 1.70158;
		double c3 = c1 + 1.0;
		double p = t - 1.0;
		return 1.0 + c3 * p * p * p + c1 * p * p;
	}
}
