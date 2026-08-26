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
of day, the weather, the settings preset, and the length of the flight. Verified rather than
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
| 8 chunks | **970** | 722 | 950 | 931 |
| 24 chunks | 303 | **596** | 751 | 795 |
| 32 chunks | 150 | **380** | 610 | 700 |

Relative to the game's own renderer:

| Render distance | **This mod** | Nothirium | Relictium |
|---|---|---|---|
| 8 chunks | 0.74x | 0.98x | 0.96x |
| 24 chunks | **1.97x** | 2.48x | 2.62x |
| 32 chunks | **2.53x** | 4.07x | 4.67x |

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

**The two specialists are faster than this mod at every distance where any of them help.**
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
