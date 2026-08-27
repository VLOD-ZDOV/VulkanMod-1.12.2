# Benchmarks

Numbers for this mod against the game's own renderer and against the other 1.12.2 renderer
replacements, measured rather than estimated, with the method written down so that anybody
can disagree with the result by running it.

Every number here is a frame rate from the same fixed route through the same fixed world.
**The effects this mod adds are switched off for all of it.** They are optional, they are off
by default, and leaving them on would be measuring the effects rather than the renderer.

---

## Method

The mod can fly itself. `-Pflight=<route>` makes the game create a world from a fixed seed,
wait for it to settle, fly a fixed route at a fixed speed, and quit. Nobody sits and flies it,
so two runs differ by what is being measured rather than by how the mouse was moved.

**The number is the game's own frame counter**, the one in the corner of the debug screen,
read once a second for the twenty seconds of the route, and reported as the median. It has to
be the game's: this mod's own diagnostics are better and cannot compare renderers at all,
because when another renderer is in the folder this mod stands aside and takes its diagnostics
with it. A counter that belongs to the game is there whoever is drawing, and that is the only
thing that makes one row of the table below mean the same as the row above it.

Pinned for every run: the world seed, the route, the render distance, the time of day, the
weather, the settings preset, the length of the flight, and **the size of the window** — which
turned out to matter more than any of the others, and is why the table has four columns
instead of one number.

Verified rather than assumed:

- **No frame cap of any kind.** Vertical sync is off, and the game's frame limit is at the
  setting it calls "unlimited". The rows that look flat are flat because of what limits them,
  not because something is holding them there — and the giveaway is the spread: a capped run
  sits on one number, and the flattest row here still wanders by eight per cent from second to
  second.
- **The world is warm.** The route keeps its world between runs, so the terrain is already on
  disk. The first run of a seed is generating rather than drawing, and before this was
  understood two runs of an identical build differed by forty per cent.
- **The medians are stable.** Every row was measured twice at 1920 × 1080, on separate runs.
  The two agree within four per cent, which is the noise floor of this measurement.

---

## The table

Frames per second along the same route, at render distance 32, at four window sizes. Higher is
better.

| | 1280 × 720 | 1920 × 1080 | 2560 × 1440 | 3840 × 2160 |
|---|---|---|---|---|
| The game's own renderer | 163 | 165 | 163 | 152 |
| **This mod** | **953** | 855 | 734 | 532 |
| **This mod, everything on** | **1128** | **972** | 797 | 564 |
| Nothirium | 865 | 855 | **822** | 565 |
| Relictium | 659 | 656 | 659 | **646** |

"Everything on" means the two settings this build ships with off — packed chunk vertices and
the short layer filter list. They are off because they are new, not because they cost anything.

Against the game's own renderer:

| | 1280 × 720 | 1920 × 1080 | 2560 × 1440 | 3840 × 2160 |
|---|---|---|---|---|
| **This mod** | **5.8x** | **5.2x** | **4.5x** | **3.5x** |
| **This mod, everything on** | **6.9x** | **5.9x** | **4.9x** | **3.7x** |
| Nothirium | 5.3x | 5.2x | 5.0x | 3.7x |
| Relictium | 4.0x | 4.0x | 4.0x | 4.3x |

---

## Reading it honestly

**There is no fastest renderer here. There is a fastest renderer for your screen.**

That is the whole finding, and it is not what a single-column table can say. Each of these four
is held back by something different, and what holds it back decides how it answers a bigger
window:

- **The game's own renderer is bound by how many separate draws it makes.** Eight times the
  pixels costs it seven per cent. It is doing thousands of draw calls and the card is idle
  between them; the screen size barely reaches it.
- **Relictium is bound by the processor**, at four times the game's rate and just as flat.
  Eight times the pixels costs it two per cent. It is a port of Sodium, and Sodium is very
  careful about what it asks the card to do.
- **Nothirium is flat until 1440 and then is not.** It holds 865 to 822 across the first three
  columns and falls to 565 at 4K, which is where it starts paying for pixels like this mod does.
- **This mod is bound by pixels from the start.** Eight times the pixels costs it 1.8 times the
  frame rate. It composites a full screen of colour and hands a full screen of depth across
  twice a frame, and none of the other three do any of that.

So: **at 1280 × 720 this mod is the fastest thing in the table, by a fifth. At 3840 × 2160 it
is the slowest of the three replacements.** Both of those are the same fact seen from two ends,
and neither is worth stating without the other.

**Below about eighteen chunks of render distance the game's own renderer is still faster than
this one**, whatever the window. This mod trades a cost per chunk for a cost per pixel, and at
a short distance there are not enough chunks for the trade to pay.

**If frames are the only thing you want**, the honest recommendation depends on the screen:
Relictium above 1440, this mod below it, and Nothirium is a good answer anywhere. Neither of
the other two draws water with waves or a sun that glints off it, and it costs nothing to say
so.

---

## What changed while this was being measured

Measuring produced a fix rather than only a table.

Standing still at 32 chunks this mod was within fifteen per cent of the fastest thing in the
table. Flying the same route it dropped to half. The gap was not in drawing: this renderer's
own work is 0.3 ms of a 4 ms frame, and the card is idle. It was in deciding *which chunks are
on screen* — a walk over the visible set that is redone whenever the answer might have changed.

The measurement, on one interval of one flight: the walk was armed **1 201 010 times**, every
one of them by a chunk finishing its build rather than by the view moving, and it ran on
**1762 frames out of 1762** at about 1.4 ms each. A third of the render thread, spent rebuilding
an answer that had changed by one chunk.

The view is now answered at once and a finished chunk at a tick's pace. Same route, same world:

| At 32 chunks, flying | Before | After |
|---|---|---|
| Deciding what is on screen | 1.74–1.88 ms/frame | 0.31–0.46 ms/frame |
| Frame rate, second half of route | 216–273 | 343–441 |

The frames taken at the same points before and after are the same picture. Standing still is
unchanged, which is the point: the cost was never there.

---

## The pass that was not about creatures

The second thing measuring produced, and the larger one.

`renderEntities` sounds like a loop over creatures. It is a loop over **every section on
screen**: for each one it asks the world which chunk that section belongs to, and only then
whether anything is standing in it. At thirty-two chunks that is some 17 700 sections, of
which about 2 700 contain any blocks at all — the rest is open air, walked twice a frame.

The proof is a render-distance sweep with the scene held still. Five creatures drawn at every
distance, nothing else changed:

| Render distance | `renderEntities` | of it, block entities | creatures drawn |
|---|---|---|---|
| 8 chunks | 0.07 ms | 0.01 | 5 |
| 16 chunks | 0.38 ms | 0.08 | 5 |
| 32 chunks | **1.10 ms** | **0.36** | 5 |

The creature count never moves and the cost grows sixteenfold. The block-entity column is the
cleanest part of it: 0.01 ms to 0.36 with **zero block entities drawn anywhere**. Nothing is
being rendered there. That is the walk, and only the walk.

So the loop is turned inside out. There are sixty entities in that world and seventeen
thousand sections; each entity already records the section it is filed under, and this mod's
visibility search already knows in one array read whether a section is on screen. The sections
that can hold something are handed to the game, in the order it would have visited them, and
the game finds exactly the same creatures in them. The block-entity list is gathered while the
search walks, and topped up when a chunk finishes building with a chest in it.

Same route, same world, one session, the change switched on and off:

| At 32 chunks, flying | Before | After |
|---|---|---|
| `renderEntities` | 1.12 ms/frame | **0.01 ms/frame** |
| of it, block entities | 0.37 ms | 0.00 ms |
| Frame rate along the route | 305–354 | **512–513** |
| Worst frame on the route | 225 | **353** |

At 24 chunks it gains one per cent and at 8 chunks nothing at all, which is the same statement
from the other side: below thirty-two the frame is not waiting on the thread this saves.

**Neither of the other two replacements pays this cost either**, and it is worth saying so
plainly: this is not something they had missed. Both of them replace the game's own terrain
setup wholesale, so the list this loop walks is simply never filled — Relictium then draws
creatures from its own visible set in the same place, by the same reasoning. The measurement
above is what this mod had to spend to arrive where they already were, not an advantage over
them.

**How it was checked.** A shortcut that quietly drops a creature looks exactly like a creature
that walked off, so the shortened lists are compared against the full scan they replace, entry
by entry, under a switch. It found a real fault the first time it ran — 186 block-entity
sections missing while a world filled in, because a section already on the list can finish
building afterwards — and reports nothing missing at all now, on either list. The frames taken
at the same points on the route differ from each other exactly as much as two runs of the
identical build do, which is animals having wandered.

---

## Why the shape of the table is what it is

The rows above differ because the frame is broken in different places, and the
frame can be taken apart to show where.

It was split into the phases the game names for its own profiler, and then into
what the loop around them does. At thirty-two chunks, drawing the whole world
costs **0.83 ms of a 2.0 ms frame**. Most of the rest is a single thing: the game
asks the driver twice a frame whether anything has gone wrong, and the answer
cannot come back until the card has caught up with the work already handed to
it. That wait is not the question's fault — skipping the question does not gain
a frame, it only moves the wait to the next call that needs the driver — but it
is a clean measure of how far behind the card is, and it tracks the pixel count
and almost nothing else: **0.33 ms at 0.9 megapixels, 0.45 at 2.1, 1.05 at 7.6.**

**So this renderer's frame is about 0.7 ms of processor work plus a tenth of a
millisecond for every megapixel** — which is exactly the curve the second row of
the table draws.

Taken apart further, at 7.6 megapixels the card spends 1.88 ms on the frame:
0.005 on clearing it, 0.46 on this renderer's terrain, 0.13 on handing the
colour and the depth across, 0.034 on copying the finished frame to the window,
and **0.69 on presenting it**. That last one is nobody's code and everybody pays
it — but only a renderer that has already finished its own work waits on it, and
that is why the two flat rows stay flat and this one does not.

---

## What the terrain pass itself is waiting for

The window sweep above answers half the question. The other half is what the
Vulkan pass costs, and it turns out not to be pixels at all:

| Render distance | vertices | the card's time |
|---|---|---|
| 8 chunks | 0.50 M | 0.05 ms |
| 16 chunks | 1.93 M | 0.15 ms |
| 24 chunks | 5.05 M | 0.30 ms |
| 32 chunks | 9.02 M | 0.46 ms |

About 0.048 ms per million vertices, and flat across the window: 0.44 ms at 0.9
megapixels against 0.50 at 7.6. Nine million vertices at the twenty-eight bytes
this mod mirrors from the game is **252 MB of vertex reading a frame**, which at
that time is roughly the card's whole memory bandwidth. The pass is not filling
pixels and not running out of shader — it is reading vertices.

Packing that vertex into sixteen bytes is a setting (off by default, Advanced →
Pack Chunk Vertices). It moves the card's terrain time to 0.42 ms, and what that
is worth in frames depends on the window in exactly the way everything else here
does — because reading vertices costs the same whatever the screen, so removing
some of it is a bigger share of a shorter frame:

| | 1280 × 720 | 1920 × 1080 | 2560 × 1440 | 3840 × 2160 |
|---|---|---|---|---|
| Packing the vertex is worth | **+18%** | **+14%** | +9% | +6% |

And it does one thing in full, at every window: **428 MiB of video memory for
this mod's copy of the world becomes 240**. How much of the world fits is what
decides whether a long render distance is possible at all.

---

## What is not in the table

- **OptiFine** does not start in a development client at all, so it could not be measured this
  way. It needs a real instance, and that is worth doing separately.
- **Celeritas** is only distributed inside Actinium, and that build stops on a library it
  expects to find bundled with it.
- **Vintagium** and **Neonium** were not measured. Relictium is a fork of Vintagium and stands
  in for that branch.

---

## Caveats

One world, one route, one machine, one build of each, at one render distance. The route is
over open terrain with water and hills, which is a fair average and not a worst case; a dense
modpack is a different question that this does not answer.

The window sizes are windows, not screens: the game was told to render at each size, which is
what decides the cost, and a window larger than the monitor renders every one of its pixels
just the same. Frame rates this high are decided by the processor and the driver as much as by
the card, so the ratios travel better than the absolute numbers do — and the *shapes* of the
rows travel better than either.

The game's own renderer here is this mod with its Vulkan terrain switched off rather than the
game with no mod installed, which is also how it was measured before. Everything here can be
re-run: the harness is in the repository and so is the comparison tool.
