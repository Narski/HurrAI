"""Simple foraging loop: walk to the nearest forageable, pick it, repeat.

Run the game with Play_Agent.bat (or Play_Agent.sh), log in and choose a character, then:

    python forage.py
"""
import argparse
import time

from hurrai_agent import Bridge, BridgeError


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--filter", default="gfx/terobjs/herbs/",
                    help="resource-name substring of things to pick (default: wild herbs)")
    ap.add_argument("--option", default="Pick", help="right-click menu option to choose")
    ap.add_argument("--radius", type=float, default=11 * 25, help="search radius in world units (11 per tile)")
    ap.add_argument("--min-stamina", type=float, default=0.25, help="stop when stamina drops below this (0-1)")
    ap.add_argument("--max-picks", type=int, default=50)
    ap.add_argument("--port", type=int, default=37771)
    args = ap.parse_args()

    skipped = set()
    with Bridge(port=args.port) as game:
        game.say("Agent connected: foraging")
        picks = 0
        while picks < args.max_picks:
            st = game.state()
            stam = st["meters"].get("stam")
            if stam is not None and stam < args.min_stamina:
                print(f"Stamina low ({stam:.0%}), stopping.")
                break
            main_inv = next((i for i in st["inventories"] if i["main"]), None)
            if main_inv is not None and main_inv["free"] == 0:
                print("Inventory full, stopping.")
                break

            targets = [g for g in game.gobs(radius=args.radius, filter=args.filter) if g["id"] not in skipped]
            if not targets:
                print("Nothing left to forage in range.")
                break
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
            picks += 1
            time.sleep(0.5)
        print(f"Done after {picks} picks.")


if __name__ == "__main__":
    main()
