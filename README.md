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

## Roadmap

Remaining work, known limits and their priority: [ROADMAP.md](ROADMAP.md).

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
- `-Dvulkanmod112.ultraLog=true` — write a full diagnostics report to `logs/vulkanmod112-diagnostics.log`; the same switch lives in the settings screen under Advanced.
- `-Dvulkanmod112.extraRendererMarkers=name` — treat additional mod jars as renderer replacements, so the Vulkan terrain mixins are not loaded beside them.
- `-Dvulkanmod112.depthBlit=false` — composite depth through the fragment shader instead of `glBlitFramebuffer`; use if depth looks wrong after the change.
- `-Dvulkanmod112.allowIncompatibleRenderer=true` — test with OptiFine/shader-mod renderer replacements; unsupported and off by default.

## In-game settings

Open **Options → Video Settings → VulkanMod112 Settings...**. The terrain switch is applied immediately and returns to vanilla OpenGL when disabled. The diagnostic overlay is off by default.

The slider permits 2–64 chunks. 64 is an experimental maximum: vanilla 1.12.2 must allocate a very large render-chunk grid, so it can consume substantial CPU and RAM, and multiplayer servers can impose a smaller view-distance cap. Increase it gradually and restart the world if the chunk grid does not refresh immediately.

For Vulkan/OpenGL sharing, both APIs must select the same GPU. On hybrid Linux systems, check F3: both the Vulkan device and OpenGL renderer should report the discrete NVIDIA GPU. Running only Vulkan on NVIDIA while OpenGL is on an iGPU is not supported.

## Troubleshooting

**The game dies the moment the world appears, with no crash report.** The log stops right
after the block atlas is handed to Vulkan and a `hs_err_pid*.log` points at a driver
library. This means OpenGL and Vulkan ended up on different GPUs: sharing memory between
them dereferences a handle the importing driver cannot understand, and the process is gone
before any Java code can react. Recent versions detect this first and refuse the zero-copy
path with a message naming both device UUIDs, leaving the game on vanilla rendering.

On a hybrid Linux system the game's OpenGL context goes to the integrated or software
renderer by default, so force it onto the same discrete card Vulkan picks:

```
__NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia
```

In Prism Launcher: Edit instance -> Settings -> Custom commands -> Wrapper command:

```
env __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia
```

Check the result in F3: the Vulkan device and the OpenGL renderer must name the same GPU.

**Anything else.** Turn on Ultra Logging in the settings screen, reproduce, and attach
`logs/vulkanmod112-diagnostics.log`. It records versions, installed mods, the GL driver,
every active renderer path, the frame cost breakdown and resource counts.

## Compatibility and diagnostics

OptiFine and legacy shader mods replace the same renderer classes this mod rewrites. When one of them is installed, the terrain mixins are not registered at all, so the game boots on that renderer while this mod's settings screen and game-side optimisations stay active. Sharing terrain rendering between the two is not possible: the vertex format and pass order differ, and with a shader pack loaded the format changes again. See [ROADMAP.md](ROADMAP.md) section G.

Any Forge build for 1.12.2 works; the only hard dependency is MixinBooter 10.7 or newer, which Forge now reports itself if missing.

The F3 overlay reports GPU selection, VBO mirror statistics, active terrain mode and chunk count. Periodic log entries report fence wait, command recording, submit/composite and GPU timings. For anything more detailed, turn on Ultra Logging and attach `logs/vulkanmod112-diagnostics.log`. Start with `validation=true` when debugging a driver or synchronisation issue.
