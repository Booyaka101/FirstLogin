# FirstLogin – Changelog

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
