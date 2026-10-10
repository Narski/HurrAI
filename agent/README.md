# HurrAI agent

Python side of the HurrAI bot. The game client runs an **AgentBridge** (`src/haven/agent/`) that
exposes game state and commands over a local socket; scripts here connect to it and play.

## Running

1. Build the client as usual (`ant`), then start it with the bridge turned on:
   `Play_Agent.bat` on Windows or `./Play_Agent.sh` on Linux/macOS.
   (These are the normal launchers plus `-Dhurrai.bridge=true`.)
2. Log in and choose a character. The bridge starts listening on `127.0.0.1:37771`
   once you're in the world. It only accepts connections from your own machine.
3. Run a script, for example `python forage.py` (Python 3.8+, no extra packages).

## Bridge protocol

One JSON object per line. Request `{"id": 1, "cmd": "state"}`, response
`{"id": 1, "ok": true, "result": {...}}` or `{"id": 1, "ok": false, "error": "..."}`.
Coordinates are world units (11 per tile).

| cmd | args | does |
| --- | --- | --- |
| `ping` | | returns `"pong"` |
| `state` | | position, meters (`hp`, `stam`, `nrj`), progress bar, hand, open flower menu, windows, inventories |
| `gobs` | `radius`, `filter` | nearby objects nearest first: `id`, `res`, `x`, `y`, `dist` |
| `walk` | `x`, `y` | pathfind to a point |
| `path_to_gob` | `gob` | pathfind next to an object |
| `click_gob` | `gob`, `button` (3 = right), `mods` | click an object without pathing |
| `pick_menu` | `option` | choose a right-click menu option (`null` closes the menu) |
| `item` | `item`, `action` | `take`, `transfer`, `drop` or `interact` on an inventory item |
| `wait_idle` | `timeout` (ms) | wait until not pathing, moving or working; `false` on timeout |
| `say` | `text` | show a message in the game's chat area |
