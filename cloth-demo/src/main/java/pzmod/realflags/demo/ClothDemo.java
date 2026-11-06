package pzmod.realflags.demo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import pzmod.realflags.cloth.AnchorFrame;
import pzmod.realflags.cloth.Cloth;
import pzmod.realflags.cloth.ClothProfile;
import pzmod.realflags.cloth.ClothProfiles;
import pzmod.realflags.cloth.StaticAnchor;
import pzmod.realflags.cloth.WindForce;

/**
 * Dev-only headless harness for cloth-core. Modes:
 * <pre>
 *   png    scripted gust, PNG frames + contact sheet + metrics.csv      (default)
 *   ascii  same script, side view as ASCII art every 0.5 s
 *   sweep  steady wind 0.5..33 m/s: mean / min / max displacement per speed
 *   bench  N cloths in gusty wind, ms per frame (core cost only)
 *   calm   storm from 12 directions, then calm: seconds until asleep exactly on the reference (−1 = not within 30 s)
 * Options:
 *   --mode png|ascii|sweep|bench   --flag wall|pole   --seconds S   --out DIR
 *   --texture PNG (cloth colours; e.g. a DGVFlags flag)   --uv u0,v0,u1,v1   --every N (frames between PNGs)
 *   --restore X (pose memory 1/s)   --count N (bench)   --turbulence X (bench)   --seed N   --cd X   --cf X   --mass X   --iterations N   --grid CxR   --exposure X
 * </pre>
 * Never shipped with the mod.
 */
public final class ClothDemo {
    private static final float DT = 1f / 60f;

    private String mode = "png", flag = "wall", out = "demo-out", texturePath;
    private float seconds = 24f, exposure = 1f;
    private float[] uv = {0f, 0f, 1f, 1f};
    private int every = 6, count = 50;
    private long seed = 1234;
    private Float cd, cf, mass;
    private Integer iterations, gridC, gridR;
    private Float restore;
    private float benchTurbulence = 0.4f;

    public static void main(String[] args) throws IOException {
        ClothDemo d = new ClothDemo();
        d.parse(args);
        switch (d.mode) {
            case "png" -> d.runScript(false);
            case "ascii" -> d.runScript(true);
            case "sweep" -> d.sweep();
            case "bench" -> d.bench();
            case "calm" -> d.calm();
            default -> throw new IllegalArgumentException("unknown mode " + d.mode);
        }
    }

    private void parse(String[] a) {
        for (int i = 0; i < a.length; i++) {
            String k = a[i];
            String v = i + 1 < a.length ? a[i + 1] : null;
            switch (k) {
                case "--mode" -> { mode = v; i++; }
                case "--flag" -> { flag = v; i++; }
                case "--seconds" -> { seconds = Float.parseFloat(v); i++; }
                case "--out" -> { out = v; i++; }
                case "--texture" -> { texturePath = v; i++; }
                case "--uv" -> {
                    String[] p = v.split(",");
                    for (int q = 0; q < 4; q++) uv[q] = Float.parseFloat(p[q]);
                    i++;
                }
                case "--every" -> { every = Integer.parseInt(v); i++; }
                case "--count" -> { count = Integer.parseInt(v); i++; }
                case "--seed" -> { seed = Long.parseLong(v); i++; }
                case "--cd" -> { cd = Float.parseFloat(v); i++; }
                case "--cf" -> { cf = Float.parseFloat(v); i++; }
                case "--mass" -> { mass = Float.parseFloat(v); i++; }
                case "--iterations" -> { iterations = Integer.parseInt(v); i++; }
                case "--restore" -> { restore = Float.parseFloat(v); i++; }
                case "--grid" -> {
                    String[] p = v.toLowerCase(Locale.ROOT).split("x");
                    gridC = Integer.parseInt(p[0]); gridR = Integer.parseInt(p[1]);
                    i++;
                }
                case "--exposure" -> { exposure = Float.parseFloat(v); i++; }
                case "--turbulence" -> { benchTurbulence = Float.parseFloat(v); i++; }
                default -> throw new IllegalArgumentException("unknown option " + k);
            }
        }
    }

    // ------------------------------------------------------------------ set-up

    private ClothProfile profile() {
        ClothProfile base = flag.equals("pole") ? ClothProfiles.POLE : ClothProfiles.WALL;
        ClothProfile.Builder b = base.toBuilder();
        if (cd != null) b.dragCoefficient(cd);
        if (cf != null) b.frictionCoefficient(cf);
        if (mass != null) b.mass(mass);
        if (iterations != null) b.iterations(iterations);
        if (restore != null) b.calmRestoreRate(restore);
        if (gridC != null) b.grid(gridC, gridR);
        return b.build();
    }

    /** Wall: 0.6 × 1.1 m, top at z=2.2, wall along x, front normal +y. Pole: 1.2 × 0.63 m, hoist on the pole at origin, top z=3. */
    private Cloth makeCloth(ClothProfile p, float offsetX) {
        if (flag.equals("pole")) {
            return new Cloth(p, 1.2f, 0.63f, StaticAnchor.of(
                    new float[]{offsetX, 0f, 3f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f}));
        }
        return new Cloth(p, 0.6f, 1.1f, StaticAnchor.of(
                new float[]{offsetX, 0f, 2.2f}, new float[]{1f, 0f, 0f}, new float[]{0f, 0f, -1f}));
    }

    /** Scripted weather: calm → breeze → gusty → direction swing → storm → calm (should sleep and snap home). */
    private static void script(float t, WindForce w, float exposure) {
        float speed, dirDeg;
        if (t < 2f) { speed = 0f; dirDeg = 90f; }
        else if (t < 5f) { speed = lerp(0f, 5f, (t - 2f) / 3f); dirDeg = 90f; }
        else if (t < 8f) { speed = 5f; dirDeg = 90f; }
        else if (t < 10f) { speed = lerp(5f, 14f, (t - 8f) / 2f); dirDeg = 90f; }
        else if (t < 14f) { speed = 14f; dirDeg = lerp(90f, 0f, (t - 10f) / 4f); }
        else if (t < 16f) { speed = lerp(14f, 25f, (t - 14f) / 2f); dirDeg = lerp(0f, 160f, (t - 14f) / 2f); }
        else if (t < 18f) { speed = lerp(25f, 0f, (t - 16f) / 2f); dirDeg = 160f; }
        else { speed = 0f; dirDeg = 160f; }
        w.setHorizontal(speed * exposure, (float) Math.toRadians(dirDeg));
    }

    private static float lerp(float a, float b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------ png / ascii

    private void runScript(boolean ascii) throws IOException {
        ClothProfile p = profile();
        Cloth c = makeCloth(p, 0f);
        WindForce w = new WindForce(seed);
        c.addForceSource(w);
        BufferedImage tex = texturePath != null ? ImageIO.read(new File(texturePath)) : null;
        File dir = new File(out, flag);
        if (!ascii && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);

        List<BufferedImage> sheet = new ArrayList<>();
        PrintWriter csv = ascii ? null : new PrintWriter(new File(dir, "metrics.csv"), StandardCharsets.UTF_8);
        if (csv != null) csv.println("t,windX,windY,speed,gust,maxDevRef,sleeping,substeps,resets");

        int frames = Math.round(seconds / DT);
        for (int f = 0; f <= frames; f++) {
            float t = f * DT;
            script(t, w, exposure);
            int sub = f == 0 ? 0 : c.step(DT);
            if (csv != null) {
                csv.printf(Locale.ROOT, "%.4f,%.3f,%.3f,%.3f,%.3f,%.5f,%d,%d,%d%n", t, w.windX(), w.windY(), w.speed(),
                        w.gustFactor(c.time()), c.maxDeviationFromReference(), c.isSleeping() ? 1 : 0, sub, c.resetCount());
            }
            if (ascii) {
                if (f % 30 == 0) System.out.println(asciiSide(c, t, w));
            } else if (f % every == 0) {
                BufferedImage img = render(c, w, t, tex);
                ImageIO.write(img, "png", new File(dir, String.format(Locale.ROOT, "frame_%05d.png", f)));
                if (f % (every * 10) == 0) sheet.add(img);
            }
        }
        if (csv != null) csv.close();
        if (!ascii) {
            writeSheet(sheet, new File(dir, "sheet.png"));
            System.out.printf(Locale.ROOT, "%s: %d frames to %s (sheet.png, metrics.csv). sleeping at end=%s, resets=%d%n",
                    p, frames, dir.getAbsolutePath(), c.isSleeping(), c.resetCount());
        }
    }

    /** Simple PZ-like isometric projection: x right-down, y left-down, z up. */
    private static final float S = 150f;

    private static float sx(float x, float y) { return (x - y) * S * 0.866f; }
    private static float sy(float x, float y, float z) { return (x + y) * S * 0.5f - z * S; }

    private BufferedImage render(Cloth c, WindForce w, float t, BufferedImage tex) {
        int W = 480, H = 480;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(92, 110, 92));
        g.fillRect(0, 0, W, H);
        float cx = W * 0.45f, cy = H * 0.85f;

        // ground grid and the wall / pole for context
        g.setColor(new Color(80, 96, 80));
        for (int i = -3; i <= 3; i++) {
            line(g, cx, cy, i, -3, 0, i, 3, 0);
            line(g, cx, cy, -3, i, 0, 3, i, 0);
        }
        AnchorFrame a = c.anchorFrame();
        if (flag.equals("pole")) {
            g.setColor(new Color(170, 170, 175));
            g.setStroke(new BasicStroke(4f));
            line(g, cx, cy, a.ox, a.oy, 0f, a.ox, a.oy, a.oz + 0.1f);
            g.setStroke(new BasicStroke(1f));
        } else {
            Path2D wall = new Path2D.Float();
            moveTo(wall, cx, cy, -0.4f, 0f, 0f);
            lineTo(wall, cx, cy, 1.0f, 0f, 0f);
            lineTo(wall, cx, cy, 1.0f, 0f, 2.6f);
            lineTo(wall, cx, cy, -0.4f, 0f, 2.6f);
            wall.closePath();
            g.setColor(new Color(150, 140, 125));
            g.fill(wall);
        }

        // cloth: one polygon per triangle, back-to-front by depth (x+y−z), lit by normal
        float[] p = c.positions();
        int[] tri = c.triangles();
        int tc = c.triangleCount();
        Integer[] order = new Integer[tc];
        float[] depth = new float[tc];
        for (int i = 0; i < tc; i++) {
            order[i] = i;
            int i0 = 3 * tri[3 * i], i1 = 3 * tri[3 * i + 1], i2 = 3 * tri[3 * i + 2];
            depth[i] = (p[i0] + p[i1] + p[i2] + p[i0 + 1] + p[i1 + 1] + p[i2 + 1]) - 0.5f * (p[i0 + 2] + p[i1 + 2] + p[i2 + 2]);
        }
        java.util.Arrays.sort(order, (x, y) -> Float.compare(depth[x], depth[y]));
        for (int idx : order) {
            int k0 = tri[3 * idx], k1 = tri[3 * idx + 1], k2 = tri[3 * idx + 2];
            int i0 = 3 * k0, i1 = 3 * k1, i2 = 3 * k2;
            float e1x = p[i1] - p[i0], e1y = p[i1 + 1] - p[i0 + 1], e1z = p[i1 + 2] - p[i0 + 2];
            float e2x = p[i2] - p[i0], e2y = p[i2 + 1] - p[i0 + 1], e2z = p[i2 + 2] - p[i0 + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9f;
            float light = Math.abs((nx * -0.4f + ny * 0.5f + nz * 0.77f) / nl);
            float shade = 0.45f + 0.55f * light;
            float u = (c.texU(k0) + c.texU(k1) + c.texU(k2)) / 3f;
            float v = (c.texV(k0) + c.texV(k1) + c.texV(k2)) / 3f;
            Color base = sample(tex, u, v, idx);
            g.setColor(new Color(clamp255(base.getRed() * shade), clamp255(base.getGreen() * shade), clamp255(base.getBlue() * shade)));
            Path2D poly = new Path2D.Float();
            moveTo(poly, cx, cy, p[i0], p[i0 + 1], p[i0 + 2]);
            lineTo(poly, cx, cy, p[i1], p[i1 + 1], p[i1 + 2]);
            lineTo(poly, cx, cy, p[i2], p[i2 + 1], p[i2 + 2]);
            poly.closePath();
            g.fill(poly);
            g.draw(poly);
        }

        // reference pose outline (where vanilla draws the sprite)
        g.setColor(new Color(255, 255, 0, 120));
        float[] r = c.reference();
        int cols = c.cols(), rows = c.rows();
        int[] corners = {0, cols - 1, cols * rows - 1, cols * (rows - 1)};
        Path2D outline = new Path2D.Float();
        for (int q = 0; q < 4; q++) {
            int o = 3 * corners[q];
            if (q == 0) moveTo(outline, cx, cy, r[o], r[o + 1], r[o + 2]);
            else lineTo(outline, cx, cy, r[o], r[o + 1], r[o + 2]);
        }
        outline.closePath();
        g.draw(outline);

        // wind arrow + HUD
        float ws = w.speed();
        if (ws > 0.01f) {
            float len = Math.min(1f, ws / 15f);
            g.setColor(Color.CYAN);
            g.setStroke(new BasicStroke(2f));
            line(g, 70, 400, 0, 0, 0, w.windX() / ws * len, w.windY() / ws * len, 0);
            g.setStroke(new BasicStroke(1f));
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.drawString(String.format(Locale.ROOT, "t=%5.2fs wind=%5.1f m/s gust=%.2f", t, ws, w.gustFactor(c.time())), 8, 18);
        g.drawString(String.format(Locale.ROOT, "dev=%.3f m %s resets=%d", c.maxDeviationFromReference(),
                c.isSleeping() ? "SLEEP" : "awake", c.resetCount()), 8, 34);
        g.dispose();
        return img;
    }

    private Color sample(BufferedImage tex, float u, float v, int triIndex) {
        if (tex == null) {
            boolean check = (((int) (u * 6)) + ((int) (v * 6))) % 2 == 0;
            return check ? new Color(200, 40, 50) : new Color(235, 235, 235);
        }
        float tu = uv[0] + (uv[2] - uv[0]) * u, tv = uv[1] + (uv[3] - uv[1]) * v;
        int x = Math.min(tex.getWidth() - 1, Math.max(0, (int) (tu * tex.getWidth())));
        int y = Math.min(tex.getHeight() - 1, Math.max(0, (int) (tv * tex.getHeight())));
        return new Color(tex.getRGB(x, y));
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static void moveTo(Path2D p, float cx, float cy, float x, float y, float z) {
        p.moveTo(cx + sx(x, y), cy + sy(x, y, z));
    }

    private static void lineTo(Path2D p, float cx, float cy, float x, float y, float z) {
        p.lineTo(cx + sx(x, y), cy + sy(x, y, z));
    }

    private static void line(Graphics2D g, float cx, float cy, float x0, float y0, float z0, float x1, float y1, float z1) {
        g.drawLine(Math.round(cx + sx(x0, y0)), Math.round(cy + sy(x0, y0, z0)),
                Math.round(cx + sx(x1, y1)), Math.round(cy + sy(x1, y1, z1)));
    }

    private static void writeSheet(List<BufferedImage> imgs, File file) throws IOException {
        if (imgs.isEmpty()) return;
        int cols = Math.min(5, imgs.size()), rows = (imgs.size() + cols - 1) / cols;
        int w = imgs.get(0).getWidth() / 2, h = imgs.get(0).getHeight() / 2;
        BufferedImage sheet = new BufferedImage(cols * w, rows * h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < imgs.size(); i++) g.drawImage(imgs.get(i), (i % cols) * w, (i / cols) * h, w, h, null);
        g.dispose();
        ImageIO.write(sheet, "png", file);
    }

    /** Side view projected on the plane (axisU, normal): columns = across, rows = height. */
    private static String asciiSide(Cloth c, float t, WindForce w) {
        int W = 60, H = 22;
        char[][] grid = new char[H][W];
        for (char[] row : grid) java.util.Arrays.fill(row, ' ');
        AnchorFrame a = c.anchorFrame();
        float[] p = c.positions();
        float span = 1.6f * Math.max(c.width(), c.height());
        for (int k = 0; k < c.particleCount(); k++) {
            int o = 3 * k;
            float dx = p[o] - a.ox, dy = p[o + 1] - a.oy, dz = p[o + 2] - a.oz;
            float horiz = dx * a.ux + dy * a.uy + dz * a.uz;   // along the wall / fly direction
            float out = dx * a.nx + dy * a.ny + dz * a.nz;     // out of plane
            int col = Math.round((horiz + out * 0.5f + span * 0.3f) / span * (W - 1));
            int row = Math.round((-dz) / span * (H - 1)) + 1;
            if (col >= 0 && col < W && row >= 0 && row < H) grid[row][col] = c.isPinned(k) ? '#' : (out > 0.05f ? 'o' : '*');
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "--- t=%5.2f s  wind=%5.1f m/s  %s%n", t, w.speed(), c.isSleeping() ? "SLEEP" : ""));
        for (char[] row : grid) sb.append(row).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ sweep

    private void sweep() {
        float[] speeds = {0.06f, 0.5f, 1f, 2f, 3f, 4.5f, 6f, 8f, 10f, 12.5f, 15f, 20f, 25f, 33f};
        System.out.println("flag=" + flag + " " + profile());
        System.out.println(" speed  meanOut  minOut   maxOut   meanLift  maxEdgeStretch");
        for (float s : speeds) {
            Cloth c = makeCloth(profile(), 0f);
            WindForce w = new WindForce(seed).setGusts(0f, 0f).setTurbulence(0f, 0f);
            c.addForceSource(w);
            if (flag.equals("pole")) w.setWind(s * exposure, 0f, 0f); else w.setWind(0f, s * exposure, 0f);
            float ox = flag.equals("pole") ? 1f : 0f, oy = flag.equals("pole") ? 0f : 1f;
            for (int f = 0; f < 240; f++) c.step(DT);
            double sum = 0, lift = 0;
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE, stretch = 0f;
            int frames = 180;
            for (int f = 0; f < frames; f++) {
                c.step(DT);
                float d = mean(c, ox, oy, 0f);
                sum += d;
                lift += mean(c, 0f, 0f, 1f);
                mn = Math.min(mn, d);
                mx = Math.max(mx, d);
                stretch = Math.max(stretch, maxEdgeStretch(c));
            }
            System.out.printf(Locale.ROOT, "%6.1f  %7.4f  %7.4f  %7.4f  %8.4f  %6.3f%n",
                    s, sum / frames, mn, mx, lift / frames, stretch);
        }
    }

    private static float mean(Cloth c, float dx, float dy, float dz) {
        float[] p = c.positions(), r = c.reference();
        double sum = 0;
        for (int k = 0; k < c.particleCount(); k++) {
            int o = 3 * k;
            sum += (p[o] - r[o]) * dx + (p[o + 1] - r[o + 1]) * dy + (p[o + 2] - r[o + 2]) * dz;
        }
        return (float) (sum / c.particleCount());
    }

    private static float maxEdgeStretch(Cloth c) {
        float[] p = c.positions();
        float restU = c.width() / (c.cols() - 1), restV = c.height() / (c.rows() - 1);
        float worst = 0f;
        for (int j = 0; j < c.rows(); j++) {
            for (int i = 0; i < c.cols(); i++) {
                int a = 3 * (j * c.cols() + i);
                if (i + 1 < c.cols()) worst = Math.max(worst, dist(p, a, a + 3) / restU - 1f);
                if (j + 1 < c.rows()) worst = Math.max(worst, dist(p, a, a + 3 * c.cols()) / restV - 1f);
            }
        }
        return worst;
    }

    private static float dist(float[] p, int a, int b) {
        float dx = p[b] - p[a], dy = p[b + 1] - p[a + 1], dz = p[b + 2] - p[a + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ calm (return-to-rest)

    private void calm() {
        ClothProfile p = profile();
        System.out.println("flag=" + flag + " " + p + " restore=" + p.calmRestoreRate);
        float worst = 0f;
        int fails = 0;
        StringBuilder sb = new StringBuilder();
        for (int deg = 0; deg < 360; deg += 30) {
            Cloth c = makeCloth(p, 0f);
            WindForce w = new WindForce(seed + deg).setGusts(0.6f, 0.5f).setTurbulence(0.4f, 2.5f);
            c.addForceSource(w);
            w.setHorizontal(14f, (float) Math.toRadians(deg));
            for (int f = 0; f < 240; f++) c.step(DT);
            w.setWind(0f, 0f, 0f);
            float t = -1f;
            for (int f = 1; f <= 1800; f++) {
                c.step(DT);
                if (c.isSleeping()) { t = f * DT; break; }
            }
            if (t < 0f) fails++; else worst = Math.max(worst, t);
            sb.append(String.format(Locale.ROOT, " %3d°:%5.1fs", deg, t));
        }
        System.out.println(sb);
        System.out.printf(Locale.ROOT, "worst %.1fs, not asleep within 30 s: %d of 12%n", worst, fails);
    }

    // ------------------------------------------------------------------ bench

    private void bench() {
        ClothProfile p = profile();
        Cloth[] cloths = new Cloth[count];
        WindForce w = new WindForce(seed).setGusts(0.5f, 0.5f).setTurbulence(benchTurbulence, 2.5f);
        w.setHorizontal(9f, 0.7f);
        for (int i = 0; i < count; i++) {
            cloths[i] = makeCloth(p, i * 2f);
            cloths[i].addForceSource(w);
        }
        int warm = 600, frames = 1800;
        for (int f = 0; f < warm; f++) for (Cloth c : cloths) c.step(DT);
        long[] perFrame = new long[frames];
        for (int f = 0; f < frames; f++) {
            long t0 = System.nanoTime();
            for (Cloth c : cloths) c.step(DT);
            perFrame[f] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(perFrame);
        double mean = 0;
        for (long v : perFrame) mean += v;
        mean /= frames;
        System.out.printf(Locale.ROOT,
                "bench: %d × %s (%d particles, %d constraints, %d it), 1 substep/frame%n"
                        + "  per frame: mean %.3f ms, median %.3f ms, p99 %.3f ms, max %.3f ms%n"
                        + "  per cloth: mean %.1f µs%n",
                count, p, cloths[0].particleCount(), cloths[0].constraintCount(), p.iterations,
                mean / 1e6, perFrame[frames / 2] / 1e6, perFrame[(int) (frames * 0.99)] / 1e6, perFrame[frames - 1] / 1e6,
                mean / 1e3 / count);
    }
}
