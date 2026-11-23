#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_Texture;
uniform float m_Saturation;
uniform float m_Contrast;
uniform float m_Vignette;
uniform vec4 m_Tint;
varying vec2 texCoord;

// Simple grade in linear space: saturation, contrast pivoting on mid gray (0.18), tint, vignette.
void main() {
    vec4 c = texture2D(m_Texture, texCoord);
    vec3 col = max(c.rgb, vec3(0.0));
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = max(mix(vec3(luma), col, m_Saturation), vec3(0.0));
    col = 0.18 * pow(col / 0.18, vec3(m_Contrast));
    col *= m_Tint.rgb;
    vec2 d = texCoord - vec2(0.5);
    col *= 1.0 - m_Vignette * dot(d, d) * 1.6;
    gl_FragColor = vec4(col, c.a);
}
