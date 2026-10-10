"""Simple foraging loop: walk to the nearest forageable, pick it, repeat.

When nothing is left in range it wanders --wander-tiles toward ground it hasn't searched yet
(up to --max-wander-steps times) and keeps going. When the bag fills up it casts
"Travel to your hearth fire" to go home (unless --no-hearth).

Drinks when stamina gets low, and makes a pitstop at the nearest shallow fresh water
(within --water-radius tiles) to refill its containers when they run low or empty.

Run the game with Play_Agent.bat (or Play_Agent.sh), log in and choose a character, then:

    python forage.py
"""
import argparse
import math
import random
import time

from hurrai_agent import Bridge, BridgeError


def water_pitstop(game, args, why):
    """Walk to nearby water and refill. Returns True when containers were refilled."""
    spot = game.find_water(radius_tiles=args.water_radius)
    if spot is None:
        print(f"{why}, but no fresh water within {args.water_radius} tiles.")
        return False
    print(f"{why}: pitstop at water {spot['tiles']:.0f} tiles away.")
    game.walk(spot["x"], spot["y"])
    if not game.wait_idle(55000):  # under the bridge's 60 s socket timeout
        print("  Couldn't reach the water.")
        return False
    try:
        r = game.refill()
    except BridgeError as e:
        print(f"  Refill failed: {e}")
        return False
    print(f"  Refilled: {r['before']:.2f} -> {r['water']:.2f} of {r['capacity']:.2f} water.")
    return r["water"] > r["before"]


def inventory_snapshot(st):
    """What's in the main inventory, to tell whether a pick actually gave us something."""
    inv = next((i for i in st["inventories"] if i["main"]), None)
    if inv is None:
        return None
    return inv["free"], sorted((i["id"], i["name"]) for i in inv["items"])


def wander(game, args, visited, step):
    """Walk ~--wander-tiles to the compass point farthest from everywhere already searched.

    Tries the next-best direction when the path is blocked (we barely moved). Returns False
    when no direction got us anywhere.
    """
    st = game.state()
    x, y = st["x"], st["y"]
    dist = args.wander_tiles * 11
    candidates = []
    offset = random.uniform(0, math.pi / 4)  # don't always walk the same 8 rays
    for k in range(8):
        a = offset + k * math.pi / 4
        cx, cy = x + math.cos(a) * dist, y + math.sin(a) * dist
        novelty = min(math.hypot(cx - vx, cy - vy) for vx, vy in visited)
        candidates.append((novelty, cx, cy))
    candidates.sort(reverse=True)
    for novelty, cx, cy in candidates[:3]:
        print(f"Wander {step}/{args.max_wander_steps}: nothing in range, heading {args.wander_tiles:.0f} tiles "
              f"toward unexplored ground ({novelty / 11:.0f} tiles from anywhere searched).")
        game.walk(cx, cy)
        game.wait_idle(55000)
        now = game.state()
        moved = math.hypot(now["x"] - x, now["y"] - y)
        if moved >= dist / 3:
            return True
        print(f"  Only got {moved / 11:.0f} tiles, path looks blocked; trying another direction.")
        visited.append((cx, cy))  # don't pick that blocked spot again
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--filter", default="gfx/terobjs/herbs/",
                    help="resource-name substring of things to pick (default: wild herbs)")
    ap.add_argument("--option", default="Pick", help="right-click menu option to choose")
    ap.add_argument("--radius", type=float, default=11 * 25, help="search radius in world units (11 per tile)")
    ap.add_argument("--drink-below", type=float, default=0.5, help="drink when stamina drops below this (0-1)")
    ap.add_argument("--drink-to", type=float, default=0.95, help="keep drinking until stamina reaches this (0-1)")
    ap.add_argument("--no-drink", action="store_true", help="never drink, just stop at --min-stamina")
    ap.add_argument("--refill-below", type=float, default=0.5,
                    help="make a water pitstop when containers are below this fraction full (0-1)")
    ap.add_argument("--water-radius", type=int, default=50, help="how far to go for water, in tiles")
    ap.add_argument("--no-refill", action="store_true", help="never go looking for water")
    ap.add_argument("--min-stamina", type=float, default=0.25, help="stop when stamina is still below this after drinking (0-1)")
    ap.add_argument("--no-hearth", action="store_true",
                    help="when the bag is full just stop, instead of travelling to the hearth fire")
    ap.add_argument("--max-picks", type=int, default=50)
    ap.add_argument("--wander-tiles", type=float, default=25,
                    help="how far to walk to a fresh spot when nothing is left in range, in tiles")
    ap.add_argument("--max-wander-steps", type=int, default=10,
                    help="stop after wandering this many times (0 = never wander)")
    ap.add_argument("--port", type=int, default=37771)
    args = ap.parse_args()

    skipped = set()
    no_water_near = False  # don't keep scanning for water every loop once we know there's none
    with Bridge(port=args.port) as game:
        game.say("Agent connected: foraging")
        picks = 0
        wander_steps = 0
        visited = []  # spots we've already searched from, so wandering heads somewhere new
        while picks < args.max_picks:
            if not args.no_refill and not no_water_near:
                ws = game.water_status()
                if ws["fraction"] is not None and ws["fraction"] < args.refill_below:
                    if not water_pitstop(game, args, f"Water low ({ws['fraction']:.0%})"):
                        no_water_near = True

            st = game.state()
            stam = st["meters"].get("stam")
            if stam is not None and stam < args.drink_below and not args.no_drink:
                r = game.drink(target=args.drink_to)
                print(f"Drank {r['sips']} time(s): stamina {r['before']:.0%} -> {r['after']:.0%} ({r['stopped']})")
                stam = r["after"]
                if r["stopped"] == "no_water" and stam < args.drink_to and not args.no_refill:
                    if water_pitstop(game, args, "Out of water"):
                        no_water_near = False
                        r = game.drink(target=args.drink_to)
                        print(f"Drank {r['sips']} time(s): stamina {r['before']:.0%} -> {r['after']:.0%} ({r['stopped']})")
                        stam = r["after"]
                    else:
                        game.say("Agent: out of water")
            if stam is not None and stam < args.min_stamina:
                print(f"Stamina low ({stam:.0%}), stopping.")
                break
            main_inv = next((i for i in st["inventories"] if i["main"]), None)
            if main_inv is not None and main_inv["free"] == 0:
                if args.no_hearth:
                    print("Inventory full, stopping.")
                else:
                    print("Inventory full: travelling to the hearth fire.")
                    r = game.hearth()
                    if r["arrived"]:
                        print(f"  Home ({r['tiles']:.0f} tiles from where we were).")
                        game.say("Agent: bag full, went home to the hearth fire")
                    else:
                        print("  Hearth travel didn't happen (interrupted, or no hearth fire set?).")
                break

            targets = [g for g in game.gobs(radius=args.radius, filter=args.filter) if g["id"] not in skipped]
            if not targets:
                visited.append((st["x"], st["y"]))
                if wander_steps >= args.max_wander_steps:
                    print(f"Nothing left to forage in range, and used all {args.max_wander_steps} wander steps.")
                    break
                wander_steps += 1
                if not wander(game, args, visited, wander_steps):
                    print("Couldn't find anywhere new to walk to, stopping.")
                    break
                continue
            target = targets[0]
            print(f"Going to {target['res']} ({target['dist'] / 11:.1f} tiles away)")

            game.path_to_gob(target["id"])
            if not game.wait_idle(30000):
                print("  Couldn't reach it, skipping.")
                skipped.add(target["id"])
                continue
            try:
                game.click_gob(target["id"], button=3)
                game.pick_menu(args.option)
            except BridgeError as e:
                print(f"  {e}")
                skipped.add(target["id"])
                continue
            game.wait_idle(30000)
            time.sleep(0.5)
            if inventory_snapshot(game.state()) == inventory_snapshot(st):
                print("  Picked, but nothing new showed up in the inventory.")
                skipped.add(target["id"])
            else:
                picks += 1
        print(f"Done after {picks} picks, {wander_steps} wander steps.")


if __name__ == "__main__":
    main()
