# VulkanMod112 0.9.0

Experimental Vulkan terrain renderer for Minecraft Forge 1.12.2. Minecraft still owns the window and OpenGL context; VulkanMod112 mirrors vanilla chunk VBOs to Vulkan, renders opaque terrain there, then composites colour and depth back into the game's framebuffer through GPU external-memory interop.

## Current scope

- Vulkan device selection and isolated LWJGL 3 runtime alongside Minecraft's LWJGL 2.
- Device-local Vulkan chunk geometry in one growable shared buffer with persistently mapped staging uploads, plus block atlas and lightmap.
- Vulkan rendering for the `SOLID`, `CUTOUT_MIPPED`, `CUTOUT` and `TRANSLUCENT` terrain layers.
- Optional dynamic lights, computed while shading rather than rebuilt into the world.
- Optional dropping of the vanilla chunk buffers once Vulkan holds the geometry, so the world is stored once in video memory instead of twice.
- Particles, rain and snow drawn in Vulkan, riding the translucent pass rather than opening one of their own.
- Optional ray-traced shadows from the sun, from carried lights, from burning creatures and from the light-emitting blocks already in the world, with frame averaging to turn one ray per pixel into a soft edge. Needs `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`; without them the settings do nothing and say so.
- Optional water refraction, and a round sun and moon drawn at runtime rather than shipped as files.
- Vanilla OpenGL remains responsible for entities, tile entities, the sky and the GUI.
- If Vulkan, required driver extensions, or terrain rendering fail, the game falls back to vanilla OpenGL rather than crashing.
- Video Settings includes a **VulkanMod112 Settings...** page with presets, a geometry budget, per-setting CPU/GPU/VRAM costs and a render-distance slider up to 64 chunks, or 128 with Extreme Render Distance turned on.
- Hold-to-zoom on **C** (rebindable under Controls), with mouse sensitivity scaled to match.

This is not yet a complete replacement for the modern VulkanMod renderer.

## Current limits

- The world's geometry exists twice by default: once in the game's own OpenGL buffers and once in the Vulkan mirror. On a card with little memory to spare that is what caps the usable render distance, and it depends on how much geometry is actually in view rather than on the distance setting alone. **Drop Vanilla Chunk Buffers** removes the duplicate and is on by default; switching it either way rebuilds the world. The settings header shows the memory the GPU reports, and the diagnostics log shows what the mirror is using.
- Chunk building and uploading dominate the frame while the camera moves at high render distances. Every chunk is still uploaded twice — once by the game to OpenGL, once here to Vulkan — but this mod's copy now happens on the thread that built the chunk rather than on the thread that draws, so it no longer competes for the per-frame upload budget the game runs on the render thread.
- Entities, the sky and the GUI are still drawn by vanilla OpenGL, so no effect here reaches them: a burning creature does not glow, casts nothing into the corner it stands in, and appears in no reflection. Drawing entities in Vulkan is started and switched off — the pass they would go in cannot write depth, and the switch says so.
- The item model held in first person is not lit by dynamic lights, only what it lights is.

## Requirements

- Forge 14.23.5.2857 (or compatible 1.12.2 Forge) / Minecraft 1.12.2.
- MixinBooter 10.7 in the instance `mods` directory when installing the released JAR manually. It is a required runtime dependency and no launcher resolves it for you.
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
- `-Dvulkanmod112.geometryBudget=MiB` — geometry budget; 0 derives it from the GPU. Also in the settings screen.
- `-Dvulkanmod112.framesInFlight=1..3` — how far the CPU may run ahead of the GPU. Also in the settings screen.
- `-Dvulkanmod112.rayTracing=true` — build acceleration structures over the terrain without opening the menu.
- `-Dvulkanmod112.noAtlasAnimations=true` — stop uploading animated block textures, to tell that path apart from another when something goes wrong.
- `-Dvulkanmod112.slowChunkMs=N` — how long a chunk build has to take before it is named in the log. 100 by default.

## In-game settings

Open **Options → Video Settings → VulkanMod112 Settings...**. The terrain switch is applied immediately and returns to vanilla OpenGL when disabled. The diagnostic overlay is off by default.

Defaults are the conservative choice throughout: nothing is traded for speed until you ask for it. Three presets on the Rendering page do the asking — **Stable** (the shipped values), **Balanced** (caps the draw distances vanilla leaves wider than anyone can see) and **Performance** (trades visible detail for frames). A preset writes its settings once and then stops existing, so anything you change afterwards stays changed. **Reset** at the bottom restores this mod's settings only; Minecraft's own are left alone.

Every row states what it costs on the CPU, the GPU and in VRAM separately, because which of the three you are short of decides whether a setting will help you at all.

**Geometry Budget** (Advanced) sets how much video memory the world geometry may take before the renderer stops growing its buffer generously. Each growth stops the GPU and re-uploads every chunk, so on a card with memory to spare a larger budget buys those stutters away; on a small one a lower value keeps the footprint tight. Automatic uses a quarter of the device-local memory the GPU reports, shown in the screen header. Chunks are never dropped to stay inside the budget — it steers growth, it is not a cap.

The slider permits 2–64 chunks, and 2–128 with **Extreme Render Distance** on. Both are experimental maxima: vanilla 1.12.2 allocates a render chunk for every cell of a `(2d+1) x (2d+1) x 16` grid as soon as a world loads and keeps all of them — 266 256 at 64 and 1 056 784 at 128 — so CPU and RAM are spent up front whether or not there is terrain out there to put in them, and multiplayer servers can impose a smaller view-distance cap regardless. Increase it gradually and restart the world if the chunk grid does not refresh immediately. Turning Extreme Render Distance back off pulls the distance down to 64 with it.

Zero-copy sharing requires OpenGL and Vulkan to run on the same GPU. On systems with more than one, the mod compares device UUIDs at startup and stays on vanilla rendering if they differ, naming both devices in the log.

## Troubleshooting

Turn on Ultra Logging in the settings screen, reproduce, and attach
`logs/vulkanmod112-diagnostics.log`. It records versions, installed mods, the GL driver,
every active renderer path, the frame cost breakdown and resource counts.

## Compatibility and diagnostics

OptiFine, legacy shader mods and Sodium-derived renderers for 1.12.2 — Celeritas, and Actinium which ships it — replace the same renderer classes this mod rewrites. When one of them is installed, the terrain mixins are not registered at all, so the game boots on that renderer while this mod's settings screen and game-side optimisations stay active. Sharing terrain rendering between the two is not possible: the vertex format and pass order differ, and with a shader pack loaded the format changes again.

Any Forge build for 1.12.2 works; the only hard dependency is MixinBooter 10.7 or newer, which Forge now reports itself if missing.

A renderer replacement this build has not heard of can be named without waiting for a release: `-Dvulkanmod112.extraRendererMarkers=part-of-its-jar-name` makes this mod stand aside for it.

The F3 overlay reports GPU selection, VBO mirror statistics, active terrain mode and chunk count. Periodic log entries report fence wait, command recording, submit/composite and GPU timings. For anything more detailed, turn on Ultra Logging and attach `logs/vulkanmod112-diagnostics.log`. Start with `validation=true` when debugging a driver or synchronisation issue.
