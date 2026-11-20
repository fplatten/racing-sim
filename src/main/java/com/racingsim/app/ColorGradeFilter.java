package com.racingsim.app;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;

/** Final colour grade: saturation, filmic contrast, warm tint and a soft vignette. */
public final class ColorGradeFilter extends Filter {

    private final float saturation, contrast, vignette;
    private final ColorRGBA tint;

    public ColorGradeFilter(float saturation, float contrast, float vignette, ColorRGBA tint) {
        super("ColorGradeFilter");
        this.saturation = saturation;
        this.contrast = contrast;
        this.vignette = vignette;
        this.tint = tint;
    }

    @Override
    protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp, int w, int h) {
        material = new Material(manager, "MatDefs/ColorGrade.j3md");
        material.setFloat("Saturation", saturation);
        material.setFloat("Contrast", contrast);
        material.setFloat("Vignette", vignette);
        material.setColor("Tint", tint);
    }

    @Override
    protected Material getMaterial() {
        return material;
    }
}
