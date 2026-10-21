// The desktop and iOS app icons, rendered from the ANDROID launcher icon's committed
// resources, so every host carries the same icon by construction:
//
//   android-app/src/main/res/drawable/ic_launcher_foreground.xml   the mark (VectorDrawable)
//   android-app/src/main/res/values/ic_launcher_background.xml     the evening blue behind it
//
// Both are generated from assets/serotine_logo.svg by scripts/gen_android_icons.py, which CI
// keeps in step with the SVG; this file adds only each platform's geometry. The adaptive
// icon's visible 72dp window is mapped onto the icon body:
//
//   --icns  macOS (the dmg): Apple's macOS 11+ grid — an 824 px rounded square, 100 px in
//           from each edge of the 1024 px canvas, corner radius 185.4 px — at every size an
//           .iconset holds, so the Mac icon is the Android one under a rounded-square mask.
//   --ico   Windows (the msi's exe and shortcuts): the same rounded square filling the
//           canvas (Windows draws no plate of its own), 16 to 256 px.
//   --png   Linux (the deb) and the desktop window and tray: that square as one 512 px PNG.
//   --ios   iOS (the app's asset catalog): a 1024 px opaque square, the window filling it —
//           iOS masks the corners itself and refuses an app icon with an alpha channel.
//
// Ported from Myotis's gen_mac_icon.java (biafra23/myotis, scripts/), which writes the icns
// alone. Run at build time by :desktop-app's generateAppIcons and :ios-app's
// generateAppIcon tasks (nothing binary is committed), in Java's single-file source mode
// with the JDK 21 toolchain — plain JDK, no dependencies:
//
//   java -Djava.awt.headless=true scripts/gen_app_icons.java FOREGROUND.xml BACKGROUND.xml \
//       [--icns OUT.icns] [--ico OUT.ico] [--png OUT.png] [--ios OUT.png]

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class GenAppIcons {

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // Every icon is drawn on a 1024 px canvas and scaled to its size.
    static final double CANVAS = 1024;

    // The Android adaptive icon: a 108dp foreground canvas whose centred 72dp a launcher shows.
    static final double FOREGROUND_DP = 108, VISIBLE_DP = 72;

    /** Where the visible window lands on the canvas: a square `size` px in from `inset`, its corners rounded by `radius`. */
    record Body(double inset, double radius, boolean opaque) {
        double size() { return CANVAS - 2 * inset; }

        Shape shape() {
            return radius == 0 ? new Rectangle2D.Double(inset, inset, size(), size())
                : new RoundRectangle2D.Double(inset, inset, size(), size(), 2 * radius, 2 * radius);
        }
    }

    static final Body MAC = new Body(100, 185.4, false);
    static final Body SQUARE = new Body(16, 225, false);
    static final Body IOS = new Body(0, 0, true);

    // The icns elements iconutil writes for a full .iconset, with their pixel sizes; every
    // one carries a PNG (supported for all of these since macOS 10.7).
    static final String[] ICNS_TYPES = {"icp4", "ic11", "icp5", "ic12", "ic07", "ic13", "ic08", "ic14", "ic09", "ic10"};
    static final int[] ICNS_SIZES = {16, 32, 32, 64, 128, 256, 256, 512, 512, 1024};
    // The sizes Windows picks from (shell, taskbar, title bar at 100-200 % scaling).
    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};
    static final int PNG_SIZE = 512;

    record Fill(Path2D shape, Color color) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length % 2 != 0) usage();
        List<Fill> foreground = parseVector(Path.of(args[0]));
        Color background = parseBackground(Path.of(args[1]));
        for (int i = 2; i < args.length; i += 2) {
            Path out = Path.of(args[i + 1]);
            byte[] bytes = switch (args[i]) {
                case "--icns" -> icns(foreground, background);
                case "--ico" -> ico(foreground, background);
                case "--png" -> png(render(foreground, background, SQUARE, PNG_SIZE));
                case "--ios" -> png(render(foreground, background, IOS, 1024));
                default -> { usage(); yield null; }
            };
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, bytes);
            System.out.println("wrote " + out + " (" + bytes.length + " bytes)");
        }
    }

    static void usage() {
        System.err.println("usage: java gen_app_icons.java FOREGROUND.xml BACKGROUND.xml "
            + "[--icns OUT.icns] [--ico OUT.ico] [--png OUT.png] [--ios OUT.png]");
        System.exit(2);
    }

    static byte[] icns(List<Fill> foreground, Color background) throws IOException {
        Map<Integer, byte[]> pngBySize = new LinkedHashMap<>();
        for (int size : ICNS_SIZES) {
            if (!pngBySize.containsKey(size)) pngBySize.put(size, png(render(foreground, background, MAC, size)));
        }
        ByteArrayOutputStream elements = new ByteArrayOutputStream();
        DataOutputStream e = new DataOutputStream(elements);
        for (int i = 0; i < ICNS_TYPES.length; i++) {
            byte[] data = pngBySize.get(ICNS_SIZES[i]);
            e.write(ICNS_TYPES[i].getBytes(StandardCharsets.US_ASCII));
            e.writeInt(8 + data.length); // big-endian, header included
            e.write(data);
        }
        ByteArrayOutputStream icns = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(icns);
        h.write("icns".getBytes(StandardCharsets.US_ASCII));
        h.writeInt(8 + elements.size());
        h.write(elements.toByteArray());
        return icns.toByteArray();
    }

    /** An .ico of PNG entries (read by Windows since Vista), little-endian throughout. */
    static byte[] ico(List<Fill> foreground, Color background) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : ICO_SIZES) images.add(png(render(foreground, background, SQUARE, size)));
        int offset = 6 + 16 * images.size();
        ByteBuffer dir = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        dir.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        for (int i = 0; i < images.size(); i++) {
            int size = ICO_SIZES[i];
            dir.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size)); // 0 means 256
            dir.put((byte) 0).put((byte) 0);            // no palette, reserved
            dir.putShort((short) 1).putShort((short) 32); // planes, bits per pixel
            dir.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        ByteArrayOutputStream ico = new ByteArrayOutputStream();
        ico.write(dir.array());
        for (byte[] image : images) ico.write(image);
        return ico.toByteArray();
    }

    /** Foreground dp → canvas px: the visible 72dp window lands exactly on the icon body. */
    static AffineTransform foregroundToCanvas(Body body) {
        double pxPerDp = body.size() / VISIBLE_DP;
        double origin = body.inset() - (FOREGROUND_DP - VISIBLE_DP) / 2 * pxPerDp;
        AffineTransform t = AffineTransform.getTranslateInstance(origin, origin);
        t.scale(pxPerDp, pxPerDp);
        return t;
    }

    static BufferedImage render(List<Fill> foreground, Color background, Body body, int size) {
        AffineTransform toCanvas = foregroundToCanvas(body);
        // The Android safe zone keeps the mark off every launcher mask; check it clears this
        // body too rather than trust it, since a clipped wing would ship silently otherwise.
        Area outside = new Area();
        for (Fill f : foreground) outside.add(new Area(toCanvas.createTransformedShape(f.shape())));
        outside.subtract(new Area(body.shape()));
        if (!outside.isEmpty()) {
            fail("the foreground reaches outside the icon body (" + outside.getBounds2D() + "): it would be clipped");
        }
        BufferedImage img = new BufferedImage(size, size,
            body.opaque() ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            g.scale(size / CANVAS, size / CANVAS);
            g.setColor(background);
            g.fill(body.shape());
            for (Fill f : foreground) {
                g.setColor(f.color());
                g.fill(toCanvas.createTransformedShape(f.shape()));
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bytes)) fail("no PNG writer in this JDK");
        return bytes.toByteArray();
    }

    // ---- the Android resources ----

    static Document xml(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    static Color parseBackground(Path file) throws Exception {
        Element root = xml(file).getDocumentElement();
        var colors = root.getElementsByTagName("color");
        for (int i = 0; i < colors.getLength(); i++) {
            Element c = (Element) colors.item(i);
            if ("ic_launcher_background".equals(c.getAttribute("name"))) return color(c.getTextContent().trim());
        }
        fail(file + " has no <color name=\"ic_launcher_background\">");
        return null;
    }

    /** The vector's filled paths, in dp of its canvas, with every group's transform applied. */
    static List<Fill> parseVector(Path file) throws Exception {
        Element root = xml(file).getDocumentElement();
        if (!"vector".equals(root.getLocalName())) fail(file + " is not a <vector>");
        onlyRendered(root, "width", "height", "viewportWidth", "viewportHeight");
        double width = dp(attr(root, "width"));
        double viewport = Double.parseDouble(attr(root, "viewportWidth"));
        if (width != FOREGROUND_DP || dp(attr(root, "height")) != FOREGROUND_DP
                || viewport != Double.parseDouble(attr(root, "viewportHeight"))) {
            fail(file + " is not a square " + FOREGROUND_DP + "dp adaptive-icon foreground");
        }
        List<Fill> out = new ArrayList<>();
        walk(root, AffineTransform.getScaleInstance(width / viewport, width / viewport), out);
        if (out.isEmpty()) fail(file + " has no <path>");
        return out;
    }

    static void walk(Element parent, AffineTransform t, List<Fill> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el)) continue;
            switch (el.getLocalName()) {
                case "group" -> {
                    onlyRendered(el, "scaleX", "scaleY", "translateX", "translateY");
                    // VectorDrawable groups scale, then translate (pivot 0): p' = s*p + t.
                    AffineTransform g = new AffineTransform(t);
                    g.translate(num(attr(el, "translateX"), 0), num(attr(el, "translateY"), 0));
                    g.scale(num(attr(el, "scaleX"), 1), num(attr(el, "scaleY"), 1));
                    walk(el, g, out);
                }
                case "path" -> {
                    onlyRendered(el, "fillColor", "pathData");
                    Path2D p = pathData(attr(el, "pathData"));
                    p.transform(t);
                    out.add(new Fill(p, color(attr(el, "fillColor"))));
                }
                default -> fail("<" + el.getLocalName() + "> is not supported in the foreground vector");
            }
        }
    }

    static final Pattern TOKEN = Pattern.compile("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /** Absolute M/L/C/Z only — what gen_android_icons.py emits; anything else is refused. */
    static Path2D pathData(String d) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(d);
        while (m.find()) tokens.add(m.group());
        Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
        int i = 0;
        String cmd = null;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = t;
                i++;
                if (cmd.equals("Z")) {
                    p.closePath();
                    cmd = null;
                    continue;
                }
            } else if (cmd == null) {
                fail("path data starts a segment without a command at token " + i + ": " + t);
            }
            double[] v;
            switch (cmd) {
                case "M" -> { v = nums(tokens, i, 2); p.moveTo(v[0], v[1]); i += 2; cmd = "L"; }
                case "L" -> { v = nums(tokens, i, 2); p.lineTo(v[0], v[1]); i += 2; }
                case "C" -> { v = nums(tokens, i, 6); p.curveTo(v[0], v[1], v[2], v[3], v[4], v[5]); i += 6; }
                default -> fail("unsupported path command " + cmd + " (absolute M/L/C/Z only)");
            }
        }
        return p;
    }

    static double[] nums(List<String> tokens, int from, int n) {
        double[] v = new double[n];
        for (int k = 0; k < n; k++) {
            if (from + k >= tokens.size() || Character.isLetter(tokens.get(from + k).charAt(0))) {
                fail("path data ends a segment early at token " + (from + k));
            }
            v[k] = Double.parseDouble(tokens.get(from + k));
        }
        return v;
    }

    /**
     * Refuse any android: attribute this renderer does not apply (fillAlpha, a stroke, a
     * rotation, a tint...): the icon would quietly differ from the Android one, which
     * is the one thing this script exists to prevent. android:name changes nothing.
     */
    static void onlyRendered(Element el, String... rendered) {
        var attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!ANDROID_NS.equals(a.getNamespaceURI()) || a.getLocalName().equals("name")) continue;
            if (!List.of(rendered).contains(a.getLocalName())) {
                fail("<" + el.getLocalName() + " android:" + a.getLocalName() + "> is not supported: "
                    + "the icon would not match the Android one");
            }
        }
    }

    static String attr(Element el, String name) {
        return el.getAttributeNS(ANDROID_NS, name);
    }

    static double num(String s, double fallback) {
        return s.isEmpty() ? fallback : Double.parseDouble(s);
    }

    static double dp(String s) {
        if (!s.endsWith("dp")) fail("expected a dp size, got " + s);
        return Double.parseDouble(s.substring(0, s.length() - 2));
    }

    /** #RRGGBB or #AARRGGBB, as Android writes colours. */
    static Color color(String s) {
        if (!s.matches("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")) fail("unsupported colour " + s);
        long v = Long.parseLong(s.substring(1), 16);
        return s.length() == 7 ? new Color((int) v) : new Color((int) v, true);
    }

    static void fail(String why) {
        throw new IllegalStateException("gen_app_icons: " + why);
    }
}
