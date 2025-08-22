# FirstLogin

A lightweight, friendly first-join experience plugin for Spigot/Paper. Shows custom messages for first-time players, optional visuals (title/action bar/sound), and simple server stats. Now with Adventure MiniMessage support and seamless legacy color fallback.

Author: BooPug Studios
Version: 1.6
MC: 1.20+ (Java 17)

## Features
- First-join broadcast and private welcome message
- MiniMessage formatting (<green>, <gray>, etc) with legacy "&" color fallback
- Configurable visuals: title, action bar, sound
- Returning player gate (only greet again if offline X days)
- Simple stats: player count to date, online count
- Built-in placeholders: {player}, {online}, {total}, {owner}
- Optional PlaceholderAPI support
- Admin command: /firstlogin (reload, seen, reset)
- bStats metrics (optional)

## Installation
1. Place `target/firstlogin-1.6.jar` into your server `plugins/` folder.
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
- `/firstlogin reload|seen <player>|reset <player|all>` – admin utilities

## Permissions
- `firstlogin.admin` – use `/firstlogin` admin commands (default: op)
- `firstlogin.command.listp` – use `/listp` (default: op)
- `firstlogin.command.pnames` – use `/pnames` (default: op)
- `firstlogin.command.owner` – use `/owner` (default: op)
- `firstlogin.command.onlinep` – use `/onlinep` (default: op)
- `firstlogin.command.firsthelp` – use `/firsthelp` (default: true)

## Configuration highlights
- Formatting toggles under `formatting:`
  - `useMiniMessage` – enables Adventure MiniMessage
  - `usePlaceholders` – built-ins like {player}
  - `usePlaceholderAPI` – resolves PAPI placeholders if installed
- Visuals under `firstJoinVisuals:` (title/actionbar/sound)
- Returning player gating under `returningGate.minDaysOffline`
- Metrics under `metrics.enabled` and `metrics.pluginId`

Example MiniMessage title in config:
```yaml
firstJoinVisuals:
  title:
    enabled: true
    title: "<green>Welcome, {player}!"
    subtitle: "<gray>Enjoy your stay."
```
If you prefer legacy, just use `&` color codes in messages; the plugin will render them correctly.

## Metrics (bStats)
- Opt-out globally via `plugins/bStats/config.yml` or per-plugin by setting `metrics.enabled: false`.
- `metrics.pluginId` is set to your bStats ID in `config.yml`.

## Changelog
See `CHANGELOG.md` for full details of 1.6.

## Support
Open an issue or discussion on your repository, or contact BooPug Studios through your preferred channel.
