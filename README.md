# VulkanMod112

Experimental Vulkan terrain renderer for Minecraft Forge 1.12.2. Minecraft still owns the window and OpenGL context; VulkanMod112 mirrors vanilla chunk VBOs to Vulkan, renders opaque terrain there, then composites colour and depth back into the game's framebuffer through GPU external-memory interop.

## Current scope

- Vulkan device selection and isolated LWJGL 3 runtime alongside Minecraft's LWJGL 2.
- Device-local Vulkan chunk geometry in one growable shared buffer with persistently mapped staging uploads, plus block atlas and lightmap.
- Vulkan rendering for `SOLID`, `CUTOUT_MIPPED` and `CUTOUT` terrain layers.
- Vanilla OpenGL remains responsible for translucent terrain, entities, tile entities, particles, sky and GUI.
- If Vulkan, required driver extensions, or terrain rendering fail, the game falls back to vanilla OpenGL rather than crashing.

This is not yet a complete replacement for the modern VulkanMod renderer.

## Requirements

- Forge 14.23.5.2847 / Minecraft 1.12.2.
- A 64-bit Windows or Linux Vulkan driver.
- `GL_EXT_memory_object_fd`, `GL_EXT_semaphore_fd` and matching Vulkan external-memory/semaphore extensions for the terrain path. Without them the mod loads safely but leaves terrain in OpenGL.

## Run and build

Use Java 8 for the Minecraft client and Java 25 or newer for Gradle. Configure a local Gradle JDK in your IDE or through `JAVA_HOME`; do not commit machine-specific paths to `gradle.properties`.

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
```

Useful JVM properties:

- `-Dvulkanmod112.terrain=false` — disable Vulkan terrain completely.
- `-Dvulkanmod112.validation=true` — request Vulkan validation layers when installed.
- `-Dvulkanmod112.debugLoader=true` — print LWJGL loader diagnostics.
- `-Dvulkanmod112.cull=false` — disable Vulkan terrain backface culling for visual debugging.
- `-Dvulkanmod112.overlay=true` — show the legacy Vulkan demo overlay.
- `-Dvulkanmod112.allowIncompatibleRenderer=true` — test with OptiFine/shader-mod renderer replacements; unsupported and off by default.

## Compatibility and diagnostics

OptiFine and legacy shader mods alter the same renderer classes as this mod. Their presence disables Vulkan terrain automatically and leaves vanilla rendering active. This is a safety measure, not claimed support.

The F3 overlay reports GPU selection, VBO mirror statistics, active terrain mode and chunk count. Periodic log entries report fence wait, command recording and submit/composite timings. Start with `validation=true` when debugging a driver or synchronisation issue.
