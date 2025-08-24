# FirstLogin

A lightweight, friendly first-join experience plugin for Spigot/Paper. Shows custom messages for first-time players, optional visuals (title/action bar/sound), and simple server stats. Now with Adventure MiniMessage support and seamless legacy color fallback.

Author: BooPug Studios
Version: 1.7.2
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

## Installation
1. Place `target/firstlogin-1.7.2.jar` into your server `plugins/` folder.
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
  - `reload` – reload config and messages (extracts bundled locales)
  - `gui <open [player]|accept [player]|trigger <key> [player]>`
  - `clearcooldown <player> <key|all>`
  - `clearflag <player> <flag|all>`
  - `seen <player>` / `reset <player|all>` / `status [player]`
  - `set <key> <value>` – runtime toggles (see keys below)
  - `metrics [reset]` – show or reset today’s telemetry counters

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
- `firstlogin.command.listp` – use `/listp` (default: op)
- `firstlogin.command.pnames` – use `/pnames` (default: op)
- `firstlogin.command.owner` – use `/owner` (default: op)
- `firstlogin.command.onlinep` – use `/onlinep` (default: op)
- `firstlogin.command.firsthelp` – use `/firsthelp` (default: true)
 - `firstlogin.bypass.rules` – bypass forced Welcome GUI reopen on close when rules are not yet accepted (default: false)

## PlaceholderAPI

If PlaceholderAPI is installed and `formatting.usePlaceholderAPI` is enabled, FirstLogin registers a PAPI expansion providing:

- `%firstlogin_player%` – player name
- `%firstlogin_online%` – currently online players count
- `%firstlogin_total%` – total players who have joined (to date)
- `%firstlogin_owner%` – configured server owner from config
- `%firstlogin_rules_accepted%` – true/false if the player accepted rules (versioned)
- `%firstlogin_rules_version%` – current rules version from config

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

Examples:

```
/papi parse me %firstlogin_player%
/papi parse me %firstlogin_online%/%firstlogin_total%
/papi parse me %firstlogin_owner%
/papi parse me %firstlogin_rules_accepted%
// New examples
/papi parse me %firstlogin_gui_opens_today%
/papi parse me %firstlogin_item_clicks_today_confirm_accept%
/papi parse me %firstlogin_join_order%
```

## Configuration highlights
- Formatting toggles under `formatting:`
  - `useMiniMessage` – enables Adventure MiniMessage
  - `usePlaceholders` – built-ins like {player}
  - `usePlaceholderAPI` – resolves PAPI placeholders if installed
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
- Metrics under `metrics.enabled` and `metrics.pluginId`
- Debug logging under `debug:`
  - `debug.gui` – log GUI flow and actions
  - `debug.inventory` – log inventory event cancellations

### Runtime toggles via `/firstlogin set`
Supported keys (case-insensitive):
- `welcomeGui.reopenOnJoinUntilAccepted` – true|false
- `welcomeGui.blockCloseUntilAccepted` – true|false
- `welcomeGui.confirmOnAccept` – true|false
- `welcomeGui.rulesVersion` – integer (>= 1)
- `debug.gui` – true|false
- `debug.inventory` – true|false

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
```
Commands and URLs in GUI actions fully resolve MiniMessage and PlaceholderAPI placeholders before execution.

## Metrics (bStats)
- Opt-out globally via `plugins/bStats/config.yml` or per-plugin by setting `metrics.enabled: false`.
- `metrics.pluginId` is set to your bStats ID in `config.yml`.

## Telemetry (in-plugin)
- Daily counters: GUI opens and rules accepted.
- View with `/firstlogin metrics`; reset using `/firstlogin metrics reset`.

## Changelog
See `CHANGELOG.md` for full details of 1.7.2.

## Support
Open an issue or discussion on your repository, or contact BooPug Studios through your preferred channel.
