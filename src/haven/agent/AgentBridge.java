package haven.agent;

import haven.*;
import haven.Window;
import haven.automated.RefillWaterContainers;

import java.awt.Color;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static haven.OCache.posres;

/**
 * Local socket bridge that lets an external agent (see agent/ in the repo root) read game
 * state and send high-level commands. Speaks newline-delimited JSON on 127.0.0.1 only.
 *
 * Request:  {"id": 1, "cmd": "state", ...args}
 * Response: {"id": 1, "ok": true, "result": ...} or {"id": 1, "ok": false, "error": "..."}
 *
 * Enabled by launching the client with -Dhurrai.bridge=true (port: -Dhurrai.bridge.port, default 37771).
 */
public class AgentBridge implements Runnable {
    public static final int DEFAULT_PORT = 37771;
    private static AgentBridge current;

    private final GameUI gui;
    private final int port;
    private volatile boolean stop = false;
    private ServerSocket server;
    private Thread thread;

    private AgentBridge(GameUI gui, int port) {
	this.gui = gui;
	this.port = port;
    }

    public static boolean enabled() {
	return(Boolean.parseBoolean(System.getProperty("hurrai.bridge", "false")));
    }

    /** Called when a GameUI is attached; replaces any bridge left over from a previous character. */
    public static synchronized void start(GameUI gui) {
	if(!enabled())
	    return;
	if(current != null)
	    current.stop();
	int port = Integer.getInteger("hurrai.bridge.port", DEFAULT_PORT);
	current = new AgentBridge(gui, port);
	current.thread = new Thread(current, "AgentBridge");
	current.thread.setDaemon(true);
	current.thread.start();
    }

    public static synchronized void stop(GameUI gui) {
	if(current != null && current.gui == gui) {
	    current.stop();
	    current = null;
	}
    }

    private void stop() {
	stop = true;
	try {
	    if(server != null)
		server.close();
	} catch(IOException ignored) {
	}
	if(thread != null)
	    thread.interrupt();
    }

    @Override
    public void run() {
	try(ServerSocket ss = new ServerSocket()) {
	    server = ss;
	    ss.setReuseAddress(true);
	    ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
	    System.out.println("AgentBridge listening on 127.0.0.1:" + port);
	    while(!stop) {
		try(Socket sk = ss.accept()) {
		    serve(sk);
		} catch(IOException e) {
		    if(!stop)
			System.err.println("AgentBridge client error: " + e);
		}
	    }
	} catch(IOException e) {
	    if(!stop)
		System.err.println("AgentBridge could not listen on port " + port + ": " + e);
	}
    }

    private void serve(Socket sk) throws IOException {
	BufferedReader in = new BufferedReader(new InputStreamReader(sk.getInputStream(), StandardCharsets.UTF_8));
	Writer out = new BufferedWriter(new OutputStreamWriter(sk.getOutputStream(), StandardCharsets.UTF_8));
	String line;
	while(!stop && (line = in.readLine()) != null) {
	    if(line.isBlank())
		continue;
	    Map<String, Object> resp = new LinkedHashMap<>();
	    try {
		Object parsed = Json.parse(line);
		if(!(parsed instanceof Map))
		    throw new IllegalArgumentException("request must be a JSON object");
		@SuppressWarnings("unchecked")
		Map<String, Object> req = (Map<String, Object>)parsed;
		resp.put("id", req.get("id"));
		resp.put("result", handle(req));
		resp.put("ok", true);
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		return;
	    } catch(Exception e) {
		resp.put("ok", false);
		resp.put("error", String.valueOf(e.getMessage() != null ? e.getMessage() : e));
	    }
	    out.write(Json.write(resp));
	    out.write('\n');
	    out.flush();
	}
    }

    /* Commands */

    private Object handle(Map<String, Object> req) throws InterruptedException {
	String cmd = str(req, "cmd");
	switch(cmd) {
	case "ping": return("pong");
	case "state": return(state());
	case "gobs": return(gobs(dbl(req, "radius", 11 * 30), (String)req.get("filter")));
	case "walk": return(walk(dbl(req, "x"), dbl(req, "y")));
	case "path_to_gob": return(pathToGob(lng(req, "gob")));
	case "click_gob": return(clickGob(lng(req, "gob"), (int)dbl(req, "button", 3), (int)dbl(req, "mods", 0)));
	case "pick_menu": return(pickMenu((String)req.get("option")));
	case "item": return(item(lng(req, "item"), str(req, "action")));
	case "wait_idle": return(waitIdle((long)dbl(req, "timeout", 30000)));
	case "drink": return(drink(dbl(req, "target", 0.95), (long)dbl(req, "timeout", 30000)));
	case "water_status": return(waterStatus());
	case "find_water": return(findWater((int)dbl(req, "radius", 50)));
	case "refill": return(refill());
	case "hearth": return(hearth((long)dbl(req, "timeout", 45000)));
	case "say": gui.msg(str(req, "text"), Color.CYAN); return(true);
	default: throw new IllegalArgumentException("unknown cmd: " + cmd);
	}
    }

    private MapView map() {
	MapView map = gui.map;
	if(map == null)
	    throw new IllegalStateException("map not loaded yet");
	return(map);
    }

    private Gob player() {
	Gob pl = map().player();
	if(pl == null)
	    throw new IllegalStateException("player not in world yet");
	return(pl);
    }

    private Gob gob(long id) {
	Gob g = map().glob.oc.getgob(id);
	if(g == null)
	    throw new IllegalArgumentException("no such gob: " + id);
	return(g);
    }

    private Map<String, Object> state() {
	Map<String, Object> st = new LinkedHashMap<>();
	st.put("character", gui.chrid);
	Gob pl = (gui.map == null) ? null : gui.map.player();
	if(pl != null) {
	    st.put("x", pl.rc.x);
	    st.put("y", pl.rc.y);
	    st.put("moving", pl.getv() > 0);
	}
	Map<String, Object> meters = new LinkedHashMap<>();
	for(String m : new String[] {"hp", "stam", "nrj"}) {
	    IMeter.Meter mt = gui.getmeter(m, 0);
	    if(mt != null)
		meters.put(m, mt.a);
	}
	st.put("meters", meters);
	st.put("pathfinding", gui.map != null && gui.map.pfthread != null && gui.map.pfthread.isAlive());
	GameUI.Progress prog = gui.prog;
	st.put("progress", (prog == null) ? null : prog.prog);
	st.put("hand", (gui.vhand == null) ? null : itemInfo(gui.vhand));
	List<Object> wielded = new ArrayList<>();
	Equipory eq = gui.getequipory();
	if(eq != null) {
	    for(int slot : new int[] {HAND_L, HAND_R}) {
		if(eq.slots[slot] != null)
		    wielded.add(itemInfo(eq.slots[slot]));
	    }
	}
	st.put("wielded", wielded);
	st.put("flower_menu", flowerOptions());
	List<Object> wnds = new ArrayList<>();
	for(Widget w = gui.child; w != null; w = w.next) {
	    if(w instanceof Window && ((Window)w).cap != null)
		wnds.add(((Window)w).cap);
	}
	st.put("windows", wnds);
	List<Object> invs = new ArrayList<>();
	for(Inventory inv : gui.getAllInventories()) {
	    Map<String, Object> im = new LinkedHashMap<>();
	    Window w = inv.getparent(Window.class);
	    im.put("window", (w == null) ? null : w.cap);
	    im.put("main", inv == gui.maininv);
	    im.put("free", inv.getFreeSpace());
	    List<Object> items = new ArrayList<>();
	    for(Widget c = inv.child; c != null; c = c.next) {
		if(c instanceof WItem)
		    items.add(itemInfo((WItem)c));
	    }
	    im.put("items", items);
	    invs.add(im);
	}
	st.put("inventories", invs);
	return(st);
    }

    private Map<String, Object> itemInfo(WItem w) {
	Map<String, Object> m = new LinkedHashMap<>();
	m.put("id", (long)w.item.wdgid());
	try {
	    m.put("res", w.item.getres().name);
	} catch(Loading l) {
	    m.put("res", null);
	}
	m.put("name", w.item.getname());
	m.put("slot", Arrays.asList(w.c.x / Inventory.sqsz.x, w.c.y / Inventory.sqsz.y));
	return(m);
    }

    private List<Object> gobs(double radius, String filter) {
	Gob pl = player();
	List<Map<String, Object>> ret = new ArrayList<>();
	synchronized(map().glob.oc) {
	    for(Gob g : map().glob.oc) {
		if(g.id == pl.id)
		    continue;
		double d = g.rc.dist(pl.rc);
		if(d > radius)
		    continue;
		String res;
		try {
		    Resource r = g.getres();
		    res = (r == null) ? null : r.name;
		} catch(Loading l) {
		    res = null;
		}
		if(filter != null && (res == null || !res.contains(filter)))
		    continue;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", g.id);
		m.put("res", res);
		m.put("x", g.rc.x);
		m.put("y", g.rc.y);
		m.put("dist", d);
		ret.add(m);
	    }
	}
	ret.sort(Comparator.comparingDouble(m -> (Double)m.get("dist")));
	return(new ArrayList<>(ret));
    }

    private boolean walk(double x, double y) {
	player();
	map().pfLeftClick(new Coord2d(x, y).floor(), null);
	return(true);
    }

    private boolean pathToGob(long id) {
	map().pfLeftClickGob(gob(id), null);
	return(true);
    }

    /** Clicks a gob directly (no pathfinding). Button 3 is right-click, which opens its flower menu. */
    private boolean clickGob(long id, int button, int mods) {
	Gob g = gob(id);
	map().wdgmsg("click", Coord.z, g.rc.floor(posres), button, mods, 0, (int)g.id, g.rc.floor(posres), 0, -1);
	return(true);
    }

    private FlowerMenu flowerMenu() {
	return((gui.ui == null) ? null : gui.ui.root.getchild(FlowerMenu.class));
    }

    private List<Object> flowerOptions() {
	FlowerMenu fm = flowerMenu();
	if(fm == null || fm.opts == null)
	    return(null);
	List<Object> ret = new ArrayList<>();
	for(FlowerMenu.Petal p : fm.opts)
	    ret.add(p.name);
	return(ret);
    }

    /** Chooses the named option in the open flower menu, or closes it when option is null. */
    private boolean pickMenu(String option) throws InterruptedException {
	FlowerMenu fm = null;
	for(int i = 0; i < 25 && (fm = flowerMenu()) == null; i++)
	    Thread.sleep(40);
	if(fm == null)
	    throw new IllegalStateException("no flower menu open");
	if(option == null) {
	    fm.choose(null);
	    return(true);
	}
	for(FlowerMenu.Petal p : fm.opts) {
	    if(p.name.equalsIgnoreCase(option)) {
		fm.choose(p);
		return(true);
	    }
	}
	fm.choose(null);
	throw new IllegalArgumentException("menu has no option '" + option + "', had " + flowerOptions());
    }

    private WItem findItem(long id) {
	for(Inventory inv : gui.getAllInventories()) {
	    for(Widget c = inv.child; c != null; c = c.next) {
		if(c instanceof WItem && ((WItem)c).item.wdgid() == id)
		    return((WItem)c);
	    }
	}
	throw new IllegalArgumentException("no such item: " + id);
    }

    /** Item actions: take (to hand), transfer (to other open container), drop, interact (opens its menu). */
    private boolean item(long id, String action) {
	WItem w = findItem(id);
	switch(action) {
	case "take": w.item.wdgmsg("take", Coord.z); break;
	case "transfer": w.item.wdgmsg("transfer", Coord.z); break;
	case "drop": w.item.wdgmsg("drop", Coord.z); break;
	case "interact": w.item.wdgmsg("iact", Coord.z, gui.ui.modflags()); break;
	default: throw new IllegalArgumentException("unknown item action: " + action);
	}
	return(true);
    }

    private double stamina() {
	IMeter.Meter m = gui.getmeter("stam", 0);
	if(m == null)
	    throw new IllegalStateException("no stamina meter yet");
	return(m.a);
    }

    private boolean drinking() {
	Gob pl = (gui.map == null) ? null : gui.map.player();
	return(pl != null && Boolean.TRUE.equals(pl.imDrinking));
    }

    /**
     * Uses the game's own Drink action (same as the Drink button) until stamina reaches target.
     * The server picks the container (waterskin, flask, kuksa, bucket...). Stops early when a
     * sip doesn't raise stamina, which usually means the character is out of water.
     */
    private Map<String, Object> drink(double target, long timeout) throws InterruptedException {
	double before = stamina();
	long end = System.currentTimeMillis() + timeout;
	int sips = 0;
	String reason = "full";
	while(stamina() < target) {
	    if(System.currentTimeMillis() > end) {
		reason = "timeout";
		break;
	    }
	    double was = stamina();
	    gui.wdgmsg("act", "drink");
	    /* Wait for the drinking pose to start, then for it to finish. */
	    long started = System.currentTimeMillis();
	    while(!drinking() && System.currentTimeMillis() - started < 2500)
		Thread.sleep(50);
	    while(drinking() && stamina() < target && System.currentTimeMillis() < end)
		Thread.sleep(100);
	    Thread.sleep(300);
	    if(stamina() <= was + 0.005) {
		reason = "no_water";
		break;
	    }
	    sips++;
	}
	Map<String, Object> ret = new LinkedHashMap<>();
	ret.put("before", before);
	ret.put("after", stamina());
	ret.put("sips", sips);
	ret.put("stopped", reason);
	return(ret);
    }

    /** Every water-holding container the character carries: inventories, belt and the two pouch slots. */
    private List<WItem> waterContainers() {
	List<WItem> ret = new ArrayList<>();
	for(Inventory inv : gui.getAllInventories()) {
	    for(Widget c = inv.child; c != null; c = c.next) {
		if(c instanceof WItem)
		    ret.add((WItem)c);
	    }
	}
	Equipory eq = gui.getequipory();
	if(eq != null) {
	    for(int slot : new int[] {HAND_L, HAND_R, 19, 20}) {
		if(eq.slots[slot] != null)
		    ret.add(eq.slots[slot]);
	    }
	}
	ret.removeIf(w -> {
	    try {
		return(capacity(w.item.getres().name) == null);
	    } catch(Loading l) {
		return(true);
	    }
	});
	return(ret);
    }

    private static final int HAND_L = 6, HAND_R = 7;
    private static final float BUCKET_CAPACITY = 10.0f;

    private static boolean isBucket(String res) {
	return(res.startsWith("gfx/invobjs/bucket") || res.startsWith("gfx/invobjs/small/bucket"));
    }

    /** Litres a container holds, or null when it isn't a water container. */
    private static Float capacity(String res) {
	Float c = RefillWaterContainers.CONTAINER_CAPACITIES.get(res);
	if(c == null && isBucket(res))
	    c = BUCKET_CAPACITY;
	return(c);
    }

    /** Fills a bucket held in a hand slot: lift it onto the cursor, use it on the water, put it back. */
    private void refillHandBuckets(Coord2d water) throws InterruptedException {
	Equipory eq = gui.getequipory();
	if(eq == null)
	    return;
	for(int slot : new int[] {HAND_L, HAND_R}) {
	    WItem w = eq.slots[slot];
	    if(w == null)
		continue;
	    String res;
	    try {
		res = w.item.getres().name;
	    } catch(Loading l) {
		continue;
	    }
	    if(!isBucket(res))
		continue;
	    ItemInfo.Contents ci = null;
	    try {
		ci = w.item.getcontents();
	    } catch(Loading ignored) {
	    }
	    if(ci != null && ci.content != null && ci.content.name != null && !ci.content.name.equals("Water"))
		continue;  // holding seeds or milk or the like, leave it alone
	    if(ci != null && ci.content != null && ci.content.count >= BUCKET_CAPACITY - 0.01)
		continue;
	    w.item.wdgmsg("take", Coord.z);
	    for(int i = 0; i < 20 && gui.vhand == null; i++)
		Thread.sleep(50);
	    if(gui.vhand == null)
		continue;
	    gui.map.wdgmsg("itemact", Coord.z, water.floor(posres), 0);
	    Thread.sleep(800);
	    eq.wdgmsg("drop", slot);
	    for(int i = 0; i < 20 && gui.vhand != null; i++)
		Thread.sleep(50);
	}
    }

    /** How much water is carried: per-container contents and an overall fill fraction (null when no containers). */
    private Map<String, Object> waterStatus() {
	List<Object> conts = new ArrayList<>();
	double water = 0, cap = 0;
	for(WItem w : waterContainers()) {
	    String res = w.item.getres().name;
	    float capacity = capacity(res);
	    ItemInfo.Contents.Content content = null;
	    try {
		ItemInfo.Contents ci = w.item.getcontents();
		if(ci != null)
		    content = ci.content;
	    } catch(Loading ignored) {
	    }
	    String what = (content == null) ? null : content.name;
	    double amount = (content == null) ? 0 : content.count;
	    Map<String, Object> m = new LinkedHashMap<>();
	    m.put("id", (long)w.item.wdgid());
	    m.put("res", res);
	    m.put("contents", what);
	    m.put("amount", amount);
	    m.put("capacity", (double)capacity);
	    conts.add(m);
	    /* Only empty or water-filled containers count; a skin full of wine isn't refillable. */
	    if(what == null || what.equals("Water") || what.equals("Tea")) {
		cap += capacity;
		if(what != null)
		    water += amount;
	    }
	}
	Map<String, Object> ret = new LinkedHashMap<>();
	ret.put("containers", conts);
	ret.put("water", water);
	ret.put("capacity", cap);
	ret.put("fraction", (cap > 0) ? (Object)(water / cap) : null);
	return(ret);
    }

    private static boolean freshWater(MCache mc, Coord tc) {
	try {
	    Resource r = mc.tilesetr(mc.gettile(tc));
	    /* Shallow fresh water only: "deep" means swimming and the o* tiles are salt water. */
	    return(r != null && r.name.equals("gfx/tiles/water"));
	} catch(Loading l) {
	    return(false);
	}
    }

    /** Nearest shallow fresh-water tile within radius tiles, or null. Coordinates are the tile's centre in world units. */
    private Map<String, Object> findWater(int radius) {
	Gob pl = player();
	MCache mc = gui.ui.sess.glob.map;
	Coord ptc = pl.rc.floor(MCache.tilesz);
	Coord best = null;
	double bestd = Double.MAX_VALUE;
	for(int dy = -radius; dy <= radius; dy++) {
	    for(int dx = -radius; dx <= radius; dx++) {
		double d = Math.sqrt(dx * dx + dy * dy);
		if(d > radius || d >= bestd)
		    continue;
		Coord tc = ptc.add(dx, dy);
		if(freshWater(mc, tc)) {
		    best = tc;
		    bestd = d;
		}
	    }
	}
	if(best == null)
	    return(null);
	Coord2d c = best.mul(MCache.tilesz).add(MCache.tilesz.div(2));
	Map<String, Object> ret = new LinkedHashMap<>();
	ret.put("x", c.x);
	ret.put("y", c.y);
	ret.put("tiles", bestd);
	return(ret);
    }

    /** Fills every carried container with water. The character must be standing on a shallow fresh-water tile. */
    private Map<String, Object> refill() throws InterruptedException {
	Gob pl = player();
	MCache mc = gui.ui.sess.glob.map;
	if(!freshWater(mc, pl.rc.floor(MCache.tilesz)))
	    throw new IllegalStateException("not standing on shallow fresh water");
	double before = (double)waterStatus().get("water");
	RefillWaterContainers rw = new RefillWaterContainers(gui);
	for(int pass = 0; pass < 3; pass++) {
	    rw.refillContainers(pl.rc, null);
	    refillHandBuckets(pl.rc);
	    Thread.sleep(600);
	    Map<String, Object> st = waterStatus();
	    if((double)st.get("water") >= (double)st.get("capacity") - 0.01)
		break;
	}
	Map<String, Object> st = waterStatus();
	st.put("before", before);
	return(st);
    }

    /**
     * Casts "Travel to your hearth fire" and waits for the teleport, which shows up as the
     * character jumping far further than it could walk in one poll.
     */
    private Map<String, Object> hearth(long timeout) throws InterruptedException {
	Coord2d from = player().rc;
	gui.act("travel", "hearth");
	long end = System.currentTimeMillis() + timeout;
	boolean arrived = false;
	while(System.currentTimeMillis() < end) {
	    Thread.sleep(250);
	    Gob pl = (gui.map == null) ? null : gui.map.player();
	    if(pl != null && pl.rc.dist(from) > 11 * 15) {
		arrived = true;
		break;
	    }
	}
	if(arrived) {
	    /* Let the new area's map and objects load before the agent looks around. */
	    Thread.sleep(2000);
	}
	Map<String, Object> ret = new LinkedHashMap<>();
	Coord2d to = player().rc;
	ret.put("arrived", arrived);
	ret.put("from", Arrays.asList(from.x, from.y));
	ret.put("to", Arrays.asList(to.x, to.y));
	ret.put("tiles", to.dist(from) / 11);
	return(ret);
    }

    /** Waits until the character has stopped pathing, moving and working on a progress bar. */
    private boolean waitIdle(long timeout) throws InterruptedException {
	long end = System.currentTimeMillis() + timeout;
	Thread.sleep(300);
	while(System.currentTimeMillis() < end) {
	    Gob pl = (gui.map == null) ? null : gui.map.player();
	    boolean pf = gui.map != null && gui.map.pfthread != null && gui.map.pfthread.isAlive();
	    boolean moving = pl != null && pl.getv() > 0;
	    boolean working = gui.prog != null && gui.prog.prog >= 0;
	    if(!pf && !moving && !working)
		return(true);
	    Thread.sleep(100);
	}
	return(false);
    }

    /* Argument helpers */

    private static String str(Map<String, Object> req, String k) {
	Object v = req.get(k);
	if(v == null)
	    throw new IllegalArgumentException("missing '" + k + "'");
	return(String.valueOf(v));
    }

    private static double dbl(Map<String, Object> req, String k) {
	Object v = req.get(k);
	if(!(v instanceof Number))
	    throw new IllegalArgumentException("missing number '" + k + "'");
	return(((Number)v).doubleValue());
    }

    private static double dbl(Map<String, Object> req, String k, double def) {
	return(req.get(k) instanceof Number ? ((Number)req.get(k)).doubleValue() : def);
    }

    private static long lng(Map<String, Object> req, String k) {
	return((long)dbl(req, k));
    }
}
