package pzmod.realflags;

import java.util.function.Consumer;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.weather.fx.WeatherFxMask;

/**
 * Replaces a flag's single vanilla sprite quad with cloth geometry.
 *
 * <h2>Depth and seams (DECISIONS D24)</h2>
 * The vanilla {@code texdModifier} is run once on a scratch {@link TextureDraw} covering the vanilla quad; every emitted
 * quad takes its main/depth/second-texture UVs from that captured quad by bilinear (or affine) interpolation, i.e. exactly
 * what the GPU would interpolate across the single vanilla quad. Depth therefore follows the rest pose.
 *
 * <h2>Wall flags</h2>
 * The image is the vanilla sprite. A render mesh covers the sprite rectangle; each vertex is displaced by the cloth's
 * projected (position − reference), sampled at the vertex's (u, v) on the wall cloth. At zero wind every displacement is
 * exactly 0 and shading exactly 1, so the result is the vanilla picture.
 *
 * <h2>Pole flags</h2>
 * Static quads rebuild the pole (from this sprite and/or other sprites), then the canvas texture is drawn at the cloth
 * particles' absolute positions, depth-sampled at {@code depthPoint} on the shaft.
 * <p>
 * Allocation-free per frame; main thread only.
 */
final class SpriteMesh {
    private static final int MAX_V = 33 * 33;

    private static final TextureDraw scratch = new TextureDraw();
    private static final QuadModifier quad = new QuadModifier();
    // captured vanilla quad, corners 0=TL 1=TR 2=BR 3=BL
    private static final float[] cx = new float[4], cy = new float[4], cu = new float[4], cv = new float[4];
    private static final float[] c1u = new float[4], c1v = new float[4], c2u = new float[4], c2v = new float[4];
    // wall mesh vertices
    private static final float[] vx = new float[MAX_V], vy = new float[MAX_V], vu = new float[MAX_V], vv = new float[MAX_V];
    private static final float[] v1u = new float[MAX_V], v1v = new float[MAX_V], v2u = new float[MAX_V], v2v = new float[MAX_V];
    private static final float[] vs = new float[MAX_V];
    private static final float[] tmp = new float[4];
    // per-call frame mapping
    private static float frameX, frameY, frameK, nextZ, nextChunkDepth;

    private SpriteMesh() {}

    /**
     * @return true if we drew (vanilla's quad must be skipped), false to let vanilla draw
     */
    static boolean draw(FlagDef def, FlagInstance inst, Texture tex, float x, float y, float w, float h,
                        float r, float g, float b, float a, Consumer<TextureDraw> modifier) {
        RealFlags.Stats st = RealFlags.stats;
        if (tex == null) { st.fallbackPath++; return false; }
        if (!tex.isReady()) { st.fallbackNotReady++; return false; }
        if (a == 0f) return true; // vanilla draws nothing either (GenericSpriteRenderState.render: a != 0)
        if (tex.flip) { st.fallbackFlip++; return false; }
        if (!PerformanceSettings.fboRenderChunk || WeatherFxMask.isRenderingMask()) { st.fallbackPath++; return false; }
        if (!RenderStateProbe.isPlain()) { st.fallbackState++; return false; }
        if (tex.getWidth() <= 0 || tex.getWidthOrig() <= 0) { st.fallbackPath++; return false; }

        // vanilla quad (Texture.render adds the trim offset)
        float x0 = x + tex.offsetX, y0 = y + tex.offsetY, x1 = x0 + w, y1 = y0 + h;
        // 2x-frame pixel → screen: performRenderFrame pre-shifted x by offsetX·(scale−1), so the frame origin is x0 − offsetX·scale
        float scale = w / tex.getWidth();
        frameX = x0 - tex.offsetX * scale;
        frameY = y0 - tex.offsetY * scale;
        frameK = scale * tex.getWidthOrig() / 128f;

        int col = Color.colorToABGR(r, g, b, a);
        nextZ = TextureDraw.nextZ;
        nextChunkDepth = TextureDraw.nextChunkDepth;
        scratch.reset();
        TextureDraw.Create(scratch, tex, x0, y0, x1, y0, x1, y1, x0, y1, col, col, col, col, modifier);
        TextureDraw.nextZ = nextZ;
        TextureDraw.nextChunkDepth = nextChunkDepth;
        if (scratch.col0 != col || scratch.col1 != col || scratch.col2 != col || scratch.col3 != col) {
            st.fallbackColors++;
            return false;
        }
        capture();

        boolean drew;
        if (def.kind == FlagDef.Kind.WALL) {
            drew = drawWall(def, inst, tex, r, g, b, a);
        } else {
            drew = drawPole(def, inst, tex, r, g, b, a);
        }
        if (drew && RealFlags.debugOverlay) drawOverlay();
        TextureDraw.nextZ = 0f;
        TextureDraw.nextChunkDepth = 0f;
        if (drew) st.drawsReplaced++;
        return drew;
    }

    private static void capture() {
        cx[0] = scratch.x0; cx[1] = scratch.x1; cx[2] = scratch.x2; cx[3] = scratch.x3;
        cy[0] = scratch.y0; cy[1] = scratch.y1; cy[2] = scratch.y2; cy[3] = scratch.y3;
        cu[0] = scratch.u0; cu[1] = scratch.u1; cu[2] = scratch.u2; cu[3] = scratch.u3;
        cv[0] = scratch.v0; cv[1] = scratch.v1; cv[2] = scratch.v2; cv[3] = scratch.v3;
        c1u[0] = scratch.tex1U0; c1u[1] = scratch.tex1U1; c1u[2] = scratch.tex1U2; c1u[3] = scratch.tex1U3;
        c1v[0] = scratch.tex1V0; c1v[1] = scratch.tex1V1; c1v[2] = scratch.tex1V2; c1v[3] = scratch.tex1V3;
        c2u[0] = scratch.tex2U0; c2u[1] = scratch.tex2U1; c2u[2] = scratch.tex2U2; c2u[3] = scratch.tex2U3;
        c2v[0] = scratch.tex2V0; c2v[1] = scratch.tex2V1; c2v[2] = scratch.tex2V2; c2v[3] = scratch.tex2V3;
        quad.tex1 = scratch.tex1;
        quad.tex2 = scratch.tex2;
        quad.useAttribArray = scratch.useAttribArray;
        quad.tex1Col0 = scratch.tex1Col0; quad.tex1Col1 = scratch.tex1Col1;
        quad.tex1Col2 = scratch.tex1Col2; quad.tex1Col3 = scratch.tex1Col3;
    }

    // ------------------------------------------------------------------ wall

    private static boolean drawWall(FlagDef def, FlagInstance inst, Texture tex, float r, float g, float b, float a) {
        // far LOD also halves the render mesh (fewer quads to submit); the pole canvas follows its coarser cloth grid
        boolean far = inst.lod == Prototypes.FAR;
        int mc = far ? Math.max(2, def.meshCols / 2) : def.meshCols;
        int mr = far ? Math.max(2, def.meshRows / 2) : def.meshRows;
        if ((mc + 1) * (mr + 1) > MAX_V) return false;
        int ts = Core.tileScale;
        float tlx = frameX + def.clothX * frameK, tly = frameY + def.clothY * frameK;
        float widthM = def.wallWidthMetres(), heightM = def.wallHeightMetres();
        int cols = inst.cloth.cols(), rows = inst.cloth.rows();
        boolean moving = !inst.cloth.isSleeping();

        for (int j = 0; j <= mr; j++) {
            float t = (float) j / mr;
            for (int i = 0; i <= mc; i++) {
                float s = (float) i / mc;
                int k = j * (mc + 1) + i;
                float px = bilerp(cx, s, t), py = bilerp(cy, s, t);
                vu[k] = bilerp(cu, s, t); vv[k] = bilerp(cv, s, t);
                v1u[k] = bilerp(c1u, s, t); v1v[k] = bilerp(c1v, s, t);
                v2u[k] = bilerp(c2u, s, t); v2v[k] = bilerp(c2v, s, t);
                float shade = 1f;
                if (moving) {
                    Projection.wallScreenToUv(def.facing, tlx, tly, widthM, heightM, ts, px, py, tmp, 0);
                    Projection.sampleGrid2(inst.screen, cols, rows, tmp[0], tmp[1], tmp, 2);
                    px += tmp[2];
                    py += tmp[3];
                    shade = sampleGrid1(inst.shade, cols, rows, tmp[0], tmp[1]);
                }
                vx[k] = px; vy[k] = py; vs[k] = shade;
            }
        }
        quad.setMainUv = true;
        for (int j = 0; j < mr; j++) {
            for (int i = 0; i < mc; i++) {
                int k0 = j * (mc + 1) + i, k1 = k0 + 1, k3 = k0 + (mc + 1), k2 = k3 + 1;
                quad.corner(0, vu[k0], vv[k0], v1u[k0], v1v[k0], v2u[k0], v2v[k0]);
                quad.corner(1, vu[k1], vv[k1], v1u[k1], v1v[k1], v2u[k1], v2v[k1]);
                quad.corner(2, vu[k2], vv[k2], v1u[k2], v1v[k2], v2u[k2], v2v[k2]);
                quad.corner(3, vu[k3], vv[k3], v1u[k3], v1v[k3], v2u[k3], v2v[k3]);
                emit(tex, vx[k0], vy[k0], vx[k1], vy[k1], vx[k2], vy[k2], vx[k3], vy[k3],
                        vs[k0], vs[k1], vs[k2], vs[k3], r, g, b, a);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ pole

    private static boolean drawPole(FlagDef def, FlagInstance inst, Texture tex, float r, float g, float b, float a) {
        Texture canvas = Canvases.get(def.canvasTexture);
        if (canvas == null || !canvas.isReady()) { RealFlags.stats.fallbackCanvas++; return false; }

        // 1) static pole pieces
        quad.setMainUv = true;
        for (FlagDef.StaticQuad sq : def.staticQuads) {
            Texture src = sq.sprite() == null ? tex : Canvases.sprite(sq.sprite());
            if (src == null || !src.isReady()) { RealFlags.stats.fallbackStatic++; continue; }
            float dx0 = frameX + sq.dst().x0() * frameK, dy0 = frameY + sq.dst().y0() * frameK;
            float dx1 = frameX + sq.dst().x1() * frameK, dy1 = frameY + sq.dst().y1() * frameK;
            float su0 = frameU(src, sq.src().x0()), su1 = frameU(src, sq.src().x1());
            float sv0 = frameV(src, sq.src().y0()), sv1 = frameV(src, sq.src().y1());
            depthCorner(0, dx0, dy0, su0, sv0);
            depthCorner(1, dx1, dy0, su1, sv0);
            depthCorner(2, dx1, dy1, su1, sv1);
            depthCorner(3, dx0, dy1, su0, sv1);
            emit(src, dx0, dy0, dx1, dy0, dx1, dy1, dx0, dy1, 1f, 1f, 1f, 1f, r, g, b, a);
        }

        // 2) canvas at the cloth's absolute positions, depth from a point on the shaft
        float ax = frameX + def.anchor.x() * frameK, ay = frameY + def.anchor.y() * frameK;
        float dpx = frameX + def.depthPoint.x() * frameK, dpy = frameY + def.depthPoint.y() * frameK;
        depthAt(dpx, dpy, tmp);
        float d1u = tmp[0], d1v = tmp[1], d2u = tmp[2], d2v = tmp[3];
        int cols = inst.cloth.cols(), rows = inst.cloth.rows();
        float[] scr = inst.screen;
        float[] shade = inst.shade;
        float u0 = def.canvasU0, du = def.canvasU1 - def.canvasU0, v0 = def.canvasV0, dv = def.canvasV1 - def.canvasV0;
        float tu0 = canvas.getXStart(), tdu = canvas.getXEnd() - canvas.getXStart();
        float tv0 = canvas.getYStart(), tdv = canvas.getYEnd() - canvas.getYStart();
        for (int j = 0; j + 1 < rows; j++) {
            for (int i = 0; i + 1 < cols; i++) {
                int k0 = j * cols + i, k1 = k0 + 1, k3 = k0 + cols, k2 = k3 + 1;
                float uA = tu0 + (u0 + du * i / (cols - 1)) * tdu, uB = tu0 + (u0 + du * (i + 1) / (cols - 1)) * tdu;
                float vA = tv0 + (v0 + dv * j / (rows - 1)) * tdv, vB = tv0 + (v0 + dv * (j + 1) / (rows - 1)) * tdv;
                quad.corner(0, uA, vA, d1u, d1v, d2u, d2v);
                quad.corner(1, uB, vA, d1u, d1v, d2u, d2v);
                quad.corner(2, uB, vB, d1u, d1v, d2u, d2v);
                quad.corner(3, uA, vB, d1u, d1v, d2u, d2v);
                emit(canvas,
                        ax + scr[2 * k0], ay + scr[2 * k0 + 1], ax + scr[2 * k1], ay + scr[2 * k1 + 1],
                        ax + scr[2 * k2], ay + scr[2 * k2 + 1], ax + scr[2 * k3], ay + scr[2 * k3 + 1],
                        shade[k0], shade[k1], shade[k2], shade[k3], r, g, b, a);
            }
        }
        return true;
    }

    /** Main-texture U of a 2x-frame x coordinate in {@code t}'s atlas, clamped to its trimmed rectangle. */
    private static float frameU(Texture t, float fx2x) {
        float fx = fx2x * t.getWidthOrig() / 128f - t.getOffsetX();
        fx = Math.max(0f, Math.min(t.getWidth(), fx));
        return t.getXStart() + fx / t.getWidth() * (t.getXEnd() - t.getXStart());
    }

    private static float frameV(Texture t, float fy2x) {
        float fy = fy2x * t.getHeightOrig() / 256f - t.getOffsetY();
        fy = Math.max(0f, Math.min(t.getHeight(), fy));
        return t.getYStart() + fy / t.getHeight() * (t.getYEnd() - t.getYStart());
    }

    /** Depth/second-texture UVs at a screen point, by affine extension of the captured (axis-aligned) vanilla quad. */
    private static void depthAt(float px, float py, float[] out) {
        float sw = cx[1] - cx[0], sh = cy[3] - cy[0];
        float s = sw != 0f ? (px - cx[0]) / sw : 0f, t = sh != 0f ? (py - cy[0]) / sh : 0f;
        out[0] = bilerp(c1u, s, t);
        out[1] = bilerp(c1v, s, t);
        out[2] = bilerp(c2u, s, t);
        out[3] = bilerp(c2v, s, t);
    }

    private static void depthCorner(int k, float px, float py, float mu, float mv) {
        depthAt(px, py, tmp);
        quad.corner(k, mu, mv, tmp[0], tmp[1], tmp[2], tmp[3]);
    }

    // ------------------------------------------------------------------ emit

    /** Screen offset added to every emitted vertex (dev stress-test ghosts); 0,0 for real flags. */
    static void setOffset(float ox, float oy) {
        offX = ox;
        offY = oy;
    }

    private static float offX, offY;

    private static void emit(Texture t, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                             float s0, float s1, float s2, float s3, float r, float g, float b, float a) {
        TextureDraw.nextZ = nextZ;
        TextureDraw.nextChunkDepth = nextChunkDepth;
        x0 += offX; x1 += offX; x2 += offX; x3 += offX;
        y0 += offY; y1 += offY; y2 += offY; y3 += offY;
        SpriteRenderer.instance.render(t, x0, y0, x1, y1, x2, y2, x3, y3,
                r * s0, g * s0, b * s0, a, r * s1, g * s1, b * s1, a,
                r * s2, g * s2, b * s2, a, r * s3, g * s3, b * s3, a, quad);
        RealFlags.stats.subQuads++;
    }

    /** Debug: pulsing tint over the vanilla sprite rectangle, depth-tested like the flag. */
    private static void drawOverlay() {
        Texture white = Texture.getWhite(); // constructor-made texture, drawn ungated by vanilla too (D28)
        if (white == null) return;
        float t = (System.nanoTime() % 2_000_000_000L) / 1e9f;
        float alpha = 0.25f + 0.15f * (float) Math.sin(t * Math.PI);
        quad.setMainUv = false;
        for (int k = 0; k < 4; k++) quad.corner(k, 0f, 0f, c1u[k], c1v[k], c2u[k], c2v[k]);
        emit(white, cx[0], cy[0], cx[1], cy[1], cx[2], cy[2], cx[3], cy[3], 1f, 1f, 1f, 1f, 1f, 0.15f, 0.85f, alpha);
        quad.setMainUv = true;
    }

    private static float bilerp(float[] c, float s, float t) {
        float top = c[0] + (c[1] - c[0]) * s;
        float bottom = c[3] + (c[2] - c[3]) * s;
        return top + (bottom - top) * t;
    }

    private static float sampleGrid1(float[] f, int cols, int rows, float u, float v) {
        float gx = u * (cols - 1), gy = v * (rows - 1);
        int i0 = Math.min(cols - 2, Math.max(0, (int) gx)), j0 = Math.min(rows - 2, Math.max(0, (int) gy));
        float fx = gx - i0, fy = gy - j0;
        int a = j0 * cols + i0;
        float top = f[a] + (f[a + 1] - f[a]) * fx, bot = f[a + cols] + (f[a + cols + 1] - f[a + cols]) * fx;
        return top + (bot - top) * fy;
    }

    /** Fills one emitted quad's UVs and copies the captured depth/second texture; reused (TextureDraw.Create calls it synchronously). */
    static final class QuadModifier implements Consumer<TextureDraw> {
        final float[] u = new float[4], v = new float[4], t1u = new float[4], t1v = new float[4], t2u = new float[4], t2v = new float[4];
        Texture tex1, tex2;
        byte useAttribArray;
        int tex1Col0, tex1Col1, tex1Col2, tex1Col3;
        boolean setMainUv = true;

        void corner(int k, float mu, float mv, float au, float av, float bu, float bv) {
            u[k] = mu; v[k] = mv; t1u[k] = au; t1v[k] = av; t2u[k] = bu; t2v[k] = bv;
        }

        @Override
        public void accept(TextureDraw td) {
            if (setMainUv) {
                td.u0 = u[0]; td.u1 = u[1]; td.u2 = u[2]; td.u3 = u[3];
                td.v0 = v[0]; td.v1 = v[1]; td.v2 = v[2]; td.v3 = v[3];
            }
            td.tex1 = tex1;
            if (tex1 != null) {
                td.tex1U0 = t1u[0]; td.tex1U1 = t1u[1]; td.tex1U2 = t1u[2]; td.tex1U3 = t1u[3];
                td.tex1V0 = t1v[0]; td.tex1V1 = t1v[1]; td.tex1V2 = t1v[2]; td.tex1V3 = t1v[3];
            }
            td.tex2 = tex2;
            if (tex2 != null) {
                td.tex2U0 = t2u[0]; td.tex2U1 = t2u[1]; td.tex2U2 = t2u[2]; td.tex2U3 = t2u[3];
                td.tex2V0 = t2v[0]; td.tex2V1 = t2v[1]; td.tex2V2 = t2v[2]; td.tex2V3 = t2v[3];
            }
            td.useAttribArray = useAttribArray;
            td.tex1Col0 = tex1Col0; td.tex1Col1 = tex1Col1; td.tex1Col2 = tex1Col2; td.tex1Col3 = tex1Col3;
        }
    }
}
