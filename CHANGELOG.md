# Changelog

## [0.1.0] - 2026-07-24

### Fixed

- Restored compilation of the per-frame lightmap staging path.
- Select the correct staging buffer for each frame in flight and release every staging allocation and fence.
- Extract the LWJGL 3 OpenGL native companion required by GL/Vulkan interop.

### Performance

- Move mirrored chunk draw buffers from host-visible memory to device-local VRAM.
- Batch VBO copies into one Vulkan submission before each terrain frame instead of synchronising an upload per chunk.
- Suballocate all chunk VBOs in a shared growable vertex buffer, reducing per-frame vertex-buffer binds to one per terrain layer.
- Align each shared-buffer allocation to the 28-byte vanilla vertex stride, fixing corrupted UV/colour attributes after small or empty VBOs.

### Added

- Safe automatic fallback when OptiFine or legacy shader renderer classes are detected.
- Runtime requirements, diagnostic flags and current renderer scope in the README.
