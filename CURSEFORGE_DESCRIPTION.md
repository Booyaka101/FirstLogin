# FirstLogin (v1.7.2)
A lightweight, friendly first-join experience for Spigot/Paper. Show polished welcome messages, optional visuals (titles/action bar/sounds), and a configurable Welcome GUI that can gate “rules accepted” with an optional confirm dialog. Includes async player data saving and PlaceholderAPI placeholders for timestamps, telemetry, and join order.

## Features
- Global + private welcome messages
- Adventure MiniMessage support (with seamless legacy `&` fallback)
- Optional visuals: Title, Action Bar, Sound
- Returning player gate: greet again only if offline for X days
- Welcome GUI: per-item permissions and gating (once/cooldown/closeOnClick/clickSound)
- Reopen-on-join until current rules are accepted (configurable)
- Optional confirmation dialog before accept
- Asynchronous players.yml saving (non-blocking)
- Telemetry counters (today): GUI opens, rules accepted, per-item clicks
- PlaceholderAPI support (timestamps, telemetry, join order)
- bStats metrics (opt-out)

## Requirements
- Spigot or Paper 1.20+
- Java 17
- Optional: PlaceholderAPI (to use `%firstlogin_*%` placeholders)

## Installation
1) Drop `firstlogin-1.7.2.jar` into your `plugins/` folder.  
2) Start the server once to generate:
   - `plugins/FirstLogIn/config.yml`
   - `plugins/FirstLogIn/messages.yml`
   - `plugins/FirstLogIn/players.yml`
3) Configure messages and visuals to your liking.  
4) Restart the server (recommended over `/reload`).

## Commands
- `/listp` — number of players who have joined to date  
- `/pnames` — list of names who have joined to date  
- `/owner` — prints server owner from config  
- `/onlinep` — online/total summary  
- `/firsthelp` — quick help  
- `/firstlogin` — admin utilities (tab-complete)
  - `reload` — reload config/messages
  - `gui <open [player]|accept [player]|trigger <key> [player]>`
  - `clearcooldown <player> <key|all>`
  - `clearflag <player> <flag|all>`
  - `seen <player>` / `reset <player|all>` / `status [player]`
  - `set <key> <value>` — runtime toggles
  - `metrics [reset]` — show or reset today’s telemetry

## PlaceholderAPI Placeholders
Timestamps and status:
- `%firstlogin_first_join_date%` — formatted first join time (uses `formatting.datePattern`, default `yyyy-MM-dd HH:mm:ss`)
- `%firstlogin_rules_accepted_date%` — formatted rules accepted time
- `%firstlogin_days_since_first_join%` — whole days since first join
- `%firstlogin_first_join_ts%` — epoch millis of first join (0 if unknown)
- `%firstlogin_rules_accepted_ts%` — epoch millis of rules acceptance (0 if unknown)
- `%firstlogin_days_since_rules_accepted%` — whole days since rules acceptance (0 if unknown)
- `%firstlogin_rules_version_accepted%` — highest rules version accepted (0 if none)
- `%firstlogin_rules_pending%` — true if player has NOT accepted current rules version

Telemetry and join order (new in 1.7.2):
- `%firstlogin_gui_opens_today%`
- `%firstlogin_rules_accepted_today%`
- `%firstlogin_item_clicks_today_<key>%` — per-GUI-item click counter using your item key
- `%firstlogin_join_order%` — 1-based join order (alias: `%firstlogin_join_number%`)

Examples:
```
/papi parse me %firstlogin_player%
/papi parse me %firstlogin_first_join_date%
/papi parse me %firstlogin_gui_opens_today%
/papi parse me %firstlogin_item_clicks_today_confirm_accept%
/papi parse me %firstlogin_join_order%
```

## Configuration Highlights
- `formatting`: toggle MiniMessage, built-ins, and PlaceholderAPI resolution
- `returningGate.minDaysOffline`: greet returning players only if away X days
- `welcomeGui`: define items, permissions, gating, once/cooldown, click actions/sounds
- Runtime toggles via `/firstlogin set <key> <value>` (no file edits needed)
- Debug toggles for GUI/inventory logging

## Permissions (examples)
- `firstlogin.admin` — use `/firstlogin` admin commands
- `firstlogin.command.listp` — `/listp`
- `firstlogin.command.pnames` — `/pnames`
- `firstlogin.command.owner` — `/owner`
- `firstlogin.command.onlinep` — `/onlinep`
- `firstlogin.command.firsthelp` — `/firsthelp`

## bStats
Anonymous usage statistics help guide development and can be disabled:
- Globally via `plugins/bStats/config.yml`
- Or per-plugin in `plugins/FirstLogIn/config.yml` (if exposed)

## What’s New in 1.7.2
- Asynchronous `players.yml` saving for Welcome GUI state (non-blocking)
- Persisted timestamps: `timestamps.<uuid>.first_join`, `timestamps.<uuid>.rules_accepted`
- PlaceholderAPI: first join + rules date/epoch, days-since; telemetry counters; join order
- `/firstlogin metrics` to view/reset today’s counts
- GUI improvements: reopen-on-join until accepted, optional confirm dialog

---
Thanks for checking out FirstLogin! Feedback and suggestions are welcome.
