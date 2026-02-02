# FirstLogin – Changelog

## 1.8.0 (February 3, 2026)

### Performance & Thread Safety
- **Improve**: Thread-safe GUI action tracking using `ConcurrentHashMap` to prevent race conditions
- **Improve**: Cached rules accepted/pending counts with async refresh (60s TTL) to avoid expensive iteration
- **Improve**: Warm up rules counts cache on startup alongside players-to-date cache

### Code Quality
- **Refactor**: Centralized `versionedFlagName()` and `getRulesVersion()` methods in `FirstLogin` class
- **Refactor**: Removed duplicate code across `WelcomeGui`, `FirstLoginCommand`, and `PlayersStore`
- **Refactor**: Replaced reflection hack in `PlayersStore` with direct method call

### Commands & Permissions
- **Add**: Permission `firstlogin.gui.open` (default: true) for `/welcome` command access
- **Add**: Permission `firstlogin.autoaccept` (default: false) for auto-accepting rules on join
- **Improve**: `/welcome` command now works for regular players with page number support (`/welcome [page]`)
- **Improve**: Better error message for console users attempting player-only commands

### Resource Cleanup
- **Add**: Player quit listener to clean up bossbars and guides when players leave
- **Add**: `cleanupPlayerResources()` method for proper resource management

### New PlaceholderAPI Placeholders
- **Add**: `%firstlogin_has_guide%` - true if player has an active animated guide
- **Add**: `%firstlogin_bossbar_active%` - true if bossbar feature is enabled
- **Add**: `%firstlogin_version%` - plugin version string
- **Add**: `%firstlogin_item_clicks_total%` - total item clicks today across all items

### New Commands
- **Add**: `/firstlogin version` - shows plugin version (works from console too)
- **Add**: `/firstlogin debug [gui|inventory|saves|telemetry|all] [on|off]` - toggle debug modes at runtime
- **Add**: `/firstlogin info <player>` - detailed player information (timestamps, flags, cooldowns, once claims)
- **Add**: `/firstlogin validate` - on-demand configuration validation with warnings
- **Add**: `/firstlogin stats` - server-wide statistics (players, telemetry, config status)
- **Improve**: Console users now get helpful message instead of silent failure

### Configuration Validation
- **Add**: Startup validation of config.yml with warnings for:
  - Invalid materials in GUI items
  - Out-of-bounds slot numbers
  - Invalid sound names
  - Missing/invalid world name
  - Invalid bossbar color/overlay values

### GUI Sounds
- **Add**: Configurable sounds for GUI events:
  - `welcomeGui.sounds.open` - played when GUI opens
  - `welcomeGui.sounds.close` - played when GUI closes
  - `welcomeGui.sounds.rulesAccepted` - played when rules are accepted (enabled by default)

### Action Bar Welcome
- **Add**: New `ActionBarManager` for action bar welcome messages
- **Add**: Configurable action bar settings:
  - `actionbar.enabled` - enable/disable feature
  - `actionbar.text` - MiniMessage formatted text with `{player}` placeholder
  - `actionbar.durationSeconds` - how long to show the message
  - `actionbar.refreshTicks` - refresh rate for the action bar

### Help System
- **Add**: `/firstlogin help` - comprehensive help command showing all available commands
- **Add**: `/firstlogin` with no args now shows help instead of doing nothing

### Completed Previously Unfinished Features
- **Fix**: `firstJoinVisuals.title` - now actually sends welcome title on first join
- **Fix**: `firstJoinVisuals.actionbar` - now actually sends action bar message on first join
- **Fix**: `firstJoinVisuals.sound` - now actually plays sound on first join
- **Fix**: `msgFor()` now supports locale-aware messages (checks `messages_<locale>.yml` first)
- **Fix**: `message` config - now sends personal welcome message to first-time players
- **Fix**: `messageGlobal` config - now broadcasts to all players when someone joins for the first time
- **Fix**: `messageBack` config - now sends welcome back message to returning players
- **Fix**: `returningGate.minDaysOffline` - now properly gates messageBack based on days offline
- **Fix**: Join extras (particles, guide, bossbar, title, sound) now only trigger for first-time joins

### Config Documentation
- **Add**: Missing config options now documented in `config.yml`:
  - `welcomeGui.reopenOnJoinUntilAccepted` - reopen GUI on every join until rules accepted
  - `welcomeGui.bypassClosePermission` - allow bypass with permission
  - `welcomeGui.sounds.*` - GUI open/close/rulesAccepted sounds
  - `welcomeGui.filler.*` - empty slot filler configuration
  - `coordination.*` - join-message coordination to avoid multi-plugin spam
  - `bossbar.*` - BossBar welcome message configuration
  - `actionbar.*` - Action bar welcome message configuration
  - `particles.*` - Particle effects on first join (experimental)
  - `animatedGuide.*` - Animated NPC guide (experimental)

### Missing Commands & Permissions
- **Add**: `/firstlogin set <player> <flag|cooldown|locale|timestamp> <key> [value]` - set player data directly
- **Add**: `firstlogin.admin.forceopen` permission (was missing from plugin.yml)
- **Fix**: `/firsthelp` command updated to show modern commands and be more helpful
- **Fix**: `formatting.useMiniMessage` config option now actually controls MiniMessage parsing
- **Fix**: `formatting.usePlaceholders` config option now actually controls built-in placeholder replacement
- **Fix**: Added missing `{online}` and `{owner}` placeholders to placeholder system
- **Add**: `welcomeGui.denySound` config option now documented (sound when action is denied)
- **Add**: `disabledVariant` example added to config.yml (show different item when locked)
- **Add**: `permission` and `hideIfNoPermission` example added to config.yml (permission-gated items)
- **Add**: `runAs` and `cooldownBypassPermission` now documented in config.yml
- **Add**: `delayTicks` and `urlLabel` now documented in config.yml
- **Add**: `requiresAll` and `requiresAny` now documented in config.yml (composite requirements)
- **Add**: `page` option now documented in config.yml (multi-page GUI support)
- **Add**: `pagination` config section now documented (prev/next navigation for multi-page GUIs)
- **Add**: `actions` (multiple actions) now documented in config.yml
- **Add**: All action types now documented (message, command, url, flag:set, flag:clear, page, openRules, acceptRules, back)
- **Add**: All requirement types now documented (flag, perm)

### Enhanced GUI Action System
- **Add**: `sound:<name>:<vol>:<pitch>` action - play sounds on item click
- **Add**: `teleport:<world>:<x>:<y>:<z>:<yaw>:<pitch>` action - teleport players
- **Add**: `give:<material>:<amount>` action - give items directly from GUI
- **Add**: `title:<text>|<subtitle>:<fadeIn>:<stay>:<fadeOut>` action - show titles
- **Add**: `actionbar:<text>` action - show action bar messages
- **Add**: `effect:<type>:<seconds>:<amplifier>` action - apply potion effects
- **Add**: `broadcast:<message>` action - broadcast to all online players
- **Add**: `console:<command>` action - run command as console (ignores runAs)
- **Add**: `player:<command>` action - run command as player (ignores runAs)
- **Add**: `chat:<message>` action - make player send chat message
- **Add**: `xp:<amount>` or `xp:levels:<amount>` action - give XP points or levels
- **Add**: `close` action - close GUI without other actions

### Enhanced Requirement System
- **Add**: `!flag:<name>` - negated flag check (true if flag NOT set)
- **Add**: `!perm:<permission>` - negated permission check
- **Add**: `level:>=10` - check player XP level with comparison operators
- **Add**: `health:>=10` - check player health
- **Add**: `food:>=10` - check player food level
- **Add**: `gamemode:SURVIVAL` - check player gamemode
- **Add**: `world:<name>` - check world name
- **Add**: `online:>=10` - check online player count
- **Add**: `time:day` or `time:night` - check world time
- **Add**: `weather:clear` - check weather (clear, rain, storm)
- **Add**: `cooldown:key:60` - check if cooldown expired
- **Add**: `played:>=3600` - check total playtime in seconds

### Enhanced Item Display Options
- **Add**: `amount` - set item stack size (1-64)
- **Add**: `glow: true` - add enchantment glow without visible enchantment
- **Add**: `customModelData` - support for resource pack custom models
- **Add**: `hideAttributes: true` - hide item attributes for cleaner tooltip
- **Add**: `hideFlags: true` - hide all item flags (enchants, attributes, etc.)
- **Add**: `skullOwner` - set player head texture (supports {player} placeholder)
- **Add**: `color` - set leather armor color (#RRGGBB or color name)

### Advanced Action Types
- **Add**: `heal:<amount>` or `heal:full` - heal player
- **Add**: `feed:<amount>` or `feed:full` - feed player
- **Add**: `gamemode:<mode>` - set player gamemode
- **Add**: `fly:on/off/toggle` - toggle flight ability
- **Add**: `random:action1|action2|...` - pick random action from list
- **Add**: `delay:<ticks>:<action>` - execute action after delay
- **Add**: `if:<requirement>:<action>:<elseAction>` - conditional action execution
- **Add**: `repeat:<count>:<action>` - repeat action N times (max 100)
- **Add**: `chance:<percent>:<action>` - execute with X% chance

### Enhanced Placeholder System
- **Add**: `{health}` and `{max_health}` - player health values
- **Add**: `{food}` - player food level
- **Add**: `{level}` and `{xp}` - player XP level and percentage
- **Add**: `{world}`, `{x}`, `{y}`, `{z}` - player location
- **Add**: `{gamemode}` - player gamemode (survival, creative, etc.)
- **Add**: `{time}` - world time (day/night)
- **Add**: `{weather}` - world weather (clear/rain/storm)
- **Add**: `{playtime}` and `{playtime_hours}` - total playtime
- **Add**: `{ping}` - player ping in ms
- **Add**: `{uuid}` and `{uuid_short}` - player UUID
- **Add**: `{bar_health}`, `{bar_food}`, `{bar_xp}` - visual progress bars (10 chars)
- **Add**: `{progress:current:max:width:filledColor:emptyColor}` - custom progress bar generator

### Visual Effects & Item Enhancements
- **Add**: `firework:<color>:<type>:<power>` action - launch fireworks at player
- **Add**: `particle:<type>:<count>` action - spawn particles at player
- **Add**: `enchantments` item option - add enchantments to GUI items
- **Add**: `potionColor` item option - set custom potion color
- **Add**: `potionEffects` item option - add potion effects to potion items

### Player Control Actions
- **Add**: `bossbar:<text>:<color>:<seconds>` action - show temporary boss bar
- **Add**: `cleareffects` action - clear all potion effects from player
- **Add**: `cleareffect:<type>` action - clear specific potion effect
- **Add**: `velocity:up:<power>` or `velocity:x:y:z` action - apply velocity to player
- **Add**: `sudo:<command>` action - make player run command
- **Add**: `op:<command>` action - run command with temporary OP

### Join Tracking & Events
- **Add**: Login streak tracking (consecutive days played)
- **Add**: Max login streak tracking
- **Add**: Total logins counter
- **Add**: Anniversary detection and celebration messages
- **Add**: `FirstJoinEvent` custom event for other plugins to hook into
- **Add**: `ReturningPlayerEvent` custom event with streak and anniversary info
- **Add**: Streak milestone messages (7, 14, 30, 60, 90, 100, 365 days)
- **Add**: Referral system (track who invited new players)
- **Add**: Player notes system for admins

### Discord/Slack Webhooks
- **Add**: Webhook notifications for first-time joins
- **Add**: Webhook notifications for returning players
- **Add**: Customizable webhook messages with placeholders
- **Add**: Anniversary notifications in webhooks

### Dependencies
- **Update**: Adventure API to 4.17.0 (from 4.14.0)
- **Update**: Adventure Platform Bukkit to 4.3.4 (from 4.3.1)
- **Update**: bStats to 3.0.3 (from 3.0.2)

---

## 1.7.3 (Unreleased)

- Add: Daily telemetry reset scheduling with persistence of last reset timestamp
  - Config: `telemetry.reset.enabled` (bool), `telemetry.reset.time` (HH:mm server local time)
  - Persists last reset under `telemetry.yml` as both epoch millis and formatted date
  - `/firstlogin metrics` now shows the last reset date
- Add: New PlaceholderAPI placeholder `%firstlogin_metrics_reset_date%` (formatted by `formatting.datePattern`)
- Add: Configurable debounce for async players.yml saving and debug logs
  - Config: `asyncSave.players.debounceTicks` (ticks)
  - Debug toggles: `debug.saves` and `debug.telemetry`

- Add: Track and expose next scheduled telemetry reset time
  - `/firstlogin metrics` now shows the next reset time
  - New PAPI placeholders:
    - `%firstlogin_metrics_last_reset_ts%` – epoch millis of last telemetry reset (0 if never)
    - `%firstlogin_metrics_next_reset_date%` – formatted next scheduled telemetry reset time
    - `%firstlogin_metrics_next_reset_ts%` – epoch millis of next scheduled telemetry reset (0 if disabled)
    - `%firstlogin_metrics_next_reset_in_seconds%`
    - `%firstlogin_metrics_next_reset_in_minutes%`
    - `%firstlogin_metrics_next_reset_in_hours%`
    - `%firstlogin_metrics_next_reset_pretty%`
    - `%firstlogin_metrics_last_reset_pretty%`

- Add: Runtime telemetry reset toggles via `/firstlogin set`
  - Keys: `telemetry.reset.enabled` (bool), `telemetry.reset.time` (HH:mm)
  - Changes persist immediately and reschedule the next reset right away
- Add: New `/firstlogin metrics` subcommands for convenience
  - `when` – shows last and next reset with pretty "ago"/"in" durations
  - `now` – forces an immediate telemetry reset and recalculates schedule
- Update: Tab completion includes new `set` keys and `metrics` subcommands

- Add: PlaceholderAPI expansion runtime toggle and dynamic reload
  - Config: `placeholderapi.enabled` (bool). When true and PlaceholderAPI is installed, the FirstLogin expansion is registered.
  - `/firstlogin reload` re-evaluates `placeholderapi.enabled` and dynamically registers/unregisters the expansion at runtime.

- Change: Mark particle effects and animated guide as experimental (disabled by default)
  - Config: `particles.enabled` and `animatedGuide.enabled` now default to `false`
  - Lazy initialization on enable; `/firstlogin reload` will initialize/teardown these managers at runtime based on toggles

- Add: Powerful in-game GUI Admin Editor (all subcommands support tab completion)
  - `gui list` and `gui list filter=<prefix>` – list items with slot/page/material
  - `gui listpage <n>` – list items on a specific page
  - `gui open [player] [page]` – preview the GUI
  - `gui additem <key> [slot] [material]`, `gui set <key> <path> <value>`, `gui move <key> <slot>`, `gui remove <key>`
  - `gui fill <material> [name] [lore...]` – set filler panes
  - `gui normalize` – clamp slots into valid range
  - `gui validate actions|layout|pages|all` – validation including `actions strict` for deep checks
  - `gui preview <key> [player]` – open the page containing the item
  - `gui export <key>`, `gui jsonexport <key>` – export a single item
  - `gui import <file.yml> [key] [dry] [overwrite]` – import with dry-run and overwrite support
  - `gui fixduplicates [verbose]` – resolve duplicate slots across pages (verbose shows moved items)
  - `gui massset <path> <value> [filter=<prefix>] [page=<n>] [dry|confirm]` – bulk apply; dry by default unless `confirm`
  - `gui rename <oldKey> <newKey>`, `gui movepage <from> <to>`, `gui clearpage <n> confirm`
  - Safety: Auto backup `config.backup-YYYYMMDD-HHMMSS.yml` before any mutation; `gui undo` restores latest

- Update: README and `plugin.yml` usage extended with the new GUI editor, examples, and safety notes

- Add: New PAPI placeholders for rule acceptance counters
  - `%firstlogin_rules_pending_count%`
  - `%firstlogin_rules_accepted_count%`

## 1.7.2 (2025‑08‑24)

- Add: Optional confirmation dialog before accepting rules in the Welcome GUI (`welcomeGui.confirmOnAccept`)
- Add: In-plugin telemetry counters for daily GUI opens and rules accepted; view/reset via `/firstlogin metrics [reset]`
- Add: Runtime command toggles via `/firstlogin set <key> <value>` (no file edit required)
  - Keys: `welcomeGui.reopenOnJoinUntilAccepted`, `welcomeGui.blockCloseUntilAccepted`, `welcomeGui.confirmOnAccept`, `welcomeGui.rulesVersion`, `debug.gui`, `debug.inventory`
- Add: Granular admin permissions for each `/firstlogin` subcommand under `firstlogin.admin.*`
- Add: New admin subcommands with tab-completion: `set`, `metrics`
- Update: `plugin.yml` usage expanded and permissions detailed for new subcommands

- Change: Switch Welcome GUI persistence to asynchronous player data saving (non-blocking writes to `players.yml`)

- Add: Persist player timestamps to `players.yml`
  - `timestamps.<uuid>.first_join` – epoch millis of first join (fallback to Bukkit firstPlayed)
  - `timestamps.<uuid>.rules_accepted` – epoch millis when rules were accepted
- Add: PlaceholderAPI placeholders for timestamps and status
  - `%firstlogin_first_join_date%`, `%firstlogin_rules_accepted_date%` (formatted via `formatting.datePattern`)
  - `%firstlogin_days_since_first_join%`, `%firstlogin_days_since_rules_accepted%`
  - `%firstlogin_first_join_ts%`, `%firstlogin_rules_accepted_ts%`
  - `%firstlogin_rules_version_accepted%`, `%firstlogin_rules_pending%`
- Add: PlaceholderAPI telemetry + join order placeholders
  - `%firstlogin_gui_opens_today%`, `%firstlogin_rules_accepted_today%`
  - `%firstlogin_item_clicks_today_<key>%` (per-GUI-item click counter)
  - `%firstlogin_join_order%` (alias `%firstlogin_join_number%`) – 1-based join order across known players
- Note: `formatting.datePattern` defaults to `yyyy-MM-dd HH:mm:ss` and can be customized.

## 1.7.1 (2025‑08‑24)

- Fix: Eliminated server stalls when opening the Welcome GUI by removing reflection and opening the GUI after a safer delay
- Fix: Fully blocked item movement while the Welcome GUI is open (clicks, drags, creative actions, drops, hand swapping)
- Add: Player overload for `sendMsg(Player, String, Player, int)` that delegates to the `CommandSender` version
- Add: Public helpers to avoid reflection (`playersToDate()`, messaging helpers)
- Add: Locale-aware message accessors (`messagesFor()`, `msgFor()`, `msgListFor()`)
- Change: Increased/validated default `welcomeGui.openDelayTicks` to 40 ticks to avoid NMS init stalls
- Internal: Strengthened event handlers in `firstlogin/gui/WelcomeGui.java` at multiple priorities
- Performance: Players-to-date count warmed up asynchronously on enable to avoid main-thread I/O stalls
- Build: Bump version to 1.7.1
- Add: MiniMessage and PlaceholderAPI parsing for GUI item actions (`command:`, `url:`) and on-rules-accepted commands
- Add: Per-item permission support in Welcome GUI (`items.*.permission`, `items.*.hideIfNoPermission`) and `requires: perm:<node>`
- Add: `welcomeGui.reopenOnJoinUntilAccepted` to reopen GUI on every join until rules are accepted
- Add: Debug toggles `debug.gui` and `debug.inventory` with detailed GUI/inventory cancel logs
- Toggleable options
  - New in 1.7.1:
    - `welcomeGui.openDelayTicks` – delay before opening the Welcome GUI after first join (ticks)
    - `welcomeGui.reopenOnJoinUntilAccepted` – reopen GUI on join until rules accepted
    - `debug.gui`, `debug.inventory` – debug logging for GUI and inventory events
  - Existing toggles (for reference):
    - `formatting.useMiniMessage`, `formatting.usePlaceholders`, `formatting.usePlaceholderAPI`
    - `firstJoinVisuals.title.enabled`, `firstJoinVisuals.actionbar.enabled`, `firstJoinVisuals.sound.enabled`
    - `messageGlobal.enabled`, `message.enabled`, `messageBack.enabled`
    - `welcomeGui.enabled`, `welcomeGui.blockCloseUntilAccepted`
    - `metrics.enabled`
  - Per-item actions/flags
    - Actions: `message:`, `url:`, `command:`, `flag:set`
    - Toggles: `closeOnClick`, `once`, `cooldownSeconds`, `requires`, `clickSound`
    - Permissions: `permission`, `hideIfNoPermission` and `requires: perm:<node>`
    - Notes: `requires: "flag:rules"` gates items until rules are accepted; `clickSound` supports `name`, `volume`, and `pitch`

## 1.7 (2025‑08‑22)

- New: Full tab completion for `/firstlogin` subcommands
  - `locale <locale|reset>` with dynamic locale tag discovery from data folder
  - `clearcooldown <player> <key|all>`
  - `clearflag <player> <flag|all>`
  - `gui <open|accept|trigger>`
  - `seen <player>` / `reset <player|all>` suggest player names
- New: Automatic extraction of bundled locale files (`messages_*.yml`) to the plugin data folder on startup and on `/firstlogin reload`
- Update: `plugin.yml` usage reflects new subcommands
- Update: `messages.yml` and `messages_en_us.yml` include keys for new commands
- Internal: Refactored `onTabComplete` and deduped locale extraction on reload
- Build: Bump version to 1.7

## 1.6 (2025‑08‑22)

- New: MiniMessage formatting with legacy color fallback
  - Supports Adventure MiniMessage tags (e.g., <green>, <gray>)
  - Seamless fallback for legacy "&" color codes when MiniMessage isn’t used in a line
- New: First-join visuals (configurable)
  - Title + subtitle with fade timings  
  - Optional action bar message
  - Optional sound to celebrate first join
- New: Admin command `/firstlogin` with subcommands
  - `reload` – reloads config and messages
  - `seen <player>` – checks if a player has been marked as "seen" by this plugin
  - `reset <player|all>` – resets the seen state (per player or all)
- New: Returning player gate
  - Only send the "messageBack" greeting if the player has been offline for at least `returningGate.minDaysOffline` days (configurable)
- New: Placeholder support
  - Built-in placeholders: `{player}`, `{online}`, `{total}`, `{owner}`
  - Optional PlaceholderAPI integration (if installed and enabled in config)
- New: bStats metrics (configurable)
  - Controlled by `metrics.enabled`
  - `metrics.pluginId` added (now set to your plugin ID)
- Quality: Better logging and configuration handling
  - Logs the plugin version dynamically from `plugin.yml`
  - Replaced deprecated YAML header API usage
  - Removed unused imports and minor code cleanups
- Build: Shaded jar with dependencies (Adventure + bStats) for easy deployment
- Branding: Updated author to "BooPug Studios"

### Upgrade notes from older versions
- Drop-in replacement. Keep your existing `config.yml` and adjust new sections:
  - `formatting` (MiniMessage, placeholders, PlaceholderAPI)
  - `firstJoinVisuals` (title/actionbar/sound)
  - `returningGate.minDaysOffline`
  - `metrics` (enable/disable + pluginId)
- Restart the server (preferred) after replacing the jar.

---

## 1.4 and earlier
- Initial release and incremental fixes (pre‑Adventure formatting).
