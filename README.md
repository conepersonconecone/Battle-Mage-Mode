# Battle-Mage Mode

A self-imposed challenge mode for Old School RuneScape. Swear an oath to one of four gods (Saradomin, Zamorak, Guthix or Zaros) and play by the Battle-Mage rules: magic runs on a resource called **PKP**, and your gear/gameplay are shaped by the system in correlation to whichever god you serve.

## How it works

- **PKP.** Combat spells cost PKP. Melee and ranged attacks earn it back. Some consumables, special attacks and shields add or drain it.
- **Failure states.** Gaining PKP while the bar is full leaves you **Overloaded** (no melee, ranged or eating until you cast). Dropping below zero leaves you **Depleted** (no drinking until you regenerate). Attacking while Overloaded triggers **Critical Overload**, which also blocks teleports.
- **Equipment.** Weapons need a magic attack bonus above 0, and armour must stay above -10. Each god also has its own aligned and forbidden gear. Items you can't use show a stop symbol, and their Wield/Wear option is removed.
- **Blocking.** Blocking a hit may restore or drain PKP depending on the shield or off-hand you have equipped. It never causes an overload or depletion.
- **Rulebook.** The side panel opens a full rulebook in your browser, with a PKP simulator and the verdict for every listed item under every god.

## Using it

1. Enable **Battle-Mage Mode** and open its side panel (the partyhat icon).
2. Choose your god. The PKP bar appears and the rules take effect.
3. Use **Customize appearance** in the side panel to adjust the bar, text and overlays. RuneLite's own configuration panel has no settings for this plugin.

You can denounce your god at any time and choose another.

## Privacy

The plugin makes no network requests and stores no account details. It saves only your chosen god and your appearance settings, in RuneLite's normal plugin configuration. It does not send any game input. It only reads client state, removes menu options that break the rules, and draws overlays.

## Rules data

Every rule, PKP amount and item list lives in `src/main/resources/battlemage-codex.json`.
