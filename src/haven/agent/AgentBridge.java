package haven.agent;

import haven.*;
import haven.Window;

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
