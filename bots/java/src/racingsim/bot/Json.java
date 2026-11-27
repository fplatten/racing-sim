package racingsim.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough JSON for the bot protocol (no library needed). The same code as the game's.
 * {@link #parse} gives {@code Map<String,Object>}, {@code List<Object>}, {@code Double}, {@code String},
 * {@code Boolean} or {@code null}; {@link Out} writes compact JSON.
 */
public final class Json {

    private Json() {}

    public static final class ParseException extends RuntimeException {
        ParseException(String msg, int at) { super(msg + " at " + at); }
    }

    public static Object parse(String text) {
        Json.Parser p = new Json.Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw new ParseException("trailing characters", p.i);
        return v;
    }

    /** The object's number field, {@code def} if it is missing, not a number, NaN or infinite. */
    public static double num(Map<?, ?> o, String key, double def) {
        Object v = o.get(key);
        if (!(v instanceof Double d) || d.isNaN() || d.isInfinite()) return def;
        return d;
    }

    public static String str(Map<?, ?> o, String key, String def) {
        Object v = o.get(key);
        return v instanceof String s ? s : def;
    }

    public static boolean bool(Map<?, ?> o, String key, boolean def) {
        Object v = o.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value(int depth) {
            if (depth > 32) throw new ParseException("nested too deep", i);
            if (i >= s.length()) throw new ParseException("unexpected end", i);
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new ParseException("unexpected '" + c + "'", i);
            }
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw new ParseException("bad literal", i);
            i += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new ParseException("expected a key", i);
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new ParseException("expected , or }", i - 1);
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new ParseException("expected , or ]", i - 1);
            }
        }

        String string() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new ParseException("unterminated string", i);
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = next();
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new ParseException("bad escape", i);
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new ParseException("bad number", start);
            }
        }

        char peek() {
            if (i >= s.length()) throw new ParseException("unexpected end", i);
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        void expect(char c) {
            if (next() != c) throw new ParseException("expected " + c, i - 1);
        }
    }

    /** A compact JSON writer: {@code new Out().obj().key("a").num(1).end().toString()}. */
    public static final class Out {
        private final StringBuilder b = new StringBuilder(512);
        private boolean comma;

        private void sep() {
            if (comma) b.append(',');
            comma = true;
        }

        public Out obj() { sep(); b.append('{'); comma = false; return this; }
        public Out arr() { sep(); b.append('['); comma = false; return this; }
        public Out end() { b.append(b.charAt(lastOpen()) == '{' ? '}' : ']'); comma = true; return this; }
        public Out endObj() { b.append('}'); comma = true; return this; }
        public Out endArr() { b.append(']'); comma = true; return this; }

        private int lastOpen() {
            int depth = 0;
            boolean inStr = false;
            for (int k = b.length() - 1; k >= 0; k--) {
                char c = b.charAt(k);
                if (c == '"' && (k == 0 || b.charAt(k - 1) != '\\')) inStr = !inStr;
                if (inStr) continue;
                if (c == '}' || c == ']') depth++;
                else if (c == '{' || c == '[') {
                    if (depth == 0) return k;
                    depth--;
                }
            }
            throw new IllegalStateException("nothing open");
        }

        public Out key(String k) {
            sep();
            quote(k);
            b.append(':');
            comma = false;
            return this;
        }

        public Out num(double v) {
            sep();
            if (Double.isNaN(v) || Double.isInfinite(v)) b.append("null");
            else if (v == Math.rint(v) && Math.abs(v) < 1e15) b.append((long) v);
            else b.append(String.format(Locale.ROOT, "%.6g", v).replaceFirst("(\\.\\d*?)0+(e|$)", "$1$2").replace(".e", "e").replaceFirst("\\.$", ""));
            return this;
        }

        public Out num(String k, double v) { return key(k).num(v); }
        public Out str(String k, String v) { return key(k).str(v); }
        public Out bool(String k, boolean v) { key(k); sep(); b.append(v); return this; }

        public Out str(String v) {
            sep();
            if (v == null) b.append("null");
            else quote(v);
            return this;
        }

        private void quote(String v) {
            b.append('"');
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }

        @Override public String toString() { return b.toString(); }
    }
}
