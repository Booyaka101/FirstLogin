# FirstLogin (v1.8.0)
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

### Experimental (opt-in)
- Particle effects on first join (`particles.enabled`) — disabled by default
- Animated NPC guide (`animatedGuide.enabled`) — disabled by default
- Toggle in `config.yml` and apply at runtime with `/firstlogin reload`

## Requirements
- Spigot or Paper 1.20+
- Java 17
- Optional: PlaceholderAPI (to use `%firstlogin_*%` placeholders)
  - Toggle the FirstLogin expansion via `placeholderapi.enabled`. Use `/firstlogin reload` to dynamically register/unregister the expansion at runtime.

## Installation
1) Drop `firstlogin-1.8.0.jar` into your `plugins/` folder.  
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
  - `metrics [reset|when|now]` — view telemetry (last/next reset with pretty durations), reset today, or reset immediately

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

Telemetry reset (new in 1.7.3):
- `%firstlogin_metrics_reset_date%` — formatted date/time of the last telemetry reset
- `%firstlogin_metrics_last_reset_ts%` — epoch millis of the last telemetry reset (0 if never)
- `%firstlogin_metrics_next_reset_date%` — formatted date/time of the next scheduled telemetry reset
- `%firstlogin_metrics_next_reset_ts%` — epoch millis of the next scheduled telemetry reset (0 if disabled)

Additional timing/pretty placeholders (new in 1.7.3):
- `%firstlogin_metrics_next_reset_in_seconds%`
- `%firstlogin_metrics_next_reset_in_minutes%`
- `%firstlogin_metrics_next_reset_in_hours%`
- `%firstlogin_metrics_next_reset_pretty%` — e.g., "3h 14m 5s"
- `%firstlogin_metrics_last_reset_pretty%` — e.g., "1d 2h 3m 4s ago"

Examples:
```
/papi parse me %firstlogin_player%
/papi parse me %firstlogin_first_join_date%
/papi parse me %firstlogin_gui_opens_today%
/papi parse me %firstlogin_item_clicks_today_confirm_accept%
/papi parse me %firstlogin_join_order%
/papi parse me %firstlogin_metrics_reset_date%
/papi parse me %firstlogin_metrics_next_reset_date%
```

## Configuration Highlights
- `formatting`: toggle MiniMessage, built-ins, and PlaceholderAPI resolution
- `returningGate.minDaysOffline`: greet returning players only if away X days
- `welcomeGui`: define items, permissions, gating, once/cooldown, click actions/sounds
- Runtime toggles via `/firstlogin set <key> <value>` (no file edits needed)
- Debug toggles for GUI/inventory logging
- Telemetry reset scheduling: `telemetry.reset.enabled` (bool), `telemetry.reset.time` (HH:mm; reschedules immediately on change)

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

## What's New in 1.8.0

### Enhanced GUI System
- **29 Action Types**: message, command, url, sound, give, teleport, effect, title, actionbar, broadcast, heal, feed, gamemode, fly, firework, particle, bossbar, velocity, cleareffects, sudo, op, random, delay, if, chance, repeat
- **13 Requirement Types**: flag, !flag, perm, !perm, level, health, food, gamemode, world, online, time, weather, cooldown, played
- **10 Item Display Options**: amount, glow, customModelData, hideAttributes, hideFlags, skullOwner, color, enchantments, potionColor, potionEffects
- **15+ New Placeholders**: {health}, {food}, {level}, {xp}, {world}, {x}, {y}, {z}, {gamemode}, {time}, {weather}, {playtime}, {ping}, {uuid_short}
- **Progress Bars**: {bar_health}, {bar_food}, {bar_xp}, custom {progress:current:max:width:filledColor:emptyColor}

### Join Tracking & Events
- Login streak tracking (consecutive days played, max streak)
- Anniversary detection and celebration messages
- `FirstJoinEvent` and `ReturningPlayerEvent` custom events for other plugins
- Streak milestone messages (7, 14, 30, 60, 90, 100, 365 days)
- Referral system and player notes for admins

### Discord/Slack Webhooks
- Webhook notifications for first-time joins and returning players
- Customizable messages with placeholders
- Anniversary notifications in webhooks

## What’s New in 1.7.2
- Asynchronous `players.yml` saving for Welcome GUI state (non-blocking)
- Persisted timestamps: `timestamps.<uuid>.first_join`, `timestamps.<uuid>.rules_accepted`
- PlaceholderAPI: first join + rules date/epoch, days-since; telemetry counters; join order
- `/firstlogin metrics` to view/reset today’s counts
- GUI improvements: reopen-on-join until accepted, optional confirm dialog

---
Thanks for checking out FirstLogin! Feedback and suggestions are welcome.
