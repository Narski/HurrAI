package haven.agent;

import java.util.*;

/** Minimal JSON reader/writer for the agent bridge, so the client needs no extra library. */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) {
	this.s = s;
    }

    public static Object parse(String s) {
	Json j = new Json(s);
	j.ws();
	Object v = j.value();
	j.ws();
	if(j.p != s.length())
	    throw j.err("trailing data");
	return(v);
    }

    private IllegalArgumentException err(String msg) {
	return(new IllegalArgumentException("JSON " + msg + " at " + p));
    }

    private void ws() {
	while(p < s.length() && Character.isWhitespace(s.charAt(p)))
	    p++;
    }

    private char peek() {
	if(p >= s.length())
	    throw err("unexpected end");
	return(s.charAt(p));
    }

    private void expect(char c) {
	if(peek() != c)
	    throw err("expected '" + c + "'");
	p++;
    }

    private Object value() {
	char c = peek();
	switch(c) {
	case '{': return(object());
	case '[': return(array());
	case '"': return(string());
	case 't': lit("true"); return(Boolean.TRUE);
	case 'f': lit("false"); return(Boolean.FALSE);
	case 'n': lit("null"); return(null);
	default: return(number());
	}
    }

    private void lit(String l) {
	if(!s.startsWith(l, p))
	    throw err("bad literal");
	p += l.length();
    }

    private Map<String, Object> object() {
	Map<String, Object> m = new LinkedHashMap<>();
	expect('{'); ws();
	if(peek() == '}') {p++; return(m);}
	while(true) {
	    ws();
	    String k = string();
	    ws(); expect(':'); ws();
	    m.put(k, value());
	    ws();
	    if(peek() == ',') {p++; continue;}
	    expect('}');
	    return(m);
	}
    }

    private List<Object> array() {
	List<Object> l = new ArrayList<>();
	expect('['); ws();
	if(peek() == ']') {p++; return(l);}
	while(true) {
	    ws();
	    l.add(value());
	    ws();
	    if(peek() == ',') {p++; continue;}
	    expect(']');
	    return(l);
	}
    }

    private String string() {
	expect('"');
	StringBuilder b = new StringBuilder();
	while(true) {
	    char c = peek(); p++;
	    if(c == '"')
		return(b.toString());
	    if(c != '\\') {
		b.append(c);
		continue;
	    }
	    char e = peek(); p++;
	    switch(e) {
	    case 'n': b.append('\n'); break;
	    case 't': b.append('\t'); break;
	    case 'r': b.append('\r'); break;
	    case 'b': b.append('\b'); break;
	    case 'f': b.append('\f'); break;
	    case 'u':
		if(p + 4 > s.length())
		    throw err("bad escape");
		b.append((char)Integer.parseInt(s.substring(p, p + 4), 16));
		p += 4;
		break;
	    default: b.append(e);
	    }
	}
    }

    private Number number() {
	int st = p;
	while(p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0)
	    p++;
	String n = s.substring(st, p);
	if(n.isEmpty())
	    throw err("unexpected character");
	if(n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0)
	    return(Long.parseLong(n));
	return(Double.parseDouble(n));
    }

    public static String write(Object v) {
	StringBuilder b = new StringBuilder();
	write(b, v);
	return(b.toString());
    }

    private static void write(StringBuilder b, Object v) {
	if(v == null) {
	    b.append("null");
	} else if(v instanceof String) {
	    quote(b, (String)v);
	} else if(v instanceof Double || v instanceof Float) {
	    double d = ((Number)v).doubleValue();
	    if(Double.isNaN(d) || Double.isInfinite(d))
		b.append("null");
	    else
		b.append(d);
	} else if(v instanceof Number || v instanceof Boolean) {
	    b.append(v);
	} else if(v instanceof Map) {
	    b.append('{');
	    boolean f = true;
	    for(Map.Entry<?, ?> e : ((Map<?, ?>)v).entrySet()) {
		if(!f) b.append(',');
		f = false;
		quote(b, String.valueOf(e.getKey()));
		b.append(':');
		write(b, e.getValue());
	    }
	    b.append('}');
	} else if(v instanceof Iterable) {
	    b.append('[');
	    boolean f = true;
	    for(Object o : (Iterable<?>)v) {
		if(!f) b.append(',');
		f = false;
		write(b, o);
	    }
	    b.append(']');
	} else {
	    quote(b, String.valueOf(v));
	}
    }

    private static void quote(StringBuilder b, String s) {
	b.append('"');
	for(int i = 0; i < s.length(); i++) {
	    char c = s.charAt(i);
	    switch(c) {
	    case '"': b.append("\\\""); break;
	    case '\\': b.append("\\\\"); break;
	    case '\n': b.append("\\n"); break;
	    case '\r': b.append("\\r"); break;
	    case '\t': b.append("\\t"); break;
	    default:
		if(c < 0x20)
		    b.append(String.format("\\u%04x", (int)c));
		else
		    b.append(c);
	    }
	}
	b.append('"');
    }
}
