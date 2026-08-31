#version 450

layout(push_constant) uniform PushConstants {
    float angle;
} pc;

layout(location = 0) out vec3 fragColor;

vec2 positions[3] = vec2[](
    vec2( 0.0, -0.8),
    vec2( 0.8,  0.8),
    vec2(-0.8,  0.8)
);

vec3 colors[3] = vec3[](
    vec3(1.0, 0.0, 0.0),
    vec3(0.0, 1.0, 0.0),
    vec3(0.0, 0.0, 1.0)
);

void main() {
    float c = cos(pc.angle);
    float s = sin(pc.angle);
    vec2 p = mat2(c, -s, s, c) * positions[gl_VertexIndex];
    gl_Position = vec4(p, 0.0, 1.0);
    fragColor = colors[gl_VertexIndex];
}
