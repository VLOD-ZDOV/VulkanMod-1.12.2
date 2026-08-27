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
wait for it to settle, fly a fixed route at a fixed speed, record the frame rate at fixed
points along it, and quit. Nobody sits and flies it, so two runs differ by what is being
measured rather than by how the mouse was moved.

Pinned for every run in the table: the world seed, the route, the render distance, the time
of day, the weather, the settings preset, the length of the flight, and — since it turned out
to matter more than any of the others — the size of the window. Verified rather than
assumed:

- **The render distance really changes.** At 8 chunks the frame holds 180–218 chunk sections;
  at 32 it holds 2581–2689. A preset that caps the distance is overwritten, because the route
  sets it afterwards and reloads the renderer.
- **The world is warm.** The route keeps its world between runs instead of generating it
  again, so the terrain is already on disk. The first run of a seed is generating rather than
  drawing, and its numbers are thrown away — before this was understood, two runs of an
  identical build differed by forty per cent. The harness now says in the log which kind of
  run it is.
- **The picture is the same.** Frames taken at the same point on the route are compared pixel
  by pixel. None of the renderers here draws less of the world than the others: the same far
  shore, the same horizon, the same fog. Where two frames differ it is by a few per cent, and
  that few per cent is animals having wandered.

---

## The table

Frames per second along the route, at three render distances. Higher is better.

| Render distance | Vanilla | **This mod** | Nothirium | Relictium |
|---|---|---|---|---|
| 8 chunks | **970** | 715 | 950 | 931 |
| 24 chunks | 303 | **595** | 751 | 795 |
| 32 chunks | 150 | **512** | 610 | 700 |

Relative to the game's own renderer:

| Render distance | **This mod** | Nothirium | Relictium |
|---|---|---|---|
| 8 chunks | 0.74x | 0.98x | 0.96x |
| 24 chunks | **1.96x** | 2.48x | 2.62x |
| 32 chunks | **3.41x** | 4.07x | 4.67x |

This mod's column was measured again after the change described in *The pass that
was not about creatures* below; the other three are unchanged. The two distances
that change did not move — 715 against 722, 595 against 596 — which is what says
the two sessions are comparable at all.

---

## Reading it honestly

**Below about eighteen chunks this mod is slower than the game.** It has a constant cost per
frame that the game does not — a second API, a composite, a depth hand-off — and at a short
render distance there is not enough chunk work for the saving to pay for it. At 8 chunks
nothing in the table beats the game's own renderer, because at 8 chunks nothing in the frame
is waiting on chunks.

**Above it, the picture reverses**, and the further out you go the more it reverses: about
twice the game's frame rate at 24 chunks and two and a half times at 32. That is the case this
mod exists for.

**The two specialists are still faster than this mod, and the gap narrows with distance.**
Nothirium rewrites the chunk rendering engine; Relictium is a fork of Vintagium, which is a
port of Sodium. They do one thing and they do it well, and neither of them draws water with
waves or a sun that glints off it. If frames are the only thing you want, they are the honest
recommendation, and it costs nothing to say so.

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

**How it was checked.** A shortcut that quietly drops a creature looks exactly like a creature
that walked off, so the shortened lists are compared against the full scan they replace, entry
by entry, under a switch. It found a real fault the first time it ran — 186 block-entity
sections missing while a world filled in, because a section already on the list can finish
building afterwards — and reports nothing missing at all now, on either list. The frames taken
at the same points on the route differ from each other exactly as much as two runs of the
identical build do, which is animals having wandered.

---

## What the frame is actually limited by

Everything above is one window on one machine, and it turns out the window is
half the answer.

The frame was broken into the phases the game names for its own profiler, and
then into what the loop around them does. At thirty-two chunks, drawing the
whole world costs **0.83 ms of a 2.0 ms frame**. Most of the rest is a single
thing: the game asks the driver twice a frame whether anything has gone wrong,
and the answer cannot come back until the card has caught up with the work
already handed to it. That wait is not the question's fault — skipping the
question does not gain a frame, it only moves the wait to the next call that
needs the driver — but it is a clean measure of how far behind the card is.

It tracks the pixel count and almost nothing else:

| Window | Megapixels | **This mod** | Waiting for the card | The game's renderer |
|---|---|---|---|---|
| 1280 × 720 | 0.9 | **1041** | 0.33 ms | 155 |
| 1920 × 1080 | 2.1 | **904** | 0.45 ms | — |
| 3673 × 2066 | 7.6 | **512** | 1.05 ms | 149 |

Same route, same world, same render distance of 32; only the window changed.

**This renderer's frame is about 0.7 ms of processor work plus a tenth of a
millisecond for every megapixel.** The game's own renderer, measured the same
way, barely notices the window at all — 155 frames a second at 0.9 megapixels
and 149 at 7.6 — because what holds it back is the number of separate draws it
makes, not the number of pixels it fills.

That is the whole trade this mod makes, stated as a measurement rather than as a
design note: **it turns a cost per chunk into a cost per pixel.** Which is the
better bargain depends on your screen, and the table further up was measured on
a very large one. On an ordinary 1080p monitor the same route at the same
distance runs at six times the game's own renderer rather than three and a half.

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
Pack Chunk Vertices). It moves the card's terrain time to 0.42 ms and the route
to 547 frames a second — five per cent, less than the byte count alone would
suggest, because part of the pass is triangle setup rather than reading. What it
does do in full is memory: **428 MiB of video memory for this mod's copy of the
world becomes 240**, and how much of the world fits is what decides whether a
long render distance is possible at all.

---

## What is not in the table

- **OptiFine** does not start in a development client at all, so it could not be measured this
  way. It needs a real instance, and that is worth doing separately.
- **Celeritas** is only distributed inside Actinium, and that build stops on a library it
  expects to find bundled with it.
- **Vintagium** and **Neonium** are on CurseForge only, which has no open interface to fetch a
  file from. Relictium is a fork of Vintagium and stands in for that branch.

---

## Caveats

One world, one route, one machine, one build of each. The route is over open terrain with
water and hills, which is a fair average and not a worst case; a dense modpack is a different
question that this does not answer. Frame rates this high are decided by the processor and the
driver, so the ratios travel better than the absolute numbers do. Everything here can be
re-run: the harness is in the repository and so is the comparison tool.
