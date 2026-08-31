# Advanced

Everything the [README](README.md) leaves out: the full list of what this renderer does,
the switches that are not in the menu, and what to do when a pack fights back.

---

## What it draws, in full

- Vulkan device selection and an isolated LWJGL 3 runtime alongside Minecraft's LWJGL 2.
- Device-local chunk geometry in one growable shared buffer, filled by persistently mapped
  staging uploads, plus the block atlas and the light map.
- The `SOLID`, `CUTOUT_MIPPED`, `CUTOUT` and `TRANSLUCENT` terrain layers.
- Particles, rain and snow, riding the translucent pass rather than opening one of their own.
- Living creatures, in a subpass of their own, so they hide one another by depth rather than
  by draw order — shaded with the same two directional lights the game uses, and carrying
  vanilla's red damage flash and the shimmer of enchanted armour.
- Dynamic lights, worked out while shading rather than rebuilt into the world.
- Ray-traced shadows from the sun, from a carried light, from a burning creature and from the
  light-emitting blocks already in the world, with frame averaging to turn one ray a pixel
  into a soft edge. Needs `VK_KHR_acceleration_structure` and `VK_KHR_ray_query`; without
  them the settings do nothing and say so.
- Surface effects: waves and refraction, ice that gathers the sky, caustics on the bed of
  shallow water, rain that darkens and wets upward faces, fog leaning towards the sun,
  vanilla clouds tinted with the sky they hang in, and a sun and moon glint on water.
- Effects over the finished frame — occlusion of the whole scene, contact shadows, cloud
  shadows, god rays and grading — which reach whatever drew into it, this mod's terrain and
  another mod's content alike.
- Sixteen bits a channel on this renderer's own targets, behind a switch, with the driver
  asked first.
- Optional dropping of the vanilla chunk buffers once Vulkan holds the geometry, so the
  world is stored once in video memory instead of twice.

Tile entities, the sky and the interface stay with vanilla OpenGL.

---

## Settings that are not effects

- **Entity and block-entity draw distance.** Vanilla decides per object from its size, which
  for large mobs reaches far past anything you can make out.
- **Animated textures.** Water, lava, fire, portals and every animated modded block upload a
  new frame every tick whether they are on screen or not.
- **Background framerate cap**, for a minimised game that would otherwise keep the card at
  full load drawing frames nobody can see.
- **Render distance to 64 chunks**, or 128 with Extreme Render Distance on. Both are
  experimental: vanilla allocates a render chunk for every cell of a `(2d+1) x (2d+1) x 16`
  grid as soon as a world loads — 266 256 at 64 and 1 056 784 at 128 — so processor and
  memory are spent up front whether or not there is terrain to put in them, and a server can
  cap the view distance regardless.
- **Offscreen chunk preloading**, so the world stops filling in only along wherever you
  happen to be looking.
- **Geometry Budget**, which steers how generously the geometry buffer grows. Each growth
  stops the GPU and re-uploads every chunk, so a larger budget buys those stutters away on a
  card with memory to spare. Chunks are never dropped to stay inside it — it is not a cap.
- **Hold-to-zoom** on **C**, rebindable, with mouse sensitivity scaled to match.

---

## JVM properties

For the cases the menu cannot cover — a machine that will not reach the menu, or a bug that
has to be told apart from another one.

| Property | What it does |
|---|---|
| `-Dvulkanmodnext.terrain=false` | Turn the Vulkan terrain off completely. |
| `-Dvulkanmodnext.validation=true` | Ask for the Vulkan validation layers, when installed. |
| `-Dvulkanmodnext.debugLoader=true` | Print LWJGL loader diagnostics. |
| `-Dvulkanmodnext.cull=false` | Stop culling back faces, for looking at geometry. |
| `-Dvulkanmodnext.overlay=true` | Show the legacy Vulkan demo overlay. |
| `-Dvulkanmodnext.ultraLog=true` | Write the full report to `logs/vulkanmodnext-diagnostics.log`. Also in the menu. |
| `-Dvulkanmodnext.extraRendererMarkers=name` | Treat another jar as a renderer replacement and stand aside for it. |
| `-Dvulkanmodnext.depthBlit=false` | Composite depth through a shader instead of `glBlitFramebuffer`. |
| `-Dvulkanmodnext.allowIncompatibleRenderer=true` | Run beside OptiFine or a shader mod anyway. Unsupported. |
| `-Dvulkanmodnext.geometryBudget=MiB` | Geometry budget; 0 derives it from the GPU. Also in the menu. |
| `-Dvulkanmodnext.framesInFlight=1..3` | How far the processor may run ahead of the card. Also in the menu. |
| `-Dvulkanmodnext.rayTracing=true` | Build acceleration structures without opening the menu. |
| `-Dvulkanmodnext.noAtlasAnimations=true` | Stop uploading animated block textures, to tell that path apart from another. |
| `-Dvulkanmodnext.slowChunkMs=N` | How long a chunk build must take before it is named in the log. 100 by default. |
| `-Dvulkanmodnext.rayTracingFoliage=false` | Keep leaves out of the acceleration structures, halving what they hold. |
| `-Dvulkanmodnext.javaCeiling=NN` | The newest Java the bundled LWJGL may run on. What actually decides is the JNI version the JVM reports, which is in the log. |

A renderer replacement this build has not heard of can also be named without waiting for a
release, in `config/vulkanmodnext-standaside.txt`: one fragment of a jar's file name a line.

---

## When a pack fights back

**MixinBooter has to load before any mod carrying its own copy of Mixin.** Older large packs
carry Mixin 0.7.11 inside a library mod — malisiscore and Phosphor are the two that turn up
most. Forge adds coremod jars to the classpath in file-name order, so whichever sorts first
owns the `org.spongepowered.asm` package for everyone, and MixinBooter's newer copy then dies
on the older one with `NoSuchMethodError: org.spongepowered.asm.util.VersionNumber.getMajor()S`.
Renaming its jar so it sorts first — `aaa_mixinbooter-*.jar` — settles it. This is true of any
pack that age, with or without this mod.

Not every configuration written for 0.7.11 survives the upgrade afterwards. Phosphor's
`MixinChunk$Vanilla` fails its injection check and stops the game; an individual
configuration can be switched off through `blacklistedConfigs` in `config/mixinbooter.cfg`.

**A missing MixinBooter is never reported as a missing dependency.** Forge reads this mod's
declared dependencies only after the coremod class has loaded, and that class cannot load
without MixinBooter — so what you get is
`ClassNotFoundException: zone.rong.mixinbooter.IEarlyMixinLoader`. That message means
MixinBooter and nothing else.

**A patch that will not apply no longer takes the game down with it.** The class patches are
split into eight groups; a group whose patch fails is quarantined and the next launch starts
without it, with two lines in the log saying what and which. The groups can also be switched
off by hand under **Settings → Advanced → Diagnostics → Class Patches**, or by deleting
`config/vulkanmodnext-patches.cfg`.

---

## Building

Java 8 for the Minecraft client, Java 25 or newer for Gradle. Set a Gradle JDK in your IDE or
through `JAVA_HOME`; machine-specific paths do not belong in `gradle.properties`.

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
```

`python3 tools/check-gl-shaders.py` compiles the OpenGL shaders that are built from Java
strings at runtime — the glow, the occlusion, the light shafts, the composite. The Vulkan ones
are files and `./gradlew compileShaders` refuses to build when one is wrong; these are handed to
the driver while the game runs, and a typo in one switches that effect off for the session with
a line in the log. Needs `glslangValidator` on the path.

`./gradlew runClient -PnoDepthBlit` reproduces the path taken on a card that cannot hand its
depth back in the format the game keeps — which is half the installed base, and the half
easiest to break without noticing.

---

## Diagnostics

The F3 overlay reports GPU selection, mirror statistics, the active terrain mode and the
chunk count. Periodic log entries report fence wait, command recording, submit and composite,
and the card's own time.

For anything more, turn on **Ultra Logging** and attach `logs/vulkanmodnext-diagnostics.log`.
It records versions, installed mods, the GL driver, every active renderer path, where the
frame's time went and what every resource is using. Start with `validation=true` when
chasing a driver or synchronisation problem.
