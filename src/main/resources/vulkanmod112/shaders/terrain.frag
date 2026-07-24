#version 450

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 0, binding = 1) uniform sampler2D lightmap;

layout(push_constant) uniform PC {
    mat4 mvp;
    vec4 offsetAndCutoff;
} pc;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLight;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 tex = texture(atlas, vUV);
    if (tex.a < pc.offsetAndCutoff.w) {
        discard;
    }
    vec3 light = texture(lightmap, vLight).rgb;
    outColor = vec4(tex.rgb * vColor.rgb * light, 1.0);
}
