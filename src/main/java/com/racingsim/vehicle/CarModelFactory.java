package com.racingsim.vehicle;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import com.racingsim.track.Pbr;

/**
 * Builds a race car from the Blender-made glTF models ({@code blender/make_car.py}):
 * the body (monocoque, sidepods, wings, suspension, driver) plus four wheels, with PBR
 * materials and a per-car livery texture painted by {@link LiveryPainter}.
 * Car local frame: +Z forward, +X left, origin = centre of mass.
 */
public final class CarModelFactory {

    /** Result: body node plus the four wheel pivot / spinner nodes (FL, FR, RL, RR). */
    public record CarModel(Node root, Node[] wheelPivots, Node[] wheelSpinners) { }

    private final AssetManager assets;
    private final Spatial bodyTemplate;
    private final Spatial wheelFrontTemplate;
    private final Spatial wheelRearTemplate;
    private final Material carbon, black, suspension, visor, light, tire, rim, hub;

    public CarModelFactory(AssetManager assets) {
        this.assets = assets;
        bodyTemplate = assets.loadModel("Models/Car/indycar.glb");
        wheelFrontTemplate = assets.loadModel("Models/Car/wheel_front.glb");
        wheelRearTemplate = assets.loadModel("Models/Car/wheel_rear.glb");
        MikktspaceTangentGenerator.generate(bodyTemplate);   // carbon parts are normal mapped

        carbon = Pbr.textured(assets, "Textures/Car/carbon_albedo.png", "Textures/Car/carbon_normal.png", null, 0.25f, 0.28f);
        black = Pbr.material(assets, new ColorRGBA(0.02f, 0.02f, 0.02f, 1), 0f, 0.7f);
        suspension = Pbr.material(assets, new ColorRGBA(0.08f, 0.08f, 0.09f, 1), 0.6f, 0.35f);
        visor = Pbr.material(assets, new ColorRGBA(0.05f, 0.05f, 0.07f, 1), 0.9f, 0.05f);
        light = Pbr.material(assets, new ColorRGBA(0.6f, 0.02f, 0.02f, 1), 0f, 0.3f);
        light.setColor("Emissive", new ColorRGBA(1f, 0.05f, 0.02f, 1));
        tire = Pbr.material(assets, ColorRGBA.White, 0f, 0.82f);
        Texture tireTex = TireTexture.create();
        tire.setTexture("BaseColorMap", tireTex);
        rim = Pbr.material(assets, new ColorRGBA(0.10f, 0.10f, 0.11f, 1), 0.85f, 0.32f);
        hub = Pbr.material(assets, new ColorRGBA(0.85f, 0.68f, 0.25f, 1), 1f, 0.3f);
        // lathe-turned wheel surfaces are single sided: render both faces
        for (Material m : new Material[]{tire, rim, hub}) {
            m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        }
    }

    public CarModel build(VehicleSpec spec, Livery livery, float rideHeight, boolean highDetail) {
        Node root = new Node("CarModel-" + livery.name());

        Material paint = Pbr.material(assets, ColorRGBA.White, 0.05f, 0.16f);
        paint.setTexture("BaseColorMap", LiveryPainter.paint(livery, highDetail ? 2048 : 1024));
        paint.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        Material helmet = Pbr.material(assets, livery.accent(), 0.1f, 0.12f);

        Spatial body = bodyTemplate.clone(false);
        body.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) g.setMaterial(materialFor(partName(g), paint, helmet));
        });
        body.setLocalTranslation(0, -rideHeight, 0);
        root.attachChild(body);

        Node[] pivots = new Node[4];
        Node[] spinners = new Node[4];
        for (int i = 0; i < 4; i++) {
            boolean front = i < 2;
            boolean leftSide = (i % 2) == 0;
            float x = (leftSide ? 1 : -1) * (front ? spec.frontHalfTrack : spec.rearHalfTrack);
            float z = front ? spec.frontAxleZ : spec.rearAxleZ;
            Node pivot = new Node("WheelPivot" + i);
            pivot.setLocalTranslation(x, spec.mountHeight - spec.restLength, z);
            Node spinner = new Node("WheelSpin" + i);
            Spatial wheel = (front ? wheelFrontTemplate : wheelRearTemplate).clone(false);
            wheel.depthFirstTraversal(s -> {
                if (s instanceof Geometry g) g.setMaterial(materialFor(partName(g), paint, helmet));
            });
            if (!leftSide) {
                // the model's outer face points +X; mirror it for the right-hand wheels
                Node flip = new Node("flip");
                flip.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y));
                flip.attachChild(wheel);
                spinner.attachChild(flip);
            } else {
                spinner.attachChild(wheel);
            }
            pivot.attachChild(spinner);
            root.attachChild(pivot);
            pivots[i] = pivot;
            spinners[i] = spinner;
        }
        root.setShadowMode(RenderQueue.ShadowMode.Cast);
        return new CarModel(root, pivots, spinners);
    }

    /** The Blender material name (also used as mesh / object name by the export script). */
    private static String partName(Geometry g) {
        Material m = g.getMaterial();
        if (m != null && m.getName() != null && !m.getName().isEmpty()) return m.getName();
        Spatial s = g;
        while (s != null) {
            if (s.getName() != null && !s.getName().isEmpty() && !s.getName().startsWith("Geometry")) return s.getName();
            s = s.getParent();
        }
        return "";
    }

    private Material materialFor(String name, Material paint, Material helmet) {
        if (name.startsWith("Livery")) return paint;
        if (name.startsWith("Carbon")) return carbon;
        if (name.startsWith("Suspension") || name.startsWith("Susp")) return suspension;
        if (name.startsWith("Helmet")) return helmet;
        if (name.startsWith("Visor")) return visor;
        if (name.startsWith("Light")) return light;
        if (name.startsWith("Tire") || name.contains("Tire")) return tire;
        if (name.startsWith("Hub") || name.contains("Hub")) return hub;
        if (name.startsWith("Rim") || name.contains("Rim")) return rim;
        return black;
    }
}
