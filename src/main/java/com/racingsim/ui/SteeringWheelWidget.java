package com.racingsim.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

/**
 * Steering wheel at the bottom center of the screen. The hub sits below the screen edge so
 * only the arc from 10 to 2 o'clock is visible; the rest of the wheel is implied. A red stripe
 * marks 12 o'clock. The whole wheel rotates with the steering input (keyboard or mouse).
 */
public final class SteeringWheelWidget {

    /** Wheel rotation at full steering input (degrees, like a real wheel at full lock). */
    private static final float MAX_ROTATION_DEG = 90f;

    private final Node root = new Node("SteeringWheel");
    private final Node wheel = new Node("WheelRotor");
    private final BitmapText status;
    private final float radius;

    /**
     * @param radius outer rim radius in pixels; the hub is placed radius/2 below the bottom
     *               edge so that 10 and 2 o'clock sit exactly on the screen edge
     */
    public SteeringWheelWidget(AssetManager assets, BitmapFont font, float screenWidth, float radius) {
        this.radius = radius;
        float cx = screenWidth / 2f, cy = -radius / 2f;
        wheel.setLocalTranslation(cx, cy, 0);
        root.attachChild(wheel);

        Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setBoolean("VertexColor", true);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        float rimW = radius * 0.11f;
        float rIn = radius - rimW;
        // rim body: dark rubber with a lighter top edge and darker outer edge (bevel look)
        add(mat, "Rim", arc(rIn, radius, -180, 180, 160,
                new ColorRGBA(0.06f, 0.06f, 0.07f, 0.95f), new ColorRGBA(0.16f, 0.16f, 0.18f, 0.95f)), 1);
        add(mat, "RimInnerEdge", arc(rIn - 2f, rIn + 1.5f, -180, 180, 160,
                new ColorRGBA(0.35f, 0.35f, 0.38f, 0.9f), new ColorRGBA(0.35f, 0.35f, 0.38f, 0.9f)), 2);
        add(mat, "RimOuterEdge", arc(radius - 2f, radius + 1.5f, -180, 180, 160,
                new ColorRGBA(0.02f, 0.02f, 0.02f, 0.9f), new ColorRGBA(0.02f, 0.02f, 0.02f, 0.9f)), 2);
        // suede grips where the hands go (around 10 and 2 o'clock) with stitching lines
        for (float center : new float[]{-55f, 55f}) {
            add(mat, "Grip", arc(rIn + 1.5f, radius - 2f, center - 22, center + 22, 40,
                    new ColorRGBA(0.11f, 0.11f, 0.13f, 1f), new ColorRGBA(0.22f, 0.22f, 0.25f, 1f)), 3);
            for (float edge : new float[]{center - 22, center + 22}) {
                add(mat, "GripEdge", arc(rIn + 1.5f, radius - 2f, edge - 0.6f, edge + 0.6f, 2,
                        new ColorRGBA(0.5f, 0.5f, 0.55f, 1f), new ColorRGBA(0.5f, 0.5f, 0.55f, 1f)), 4);
            }
        }
        // carbon spokes toward the (hidden) hub, at 10:30 / 1:30 and 6 o'clock
        for (float a : new float[]{-48f, 48f, 180f}) {
            add(mat, "Spoke", spoke(radius * 0.18f, rIn, a, radius * 0.07f,
                    new ColorRGBA(0.10f, 0.10f, 0.11f, 0.95f)), 1);
        }
        // red 12 o'clock stripe, slightly proud of the rim
        add(mat, "TopStripe", arc(rIn - 3f, radius + 3f, -2.6f, 2.6f, 4,
                new ColorRGBA(0.85f, 0.05f, 0.05f, 1f), new ColorRGBA(1f, 0.15f, 0.12f, 1f)), 5);

        status = new BitmapText(font);
        status.setSize(font.getCharSet().getRenderedSize() * 0.8f);
        root.attachChild(status);
        setMouseSteering(false, screenWidth);
    }

    public Node getNode() { return root; }

    /** Height of the visible part of the wheel above the screen bottom (pixels). */
    public float visibleHeight() { return radius / 2f + 3f; }

    /** @param steer -1..1, positive = left (counter-clockwise) */
    public void update(float steer) {
        float angle = FastMath.clamp(steer, -1f, 1f) * MAX_ROTATION_DEG * FastMath.DEG_TO_RAD;
        wheel.setLocalRotation(new Quaternion().fromAngleAxis(angle, Vector3f.UNIT_Z));
    }

    private boolean mouseOn;

    public void setMouseSteering(boolean on, float screenWidth) {
        if (on == mouseOn && status.getText() != null && !status.getText().isEmpty()) return;
        mouseOn = on;
        status.setText(on ? "MOUSE STEERING ON  -  click mouse wheel to release"
                : "Click the mouse wheel to steer with the mouse");
        status.setColor(on ? new ColorRGBA(0.3f, 1f, 0.45f, 1f) : new ColorRGBA(0.75f, 0.77f, 0.8f, 0.85f));
        status.setLocalTranslation(screenWidth / 2f - status.getLineWidth() / 2f, visibleHeight() + 24f, 1);
    }

    private void add(Material mat, String name, Mesh mesh, int layer) {
        Geometry g = new Geometry(name, mesh);
        g.setMaterial(mat);
        g.setQueueBucket(RenderQueue.Bucket.Gui);
        g.setLocalTranslation(0, 0, layer * 0.01f);
        wheel.attachChild(g);
    }

    /**
     * Annular sector between angles a0..a1 (degrees clockwise from 12 o'clock), colored with
     * an inner -> outer gradient.
     */
    private static Mesh arc(float rIn, float rOut, float a0, float a1, int segments, ColorRGBA inner, ColorRGBA outer) {
        float[] pos = new float[(segments + 1) * 2 * 3];
        float[] col = new float[(segments + 1) * 2 * 4];
        short[] idx = new short[segments * 6];
        for (int i = 0; i <= segments; i++) {
            float a = (a0 + (a1 - a0) * i / segments) * FastMath.DEG_TO_RAD;
            float sx = FastMath.sin(a), sy = FastMath.cos(a);       // clockwise from 12 o'clock
            int v = i * 2;
            pos[v * 3] = sx * rIn; pos[v * 3 + 1] = sy * rIn;
            pos[v * 3 + 3] = sx * rOut; pos[v * 3 + 4] = sy * rOut;
            putColor(col, v, inner);
            putColor(col, v + 1, outer);
            if (i < segments) {
                int k = i * 6;
                idx[k] = (short) v; idx[k + 1] = (short) (v + 1); idx[k + 2] = (short) (v + 3);
                idx[k + 3] = (short) v; idx[k + 4] = (short) (v + 3); idx[k + 5] = (short) (v + 2);
            }
        }
        return mesh(pos, col, idx);
    }

    /** Tapered spoke from radius r0 to r1 at angle a (degrees clockwise from 12 o'clock). */
    private static Mesh spoke(float r0, float r1, float aDeg, float width, ColorRGBA color) {
        float a = aDeg * FastMath.DEG_TO_RAD;
        float dx = FastMath.sin(a), dy = FastMath.cos(a);
        float px = dy, py = -dx;                                        // perpendicular
        float w0 = width, w1 = width * 0.6f;
        float[] pos = {
                dx * r0 + px * w0, dy * r0 + py * w0, 0,
                dx * r0 - px * w0, dy * r0 - py * w0, 0,
                dx * r1 - px * w1, dy * r1 - py * w1, 0,
                dx * r1 + px * w1, dy * r1 + py * w1, 0};
        float[] col = new float[16];
        for (int i = 0; i < 4; i++) putColor(col, i, color);
        return mesh(pos, col, new short[]{0, 1, 2, 0, 2, 3});
    }

    /** Colors are authored in sRGB; vertex colors are linear in the gamma-correct pipeline. */
    private static void putColor(float[] col, int v, ColorRGBA c) {
        col[v * 4] = srgbToLinear(c.r);
        col[v * 4 + 1] = srgbToLinear(c.g);
        col[v * 4 + 2] = srgbToLinear(c.b);
        col[v * 4 + 3] = c.a;
    }

    private static float srgbToLinear(float x) {
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4f);
    }

    private static Mesh mesh(float[] pos, float[] col, short[] idx) {
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(col));
        m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createShortBuffer(idx));
        m.updateBound();
        return m;
    }
}
