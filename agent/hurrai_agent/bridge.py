"""Client for the AgentBridge running inside the HurrAI game client.

The client must be started with -Dhurrai.bridge=true (see Play_Agent.bat / Play_Agent.sh).
It listens on 127.0.0.1:37771 and speaks newline-delimited JSON.
"""
import itertools
import json
import socket
import time


class BridgeError(Exception):
    """The client rejected a command (bad arguments, nothing to click, and so on)."""


class Bridge:
    def __init__(self, host="127.0.0.1", port=37771, timeout=60.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._ids = itertools.count(1)
        self._sock = None
        self._file = None

    def connect(self, retry_for=300.0):
        """Connect, retrying while the client is still starting or the character is loading."""
        deadline = time.monotonic() + retry_for
        while True:
            try:
                self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
                self._file = self._sock.makefile("rw", encoding="utf-8", newline="\n")
                return self
            except OSError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(2)

    def close(self):
        if self._sock:
            self._sock.close()
            self._sock = self._file = None

    def __enter__(self):
        return self.connect()

    def __exit__(self, *exc):
        self.close()

    def call(self, cmd, **args):
        req = {"id": next(self._ids), "cmd": cmd, **args}
        self._file.write(json.dumps(req) + "\n")
        self._file.flush()
        line = self._file.readline()
        if not line:
            raise ConnectionError("game client closed the bridge")
        resp = json.loads(line)
        if not resp.get("ok"):
            raise BridgeError(f"{cmd}: {resp.get('error')}")
        return resp.get("result")

    # Convenience wrappers, one per bridge command.

    def state(self):
        return self.call("state")

    def gobs(self, radius=330.0, filter=None):
        """Nearby game objects, nearest first. Distances are in world units (11 per tile)."""
        return self.call("gobs", radius=radius, filter=filter)

    def walk(self, x, y):
        return self.call("walk", x=x, y=y)

    def path_to_gob(self, gob_id):
        return self.call("path_to_gob", gob=gob_id)

    def click_gob(self, gob_id, button=3, mods=0):
        return self.call("click_gob", gob=gob_id, button=button, mods=mods)

    def pick_menu(self, option):
        """Choose an option in the open right-click (flower) menu; None closes it."""
        return self.call("pick_menu", option=option)

    def item(self, item_id, action):
        """action: take, transfer, drop or interact."""
        return self.call("item", item=item_id, action=action)

    def wait_idle(self, timeout_ms=30000):
        return self.call("wait_idle", timeout=timeout_ms)

    def say(self, text):
        return self.call("say", text=text)
