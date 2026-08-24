# Translations

Apricot can show its interface — and the item names, action names and item
descriptions the game server sends — in languages other than English.

Pick your language under **Options → Advanced Settings → Interface Settings →
Language**, then restart the client.

Anything that has no translation yet stays in English, so a half-finished
language is still perfectly playable.

## What lives where

The client reads each language from two places and merges them, with this
folder winning:

| Layer | Location | Who owns it |
|---|---|---|
| Official | inside `hafen.jar` | shipped with the client, replaced on every update |
| Yours | `Translations/<language>/` (this folder) | you — **never touched by the updater** |

So if a word is translated badly, you do not have to wait for a release. Drop
a file here with just the entries you want to change and they override the
official ones. Your file only needs the lines you are changing.

## The files

Each language is a folder named with its language code — `ru`, `de`, `pl`,
`zh`, `ko`, `fr` — holding one JSON file per kind of text:

| File | Covers |
|---|---|
| `button.json` | push-button captions |
| `label.json` | labels, headings, column titles |
| `window.json` | window titles |
| `tooltip.json` | item and object names, hover tooltips |
| `action.json` | actions in the crafting/building menu |
| `pagina.json` | item and action descriptions |
| `flower.json` | the petals of the right-click menu |
| `ingredient.json` | ingredient names inside crafted-item tooltips |
| `itemtip.json` | stat lines inside item tooltips -- see below |
| `biome.json` | biome names on the minimap |
| `msg.json` | login and system messages |
| `meta.json` | the language's own name, for the settings menu |

Every file is a plain JSON object mapping the English text to your
translation:

```json
{
    "Cancel": "Отмена",
    "Drink": "Пить"
}
```

`tooltip.json`, `action.json` and `pagina.json` are keyed by *resource path*
instead of by English text, because the same word can mean different things on
different objects:

```json
{
    "gfx/invobjs/bucket": "Ведро",
    "paginae/act/craft": "Ремесло"
}
```

Files must be saved as **UTF-8**.

## `itemtip.json` works differently

The stat lines in an item tooltip -- `Damage: 44`, `Armor penetration: 20.0%`,
`Durability: 44/50 (88.00%)` -- are assembled by the game server with the
numbers and colour markup already in them, so there is no fixed string to look
up. This one file therefore holds **phrases**, which are replaced wherever they
appear in a line:

```json
{
    "Armor penetration": "Пробитие брони",
    "Durability": "Прочность"
}
```

Longer phrases are applied first, so `"Demolition Damage"` wins over
`"Damage"`. Keep the entries to distinctive words -- a phrase as short as `"of"`
would rewrite text you did not mean to touch.

## Starting from the full list instead

`Translations/_template/` holds every piece of text the client itself can show,
extracted straight from the source -- roughly 740 entries across `button`,
`label`, `window`, `tooltip` and `msg`. Copy a file into your language folder
and replace the values; there is no need to play through every screen to find
them. (Directories starting with `_` are not languages and never appear in the
menu.)

Regenerate it after the client gains new text with:

```bash
python etc/l10n-extract.py
```

That list covers the client's own interface. Item names, actions and
descriptions come from the server and are not in it -- use the recording below
for those.

## Finding what still needs translating

Turn on **Record untranslated text** next to the language setting and play for
a while. Everything the client could not translate is written to
`Translations/<language>/missing/*.json`, already in the right format:

```json
{
    "Drink": "Drink"
}
```

Replace the values with your translation, then move the entries into the
matching file one folder up. Delete the `missing` folder when you are done and
turn the setting back off.

## Adding a language

1. Make a folder here named with the language code — `it` for Italian, `sv`
   for Swedish, and so on.
2. Put a `meta.json` in it:
   ```json
   {
       "name": "Italiano",
       "english": "Italian"
   }
   ```
3. Restart the client. The language is now in the dropdown, with everything
   still in English.
4. Select it, turn on **Record untranslated text**, and work through the
   `missing` files.

Removing a language is the same in reverse: delete its folder.

## Patterns

`label.json`, `window.json` and `flower.json` also accept regular expressions
as keys, for text that has numbers or names in it. Capture groups come back as
`@1`, `@2`, … in the translation:

```json
{
    "(\\d+) of (\\d+)": "@1 из @2"
}
```

Use `$1` instead of `@1` to run the captured text through `ingredient.json`
before inserting it. A `\@` or `\$` inserts a literal `@` or `$`.

If a key contains no regex characters it is matched literally, which is faster
— so only reach for a pattern when the text really does vary. A key that looks
like a pattern but is not a valid one, such as `Chat ($col[255,255,0]{Ctrl+C})`,
is matched literally too, so you rarely need to escape anything by hand.

## Sending translations back

Corrections that belong to everyone are worth contributing upstream so the
next release ships them: open a pull request adding your files under
`src/l10n/<language>/`.
