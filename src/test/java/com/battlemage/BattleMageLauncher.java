package com.battlemage;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development launcher: starts a normal RuneLite client with Battle-Mage Mode already loaded.
 *
 * <p>In IntelliJ, prefer the Gradle task <b>run</b> over running this class directly: the task passes
 * {@code -ea} and {@code --developer-mode} for you. {@link ExternalPluginManager#loadBuiltin} throws
 * "Assertions are not enabled, add '-ea' to your VM options" if it is launched without them, so the
 * failure is loud rather than mysterious - but it is still a failure.
 *
 * <p>This class lives in the test source set and is not part of the side-loaded jar.
 */
public class BattleMageLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BattleMagePlugin.class);
		RuneLite.main(args);
	}
}
