# Roadmap

What is done, and what is planned next. For the detail behind any line, see `CHANGELOG.md`.

Everything new ships behind a switch, and the defaults never trade looks for frames without
being asked. If the Vulkan path fails for any reason, the game falls back to its own renderer
rather than crashing.

## Done

### 0.10.0-alpha

- **Creatures shade their own faces against the sun.** Only the sky half of the game's light map
  is moved, so a creature beside a torch in a cave comes out exactly as the game drew it.
- **The shimmer of enchanted armour**, which lives entirely in a texture matrix and was therefore
  lost by a renderer that captures geometry.
- **Only the animated textures something on screen is using are uploaded**, instead of every one
  in the atlas every tick. Experimental, and it needs the chunks in view rebuilt before it saves
  anything.
- **A fog distance of your own.** Vanilla ties the haze to the render distance, so more chunks
  arrive wrapped in more of it and look no further away than before.
- **The class patches are split into eight groups that can be switched off**, and a group whose
  patch fails is quarantined for the next launch. A failed patch used to poison the class it was
  aimed at and name neither itself nor the mod it came from.
- **The mod says what it is unable to do**: when a newer build exists, when a setting is switched
  on and something else is holding it still, when creature light cannot act, where the frame
  actually went, and which of its settings give a resource back rather than take one.
- Fixed: the field of dots in the middle of the sun's reflection on water; a wet floor brighter
  than a dry one; two numbers in the water measured along the view ray or in the wrong space
  entirely; three sun-driven shadows arriving at three different sunrises and then multiplying; a
  shadow drawn over the finished frame darkening a torchlit cave; the preset named for looks
  switching on one unfinished effect and one that could not act at all; creatures drawn
  see-through, missing their hurt flash, and black on the far side in daylight; a crash in a pack
  from a patch that insisted on finding something another mod had already done; and twenty-three
  comments describing what the code no longer does, three of them real defects.

### 0.9.0

- **The whole picture is graded**, not only the blocks. It runs on the marker the game raises once
  the world is finished in full, so it reaches terrain, creatures, particles, weather and water
  together and stops before the hand and the interface.
- **Effects on the surfaces of the world**, each off by default: ice that gathers the sky, light
  banding on the bed of shallow water, rain that darkens and wets what it lands on, fog that leans
  warm towards the sun, clouds that take the colour of the sky they hang in, and the sun — and at
  night the moon — glinting off water. The glint fades out with distance rather than growing as
  you climb, and is left out of the traced shader, where a term of its shape once cost the
  graphics device.
- **Tall plants, leaves, cobwebs, vines and sugar cane move in the wind.** Each was standing still
  for a reason of its own, and each needed a different answer than the rule that moves the top of
  a plant.
- **A time of day and a weather of your own**, on this screen only. Nothing is sent to a server,
  nothing is written to the world, and a storm the server believes in still charges a creeper.
- **A budget for explosion particles**, and the primed TNT cube recorded once instead of rebuilt
  per charge per frame.
- **Ray tracing and shaders have pages of their own**, a key that steps through the saved settings
  profiles, the frame time graph in any of the four corners, and two sliders for the offscreen
  chunk preloader that used to be constants in the source.
- **Living creatures can be drawn by Vulkan**, in a subpass of their own — which is what was
  missing, rather than anything about the drawing: the pass they used to go in declares its depth
  read-only, because the water shader samples that same image. They hide one another properly
  now, and they are lit the way the game lights them. Experimental, and the switch names what is
  still missing.
- **A creature casts a shadow of its own shape** instead of the same round blur vanilla puts
  under a chicken and a horse alike. The circle only goes when there is really something in the
  structure to replace it.
- **Light comes through a canopy.** A ray reads no textures, so a leaf used to stop a shadow the
  way stone does and a tree threw a solid slab of shade. A quad is asked how much of it is holes
  instead of where they are, and light passes with that probability.
- **Water has a depth**, so a puddle and an ocean stop looking alike — red is absorbed within a
  block or two and blue survives — with foam where the water is thinnest.
- **The whole scene gets its corners darkened**, not only the blocks: the pass reads the depth of
  the finished frame, so a chest, a mob and another mod's machine are all shaded by it.
- **The sky is shaded by where you are actually looking** rather than by the height of the pixel
  on the screen, and warms towards the sun near the horizon.
- Fixed: the renderer refused to start on any modern Java, over a limit that was not real; this
  mod's copy of LWJGL collided with a loader shipping its own; height fog was measured from the
  camera rather than from the sea; the mouse wheel did not scroll these screens on Cleanroom; a
  settings change could invalidate the frame being recorded; the Beautiful preset named eight
  effects out of eighteen and none of what they all stand on.

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

- **A shadow map for what this renderer draws.** Traced shadows are more accurate than the one a
  shader pack builds, and they reach only what a ray can hit. A map reaches everything drawn into
  it and costs the same whatever is in front of the camera. Not started. It waited on the three
  sun-driven systems already here darkening independently, since a fourth laid over three that
  disagree would be built on sand; they share one sunrise and one answer as of this version.
- **Block entities**, which are still vanilla's — chests, signs and every mod's machine. They are
  drawn by code of their own rather than out of model parts, which is the wall this path reaches
  rather than a matter of effort.
- **Taking work off the game's render thread.** Measured rather than assumed: in a flight this
  renderer accounted for half a millisecond of processor time and four tenths of a millisecond on
  the card, out of a worst frame of seven and a half. Optimising this mod's own drawing has very
  little left to give; what the frame is actually waiting on is the game, and that is where the
  next measurement goes.
- **Connected glass textures.**
- **Front-to-back drawing** within a terrain layer.
- **Intel graphics.** Nobody has reported either way. The class of fault that catches an Intel
  driver where AMD and NVIDIA forgive it has been audited for and is not present, which is not
  the same as having run it.

## Not planned

- **Ray-traced reflections and global illumination.** Shadows shipped in 0.8.0; these do not
  follow from them. A chunk's acceleration structure has to be rebuilt whenever the chunk is,
  which is constantly, and chunk rebuilding is already the largest cost in a moving frame — the
  shadows pay that price for one ray, and a reflection asks for many. The rays also do not see
  entities, particles or the sky, because this renderer does not draw them, so a world reflected
  without a single mob in it reads as a fault rather than as an effect. Reflections wait on
  entities; entities are above.
- **Shader packs.** A different project rather than a feature — the vertex format, the passes
  and the whole pipeline change with a pack loaded.
- **Replacing the entire renderer.** Entities, particles, the sky and the interface stay on the
  game's OpenGL renderer. Sharing the frame with it is what makes the fallback possible.
- **Running alongside OptiFine's renderer.** They replace the same classes. With OptiFine
  installed this mod's settings and game-side optimisations still work; the Vulkan terrain does
  not load.
