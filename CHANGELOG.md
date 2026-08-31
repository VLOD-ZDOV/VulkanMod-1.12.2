# Changelog

## [Unreleased]

- **The three passes over the finished frame are now timed on the card.** The
  Vulkan half of this renderer has been timed since the translucent pass was
  split out, and the hand-over has had a timer of its own for as long; the
  occlusion with its light shafts, the grading and the glow had none at all.
  Every judgement about them came from the frame rate with them on against the
  frame rate with them off, which on a machine where the card is the ceiling
  answers a different question than the one being asked. They use the eight-slot
  ring the other timers already use — an answer is read when the driver says it
  is there and never waited for — and they are wrapped around each pass rather
  than written inside it, because all three return early in several places and a
  timer left open across a frame boundary would swallow the next frame whole.
  First numbers, at 1920 × 1080 with everything on: occlusion and shafts 0.10 ms,
  glow 0.10 ms, grading 0.03 ms, against 0.05 ms for handing the frame over. The
  instrument was then measured against itself over four interleaved runs and
  costs at most half a per cent, which is the noise floor of a stationary
  measurement.

- **The atlas mirror stopped asking the driver what is bound.** Every animation
  frame the game uploads is checked against the block atlas before it is copied
  to Vulkan, because the method it is caught in uploads everything — entity
  skins, the map item, whatever a mod puts through it. That check asked OpenGL
  which texture was bound, once per sprite per tick, and a question to the
  driver is a wait for everything already queued behind it. The answer is known
  without asking whenever the upload comes from the atlas stepping its own
  animations, which is a place this mod already hooks at both ends. The driver
  question is kept as the fallback rather than removed: a mod that animates a
  sprite of its own outside that method would otherwise freeze in the terrain
  and nowhere else, which is the exact bug this mirror was written to fix.

- **Colour vision correction** — Effects → Colour Vision, off by default, with
  a setting each for protanopia, deuteranopia and tritanopia. Not a filter laid
  over the picture and not a simulation of what somebody else sees: the colour
  is taken into the space the three cone types respond in, the missing cone's
  response is rebuilt from the other two — which is what that eye does — and the
  difference between that and the original, which is the information being
  lost, is pushed into the channels that survive. Measured on the red pixels of
  a frame with the green correction on: red unchanged, green up six, blue up
  fifteen, which is the whole of what it claims to do. Redstone against stone
  and a lit torch against an unlit one are what it is for, and it is aimed at
  people who cannot simply install a resource pack. **No preset touches this
  row, including the reset** — it describes the person rather than the look, and
  a preset that helpfully switched it off would be taking something away and
  calling it a change of mood.

- **Gamma** — Effects → Gamma, fifty being the frame untouched to the bit,
  above it lifting the picture and below it deepening it. The range is
  deliberately narrow: past its ends a picture stops being graded and starts
  being broken, and a control that can break the picture is one somebody will
  reach for to fix something else. It rides in the pass that was already
  grading the frame, so it costs nothing measurable, and that pass now runs for
  it alone — before this it only woke up for the tone slider or a floating
  frame.

- **Three more looks beside Beautiful** — Golden Hour, Cold Front and Soft
  Film. They are the same effects at different settings, which is what a shader
  pack is: none of them adds a pass, and none costs more than the preset it
  came from. Golden Hour leans on the two things only a low sun can show, the
  haze the air picks up looking towards it and the light that comes through a
  leaf rather than off it, with the rain switched off because an evening that
  is golden is not also wet. Cold Front does the opposite — almost no haze and
  almost no shafts, since both are warm and both soften — and turns up
  everything that describes shape instead: occlusion, contact shadows, the
  shadow of a cloud, and the two surfaces that read as cold. Soft Film is
  carried by the glow, with the exposure brought down to make room for it,
  because a glow added on top of a picture already at full brightness only
  flattens it. Beautiful is unchanged and is now the neutral one of the four.
  What every look shares — distances, threads, mipmapping, the twelve-chunk cap
  — is stated in one place rather than four, so the next time one of those is
  wrong there is one place to fix it.

- **The settings search finds a row by what it can be set to.** Reported as
  "the time switch is not in the search": it is, and it is called Time Control
  — but somebody looking for it types the answer rather than the question,
  "fixed" or "frozen", and those words were in neither its name nor its
  description. The same went for "off", which is the whole of what half these
  rows do. Values are now in the index, scored below a name and above a
  description, so a row that is *called* something still beats one that merely
  offers it.

- **Stable and Balanced say what they actually do.** Stable is also the reset
  button — everything this mod owns back to shipped, Minecraft's own settings
  untouched — and neither of those was written down anywhere. Balanced is
  Stable and then it reaches into Minecraft's settings as well, which is the
  whole difference between them.

- **A leaf lets the sun through from behind it** — Effects → Leaf Glow, off by
  default like every other effect here. The game shades a leaf by how much light
  *reaches* it, and light that goes through the leaf and on towards you is not
  in that answer at all — which is why a tree with the sun behind it comes out a
  dark cut-out, and why a canopy reads as a solid block of green rather than as
  something made of leaves. This asks one question, whether the sun is behind
  this leaf from where you are standing, and brightens the leaf in its own
  colour when it is. Not physics, and deliberately so: real subsurface
  scattering asks how far light travels inside a material, while the question
  the eye is actually answering when it calls a crown "lit through" is the
  simple one. No normal is involved either, because a cross-shaped plant has no
  honest normal and this has to work on grass as much as on leaves. Two things
  hold it where it would otherwise be wrong — the sky light the leaf already
  has, so nothing glows under a canopy or in a cave, and how high the sun is, so
  it does not switch on at dawn while the world is still dark. It costs a dot
  product on leaves and plants and nothing anywhere else, needs no rays, and
  works with ray tracing off. Checked the way an effect that could be a wash
  over the whole canopy has to be: looking into a low sun it changes 5.5% of the
  picture, brightening and green; looking the other way, 0.05%; at noon, none at
  all — against a control pair of two runs of the identical build that differ by
  0.02%.

## [0.10.0-alpha.3]

The third alpha, published for the same reason as the two before it: to be
reported against rather than because it is finished. Everything below was flown
on one machine on Linux and nowhere else.

**What is different about this one is that it is mostly switches being turned
on.** Three optimisations shipped in the earlier alphas switched off, because
each of them replaces something the game does rather than something this mod
does, and the way they fail is quiet. They have since been measured, checked
against the picture they replace, and flown; so packed chunk vertices, the short
layer filter list and quad facing groups are all on by default now. A settings
file from an earlier alpha is moved once, and the log says which settings moved
and why. Every one of them can be turned off again where it came from.

The largest single change is the last of those three, and it grew after it was
written: it now leaves unread **36% of the world's vertices** — the undersides
of the floor you are standing on, the tops of the ceiling above you, and the two
sides of every chunk that face away from you. That is worth between five and
thirteen per cent of the frame rate depending on how big the window is.

- **A chunk's faces are sorted by which way they point** — Optimization →
  Group Quad Facings, on by default. Standing above a floor you cannot see
  its underside, and the card knows that as well as you do; what it cannot do is
  know it in time. Back-face culling happens after the vertex shader, so every
  one of those hidden vertices is read from memory and transformed before being
  thrown away, and reading vertices is precisely what this renderer's terrain
  pass is limited by — nine times the pixels cost it a fifth, nine million
  vertices cost it everything. Sorted at copy time by which way each face
  points, the draw simply stops short of the parts a camera is on the wrong side
  of: the undersides of the floor it stands on, the tops of the ceiling above
  it, and the two sides of every section that face away from it. The four
  sideways shelves are kept in a ring rather than in pairs, which is the whole
  trick of the thing — a camera sees one of each opposite pair, and in a ring
  any such choice is two neighbours, so what it cannot see is usually one
  unbroken stretch and never more than two. Measured at render distance 32:
  **36% of the reading never happens**, and the frame rate rises thirteen per
  cent at 720p, twelve at 1080p, eleven at 1440p and five at 4K, where the frame
  is spending its time on pixels rather than vertices. It shipped switched
  off for a reason worth stating: while it was being built it was wrong twice,
  and both times the ordinary picture looked perfect — only the view that paints
  the world by material showed it. Something that can be wrong without looking
  wrong gets a session behind it before it is on for everybody, and it has had
  one. Optimization turns it off again, a settings file from an earlier version
  is moved once with a line in the log saying so, and it takes effect on the
  next start — one geometry buffer cannot hold two orders at once.

- **The short layer filter list is on by default.** Four times a frame the game
  walks every visible section asking whether it has anything in the layer being
  drawn — at render distance 32 that is around 20 000 questions of which some
  2 000 are useful, and the answer is one bit on an object this mod's visibility
  search already has in its hand. This has existed and been switched off since
  it was written, because on the machine it was written on it bought nothing:
  that frame waits for the card, and this is work taken off the processor. Made
  small enough that the processor is the ceiling again, it is worth seven per
  cent. What it could get wrong is a chunk that stops being drawn, so it was not
  turned on by eye: the mod can walk the long list alongside the short one and
  count what is missing, and over 16 500 layer passes on a moving route nothing
  was. Optimization turns it off again, and a settings file from an earlier
  version is moved once, with a line in the log saying so.

- **The list of chunks to draw is kept between frames.** It was built four
  times a frame, once per layer, out of numbers that never depended on where
  the camera was — a mirror slot and a block position. It is now rebuilt only
  when the visible set is walked afresh or a slot is handed out or given back,
  which in flight is one frame in ten. Nothing about the picture changes, and
  that was checked by comparing frames rather than by looking: the difference
  from the previous build is a handful of cows that had walked. What it is worth depends
  entirely on what your frame is waiting for. Where the card is the ceiling —
  which on a fast machine at this render distance it is — the frame rate does
  not move by a single frame. Where the processor is the ceiling, twelve
  interleaved runs put it at five to seven per cent, with the two sets of
  measurements not overlapping at all. So: nothing on a fast machine, something
  on a slow one or under a heavy pack.

- **Rain no longer falls through twenty blocks of rock.** How wet a floor got
  was taken from its sky light, which is not the same question as whether the
  sky can reach it: a cave with a mouth some distance away still carries sky
  light halfway in, and that floor came out half wet. It is now the step
  between the last two sky levels, so one block of overhang is enough to stay
  dry — and so is a tree, which is right.
- **Ice reflects the sky it is actually under.** The sheen was a bare Fresnel
  term, which is zero when you look straight down, so a frozen lake went flat
  the moment you stood on it and held the sky again the moment you stepped off.
  It now keeps a floor, the way the wet-surface sheen beside it already did —
  and it mixes in the sky along the reflected ray rather than one horizon
  colour for every direction, which is what the water beside it has always
  done. Be told plainly what this buys: standing on ice, almost nothing you can
  see. By day the ice texture, the horizon and the zenith are the same pale
  blue, so there was never a colour there to reveal. What is gone is the step
  where the effect switched itself off entirely.
- **The Performance preset no longer asks for a third frame in flight.** It was
  there on the reasoning that a third frame gives the processor room when the
  processor is what holds the frame up. Measured at render distance 32, where
  that is most nearly true, it buys nothing: two settings a percent apart while
  two runs of one build differ by four. The third frame's costs are not in
  doubt — a frame of input latency and a third more of every per-frame buffer —
  so it goes.

- **Chunk vertices are packed to sixteen bytes by default.** The setting shipped
  off in the last alpha because it was new, and the picture has now been checked
  rather than argued about: the same eight views of the same world, packed and
  unpacked, differ by no more than two runs of one build do — and every large
  difference in them is an animal having walked. What is left is a scatter of
  single pixels a fraction of a texture pixel wide, which is exactly what the
  arithmetic predicts. It is worth 7% to 18% of the frame rate depending on how
  many pixels your screen has, and it takes this mod's copy of the world from 428
  MiB of video memory to 240 — which is what decides whether a long render
  distance fits at all. Advanced → Pack Chunk Vertices turns it off again.
  Because a settings file written by an earlier version already answers this
  question, the answer is moved once, and the log says so when it happens.
- **A very large texture atlas turns the packing off by itself.** A packed
  texture coordinate is one part in 65 535 of the whole sheet, so on a sheet
  above 8192 pixels it stops being finer than a texture pixel and sprite edges
  could bleed. The sheet is not loaded at the moment the layout has to be
  decided — so the size is remembered from the session before, and a pack that
  needs the wide layout gets it from its second start onwards without anybody
  reading a log. Previously this was a warning and nothing else.

## [0.10.0-alpha.2]

The second alpha, and like the first one it is published to be reported against
rather than because it is finished. Everything below has been flown on one
machine on Linux with an NVIDIA card and nowhere else. The three new settings
are all off by default except the first, and each says in its own description
what it costs and what it could get wrong.

If you are on the first alpha, this one will tell you about itself: a build on
the way to a version now asks for the whole list of releases rather than the
latest finished one, which is what an alpha needs and a finished version does
not.

### Added

- **A chunk vertex can be packed into 16 bytes instead of the 28 the game uses.** The pass that draws terrain was measured against render distance and against window size, and the two answers together name what it waits for: 0.048 ms per million vertices, and no change at all between a 0.9-megapixel window and a 7.6-megapixel one. Nine million vertices at twenty-eight bytes is 252 MB of vertex reading a frame, which at the time it takes is about 580 GB/s — the card's memory bandwidth. So the vertex is packed: the position keeps a two-thousand-and-forty-eighth of a block, which is 128 times finer than one pixel of a block texture and lands on a lattice shared exactly by neighbouring chunks; the two light values are exact, because the game never writes them above a byte's worth; the colour, which carries the shading in corners, is untouched. At render distance 32 the card's terrain time falls from 0.49 ms a frame to 0.42, the fixed route from 521 frames a second to 547, and the buffer holding this mod's copy of the world from 428 MiB of video memory to 240. The memory is the larger half of that: how much of the world fits is what decides whether a long render distance is possible at all. Off by default under Settings → Advanced → Pack Chunk Vertices, effective on the next start, and it stands aside while ray tracing is on because the acceleration structures read the same buffer. Every value that could fail to fit is counted rather than assumed, and all three counts are zero over the route.

- **The step that picks which chunks contribute to a render layer is given a short list too.** It runs four times a frame, once per layer, and walks every section on screen to do it — around 20 000 at render distance 32, of which 3 600 hold any blocks. Whether a section is empty is one bit on the same object the visibility search already reads for it, so the list is kept while that search walks. The step falls from 0.72 ms a frame to 0.45, and on the machine it was measured on that bought no frames at all, which is said plainly in the setting rather than left for somebody to discover: once the entity passes were shortened, the frame stopped waiting on that thread. It is worth having on a processor slow enough for that thread to be the limit again. Off by default under Settings → Optimisation → Short Layer Filter List, and checked against the full scan it replaces under a build flag — zero disagreements on all four layers.

- **The two passes that draw creatures and chests are given a short list instead of every section on screen.** `renderEntities` reads as a loop over creatures and is not one: it walks the visible list — around 17 700 sections at render distance 32, of which some 2 700 hold any blocks — and asks the world which chunk each section belongs to before finding out whether anything stands in it. Measured with the scene held still at five drawn creatures it costs 0.07 ms a frame at eight chunks and 1.10 at thirty-two, and the block-entity half grows the same way with none drawn at all, which is the proof that the cost is the walk. The creature loop is turned inside out — every entity already records the section it is filed under, and the visibility search answers in one array read whether that section is on screen — and the block-entity list is gathered while that search walks and topped up when a chunk finishes building with a chest in it. The same creatures are drawn, in the same order. At thirty-two chunks the pass falls from 1.12 ms a frame to 0.01 and the fixed route from 305–354 fps to 512, with the worst frame on it going from 225 to 353; at twenty-four chunks it gains one per cent and at eight nothing, because below thirty-two the frame is not waiting on that thread. Switchable under Settings → Optimisation → Short Entity Section Lists, and there is a build flag that checks the short lists against the full scan they replace rather than trusting them.

## [0.10.0-alpha]

An alpha, published to be reported against rather than because it is finished.
It has not been run on Windows or on an AMD card, and the newest of what is
listed below has been looked at on one machine only. Everything here works on
the machine it was built on; that is the whole of what is known. Faults go to
the issue tracker, and the log at `logs/latest.log` is worth more than a
description of what happened.

### Added

- **Creatures shade their own faces against the sun.** A cow in a lit world was lit flat against it, because the game's own lighting of a creature is one number per vertex and knows nothing about which way a face points. The face is taken from the geometry being drawn rather than reconstructed from the depth of the picture, so it is exact and has no outline around it. Only the sky half of the game's light map is moved, never the block half — a creature in a cave beside a torch comes out exactly as the game drew it, whatever the slider says, by construction rather than by tuning. That is also why the effect lives in the pass that draws creatures instead of over the finished frame: by the time a frame is finished the two halves of the light have been multiplied into one number, and a pass over it would dim one side of that torchlit pig according to where the sun is, in a cave, at night.

- **The shimmer of enchanted armour.** The game draws it by rendering the same model twice more with the texture matrix scaled, turned and slid along, which is the whole of the effect — nothing about it reaches the vertices, so a renderer that captures geometry and ignores that matrix captures three copies of one thing and can draw only one. The matrix is mirrored now and the captured coordinates go through it. Skins are handed over before glints, because a glint is depth-tested and writes no depth: it can only appear where the skin it belongs to has already put its own depth there.

- **The class patches are split into eight groups that can be switched off**, under Settings → Advanced → Diagnostics → Class Patches, and a group whose patch fails is quarantined so the next launch starts without it. A failed patch used to poison the class it was aimed at: the loader remembers the failure and everything afterwards sees `NoClassDefFoundError` on a vanilla class, naming neither the patch nor the mod that caused it. Removing mods one at a time cannot isolate that. Deleting `config/vulkanmod112-patches.cfg` turns everything back on.

- **A file for naming a renderer this build has not heard of.** `config/vulkanmod112-standaside.txt`, one fragment of a jar's file name a line: the Vulkan terrain then does not register itself when that jar is present. Until now the only way was a JVM property, and the only way to add a name was a release.

- **The mod says when a newer build exists**, in gold at the top of the settings screen, with a button beside Done that opens it. Both places are asked rather than the first that answers, because a release reaches one before the other, and the button leads to whichever of them actually has that version. Comparison is numeric, which matters more than it sounds: 0.10.0 is newer than 0.9.0 and sorts below it as text.

- **A line naming where the frame went.** The card's translucent pass is timed as well as its opaque one, and the three numbers that answer "what am I actually waiting for" — the worst frame, this renderer's own processor time with the waiting split out of it, and the card's time — now stand together with a verdict over them: the card, this renderer, waiting, or something else. An external counter showing the GPU at a hundred per cent answers a different question, which is whether the queue was ever empty.

- **A fog distance setting.** Vanilla ties the haze to the render distance, so more chunks arrive wrapped in more of it and look no further away than before. It scales the game's own linear fog through `GlStateManager` rather than behind it, so the whole scene moves together; fog that tells you something — blindness, being under water or in lava — is never rescaled.

- **Only the animated textures something on screen is using are uploaded.** The game hands the card a new frame for every animated texture in the atlas every tick — fire, portals, sea lanterns, the animated block of every mod installed — whether or not anything being drawn is wearing one. Nothing in the game knows which is which, so a chunk records what it uses while it is built, and the answer is gathered from the chunks the game decided to draw plus whatever is held in hand. Experimental, off by default, and it needs the chunks in view rebuilt with F3+A before it can save anything, because the record is written while a chunk is built and the ones already standing were built without it.

- **The mod says when a setting is switched on and something else is holding it still.** Three of them can be: exposure needs the grading pass to run at all, cloud shadows read a cloud sheet the video settings may have switched off, and caustics need both the waves that shape them and the refraction that fetches the bed. All three were drawing nothing and saying nothing about why.

### Fixed

- **The field of dots in the middle of the sun's reflection on water.** A march walked from the water towards the sun and asked at each step whether it had ended up behind what was drawn there — but a depth buffer records a surface and not a solid, so every ray that clears the far bank passes behind the far bank on the way up, and "went into it" reads exactly the same as "went over it". Neighbouring pixels sample different texels and disagree, one keeping all of its glint and the other none. The march is gone rather than softened: five attempts to soften it moved the dots without removing them, because the question has no answer in a depth buffer. The price is named rather than hidden — a glint can now appear on water the sun is behind a hill from.

- **A wet floor was brighter than a dry one.** Measured against the same frame with rain off, wet ground came out up to twenty-one levels of two hundred and fifty-five brighter: the sheen gathering the sky more than undid the darkening, so rain painted the world pale blue instead of wetting it. The sheen is also the one term in the effect that depends on the viewing angle, swinging by a factor of five between a floor looked down at and one looked along, which is why a block a step up looked untouched while the floor at your feet did not.

- **Two numbers in the water were measured in the wrong space.** The refraction shifted a screen coordinate by a world one, so the bed slid the wrong way as the view turned: right on the screen facing one direction and reversed facing the other. The point is moved in the world now and then projected, which is the conversion the old code skipped. And the thickness of the water was the difference of two depths along the view ray rather than down the column it claims to measure — at a grazing angle that is several times the depth, so one shallow pond read as an ocean from across it and as a puddle from above, with the absorption and the foam both riding on it.

- **The three sun-driven shadows share one sunrise and give one answer.** Each had been arriving at its own hour, and two of them then multiplied, so a contact shadow lying under a cloud came out darker than either could make it. They are not two occluders in front of two lights but two ways of finding out about the one sun, so the answer is whichever of them found more of it.

- **The shadow drawn over the finished frame no longer darkens a torchlit cave.** The traced shadow lowers sky light and leaves block light alone — a wall lit by a torch is never darkened by the sun — and a pass over the finished picture could not do that, because by then the two halves of the light have been multiplied into one colour and nothing downstream can tell them apart. The terrain now carries how much sky a surface gets in the half of its alpha nothing was using, and the shadow is weighed by it.

- **Light through a canopy was set to full strength by the preset named for looks, and could not act.** That effect needs something to be traced and nothing was, and the one line whose whole job is to name the effects waiting on rays listed three of the four.

- **The preset named for looks no longer switches on the effect this project's own descriptions call unfinished.** Screen reflections were in it, and every description shipped here says they are not finished.

- **The sky is reflected along the ray rather than by the colour of the horizon**, which gave the same answer looking along the water as looking down into it.

- **Foliage is bent upright from the diagonal it is built on**, rather than from an axis it was never on.

- **The occlusion's sample rotation is keyed to where a point is in the world** rather than to which pixel it landed on, so the pattern stops swimming across the screen as the camera moves.

- **The roof check walks in steps of one block instead of two.** It asks whether anything stands between a point and the sky, and a step of two blocks steps clean over a one-block ledge.

- **The refracted sample is mixed in only as far as it is believed**, by the measure that already decides whether to use it at all rather than by a second one that disagreed with it.

- **Creatures were see-through, and the player's own model with them.** The texture matrix was mirrored as one matrix for the whole of OpenGL, and OpenGL keeps one per texture unit: the game puts a permanent scale and offset on the light map's unit and never takes them off. Read as the skin's, that offset made every creature in every session look like a glint and be drawn as one — added to the picture rather than laid into it, writing no depth, and taking its colour from a single texel. A second latch in the same place kept a batch marked as a glint for the rest of the session once one enchanted thing had been seen. Only the unit a skin is drawn from is followed now, the mark is cleared with the batch, and the diagnostics report how many batches went out as shimmer, so the same fault would be one number rather than a report.

- **The red flash of a hurt creature came back.** It was never geometry: the game builds it out of a fixed-function texture stage, so capturing the geometry lost it. It is laid over the skin and under the light map, in that order and for a reason — vanilla does the same, and a creature hurt in the dark is a dark red rather than a lit one.

- **A crash in a pack, from a patch that insisted on finding something another mod had already done.** The redirect that shares one array of directions instead of copying it may now find nothing, because a missing call means another coremod made the same substitution first. This mod also stands aside for two more renderers it had not heard of.

- **Chunks whose geometry was never in the buffer are given up** instead of being carried into the new one when it grows, and the allocator's mark is wound back to the last byte that exists. A mark past the end of the buffer it indexes meant everything beyond it had never been uploaded, and the growth copied whatever happened to be sitting there.

- **The leaf speckle in a traced shadow is offset by the dither rotation** it was documented to use, rather than by the torch radius, which is a different number in a different unit.

- **A creature's far side no longer goes black in daylight.** The wrap that decides how much sky light a face turned away keeps mapped a face turned fully away to nothing — the one case it exists to avoid.

- **Every preset now sets the fog distance, and a low one was ruining the picture whatever else was switched on.** At two per cent of vanilla's the world is a white wall two chunks away — grass, water and sky all the same pale blue, with every effect painted behind it and invisible. No preset wrote that setting, so loading one did not repair it and all of them looked equally broken. Six flights were needed to prove it: switching off the light shafts, the bloom, the grading, the sky gradient, the haze, the wet surfaces and the high dynamic range each changed nothing, and the fog distance alone changed everything.

- **Two of the diagnostic views left water out of the picture.** Show Materials wrote its answer before the water code, and the mirror, the refraction and the glint each repainted the surface afterwards — so the one material it never showed was the one everything downstream decides again. Show Reflections sat inside both the water and the screen-reflection sliders, which meant that on a preset with the second at zero it could not draw anything at all, and "the view shows nothing" read as "the ray found nothing" rather than as "the view was never asked to run". Both now answer whatever the sliders are set to, and a water face that never reflects says so instead of staying silent.

- **The sky gradient's description said the opposite of what it does.** It claimed to follow the height of a pixel on the screen, and to put the deepest part of the sky across the middle of the view when you look up — true of the first version, and not since the pixel started being unprojected into a direction in the world. Regenerating the English language file, which nothing had done for a while, turned up four more entries that had drifted the same way.

- **Twenty-three places where a comment described something the code no longer does**, three of them real defects rather than stale prose: a sampler carrying another sampler's description and an overwrite that made most of that description untrue, and three claims in the README a player could check and find false.

- **The interop gives back everything it took when its setting up fails.** The teardown left at the door unless the whole of the initialisation had run, which is exactly the case that most needs it: a renderer whose construction throws is never stored anywhere, so an exported image, the texture built on it and two imported semaphores belonged to nobody until the process ended — and the OpenGL half was never given back even on the ordinary path. Waiting for Vulkan does not wait for OpenGL either, and this runs on every window resize.

- **The chunk allocator's limit is worked out from the mark it will be added to, whatever moved it.** It was worked out from the mark, growth was asked for it, and only then was the mark read again — and growth does move it, winding it back to the last byte that exists.

- **A model whose geometry could not be written is handed back to the game** instead of being claimed either way.

### Changed

- **The cost panel says when a setting gives a resource back instead of taking it.** Every number it could print was a cost, so the Vulkan terrain read as two, three and three — and the question that came back was whether it is not supposed to take work off the processor. It is, past about eighteen chunks of render distance, and the panel had no way of saying so. Savings print in a colour of their own now, with a line under the rows saying what the three bars are measured against.

- **The motion of every pixel is no longer worked out when nothing is going to read it**, and the preset named for the smallest machine leaves the drawing thread a core of its own rather than taking every one for chunk building.

- **The mod says when creature light is switched on and cannot act**, which is every session where the game is still drawing the creatures rather than this renderer.

## [0.9.0]

### Added

- **The sun glints off water, and at night so does the moon.** A narrow bright streak that moves with the ripples, which is the one thing the reflection could never give: a mirror shows what is behind you, a glint shows where the light itself is. It fades out over seventy-two blocks and is gone past that, which answers the only complaint the first attempt drew — flying up over an ocean used to make it grow until it filled the view. It was growing correctly: the share of a surface that returns a highlight really does widen towards the horizon as the eye rises. Nothing else in this game grows when you climb, so the correct thing read as a fault. It is deliberately absent from the traced version of the terrain shader, where a term of this exact shape cost that pass more than every other effect of this release put together and once took the graphics device with it; the setting says so rather than doing nothing quietly.

- **The mod has a logo, an author line, a licence and a link to where its bugs go.** Cleanroom draws its own mod list rather than Forge's, and it reads the last three of those out of a block of `mcmod.info` that plain Forge stores and never looks at — so on that loader the entry now has a Submit Bug button that leads somewhere, and on Forge it looks exactly as it did plus the picture.

- **Ice gathers the sky.** The game draws ice as a flat blue pane. What makes it the most recognisable surface in a shader pack is the one thing water already had here — it looks along itself the way a polished floor does, dark from overhead and bright at a grazing angle. Cheaper than water, because ice does not ripple: no waves to shade and no ray to march.

- **Light gathers into bands on the bed of shallow water.** Real caustics are the surface working as a lens on the light going through it, and the light collects where the surface is flat and thins where it is steeply tilted — which means the pattern is already in the slope this renderer computed for the waves, and needs no second field of noise, no new texture and no trigonometry at all. It brightens what refraction is already fetching, so it costs a handful of instructions on a path that was already being walked.

- **Rain wets what it can land on.** A wet surface darkens, because the film of water carries light down into the material instead of scattering it back, and it catches the sky at a grazing angle, because the film is smooth where the block is rough. Both, or it reads as a dusting of snow rather than as rain. Only upward faces, and only in proportion to the sky light a surface already receives — there is no test for what is over a particular block, so a lit cave mouth dampens a little, which is wrong and looks like weather rather than like an error.

- **The fog leans towards the sun.** The game fogs everything to one colour whichever way you are facing, and the sky it hangs under does not: air scatters short wavelengths sideways and long ones forwards, so haze into the sun is bright and warm and haze behind you is cool. This tilts the colour the game already chose rather than replacing it, so it cannot disagree with the sky above it, and it is exactly neutral when you are looking across the sun rather than at it.

- **The clouds take the colour of the sky they hang in.** Vanilla clouds are white at noon and white at sunset, in an orange sky. Two colours the game has already worked out for this exact moment are mixed in — the sky colour, which carries the biome and the weather, and the sunrise and sunset band, which exists only while there is one to have. Nothing is invented, which is why it cannot disagree with the horizon behind it. The volumetric clouds of a shader pack are a different and much larger thing; this is the half of that look which is free.

- **Vines and sugar cane move in the wind.** Both were left standing still because the rule that moves the top of a plant cannot be applied to them: a vine hangs from above, so it is the top that must stay put, and a cane is up to three blocks of one stem where the head of each and the foot of the one over it would move by different amounts. They now drift as whole blocks, the way leaves do, which sidesteps the question instead of answering it — a cane leans rather than curving, and there is no seam anywhere in it to come apart.

- **A key that steps through the saved settings profiles**, without opening anything. Profiles exist to be compared, and the comparison needs the world visible at the moment it is made rather than a menu over it. Unbound by default and rebindable like any other.

- **Two sliders for the offscreen chunk preloader** — how many chunks it keeps queued, and how much of the grid it looks through each frame. These decided the whole shape of that trade and were constants in the source, so the only way to match them to a machine was to rebuild the mod.

- **The whole picture is graded, not just the blocks.** Contrast lifted through the middle, warmth put into the balance — the thing that separates a shader pack's frame from the game's before any single effect is named. It could not be done in this mod's own composite, which is stitched into the frame before the game draws its creatures: the blocks would have been graded and the cows left alone. It runs on the marker that fires once the world is finished in full, so it reaches terrain, creatures, particles, weather and water together, and stops before the hand and the interface. The game's frame is eight bits a channel, so this is colour grading and not a film curve — there is no headroom above white, and the curve deliberately never pushes anything into it.

- **The frame time graph can sit in any of the four corners.** The default is the bottom left, and so is the chat window — a readout over what you are reading is a tool nobody leaves on. The two lines of numbers move to the other side of the graph in the top corners, so nothing runs off the screen.

- **Tall plants, leaves and cobwebs move in the wind.** A plant two blocks high used to stand still because moving the top of its lower half and the bottom of its upper half separately would have pulled the stem apart; the halves are now told apart and the seam moves as one place. Leaves drift as whole cubes rather than by their corners, which is what a full cube needs — the wave is read once at the block's centre, so the cube stays a cube and the canopy has no holes in it. Cobwebs were never classified at all and stood in a draught perfectly still.

- **A time of day and a weather of your own**, on this screen only. Nothing is sent to a server, nothing is written to the world, and no other player sees it — mobs still burn at dawn and a storm the server believes in still charges a creeper. Every effect in this mod looks different at a different hour, and this is how to see two hours without waiting for one.

- **A budget for explosion particles.** The client is sent the list of blocks an explosion destroyed and asks for two particles at every one of them, so a large charge of TNT is tens of thousands of them born in a single tick. Past the limit one in eight is kept rather than none, because cutting off leaves a hole where the blast was biggest. Off by default.

- **The primed TNT cube is recorded once instead of rebuilt per charge per frame.** The game looks the model up and issues seven draw calls for every lit charge in every frame; five hundred charges is three and a half thousand of them describing one identical cube. The picture is the same to the pixel — the list is recorded from the game's own call.

- **A short shadow where a thing meets the ground.** Every object in the game floats a little
  without one: a chest on a floor, a mob on grass, another mod's machine on stone. This is walked
  over the finished picture towards the sun rather than traced, so it lands on everything that got
  drawn, including whatever a mod put there — and it costs nothing to know what that thing was.

- **Sunlight visible in the air.** The walk from a pixel towards the sun adds up what the sky shows
  through on the way, so a gap in a canopy leaves a bright lane and anything standing in the way
  leaves a dark one. No geometry and no rays are involved, which is why it cannot break another
  mod, and whatever a mod drew casts its own shafts for free.

- **The clouds overhead throw their shade on the world.** Read out of the very sheet the game draws
  its clouds from, at the height the world reports and with the drift the game itself counts — so
  the dark patch lands under the cloud that cast it rather than beside it. A sky with clouds that
  leave no mark on the ground is a sky nobody believes.

- **The game's frame gets room above white.** Its colour buffer is converted to sixteen bits a
  channel, and the pass that grades the picture brings the range back down with a film curve
  instead of clipping it — so a highlight on water keeps the ripple inside it and a torch keeps a
  core instead of becoming a flat white patch. It touches one object rather than every framebuffer
  in the game, and it puts the frame back to eight bits by itself the moment the pass that would
  close the range down is not going to run, because a floating frame with nothing to resolve it
  would burn every highlight in the game. An **Exposure** slider comes with it.

- **A round, warm sun and moon**, drawn at runtime rather than shipped, so their size, their colour
  and the softness of their edge are sliders. Nothing is copied from anybody: a disc with a warm
  falloff is arithmetic. If another mod draws the sky in the world you are in — several do — the
  mod now says so instead of leaving the switch on and doing nothing.

- **Living creatures are drawn in a subpass of their own**, so they hide one another by depth
  rather than by the order they were listed in, they land in the depth the water reflects against,
  and they cast a shadow of their own shape instead of the round patch under them.

- **The log says how much of a slow frame belonged to the machine.** The game runs on a virtual
  machine whose collector stops every thread when it decides to, which is the one explanation that
  fits a stall on an unchanged scene — and it was the one thing never measured. The worst frame now
  reports how many collections landed inside it, and so does the interval.

### Experimental

- **Creatures are drawn by Vulkan properly, and now have depth.** They were switched off and labelled broken in 0.8.0, and the reason was one thing: the pass they were drawn in is the one built for particles, whose depth attachment is declared read-only because the water shader samples that same image for its reflections. No pipeline in such a pass may write depth, whatever it asks for — so a mob did not hide the mob behind it, the far side of a head was drawn over the near side, and water covered a creature standing above it. The layout of a depth attachment is declared per subpass rather than per pass, so there is now a subpass before the old one: creatures are drawn there with depth writes on, everything else follows in the second with depth read-only exactly as before. Face culling stays off, which is vanilla's decision rather than this mod's — it turns culling off for the whole of every living creature it draws, and the models are built with no promise about which way a face points.

  Creatures are shaded the way the game shades them, which they were not: vanilla lights them with two fixed directional lights and an ambient term, and none of it was being reproduced, so a mob drawn here came out brighter than the same mob drawn by the game, and flat with it. The same two lights are folded into the vertex colour now, where the normal already is.

  Forge asks for the entity pass twice a frame, and only the first is this renderer's. The second happens after the translucent layer has already been submitted, so anything taken there was cancelled for the game and drawn by nobody until the following frame. That pass is left to the game.

  Still missing, and the switch says so: the red flash when something is hurt, and the shimmer on enchanted armour.

### Fixed

- **The world was black on AMD, with the hand and the interface still drawn over it.** One line, and
  not the line anyone was looking at. `glDrawBuffers` and `glReadBuffer` read like a pair and are
  not: the first acts on the framebuffer bound for drawing, the second on the one bound for reading.
  A depth target built only on the draw binding therefore sent its `glReadBuffer(GL_NONE)` to the
  game's own frame and left it there for the rest of the session. That target only ever writes, so
  it paid nothing — every later copy taken *out* of the frame was refused instead: the depth for
  scene occlusion, the colour for it, and the frame for grading. The grading pass writes back what
  it read, so an empty copy went over the whole world, with the hand surviving because the hand is
  drawn afterwards. It needed a card with no sampleable 24-bit depth to build that target at all,
  and a preset that grades to make it visible, which is why it was one make of card on one preset.

- **Grass darkened when you turned round.** The normal a surface is shaded from is measured from two
  screen-space derivatives, and the sign of that measurement follows how the triangle happened to
  land on screen. A block face is only ever seen from the front, so it never noticed; foliage is
  drawn with its back faces kept, and the same blade of grass handed back opposite normals depending
  on which side of it you stood — lit from one direction, then from the other, with nothing about
  the light having moved. It now faces the eye before anything is asked of it.

- **Shallow water at a shore was painted the colour of an ocean, in rectangles.** How much water the
  light came through was measured along the refracted line rather than straight down, and the shift
  comes from the wave normal — which a flat-topped water block carries almost unchanged across its
  whole face. So a face's worth of fragments moved together, and at a shore they cleared it
  entirely, read the far bank or the sky, and reported hundreds of blocks of water. The refracted
  sample is still what the surface looks through; it is the wrong thing to measure a column with.

- **The glow and the grading passes gave back what they borrowed only on the way out**, rather than
  on any way out. Both take the attribute stack, the current program, a texture unit and the
  framebuffer, and the grading pass writes over the whole picture — so anything thrown inside them
  would have left the next thing to draw using this mod's viewport and blending for the entire
  frame rather than for a corner of it.

- **Ore looked like stone on the Potato preset.** That preset replaces every face with a colour read
  from the end of the mip chain, where a sprite is a single texel — and an ore block is stone with
  specks in it, so iron and stone averaged to the same grey. Losing the ore on the one preset meant
  for playing on rather than looking at is losing the game. The sampler now stops one level short:
  four texels instead of one, ore reads as speckled, everything else still reads as a colour, and it
  is the block's own texture rather than a second one invented for it.

- **Ultra logging was switched off by loading the Stable preset.** It changes nothing about the
  picture; it is the instrument somebody is holding while they work through the presets, and a reset
  that quietly puts the instrument down leaves the next hour of testing producing a file that stops
  where the interesting part starts.

- **The scene occlusion pass said nothing at all in the diagnostics.** Every other effect in that
  file names itself and how many frames it ran for. The one under suspicion for a black world was
  the one that did not, so a report from the machine where it happened could not answer whether the
  pass had even run. It now says that, what it thinks of the two copies it reads, and the pipeline
  state it was handed — which separates "off" from "on and reading a copy the driver refused",
  two opposite faults that look identical on screen.

- **Switching the material tags on rebuilt nothing.** What a block is made of is recorded into its
  geometry while the chunk is built, so the world was left half tagged — grass swaying in whatever
  chunks happened to be rebuilt since and standing still in the rest, with the glow, the shading
  and every water and ice effect patchy the same way. That reads as an effect that half works
  rather than as a stale chunk.

- **Contact shadows flickered indoors on the smallest movement.** The jitter that offsets their ray
  was read from which pixel a point landed on, and this march takes a maximum over a hard threshold
  with nothing downstream to average it — so turning the head a fraction of a degree moved where
  the threshold fell. It is read from where the point is in the world now.

- **The Beautiful preset asked for thirty-two chunks of render distance**, which bought it nothing:
  its effects are paid per pixel, while rebuilding chunks at that range is what turned a
  four-millisecond frame into a thirty-millisecond one every twentieth frame. It caps at twelve
  now, and sizes the chunk-building pool from the processor like the presets named for speed always
  did. Its button also had no word on it.

- **Grass kept swaying long after the sway was smaller than a pixel.** It fades out by how big the
  movement would be on screen rather than by distance — which means the zoom key needs no special
  case at all: narrowing the field of view enlarges everything, and the grass starts again exactly
  where it becomes visible.

- **A wait for the graphics card was being counted as time spent recording commands**, so a frame
  reporting thirty-eight milliseconds of this renderer's work was reporting a processor doing
  nothing at all. The wait is timed on its own now; real recording has never exceeded half a
  millisecond.


- **Light comes through a canopy.** A ray does not read textures, so a leaf block stopped a shadow ray exactly as stone does, and a tree that is mostly holes threw a solid slab of shade — which is the single thing that tells this apart from a shader pack at a glance. Carrying the atlas, the texture coordinates and a buffer address into every shadow test would be a great deal of machinery on the one path in this shader with no room left in it, so a quad is not asked *where* its holes are: it is asked *how much* of it is holes, and light passes with that probability. Averaged across the frames the accumulation pass already blends, that comes out as dapple. Grass and flowers cast a shadow at all for the first time — they were not in the structures — and a light one. The water path keeps the old, cheaper test: the same loop costs that pipeline six per cent more code, and six per cent of that pipeline is what lost the graphics device once.

- **The sky is shaded by where you are actually looking.** The first version used the height of the pixel on the screen, which is the same thing only while the camera is level: looking up put the deepest part of the sky across the middle of the view instead of overhead. It unprojects the far plane now, which costs one matrix multiply and answers the real question — and with a real direction in hand, the sky warms towards the sun near the horizon and stays clear away from it. The colours are still the game's own, so nothing here can disagree with the horizon it hangs over.

- **Water has a depth.** A puddle and an ocean were shaded identically, because nothing anywhere asked how much water the light had come through — which is why ours read as a blue window rather than as water. Real water takes the long wavelengths out first, so red is gone within a block or two, green survives further and blue further still: shallow water shows the sand nearly as it is, deep water is a colour of its own with none of the bed left in it. The thickness costs nothing to know — the depth of the bed is already being read a few lines earlier to decide whether the refracted sample is really behind the surface. **Foam rides on the same number**: the shore is simply where the water is thinnest, so no test for "is this the edge" is needed, and the wave slope breaks it up so it moves with the surface instead of lying on it like paint. Both are left out of the traced version of the shader, where together they cost that pass 5.3% more code — very nearly what the glint cost when it lost the graphics device — and the settings screen says so.

- **A torch keeps its shadow when you take a step.** Which sources were allowed a traced shadow was decided by their place in a list sorted by distance to the *camera*, and the shader took the first few of it — so which torch cast a shadow was a fact about where the player stood. Walk one block, the order changes, a different torch is chosen, and the shadow on the wall in front of you appears or vanishes with nothing in the scene having moved. It is now decided by distance to the surface being shaded, which does not change when the player moves. Kept out of the translucent pipeline by the same specialisation constant that keeps the wet surfaces out, because what this fixes is torch shadows on blocks: +4.3% of the opaque pipelines, and 0.2% of the one with no headroom.

- **The sky is no longer one flat colour.** Vanilla paints it the same blue from the horizon to straight overhead, with a warm band at dawn and dusk and nothing in between — and the sky is the largest single area of the screen. It now deepens away from the horizon, in the game's own fog colour taken down towards a night sky, so the top of it cannot disagree with the horizon underneath. Painted only where nothing else drew, which is decided by this renderer's own depth rather than by looking at the colour: a hilltop against the sky keeps its own. Off by default, and honest in its own description about what it is — the gradient follows the screen rather than the true direction of the sky, so looking straight up puts the deepest part across the middle. A look, not a sky.

- **A creature casts its own shadow instead of a round blur.** Vanilla draws a circle on the ground under every entity because it has no shadows at all — the same circle for a chicken and for a horse, in the same place whatever the sun is doing. Creatures are in the acceleration structures now, as one structure a frame over all of them rather than one each, so the sun throws a real shadow shaped like the animal, from the same ray the terrain already uses. The circle goes when the real shadow is certainly there and comes straight back when it is not: tracing has to be running, sun shadows on, and creatures going through this renderer, or there is nothing in the structure to cast anything.

- **A settings change no longer invalidates the frame being recorded.** Switching a preset, or anything else that rewrites the descriptor sets, could land between two terrain layers — after the first of them opened the frame. The specification is explicit that this makes the command buffer invalid from that moment, and everything recorded into it afterwards is recorded into nothing; drivers had been carrying on regardless and the frame looked right, which is why it survived. Found by the validation layer within seconds of somebody pressing a preset button with it on. The rewrite now waits for the gap between frames, which costs a settings change up to one frame of delay.

- **The Beautiful preset turns on the things it is named for.** It stated eight effects out of eighteen: everything added to this mod after it was written was never filed in it, so on a fresh install the preset left most of the picture switched off and looked like it had done nothing. Worse, it never turned on Material Tags — which are not an effect but the record of which block a vertex came from, and swaying, bloom, occlusion and every water and ice effect are switched on and inert without them. That is the one way to pay for all of it and see none of it. All eighteen are stated now, and so are the other presets, which is what makes a preset undoable by another one.

- **With another renderer installed, the mod says so in the chat.** OptiFine and the Sodium-derived renderers replace the same classes this one does, so the Vulkan terrain stands aside for them — silently, because nothing fails: the renderer simply never starts, the log has one line, and every setting in this mod stops meaning anything. That reads as "this mod is broken" rather than "it stepped aside for the renderer you installed", and those want very different things from the player. It now says which mod, in the chat, once, the same way every other reason for standing aside already did.

- **The glint is glitter again rather than a painted stripe.** It was measured against the calmed normal the reflection uses, and that calming exists to stop the Fresnel term banding near grazing — for a highlight it removes the one thing the highlight is made of. So the sea answered the sun the way a sheet of glass does: a single straight white column running to the horizon with no ripple anywhere in it. It is measured against the full wave now, which breaks it into the scattered path of separate facets each catching the sun for a moment. Its peak is lower to go with it, because the old one clipped: this frame has no headroom above white, so brightness spent past that bought a flat sheet of it instead of a bright core with ripple inside.

- **The sun does not shine on ice in a sealed cave.** The glint asked which way a surface faced and how high the sun was, and never asked whether the surface can see the sky at all — so a frozen pool underground, with no way out to the surface, carried the sun's streak across it. Sky light gates it now, exactly as it already gated the sheen the ice gathers.

- **Caustics have a pattern in them.** The bands come out of the slope of the wave, and how steep counted as steep was a fixed number that the water never approaches at any wave strength anyone plays with. Every fragment landed in the flat part of that curve, so the whole riverbed simply got brighter by the same amount and the cells and dark lines — the entire effect — were a few per cent of contrast. It is measured against the steepest slope the current wave setting can actually produce, so the pattern keeps its shape at any strength. They also need the waves on, which is now said where the setting is.

- **Rain no longer wets grass, flowers and leaves.** Foliage here is lit as a volume rather than as the two flat quads it is drawn from, which means its normal is bent most of the way to standing up — and the rain asked that bent normal whether it pointed at the sky. Every blade of grass and every leaf answered yes, so a field turned blue in the rain and the canopies bluest of all. The question is about geometry rather than about light, and it is asked of the geometry now.

- **The desert stays dry.** Rain strength is one number for the whole world, and vanilla's own rain is not — it asks each column's biome whether rain falls there before drawing a drop, which is why a desert stays clear in a storm. Nothing in a fragment knows where in the world it is, so the ground in a desert was wet through a thunderstorm. The camera's biome now answers for the frame: exact where the player is standing, and wrong only for the few blocks across a border they are looking at from the other side. Snowy biomes answer no as well, snow being what falls there.

- **Sugar cane stands still again.** It was given the drift that leaves have, and a cane is not a leaf: it is a rigid stick, and a rigid stick sliding sideways as one piece reads as the stick being pushed rather than as it bending. Bending is what would look right and is exactly what a column of identical block states cannot express. Vines keep the drift — a vine is a mat hanging on a wall, which is what that rule was for.

- **A canopy casts a shadow.** The acceleration structures were built from the solid layer alone, described in the code as the whole of the terrain when it is one opaque layer of three — so a forest cast the shadows of its trunks and nothing else, long lone sticks lying across open ground with nothing over them. Leaves are in now. Grass and flowers deliberately are not: a ray sees the quad rather than the picture on it, so a tuft of grass would lay down the shadow of the whole square it is drawn on.

- **The line saying ray tracing needs a restart now actually reaches the chat.** It was queued correctly and printed by one handler — the one that exists only in a session where the renderer never started, which is every session except the one this message is for.

- **The settings screen nods on the screen you are looking at.** Saving a profile called 67 rocked the settings page behind the profile screen, which is not the one in front of anybody at the moment a profile is written.

- **The renderer refused to start on any modern Java, which is every Cleanroom instance there is.** It said so plainly — "Java 26 is newer than the bundled LWJGL supports" — and the refusal was over a limit that did not exist. The library switches its behaviour on the version of the JNI, not the version of Java, and those two move at different rates: the bundled LWJGL knows JNI 24, and Java 25 and 26 both report exactly that. A Java version was standing in for the number that actually decides, so the check refused a JVM that works. The line is now drawn where the library draws it, the JNI version the JVM reports is written into the log beside it, and `-Dvulkanmod112.javaCeiling=NN` moves it for anyone who wants to find out for themselves. Everything ever measured on such an instance was measuring the game's own renderer.

- **This mod's copy of LWJGL no longer collides with the loader's.** The native libraries were unpacked to the paths LWJGL itself searches, checksums and all, so a loader shipping a newer LWJGL of its own found ours first and printed `Incompatible Java and native library versions detected` — twice a session, in a game whose world we were not even drawing. Somebody else's error message, caused by us, in the file people are asked to send when something breaks. The libraries now live under a private prefix that nothing else scans.

- **Height fog was measured from the camera rather than from the sea.** The question it asks is how deep a place is, and it was answering how far below your eye it is, which is the same number only while you stand on the ground. Fly up, and everything in the world is far below you at once: the curve saturates everywhere, and the effect stops being fog and becomes a flat wash of fog colour over every block on the screen. With Distant Horizons installed it is unmistakable — a coloured disc exactly the width of the vanilla render distance, with a hard edge, travelling with the player, because the terrain past that is drawn by that mod and never saw any of this. It is now measured from this world's own sea level rather than a constant, so fog gathers in ravines, canyons and the sea bed and leaves the hilltops alone. **Values set before this will need setting again** — the old ones were chosen against a model that applied everywhere.

- **The two chunk counts in the terrain line are now the same kind of thing.** One was the sum over all four layers and the other the list for a single one, so the line could report eleven thousand chunks drawn out of four thousand — a counter larger than its own total, in the first line anybody reads when the world does not appear.

- **The mouse wheel scrolls the settings screens on Cleanroom.** That loader answers the wheel in detents where LWJGL 2 answers in the units Windows uses — one notch arrives as 1 rather than as 120 — so the reading rounded down to nothing and no list in this mod moved. Both screens now take the magnitude where there is one and the direction where there is not, which is right under either. Reported, and diagnosed, by the person who reported it.

### Removed

- **The visibility walk interval.** It held back the game's visibility search while the camera stood still, and it was measured and closed long ago: nine and a half of every ten thousand requests a second come from the camera moving, which it deliberately never touched, so no value of it helped. It sat at zero, which made every branch below it unreachable — a hundred and eighty seven lines that read as a working mechanism and did nothing, in code that had already broken once silently. The counting it also did is kept; that is the line that says where a frame went.

### Changed

- **Ray tracing has a page of its own.** Everything that traces was filed inside a block called Memory, under the video-memory budget. The switch the other eight settings depend on now sits first on a page named for what it does.

- **Shaders have a page of their own**, and the sun, the moon, sky pictures and water refraction moved onto it out of the diagnostics block they had been sitting in.

- **Turning on ray tracing mid-game now says, in chat, that the game has to restart.** The line under the switch said so already; somebody who has just pressed four switches and gone looking for shadows is not reading it.

- **The mod now says when an effect is switched on and cannot act because rays are being traced.** Three of the effects above — the glint, the ice and the caustics — are deliberately left out of the traced version of the terrain shader: all three live in the one branch that lost the graphics device outright while tracing was on, and that branch has no headroom left in it. Being told beats a slider that appears to do nothing.

- **A lighting mod that replaces the light map rather than writing into it is now named in the log.** Terrain lighting here follows the game's own light map, so a mod that writes into it is followed for free; one that replaces the texture is not, and the world would then be lit two different ways in one frame with nothing anywhere to say so.

- **The device is no longer stopped twice in a row** when the flat-colour setting is switched. Both the sampler and the descriptor sets waited for the card to go completely idle, one immediately after the other, with no work in between.

- **The interop handles this mod is holding are counted in the diagnostics report**, which is the only way from inside the process to answer whether a long session grows them.

## [0.8.1]

### Fixed

- **A torch in your hand now lights itself.** It lit the ground and the walls and stayed dark in your fist, which is the one place the light is most obviously expected to be. The hook meant to fix that was never attached: it was aimed at the class that declares the method rather than the class the game calls it on, and the mixin library is allowed to skip a hook it cannot place without saying so. Every hook in the mod is now required to find its target, so the next one of these stops the game instead of shipping.

- **Ray tracing switched on mid-game now says it needs a restart.** Acceleration structures have to be asked for when the graphics device is created, so the switch takes effect at the next start — and until then sun shadows, traced light and traced block light all quietly do nothing. The settings screen said "applies after the game restarts"; the log said "ray tracing: off in the settings", which to somebody who has just switched it on is worse than saying nothing at all. Both now say what is actually happening, and the check below no longer believes the setting over the device.

- **A setting that cannot do anything now says so.** Every effect in this mod lives inside the Vulkan renderer, so with it switched off they all do nothing — including dynamic lights, whose sources are collected during the Vulkan draw and nowhere else. Nothing said this anywhere: the switch read as on and the log said Vulkan had started, which is true and means something else. The game now names what is switched on and inert, and why, at startup, in the log when it changes, and in the diagnostics report.

### Changed

- **The diagnostics report records every setting**, not the five it used to. A report saying an effect looks wrong could not be told from a report by someone who never turned it on.

- **Settings changes are written to the log as they happen**, so a report can be read as a sequence of events rather than reconstructed by comparing snapshots.

- **A batch the renderer will not take is drawn rather than dropped.** It used to be counted, logged as left to OpenGL, and then left to nobody, because the caller had already been told the geometry was handled.

- **Turning on Ultra Logging mid-session now captures the log too.** It only attached at startup, so a file started after something went wrong contained snapshots and not one line of the mod's own log — which is the half that says what happened.

## [0.8.0]

### Added

- **Ray-traced shadows.** The sun casts real shadows across the world, and so does a torch in your hand, a creature that is on fire, and the torches already on the walls. A shadow here does not darken the finished colour — it lowers how much sky light a surface receives, which is what the game itself does when night falls, and is why a cave lit by a torch does not go dark when the sun goes behind a hill. Sharpness is a slider for the sun and another for flames, and at zero the edge follows the pixel grid exactly. Needs a card that can trace rays from a shader; where there is none the setting simply does nothing and says so in the log.

- **Frame averaging**, which is what makes the shadows above usable. One ray per pixel is grain, not a soft edge; the rays are aimed differently each frame and this mixes each pixel with what it was, which turns them into one. History is thrown away wherever keeping it would smear rather than smooth — off the edge of the screen, on anything that has just changed, and increasingly the faster you turn. There is a diagnostic view that paints how much history each pixel kept.

- **Water refraction.** Reflection and refraction are two halves of one thing and only one of them was here: a pond whose mirror moves while its bed stays perfectly still reads as glass laid over a photograph, and it is the bed that gives it away. The displacement follows the tilt of the wave and shrinks with distance, so a lake does not shear at the horizon.

- **Particles, rain and snow are drawn by Vulkan.** They ride the pass the water already uses rather than opening one of their own, so they cost nothing beyond the drawing itself.

- **A round sun and moon.** The pictures are built by the mod rather than shipped as files, which is what makes their size and warmth sliders instead of somebody's fixed choice. The moon is drawn as all eight of its phases, because the game does not draw a moon so much as one cell of a sheet chosen by tonight's phase.

- **Named setting profiles.** Save the whole settings screen under a name and switch between them — a heavy one for screenshots, a light one for playing.

- **A key that opens the settings**, **F6** by default and rebindable. Every setting here is judged by looking at the world, and the trip through four menus and back is what stops people comparing two values.

- **The Vulkan device is chosen to match OpenGL** rather than by which one is fastest. On a machine with two cards the fast one is of no use if the other half of the frame is on the other card, and this is the difference between the mod working and the mod standing aside.

- **Traced light from the blocks already in the world**, replacing as much of the game's own flat block light as you ask for. Vanilla's is a flood fill: correct around corners and completely flat, with no idea where the light came from, so a torch on a wall lights a room exactly like a torch on the floor. Searched up to fifty blocks around you.

- **Sky pictures can be borrowed from a pack you already have** — a resource pack sitting switched off, or a shader pack that happens to ship one. Only the pictures: a shader pack's code is written against a loader that does not exist here. Most shader packs ship no sun at all, and the log says so plainly rather than leaving you wondering.

- **Water, glass and ice are drawn by Vulkan on cards that have no 24-bit depth** — which is every AMD card, on Windows and on Linux alike, and so about half the machines this runs on. The transparent layer has to be tested against the depth the game owns by then, and the only way of handing that depth over was a hardware copy that works between buffers of the same format. Those cards use a different one, the copy was refused, and the layer quietly declined every single frame. There is now a second way: a shader reads the depth and writes it, and the hardware converts between the formats on the way past. Measured at just under a tenth of a millisecond a frame at four megapixels.

- The Vulkan validation layer's findings now go into this mod's log rather than the process output, where a modded 1.12 client buries them. A lost device is always reported by some later call that merely noticed — the layer is the only thing that sees the offending command as it is recorded.

### Performance

- **The atlas upload no longer stops the frame twenty times a second.** Every tick in which any animated texture changes — lava, water, fire, a portal, which is to say nearly every scene — the thread drawing the frame built a command buffer, submitted it and waited for the card to finish before anything else in the frame could happen. Nothing waits now: the work is carried by the frame's own commands.

- **The world is kept once instead of twice by default.** The blocks used to sit in both the game's memory and this mod's; dropping the game's copy frees around a gigabyte of video memory, which is what limited how far the render distance could be pushed.

- **The resource pack screen no longer decodes every icon it can see, on every frame.** Vanilla reads `pack.png` out of the pack and decodes it again for each visible row of the list, sixty times a second, for textures it has already uploaded — and packs without an icon of their own cost more the more packs are enabled, which is why the list only becomes unusable for people with a lot of them. Two hundred packs and a short scroll: two hundred decodes instead of forty-two thousand.

### Fixed

- **The card could be lost outright while ray tracing was on.** Shadow structures are built one after another into a single list of commands, each naming the scratch memory it will write to, and a bigger chunk arriving later in a batch grew that memory and freed the old one on the spot — while commands already recorded went on naming it. A write to freed memory, which on this hardware takes the display with it. It needed the world to be filling in for a bigger chunk to arrive after a smaller one, so it never happened standing still, which is most of why it took three occurrences and two wrong answers to find.

- **The world could stop drawing after a resource reload**, for the same shape of reason: memory the card was still copying from was freed before it had finished. It now waits.

- Four violations of the Vulkan specification that every driver had simply been letting through — a buffer copied without being marked as copyable, two copies reading past the end of what they were copying from, a batched draw command used without asking whether the driver supports batching, and a shared index buffer freed while frames still named it. All were years old and none had a symptom until they did.

- **Mobs and particles showed through blocks on AMD cards.** The world is drawn into a buffer shared with OpenGL, and its depth has to be handed back so the game can hide what is behind the terrain. On hardware that offers no sampleable 24-bit depth — every AMD card, on Windows and on Linux alike — that hand-back goes through the shader instead of a hardware copy, and it was being thrown away before it landed. The picture looked correct, because the colour arrived either way; only occlusion was missing, so anything the game drew after the world floated in front of it.

- **Water and glass could disappear entirely.** With the setting that stops the game filling its own chunk buffers, the transparent layer was left to a renderer that declines it on those same cards, and to buffers that were no longer being filled — so nothing drew it at all, and an ocean became a hole in the world. That setting now checks whether the transparent layer really goes through Vulkan before it drops anything.

- **Settings given on the command line were overwritten by the settings file** before the renderer could read them, so a `-D` switch appeared to work and changed nothing. The log now names every setting the command line has pinned.

- The diagnostics snapshot reported the depth copy as enabled by the setting on one line and disabled by the driver twenty lines below. The first line now says it is a request, not the result.

- **Handles the driver was given are no longer closed while it may still be holding them.** Sharing a frame between Vulkan and OpenGL is done by handing the second one a handle naming what the first one made, and this closed each handle the moment the import returned. That is what everyone does and it works on every driver it was written against, but nothing in either extension promises the driver copies what it keeps. One that stores the handle instead is left with a closed one, and then the object it names is dead while still answering as though it were alive — a wait that never returns, memory that faults when read. The handles are now held until the device that owns what they name is destroyed.

- **The two APIs no longer disagree about the shared depth image, which hung the card.** A layout is how the card has packed and compressed the pixels, and the two sides exchange that knowledge inside the semaphore operation and nowhere else in either API. OpenGL wrote the depth as an attachment and said so; the Vulkan pass that took it back declared it was receiving a texture to sample. Every frame the same image was handed over under two different names — one compression scheme read as another. It had been that way for a while and cost nothing, because the pass that would have exercised it was declining on those machines anyway; turning the transparent layer on above is what set it off.

- **A capability the mod asks for and does not get is now always a line in the log.** The validation layer was requested, was not installed, and the code went quietly on — a whole session spent to read an answer that was never going to be written. This is the fifth setting in two days to lose without saying so.

- The reported operating system on Windows is no longer quietly wrong. Windows tells any program built before it that it is an older version, and Java 8 is such a program, so every Windows 10 and 11 machine reported itself as 8.1. The line now says as much rather than inviting a bug to be explained with the wrong system.

### Diagnostics

- **The log can now show you a stutter.** A frame that takes forty milliseconds once a second is invisible in an average over three hundred frames and is the single thing anyone calls a lag. The report gives the middle frame, the worst one in twenty, the worst one in a hundred, and for the worst frame in the interval, where its time went and how much of it was this mod at all. Frames held back by the background framerate cap are counted separately instead of being reported as stalls, which is what they used to look like.

- **A number in the log had stopped being a measurement.** The OpenGL timer printed the same value to the hundredth for fifty-seven reports running, because it never once managed to collect a result and kept the last one it had — from while the world was still loading. It reported the cost as sixty-five milliseconds; the real figure is a little over one. Both timers now print how many results they actually collected, because a number that never changes looks exactly like a stable system.

- **Chunk builds are reported as a spread rather than an average.** The average was known and is about two milliseconds; what was not known was how often one takes hundreds. Anything past a tenth of a second writes a line naming where it was.

- **A chunk probe**, on a key of its own and unbound by default. It asks the game why the chunk you are looking at is not on screen — whether it was reached by the visibility search, whether it was ever built, whether it is waiting to be, and how many ways through it the game believes exist. It answered a question in two presses that had survived weeks of switching settings on and off: the chunk that would not draw is one vanilla's own visibility graph declines to reach, and this mod was never involved.

- **The mod now reads the memory it shares with OpenGL three separate ways at startup, announcing each before it runs.** A driver that accepts the sharing and cannot really use it takes the display with it at the first read, and there is no return value from that — so the answer had to be the log itself: whichever step is announced and never reports back is the one at fault. The reads happen before a semaphore is involved in anything, which is what makes the answer mean only one thing.

- The imported semaphore is now checked against the driver's own opinion of whether it is a semaphore at all. A refused import has no other symptom until the wait that never returns.

### Experimental

- **Creatures can be drawn by Vulkan**, and should not be yet — the switch says so. Everything about where a creature's bones go is solved and verified; what is not is the pass they are drawn in, which cannot write depth, so a mob is see-through and water covers one standing above it. It is left in, off and labelled, because the work continues from there.

## [0.7.1]

### Fixed

- **The renderer refused to start on some machines, and nothing on screen said so.** Everything this mod draws was silently absent — no swaying grass, no glow, no reflections, no water — while the part of it that never needed Vulkan kept working: the settings screen, the render distance, the chunk build threads. So the mod looked installed and healthy, with every switch in it moving and none of them doing anything.

  Creating a VkInstance is not the cheap wrapper it looks like. LWJGL lists the extensions of every physical device in the machine to work out which entry points exist, and it does that on the thread's scratch stack, in one frame it does not release between devices. An entry is 256 bytes of name and a version, 260 in all, so the frame costs 260 bytes times the extensions of every GPU added together — a number that belongs to the driver, not to us. LWJGL's own default of 64 KiB holds 252 entries, and a current driver lists around 270 for a single card. One report was a machine failing to start over a shortfall of three kilobytes.

  This mod had been raising that setting for exactly this reason, and on the machine in question the request was being ignored. LWJGL reads it once, into a static, the first time its MemoryStack is initialized, and by the time the Vulkan side runs, something has already done that — so the setting was applied to a stack that already existed, and the log cheerfully reported a budget the machine did not have. It is set through the system property now, before the classloader that reads it is even created, which cannot be too late. A `-Dorg.lwjgl.system.stackSize=…` given to the launcher still wins over it.

  Raising the value alone would not have found this. It was raised fourfold first, the failure came back identical, and a limit that does not move when it is raised is not the limit — so both numbers are now read rather than assumed. Startup logs the size in effect beside the size asked for and says so when they differ, the failure counts the machine's devices and their extensions itself and reports what that needs, and the per-GPU extension count sits in the startup log next to the device name.

  That counting is done on the heap, through the Vulkan loader's own exported entry points, rather than through the typed API — the scratch space is exhausted at that moment, and a VkInstance is precisely what could not be built.

  What the player used to be told was an `OutOfMemoryError` reading "Out of stack space", which is true, useless, and reads as though the game needs more memory allocated to it. It does not; this has nothing to do with the heap, and following that reading cannot help.

- **Nothing said the renderer was off.** The F3 lines that report the GPU and the terrain mode are added by the overlay, and the overlay only exists once Vulkan has started, so in the one case where a player has nothing else to go on, the screen they would check first was silent. The diagnostics log was in the same position — its per-frame tick came from that same overlay, so the file a report would have been built from did not exist either.

  There are now two F3 lines whenever the renderer did not come up: that it is off, and why. The reason is the innermost cause, which is usually the real one, unless something along the way already knew what to say — a stack too small for the machine's drivers explains itself far better than the library error underneath it does. The diagnostics tick and the background frame cap, neither of which ever needed Vulkan, keep running.

  `-Dvulkanmod112.forceFallback=true` reaches that state on purpose, because otherwise what a player sees when the renderer is off can only be checked on a machine where it is broken.

- The diagnostics log reported `terrain: Vulkan` on a machine where Vulkan never started, two lines above its own report that it was not initialized. The terrain path was allowed by the settings and nothing had failed since — because nothing had run.

## [0.7.0]

### Added

- **Ambient Occlusion**, off by default. Corners, the undersides of overhangs and the join where a wall meets a floor pick up shadow the game has no way to express. Vanilla shades a block face by which way it points and by nothing else — top at full, sides at 0.8 and 0.6, underside at half — so an inside corner is lit exactly like an open wall, and a room has no shape to it beyond its textures. What is missing is not a fact about the surface but about its neighbourhood: how much of its surroundings a point can actually see. That is the question a depth buffer answers, and the depth buffer is already here as a texture, so this costs no geometry, no extra pass over the world and no second copy of anything.

  A view-space position comes back from a depth and the two numbers a projection is made of, which are read from the projection the game has set rather than carried across — this runs inside the game's own world pass, so it is still current. The surface's direction then comes from how that position changes across the screen, and here that is exact rather than approximate, because every face of a block is flat. Eight neighbours are asked whether they stand in front of that surface and how near, the kernel turned by a different angle at every pixel so what eight samples cannot cover comes out as noise rather than as eight rings; then it is blurred, which removes the noise and leaves the answer, because the answer is about corners and crevices and not about texels. Half resolution throughout for the same reason.

  **Ambient Occlusion Reach** beside it says how far a corner's shadow carries, two blocks by default. One row says how dark and the other how far, and the second is what decides whether the thing reads as shadow at all: the first build reached three quarters of a block and was reported as a hard band along the join between a wall and a ceiling rather than as shadow fading out of it, because everything the effect had to say was being said within a few pixels of the seam. Two blocks is a little under the height of a doorway, which is the scale a room's corners are read at, and it is a slider rather than a number in the source because where to put it is judged in a lit room rather than reasoned about. It costs nothing to raise: the same sixteen neighbours are asked, only further apart — which is also its limit, since a set of samples spread across a room will step straight over a small alcove.

  Two more things were making that band, and neither was the strength either. A neighbour counted in full right up to the edge of the radius and then stopped, so as the camera moved each sample switched on and off at its own moment — as many steps in the shading as there were samples, which is what a band is; each one now fades to nothing at the edge instead. And the blur was the one written for bloom: a width that hides the grain of a single bright pixel does not hide the grain of sixteen directions, which is coarser and lives on a larger scale, so its taps walk twice as far here. That costs nothing, there being five of them either way. Sixteen samples also cost what eight did, once a neighbour's depth stopped being fetched twice — once to test whether anything was drawn there and again to turn it into a position — and they are laid out on the golden angle, so consecutive ones never line up however many there are, and spaced by a square root, so they cover the disc evenly rather than crowding its middle.

  It is applied before the terrain colour reaches the frame, so what the frame receives is already darkened. That mattered for more than tidiness: bloom decides whether a light is covered by comparing the frame against a copy of the terrain, and a copy that had not been darkened would have matched nothing, which would have put the glow out entirely. Terrain only — entities are drawn by the game after this renderer has finished, so a creature casts nothing into the corner it is standing in.

- **Bloom**, off by default. Light spills off a glowing surface into the pixels around it: lava, torches, glowstone and any modded block that gives off light — and only those. What glows is not guessed at from how bright a pixel is, which is how this is usually done and is the wrong answer here: snow and sand in sunlight are as bright on screen as lava and are not lights. Being a light is a fact about a block, and it is written down where the chunk is built: the block's own light level, as a level rather than a yes or no — a brown mushroom gives off light 1 in this game, and as a yes it lit up like glowstone, where as a fifteenth it disappears — carried in the material byte beside what the block is made of, so a modded lamp is a light without this having to know it exists. Two earlier versions worked it out from what a fragment could see instead, and both were wrong in the same way — reported from a screenshot, twice. Judging by how bright the texture is lit the pale texels of a glowstone block and left its dark ones to receive the glow spilling off their neighbours, so a light source came out mottled; and folding in the vertex colour handed the decision to vanilla's own face shading, which multiplies the sides of a block by 0.8 and 0.6 and its underside by 0.5, so a block glowed from its top and two of its sides and not the other two. Needs Material Tags on, and says so.

  The difference is known exactly once, while the terrain is being shaded — the game's own block light says whether a surface is lit from outside or is the source of it, and the texture's own colour before anything shaded it says whether the thing is bright in itself. A torch is bright and at block light 14; the stone it stands on is at 13 and grey, and grey is what rules it out. That answer is written into the alpha of every opaque pixel, which was carrying the constant 1.0 and nothing else — the composite only ever asks whether it is greater than zero, so the upper half of the range was free.

  Nothing is point-sampled on the way down, and that was the last and longest-lived defect in this: a small light had no reach at all while a lava lake was fine, reported four times in a row and blamed on three different things before the cause was found. The frame the game hands over is filtered nearest and carries no mip chain, so one read of it returns exactly one pixel however far the target has been shrunk — and a lamp post a few pixels wide simply fell between the samples of a grid eight pixels apart and contributed nothing, where a lake covered so many of them that it could not be missed. Every reduction is an average now: four samples into the half-size target, then four bilinear reads of a texture this renderer filters itself, which is a sixteen-pixel box, down to an eighth.

  Three blurs of different widths, added together, because one cannot do this. A blur spreads a source's light over the area it covers, so the further it reaches the dimmer it gets — and a lamp post, a column of glowstone a few pixels across at an eighth of the screen, has so little light to spread that a wide blur leaves nothing to see. It was reported as a fifth of a block of glow where a sphere of light was expected. A tight blur at half resolution gives the bright core close in, one at an eighth carries a block or so, and one at a thirty-second reaches a couple of blocks with almost nothing per pixel, which is what the far part of a glow is. Reach is bought by shrinking rather than by more passes: a blur of a fixed number of taps covers four times the frame for every quartering of the target, and costs a sixteenth as much doing it.

  Fullscreen passes over targets a fraction of the screen, no new Vulkan object of any kind: the composite was already an OpenGL program drawing the Vulkan colour target as a texture, so pulling the glow out, blurring it and adding it back is three more quads over targets of the same kind. The blur is separable, five taps an axis with the offsets sitting between texels so linear filtering makes each read count for two. The glow is added with a quad of its own rather than folded into the composite, and that is not tidiness: the composite discards where there is no terrain so the sky shows through, and a glow that stopped at the silhouette of a lava lake would be a lake with a hard edge.

  The glow is what lands on everything else, and the source is not touched at all. Two builds were needed to get there and the second was the more useful correction: with the source only mostly held back, the one thing left visible was the small amount still reaching it — so the effect read as "the lava texture changed" rather than as light spilling, which is a fair description of what it was doing. The reach was the other half, and it took being told three times that the effect could not be seen. A blur of a fixed number of taps covers twice the frame for every halving of the target it runs on, so reach is the same lever as speed rather than a trade against it. At half resolution the halo stopped six pixels out, close enough to the edge of a block to be taken for the block being brighter. At a quarter it was a rim drawn round the block — visible, and still not light falling on anything. It runs at an eighth now, blurred three times, which is where the ground beside a lava lake changes colour, and that is what this is for. Small sources are not lost at that size the way it sounds as though they would be: a torch is one texel there, but a blur moves energy rather than discarding it, so a torch becomes a wide faint glow, which is what a torch across a room looks like. A blur of a fixed number of taps covers twice the frame for every halving of the target it runs on, and at half resolution the halo stopped about six pixels out, close enough to the edge of a block to be taken for the block being brighter. It runs at a quarter now, blurred twice, which costs a quarter as much and fades out instead of ending.

  The glow is held back on the surfaces producing it, which the first build did not do and looked it: lava and glowstone came out as sheets of flat colour with their patterns gone. Adding light to a pixel already near the top of an eight-bit channel does not make it brighter, it makes it flat — there is no headroom anywhere in this frame, so the only thing the addition can spend is the texture's own detail. And a glow is not the source being brighter, it is light that landed somewhere else, so that is what is added now and the source keeps the look it earned.

  The glow is added once the game has finished drawing the world — after entities, particles, weather and water, and before the hand — rather than in this mod's own composite, which happens before the game has drawn a single creature. So a mob standing in front of lava is inside the glow rather than pasted over it, and a torch throws light onto the sky, which is not drawn until long after this renderer is done. The frame's own analysis said a full-scene effect was impossible here and it was wrong: the game binds one framebuffer for the whole world and its own profiler names the boundary, a section called "hand" opened once the world is finished, so the injection is on that label rather than on a line number.

  What glows is worked out from the finished frame rather than from this renderer's own picture of the world, and that is what stops a light shining through whatever is in front of it. The terrain image has no creatures in it, so a glowstone block behind a mob was still there to be found and its glow went straight over the mob — the block appeared to shine through it, and through a pane of glass put in front of lava. In the finished frame a covered source is simply not there. Whether it is covered is decided by comparing the frame against what the terrain looked like before the game drew into it: those are equal wherever nothing was put in the way, so a comparison answers it and no depth buffer is needed — which matters, because the game's is a renderbuffer and cannot be read at all.

  Nothing Vulkan owns is read at that point, which is what the shape of this is really about. By then the colour target has been handed back through a semaphore and reading it would race the next frame, so what the later passes still need from it — which pixels are lights, and what the terrain looked like before anything was drawn over it — is copied into a texture of this renderer's own while the target is still safe to read.

  What still does not glow is the creatures themselves: a burning creeper spills no light. Knowing which pixels of an entity are a light needs something the game does not record anywhere this can reach.

- **Foliage Sway**, off by default. Grass, flowers, saplings and crops lean in the wind. The vertex is moved rather than the shading faked, and only the top pair of corners of each quad — the bottom of a plant is in the ground and stays there.

  Knowing which corners those are looked like it would cost a byte a vertex and it costs nothing. A cross model spans a whole block, so its top and its bottom both sit at whole numbers and the position cannot tell them apart; but the game builds every quad's four corners in one fixed order, and its own table of them gives the same answer for all four vertical faces — corners 0 and 3 are the top pair. So the marker is the corner number, which is already there. The vertex index carries the draw's vertex offset added in and a chunk need not begin on a quad boundary, so where its own count starts rides in the chunk origin's spare fourth float, which was being written as zero. Four sines at unrelated angles, on the same sixteen-block lattice the water waves use and for the same reason. No random phase per plant: neighbours moving independently reads as noise, and a field in wind bends in waves that travel across it.

  Grass was left out of the first build of this by a name. Vanilla sorts blocks by a Material that describes physical behaviour rather than shape, and in this version ordinary grass, ferns, dead bushes, real vines and two-block plants are all Material.VINE together — so the rule that kept vines still, because a vine hangs and the end that must not move is its top, kept every patch of grass in the world still along with them, while the flowers beside it moved. What shape a thing is now comes from the block itself. Leaves, vines, plants taller than one block and lily pads stay still, each for a reason of its own: a leaf block is a solid cube and a lily pad lies flat, so both have four corners at one height and the rule would tear them in half; and a two-block plant has the top of its lower half and the bottom of its upper half at the same height, so only the first would move and the stem would come apart. Those need somewhere to record how far up its own plant a block is, and there is nowhere yet. Cocoa pods and chorus are still for a different reason again — they are boxes rather than crossed quads, fixed to the side of a trunk or joined to each other, so shearing their tops away from their bottoms pulls them off what they grow on. Every one of those exceptions is a vanilla block named outright, so a modded plant that is not a cross of vertical quads will lean where it should not; the setting is off by default and the list is where to add it.

- **Water Waves**, off by default. Nothing is displaced and no geometry is built: the water stays exactly where the game put it, a boat floats where it always did, and the only thing that moves is which way the surface is treated as facing. That is enough, because everything this renderer already knows about water is an answer about that direction — so the sky reflection breaks up along the crests instead of lying flat, and a torch held over the water scatters across it rather than landing as one smooth patch. Four travelling sine waves crossing at unrelated angles, with the slope written out rather than measured: the sines are being evaluated anyway and a cosine of the same argument is free beside them, where a normal taken from screen-space derivatives of a height field would be a measurement of the pixel grid instead of the water. Only the top face waves — the sides of a water block are the walls of the channel it runs in.

  The reflection is measured against a calmer surface than the light is, which the first version got wrong and looked it: from the shore in daylight the water came out banded white and blue rather than rippled. Fresnel near grazing is a cliff, and the same eight degrees of tilt that are barely visible from above swing the mirror from a third of the surface to nearly all of it. Two things are missing from taking one sample of the slope and believing it, and both say the same: a crest seen near grazing hides its own trough, so less of the slope is on show than there is, and a pixel of water out there covers a great many waves, so what it ought to carry is the average of a steep curve rather than that curve at the average — which is flatter, and lower. So the wave is believed in full where the surface faces the eye, where the reflection is weak and nothing can band, and fades to a quarter of itself as the view flattens. The horizon goes back to the smooth mirror it was before waves existed and the banding becomes glitter. Reported the other way round in the same test, and it is the same fact: looking straight down, where Fresnel has nothing to say at all, the first version was invisible, so the brightness a tilted facet gains is more than twice what it was.

  The thing that had to be got right is that a wave must not travel with the player. The shader is given positions relative to the eye and nothing else, so the obvious version has the whole pattern swimming along behind you, and the world position it came from is not available in full and would not help if it were: Minecraft coordinates reach tens of millions, where a 32-bit float can no longer separate one block from the next. Neither is needed, because a phase is periodic. The camera's world position is reduced modulo sixteen blocks on the Java side, in double precision where that is exact, and only the remainder crosses over; adding it back gives the world position shifted by some whole number of sixteens, which every wave here is built to be blind to — each is a whole multiple of a full turn over sixteen blocks, along a direction with whole components. What it costs is that the pattern repeats every sixteen blocks. What it buys is that it stays still while you walk, which is the failure anyone would notice. The same fact is what motion vectors will need later.

- **Foliage lit as what it is.** Grass, flowers, saplings and vines are drawn as two flat quads crossing each other, both standing straight up, and lighting that geometry the way it is written down gives an answer that is exactly wrong in the case you notice: raise a torch over a patch of grass and nothing happens, while the ground beside it brightens as it should. Light arriving from above arrives edge-on to a vertical surface. Vanilla sidesteps the same problem by not shading cross models at all, which is the same admission in another form — the plane is not what the plant is. A tuft of grass is a small volume of scattering material, and what light does to it depends far more on where the light is than on which way any one blade happens to be turned, so the normal is bent most of the way towards standing up and the amount a leaf keeps when turned away is raised: a leaf is thin enough to be lit from behind, and a torch on the far side of a bush lights the bush. A torch above now brightens it, a torch below leaves it dim, a torch beside it lights it, and none of that depends on which of the two crossed quads you happen to be looking at. This is the first thing built on the material tags — nothing could tell grass from a torch before them — and it needs them on, along with dynamic lights and directional light.

- **Material Tags**, off by default, and on its own it changes nothing on screen. It is the groundwork every effect still to come is waiting on. The game draws terrain in four layers and a layer is not a material: TRANSLUCENT is water together with stained glass, CUTOUT is grass together with torches and rails and ladders, and the vertex is 28 bytes of position, colour, texture and light map with nothing left over. Water wants a fresnel term and moving normals, foliage wants to sway and to be lit as a soft volume rather than as the two flat vertical quads it actually is, and a ray needs to know what it passed through — none of them can begin without being able to tell one from the other. The answer exists exactly once, for a moment, in the chunk rebuild loop: it walks the blocks of a chunk and calls the block renderer for each one, so before the call the block is in hand and after it the vertices it wrote are on the end of the layer's buffer. Nowhere later does anything know which block a vertex came from. So the material is recorded against the range of vertices it produced and consecutive blocks of the same material merge into one run — a chunk of stone is one run, a hillside is a handful. Two earlier candidates were both rejected by reading the code rather than by argument: splitting the draw into more batches cannot work, because a batch is a whole chunk layer and the materials are interleaved *inside* one; and widening the vertex would mean building chunk geometry here instead of mirroring the game's, which is the one thing the renderer is built not to do. What the runs become instead is a small buffer *beside* the geometry, one byte per vertex against the twenty-eight already there, leaving the mirrored format untouched — and that is also the shape ray tracing would want it in. This slice records and measures only. Measured with F3+A from a fixed spot, which rebuilds every chunk in view rather than loading new ones — the same 6 700 chunks each press, less than half a percent apart, twice per setting: **2.06 and 1.91 ms a chunk with it off, 1.93 and 1.79 with it on**, which is to say free within the noise. A chunk carries about 1 300 blocks and a chunk layer averages 8.4 runs, and **61% of layers come out as a single plain run** — those need no material data at all, so the buffer the next slice uploads is needed for two layers in five. Flying a route was tried first and is worthless for this: two flights from two teleports made the recording look *faster*, because the second landing had emptier chunks.

- **Directional Light**, half by default. Dynamic light now cares which way a surface is turned. The game's own light is one number per block with no notion of orientation, so a dropped torch lit the underside of the floor it was lying on exactly as brightly as the top of it. The face is worked out from how the world position changes across the screen: every quad in a block model is flat, so the cross product of the two screen-space derivatives is the exact face normal rather than an approximation of it — which matters, because there is no normal to read. The game's block vertex is 28 bytes of position, colour, texture and light map, and adding one would mean building chunks here instead of mirroring the buffer the game already built. It costs two instructions per lit fragment, not one byte of memory, and nothing at all while dynamic lights are off.

  It took five goes to get the shape of this right, and four of them were fixes to real defects that were not the thing being complained about: light source positions taken at tick rate while the camera moved at frame rate, a face normal measured from screen-space derivatives that falls apart as a surface turns edge-on — the symptom for that one appeared with no torch in hand at all, which is what named it — and a cosine falloff that made every lit surface track where the lamp was. The fifth answer was not a fix. A light the player carries drags its own terminator across every nearby face whenever they move, and although that is exactly what a real lamp does, **nothing else in this game behaves that way**, so the eye reads it as the world blinking rather than as a lamp being lifted. So the range came down to vanilla's own: the underside of a block at half the top, which is all the variation Minecraft has ever shown for which way a surface points, and the setting ships at half of that. Turned down to a sixth the effect vanishes entirely; half is where it stops drawing the eye and still says which way a surface faces.

  How much of the light a face receives is not the textbook `max(dot(n, l), 0)`, and one test in a lit room showed both halves of why it should not be. A torch dropped beside a wall one block high left the top of that wall completely black, and jumping with a torch in hand lit the same face up at once. Both are the same edge — the source crossing the plane of the face — and a clipped dot product has nothing to say on either side of it. So the source is treated as a sphere the width of a flame rather than as a point: how far its light wraps past the horizon of a surface is that width over the distance to it, which is a lot when you are standing next to it and almost nothing across the room, where a hard edge is what the eye expects anyway. And the term never reaches zero. Vanilla's own face shading does not either — the underside of a block is drawn at 0.5 of the top, never dark — so a face out of the light dims rather than dropping out of a scene where nothing else ever does. The setting is a percentage rather than a switch because where to stand between vanilla's answer and this one is a matter of taste, judged in a lit room rather than read about.

- **Height Fog**, off by default, and **Height Fog Depth** beside it. The first is how much colour the ground below you gives up to fog; the second is how far down it has to be before it gives up nearly all of it, twenty-four blocks by default — about the floor of a ravine seen from its lip. One says how much, the other how soon, and a first pass with only the first slider was reported as present but weak at 60%, which is what a curve with no control over its own shape looks like. It is a look rather than a fix and its description says so: it fades towards the game's own fog colour and only where the game already has fog, so it cannot invent a haze the sky disagrees with — but it cannot reach entities or particles either, because those are fogged by OpenGL's fixed-function fog, which knows nothing about height. A mob standing in a fogged valley stays clearer than the ground under it. All of these settings are plain numbers in the frame's uniform buffer rather than compiled-in constants, deliberately: a slider that rebuilds a pipeline is a slider that stutters.

- **Extreme Render Distance**, off by default, which lets the render-distance slider go past 64 and up to 128. It is a switch of its own rather than a wider slider because past 64 it stops being more of the same: the game allocates a render chunk for every cell of a (2d+1) x (2d+1) x 16 grid the moment a world loads and keeps all of them, which is 266 256 at 64 and **1 056 784 at 128** — four times the objects, four times the heap and four times the OpenGL buffer names, up front, whether or not there is a world out there to put in them. On top of that a server decides for itself how far it will send chunks, so past its limit the extra grid is paid for and empty. Turning the switch back off pulls the distance down to 64 with it, because leaving it above would run the game at a distance vanilla's own video settings can no longer express — the bar sits pinned at the end and moving it jumps the value, which reads as a bug rather than as a setting.

- **Fast Rebuild Scan**, off by default. The last step of the terrain setup walks every chunk on screen every frame — some 13 400 at render distance 64 and 42 800 at 128 — to find the handful somebody just broke a block in. The answer for each chunk is a single bit, and what makes collecting them expensive is that the bit lives inside a chunk object somewhere else in memory. Those bits are now mirrored into a flat array beside the chunk grid, 33 KiB for a 266 000-chunk grid against the hundreds of megabytes of objects it describes, and the visible list already carries each chunk's grid slot because the visibility search knew it while stepping there. Measured on a matched scene at render distance 64 with the camera still: **0.075 ms a frame down to 0.019**, or 5.6 ns per chunk down to 1.4. Standing still is the cheap case and it is worth saying so, because the loop is doing almost nothing there — nothing is queued for rebuilding and no chunk passes the test. Flying, with 13 600 to 28 900 chunks on screen and the rebuild queue never empty, the same loop measures **0.24 to 0.74 ms a frame, around a tenth of the frame**, at 25 ns per chunk rather than 5.6. It filters rather than replaces — the game is handed an iterator over the chunks that passed and runs its own loop body over them unchanged, in the same order, re-testing the condition it always tested — so the only thing it can get wrong is leaving something off the shortlist, and the rules are one-sided against that: the grid starts with every bit set because a fresh chunk starts dirty, and a chunk whose slot is unknown answers "dirty" rather than being skipped. Both of the two methods that can change the flag are hooked, so a mod marking a chunk is caught as well as the game, and the dirty half is a compare-and-set rather than a plain write because chunk builder threads mark chunks too and sixty-four slots share a word — a lost bit there would be a lost "rebuild me", which is the one failure that leaves stale terrain on screen. Measured again flying the same route with it on and off, matched by how much world the search reached (the runs with it on had slightly more, so the comparison is if anything against it): **0.23 to 0.28 ms a frame down to 0.074 to 0.099**, about three times cheaper, and roughly 5% of the whole frame at 330 fps. The filter itself is 0.031 ms for 16 200 chunks — 1.9 ns each — and it hands back 560 to 1 140 of them, so 96% of the chunks on screen are answered for without the chunk being touched at all. What is left is vanilla's own loop body over the ones that passed, which this does not change.

- **Build Near Chunks Off Thread**, off by default, which is what the measurement above actually found. Vanilla rebuilds any dirty chunk within about 28 blocks of the eye on the render thread, in the middle of setting the frame up, and the frame waits for it. Timed separately from the scan around it, those builds are **0.4 to 0.7 ms a frame in the windows where they happen, 89% to 97% of that section** — and with them queued instead, a flight with none of them left still spends 0.24 to 0.74 ms a frame there, so both halves are real and neither one explains the other. It is the hitch you feel rather than see when you break a block or when terrain loads beside you. Forge already carries a flag for queueing those builds instead; this reaches it from the settings screen and leaves Forge's own setting winning when it is on. What it costs is that a chunk you just changed catches up a frame or two later rather than at once.

- **Frame Time Graph**, off by default, in the bottom-left corner. The framerate the game already shows is frames divided by seconds, and it cannot tell a steady 120 from a 240 that stalls every tenth frame — both average out the same and only one of them is pleasant to play. What separates them is the shape of the distribution, so this reports the worst frames instead of the mean: the **1% low** (the frame time only one frame in a hundred exceeds), the best and worst single frame of the window, and a trace drawn either side of a centre line so a periodic hitch is visible as a pattern rather than inferred from a number. The centre line is the window's average, a quicker frame goes below it and a slower one above, and an even scene draws a flat line — bars standing on the floor spend most of their height repeating the figure printed above them. **Graph Refresh** sets how often the numbers are recomputed, 1 second by default: the trace always moves every frame, but figures that change three hundred times a second cannot be read. Costs nothing while off — not even a timestamp per frame — and measured at **0.067 ms a frame** with it on, about 0.6% of a frame at render distance 64, of which the trace is 0.05 and the text 0.02. That figure is not an estimate: the overlay times itself and the diagnostics report states what it cost, because an instrument that distorts what it measures is worse than none. It is registered independently of Vulkan, since the case where a frame-time graph is most wanted is the one where the renderer did not come up.

- **The settings screen is translated**, following whichever language Minecraft is set to: Russian, Simplified Chinese, German, French, Spanish, Brazilian Portuguese and Japanese, with English underneath. Every row, every description, the tabs, the units and the cost labels — the descriptions in full rather than trimmed, because what they are for is explaining what a setting costs and why it exists. The keys are derived from the English text already in the source rather than written beside it, so an untranslated string reads as English instead of appearing as a blank row or a raw key, and a language file may be partial. `./gradlew runClient -PdumpLang` writes the English file out from the screen as it is actually built, which is how the reference file is produced and how a renamed option is caught before its translations are orphaned.

- **Dynamic Light Distance**, 160 blocks by default, adjustable from 1 to 200. How far away a light source may be and still be drawn. It is worth being precise about what that is not: it is not how far the light reaches. That comes from the source's own level — a torch lights about fifteen blocks around itself whatever this is set to — and raising this will not throw light further. What it decides is whether a distant torch lights the ground it is standing on at all, and a pool of light on the ground is visible from as far away as the ground is. The distinction is here because getting it wrong was a real defect: an early version cut sources off at 24 blocks, reasoning that a level-15 light reaches 15, and lights visibly winked out as you flew away from torches that were still in plain sight. Lowering it costs nothing and saves nothing — every loaded entity is examined either way, and only the nearest 32 sources are ever drawn — so it is there for taste rather than for frames.

### Fixed

- Animated blocks animate again in Vulkan-drawn terrain. The renderer reads the block atlas out of OpenGL once and turns it into an image of its own, and the game goes on writing new frames into its texture for as long as the world is open — so lava, water, fire, portals, sea lanterns, prismarine and magma stood still in the world while the same block held in the hand, drawn by OpenGL from the game's own copy, animated as it always did. It looked exactly like the animation setting having stopped working, which is how it was reported, and that setting was innocent.

  Both the plain frame step and the interpolated one end in the same upload call, with the pixels and the rectangle they belong in, so that is where the frames are taken — rather than asking the sprites afterwards, which would mean knowing which frame is current and would miss a modded sprite that animates its own way. The upload is also used for plenty that is not the atlas, so what decides is which texture is bound at the time, since that is what the call is about to write into. A tick can move a dozen sprites and they leave together in one submission: separately it would be a queue submission and a wait apiece, twenty times a second, for a few kilobytes each. Every mip level is sent rather than only the top one, because the smaller levels are what a distant block samples and leaving them at the frame the world loaded with would make lava change colour as you walked towards it.

- Out-of-bounds buffer reads no longer take the GPU down with them. A session ended with the display driver reporting a page fault at an address no buffer this mod allocates reaches, and the Java stack that came out of it was a red herring: a fence wait returning VK_ERROR_DEVICE_LOST is what a lost device looks like from three frames later, not where it was lost. This renderer mirrors a buffer the game built and indexes a second buffer alongside it by vertex number, and both are reached through an indirect draw's vertexOffset rather than through anything that can be bounds-checked where it is used — so one wrong offset anywhere in that chain was the end of the session rather than a wrong pixel. Vulkan has a core feature for exactly this, always available and costing a bounds check the hardware was built to do, and it is now on: a read past the end of a buffer comes back as zeros. The worst case becomes terrain shaded as though it were made of nothing, which can be seen, said out loud and found.

- Visibility Seed Cache has a row in the settings screen. It has been in the config file and read by the code since 0.6.0, and its entry in this changelog says there is a switch to rule it out, but there was nowhere to reach it from the game — which was found the first time anyone needed it, by someone being told to turn it off. It sits beside Own Visibility Search, because those two are the only things here that can decide a chunk is not reachable, and a missing chunk means testing both. Every other config key whose menu row is named differently was checked at the same time; this was the only one with no row.

- The Mipmap Levels slider no longer reloads every resource in the game, which was crashing it. A full resource reload restarts the sound engine, and this row asked for one on every step of the slider — a slider being something you drag, so a single drag asked for several, a second or so apart. The second sound engine then cannot have an OpenAL context while the first still holds one, the sound loader waits out its thirty-second timeout, and the natives are unloaded from under sound threads that are still calling into them: `UnsatisfiedLinkError`, and the game is gone. Vanilla's own setter has not done this since Forge fixed MC-64581 — it applies the level immediately, marks a flag, and lets one narrow model reload happen when the settings screen closes, which does not touch sound at all. The hand-written copy of that logic is gone and the game's setter is called instead. The row now says the atlas is rebuilt when the screen closes, because that is when it happens.

- The cost of a setting keeps its colour when the description panel does not fit beside the option list. The panel draws CPU, GPU and VRAM as coloured bars and is dropped for a plain tooltip when the window is too narrow in interface units to hold it — which is not a rare case, because at the automatic interface scale on a large display the game picks a very high multiplier and leaves only a few hundred units of width. What was left there was a grey line of text, and a cost with no colour in it says much less at a glance, which is the whole point of stating the three separately.
- Celeritas and Actinium are recognised as renderer replacements, so this mod's Vulkan terrain stands aside for them the way it already does for OptiFine instead of the game failing to start. Reported from the field against 0.6.0, which 0.5.0 did not do: 0.6.0 replaces the game's visibility search and injects into `RenderGlobal.setupTerrain` in three more places, and the renderer half of this mod is marked required, so an injection that cannot be applied stops the game rather than quietly doing less. Two renderers cannot both own the terrain in any case — with either of these installed you get their renderer, plus this mod's settings screen and game-side optimisations. A renderer this build has not heard of can be named the same way without waiting for a release: `-Dvulkanmod112.extraRendererMarkers=part-of-its-jar-name`, which the startup message now says.

### Changed

- The Dynamic Lights description said it lit the terrain only and left entities alone, which stopped being true when mobs, particles and the first-person view each got their own hook. It now also says what it does across a network, because that is the first thing anyone will want to know: nothing is written into the world and nothing is sent anywhere, so mob spawning and daylight sensors are unchanged and it works on any server. Another player carrying a torch lights the ground for you without needing this mod themselves — only the one looking needs it.

## [0.6.0] - 2026-07-28

### Added

- **Own Visibility Search**, on by default. The game decides which chunks are on screen with a flood fill through the chunk graph, and its own profiler puts that at a quarter to a half of the entire frame at render distance 64 — around eight times what drawing the world costs. This replaces the search. Two other ways of attacking it were tried first and both are in this changelog as honest failures: running it less often earns nothing because 97% of the requests come from the camera moving, and computing it more cheaply — a frustum test with 3.4 times fewer dot products, provably the same answer — moved a walked chunk from 163 ns to 139 and did not move the search's share of the frame at all. That second result is what this is built on: if three times less arithmetic buys 15% of a node, the node is not spending its time on arithmetic. It is waiting for memory, and vanilla's inner loop asks for a lot of it — for each of the six neighbours of an accepted chunk it follows a position object held by the current chunk, indexes a 266 000-element array at a scattered place, writes a frame stamp into the object it finds, then follows a second pointer to that object's bounding box. Three cache lines in three unrelated places, six times over, and only one neighbour in six is accepted. None of it has to be touched. A neighbour's position is the current one plus 16 on one axis; a chunk's bounding box is exactly its position out to sixteen blocks; the grid is a torus, so stepping one chunk over is stepping one slot over with a wrap; and the visited stamp is an int in one flat array rather than a field inside every chunk. A rejected neighbour now costs one int and the frustum test, and the chunk object is reached for only when the neighbour is accepted. Measured at render distance 64 on snapshots matched for how much of the world was on screen: **76 fps to 105, and the cost of a walked chunk from 241 ns to 128**. The step that could have disagreed with vanilla silently is deriving a position instead of looking it up, so it is checked rather than argued: a switch makes the search compare every position it derives against the chunk that slot actually holds, and over **707 million derived positions there were no disagreements**. It also never once met a case it does not implement. It ships on: the way a change like this goes wrong is that something quietly stops being drawn, so it was flown before being trusted, and the switch stays there for anyone who sees a chunk missing that comes back when they approach it.

- **Drop Vanilla Chunk Buffers**, off by default. The game stops filling its own chunk buffers once Vulkan holds the geometry, so the world is stored once in video memory instead of twice. Measured at render distance 64 with the mirror holding 31 258 chunk buffers: 1 060 MiB of geometry, and turning the Vulkan renderer off — which refills the vanilla copy — put video memory up by about the same again, from 3.3 to 4.4 GiB. It also takes the second of the two uploads out of the per-frame budget the game reserves for getting chunks onto the card, which is the thing that actually decides how fast a world fills in around you. Vanilla's upload method is four lines — bind, upload, unbind, set the vertex count — and all four are skipped rather than just the upload: leaving the count set on an empty buffer would let any vanilla draw that slipped through read past the end of it, where skipping the count means the worst case is a chunk that draws nothing. Off by default for a specific reason rather than novelty: every failure path in this mod ends in falling back to vanilla rendering, and that works because those buffers hold the world. With them empty it would mean an invisible one, so anything that makes the Vulkan path unavailable — a failure, this setting, the terrain switch — rebuilds every chunk first. That is a pause, and it should be one you chose.
- **Dynamic Lights**, off by default. A carried torch, a dropped glowing block or a burning creature lights the world around it. The usual way to do this is the game's own — write the light level into the world and let the chunk be rebuilt — and this renderer has the measurement to say what that costs: rebuilding chunks is what the frame is already waiting on while the player moves, 330 fps against 120 at render distance 64, so a torch carried at walking pace would rebuild chunks continuously. Nothing is rebuilt here. The sources are collected each frame and the terrain shader adds them while it is already shading the pixel. What makes it look like torchlight rather than a flashlight is that no colour is mixed in: the game's light map is a table whose rows are block and sky light, already carrying the warm cast and whatever the time of day and any installed mod have done to it, so the block-light coordinate is raised and the colour comes from there. The level is read from the block itself, so a modded glowing block carried in hand lights the way without this having to know it exists. Entities, particles and the first-person view each ask for that coordinate from a different place, and all three are raised to match — without which a mob standing in the pool of light from a dropped torch stayed dark, and breaking a block underground threw off a cloud of black specks. The item model held in first person is not yet lit, only what it lights. Two things that plainly glow and have no block form are named directly — a bucket of lava and a blaze rod — because asking the block registry about an item that is not a block returns air. That list is kept as short as it can be, and it does not include redstone blocks or enchantment tables: those give off no light in this game either, which is a fact about Minecraft rather than a gap here.
- **Vulkan Water and Glass**, on by default. The translucent terrain layer is drawn in Vulkan rather than left on the OpenGL path. It is not a speed setting — measured, that layer is 2.6% of a frame either way — and it is here because fog reaches everything this renderer draws and nothing it does not, so water was the one surface staying clear while the blocks around it faded into the distance. It is also what has to happen before the game's own chunk buffers can be dropped, which is where the doubled video memory goes. The layer runs in a pass of its own, after entities: vanilla draws it once entities, particles and weather are already in the frame, and depth-tests it against them with depth writes off. This renderer composites its opaque terrain before any of that, so its depth image holds terrain alone, and the game's depth has to be copied back into it first or water is drawn over anything swimming behind it. Two blends now happen to a water fragment where vanilla does one — into a target of its own, then compositing that target over the frame — so the colour is written premultiplied by its alpha; with straight alpha every overlap would come out too dark.
- **Near Clipping Plane**, 0.1 blocks by default. Vanilla projects the world with a near plane of 0.05 blocks, and depth resolution falls off as the square of distance divided by that number. With the 24-bit depth buffer the game uses, three hundred blocks out it can no longer separate surfaces closer than about 0.107 blocks — and a snow layer sits 0.125 above the block it covers, whose top face is still drawn, because a one-deep layer is not an opaque cube. That is the grey and sand speckling through distant snow from any high vantage point. Raising the plane to 0.1 puts the resolvable gap at 0.054, and confirmed by eye it removes nearly all of the speckling. It shipped at 0.2 first, which also worked but reached far enough to open a view through a wall when standing flush against one; half the distance keeps most of the margin and much less of that. It cannot be set for the Vulkan pass alone, since the depth buffer is shared with OpenGL, which draws entities and water into it from the same matrix, so this changes the game's projection and everything follows. It costs nothing to draw — it is one number in the projection matrix — so it ships on. The price is that geometry nearer to the eye than the plane is clipped away, which can open a hole when the head is inside a block; 0 restores vanilla. Clouds set the projection up themselves and put the world's back afterwards, and below cloud height that happens before the terrain is drawn, so their copy of the number has to move with it or the setting reaches nothing but the view from above the clouds.
- **Visibility Walk Interval**, default off. The game decides which chunks are on screen with a flood fill through the chunk graph, and its own profiler puts that at a quarter to a half of the entire frame at render distance 64 — around eight times what drawing the world costs. It reruns whenever the camera moves, which is fair, and also whenever any chunk is queued for rebuild or finishes uploading, which while a world fills in means every frame even standing perfectly still. This limits only the second case. Measured afterwards, it earns very little: camera movement accounts for some 97% of the requests, and there is little left to skip. Kept because it costs nothing when off and the ceiling is a property of this approach rather than of this build.
- **Visibility Seed Cache**, on by default. The same search reads all 4096 block states of the camera's own chunk section every time it runs, to work out which faces are reachable from the block the eye is in. That answer changes only when the camera moves to a different block or that section is rebuilt, and the cache is keyed on exactly those two things. No measurable framerate change in testing; it is here because hundreds of thousands of redundant block reads a second are worth removing regardless, and there is a switch to rule it out.
- **Chunk Build Threads** (Advanced), default 0 for vanilla behaviour. Vanilla sizes its chunk builder pool from the heap rather than from the processor: `threads = clamp(cores, 1, (maxMemory * 0.3 / 10 MiB) / 5)`, which on a 4 GiB heap caps out at 21 no matter how many cores are present. The setting overrides the cap. Measured honestly, it did not buy frames — +11% peak upload rate for +52% threads, and no change in the framerate — so it ships off by default; chunk building was not the bottleneck. It is kept because the heap ceiling bites hardest exactly where it is least expected, on a machine with many cores and a small heap.

### Performance

- Frames in flight is left at 2. The setting has existed since 0.4.0 with no measurement behind it; measured now at render distance 64, 2 against 3 changed nothing — 72 fps either way at a matched scene size. The reason is visible in the one number that stayed at zero: the wait on the fence is 0.00 ms in every sample. Frames in flight buys the CPU room to run ahead before it has to wait for the GPU, and the CPU never waits, so there is nothing there to buy.
- Boxes are tested against the view frustum by their far corner instead of by all eight. For each clipping plane, vanilla asks whether every one of a box's eight corners is outside it, at three multiplies and three adds per corner — up to forty-eight of those to reject one box. The dot product is linear and separable per axis, so over the corners it is maximised by the one the plane's normal points towards: if that corner is outside, all of them are, and the value computed for it is the same expression vanilla evaluates for the same corner rather than an approximation of it. Checked against vanilla's version over 2.7 million boxes on a real frustum laid out across the render-chunk grid, and over 20 million random cases including planes exactly parallel to an axis: no disagreement, and 3.4 times fewer dot products. This matters because the search that decides which chunks are on screen runs it once for every chunk it reaches — the game's own profiler puts that search at a quarter to a half of the entire frame at render distance 64, against 4.5% for drawing the world. Boxes stretched to infinity, which the search uses to seed itself when the camera is above or below the world, are handed back to vanilla: an infinite coordinate times a plane normal of exactly zero is NaN, and vanilla's chain of comparisons treats that as "not rejected" in a way the corner test would not reproduce. Measured afterwards on a flight at render distance 64, both ways, normalised against the number of chunks the search reached: 163 ns per chunk down to 139, and no visible movement in the search's share of the frame. So this is worth about 6% of a frame rather than the third the arithmetic suggested — what a walked chunk actually costs is one or two cache misses, chasing a pointer into a 266 000-element array and then a second one to a bounding box object, and the arithmetic was being computed in the shadow of that wait. Kept on because it is strictly less work for the same answer, not because it earned its keep. There is a switch, for ruling it out rather than for choosing.
- The indirect draw batches are allocated in memory the GPU owns rather than in ordinary host memory, where the driver offers such a type. The GPU reads both halves of every batch on each frame — a chunk origin per draw in the vertex shader, and the draw commands themselves in the command processor — so at high render distances that was thousands of small reads across PCIe per frame, competing with chunk geometry streaming over the same bus. Cards without resizable BAR expose only a small window of this memory for the whole system, so an allocation refused there falls back to the old behaviour rather than failing.
- The mirror copy now happens on the thread that built the chunk instead of the thread that draws. Vanilla gives chunk uploads a hard budget of a quarter of a frame minus whatever the frame has already spent, and runs them on the render thread because they need the OpenGL context. This mod's half of the work needs neither OpenGL nor a driver call — it is a memcpy into mapped staging — but it rode along on that same thread, so the budget drained twice as fast as vanilla alone. Builder threads now reserve a range in their own half of the staging ring and copy there directly; the render thread picks up the finished copy. No Vulkan call is made off the render thread, because the queue and the command pool belong to it and are not thread-safe. Measured flying into unexplored terrain at render distance 64: **97.4% of 29 940 uploads copied off the render thread, none refused.**
- The mirror is keyed by a dense slot number carried on the chunk's own vertex buffer instead of by its OpenGL buffer name. The old index held an entry for every live buffer, and the game keeps one per layer for every render chunk in the grid — hundreds of thousands at high render distances — so every lookup reached into a random place in a very large array. Command recording for a frame dropped from 0.19–0.21 ms to 0.11 ms at around 10 500 chunks. That is a tenth of a millisecond in a frame of several, so the real value is that it unblocks the change above: on a builder thread the OpenGL name does not exist yet, but the slot does.

### Changed

- The diagnostics report now carries what the F3 overlay shows — the drawn-against-total chunk counts, entities rendered against loaded, particles and block entities — taken from the same methods the overlay calls. A frame breakdown says how much of the frame this mod accounts for; when that answer came back at four percent, nothing in the report said what the other ninety-six were doing.
- A section's share of the whole frame in the profiler tree is now carried down and multiplied instead of being taken from the game's own total. Asking the profiler for a nested path renormalises against that path, so the figure came back identical to the share of the immediate parent: a search that is 94% of a stage that is half the frame printed as 94% of the frame. Every nested line in reports written before this is wrong in that direction.
- The report also writes out the game's own profiler tree, when the profiler is running. The pie chart it normally draws is laid out in interface coordinates, so on a large display it arrives a few hundred pixels across with labels too small to read. Open it with Shift+F3 and the same numbers land in the log as text, three levels deep, with anything under one percent dropped.
- Timings for the parts of the frame this mod does not own: the layers vanilla still draws, and the two methods that walk the visible-chunk list. Between them they turn "the frame goes somewhere else" into a number.
- The diagnostics report states how much of each video memory heap the driver considers spoken for, against how much it is willing to hand out, and says so when the two cross. That is the point where a driver starts moving allocations into system memory, and until now a session that slowed to a crawl with no change in the scene gave no way to tell whether that was happening. The figures cover everything on the machine, not this game alone.

### Fixed

- The cache in front of the visibility search's seed handed out the set it had stored, and the caller removes an element from what it gets back. Standing where exactly one face is reachable — inside a one-block gap — and turning the camera would empty the cached answer, and an empty answer means "sealed in", which draws the chunk under your feet and nothing else. The cache keeps a copy now.
- The mod no longer loads its isolated LWJGL 3 runtime on a Java version it has not been built against, and it no longer scans the game's classpath for LWJGL when it ships its own. LWJGL 3.3 installs its OpenGL function tables by patching the JVM's JNI function table and only knows the layout of JVMs it has seen. On a newer one it prints `Unsupported JVM detected` and then keeps running with a corrupted table: OpenGL calls start returning their own arguments instead of results, and the process dies seconds later anywhere at all. The failure looks like a broken graphics driver, which is why it is worth naming here.
- Interop now says which of the two device checks failed — a genuine mismatch between the OpenGL and Vulkan GPUs, or an inability to read the driver UUID at all. They need different answers from the user and used to produce the same message.

## [0.5.0] - 2026-07-26

### Added

- Fog. The Vulkan-drawn terrain was the one thing in the scene with no fog at all, which is most obvious underwater: fish and mobs take on the colour of the water while the blocks behind them stay perfectly clear. The parameters are read straight out of OpenGL each frame rather than recomputed, because the game changes fog for water, lava, blindness, the void and render distance, and mods add their own. There is a switch for it, on by default.
- Offscreen chunk preloading, **off by default**. Vanilla flood-fills outward from the player's chunk and ANDs a frustum test into every expansion step, so a chunk that needs rebuilding but is not on screen is never even considered — it is discovered from scratch when the camera turns. At render distance 64 the result is a world that fills in along whatever you look at, and narrowing the field of view makes distant chunks appear because it shrinks the competition. The build queue is now topped up from the rest of the grid, but only once the visible chunks are handled, and by scanning a bounded slice per frame rather than sweeping 266 000 entries. Only the build queue is touched; what gets drawn is still decided by the frustum. It is off by default because it is not free work the game was skipping out of laziness: measured at render distance 64, 330 fps without it against 120-140 with, and a world that finishes loading holds all of its geometry in video memory rather than only the part you have looked at. The settings screen states both numbers.

### Performance

- Chunk uploads go through one shared staging ring instead of a persistently mapped staging buffer per chunk. Every mirrored chunk used to cost a `vkAllocateMemory`, a `vkCreateBuffer` and a `vkMapMemory`, and kept its pinned host copy for as long as the chunk lived — as much pinned system memory as the whole world took in VRAM. At render distance 12 that was already 4321 allocations, past the 4096 the Vulkan spec guarantees; at 64 it was tens of thousands, which is where drivers that hold close to the guarantee simply start failing the allocation.
- Growing the shared geometry buffer copies the old contents on the GPU rather than re-uploading every chunk from the host. The old path pushed the entire mirrored world back across PCIe on each growth, and at high render distances there are several growths.
- The translucent layer no longer walks and packs its whole chunk list every frame for nothing. It stays on the vanilla path and the Vulkan side rejected it anyway, but the work was done first and then discarded — at high render distances water and glass make that list long. It was also inflating the drawn-chunk counter with chunks nothing ever drew.
- The lightmap is only uploaded when it changed. It is 256 texels rewritten every single frame together with two layout barriers and a copy, for data that changes at dawn, at dusk and when you walk into a cave. A hash of the array decides.
- The staging ring is 96 MiB rather than 32. Wrapping it blocks the render thread until the GPU has drained it, so what matters is the interval between wraps, not the size of one upload — this is roughly 2000 chunks of headroom instead of 600.
- Offscreen preloading only tops the build queue up once it has run dry, instead of whenever it is short. Keeping it topped up meant vanilla's chunk builder never idled, which on a CPU that is already the bottleneck is a cost paid every frame for chunks nobody is looking at yet.
- The terrain launch flag is read once instead of on every layer. `System.getProperty` locks the global property table.
- Chunk copies are recorded as one `vkCmdCopyBuffer` carrying every region instead of one call per chunk, and the buffer handed to the mirror is no longer duplicated. Both sit on the path taken by every chunk the game uploads, which is a burst of dozens each time the camera turns.

### Fixed

- The upload fence was created signalled and never reset before its first submission. `vkQueueSubmit` requires an unsignalled fence, and every wait on it afterwards returned immediately without the GPU having finished anything — so the upload command buffer was reset while still executing and its staging memory was reused underneath it. The symptom would have been corrupt geometry or a lost device with no reproducible pattern.
- A geometry buffer replaced by a larger one is now freed only once every frame that could still name it has completed. Draws bind the buffer handle by value when they are recorded, so destroying it at replacement time could hand a destroyed handle to a submit.
- Freed ranges in the geometry buffer are merged with their neighbours. Without that, flying around left the buffer as thousands of small adjacent holes that no rebuilt chunk fitted into, growing the buffer while the space was already there.
- Zooming back out left the world drawn as the narrow cone it was during the zoom, until something made the player turn. RenderGlobal rebuilds its visible-chunk list only when the player moves or turns — the field of view is not part of that condition — so a list rebuilt while zoomed in stayed in use after the view had widened again. Easing outward now invalidates it.
- Zoom is eased over about a tenth of a second instead of snapping, timed off the wall clock. The state is decided on the 20 Hz tick, and interpolating on that clock is what made the transition look stepped. Mouse sensitivity now follows the same curve rather than jumping at the tick boundary.

### Investigated, not a defect here

- Brightness underwater changes in visible steps. The game recomputes the lightmap once per tick, so it arrives as twenty steps a second no matter the framerate, and the ramp underwater is continuous. Confirmed by turning the Vulkan terrain off entirely: the stepping is identical on vanilla rendering. The diagnostics report now counts how often the lightmap actually changes, so this does not have to be re-argued.

## [0.4.0] - 2026-07-26

### Added

- Hold-to-zoom, bound to **C** by default and rebindable under Controls, like OptiFine's. Mouse sensitivity is scaled to match while the key is held — at a quarter of the field of view an unchanged sensitivity sweeps the view four times as far for the same hand movement — and the original is restored on release, on opening a screen and on losing window focus, so a temporary value can never be saved to options.txt. Works even where the Vulkan renderer falls back to OpenGL.
- Presets: Stable, Balanced and Performance, on the Rendering page. Stable is what the mod ships with and what a fresh install uses; the other two trade progressively more detail for frames. A preset is a one-shot write rather than a mode, so anything changed afterwards stays changed.
- Settings now state what they cost on the CPU, the GPU and in VRAM separately, as three bars in the description panel. Which resource is short decides whether a setting will help at all, and a single "impact" rating hid exactly that.
- Geometry budget, on the Advanced page. It sets how much video memory the world geometry may take before the renderer stops growing its buffer generously — every growth stops the GPU and re-uploads every chunk, so a card with memory to spare can buy those stutters away, and a small one can keep the footprint tight. Automatic uses a quarter of what the GPU reports. Chunks are never dropped to stay inside it; the budget steers growth rather than capping it.
- Frames in flight is configurable (1–3, default 2) instead of fixed at 2.
- Reset button, restoring this mod's settings to their defaults. Minecraft's own settings are left alone.
- The GPU's device-local memory is shown in the settings header and recorded in the diagnostics log.

### Fixed

- Terrain layers no longer stop at 4096 chunks. The indirect batch was a fixed size and everything past it was silently dropped, so at high render distances the world had holes and the GPU was handed less geometry than the scene contained. The batch now grows to fit the largest layer.

### Performance

- The alpha test is compiled out of the SOLID pipeline through a specialisation constant. Its cutoff is 0.0, so the test never fired, but a `discard` anywhere in the shader makes the hardware disable early depth testing for the whole pass — and SOLID is both the bulk of the terrain and the layer with the most overdraw. The CUTOUT layers keep the test, from the same shader modules.
- Chunk lookups are resolved once per layer instead of once per chunk, and the mirror's index no longer boxes an `Integer` for every one of them. At render distance 64 that was tens of thousands of locked, boxed lookups per frame on the thread that has to finish the frame.
- The largest mirrored chunk is tracked as it is uploaded instead of being recomputed by scanning every mirrored chunk once a frame.

## [0.3.0] - 2026-07-26

### Added

- GPU timings. Timestamp queries around the terrain pass are read back a frame late and reported alongside the CPU breakdown, so optimisation work can be measured instead of guessed at.
- Ultra logging: a full diagnostics report written to `logs/vulkanmod112-diagnostics.log`, covering versions, installed mods, GPU and driver, every active renderer path and a periodic snapshot of frame costs and settings.
- Animated block textures can be turned off, which vanilla offers no way to do.
- Background framerate cap. A minimised window with the frame limit on "unlimited" kept the GPU at full load drawing frames nobody could see; it now sleeps to 10 fps by default while the window is not active.

- Settings screen rebuilt in VulkanMod's own shape: page tabs, a scrolling list of grouped options, and a panel describing the option under the cursor together with how much it is worth in frames.
- Game-side optimisations with their own page. Entity and block-entity draw distances are capped independently of vanilla's per-object limits, and the vanilla settings that matter most for framerate are reachable without leaving the screen.

### Fixed

- The Video Settings entry point no longer lands on top of the options list; it sits in the free strip above it.

### Also in 0.3.0

- Windows support for the zero-copy path: external memory and semaphores are exported as Win32 handles there and as file descriptors on Linux, chosen at runtime by a single platform layer. Linux behaviour is unchanged.
- Block atlas mip levels. The levels Minecraft already builds per sprite are copied into the Vulkan image, and the atlas sampler filters between them, which removes distant-chunk shimmer and cuts texture-cache misses.

### Changed

- Terrain depth is composited with `glBlitFramebuffer` instead of a `gl_FragDepth` write, so the fullscreen composite keeps early-Z. The depth target is 24-bit where the driver supports it; otherwise, or if the driver rejects the blit, the previous shader path is used automatically.

### Documentation

- `ROADMAP.md` lists the remaining work found while reviewing the renderer, in the order it should be done, including two limits that currently cap render distance.

## [0.2.0] - 2026-07-24

### Added

- OptiFine-style **VulkanMod112 Settings...** entry in Video Settings.
- Config file at `config/vulkanmod112.cfg`, with live terrain and diagnostic-overlay switches.
- Render-distance slider from 2 to 64 chunks. The vanilla render-distance limit is raised to 64 on startup.

### Changed

- Documented manual installation, the MixinBooter runtime dependency, the same-GPU requirement and high-distance caveats.

## [0.1.0] - 2026-07-24

### Fixed

- Restored compilation of the per-frame lightmap staging path.
- Select the correct staging buffer for each frame in flight and release every staging allocation and fence.
- Extract the LWJGL 3 OpenGL native companion required by GL/Vulkan interop.

### Performance

- Move mirrored chunk draw buffers from host-visible memory to device-local VRAM.
- Batch VBO copies into one Vulkan submission before each terrain frame instead of synchronising an upload per chunk.
- Suballocate all chunk VBOs in a shared growable vertex buffer, reducing per-frame vertex-buffer binds to one per terrain layer.
- Align each shared-buffer allocation to the 28-byte vanilla vertex stride, fixing corrupted UV/colour attributes after small or empty VBOs.

### Added

- Safe automatic fallback when OptiFine or legacy shader renderer classes are detected.
- Runtime requirements, diagnostic flags and current renderer scope in the README.
