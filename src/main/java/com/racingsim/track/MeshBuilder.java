package com.racingsim.track;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.util.Arrays;

/**
 * Small utility for building flat-shaded, vertex-coloured triangle meshes procedurally.
 * Every face gets its own vertices so colours and normals stay crisp.
 * Normals are oriented using a caller-supplied "hint" direction, so winding order does not
 * matter (materials using these meshes should disable face culling).
 */
public final class MeshBuilder {

    private float[] pos = new float[3 * 1024];
    private float[] nrm = new float[3 * 1024];
    private float[] col = new float[4 * 1024];
    private float[] tex = new float[2 * 1024];
    private int[] idx = new int[1536];
    private int vCount;
    private int iCount;

    private static final float[] NO_UV = new float[8];
    private final Vector3f e1 = new Vector3f();
    private final Vector3f e2 = new Vector3f();
    private final Vector3f n = new Vector3f();

    private void ensure(int extraVerts, int extraIdx) {
        if ((vCount + extraVerts) * 3 > pos.length) {
            int cap = Math.max(pos.length * 2, (vCount + extraVerts) * 3);
            pos = Arrays.copyOf(pos, cap);
            nrm = Arrays.copyOf(nrm, cap);
            col = Arrays.copyOf(col, cap / 3 * 4);
            tex = Arrays.copyOf(tex, cap / 3 * 2);
        }
        if (iCount + extraIdx > idx.length) {
            idx = Arrays.copyOf(idx, Math.max(idx.length * 2, iCount + extraIdx));
        }
    }

    private void vertex(Vector3f p, Vector3f normal, ColorRGBA c, float u, float v) {
        int i3 = vCount * 3, i4 = vCount * 4, i2 = vCount * 2;
        tex[i2] = u; tex[i2 + 1] = v;
        pos[i3] = p.x; pos[i3 + 1] = p.y; pos[i3 + 2] = p.z;
        nrm[i3] = normal.x; nrm[i3 + 1] = normal.y; nrm[i3 + 2] = normal.z;
        col[i4] = c.r; col[i4 + 1] = c.g; col[i4 + 2] = c.b; col[i4 + 3] = c.a;
        vCount++;
    }

    /** Adds a planar quad a-b-c-d (in perimeter order). */
    public void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, ColorRGBA color, Vector3f hint) {
        quad(a, b, c, d, color, color, color, color, hint, null);
    }

    /** Quad with texture coordinates {ua, va, ub, vb, uc, vc, ud, vd}. */
    public void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, ColorRGBA color, Vector3f hint, float[] uv) {
        quad(a, b, c, d, color, color, color, color, hint, uv);
    }

    /** Quad with per-corner colours and optional texture coordinates. */
    public void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d,
                     ColorRGBA ca, ColorRGBA cb, ColorRGBA cc, ColorRGBA cd, Vector3f hint, float[] uv) {
        if (uv == null) uv = NO_UV;
        ensure(4, 6);
        b.subtract(a, e1);
        d.subtract(a, e2);
        e1.cross(e2, n);
        if (n.lengthSquared() < 1e-12f) {
            c.subtract(a, e2);
            e1.cross(e2, n);
        }
        n.normalizeLocal();
        if (n.dot(hint) < 0) n.negateLocal();
        int base = vCount;
        vertex(a, n, ca, uv[0], uv[1]);
        vertex(b, n, cb, uv[2], uv[3]);
        vertex(c, n, cc, uv[4], uv[5]);
        vertex(d, n, cd, uv[6], uv[7]);
        // orient triangles so that their geometric front face agrees with the normal
        boolean ccw = e1.cross(e2).dot(n) > 0;
        if (ccw) {
            add(base, base + 1, base + 2);
            add(base, base + 2, base + 3);
        } else {
            add(base, base + 2, base + 1);
            add(base, base + 3, base + 2);
        }
    }

    private void add(int i0, int i1, int i2) {
        idx[iCount++] = i0;
        idx[iCount++] = i1;
        idx[iCount++] = i2;
    }

    /** Oriented box given a centre, three orthonormal axes and half-extents. */
    public void box(Vector3f c, Vector3f ax, Vector3f ay, Vector3f az, float hx, float hy, float hz, ColorRGBA color) {
        Vector3f x = ax.mult(hx), y = ay.mult(hy), z = az.mult(hz);
        Vector3f[] v = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            v[i] = c.clone()
                    .addLocal((i & 1) == 0 ? x.negate() : x)
                    .addLocal((i & 2) == 0 ? y.negate() : y)
                    .addLocal((i & 4) == 0 ? z.negate() : z);
        }
        quad(v[1], v[3], v[7], v[5], color, ax);            // +x
        quad(v[0], v[4], v[6], v[2], color, ax.negate());   // -x
        quad(v[2], v[6], v[7], v[3], color, ay);            // +y
        quad(v[0], v[1], v[5], v[4], color, ay.negate());   // -y
        quad(v[4], v[5], v[7], v[6], color, az);            // +z
        quad(v[0], v[2], v[3], v[1], color, az.negate());   // -z
    }

    /** Axis-aligned box. */
    public void box(Vector3f c, float hx, float hy, float hz, ColorRGBA color) {
        box(c, Vector3f.UNIT_X, Vector3f.UNIT_Y, Vector3f.UNIT_Z, hx, hy, hz, color);
    }

    /**
     * Lofted box between two axis-aligned rectangles lying in planes z = z0 and z = z1
     * (used for tapered car body parts: nose, tub, sidepods, engine cover).
     */
    public void loft(float cx, float z0, float y0, float hw0, float hh0,
                     float z1, float y1, float hw1, float hh1, ColorRGBA color) {
        Vector3f[] a = {
                new Vector3f(cx - hw0, y0 - hh0, z0), new Vector3f(cx + hw0, y0 - hh0, z0),
                new Vector3f(cx + hw0, y0 + hh0, z0), new Vector3f(cx - hw0, y0 + hh0, z0)};
        Vector3f[] b = {
                new Vector3f(cx - hw1, y1 - hh1, z1), new Vector3f(cx + hw1, y1 - hh1, z1),
                new Vector3f(cx + hw1, y1 + hh1, z1), new Vector3f(cx - hw1, y1 + hh1, z1)};
        float dir = Math.signum(z1 - z0);
        Vector3f mid = new Vector3f(cx, (y0 + y1) / 2, (z0 + z1) / 2);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            Vector3f faceCenter = a[i].add(a[j]).addLocal(b[i]).addLocal(b[j]).multLocal(0.25f);
            Vector3f hint = faceCenter.subtract(mid);
            hint.z = 0;
            quad(a[i], a[j], b[j], b[i], color, hint);
        }
        quad(a[0], a[1], a[2], a[3], color, new Vector3f(0, 0, -dir));
        quad(b[0], b[1], b[2], b[3], color, new Vector3f(0, 0, dir));
    }

    public int vertexCount() { return vCount; }

    public Mesh build() {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(Arrays.copyOf(pos, vCount * 3)));
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(Arrays.copyOf(nrm, vCount * 3)));
        mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(Arrays.copyOf(col, vCount * 4)));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(Arrays.copyOf(tex, vCount * 2)));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(Arrays.copyOf(idx, iCount)));
        mesh.updateBound();
        mesh.updateCounts();
        return mesh;
    }
}
