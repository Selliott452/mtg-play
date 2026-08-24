#!/usr/bin/env python3
"""Regenerate mtg-pauper's trimmed Scryfall snapshot.

The snapshot (``mtg-pauper/src/main/resources/scryfall-mvp.json``) is the offline card database
``ScryfallIngest`` parses. It must cover every card named by every bundled decklist resource, plus
the handful of cards that exist only as test fixtures (Grizzly Bears and friends), because
``DeckLoader`` fails loudly on an unknown name.

Usage (Python 3.9+, standard library only, no third-party dependencies)::

    python tools/fetch_scryfall_snapshot.py

The script:

1. collects every distinct card name from ``mtg-pauper/src/main/resources/decks/**/*.deck``;
2. unions in the names already present in the existing snapshot, so fixture-only cards survive a
   regeneration;
3. fetches them from Scryfall's ``/cards/collection`` endpoint, 75 identifiers per request, with a
   descriptive User-Agent and a courtesy delay between requests (Scryfall's rate-limit guidance);
4. fails loudly on any name Scryfall reports in ``not_found`` — the snapshot is never written with
   a card silently missing;
5. writes the snapshot with exactly the fields ``ScryfallIngest`` reads, plus the top-level
   ``source`` attribution string required by the data's CC BY 4.0 license (ADR-003).

Card data is © Scryfall contributors and used under CC BY 4.0
(https://creativecommons.org/licenses/by/4.0/). See the README's "Card data attribution" section.
"""

from __future__ import annotations

import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DECKS_DIR = os.path.join(REPO_ROOT, "mtg-pauper", "src", "main", "resources", "decks")
SNAPSHOT = os.path.join(REPO_ROOT, "mtg-pauper", "src", "main", "resources", "scryfall-mvp.json")

COLLECTION_URL = "https://api.scryfall.com/cards/collection"
BATCH_SIZE = 75
"""Scryfall's documented maximum identifiers per /cards/collection request."""

REQUEST_DELAY_SECONDS = 0.1
"""Scryfall asks for 50–100ms between requests."""

USER_AGENT = "mtg-play/0.1 (https://github.com/Selliott452/mtg-play; card-metadata snapshot builder)"

ENTRY_PATTERN = re.compile(r"^(\d+)\s+(.+)$")

# The fields ScryfallIngest.parse reads, in the order the snapshot writes them. Every one must be
# present on every card, with the right JSON type, or ingestion fails loudly (by design).
CARD_FIELDS = (
    "name",
    "mana_cost",
    "type_line",
    "oracle_text",
    "power",
    "toughness",
    "colors",
    "legalities",
    "oracle_id",
)


def deck_card_names() -> set:
    """Every distinct card name across every bundled decklist resource."""
    names = set()
    for dirpath, _dirnames, filenames in os.walk(DECKS_DIR):
        for filename in sorted(filenames):
            if not filename.endswith(".deck"):
                continue
            with open(os.path.join(dirpath, filename), encoding="utf-8") as handle:
                for raw in handle:
                    line = raw.strip()
                    if not line or line.startswith("#") or line.startswith("Name:"):
                        continue
                    if line.lower() in ("main", "mainboard", "sideboard"):
                        continue
                    match = ENTRY_PATTERN.match(line)
                    if match is None:
                        raise SystemExit(f"{filename}: unparsable decklist line: {line!r}")
                    names.add(match.group(2).strip())
    return names


def existing_snapshot_names() -> set:
    """The names already in the snapshot — kept so fixture-only cards survive a regeneration."""
    if not os.path.exists(SNAPSHOT):
        return set()
    with open(SNAPSHOT, encoding="utf-8-sig") as handle:
        return {card["name"] for card in json.load(handle)["cards"]}


def fetch(names: list) -> list:
    """Fetches every name from Scryfall, failing loudly on anything unresolved."""
    fetched = []
    for start in range(0, len(names), BATCH_SIZE):
        batch = names[start : start + BATCH_SIZE]
        payload = json.dumps({"identifiers": [{"name": name} for name in batch]}).encode("utf-8")
        request = urllib.request.Request(
            COLLECTION_URL,
            data=payload,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request) as response:
            body = json.load(response)
        not_found = body.get("not_found") or []
        if not_found:
            raise SystemExit(f"Scryfall could not resolve: {[item.get('name') for item in not_found]}")
        fetched.extend(body["data"])
        print(f"  fetched {len(fetched)}/{len(names)}", file=sys.stderr)
        time.sleep(REQUEST_DELAY_SECONDS)
    return fetched


FACE_SEPARATOR = "\n//\n"
"""Scryfall's own separator between the halves of a multi-faced card's combined text."""


def snapshot_name(card: dict) -> str:
    """The card's name (CR 201.2), which is not always Scryfall's combined ``name`` field.

    Scryfall joins face names with ``//`` for every multi-faced layout, but the Comprehensive Rules
    do not:

    * A **split** card's name really is both halves joined (CR 708.2a), so Scryfall's field is right.
    * An **adventure** card's name is the creature's name alone (CR 715.3a: outside the stack and
      exile it has only the creature's characteristics); the adventure half's name is an
      alternative characteristic, not the card's name. Decklists therefore write "Fang Dragon".

    Any other multi-faced layout raises rather than guessing — the decklist-resolution key must not
    be invented.
    """
    if "card_faces" not in card:
        return card["name"]
    layout = card.get("layout")
    if layout == "split":
        return card["name"]
    if layout == "adventure":
        return card["card_faces"][0]["name"]
    raise SystemExit(
        f'"{card["name"]}" has the unhandled multi-faced layout "{layout}"; '
        "decide its CR 201.2 name deliberately before adding it to the snapshot"
    )


def oracle_text_of(card: dict) -> str:
    """The card's printed rules text (CR 207), flattened to the snapshot's single string.

    A card with no rules text (a basic land, a vanilla creature) has no ``oracle_text`` key at all;
    its printed text genuinely is empty, which ``CardMetadata.oracleText`` documents as the vanilla
    case. A multi-faced card carries text per face instead, joined here with Scryfall's own ``//``
    separator so neither half is lost.
    """
    if "oracle_text" in card:
        return card["oracle_text"]
    if "card_faces" in card:
        return FACE_SEPARATOR.join(face.get("oracle_text", "") for face in card["card_faces"])
    return ""


def trim(card: dict) -> dict:
    """Reduces a Scryfall card object to exactly the fields the ingest reads, failing loudly on a gap.

    Beyond [snapshot_name] and [oracle_text_of], the remaining fields are taken from the top level,
    where Scryfall already publishes the combined values for a multi-faced card (``mana_cost``
    ``{5}{R}{R} // {1}{R}``, ``type_line`` ``Creature — Dragon // Sorcery — Adventure``). Nothing is
    dropped, and the two halves stay visible in every field that has them.
    """
    name = snapshot_name(card)
    trimmed = {
        "name": name,
        "mana_cost": card["mana_cost"],
        "type_line": card["type_line"],
        "oracle_text": oracle_text_of(card),
        "power": card.get("power"),
        "toughness": card.get("toughness"),
        "colors": card["colors"],
        "legalities": {"pauper": card["legalities"]["pauper"]},
        "oracle_id": card["oracle_id"],
    }
    missing = [field for field in CARD_FIELDS if field not in trimmed]
    if missing:
        raise SystemExit(f'"{name}" is missing snapshot fields {missing}')
    return trimmed


def main() -> None:
    names = sorted(deck_card_names() | existing_snapshot_names())
    print(f"resolving {len(names)} distinct card names", file=sys.stderr)
    cards = [trim(card) for card in fetch(names)]
    cards.sort(key=lambda card: card["name"])

    by_name = {}
    for card in cards:
        if card["name"] in by_name:
            raise SystemExit(f'Scryfall returned two cards named "{card["name"]}"')
        by_name[card["name"]] = card
    unresolved = [name for name in names if name not in by_name]
    if unresolved:
        raise SystemExit(f"requested but not returned: {unresolved}")

    fetched_on = datetime.date.today().isoformat()
    # CC BY 4.0 requires the creator, a copyright notice, a link to the license, and an indication
    # that the material was modified (ADR-003). All four are carried in this one string, which
    # CardCatalog.attribution surfaces to every downstream consumer.
    document = {
        "cards": cards,
        "source": (
            "Card data from Scryfall (https://scryfall.com), copyright Scryfall, LLC, "
            "used under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). "
            "Modified: trimmed to the cards this project's decklists name and to a subset of "
            f"fields. Fetched {fetched_on} via tools/fetch_scryfall_snapshot.py."
        ),
    }
    with open(SNAPSHOT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(document, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print(f"wrote {len(cards)} cards to {SNAPSHOT}", file=sys.stderr)

    non_legal = [(card["name"], card["legalities"]["pauper"]) for card in cards if card["legalities"]["pauper"] != "legal"]
    if non_legal:
        print(f"NOTE: {len(non_legal)} card(s) are not Pauper-legal: {non_legal}", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Scryfall returned HTTP {error.code}: {error.read().decode('utf-8', 'replace')}")
