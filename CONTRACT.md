# Output contract

Every ModPackExport branch, whatever the MC version, writes these into the game
directory. The modpacks-wiki pipeline consumes exactly these shapes — keep them
stable across branches.

## item-icons/
`item-icons/<ns>__<path>.png` — 128px. Id `<ns>:<path>` encodes with a double
underscore. Fluids: `fluid__<ns>__<path>.png`. Animated items: a vertical strip
(height a whole multiple of width). Trigger: `items` (`/itemdump`,
`-Dmodpackexport.items=true`, or `items.trigger`).

## jei-layouts/
`jei-layouts/<safeCategoryId>.json`:

```json
{ "category": "<recipe type id>", "title": "<localized>",
  "bg": { "w": 0, "h": 0, "texture": "<rl>", "u": 0, "v": 0, "texW": 0, "texH": 0 },
  "slots": [ { "role": "INPUT|OUTPUT|CATALYST", "x": 0, "y": 0, "w": 18, "h": 18 } ] }
```

Optional `jei-layouts/<safeCategoryId>_bg.png` — the real colored machine GUI.
Trigger: `layouts` (`/jeidump`, `-Dmodpackexport.layouts=true`, `layouts.trigger`).

## mob-icons/
`mob-icons/<ns>__<path>.png` — a 12-frame horizontal turntable sheet. Trigger:
`mobs` (`/mobdump`, `-Dmodpackexport.mobs=true`, `mobs.trigger`).

## recipes.json

```json
{ "recipes": [ { "id": "<rl>", "type": "<serializer rl>", "json": { } } ],
  "names":  { "<item id>": "<display name>" },
  "tags":   { "<tag id>": [ "<item id>", "..." ] } }
```

`json` is the recipe's raw JSON (serializer codec). Trigger: `recipes`
(`/recipedump`, `-Dmodpackexport.recipes=true`, `recipes.trigger`).

## Per-version notes
- Recipe-JSON capture: codec re-encode over the live `RecipeManager` on MC ≥1.20.5
  (codecs exist). On older branches, read datapack files from the resource manager
  (`data/<ns>/recipes/**.json`); runtime/script-added recipes are best-effort.
- Recipe folder path differs: `recipes/` (≤1.20.x) vs `recipe/` (1.21+) — only
  relevant to the resource-read fallback.
