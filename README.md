# ModPackExport

Headless Minecraft mod that exports everything a modpack wiki needs — item icons,
JEI machine-GUI layouts, mob turntables, and the full recipe/name/tag dump —
straight from a running modded client, per MC version.

Powers [modpacks.my-monkey.fr](https://modpacks.my-monkey.fr).

## Branches (one per MC version)

| Branch | MC | Loader |
|---|---|---|
| `neoforge-1.21` | 1.21.1 | NeoForge |
| `forge-1.20` | 1.20.1 | Forge (planned) |
| `forge-1.19` | 1.19.2 | Forge (planned) |
| `forge-1.18` | 1.18.2 | Forge (planned) |
| `forge-1.16` | 1.16.5 | Forge (planned) |
| `forge-1.12` | 1.12.2 | Forge (planned) |

Every branch emits the same shapes — see [`CONTRACT.md`](CONTRACT.md). The
consuming pipeline (modpacks-wiki) is version-agnostic.

## Output families

`item-icons/` · `jei-layouts/` · `mob-icons/` · `recipes.json` — fired in a
headless client via `<family>.trigger` files or `-Dmodpackexport.<family>=true`.

Attribution: item rendering adapted from CyclopsMC/IconExporter (MIT) — see `NOTICE`.
