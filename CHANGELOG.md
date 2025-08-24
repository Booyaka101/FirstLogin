# FirstLogin – Changelog

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
