<h1 align="center">
  <img src="src/main/resources/assets/vulkanmod112/logo.png" width="96" height="96" alt="">
  <br>
  VulkanMod112
</h1>

<p align="center">
  <b>Minecraft 1.12.2 draws its world with Vulkan instead of OpenGL.</b><br>
  More frames at high render distances, a menu of effects you assemble yourself,<br>
  and the performance settings this version never had.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/vulkanmod-legacy"><img src="https://cf.way2muchnoise.eu/full_1624664_downloads.svg?badge_style=flat" alt="CurseForge"></a>
  <a href="https://github.com/VLOD-ZDOV/VulkanMod-1.12.2/releases"><img src="https://img.shields.io/github/downloads/VLOD-ZDOV/VulkanMod-1.12.2/total?style=flat&logo=github&label=GitHub" alt="GitHub downloads"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.12.2-brightgreen?style=flat" alt="Minecraft 1.12.2">
  <img src="https://img.shields.io/badge/licence-LGPL--3.0-blue?style=flat" alt="LGPL-3.0">
</p>

<!-- Add once the Modrinth project exists, with the real slug in place of vulkanmod-legacy:
  <a href="https://modrinth.com/mod/vulkanmod-legacy"><img src="https://img.shields.io/modrinth/dt/vulkanmod-legacy?style=flat&logo=modrinth&label=Modrinth" alt="Modrinth"></a>
-->

---

## Get it

| | |
|---|---|
| **[CurseForge](https://www.curseforge.com/minecraft/mc-mods/vulkanmod-legacy)** | the published build |
| **[Releases](https://github.com/VLOD-ZDOV/VulkanMod-1.12.2/releases)** | the same jars, and the alphas that go out ahead of them |

**You also need [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) 10.7
or newer in your mods folder.** No launcher installs it for you, and without it the game
stops during coremod discovery on `ClassNotFoundException: zone.rong.mixinbooter.IEarlyMixinLoader`.

---

## What it is

The game still owns the window and the OpenGL context. This mod mirrors vanilla's chunk
geometry into Vulkan, draws the world's blocks and its creatures there, and hands the colour
and the depth back into the game's own frame through shared GPU memory — so both halves line
up, and everything this mod does not draw is drawn exactly as it always was.

If Vulkan is missing, the driver is old, or the two graphics cards in a laptop disagree, the
game renders the way it always did and the log says why.

---

## What you get

**More frames**, most of all at high render distances — the biggest single saving is in how
the game decides which chunks are on screen, and that cost grows with the distance.
→ [what shipped when](ROADMAP.md#done)

**Effects with a slider each.** Waves, reflections, refraction, bloom, occlusion, swaying
grass, god rays, ray-traced shadows, a round sun and moon. Every one off by default, and
every one a row in a menu rather than a zip to load — turning one on costs a frame, not a
recompile. → [the full list](ADVANCED.md#what-it-draws-in-full)

**Dynamic lights.** A torch in your hand lights the world. So does one you dropped, and a
mob that is on fire. No chunk is rebuilt for it.

**The settings 1.12.2 never gave you.** Entity draw distance, animated-texture control, a
background framerate cap, render distance to 64 chunks, hold-to-zoom.
→ [all of them](ADVANCED.md#settings-that-are-not-effects)

**It steps aside rather than fighting.** Another renderer in the folder, a driver without an
extension, a model built some way this mod does not understand — each one is a fallback, not
a crash. → [when a pack fights back](ADVANCED.md#when-a-pack-fights-back)

Open the menu at **Options → Video Settings → VulkanMod112 Settings…**, or press **F6**.
Every row states what it costs on your processor, your graphics card and in video memory
separately, because which of the three you are short of decides whether a setting helps you
at all. Five presets do the choosing if you would rather not: **Stable**, **Beautiful**,
**Balanced**, **Performance**, **Potato**.

---

## Where it is known to run

|              | Windows | Linux | macOS |
|--------------|:-------:|:-----:|:-----:|
| **NVIDIA**   | ✅ run  | ✅ run | ❌ |
| **AMD**      | ✅ run  | ✅ run | ❌ |
| **Intel**    | ⚠️ untried | ⚠️ untried | ❌ |

✅ run — exercised deliberately, not assumed. AMD and NVIDIA are **not the same path**: where
a card cannot hand its depth back in the format the game keeps, this mod hands it over through
a shader instead. That second path is easy to get wrong and hard to notice, so it is tested on
purpose rather than hoped for.

⚠️ untried — no reason it should not work, nobody has reported either way. If you are on one,
a bug report with the diagnostics file is genuinely useful.

❌ — macOS has no Vulkan driver of its own and is out of scope.

---

## Other optimisation mods

| | | |
|---|:---:|---|
| **Phosphor**, **Alfheim** | 🟢 | Lighting engines. They work on the world, this works on the picture. Keep them. |
| **FoamFix**, **LoliASM / CensoredASM** | 🟢 | Memory. Nothing in common with this. |
| **VintageFix** | 🟢 | Resource and model loading, before a frame is ever drawn. |
| **BetterFps**, **Clumps**, **FastFurnace**, **AI Improvements**, **RandomPatches** | 🟢 | Server-side and tick-side work. Untouched by any of this. |
| **Particle Culling**, **Chunk Pregenerator**, **RoughlyEnoughIDs** | 🟢 | Run alongside; all three are in the pack below. |
| **OptiFine** | 🟡 | Replaces the same part of the game. The Vulkan renderer does not load; its settings and speed options stay. |
| **Shaders Mod** (the old GLSL one) | 🟡 | Same as OptiFine, same outcome. |
| **Celeritas**, **Actinium** | 🟡 | Sodium ports — a second terrain renderer. This one steps aside. |
| **Nothirium** | 🟡 | Rewrites the chunk rendering engine. This one steps aside. |
| **Vintagium**, **Relictium**, **Neonium** | 🟡 | A Sodium port and two forks of it. Same story, same outcome. |
| **Vulcanizator** | 🟡 | A second Vulkan renderer, and it takes over presentation as well. This one steps aside. |
| anything else | 🔴 | **None known.** No mod has yet been found that cannot be in the folder at all. |
| **All the Mods 3 Remix** — around **340 mods** | 🟢 | Loads, draws its world through this renderer, on the Beautiful preset with every effect on. Confirmed, not assumed. |

🟢 runs alongside, nothing to do · 🟡 the Vulkan renderer stands aside and the rest of the mod
stays · 🔴 cannot be installed together

Standing aside is automatic and silent. You keep the settings screen, the draw distances, the
background cap, the zoom and every speed option — you just do not get the Vulkan terrain,
because two renderers cannot own the world between them. One this build has not heard of can
be named by hand in `config/vulkanmod112-standaside.txt`.

---

## Requirements

- Minecraft **1.12.2** with Forge 14.23.5.2857 or compatible, or **Cleanroom** — both tested
- **MixinBooter 10.7 or newer**, installed by hand
- A 64-bit **Vulkan driver** on Windows or Linux
- For the terrain path, matching external-memory and semaphore extensions on both sides:
  `GL_EXT_memory_object_fd` / `GL_EXT_semaphore_fd` with `VK_KHR_external_memory_fd` /
  `VK_KHR_external_semaphore_fd` on Linux, and the `_win32` variants of the same four on
  Windows. The right pair is chosen for the host automatically; without them the mod loads
  safely and leaves terrain to OpenGL.
- One graphics card doing both halves. On a machine with two, the mod compares device UUIDs
  at startup and stays on vanilla rendering if they differ, naming both in the log.

---

## Honest limits

This is not a drop-in OptiFine replacement, and it is not finished.

- **Not every effect reaches everything.** The ones that run over the finished picture —
  occlusion, contact and cloud shadows, god rays, grading — reach creatures, particles and
  other mods' content for free. The rest are worked out while the blocks are drawn and stop
  there: a burning creeper does not glow, because what glows is recorded per block while a
  chunk is built and a creeper is not a block.
- **A mod that builds its creatures with its own drawing code** rather than out of the game's
  model parts is left to the game, as are chests, signs and machines.
- **Screen reflections reflect what is on the screen** — nothing off the edge of the frame,
  nothing hidden behind something nearer. Treat this one as unfinished.
- **Chunk building dominates the frame while you move** at high render distances. Standing
  still is much faster than turning, and that is the game's own work rather than this mod's.
- **No connected textures, and no shader pack support.**

---

## Something looks wrong

**Send one file: `logs/latest.log`.** Play for about a minute with the world visible first;
that is the whole of the preparation.

A picture that comes out wrong has a handful of causes that look identical on screen — the
world never arrived, a copy the driver refused, a pass that stood down, an effect that graded
it away. So the renderer reads back a pixel of its own frame at three points and writes what
it found, in plain numbers, into that file, unasked and whatever the settings are. Those
numbers separate all four before anyone has to ask you anything.

Going further is optional: turn on **Ultra Logging** and send
`logs/vulkanmod112-diagnostics.log` as well. → [what else is in there](ADVANCED.md#diagnostics)

---

## More

| | |
|---|---|
| [ROADMAP.md](ROADMAP.md) | what is [done](ROADMAP.md#done), [planned](ROADMAP.md#planned) and [not planned](ROADMAP.md#not-planned) |
| [CHANGELOG.md](CHANGELOG.md) | every release, in detail |
| [ADVANCED.md](ADVANCED.md) | the full feature list, the JVM switches, building, diagnostics |
| [Issues](https://github.com/VLOD-ZDOV/VulkanMod-1.12.2/issues) | bugs and requests |

---

## Licence

GNU LGPL v3. See [LICENSE](LICENSE).
