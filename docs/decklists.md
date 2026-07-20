# MVP Decklists

The two fixture decks for the first playable milestone (PLAN.md §6, Phase 6). Card names
oracle-verified against Scryfall on 2026-07-20; all cards confirmed Pauper-legal.

## Mono-Red Madness

**Main (60):**
4 Melded Moxite · 4 Guttersnipe · 4 Sneaky Snacker · 4 Voldaren Epicure · 4 Fiery Temper ·
4 Fireblast · 4 Lava Dart · 4 Lightning Bolt · 4 Grab the Prize · 4 Highway Robbery ·
2 Faithless Looting · 18 Mountain

**Sideboard (15):**
3 Relic of Progenitus · 4 Cast into the Fire · 4 Pyroblast · 4 Cleansing Wildfire

Note: Sneaky Snacker is {U}{B} and is never cast — it is discarded and recurs itself from
the graveyard. Deck legality validation must not assume mono-color mana coverage.

### Mechanics inventory → where each lands in the plan

**Mainboard (required for single-game MVP):**

| Mechanic | Cards | Packet |
|---|---|---|
| Direct damage: any target / each opponent | Bolt, Fiery Temper, Fireblast, Lava Dart, Guttersnipe, Voldaren Epicure | P2.2 |
| ETB triggers, incl. optional ("you may… if you do") | Voldaren Epicure, Melded Moxite | P5.1 |
| Spell-cast triggers | Guttersnipe | P5.1 |
| Triggers functioning from graveyard + per-turn draw counting | Sneaky Snacker | P5.1 (extends zone scope) |
| Predefined tokens (Blood, Robot) | Voldaren Epicure, Melded Moxite | P5.1 (token creation) |
| Activated abilities w/ composite costs ({1}, {T}, discard, sacrifice) | Blood token, Melded Moxite, Relic | P5.1 |
| Madness (discard→exile replacement + reflexive cast-or-graveyard) | Fiery Temper | P5.2 |
| Flashback, incl. non-mana flashback cost (sac a Mountain) | Lava Dart, Faithless Looting | P5.2 (cast-from-graveyard) |
| Plot (exile now, cast free later, sorcery timing) | Highway Robbery | P5.2 (cast-from-exile) |
| Additional cost: discard, with linked info at resolution ("if the discarded card wasn't a land") | Grab the Prize | P2.1 hook, impl P5.x |
| Alternative cost: sacrifice two Mountains | Fireblast | P2.1 hook, impl P5.x |
| Draw-then-discard sequencing | Faithless Looting, Highway Robbery | P5.x |
| Land-type-matters costs (Mountain) | Fireblast, Lava Dart | P2.1 (cost model) |

**Sideboard (deferred — single-game MVP has no sideboarding):**
modal spells (Cast into the Fire, Pyroblast), counterspells with conditional
("counter target spell if it's blue" — Pyroblast), land destruction with controller's
search-a-basic option (Cleansing Wildfire), graveyard exile effects (Relic of Progenitus).

### Design consequences

- The CR 601 casting pipeline (P2.1) must include hooks for **additional costs**,
  **alternative costs**, and **casting from zones other than the hand** from the start,
  even though implementations arrive in Phase 5.
- **Linked information**: what was discarded to pay a cost must be visible to the spell's
  resolution (Grab the Prize). Cost payment results are part of the spell's cast record.
- Tokens and composite activated-ability costs move from "eventually" to **required for MVP**.
- No haste anywhere in this deck; flying only on Sneaky Snacker. The keyword-heavy load
  shifts to the Bogles list.

## GW Bogles

**Main (60):**
4 Gladecover Scout · 4 Silhana Ledgewalker · 4 Slippery Bogle · 4 Abundant Growth ·
4 Ethereal Armor · 4 Rancor · 4 Utopia Sprawl · 4 Ancestral Mask · 4 Armadillo Cloak ·
2 Cartouche of Solidarity · 1 Sentinel's Eyes · 4 Malevolent Rumble · 2 Ash Barrens ·
12 Forest · 3 Plains

**Sideboard (15):**
2 Scattershot Archer · 1 Spirit Link · 1 Lifelink · 3 Ram Through · 3 Gut Shot ·
3 Tamiyo's Safekeeping · 2 Flaring Pain

### Mechanics inventory → where each lands in the plan

**Mainboard (required for single-game MVP):**

| Mechanic | Cards | Packet |
|---|---|---|
| Hexproof (targeting restriction on enumeration) | Gladecover Scout, Silhana Ledgewalker, Slippery Bogle | P5.2 |
| Hybrid mana {G/U} | Slippery Bogle | P1.1 (mana model), P2.1 (payment) |
| Conditional evasion (blockable only by flying) | Silhana Ledgewalker | P3.1 (block legality hook) |
| Auras: enchant creature / land / **Forest (subtype)** / creature-you-control | all auras; Utopia Sprawl; Cartouche | P4.1 |
| Dynamic P/T counting game state (layer 7c) | Ethereal Armor (per enchantment you control), Ancestral Mask (per other enchantment anywhere) | P4.2 |
| Keyword grants via aura (layer 6): first strike, trample, vigilance | Ethereal Armor, Rancor, Armadillo Cloak, Cartouche, Sentinel's Eyes | P4.2 |
| **Ability-granting aura** (grants a mana ability to a land) | Abundant Growth | P4.1 (layer 6 on non-creatures) |
| "As enters, choose a color" (linked choice on permanent) | Utopia Sprawl | P5.1 |
| **Triggered mana ability** (no stack, fires during payment, CR 605.1b) | Utopia Sprawl | P2.1 design + P5.1 impl |
| "Add one mana of any color" sources | Abundant Growth | P2.1 (payment enumeration) |
| Zone-change trigger: graveyard-from-battlefield self-return | Rancor | P5.1 |
| ETB triggers: draw; create token (1/1 vigilance Warrior; 0/1 Eldrazi Spawn with sac-for-{C} mana ability) | Abundant Growth, Cartouche, Malevolent Rumble | P5.1 |
| Escape (cast from graveyard; cost includes exiling two other cards) | Sentinel's Eyes | P5.2 (cast-from-elsewhere) |
| Basic landcycling (activated ability functioning from hand; search + shuffle) | Ash Barrens | P5.1 (zone-scoped abilities) |
| Reveal top 4, select to hand, rest to graveyard | Malevolent Rumble | P5.x (library manipulation) |

**Sideboard (deferred):** tap-to-damage-fliers (Scattershot Archer), damage-dealt triggers
granting life to the *aura's controller* (Spirit Link, Armadillo Cloak's twin — distinct
from the lifelink keyword granted by the card named Lifelink; all three in this 75 make a
perfect test trio), power-based creature damage with trample-conditional excess to player
(Ram Through), **Phyrexian mana {R/P}** (Gut Shot), hexproof+indestructible until EOT
(Tamiyo's Safekeeping), "damage can't be prevented this turn" (Flaring Pain).

### Design consequences

- **The mana system is the sleeper workload.** Between the two decks: hybrid {G/U},
  Phyrexian {R/P} (pay 2 life), any-color sources, colorless {C}, chosen-color linked info,
  and Utopia Sprawl's *triggered mana ability* that fires mid-payment without the stack.
  P1.1's mana types and P2.1's payment-enumeration design must cover all of these shapes
  (implementations may land later, but the model must not preclude them).
- **Cast-from-elsewhere is a framework, not four features**: madness (exile), flashback
  (graveyard), plot (exile), escape (graveyard). P5.2 builds one "casting permission +
  alternative/additional cost" mechanism; each mechanic is data on top.
- **Phase 4 scope is bounded — good news**: everything needed is layers 6 and 7c (ability
  grants + dynamic P/T). No characteristic-defining abilities, no copy/control/type-changing
  effects in either 75. Full CR 613 skeleton, but only these sublayers exercised.
- **Zone-scoped abilities are load-bearing** (CR 113.6): landcycling from hand, escape/
  flashback from graveyard, Sneaky Snacker's graveyard trigger, madness/plot from exile.
- **No haste in either 75.** Drop it from the MVP keyword set. Combat keywords needed:
  first strike, trample, vigilance, flying, hexproof, conditional evasion; plus
  indestructible and lifelink from sideboards later.
