package com.racingsim.vehicle;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

import java.util.function.Function;

/**
 * The cars' paint: the Livery material ({@code MatDefs/Livery.j3md}), a satin base under a glossy
 * clear coat that mirrors the sky.
 * <p>
 * The Formula car is painted by the material from the position on the car (a port of the Unreal
 * version's {@code Scripts/livery.hlsl}): its team's {@link CarPaint} scheme on every painted part
 * of the body (the model's {@code Livery}, {@code LiverySecondary} and {@code LiveryAccent} parts
 * share one material: the pattern decides the colors, not the part), the helmet's scheme on the
 * {@code Helmet}. The Indy car's liveries are baked textures ({@link LiveryPainter}) under the same
 * clear coat ({@link #baked}).
 */
final class FormulaPaint {

    /** The paint under the lacquer: satin; the helmet's shell is harder and glossier. */
    private static final float BODY_ROUGHNESS = 0.38f, HELMET_ROUGHNESS = 0.25f;
    /** The clear coat on top: a mirror-smooth lacquer. */
    private static final float CLEAR_COAT_ROUGHNESS = 0.04f;

    private FormulaPaint() { }

    /**
     * The materials for one Formula car's painted parts: a function from a part's material name to
     * its material, or {@code null} for a part that is not painted (carbon, tires, ...).
     */
    static Function<String, Material> forLivery(AssetManager assets, Livery livery) {
        CarPaint paint = CarPaint.forField(LiveryPainter.fieldIndex(livery));
        Material body = material(assets, livery, BODY_ROUGHNESS);
        body.setInt("Mode", 0);
        for (int c = 0; c < 4; c++) body.setVector3("C" + (c + 1), rgb(paint.body()[c]));
        body.setInt("Pattern", paint.pattern());
        body.setFloat("Seed", livery.number() * 0.37f);
        body.setTexture("Decals", decals(assets));
        Material helmet = material(assets, livery, HELMET_ROUGHNESS);
        helmet.setInt("Mode", 1);
        for (int c = 0; c < 3; c++) helmet.setVector3("C" + (c + 1), rgb(paint.helmet()[c]));
        helmet.setInt("Pattern", paint.helmetPattern());
        return name -> name.startsWith("Livery") ? body : name.startsWith("Helmet") ? helmet : null;
    }

    /**
     * The clear coat over a baked base color texture (the Indy car's livery and helmet). The
     * roughness is the material's, or the map's (times the material's) when one is set.
     */
    static Material baked(AssetManager assets, Livery livery, Texture baseColor, boolean helmet) {
        Material m = material(assets, livery, helmet ? HELMET_ROUGHNESS : BODY_ROUGHNESS);
        m.setTexture("BaseColorMap", baseColor);
        return m;
    }

    private static Material material(AssetManager assets, Livery livery, float roughness) {
        Material m = new Material(assets, "MatDefs/Livery.j3md");
        m.setName("Paint-" + livery.name());
        m.setFloat("Metallic", 0f);
        m.setFloat("Roughness", roughness);
        m.setFloat("ClearCoat", 1f);
        m.setFloat("ClearCoatRoughness", CLEAR_COAT_ROUGHNESS);
        m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return m;
    }

    /** The sponsor lettering atlas (blender/make_decals.py): a linear mask, top row first. */
    private static Texture decals(AssetManager assets) {
        TextureKey key = new TextureKey("Textures/Car/decals_mask.png", false);
        key.setGenerateMips(true);
        Texture t = assets.loadTexture(key);
        t.getImage().setColorSpace(ColorSpace.Linear);
        t.setMinFilter(Texture.MinFilter.Trilinear);
        t.setAnisotropicFilter(8);
        return t;
    }

    private static Vector3f rgb(ColorRGBA c) {
        return new Vector3f(c.r, c.g, c.b);
    }
}
