package com.racingsim.vehicle;

import com.jme3.asset.AssetManager;
import com.jme3.effect.ParticleEmitter;
import com.jme3.effect.ParticleMesh;
import com.jme3.effect.shapes.EmitterBoxShape;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tyre smoke: when a tyre slides sideways past its grip peak (or locks up / spins hard), puffs of
 * smoke billow from its contact patch, then grow, drift up and fade. One shared world-space particle
 * pool serves every car; puffs are emitted per wheel from the slip each frame (fractional amounts
 * carry over, so light slides give a thin wisp and big slides a thick cloud). The puff sprites are
 * drawn at start-up (2 x 2 atlas of soft, noisy blobs).
 */
public final class TireSmoke {

    private static final int MAX_PARTICLES = 2400;
    /** Puffs per second from one wheel at full slide. */
    private static final float MAX_RATE = 130f;

    private final ParticleEmitter emitter;
    /**
     * Puffs start anywhere in a small box above the tyre's contact patch, so the cloud has a ragged
     * outline. They start above the road and rise faster than they grow, so no puff ever dips
     * into the asphalt (a camera-facing quad that does shows a hard, straight cut).
     */
    private final EmitterBoxShape box = new EmitterBoxShape(new Vector3f(), new Vector3f(0.8f, 0.45f, 0.8f));
    private final Vector3f boxMin = new Vector3f();
    private final Map<Car, float[]> carry = new HashMap<>();
    private final Vector3f local = new Vector3f(), world = new Vector3f(), vel = new Vector3f();
    private final Random rnd = new Random(7);

    public TireSmoke(AssetManager assets, Node parent) {
        emitter = new ParticleEmitter("TireSmoke", ParticleMesh.Type.Triangle, MAX_PARTICLES);
        Material m = new Material(assets, "Common/MatDefs/Misc/Particle.j3md");
        m.setTexture("Texture", puffAtlas());
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        m.getAdditionalRenderState().setDepthWrite(false);
        emitter.setMaterial(m);
        emitter.setImagesX(2);
        emitter.setImagesY(2);
        emitter.setSelectRandomImage(true);
        emitter.setInWorldSpace(true);
        emitter.setParticlesPerSec(0);                // emitted by hand from the sliding wheels
        emitter.setShape(box);
        emitter.setStartColor(new ColorRGBA(0.80f, 0.80f, 0.80f, 0.20f));
        emitter.setEndColor(new ColorRGBA(0.86f, 0.86f, 0.87f, 0f));
        emitter.setStartSize(0.5f);
        emitter.setEndSize(3.2f);
        emitter.setLowLife(1.6f);
        emitter.setHighLife(3.2f);
        emitter.setGravity(0f, -0.45f, 0f);           // negative gravity: warm smoke keeps rising
        emitter.setRotateSpeed(0.6f);
        emitter.setRandomAngle(true);
        emitter.getParticleInfluencer().setVelocityVariation(0.35f);
        emitter.setShadowMode(RenderQueue.ShadowMode.Off);
        emitter.setQueueBucket(RenderQueue.Bucket.Transparent);
        parent.attachChild(emitter);
    }

    /**
     * Emits smoke for the cars' wheels this frame. {@code paused} freezes the smoke (pause prompt).
     */
    public void update(float tpf, List<Car> cars, boolean paused) {
        emitter.setEnabled(!paused);
        if (paused) return;
        for (Car car : cars) {
            if (car.getNode().getParent() == null) continue;
            VehiclePhysics p = car.getPhysics();
            float speed = p.getSpeed();
            float[] acc = carry.computeIfAbsent(car, c -> new float[4]);
            VehiclePhysics.Wheel[] wheels = p.getWheels();
            for (int i = 0; i < 4; i++) {
                float amount = intensity(wheels[i], speed);
                if (amount <= 0f) { acc[i] = 0f; continue; }
                acc[i] += amount * MAX_RATE * tpf;
                int n = (int) acc[i];
                if (n == 0) continue;
                acc[i] -= n;
                VehiclePhysics.Wheel w = wheels[i];
                // contact patch: below the wheel centre, in the car's local frame
                local.set(w.mountLocal.x, w.mountLocal.y - w.suspensionLength - car.getSpec().wheelRadius + 0.30f,
                        w.mountLocal.z);
                car.getNode().localToWorld(local, world);
                // smoke is shed slowly: a little of the car's motion, a lift, and random spread
                vel.set(car.getBody().getLinearVelocity()).multLocal(0.04f).addLocal(0f, 1.3f + 0.5f * amount, 0f);
                box.setMin(boxMin.set(world).addLocal(-0.4f, 0f, -0.4f));
                emitter.getParticleInfluencer().setInitialVelocity(vel);
                emitter.emitParticles(n);
            }
        }
    }

    /** 0..1 smoke from one wheel: sideways slide past the grip peak, plus hard lock-up or wheelspin. */
    private static float intensity(VehiclePhysics.Wheel w, float speed) {
        if (!w.contact || !w.onTrack || w.load < 400f || speed < 4f) return 0f;
        float lateral = (Math.abs(w.slipAngle) - 0.10f) / 0.22f;            // ~6 deg onwards, full by ~18 deg
        float longitudinal = (Math.abs(w.slipRatio) - 0.25f) / 0.45f;       // big lock-ups / wheelspin only
        float s = Math.max(lateral, 0.6f * longitudinal);
        float load = Math.min(1f, w.load / 3000f);
        return FastMath.clamp(s, 0f, 1f) * (0.4f + 0.6f * load) * Math.min(1f, speed / 15f);
    }

    /** Clears all smoke (race restart). */
    public void clear() {
        emitter.killAllParticles();
        carry.clear();
    }

    /** 2 x 2 atlas of soft smoke puffs: a radial falloff broken up by layered noise (white, alpha). */
    private static Texture2D puffAtlas() {
        int cell = 128, size = cell * 2;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(42);
        for (int k = 0; k < 4; k++) {
            int ox = (k % 2) * cell, oy = (k / 2) * cell;
            float[] noise = TireTexture.smoothNoise(cell, cell, 4, 4, rnd);
            float[] fine = TireTexture.smoothNoise(cell, cell, 16, 16, rnd);
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    float dx = (x + 0.5f) / cell * 2 - 1, dy = (y + 0.5f) / cell * 2 - 1;
                    float r = (float) Math.sqrt(dx * dx + dy * dy);
                    float n = 0.65f * noise[y * cell + x] + 0.35f * fine[y * cell + x];
                    float falloff = (float) Math.exp(-r * r * 4.0);              // soft gaussian blob
                    // billows: a few soft lobes around the centre rather than grid-aligned noise
                    double ang = Math.atan2(dy, dx);
                    float lobes = (float) (0.5 + 0.5 * Math.sin(ang * 3 + k * 1.7) * Math.sin(ang * 5 - k));
                    float a = TireTexture.clamp01(falloff * (0.70f + 0.20f * n + 0.25f * lobes * r) - 0.03f) * (r < 1f ? 1f : 0f);
                    int shade = Math.round(225 + 30 * n);
                    shade = Math.min(255, shade);
                    img.setRGB(ox + x, oy + y, (Math.round(a * 255) << 24) | (shade << 16) | (shade << 8) | shade);
                }
            }
        }
        Texture2D tex = new Texture2D(new AWTLoader().load(img, false));
        tex.setMinFilter(Texture.MinFilter.Trilinear);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        return tex;
    }
}
