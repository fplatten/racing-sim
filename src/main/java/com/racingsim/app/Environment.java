package com.racingsim.app;

import com.jme3.asset.AssetManager;
import com.jme3.environment.EnvironmentProbeControl;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.LightScatteringFilter;
import com.jme3.post.filters.ToneMapFilter;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.util.SkyFactory;

/**
 * Golden-hour lighting: Blender-rendered sky panorama ({@code blender/make_sky.py}), a matching
 * low warm sun, an image-based light probe for PBR reflections and a post-processing stack
 * (shadows, SSAO, light shafts, bloom, filmic tone mapping, FXAA).
 */
public final class Environment {

    /** Must match SUN_ELEVATION / SUN_AZIMUTH in blender/make_sky.py (Blender frame, degrees). */
    private static final float SUN_ELEVATION = 13f, SUN_AZIMUTH = 60f;
    /**
     * jME's sky shader samples with the sphere's inward normal (i.e. the negated view direction),
     * which turns a Blender equirectangular panorama by 180 degrees; rotating the dome by PI puts
     * the painted sun back where the directional light comes from.
     */
    private static final float SKY_YAW = FastMath.PI;

    private final DirectionalLight sun;
    private final SSAOFilter ssao;
    private final LightScatteringFilter shafts;
    private final BloomFilter bloom;
    private boolean highQuality = true;

    public Environment(AssetManager assets, Node rootNode, ViewPort viewPort) {
        // --- sky ---
        Spatial sky = SkyFactory.createSky(assets, "Textures/Sky/sky_sunset.png", SkyFactory.EnvMapType.EquirectMap);
        sky.setLocalRotation(new Quaternion().fromAngleAxis(SKY_YAW, Vector3f.UNIT_Y));
        rootNode.attachChild(sky);

        // --- sun: Blender (x, y, z) -> jME (x, z, -y) ---
        float el = SUN_ELEVATION * FastMath.DEG_TO_RAD, az = SUN_AZIMUTH * FastMath.DEG_TO_RAD;
        Vector3f toSun = new Vector3f(FastMath.cos(el) * FastMath.cos(az), FastMath.sin(el),
                -FastMath.cos(el) * FastMath.sin(az));
        sun = new DirectionalLight(toSun.negate(), new ColorRGBA(1.0f, 0.82f, 0.60f, 1f).multLocal(3.8f));
        rootNode.addLight(sun);

        // --- image based lighting from the sky ---
        EnvironmentProbeControl probe = new EnvironmentProbeControl(assets, 256);
        rootNode.addControl(probe);
        EnvironmentProbeControl.tagGlobal(sky);

        // --- post processing ---
        FilterPostProcessor fpp = new FilterPostProcessor(assets);
        // shadow renderer (not the filter) so the per-geometry receive flags are honoured:
        // cars cast onto the track but do not self-shadow (no acne on the glossy paint)
        DirectionalLightShadowRenderer shadows = new DirectionalLightShadowRenderer(assets, 4096, 3);
        shadows.setLight(sun);
        shadows.setShadowIntensity(0.6f);
        shadows.setLambda(0.65f);
        shadows.setShadowZExtend(220f);
        shadows.setShadowZFadeLength(40f);
        shadows.setEdgeFilteringMode(EdgeFilteringMode.PCFPOISSON);
        viewPort.addProcessor(shadows);

        ssao = new SSAOFilter(1.6f, 1.6f, 0.3f, 0.06f);
        ssao.setApproximateNormals(true);
        fpp.addFilter(ssao);

        shafts = new LightScatteringFilter(toSun.mult(3000f));
        shafts.setLightDensity(0.35f);
        shafts.setNbSamples(40);
        shafts.setBlurStart(0.02f);
        shafts.setBlurWidth(0.85f);
        fpp.addFilter(shafts);

        bloom = new BloomFilter(BloomFilter.GlowMode.Scene);
        bloom.setExposurePower(4.5f);
        bloom.setExposureCutOff(0.72f);
        bloom.setBloomIntensity(0.55f);
        bloom.setDownSamplingFactor(2f);
        fpp.addFilter(bloom);

        fpp.addFilter(new ToneMapFilter(new Vector3f(7f, 7f, 7f)));
        fpp.addFilter(new ColorGradeFilter(1.22f, 1.12f, 0.45f, new ColorRGBA(1.04f, 1.0f, 0.95f, 1f)));
        fpp.addFilter(new FXAAFilter());
        viewPort.addProcessor(fpp);
    }

    public DirectionalLight getSun() { return sun; }

    public boolean isHighQuality() { return highQuality; }

    /** Toggles the expensive effects (SSAO, light shafts, bloom). */
    public void toggleQuality() {
        highQuality = !highQuality;
        ssao.setEnabled(highQuality);
        shafts.setEnabled(highQuality);
        bloom.setEnabled(highQuality);
    }
}
