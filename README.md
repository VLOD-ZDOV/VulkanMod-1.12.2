# VulkanMod112 0.2.0

Experimental Vulkan terrain renderer for Minecraft Forge 1.12.2. Minecraft still owns the window and OpenGL context; VulkanMod112 mirrors vanilla chunk VBOs to Vulkan, renders opaque terrain there, then composites colour and depth back into the game's framebuffer through GPU external-memory interop.

## Current scope

- Vulkan device selection and isolated LWJGL 3 runtime alongside Minecraft's LWJGL 2.
- Device-local Vulkan chunk geometry in one growable shared buffer with persistently mapped staging uploads, plus block atlas and lightmap.
- Vulkan rendering for `SOLID`, `CUTOUT_MIPPED` and `CUTOUT` terrain layers.
- Vanilla OpenGL remains responsible for translucent terrain, entities, tile entities, particles, sky and GUI.
- If Vulkan, required driver extensions, or terrain rendering fail, the game falls back to vanilla OpenGL rather than crashing.
- Video Settings includes a **VulkanMod112 Settings...** page with a terrain switch, diagnostic-overlay switch and a render-distance slider up to 64 chunks.

This is not yet a complete replacement for the modern VulkanMod renderer.

## Requirements

- Forge 14.23.5.2857 (or compatible 1.12.2 Forge) / Minecraft 1.12.2.
- MixinBooter 10.7 in the instance `mods` directory when installing the released JAR manually. It is a required runtime dependency; Prism Launcher does not download Gradle dependencies automatically.
- A 64-bit Windows or Linux Vulkan driver.
- Matching OpenGL and Vulkan external-memory/semaphore extensions for the terrain path: `GL_EXT_memory_object_fd` / `GL_EXT_semaphore_fd` with `VK_KHR_external_memory_fd` / `VK_KHR_external_semaphore_fd` on Linux, and the `_win32` variants of the same four on Windows. The mod selects the pair for the host platform automatically. Without them it loads safely but leaves terrain in OpenGL.

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
- `-Dvulkanmod112.depthBlit=false` — composite depth through the fragment shader instead of `glBlitFramebuffer`; use if depth looks wrong after the change.
- `-Dvulkanmod112.allowIncompatibleRenderer=true` — test with OptiFine/shader-mod renderer replacements; unsupported and off by default.

## In-game settings

Open **Options → Video Settings → VulkanMod112 Settings...**. The terrain switch is applied immediately and returns to vanilla OpenGL when disabled. The diagnostic overlay is off by default.

The slider permits 2–64 chunks. 64 is an experimental maximum: vanilla 1.12.2 must allocate a very large render-chunk grid, so it can consume substantial CPU and RAM, and multiplayer servers can impose a smaller view-distance cap. Increase it gradually and restart the world if the chunk grid does not refresh immediately.

For Vulkan/OpenGL sharing, both APIs must select the same GPU. On hybrid Linux systems, check F3: both the Vulkan device and OpenGL renderer should report the discrete NVIDIA GPU. Running only Vulkan on NVIDIA while OpenGL is on an iGPU is not supported.

## Compatibility and diagnostics

OptiFine and legacy shader mods alter the same renderer classes as this mod. Their presence disables Vulkan terrain automatically and leaves vanilla rendering active. This is a safety measure, not claimed support.

The F3 overlay reports GPU selection, VBO mirror statistics, active terrain mode and chunk count. Periodic log entries report fence wait, command recording and submit/composite timings. Start with `validation=true` when debugging a driver or synchronisation issue.
