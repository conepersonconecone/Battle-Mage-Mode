# Battle-Mage Mode

A self-imposed challenge mode for Old School RuneScape. Swear an oath to one of four gods (Saradomin, Zamorak, Guthix or Zaros) and play by the Battle-Mage rules: magic runs on a resource called **PKP**, and your gear/gameplay are shaped by the system in correlation to whichever god you serve.

## How it works

- **PKP.** Combat spells cost PKP. Melee and ranged attacks earn it back. Some consumables, special attacks and shields add, drain, or interact with PKP in unique ways.
- **Failure states.** Gaining PKP while the bar is full leaves you **Overloaded** (no melee, ranged or eating until you cast). Dropping below zero leaves you **Depleted** (no drinking until you regenerate). Attacking while Overloaded triggers **Critical Overload**, which also blocks teleports.
- **Equipment.** Weapons need a magic attack bonus above 0, and armour must stay above -10. Each god also has its own aligned and forbidden gear. Items you can't use show a stop symbol, and their Wield/Wear option is removed.
- **Blocking.** Blocking a hit may restore or drain PKP depending on the shield or off-hand you have equipped. It never causes an overload or depletion.
- **Rulebook.** The side panel opens a full rulebook in your browser, with a PKP simulator and the verdict for every listed item under every god.

## Using it

1. Enable **Battle-Mage Mode** and select your faction when prompted on screen. The PKP bar appears and the rules take effect.
2. Open the side panel to view the handbook to learn about PKP, customize the UI components, and denounce/select your faction as is necessary.
3. Look at the bottom of the handbook to view a matrix of equipment with ties to specific factions. Alternatively, just go off vibes, and figure it out on the fly!
4. Have fun flowing through the combat triangle! Figure out what is possible! Try it on a Hardcore if you are daring!
5. Don't forget your Tea Flask and Cowbell Amulet!

## Privacy

The plugin makes no network requests and stores no account details. It saves only your chosen god and your appearance settings, in RuneLite's normal plugin configuration. It does not send any game input. It only reads client state, removes menu options that break the rules, and draws overlays.

## Rules data

Every rule, PKP amount and item list lives in `src/main/resources/battlemage-codex.json`.
