# Roadmap

What is done, and what is planned next. For the detail behind any line, see `CHANGELOG.md`.

Everything new ships behind a switch, and the defaults never trade looks for frames without
being asked. If the Vulkan path fails for any reason, the game falls back to its own renderer
rather than crashing.

## Done

### 0.8.0

- **Ray-traced shadows** from the sun, from a torch in your hand, from a creature that is on fire
  and from the torches already on the walls — with **frame averaging**, which is what turns one
  ray per pixel from grain into a soft edge. Needs a card that can trace from a shader; where
  there is none the settings do nothing and the log says why.
- **Particles, rain and snow drawn in Vulkan**, riding the pass the water already uses so they
  cost nothing beyond the drawing.
- **Water refraction** — the bed moving under the surface rather than sitting still behind it.
- **A round sun and moon**, built at runtime rather than shipped, which is what makes their size
  and warmth sliders. The moon keeps its phases.
- **Named setting profiles**, and **F6** to open the settings from the world.
- **The Vulkan device is chosen to match OpenGL** rather than by which one is faster, because the
  faster one is no use if the other half of the frame is on the other card.
- **The atlas upload no longer stops the frame** twenty times a second while anything is animated.
- **The log can show a stutter**: the median frame, the worst in twenty, the worst in a hundred,
  and where the worst one's time went — an average cannot show a frame that takes forty
  milliseconds once a second, which is the only thing anyone calls a lag.
- **A chunk probe** on a key of its own, which asks the game why the chunk you are looking at is
  not drawn.
- Fixed: the card could be lost outright while ray tracing was on; the world could stop drawing
  after a resource reload; four violations of the Vulkan specification that every driver had been
  quietly forgiving.

### 0.7.0

- **Effects built from the settings screen**, all off by default: water waves, foliage sway,
  bloom, ambient occlusion, screen reflections on water, and foliage lit as a volume rather than
  as the two flat quads it is made of. Not a shader pack loader — a set of effects with a slider
  each. All of them stand on **Material Tags**, which records what a block is made of while the
  chunk is built, because a render layer is not a material: water shares one with stained glass
  and grass shares another with torches.
- **Diagnostic views** under Advanced for the effects that can look plausible while being wrong:
  the occlusion on its own, the reflection as only what its ray found, and the frame painted with
  how far each pixel moved since the last one.
- **Screen reflections are experimental.** They reflect what is on screen and nothing else, which
  is the limit of the technique rather than of this build, but this is the newest thing here.
- **Directional light.** A dropped torch no longer lights the underside of the floor it is lying
  on as brightly as the top. On by default.
- **Height fog**, off by default — how much colour the ground below you gives up to fog.
- **Render distance up to 128**, behind a switch that states the price: the chunk grid the game
  allocates goes from 266 256 chunks to 1 056 784, all of it up front.
- **A cheaper rebuild scan**, and a switch to stop the game rebuilding nearby chunks on the
  thread that draws — which measurement showed is where that step actually spends its time.
- **Frame-time graph** in the corner, off by default, with the 1% low — the number that tells a
  steady framerate from one that stalls, which an average cannot.
- **The settings screen is translated** into Russian, Simplified Chinese, German, French, Spanish,
  Brazilian Portuguese and Japanese, following Minecraft's own language setting.
- **Dynamic light distance is adjustable**, 1 to 200 blocks. Not how far the light reaches — how
  far away a source may be and still light the ground it stands on.

### 0.6.0

- **Water and glass drawn in Vulkan.** They now get fog like everything else.
- **Dynamic lights.** A carried torch, a dropped glowing block or a burning mob lights the world
  around it. Chunks are not rebuilt for it. Off by default.
- **Vanilla chunk buffers can be dropped.** The world is then stored once in video memory instead
  of twice. Off by default.
- **Own visibility search.** The game's own search for which chunks are on screen is a quarter to
  a half of the whole frame at high render distances; this replaces it with one that reads flat
  arrays instead of chasing pointers. On by default.
- **Snow and slabs no longer speckle at a distance**, through a nearer clipping plane.
- Faster on-screen test for chunks.
- Chunk copies happen on the threads that build chunks, not on the one that draws.
- Chunk builder thread count is configurable.
- Much deeper diagnostics log.

### 0.5.0

- **Fog**, which the Vulkan terrain had been missing entirely — most obvious underwater.
- **Offscreen chunk preloading**, so the world stops filling in only along wherever you look.
  Off by default; it costs frames while it works.
- Chunk uploads share one staging buffer instead of allocating one per chunk, which is what
  used to cap the render distance.
- Growing the geometry buffer no longer re-sends the whole world across the bus.
- The lightmap is uploaded only when it changes.
- Zoom eases in and out instead of snapping, and zooming back out no longer leaves the world
  drawn as the narrow cone it was.

### 0.4.0

- **Presets** — Stable, Balanced, Performance.
- **Every setting states what it costs** on the CPU, the GPU and in video memory separately.
- **Geometry budget**, so a card with memory to spare can buy away the stutter of a growing
  buffer, and a small one can keep the footprint tight.
- Terrain layers no longer stop at 4096 chunks, which used to leave holes in the world at high
  render distances.
- Reset button, configurable frames in flight.

### 0.3.0

- **Windows support** for the shared-memory path.
- **Hold-to-zoom** on **C**, rebindable, with mouse sensitivity scaled to match.
- **Settings screen** with pages, groups and a description panel.
- **Game-side optimisations**: entity and block-entity draw distances, animated textures off,
  background framerate cap.
- Block atlas mip levels, which removes distant shimmer.
- Depth is copied by the hardware instead of written from a shader.
- GPU timings and the full diagnostics log.

### 0.2.0

- Settings entry in Video Settings, config file, render distance up to 64 chunks.

### 0.1.0

- First working Vulkan terrain: the opaque layers drawn in Vulkan and handed back to the game
  through shared GPU memory.

## Planned

In the order they are likely to be worth doing.

- **Ray tracing**, for 0.8.0. The obstacle is named under "Not planned" below and has not gone
  away — it is a question of paying for it deliberately rather than of whether it can be done.
- **Entities and block entities.** The largest part of the frame now that the visibility search
  has been dealt with, and the one change that would improve every effect above at once: the
  glow, the occlusion and the reflections all stop at the edge of what this renderer draws.
- **Smart animated textures** — updating only the animated blocks actually in view, instead of
  the all-or-nothing switch that exists today.
- **Connected glass textures.**
- **Front-to-back drawing** within a terrain layer.
- Testing the Windows path on real hardware.

## Not planned

- **Ray tracing in 0.7.** Moved to 0.8.0 rather than dropped. Not for want of hardware — the obstacle is that a chunk's acceleration
  structure has to be rebuilt whenever the chunk is, which is constantly, and chunk rebuilding is
  already the largest cost in a moving frame. The rays would also not see entities, particles or
  the sky, because this renderer does not draw them, so shadows would ignore every mob.
- **Shader packs.** A different project rather than a feature — the vertex format, the passes
  and the whole pipeline change with a pack loaded.
- **Replacing the entire renderer.** Entities, particles, the sky and the interface stay on the
  game's OpenGL renderer. Sharing the frame with it is what makes the fallback possible.
- **Running alongside OptiFine's renderer.** They replace the same classes. With OptiFine
  installed this mod's settings and game-side optimisations still work; the Vulkan terrain does
  not load.
