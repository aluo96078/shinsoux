import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;

/**
 * Golden oracle: runs the Jsoup operations that plugins use and prints them in the line format
 * shared with test/golden_run.c.  usage: Oracle <golden-dir> <selectors-file>...
 */
public class Oracle {
    static String esc(String s) {
        if (s == null) return "<null>";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        String r = b.toString();
        return r.length() > 20000 ? r.substring(0, 20000) + "...<cut>" : r;
    }
    static void prop(PrintStream o, String idx, String name, String v) { o.println("  " + idx + "." + name + "=" + esc(v)); }

    static void dump(PrintStream o, int i, Element e) {
        String idx = "[" + i + "]";
        prop(o, idx, "tag", e.tagName());
        prop(o, idx, "id", e.id());
        prop(o, idx, "class", e.className());
        prop(o, idx, "text", e.text());
        prop(o, idx, "own", e.ownText());
        prop(o, idx, "href", e.attr("href"));
        prop(o, idx, "src", e.attr("src"));
        prop(o, idx, "data-original", e.attr("data-original"));
        prop(o, idx, "abshref", e.absUrl("href"));
        prop(o, idx, "abssrc", e.absUrl("src"));
        prop(o, idx, "has_href", String.valueOf(e.hasAttr("href")));
        prop(o, idx, "nchildren", String.valueOf(e.children().size()));
        Element p = e.parent();
        prop(o, idx, "parent", p == null ? "<null>" : p.tagName());
        Element n = e.nextElementSibling();
        prop(o, idx, "next", n == null ? "<null>" : n.tagName());
        Element pv = e.previousElementSibling();
        prop(o, idx, "prev", pv == null ? "<null>" : pv.tagName());
        if (i < 3) {
            prop(o, idx, "html", e.html());
            prop(o, idx, "outer", e.outerHtml());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--urls")) {
            PrintStream u = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
            for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                String base = line.substring(0, t), rel = line.substring(t + 1);
                Element a = Jsoup.parse("<a>", base).selectFirst("a");
                a.attr("href", rel);
                u.println(esc(base) + " | " + esc(rel) + " => " + esc(a.absUrl("href")));
            }
            u.flush();
            return;
        }
        Path dir = Paths.get(args[0]);
        PrintStream o = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        List<String> sels = new ArrayList<>();
        for (int a = 1; a < args.length; a++) sels.addAll(Files.readAllLines(Paths.get(args[a]), StandardCharsets.UTF_8));
        for (String line : Files.readAllLines(dir.resolve("fixtures.txt"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            String html = new String(Files.readAllBytes(dir.resolve(f[2])), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, f[1]);
            o.println("### " + f[0]);
            o.println("DOC.tag=" + esc(doc.tagName()));
            o.println("DOC.text=" + esc(doc.text()));
            o.println("DOC.title=" + esc(doc.title()));
            Element body = doc.body();
            o.println("DOC.body.text=" + esc(body == null ? null : body.text()));
            for (String s : sels) {
                if (s.isEmpty() && false) continue;
                o.println("SEL " + esc(s));
                try {
                    Elements els = doc.select(s);
                    o.println("  count=" + els.size());
                    int n = els.size();
                    for (int i = 0; i < n; i++) {
                        if (i >= 4 && i < n - 2) continue;
                        dump(o, i, els.get(i));
                    }
                    Element first = doc.selectFirst(s);
                    o.println("  first=" + (first == null ? "<null>" : first.tagName()));
                } catch (Exception ex) {
                    o.println("  ERROR");
                }
            }
        }
        o.flush();
    }
}
