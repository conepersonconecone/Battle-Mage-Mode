# Battle-Mage Mode

A self-imposed challenge mode for Old School RuneScape. Swear an oath to one of four gods (Saradomin, Zamorak, Guthix or Zaros) and play by the Battle-Mage rules; Magic runs off a new resource called **PKP**, and your progression is shaped in accordance with the god you selected.

## How it works

- **PKP.** Combat spells cost PKP. Melee and ranged attacks earn it back. Some consumables, special attacks and shields add, drain, or interact with PKP in unique ways.
- **Failure states.** Gaining PKP while the bar is full leaves you **Overloaded** (no melee, ranged or eating until you cast). Dropping below zero leaves you **Depleted** (no drinking until you regenerate). Attacking while Overloaded triggers **Critical Overload**, which forces you to exit combat. Only your Cowbell Amulet will be able to teleport you out.
- **Equipment.** Armour and Weapons are Balanced on a per-faction basis to match both the Battle-Mage Archetype, as well as the god-faction the player has opted into. Each faction starts with a basic requirement of +1 Magic Attack for Weapons and -12 or Greater Magic Attack for Equipment. Weapons and Equipment exclusive or aligned to one faction are often banned from use in at least one other faction.
- **Blocking.** Blocking a hit may restore or drain PKP depending on the shield or off-hand you have equipped. It never causes an overload or depletion. Right-click a shield and choose **Toggle PKP** to switch its block PKP off or back on.

## Using it

1. Enable **Battle-Mage Mode**, then head to the **Wise Old Man** in Draynor Village. Stand two tiles directly south of him and **Ring** your **Cowbell amulet**. Choose **Begin Battle-Mage Mode** and select your faction. The PKP bar appears and the rules take effect.
2. Have fun flowing through the combat triangle! Figure out what is possible!
3. Don't forget your Tea Flask and Cowbell Amulet!

## Privacy

The plugin makes no network requests and stores no account details. It saves only your chosen god and your appearance settings, in RuneLite's normal plugin configuration. It does not send any game input. It only reads client state, removes menu options that break the rules, and draws overlays.

## Rules data

Every rule, PKP amount and item list lives in `src/main/resources/battlemage-codex.json`.
