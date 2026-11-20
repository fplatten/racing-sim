package com.racingsim.track;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture;

/** Small factory for physically based (PBRLighting) materials and textures. */
public final class Pbr {

    private Pbr() { }

    /** @param srgb base colour given in sRGB (converted to linear for the shader) */
    public static Material material(AssetManager assets, ColorRGBA srgb, float metallic, float roughness) {
        Material m = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setColor("BaseColor", linear(srgb));
        m.setFloat("Metallic", metallic);
        m.setFloat("Roughness", roughness);
        return m;
    }

    public static ColorRGBA linear(ColorRGBA srgb) {
        return new ColorRGBA().setAsSrgb(srgb.r, srgb.g, srgb.b, srgb.a);
    }

    public static Texture texture(AssetManager assets, String path, boolean repeat) {
        TextureKey key = new TextureKey(path, false);
        key.setGenerateMips(true);
        Texture t = assets.loadTexture(key);
        t.setMinFilter(Texture.MinFilter.Trilinear);
        t.setAnisotropicFilter(8);
        if (repeat) t.setWrap(Texture.WrapMode.Repeat);
        return t;
    }

    /** Textured material: albedo + optional normal / roughness maps. */
    public static Material textured(AssetManager assets, String albedo, String normal, String rough,
                                    float metallic, float roughness) {
        Material m = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setTexture("BaseColorMap", texture(assets, albedo, true));
        if (normal != null) {
            m.setTexture("NormalMap", texture(assets, normal, true));
            m.setFloat("NormalType", 1.0f); // OpenGL (Y+) normal maps
        }
        if (rough != null) m.setTexture("RoughnessMap", texture(assets, rough, true));
        m.setFloat("Metallic", metallic);
        m.setFloat("Roughness", roughness);
        return m;
    }
}
