# FirstLogin

A lightweight, friendly first-join experience plugin for Spigot/Paper. Shows custom messages for first-time players, optional visuals (title/action bar/sound), and simple server stats. Now with Adventure MiniMessage support and seamless legacy color fallback.

Author: BooPug Studios
Version: 1.8.0
MC: 1.20+ (Java 17)

## Features
- First-join broadcast and private welcome message
- MiniMessage formatting (<green>, <gray>, etc) with legacy "&" color fallback
- Configurable visuals: title, action bar, sound
- Returning player gate (only greet again if offline X days)
- Simple stats: player count to date, online count
- Built-in placeholders: {player}, {online}, {total}, {owner}
- Optional PlaceholderAPI support
- Welcome GUI with per-item permissions and gating
  - items.*.permission, items.*.hideIfNoPermission
  - requires: perm:<node> and requires: flag:<flag>
  - per-item: once, cooldownSeconds, closeOnClick, clickSound
- Reopen-on-join until rules are accepted (configurable)
- Debug toggles for detailed GUI/inventory event logging
- Admin commands with granular permissions and tab-completion
  - /firstlogin reload | seen | reset | status | gui | clearcooldown | clearflag | set | metrics
- Runtime toggles via /firstlogin set (no file edits needed)
- Telemetry counters: daily GUI opens and rules accepted (/firstlogin metrics)
- Optional confirmation dialog before accepting rules in GUI
- bStats metrics (optional)
- Asynchronous player data saving (non-blocking players.yml writes)

- Experimental visuals (disabled by default; opt-in):
  - Particle effects on first join (`particles.enabled`)
  - Animated NPC guide (`animatedGuide.enabled`)
  - Can be toggled in config and applied at runtime via `/firstlogin reload`

## Installation
1. Place `target/firstlogin-1.8.0.jar` into your server `plugins/` folder.
2. Start the server once to generate configs:
   - `plugins/FirstLogIn/config.yml`
   - `plugins/FirstLogIn/messages.yml`
   - `plugins/FirstLogIn/players.yml`
3. Edit `config.yml` and `messages.yml` to your liking.
4. Restart the server (recommended over /reload).

## Commands
- `/listp` – number of players who have joined to date
- `/pnames` – list of players who have joined to date
- `/owner` – prints the configured server owner from config
- `/onlinep` – currently online count versus total
- `/firsthelp` – shows help
- `/firstlogin` – admin utilities (tab-complete supported)
  - `reload` – reload config and messages (extracts bundled locales) and dynamically reinitializes optional features (particles, animated guide, bossbar) and PlaceholderAPI registration per `placeholderapi.enabled`
  - `gui <open [player]|accept [player]|trigger <key> [player]>`
  - `clearcooldown <player> <key|all>`
  - `clearflag <player> <flag|all>`
  - `seen <player>` / `reset <player|all>` / `status [player]`
  - `set <key> <value>` – runtime toggles (see keys below)
  - `metrics [reset|when|now]` – view telemetry (last/next reset, pretty durations), reset today, or reset immediately
  - `report pending [page]` – list players who have NOT accepted the current rules version
  - `report pending online|offline|all [page]` – filter the pending list
  - `report pending csv [online|offline|all]` – export a CSV report to `plugins/FirstLogIn/reports/`
  - `gui additem <key> [slot] [material]` – create a new GUI item (validates slot/material)
  - `gui set <key> <path> <value>` – set config path under `welcomeGui.items.<key>`; lists use `|` separator
  - `gui move <key> <slot>` – move an item to a new slot (bounds-checked)
  - `gui remove <key>` – remove an item
  - `gui open [player] [page]` – open the Welcome GUI to preview edits
  - `gui list` – list item keys with slot/page/material
  - `gui list filter=<prefix>` – list only items whose keys start with a prefix
  - `gui listpage <n>` – list items on a specific page
  - `gui fill <material> [name] [lore1|lore2|...]` – enable filler panes for empty slots
  - `gui normalize` – clamp all item slots to the current inventory size
  - `gui validate` – check for invalid materials, out-of-bounds/duplicate slots, missing actions, unbalanced MiniMessage, invalid runAs
  - `gui preview <key> [player]` – open the GUI page containing `<key>` for targeted preview
  - `gui export <key>` – export an item YAML to `plugins/FirstLogIn/exports/`
  - `gui import <file.yml> [key]` – import an exported item YAML under the given key
    - Flags: `dry` (preview) and `overwrite` (replace existing key)
  - `gui fixduplicates` – auto-resolve duplicate slots by moving to nearest free slot on each page
  - `gui massset <path> <value> [filter=<prefix>] [page=<n>]` – bulk-apply a property across items
  - `gui rename <oldKey> <newKey>` – rename an item key
  - `gui movepage <from> <to>` – move all items from one page to another
  - `gui clearpage <n> confirm` – remove all items from page `n` (requires confirm)

### Examples

```
# Validate actions only
/firstlogin gui validate actions

# Validate actions in strict mode (checks empty command, url format, page number, flag:set value)
/firstlogin gui validate actions strict

# Validate layout only (materials/slots/MiniMessage)
/firstlogin gui validate layout

# Show page usage summary
/firstlogin gui validate pages

# Preview a mass change without writing (dry by default when confirm is omitted)
/firstlogin gui massset runAs console filter=shop_ page=2

# Apply the mass change (writes after auto-backup)
/firstlogin gui massset runAs console filter=shop_ page=2 confirm

# Set lore (use | to separate lines) for all items on page 1 (dry run)
/firstlogin gui massset lore "&7Welcome|&7Enjoy your stay" page=1 dry

# Export one GUI item as YAML and JSON
/firstlogin gui export rules
/firstlogin gui jsonexport rules

# Fix duplicate slots and show moved items
/firstlogin gui fixduplicates verbose
```

## GUI Item Reference

Each GUI item under `welcomeGui.items.<key>` supports the following fields:

- `slot` (int): inventory slot index (0..rows*9-1)
- `page` (int): page number (default 1)
- `material` (string): Bukkit `Material` name
- `name` (string): MiniMessage/legacy supported (converted via Adventure)
- `lore` (string list): each line supports MiniMessage/legacy
- `permission` (string): per-item permission
- `hideIfNoPermission` (bool): hide item if player lacks `permission`
- `requires` (string): a single requirement (e.g., `perm:<node>` or `flag:<name>`) 
- `requiresAll` (list): all requirements must pass
- `requiresAny` (list): at least one requirement must pass
- `once` (bool): allow one-time claim (tracks in players.yml)
- `cooldownSeconds` (int): per-key cooldown in seconds
- `cooldownBypassPermission` (string): bypass cooldown if player has this permission
- `closeOnClick` (bool): close the GUI after the click
- `delayTicks` (int): delay before actions execute
- `runAs` (string): `console|player|op` for command execution context
- `clickSound.name|volume|pitch`: optional click sound
- `disabledVariant.material|name|lore`: alternate render if lacking permission and not hidden

Supported `actions` (use an `actions` list or a single `action`):

- `message:<messages.yml path or inline text>`
- `command:<server command>`
- `url:<https://example>`
- `flag:set:<flag>`
- `page:<number>`
- `acceptRulesNow`
- `back`

Validation helpers:

- `/firstlogin gui validate layout` – materials, slots, MiniMessage balance
- `/firstlogin gui validate actions` – presence and prefixes
- `/firstlogin gui validate actions strict` – empty `command:`, bad `url:`, bad `page:`, missing `flag:set:` value

## Safety & Backups

- Before any mutation via `/firstlogin gui` editor commands, the plugin creates a backup: `plugins/FirstLogIn/config.backup-YYYYMMDD-HHMMSS.yml`.
- Restore the latest backup via `/firstlogin gui undo`.
- Prefer preview/dry modes:
  - `massset` is dry by default unless `confirm` is present.
  - `import` supports `dry` (preview) and `overwrite` flags.
  - `fixduplicates verbose` shows moved items for audit.

## Permissions
- `firstlogin.admin` – use `/firstlogin` admin commands (default: op)
- Granular admin children (all default: op):
  - `firstlogin.admin.reload`
  - `firstlogin.admin.gui`
  - `firstlogin.admin.clearcooldown`
  - `firstlogin.admin.clearflag`
  - `firstlogin.admin.seen`
  - `firstlogin.admin.reset`
  - `firstlogin.admin.status`
  - `firstlogin.admin.set`
  - `firstlogin.admin.metrics`
  - `firstlogin.admin.report`
- `firstlogin.command.listp` – use `/listp` (default: op)
- `firstlogin.command.pnames` – use `/pnames` (default: op)
- `firstlogin.command.owner` – use `/owner` (default: op)
- `firstlogin.command.onlinep` – use `/onlinep` (default: op)
- `firstlogin.command.firsthelp` – use `/firsthelp` (default: true)
 - `firstlogin.bypass.rules` – bypass forced Welcome GUI reopen on close when rules are not yet accepted (default: false)

## PlaceholderAPI

PlaceholderAPI is an optional (soft) dependency. FirstLogin will register its expansion when PAPI is present and `placeholderapi.enabled` is `true`.

Tip: You can toggle this at runtime and use `/firstlogin reload` to dynamically register or unregister the expansion without restarting the server.

If PlaceholderAPI is installed and enabled, FirstLogin provides the following placeholders and will resolve PAPI placeholders in:
 - Welcome GUI titles and lore strings
 - BossBar welcome text
 - GUI actions (commands/urls) before execution

- `%firstlogin_player%` – player name
- `%firstlogin_online%` – currently online players count
- `%firstlogin_total%` – total players who have joined (to date)
- `%firstlogin_owner%` – configured server owner from config
- `%firstlogin_rules_accepted%` – true/false if the player accepted rules (versioned)
- `%firstlogin_rules_version%` – current rules version from config
 - `%firstlogin_rules_pending_count%` – number of players who have NOT accepted current rules version
 - `%firstlogin_rules_accepted_count%` – number of players who HAVE accepted current rules version

- `%firstlogin_first_join_date%` – formatted first join time (uses `formatting.datePattern`)
- `%firstlogin_rules_accepted_date%` – formatted rules accepted time (uses `formatting.datePattern`)
- `%firstlogin_days_since_first_join%` – whole days since first join
- `%firstlogin_first_join_ts%` – raw epoch millis of first join (0 if unknown)
- `%firstlogin_rules_accepted_ts%` – raw epoch millis of rules acceptance (0 if unknown)
- `%firstlogin_days_since_rules_accepted%` – whole days since rules acceptance (0 if unknown)
- `%firstlogin_rules_version_accepted%` – highest rules version the player has accepted (0 if none)
- `%firstlogin_rules_pending%` – true if player has NOT accepted current rules version

New in 1.7.2:
- `%firstlogin_gui_opens_today%` – number of Welcome GUI opens recorded today
- `%firstlogin_rules_accepted_today%` – number of rules accepted recorded today
- `%firstlogin_item_clicks_today_<key>%` – number of clicks today for GUI item `<key>` (use your item key)
- `%firstlogin_join_order%` – 1-based join order across known players (alias: `%firstlogin_join_number%`)

Note: date formatting defaults to `yyyy-MM-dd HH:mm:ss` and can be customized via `formatting.datePattern`.

### New in 1.8.0

#### Enhanced GUI System
- **29 Action Types**: message, command, url, sound, give, teleport, effect, title, actionbar, broadcast, console, player, chat, xp, heal, feed, gamemode, fly, firework, particle, bossbar, velocity, cleareffects, sudo, op, random, delay, if, chance, repeat
- **13 Requirement Types**: flag, !flag, perm, !perm, level, health, food, gamemode, world, online, time, weather, cooldown, played
- **10 Item Display Options**: amount, glow, customModelData, hideAttributes, hideFlags, skullOwner, color, enchantments, potionColor, potionEffects
- **15+ Placeholders**: {health}, {food}, {level}, {xp}, {world}, {x}, {y}, {z}, {gamemode}, {time}, {weather}, {playtime}, {ping}, {uuid_short}
- **Progress Bars**: {bar_health}, {bar_food}, {bar_xp}, {progress:current:max:width:filledColor:emptyColor}

#### Join Tracking & Events
- Login streak tracking (consecutive days played)
- Anniversary detection and celebration messages
- `FirstJoinEvent` and `ReturningPlayerEvent` custom events for other plugins
- Streak milestone messages (7, 14, 30, 60, 90, 100, 365 days)
- Referral system and player notes for admins

#### Discord/Slack Webhooks
- Webhook notifications for first-time joins and returning players
- Customizable messages with placeholders
- Anniversary notifications in webhooks

#### Previous 1.7.3 Features
- Config toggle: `placeholderapi.enabled` to enable/disable the FirstLogin PAPI expansion at runtime
- Daily telemetry reset scheduling with persistence
- Configurable async save debounce for `players.yml`
- New telemetry reset timing placeholders

Examples:

```
/papi parse me %firstlogin_player%
/papi parse me %firstlogin_online%/%firstlogin_total%
/papi parse me %firstlogin_owner%
/papi parse me %firstlogin_rules_accepted%
/papi parse me %firstlogin_gui_opens_today%
/papi parse me %firstlogin_item_clicks_today_confirm_accept%
/papi parse me %firstlogin_join_order%
/papi parse me %firstlogin_metrics_reset_date%
/papi parse me %firstlogin_metrics_next_reset_date%
/papi parse me %firstlogin_metrics_last_reset_ts%
/papi parse me %firstlogin_metrics_next_reset_ts%
/papi parse me %firstlogin_metrics_next_reset_in_minutes%
/papi parse me %firstlogin_metrics_next_reset_in_hours%
/papi parse me %firstlogin_metrics_next_reset_pretty%
/papi parse me %firstlogin_metrics_last_reset_pretty%
```

## Configuration highlights
- Formatting toggles under `formatting:`
  - `useMiniMessage` – enables Adventure MiniMessage
  - `usePlaceholders` – built-ins like {player}
  - `usePlaceholderAPI` – resolves PAPI placeholders if installed
  
- PlaceholderAPI expansion
  - `placeholderapi.enabled` – enable/disable registration of the FirstLogin PAPI expansion (soft-depend). Can be toggled and applied at runtime via `/firstlogin reload`.
- Experimental visuals (disabled by default)
  - `particles.enabled` – optional first-join particle effects
  - `animatedGuide.enabled` – optional animated NPC guide
  - Both can be toggled and applied at runtime via `/firstlogin reload`
- Visuals under `firstJoinVisuals:` (title/actionbar/sound)
- Returning player gating under `returningGate.minDaysOffline`
- Welcome GUI under `welcomeGui:`
  - `enabled` – show the GUI on first join
  - `openDelayTicks` – delay before opening the GUI after first join (ticks)
  - `blockCloseUntilAccepted` – prevent closing until rules are accepted
  - `confirmOnAccept` – show a confirmation dialog before accepting rules
  - `rulesVersion` – bump to force re-acceptance
  - `reopenOnJoinUntilAccepted` – open GUI on every join until rules are accepted
  - Per-item keys: `permission`, `hideIfNoPermission`, `requires` (supports `perm:<node>` and `flag:<flag>`), `closeOnClick`, `once`, `cooldownSeconds`, `clickSound`
  - Optional `disabledVariant` rendering when a player lacks `permission` and the item is not hidden
- Metrics under `metrics.enabled` and `metrics.pluginId`
- Async saving under `asyncSave.players.debounceTicks` (debounce ticks for players.yml async save)
- Debug logging under `debug:`
  - `debug.gui` – log GUI flow and actions
  - `debug.inventory` – log inventory event cancellations
  - `debug.saves` – log async players.yml save scheduling/execution
  - `debug.telemetry` – log telemetry reset scheduling/execution
- Telemetry reset under `telemetry.reset.enabled` and `telemetry.reset.time` (daily reset time HH:mm)

### Runtime toggles via `/firstlogin set`
Supported keys (case-insensitive):
- `welcomeGui.reopenOnJoinUntilAccepted` – true|false
- `welcomeGui.blockCloseUntilAccepted` – true|false
- `welcomeGui.confirmOnAccept` – true|false
- `welcomeGui.rulesVersion` – integer (>= 1)
- `debug.gui` – true|false
- `debug.inventory` – true|false
- `telemetry.reset.enabled` – true|false (reschedules immediately)
- `telemetry.reset.time` – time in HH:mm (server local time; reschedules immediately)

Example:
```
/firstlogin set welcomeGui.confirmOnAccept true
/firstlogin set welcomeGui.rulesVersion 2
```

Example MiniMessage title in config:
```yaml
firstJoinVisuals:
  title:
    enabled: true
    title: "<green>Welcome, {player}!"
    subtitle: "<gray>Enjoy your stay."
```
If you prefer legacy, just use `&` color codes in messages; the plugin will render them correctly.

## Configuration

Per-item GUI example (see `welcomeGui.items`):

```yaml
welcomeGui:
  enabled: true
  reopenOnJoinUntilAccepted: true
  openDelayTicks: 40
  blockCloseUntilAccepted: true
  items:
    rules:
      slot: 11
      material: PAPER
      name: "&eView &6Rules"
      action: "message:messages.rules"   # actions: message:, url:, command:, flag:set
      closeOnClick: false
      clickSound:
        name: UI_BUTTON_CLICK
        volume: 1.0
        pitch: 1.2
    accept_rules:
      slot: 12
      material: LIME_DYE
      name: "&aAccept Rules"
      action: "flag:set:rules"            # sets a persistent flag for the player
      closeOnClick: false
    starter_kit:
      slot: 15
      material: CHEST
      name: "&6Claim Starter Kit"
      permission: "firstlogin.gui.kit"
      hideIfNoPermission: true            # hide the item if player lacks permission
      requires: "perm:firstlogin.vip"     # or use flag:rules to gate on rules acceptance
      action: "command:give {player} bread 8"  # commands/urls resolve MiniMessage + PAPI placeholders
      once: true                           # allow once (or set cooldownSeconds)
      cooldownSeconds: 0
      closeOnClick: true

    vip_only_example:
      slot: 16
      material: DIAMOND
      name: "&bVIP Feature"
      permission: "firstlogin.vip"
      hideIfNoPermission: false           # do not hide; show disabled variant instead
      disabledVariant:
        material: COAL
        name: "&7VIP Feature (locked)"
        lore:
          - "&7Unlock with &bfirstlogin.vip&7 permission"
```
Commands and URLs in GUI actions fully resolve MiniMessage and PlaceholderAPI placeholders before execution.

## Metrics (bStats)
- Opt-out globally via `plugins/bStats/config.yml` or per-plugin by setting `metrics.enabled: false`.
- `metrics.pluginId` is set to your bStats ID in `config.yml`.

## Telemetry (in-plugin)
- Daily counters: GUI opens and rules accepted.
- View with `/firstlogin metrics`; shows last and next reset times.
- Use `/firstlogin metrics when` for nicely formatted "ago"/"in" durations.
- Reset using `/firstlogin metrics reset`, or force an immediate reset with `/firstlogin metrics now`.

## Changelog
See `CHANGELOG.md` for full details of 1.7.2.

## Support
Open an issue or discussion on your repository, or contact BooPug Studios through your preferred channel.
