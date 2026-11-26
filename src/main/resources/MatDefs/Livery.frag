// The cars' paint (Livery.j3md): jME's PBRLighting.frag (3.9) with
//  - the base color painted from the position on the car (LIVERY_MODE 0 the Formula car's body,
//    1 the helmet; a port of the Unreal version's Scripts/livery.hlsl, no UVs or textures but the
//    sponsor lettering atlas), or BaseColor / BaseColorMap when Mode is unset (the Indy car);
//  - a clear coat (jME has no clear-coat shading model): a lacquer layer over the PBR base with
//    its own Fresnel (F0 0.04) and roughness (ClearCoatRoughness), lit by the sun (GGX) and the sky
//    (the light probe's prefiltered environment map). The base under it is dimmed by the light the
//    coat reflects, as in Unreal's clear coat: at grazing angles the paint reads as a mirror of the
//    sky, face on it is the satin color with a sharp sun glint, so the tops read brighter than
//    the sides.
#import "Common/ShaderLib/GLSLCompat.glsllib"

#define ENABLE_PBRLightingUtils_getWorldPosition 1
#define ENABLE_PBRLightingUtils_getLocalPosition 1
#define ENABLE_PBRLightingUtils_getWorldNormal 1
#define ENABLE_PBRLightingUtils_getWorldTangent 1
#define ENABLE_PBRLightingUtils_getTexCoord 1
#define ENABLE_PBRLightingUtils_readPBRSurface 1
#define ENABLE_PBRLightingUtils_computeDirectLightContribution 1
#define ENABLE_PBRLightingUtils_computeProbesContribution 1

#import "Common/ShaderLib/module/pbrlighting/PBRLightingUtils.glsllib"

#ifdef DEBUG_VALUES_MODE
    uniform int m_DebugValuesMode;
#endif

uniform vec4 g_LightData[NB_LIGHTS];
uniform vec3 g_CameraPosition;

#ifdef CLEAR_COAT
    uniform float m_ClearCoat;
    uniform float m_ClearCoatRoughness;
#endif

#ifdef USE_FOG
    #import "Common/ShaderLib/MaterialFog.glsllib"
#endif

#ifdef LIVERY_MODE
// ------------------------------------------------------------------------------------ the livery
// Car frame: x forward, y across (+ left), z up (m), origin on the ground under the center of
// mass. The model (f1car.glb, glTF / jME: +Z forward, +X left, +Y up, the same origin) is
// swizzled to it; Unreal's imported mesh (+Y forward, X left, Z up, cm) is P.yxz * 0.01 there.
//
// Body patterns (1995-style schemes):
//   0 split    C1 nose and cockpit, C2 behind a diagonal (sidepods, engine cover), a C3 pinstripe
//   1 sweep    C1, a diagonal C2 band across the sidepods with C3 / C4 streaks ahead of it
//   2 arrow    C1 on top, C2 lower body, C3 / C2 chevrons on the nose pointing forward
//   3 blocks   C1, C2 sidepods and wings, C3 engine cover and nose tip
//   4 stripes  C1, C2 / C3 stripes nose to tail along the middle, C2 lower body
//   5 wave     C1 above a wave, C3 band, C2 below, a C4 slash on the sidepods
// Sponsor lettering (the Decals atlas, blender/make_decals.py) in C4, or black / white where C4
// would not show.
//
// Helmet patterns: 0 crown and band, 1 swept side stripes, 2 blades over the top, 3 swirl,
// 4 chevron, 5 halves.

varying vec3 lNormal;
uniform int m_Pattern;
uniform vec3 m_C1;
uniform vec3 m_C2;
uniform vec3 m_C3;
uniform vec3 m_C4;
uniform float m_Seed;
uniform sampler2D m_Decals;

// a crisp, antialiased step: 0 where v < 0, 1 where v > 0, over about a pixel
#define LIV_EDGE(v) clamp((v) / max(fwidth(v), 1e-4) + 0.5, 0.0, 1.0)
#define LIV_LUM(c) dot(c, vec3(0.2126, 0.7152, 0.0722))
#define LIV_HASH(a) fract(sin(dot(a, vec3(12.9898, 78.233, 37.719))) * 43758.5453)

vec3 liveryHelmet(vec3 p) {
    vec3 C1 = m_C1, C2 = m_C2, C3 = m_C3;
    vec3 d = normalize(p - vec3(0.34, 0.0, 0.76));
    float dy = abs(d.y);
    vec3 h = C1;
    int pat = m_Pattern;
    if (pat == 0) {
        h = mix(h, C3, LIV_EDGE(0.08 - abs(d.z + 0.03)));
        h = mix(h, C2, LIV_EDGE(0.02 - abs(d.z - 0.08)));
        h = mix(h, C3, LIV_EDGE(d.z - 0.60));
        h = mix(h, C2, LIV_EDGE(d.z - 0.70));
    } else if (pat == 1) {
        float s = d.z + 0.30 * d.x;
        h = mix(h, C2, LIV_EDGE(0.08 - abs(s - 0.12)));
        h = mix(h, C3, LIV_EDGE(0.06 - abs(s - 0.27)));
    } else if (pat == 2) {
        float blade = LIV_EDGE(0.08 - abs(dy - 0.24)) * LIV_EDGE(d.z + 0.05);
        h = mix(h, C3, LIV_EDGE(0.11 - abs(dy - 0.24)) * LIV_EDGE(d.z + 0.08));
        h = mix(h, C2, blade);
        h = mix(h, C2, LIV_EDGE(-0.45 - d.z));
    } else if (pat == 3) {
        float a = atan(d.y, d.x) / 6.2831853;
        float sw = fract(a * 3.0 + d.z * 1.2);
        h = mix(h, C3, LIV_EDGE(0.20 - abs(sw - 0.5)));
        h = mix(h, C2, LIV_EDGE(0.14 - abs(sw - 0.5)));
        h = mix(h, C2, LIV_EDGE(d.z - 0.82));
    } else if (pat == 4) {
        float v = d.z - 0.55 * abs(d.x + 0.2) + 0.15;
        h = mix(h, C3, LIV_EDGE(-v));
        h = mix(h, C2, LIV_EDGE(0.11 - abs(v)));
        h = mix(h, C2, LIV_EDGE(0.07 - dy) * LIV_EDGE(d.z - 0.2));
    } else {
        h = mix(C2, C1, LIV_EDGE(d.z - 0.10));
        h = mix(h, C3, LIV_EDGE(0.045 - abs(d.z - 0.10)));
        h = mix(h, C3, LIV_EDGE(0.05 - dy) * LIV_EDGE(d.z - 0.10));
    }
    return h;
}

// One sponsor panel: the lettering's coverage (0..1). (u, v) the position along and up the panel,
// box (u0, u1, v0, v1) its extent, gate where on the car it may show; mirror flips the name so it
// reads from where it is seen.
float liveryDecal(float b, vec4 bx, float u, float v, float gate, bool mirror) {
    float inBox = LIV_EDGE(u - bx.x) * LIV_EDGE(bx.y - u) * LIV_EDGE(v - bx.z) * LIV_EDGE(bx.w - v);
    float hgt = bx.w - bx.z, uc = 0.5 * (bx.x + bx.y);
    float du = mirror ? uc - u : u - uc;
    bool shortBox = (bx.y - bx.x) / hgt < 4.0;
    float pick = LIV_HASH(vec3(b, m_Seed, 1.7));
    float row = shortBox ? 10.0 + floor(pick * 5.999) : floor(pick * 9.999);
    vec2 uv = vec2(0.5 + du / (hgt * 8.0), (row + 1.0 - clamp((v - bx.z) / hgt, 0.0, 1.0)) / 16.0);
    float letters = texture2D(m_Decals, uv).r;
    return letters * inBox * gate;
}

vec3 liveryBody(vec3 p, vec3 n) {
    vec3 C1 = m_C1, C2 = m_C2, C3 = m_C3, C4 = m_C4;
    float ay = abs(p.y);
    float x = p.x, z = p.z;
    int pat = m_Pattern;

    float sidepod = LIV_EDGE(ay - 0.31) * LIV_EDGE(0.80 - x) * LIV_EDGE(x + 1.06) * LIV_EDGE(0.56 - z);
    float frontWing = LIV_EDGE(x - 1.90) * LIV_EDGE(0.335 - z) * LIV_EDGE(ay - 0.09);
    float rearWing = LIV_EDGE(-1.45 - x) * LIV_EDGE(z - 0.80) * LIV_EDGE(0.482 - ay);
    float endplate = LIV_EDGE(-1.40 - x) * LIV_EDGE(ay - 0.482);
    float cover = LIV_EDGE(0.24 - ay) * LIV_EDGE(0.12 - x) * LIV_EDGE(x + 1.6) * LIV_EDGE(z - 0.50);
    vec3 col = C1;

    if (pat == 0) {
        // C1 nose and cockpit, C2 from a diagonal back (sidepods, engine cover, rear wing)
        float t = x + 1.6 * z - 1.55;
        col = mix(C2, C1, LIV_EDGE(t));
        col = mix(col, C3, LIV_EDGE(0.014 - abs(t)) * (1.0 - frontWing));
        col = mix(col, C1, frontWing);
        col = mix(col, C2, frontWing * LIV_EDGE(ay - 0.67));
        col = mix(col, C2, rearWing);
    } else if (pat == 1) {
        float u = x + 1.5 * z;
        col = mix(col, C2, LIV_EDGE(0.30 - abs(u - 0.95)));
        col = mix(col, C3, LIV_EDGE(0.025 - abs(u - 1.33)));
        col = mix(col, C4, LIV_EDGE(0.025 - abs(u - 1.42)));
        col = mix(col, C2, LIV_EDGE(0.025 - abs(u - 1.51)));
        col = mix(col, C3, LIV_EDGE(x - 2.0) * (1.0 - frontWing));
        col = mix(col, C2, cover * LIV_EDGE(z - 0.80));
        col = mix(col, C2, frontWing);
    } else if (pat == 2) {
        col = mix(C2, C1, LIV_EDGE(z - 0.30 + 0.03 * x));
        float v = x - 1.3 * ay;
        col = mix(col, C3, LIV_EDGE(0.09 - abs(v - 1.05)) * LIV_EDGE(z - 0.30));
        col = mix(col, C2, LIV_EDGE(0.05 - abs(v - 1.30)) * LIV_EDGE(z - 0.30));
        col = mix(col, C3, rearWing + endplate * LIV_EDGE(z - 0.70));
        col = mix(col, C2, frontWing);
    } else if (pat == 3) {
        col = mix(col, C2, sidepod);
        col = mix(col, C3, LIV_EDGE(0.015 - abs(z - 0.25)) * sidepod);
        col = mix(col, C3, cover);
        col = mix(col, C3, LIV_EDGE(x - 1.95) * (1.0 - frontWing));
        col = mix(col, C2, frontWing + rearWing);
        col = mix(col, C3, endplate * LIV_EDGE(z - 0.75));
    } else if (pat == 4) {
        col = mix(col, C2, LIV_EDGE(0.24 - z + 0.04 * x));
        col = mix(col, C2, LIV_EDGE(0.045 - ay) * LIV_EDGE(z - 0.30));
        col = mix(col, C3, LIV_EDGE(0.012 - abs(ay - 0.065)) * LIV_EDGE(z - 0.30));
        col = mix(col, C2, rearWing);
        col = mix(col, C3, endplate * LIV_EDGE(0.012 - abs(z - 0.62)));
    } else {
        float w = 0.40 + 0.06 * sin(x * 3.2);
        col = mix(C2, C1, LIV_EDGE(z - w));
        col = mix(col, C3, LIV_EDGE(0.03 - abs(z - w + 0.035)));
        col = mix(col, C4, sidepod * LIV_EDGE(0.06 - abs(x + 0.9 * z - 0.20)));
        col = mix(col, C1, frontWing);
        col = mix(col, C3, rearWing);
    }

    // ------------------------------------------------------------ sponsor lettering
    // Decals: the lettering atlas, 16 rows of 8:1 cells, the name centered in its row: rows 0-9
    // long names, 10-15 short. Each panel: (u0, u1) along it, (v0, v1) up it; the name is as tall
    // as the panel and centered along it. The sides are mirrored by the way the surface faces.
    float side = LIV_EDGE(abs(n.y) - 0.55);
    float up = LIV_EDGE(n.z - 0.55);
    bool flip = n.y > 0.0;
    float textMask = 0.0;
    // sidepod flanks
    textMask = max(textMask, liveryDecal(0.0, vec4(-0.50, 0.36, 0.22, 0.36), x, z, side * LIV_EDGE(ay - 0.55), flip));
    // rear wing endplates
    textMask = max(textMask, liveryDecal(1.0, vec4(-1.92, -1.50, 0.60, 0.76), x, z, side * endplate, flip));
    // engine cover flanks
    textMask = max(textMask, liveryDecal(2.0, vec4(-0.95, -0.25, 0.565, 0.665), x, z, side * cover * LIV_EDGE(ay - 0.10), flip));
    // rear wing top (across the car, v along x)
    textMask = max(textMask, liveryDecal(3.0, vec4(-0.40, 0.40, -1.92, -1.80), -p.y, x, up * rearWing * LIV_EDGE(z - 0.93), false));
    // nose top (along it, v across)
    textMask = max(textMask, liveryDecal(4.0, vec4(1.20, 1.95, -0.045, 0.045), x, p.y, up * LIV_EDGE(z - 0.40), false));
    // front wing endplates
    textMask = max(textMask, liveryDecal(5.0, vec4(2.00, 2.42, 0.08, 0.19), x, z, side * frontWing * LIV_EDGE(ay - 0.68), flip));

    vec3 ink = C4;
    if (abs(LIV_LUM(ink) - LIV_LUM(col)) < 0.12) ink = LIV_LUM(col) > 0.25 ? vec3(0.02, 0.02, 0.025) : vec3(0.85);
    return mix(col, ink, textMask);
}

vec3 liveryColor() {
    // the model's frame (+Z forward, +X left, +Y up) to the car frame (x forward, y left, z up)
    vec3 p = lPosition.zxy;
    #if LIVERY_MODE == 1
        return liveryHelmet(p);
    #else
        return liveryBody(p, normalize(lNormal.zxy));
    #endif
}
#endif

void main(){
    vec3 wpos = PBRLightingUtils_getWorldPosition();
    vec3 worldViewDir = normalize(g_CameraPosition - wpos);

    PBRSurface surface = PBRLightingUtils_createPBRSurface(worldViewDir);
    PBRLightingUtils_readPBRSurface(surface);
    #ifdef LIVERY_MODE
        surface.albedo = liveryColor();
    #endif
    PBRLightingUtils_calculatePreLightingValues(surface);

    #ifdef CLEAR_COAT
        // the lacquer's normal: the surface's, facing the viewer on the inside of single-sided parts
        vec3 coatN = surface.frontFacing ? surface.normal : -surface.normal;
        float coatNdotV = clamp(dot(coatN, surface.viewDir), 0.001, 1.0);
        // the share of light the coat reflects (Schlick, F0 0.04); the base gets the rest
        float coatF = m_ClearCoat * (0.04 + 0.96 * pow(1.0 - coatNdotV, 5.0));
        float coatRough = max(m_ClearCoatRoughness, 0.03);
        vec3 coatSpecular = vec3(0.0);
    #endif

    for(int i = 0;i < NB_LIGHTS; i+=3){
        vec4 lightData0 = g_LightData[i];
        vec4 lightData1 = g_LightData[i+1];
        vec4 lightData2 = g_LightData[i+2];
        PBRLightingUtils_computeDirectLightContribution(lightData0, lightData1, lightData2, surface);
        #ifdef CLEAR_COAT
            // the sun's glint on the lacquer
            Light light = PBRLightingUtils_newLight(vec4(lightData0.rgb, 1.0), lightData1.xyz, lightData0.w,
                    lightData1.w, lightData2.w, lightData2.xyz);
            PBRLightingUtils_computeLightInWorldSpace(surface.position, coatN, surface.viewDir, light);
            vec3 cDiffuse, cSpecular;
            PBR_ComputeDirectLight(coatN, light.dir.xyz, surface.viewDir, light.color.rgb, vec3(0.04 * m_ClearCoat),
                    coatRough, coatNdotV, cDiffuse, cSpecular);
            coatSpecular += cSpecular * light.fallOff * (light.fallOff == 1.0 ? surface.exposure : 1.0);
        #endif
    }

    PBRLightingUtils_computeProbesContribution(surface);

    gl_FragColor.rgb = vec3(0.0);
    gl_FragColor.rgb += surface.bakedLightContribution;
    gl_FragColor.rgb += surface.directLightContribution;
    gl_FragColor.rgb += surface.envLightContribution;

    #ifdef CLEAR_COAT
        // the paint under the lacquer, then the sky in it
        gl_FragColor.rgb *= 1.0 - coatF;
        gl_FragColor.rgb += coatSpecular;
        #if NB_PROBES >= 1
            vec3 rv = reflect(-surface.viewDir, coatN);
            vec4 probePos = g_LightProbeData[3];
            float invRadius = fract(probePos.w);
            float nbMipMaps = probePos.w - invRadius;
            if (g_LightProbeData[0][3] == 0.0) rv = invRadius * (surface.position - probePos.xyz) + rv;
            vec3 sky = textureCubeLod(g_PrefEnvMap, rv, sqrt(coatRough) * (nbMipMaps - 1.0)).rgb;
            // a reflection that points into the car (the far side of a curve) is the car itself
            vec3 coatGN = surface.frontFacing ? surface.geometryNormal : -surface.geometryNormal;
            float horizon = clamp(1.0 + dot(reflect(-surface.viewDir, coatN), coatGN), 0.0, 1.0);
            gl_FragColor.rgb += sky * coatF * horizon * horizon * max(surface.exposure, 0.0533);
        #endif
    #endif

    gl_FragColor.rgb += surface.emission;
    gl_FragColor.a = surface.alpha;

    #ifdef USE_FOG
        gl_FragColor = MaterialFog_calculateFogColor(vec4(gl_FragColor));
    #endif

    #ifdef DEBUG_VALUES_MODE
        gl_FragColor = PBRLightingUtils_getColorOutputForDebugMode(m_DebugValuesMode, vec4(gl_FragColor.rgba), surface);
    #endif
}
