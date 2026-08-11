package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;
import net.vulkanmod112.client.gui.VActionOption;
import net.vulkanmod112.client.gui.VCyclingOption;
import net.vulkanmod112.client.gui.VOption;
import net.vulkanmod112.client.gui.VOption.Cost;
import net.vulkanmod112.client.gui.VOption.Level;
import net.vulkanmod112.client.gui.VOptionBlock;
import net.vulkanmod112.client.gui.VOptionPage;
import net.vulkanmod112.client.gui.VRangeOption;
import net.vulkanmod112.client.gui.VSwitchOption;

/**
 * Contents of the settings screen.
 *
 * Vanilla-backed rows go through GameSettings the same way the vanilla video
 * screen does, so anything the game does on change (resource reloads, renderer
 * refreshes) still happens. The rest are this mod's own settings.
 *
 * Each row carries what it costs on the CPU, the GPU and in VRAM, because
 * which of the three is the bottleneck decides whether a setting will help at
 * all — lowering entity distance does nothing for someone whose GPU is
 * saturated, and neither does mipmapping for someone whose CPU is.
 */
final class VulkanOptions {

    /** Order matches the labels below; 0 means "work it out from the GPU". */
    private static final int[] BUDGET_VALUES = {0, 256, 512, 1024, 2048, 4096};
    private static final VCyclingOption.Choices BUDGET_LABELS = VCyclingOption.Choices.of(
            "Auto", "256 MiB", "512 MiB", "1 GiB", "2 GiB", "4 GiB");

    private VulkanOptions() {
    }

    static VOptionPage[] buildPages(final Minecraft mc) {
        return new VOptionPage[]{
                renderingPage(mc),
                shadersPage(mc),
                rayTracingPage(mc),
                optimizationsPage(mc),
                qualityPage(mc),
                advancedPage(mc)
        };
    }

    private static VOptionPage renderingPage(final Minecraft mc) {
        return new VOptionPage("Rendering",
                new VOptionBlock("Presets",
                        new VActionOption("Stable",
                                "Everything back to the values this mod ships with. This is the safe "
                                        + "starting point: nothing is traded away for speed, and it "
                                        + "behaves the same on every driver. Minecraft's own settings "
                                        + "are left alone.",
                                Cost.FREE, "Apply",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        VulkanPresets.stable();
                                    }
                                }),
                        new VActionOption("Beautiful",
                                "Everything this mod can add, turned on: directional light, height "
                                        + "fog, water that reflects and moves, swaying leaves, glow "
                                        + "and ambient occlusion — with fancy graphics, full "
                                        + "particles and the view capped at twelve chunks. It used "
                                        + "to ask for thirty-two, which bought this preset nothing: "
                                        + "its effects are paid per pixel, while rebuilding chunks "
                                        + "at that range is what put a stutter in it. Screen "
                                        + "reflections are set well below their maximum on purpose: "
                                        + "they are the newest effect here and the least settled. "
                                        + "Expect to lose frames.",
                                Cost.of(Level.MEDIUM, Level.HIGH, Level.MEDIUM), "Apply",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        VulkanPresets.beautiful(mc);
                                    }
                                }),
                        new VActionOption("Balanced",
                                "Caps the draw distances vanilla leaves far wider than anyone can "
                                        + "actually see, and thins out particles. Costs almost nothing "
                                        + "visually and is the biggest easy win on busy worlds.",
                                Cost.FREE, "Apply",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        VulkanPresets.balanced(mc);
                                    }
                                }),
                        new VActionOption("Performance",
                                "Trades looks for frames: short entity distances, no texture "
                                        + "animation, minimal particles, fast graphics. The world will "
                                        + "visibly lose detail — this is the one to pick when the "
                                        + "framerate matters more than the view.",
                                Cost.FREE, "Apply",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        VulkanPresets.performance(mc);
                                    }
                                }),
                        new VActionOption("Potato",
                                "For a machine this game is too heavy for. Everything Performance "
                                        + "gives up, plus smooth lighting, mipmaps, clouds and eight "
                                        + "chunks of view — and a sixty frame ceiling with vsync on. "
                                        + "The ceiling is the point: above the refresh rate of the "
                                        + "screen, extra frames are heat and fan noise for pictures "
                                        + "nobody sees. The world will look plainly worse. Pick this "
                                        + "one only if the game is currently unplayable.",
                                Cost.FREE, "Apply",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        VulkanPresets.potato(mc);
                                    }
                                })),
                new VOptionBlock("Profiles",
                        new VActionOption("Saved Configurations",
                                "Open the list of saved configurations. A profile is your own "
                                        + "settings kept whole and given a name — everything this "
                                        + "mod owns, plus the game's own render distance, graphics "
                                        + "quality, particles and mipmaps, because those cost more "
                                        + "frames than anything here does. The point is going back "
                                        + "to a configuration without having to remember what was "
                                        + "in it.",
                                Cost.FREE, "Open",
                                new VActionOption.Action() {
                                    @Override
                                    public void run() {
                                        mc.displayGuiScreen(new GuiVulkanProfiles(mc.currentScreen));
                                    }
                                })),
                new VOptionBlock("Vulkan",
                        new VSwitchOption("Vulkan Terrain",
                                "Draw the opaque world through Vulkan instead of OpenGL. "
                                        + "Turning it off returns to vanilla rendering immediately. "
                                        + "The Vulkan path keeps a second copy of the world geometry "
                                        + "in video memory, which is where the VRAM cost comes from.",
                                Cost.of(Level.MEDIUM, Level.HIGH, Level.HIGH), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isTerrainEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setTerrainEnabled(value);
                                    }
                                })),
                new VOptionBlock("View",
                        new VSwitchOption("Extreme Render Distance",
                                "Let the slider below go past 64, up to 128. Read the number before "
                                        + "reaching for it: the game builds a render chunk for every "
                                        + "cell of a (2d+1) x (2d+1) x 16 grid as soon as the world "
                                        + "loads, and keeps all of them — 266 256 at 64, 1 056 784 at "
                                        + "128. That is four times the objects and four times the "
                                        + "memory before a single block is drawn, whether or not "
                                        + "there is anything out there to put in them, and a server "
                                        + "decides for itself how far it will send chunks at all. "
                                        + "Turning this off pulls the distance back to 64.",
                                Cost.of(Level.HIGH, Level.NONE, Level.HIGH),
                                "Only raises the limit; the distance below is what spends it.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isExtremeRenderDistance();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setExtremeRenderDistance(value);
                                        RenderDistanceLimit.apply();
                                    }
                                }),
                        new VRangeOption("Render Distance",
                                "How far chunks are drawn. Beyond 32 the vanilla chunk grid itself "
                                        + "costs a lot of CPU and RAM before this mod sees anything, "
                                        + "and servers may cap it anyway. The single most expensive "
                                        + "setting in the game, on all three resources at once.",
                                Cost.of(Level.HIGH, Level.HIGH, Level.HIGH), null,
                                2, RenderDistanceLimit.NORMAL,
                                new VRangeOption.Ceiling() {
                                    @Override
                                    public int max() {
                                        return RenderDistanceLimit.max();
                                    }
                                },
                                1, " chunks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.renderDistanceChunks;
                                    }

                                    @Override
                                    public void set(int value) {
                                        mc.gameSettings.renderDistanceChunks = value;
                                        mc.gameSettings.saveOptions();
                                    }
                                }),
                        new VRangeOption("Max Framerate",
                                "Frame cap. 260 means unlimited. A cap below your display's refresh "
                                        + "rate lowers load, heat and fan noise without costing you "
                                        + "anything you could see.",
                                Cost.of(Level.HIGH, Level.HIGH, Level.NONE), null,
                                10, 260, 10, " fps", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.limitFramerate;
                                    }

                                    @Override
                                    public void set(int value) {
                                        mc.gameSettings.limitFramerate = value;
                                        mc.gameSettings.saveOptions();
                                    }
                                }),
                        new VSwitchOption("Frame Time Graph",
                                "Draw a frame-time graph in the bottom-left corner: one bar per "
                                        + "frame over the last couple of seconds, with the best and "
                                        + "worst single frame and the 1%% low — the frame time that "
                                        + "only one frame in a hundred exceeds. The framerate the "
                                        + "game already shows is frames divided by seconds, and it "
                                        + "cannot tell a steady 120 from a 240 that stalls every "
                                        + "tenth frame; those two average out the same and only one "
                                        + "of them is pleasant to play. Nothing is recorded at all "
                                        + "while this is off, and with it on the whole graph is one "
                                        + "draw call. The diagnostics log states what drawing it "
                                        + "actually cost, rather than leaving that to be believed.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isFrameGraph();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setFrameGraph(value);
                                    }
                                }),
                        new VCyclingOption("Graph Corner",
                                "Which corner the frame time graph sits in. The default is the "
                                        + "bottom left, and so is the chat window — a readout over "
                                        + "what you are reading is a tool nobody leaves on. The two "
                                        + "lines of numbers move to the other side of the graph in "
                                        + "the top corners, so nothing runs off the screen.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                VCyclingOption.Choices.of("Bottom Left", "Bottom Right", "Top Left", "Top Right"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getFrameGraphCorner();
                                    }

                                    @Override
                                    public void set(int index) {
                                        VulkanConfig.setFrameGraphCorner(index);
                                    }
                                }),
                        new VRangeOption("Graph Refresh",
                                "How often the numbers above the frame graph are recomputed. The "
                                        + "trace itself always moves every frame — this is only the "
                                        + "text, and figures that change three hundred times a "
                                        + "second cannot be read at all. Lower it if you want the "
                                        + "worst frame reported the moment it happens rather than "
                                        + "at the end of the second it happened in.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Only does anything while Frame Time Graph is on.",
                                100, 5000, 100, " ms", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getFrameGraphInterval();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setFrameGraphInterval(value);
                                    }
                                }),
                        new VSwitchOption("Fog",
                                "Fade the Vulkan-drawn world into the distance the way the rest of "
                                        + "the scene already does. Without it the terrain is the "
                                        + "one thing in view with no fog at all, which shows up "
                                        + "worst underwater: fish and mobs take on the colour of "
                                        + "the water while the blocks behind them stay perfectly "
                                        + "clear. Off leaves the world ending in a hard edge and is "
                                        + "very slightly faster.",
                                Cost.gpu(Level.LOW), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isFogEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setFogEnabled(value);
                                    }
                                }),
                        new VSwitchOption("Zoom",
                                "Hold the zoom key to narrow the field of view, the way OptiFine "
                                        + "does it. Mouse sensitivity is scaled to match while it is "
                                        + "held, otherwise the view would sweep across the screen far "
                                        + "too fast to aim with. Rebind the key under Controls.",
                                Cost.FREE, null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isZoomEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setZoomEnabled(value);
                                    }
                                }),
                        new VRangeOption("Zoom Level",
                                "How far the zoom key narrows the field of view. 4 means a quarter "
                                        + "of it, which is what OptiFine uses.",
                                Cost.FREE, null, 2, 10, 1, "x", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return (int) VulkanConfig.getZoomFactor();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setZoomFactor(value);
                                    }
                                }),
                        new VSwitchOption("VSync",
                                "Lock the framerate to the monitor's refresh rate. Removes tearing, "
                                        + "and caps FPS at your refresh rate.",
                                Cost.of(Level.MEDIUM, Level.MEDIUM, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return mc.gameSettings.enableVsync;
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        mc.gameSettings.enableVsync = value;
                                        // What vanilla's own toggle does.
                                        org.lwjgl.opengl.Display.setVSyncEnabled(value);
                                        mc.gameSettings.saveOptions();
                                    }
                        })));
    }


    private static VOptionPage optimizationsPage(final Minecraft mc) {
        return new VOptionPage("Optimizations",
                new VOptionBlock("Entities",
                        new VRangeOption("Entity Distance",
                                "Stop drawing mobs, items and other entities past this distance. "
                                        + "Vanilla uses a per-entity limit that is often far larger "
                                        + "than you can see. The biggest win in crowded worlds, mob "
                                        + "farms and busy servers — and almost entirely a CPU one, "
                                        + "since each entity is decided and submitted one at a time.",
                                Cost.of(Level.HIGH, Level.MEDIUM, Level.NONE), null,
                                0, 256, 8, " blocks", "Vanilla",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getEntityDistance();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setEntityDistance(value);
                                    }
                                }),
                        new VRangeOption("Block Entity Distance",
                                "Same limit for chests, signs, banners and other blocks with their "
                                        + "own renderer. These are drawn one by one, so a low limit "
                                        + "helps a lot in storage rooms.",
                                Cost.of(Level.HIGH, Level.MEDIUM, Level.NONE), null,
                                0, 128, 8, " blocks", "Vanilla",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTileEntityDistance();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setTileEntityDistance(value);
                                    }
                                }),
                        new VSwitchOption("Entity Shadows",
                                "The dark blob under every entity. Each one is an extra draw.",
                                Cost.of(Level.LOW, Level.LOW, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return mc.gameSettings.entityShadows;
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        mc.gameSettings.entityShadows = value;
                                        mc.gameSettings.saveOptions();
                                    }
                                })),
                new VOptionBlock("Chunks",
                        new VRangeOption("Chunk Build Threads",
                                "How many threads turn blocks into geometry. Vanilla sizes this pool "
                                        + "from the heap rather than from the CPU and takes the smaller "
                                        + "of the two, so a large processor builds chunks with part of "
                                        + "itself idle. Building — not drawing — is what the frame waits "
                                        + "for at long render distances. Extra threads share the same "
                                        + "build buffers, whose count the heap still decides, so past a "
                                        + "point more of them simply wait. Applies on the next world load.",
                                Cost.of(Level.HIGH, Level.NONE, Level.LOW), "Next world load",
                                0, 64, 1, " threads", "Vanilla",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getChunkBuildThreads();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setChunkBuildThreads(value);
                                    }
                                }),
                        new VRangeOption("Near Clipping Plane",
                                "How close to the eye the world starts being drawn, in hundredths of "
                                        + "a block. Vanilla uses 5, and that is what makes distant "
                                        + "snow speckle grey and sand through the white: the depth "
                                        + "buffer's precision falls off with the square of distance "
                                        + "divided by this number, and three hundred blocks out it "
                                        + "can no longer separate a snow layer from the block under "
                                        + "it, whose top face is still drawn. 20 puts the resolvable "
                                        + "gap comfortably under that, and it is the default here. "
                                        + "The price is that anything closer to the eye than this is "
                                        + "clipped away, so with your head inside a block a large "
                                        + "value can open a hole in it. Set it to 0 for vanilla.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                0, 50, 1, "/100 block", "Vanilla (5)",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getNearPlaneHundredths();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setNearPlaneHundredths(value);
                                    }
                                }),
                        new VSwitchOption("Flat Block Colours",
                                "Draw every block face in one flat colour instead of its texture. "
                                        + "The block atlas is kept at its smallest size, where a "
                                        + "sprite has been reduced to a single dot, and every face "
                                        + "reads that one dot — the cheapest a texture read can "
                                        + "possibly be, and worth real frames on a machine whose "
                                        + "memory is shared with the processor. The world stops "
                                        + "having textures, so this is for making an unplayable "
                                        + "game playable rather than for looks. Note that it is "
                                        + "not the same as turning mipmaps off, which sounds "
                                        + "similar and does the opposite: without them the distant "
                                        + "world reads the full-size atlas at random and gets "
                                        + "slower, not faster.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isFlatBlockColours();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setFlatBlockColours(value);
                                    }
                                }),
                        new VSwitchOption("Drop Vanilla Chunk Buffers",
                                "Stop filling the game's own chunk buffers once Vulkan has the "
                                        + "geometry. The world is currently held twice in video "
                                        + "memory, once for each renderer, and this removes one of "
                                        + "the copies — the single largest saving there is at high "
                                        + "render distances. It also takes the second upload out "
                                        + "of the budget the game reserves each frame for getting "
                                        + "chunks onto the card, which is what really decides how "
                                        + "fast a world fills in around you. Measured at 900 MiB "
                                        + "saved on one world. On by default now that water goes "
                                        + "through Vulkan on every card; what it costs is that "
                                        + "falling back to vanilla rendering has to rebuild every "
                                        + "chunk first, which it will do — a pause, not a hole in "
                                        + "the world.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isDropVanillaBuffers();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setDropVanillaBuffers(value);
                                    }
                                }),
                        new VSwitchOption("Vulkan Particles",
                                "Draw particles with Vulkan. Where every particle is and what it "
                                        + "looks like is still decided entirely by the game — this "
                                        + "changes how the finished quads reach the card. The old "
                                        + "way hands them over as pointers into ordinary memory, "
                                        + "which the driver must copy in full before it can start "
                                        + "drawing, and the game lets itself keep up to sixteen "
                                        + "thousand particles in each of six lists. They join the "
                                        + "pass that already draws water, so nothing extra is "
                                        + "moved between the two renderers. Needs Vulkan Water and "
                                        + "Glass, which is the pass they ride in.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isVulkanParticles();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setVulkanParticles(value);
                                    }
                                }),
                        new VSwitchOption("Vulkan Rain and Snow",
                                "The same for weather. A switch of its own so that either can be "
                                        + "ruled out without the other — rain is built by very "
                                        + "different code from particles and only shares the way it "
                                        + "is drawn.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isVulkanWeather();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setVulkanWeather(value);
                                    }
                                }),
                        new VSwitchOption("Vulkan Water and Glass",
                                "Draw the translucent layer in Vulkan instead of leaving it to "
                                        + "OpenGL. Not a speed setting — measured, that layer is "
                                        + "2.6% of a frame either way. What it changes is that fog "
                                        + "reaches all of the terrain: everything this mod draws "
                                        + "fades into the distance, and water, being the one "
                                        + "surface still drawn the old way, stays clear while the "
                                        + "blocks around it do not. It also has to happen before "
                                        + "the game's own chunk buffers can be dropped, which is "
                                        + "where the video memory saving lives. Off by default "
                                        + "while it is new; turn it off again if water looks "
                                        + "wrong against entities.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isVulkanTranslucent();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setVulkanTranslucent(value);
                                    }
                                }),
                        new VSwitchOption("Fast Frustum Test",
                                "Decide whether something is off screen from its far corner "
                                        + "instead of from all eight. It is the same answer by the "
                                        + "same arithmetic, not an approximation: the corner "
                                        + "furthest along a clipping plane is the last one to "
                                        + "leave it, so if that one is outside then all of them "
                                        + "are. Vanilla evaluates up to forty-eight corner "
                                        + "positions to reject a single box, and the search that "
                                        + "decides which chunks are on screen does this once for "
                                        + "every chunk it reaches — which its own profiler puts at "
                                        + "a quarter to a half of the frame at high render "
                                        + "distances. On by default; the switch is here to rule it "
                                        + "out, not to choose.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isFastFrustumTest();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setFastFrustumTest(value);
                                    }
                                }),
                        new VSwitchOption("Own Visibility Search",
                                "Decide which chunks are on screen with this mod's search instead "
                                        + "of the game's. Same answer, run just as often — the "
                                        + "difference is that vanilla follows a chain of pointers "
                                        + "for every neighbour it tests, and this reads flat "
                                        + "arrays instead. The game's own profiler puts its "
                                        + "version at a quarter to a half of the entire frame at "
                                        + "render distance 64, against 4.5% for drawing the world, "
                                        + "so it is the largest single item there is. Measured at "
                                        + "that distance with the same amount of world on screen: "
                                        + "76 fps to 105. The step that could have gone wrong "
                                        + "silently is working a chunk's position out instead of "
                                        + "looking it up, and that was checked against the game's "
                                        + "own answer 707 million times without a disagreement. "
                                        + "Turn it off if you ever see a chunk missing that comes "
                                        + "back when you approach it.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isOwnVisibilityWalk();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setOwnVisibilityWalk(value);
                                    }
                                }),
                        new VSwitchOption("Visibility Seed Cache",
                                "Remember which way out of the camera's own chunk the search is "
                                        + "allowed to start. Working that out means reading all "
                                        + "4096 block states of the section the eye is in, and the "
                                        + "search does it every time it runs — which while the "
                                        + "world fills in is every frame. The answer changes only "
                                        + "when the camera moves to a different block or that "
                                        + "section is rebuilt, and both are what it is keyed on. No "
                                        + "measurable framerate change in testing; it is here "
                                        + "because hundreds of thousands of repeated block reads a "
                                        + "second are worth removing whether or not they show up in "
                                        + "a frame time. Turn it off alongside Own Visibility "
                                        + "Search if a chunk is missing from one spot and comes "
                                        + "back when you step off it — this is the other thing that "
                                        + "can decide a chunk is not reachable.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isVisibilitySeedCacheEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setVisibilitySeedCacheEnabled(value);
                                    }
                                }),
                        new VSwitchOption("Fast Rebuild Scan",
                                "Hand the last step of the terrain setup only the chunks it can do "
                                        + "anything with. That step walks every chunk on screen "
                                        + "every frame — around 8 600 of them at render distance "
                                        + "64, and the game's own profiler puts it at 19% of the "
                                        + "frame — to find the handful somebody just broke a block "
                                        + "in. The answer for each chunk is one bit, and what makes "
                                        + "it expensive is that the bit lives inside a chunk object "
                                        + "somewhere else in memory; it is kept in a flat array "
                                        + "beside the grid now, small enough to sit in the "
                                        + "processor's own cache. Off by default because it "
                                        + "replaces the game's own logic, and the way that goes "
                                        + "wrong is that a chunk stops being rebuilt.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Own Visibility Search on; the game's own search does not "
                                        + "record where a chunk sits in the grid.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isFastRebuildNear();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setFastRebuildNear(value);
                                    }
                                }),
                        new VSwitchOption("Build Near Chunks Off Thread",
                                "Queue a chunk that changed close to you for a builder thread "
                                        + "instead of rebuilding it on the thread that draws. The "
                                        + "game rebuilds anything dirty within about 28 blocks of "
                                        + "your eye right there in the middle of setting the frame "
                                        + "up, and the frame waits for it — measured at 0.4 to 0.7 "
                                        + "ms a frame while it is happening, which is nearly "
                                        + "everything that step costs. That is the hitch you feel "
                                        + "rather than see when you break a block or when terrain "
                                        + "loads next to you. What you pay is that the chunk you "
                                        + "just changed catches up a frame or two later instead of "
                                        + "at once. Forge has the same switch, and it wins if you "
                                        + "have already turned it on there.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isBuildNearOffThread();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setBuildNearOffThread(value);
                                    }
                                }),
                        new VSwitchOption("Preload Offscreen Chunks",
                                "Let chunks behind you be built too. Vanilla only ever schedules "
                                        + "chunks that are on screen right now, so at high render "
                                        + "distances the world fills in along whatever you look at, "
                                        + "and turning around means waiting again. This tops the "
                                        + "build queue up from the rest of the grid once the visible "
                                        + "chunks are handled, so they never lose their place in "
                                        + "line. Off by default, and the reason is worth knowing "
                                        + "before you turn it on: measured at render distance 64, "
                                        + "330 fps without it against 120-140 with. Filling the "
                                        + "world in is not free work the game was skipping out of "
                                        + "laziness — it is continuous chunk building, and it also "
                                        + "means video memory reaches what the distance really "
                                        + "implies instead of only what you have looked at. Worth it "
                                        + "if you would rather the world were there than have the "
                                        + "frames.",
                                Cost.of(Level.HIGH, Level.NONE, Level.MEDIUM), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isChunkPreloadEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setChunkPreloadEnabled(value);
                                    }
                                }),
                        new VRangeOption("Preload Queue",
                                "How many chunks the preloader keeps waiting to be built. This is "
                                        + "the size of the trade above: more of them fills the "
                                        + "world in faster and takes more of the frame while it "
                                        + "does. The measurement that made preloading off by "
                                        + "default was taken at 16, so that is where a machine "
                                        + "with processor to spare should start looking rather "
                                        + "than where it should stay.",
                                Cost.of(Level.HIGH, Level.NONE, Level.MEDIUM),
                                "Needs Preload Offscreen Chunks on.",
                                4, 128, 4, "", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getPreloadQueue();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setPreloadQueue(value);
                                    }
                                }),
                        new VRangeOption("Preload Scan",
                                "How much of the chunk grid the preloader looks through each "
                                        + "frame while hunting for something to build. The grid at "
                                        + "render distance 64 is a quarter of a million cells, so "
                                        + "sweeping all of it in one frame would trade a slow fill "
                                        + "for a stutter; this is how much of that walk is paid "
                                        + "for per frame, resuming where it stopped. Raising it "
                                        + "finds work sooner in a world that is mostly built "
                                        + "already, where most of what is scanned needs nothing.",
                                Cost.of(Level.MEDIUM, Level.NONE, Level.NONE),
                                "Needs Preload Offscreen Chunks on.",
                                512, 32768, 512, "", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getPreloadScan();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setPreloadScan(value);
                                    }
                                })),
                new VOptionBlock("Window",
                        new VRangeOption("Background FPS Limit",
                                "Framerate while the window is minimised or in the background. With "
                                        + "the frame cap on unlimited the game otherwise keeps the GPU "
                                        + "at full load drawing frames nobody sees.",
                                Cost.of(Level.HIGH, Level.HIGH, Level.NONE), null,
                                0, 60, 5, " fps", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getBackgroundFpsLimit();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setBackgroundFpsLimit(value);
                                    }
                                })),
                new VOptionBlock("Textures",
                        new VSwitchOption("Animated Textures",
                                "Water, lava, fire, portals and every animated modded block upload a "
                                        + "new frame every tick, on screen or not. Turning them off is "
                                        + "a straight win in modpacks; the blocks just stop moving.",
                                Cost.of(Level.MEDIUM, Level.MEDIUM, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.areAnimationsEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setAnimationsEnabled(value);
                                    }
                                })),
                new VOptionBlock("Effects",
                        new VSwitchOption("Cache TNT Model",
                                "Record the primed TNT cube once and replay it, instead of looking "
                                        + "the model up and rebuilding its twelve quads for every "
                                        + "charge in every frame. The game does that per charge per "
                                        + "frame — seven draw calls each — so five hundred lit "
                                        + "charges is three and a half thousand of them describing "
                                        + "one identical cube. The picture is the same to the pixel: "
                                        + "the list is recorded from the game's own call.",
                                Cost.of(Level.LOW, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isCacheBlockEntityModels();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setCacheBlockEntityModels(value);
                                    }
                                }),
                        new VRangeOption("Explosion Particles",
                                "How many particles one tick's explosions may spawn before the "
                                        + "rest are thinned out. The server sends the client the "
                                        + "list of blocks an explosion destroyed and the client "
                                        + "asks for two particles at every one of them, so a large "
                                        + "charge of TNT is tens of thousands of them born in a "
                                        + "single tick — each an object to tick, sort and draw. "
                                        + "Past the limit one in eight is kept rather than none, "
                                        + "because cutting off leaves a hole where the blast was "
                                        + "biggest. Nothing about what breaks or drops changes.",
                                Cost.of(Level.MEDIUM, Level.LOW, Level.NONE), null,
                                0, 20000, 250, " per tick", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getExplosionParticles();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setExplosionParticles(value);
                                    }
                                }),
                        new VCyclingOption("Particles",
                                "How many particles the game spawns. Minimal is a large win near "
                                        + "fire, potions and redstone.",
                                Cost.of(Level.HIGH, Level.MEDIUM, Level.NONE), null,
                                VCyclingOption.Choices.of("All", "Decreased", "Minimal"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.particleSetting;
                                    }

                                    @Override
                                    public void set(int index) {
                                        mc.gameSettings.particleSetting = index;
                                        mc.gameSettings.saveOptions();
                                    }
                                })));
    }

    private static VOptionPage qualityPage(final Minecraft mc) {
        return new VOptionPage("Quality",
                new VOptionBlock("World",
                        new VCyclingOption("Graphics",
                                "Fast drops transparent leaves and simplifies water. Mostly a CPU "
                                        + "and chunk-build win, since it removes geometry — which is "
                                        + "also why it frees a little video memory.",
                                Cost.of(Level.MEDIUM, Level.MEDIUM, Level.LOW), null,
                                VCyclingOption.Choices.of("Fast", "Fancy"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.fancyGraphics ? 1 : 0;
                                    }

                                    @Override
                                    public void set(int index) {
                                        mc.gameSettings.fancyGraphics = index == 1;
                                        mc.gameSettings.saveOptions();
                                        mc.renderGlobal.loadRenderers();
                                    }
                                }),
                        new VCyclingOption("Smooth Lighting",
                                "Ambient occlusion baked into chunk geometry. Costs chunk build "
                                        + "time, not frame time — so it shows up as stutter while the "
                                        + "world loads, not as a lower framerate standing still.",
                                Cost.cpu(Level.MEDIUM), null,
                                VCyclingOption.Choices.of("Off", "Minimum", "Maximum"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.ambientOcclusion;
                                    }

                                    @Override
                                    public void set(int index) {
                                        mc.gameSettings.ambientOcclusion = index;
                                        mc.gameSettings.saveOptions();
                                        mc.renderGlobal.loadRenderers();
                                    }
                                }),
                        new VRangeOption("Mipmap Levels",
                                "Smaller copies of the block atlas for distant surfaces. This mod "
                                        + "copies them into Vulkan, so raising this removes shimmer "
                                        + "far away and is easier on the texture cache. Raising it "
                                        + "usually costs nothing and can even gain a little.",
                                Cost.of(Level.NONE, Level.MEDIUM, Level.LOW),
                                "The atlas is rebuilt when you close this screen.",
                                0, 4, 1, "", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.mipmapLevels;
                                    }

                                    /**
                                     * Handed to the game's own setter rather than
                                     * applied by hand.
                                     *
                                     * This row used to do the four steps itself and
                                     * then call {@code scheduleResourcesRefresh},
                                     * which reloads every resource there is — and
                                     * that restarts the sound engine. A slider is
                                     * dragged, so two steps a second apart meant two
                                     * reloads a second apart, the second OpenAL
                                     * context refusing to exist beside the first,
                                     * thirty seconds of the sound loader waiting, and
                                     * then the game dying on the natives that had
                                     * been unloaded underneath its still-running
                                     * sound threads.
                                     *
                                     * Forge already fixed this in vanilla's setter for
                                     * the same reason (MC-64581): it applies the level
                                     * immediately and defers one narrow model reload
                                     * to when the screen closes. Calling that setter
                                     * inherits the fix instead of reproducing the bug
                                     * beside it; see this screen's onGuiClosed for the
                                     * other half.
                                     */
                                    @Override
                                    public void set(int value) {
                                        if (value == mc.gameSettings.mipmapLevels) {
                                            return;
                                        }
                                        mc.gameSettings.setOptionFloatValue(
                                                GameSettings.Options.MIPMAP_LEVELS, value);
                                        mc.gameSettings.saveOptions();
                                    }
                                })));
    }

    private static VOptionPage advancedPage(final Minecraft mc) {
        return new VOptionPage("Advanced",
                new VOptionBlock("Memory",
                        new VCyclingOption("Geometry Budget",
                                "How much video memory the world geometry may take before the "
                                        + "renderer stops growing its buffer generously and starts "
                                        + "creeping. Growing that buffer stops the GPU and re-uploads "
                                        + "every chunk, so a larger budget on a card that has the "
                                        + "memory to spare removes those stutters. On a small card a "
                                        + "lower value keeps the footprint tight. Auto uses a quarter "
                                        + "of what the GPU reports. Chunks are never dropped to stay "
                                        + "inside the budget — it steers growth, it is not a wall.",
                                Cost.of(Level.LOW, Level.NONE, Level.HIGH),
                                "Applies after the game restarts.",
                                BUDGET_LABELS,
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        int value = VulkanConfig.getGeometryBudgetMiB();
                                        for (int i = 0; i < BUDGET_VALUES.length; i++) {
                                            if (BUDGET_VALUES[i] == value) {
                                                return i;
                                            }
                                        }
                                        return 0;
                                    }

                                    @Override
                                    public void set(int index) {
                                        VulkanConfig.setGeometryBudgetMiB(BUDGET_VALUES[index]);
                                    }
                                }),
                        new VRangeOption("Frames In Flight",
                                "How many terrain frames the CPU may prepare before waiting for the "
                                        + "GPU. Higher hides stalls when the CPU is the bottleneck, "
                                        + "at the cost of one more frame of input delay and another "
                                        + "copy of the per-frame buffers. 2 is the safe default.",
                                Cost.of(Level.MEDIUM, Level.LOW, Level.LOW),
                                "Applies after the game restarts.",
                                1, 3, 1, " frames", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getFramesInFlight();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setFramesInFlight(value);
                                    }
                                }),
                        new VSwitchOption("Entity Capture",
                                "Read what the game draws for every creature and draw none of it. "
                                        + "The first step of moving entities into Vulkan, and it "
                                        + "takes nothing over: entities are the part of the game "
                                        + "other mods hook hardest, so before anything is replaced "
                                        + "this measures how much of a real scene comes through "
                                        + "the ordinary model path, and what asking OpenGL where "
                                        + "each part is costs. Nothing on screen changes; the "
                                        + "answer is a line in the diagnostics report.",
                                Cost.of(Level.LOW, Level.NONE, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isEntityCapture();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setEntityCapture(value);
                                    }
                                }),
                        new VRangeOption("Vulkan GPU",
                                "Which graphics card Vulkan renders on, by the number the log gives "
                                        + "it — open the diagnostics report to see which card is "
                                        + "which. Automatic does not mean the fastest card: it "
                                        + "means the card OpenGL is already running on, because "
                                        + "memory cannot be shared between two devices at all, and "
                                        + "the game's OpenGL context was placed by the driver long "
                                        + "before this mod loaded. On a laptop with two GPUs that "
                                        + "is what decides whether the Vulkan terrain runs or "
                                        + "quietly stays on OpenGL. To move the whole game to the "
                                        + "other card, launch it with that card selected for "
                                        + "OpenGL — this setting cannot do it, because the context "
                                        + "exists before the mod does.",
                                Cost.FREE, "Applies after the game restarts.",
                                -1, 7, 1, "", "Automatic",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getVulkanDevice();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setVulkanDevice(value);
                                    }
                                })),
                new VOptionBlock("Compositing",
                        new VSwitchOption("Depth Blit",
                                "Copy Vulkan's depth buffer into the game's with hardware blit "
                                        + "instead of writing it per pixel in a shader. Faster, but "
                                        + "needs a driver that can share a 24-bit depth target; the "
                                        + "renderer falls back on its own if it cannot.",
                                Cost.gpu(Level.MEDIUM),
                                "Applies after the window is resized or the game restarts.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isDepthBlitEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setDepthBlitEnabled(value);
                                    }
                                }),
                        new VSwitchOption("Backface Culling",
                                "Skip triangles facing away from the camera. Only turn this off to "
                                        + "diagnose missing or inside-out geometry — with it off the "
                                        + "GPU shades roughly twice the triangles for nothing.",
                                Cost.gpu(Level.HIGH), "Applies after the game restarts.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isCullingEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setCullingEnabled(value);
                                    }
                                })),
                new VOptionBlock("Diagnostics",
                        new VSwitchOption("Ultra Logging",
                                "Write everything about the renderer, your mods and your settings to "
                                        + "logs/vulkanmod112-diagnostics.log. Turn this on before "
                                        + "reporting a problem — the file answers most questions on its own.",
                                Cost.cpu(Level.LOW), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isUltraLogEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setUltraLogEnabled(value);
                                    }
                                }),
                        new VRangeOption("Log Interval",
                                "How often a snapshot is appended to the diagnostics file.",
                                Cost.FREE, null, 1, 60, 1, " s", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getUltraLogSeconds();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setUltraLogSeconds(value);
                                    }
                                }),
                        new VSwitchOption("Diagnostic Overlay",
                                "Small Vulkan-rendered test image in the corner. Proves the interop "
                                        + "path works; costs a submit and two semaphore waits a frame.",
                                Cost.of(Level.LOW, Level.LOW, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isOverlayEnabled();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setOverlayEnabled(value);
                                    }
                                }),
                        new VSwitchOption("Show Materials",
                                "Paint the world by what it is made of instead of by its texture: "
                                        + "water blue, foliage green, glass yellow, lava orange, "
                                        + "everything else grey. What a block is made of is decided "
                                        + "while the chunk is built, carried to the card in a "
                                        + "buffer of its own and read back in the shader, and every "
                                        + "step of that looks the same whether it is right or a "
                                        + "chunk out of place. This is how you look at it. Needs "
                                        + "Material Tags on, and the chunks in view rebuilt — "
                                        + "F3+A does that — before there is anything to show.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Material Tags on.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isShowMaterials();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setShowMaterials(value);
                                    }
                                }),
                        new VSwitchOption("Show Ambient Occlusion",
                                "Draw the corner shading on its own, as flat grey, instead of "
                                        + "applying it to the world. Vanilla darkens the corners "
                                        + "of its own blocks and darkens a face by which way it "
                                        + "points — a top at full, sides at 0.8 and 0.6, an "
                                        + "underside at half — so a dark seam in a lit room is "
                                        + "not evidence of anything until both of those are out "
                                        + "of the picture, and neither has any way of announcing "
                                        + "itself while the world is drawn normally. This takes "
                                        + "them out: what is left on screen is this effect and "
                                        + "nothing else, and a defect either survives that or was "
                                        + "never here to begin with.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Ambient Occlusion above zero.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isShowOcclusion();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setShowOcclusion(value);
                                    }
                                }),
                        new VSwitchOption("Draw Creatures in Vulkan",
                                "Creatures have a subpass of their own with a writable depth "
                                        + "buffer, which is what they were missing: one mob hides "
                                        + "the mob behind it, a head hides the back of its own "
                                        + "skull, water covers a creature only when the creature "
                                        + "is really under it, and particles stop showing through. "
                                        + "On, and the switch is a trade rather than an "
                                        + "improvement. Off, the game draws creatures and you get "
                                        + "back the red flash when something is hurt and the "
                                        + "shimmer on enchanted armour — neither of which is drawn "
                                        + "as a colour, so neither survives being taken over. What "
                                        + "you pay for them is that you then reach the shadow "
                                        + "passes twice, once as captured geometry and once as "
                                        + "something the game drew, and the rim between the two "
                                        + "shadows follows you as a trail of light while flying. "
                                        + "This is the one thing here that replaces vanilla's own "
                                        + "drawing rather than adding to it — a mod that builds "
                                        + "its models some other way is untouched and draws as it "
                                        + "always did, without a shadow of its own, but anything "
                                        + "using the ordinary model classes is taken. It will not "
                                        + "give you frames and may cost a few: the pose is worked "
                                        + "out on the processor where the graphics card used to "
                                        + "do it. What it buys is that creatures exist in this "
                                        + "renderer at all, which is what reflections and glow "
                                        + "have been waiting for. If something looks wrong, this "
                                        + "is the switch to turn off first.",
                                Cost.of(Level.LOW, Level.NONE, Level.LOW),
                                "Needs Vulkan Terrain on.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isVulkanEntities();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setVulkanEntities(value);
                                    }
                                }),
                        new VSwitchOption("Show Accumulation",
                                "Paint each pixel by how much of its history it kept instead of "
                                        + "by the world. White is a pixel that has been averaging "
                                        + "for a while, black is one starting over. The edges of "
                                        + "the screen, the far side of anything you walk past, and "
                                        + "everything at all while you turn quickly are supposed "
                                        + "to be black — that is the history being thrown away "
                                        + "where it would smear rather than smooth. Solid white "
                                        + "while you move is this being wrong.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Frame Accumulation and a traced effect.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isShowAccumulation();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setShowAccumulation(value);
                                    }
                                }),
                        new VSwitchOption("Show Motion Vectors",
                                "Paint the world with how each pixel moved since the last frame "
                                        + "instead of with itself: which way it went is the "
                                        + "colour, how fast is the brightness, and anything that "
                                        + "did not move at all is black. Opposite directions come "
                                        + "out as opposite colours. Nothing on screen depends on "
                                        + "this yet. It is what every effect that wants to remember "
                                        + "something is built on — a reflection or a shadow worked "
                                        + "out from a handful of samples is too noisy to use on "
                                        + "its own, and what makes it usable is adding this "
                                        + "frame's answer to the ones before it, which cannot be "
                                        + "done without knowing which pixel of the last frame was "
                                        + "looking at the same place. Standing perfectly still "
                                        + "over a still world must come out perfectly black; "
                                        + "anything else there is this being wrong.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Vulkan Terrain on.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isShowMotion();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setShowMotion(value);
                                    }
                                }),
                        new VSwitchOption("Motion Over World",
                                "Show the motion over a dim ghost of the world instead of over "
                                        + "black. The two answer different questions and both are "
                                        + "worth having: black answers whether anything is moving "
                                        + "at all, which is why standing still has to come out "
                                        + "empty, and the ghost answers which part of the world "
                                        + "moved — a wall and the floor beside it move quite "
                                        + "differently, and against black there is no telling "
                                        + "which of them was which.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Only does anything while Show Motion Vectors is on.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isMotionOverWorld();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setMotionOverWorld(value);
                                    }
                                }),
                        new VSwitchOption("Show Reflections",
                                "Paint the water with what the reflected ray found and nothing "
                                        + "else: no fresnel deciding how much of it to show, no "
                                        + "water colour underneath, and deep blue wherever the ray "
                                        + "found nothing at all. A reflection on water is stretched "
                                        + "even when it is perfectly right — an eye a metre or two "
                                        + "above the surface sends the reflected ray off at a very "
                                        + "shallow angle, so it travels a long way before it "
                                        + "reaches anything, and a tree on the far bank arrives as "
                                        + "a long streak rather than a tree. Which means the "
                                        + "question of whether this is working cannot be settled "
                                        + "by how it looks over the water, and this is how to "
                                        + "settle it instead.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Screen Reflections above zero.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isShowReflections();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setShowReflections(value);
                                    }
                                })));
    }

    /** Device-local memory the GPU reports, for the screen header. 0 if unknown. */
    static int vramMegabytes() {
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        if (bridge == null) {
            return 0;
        }
        try {
            return bridge.vramMegabytes();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Kept so the class is obviously client-side only. */
    static GameSettings settings(Minecraft mc) {
        return mc.gameSettings;
    }

    private static VOptionPage shadersPage(final Minecraft mc) {
        return new VOptionPage("Shaders",
                new VOptionBlock("Shaders",
                        new VSwitchOption("Material Tags",
                                "Record what each stretch of a chunk is made of while the chunk is "
                                        + "being built. On its own this changes nothing you can "
                                        + "see: it is the groundwork the effects still to come are "
                                        + "waiting on. The game draws terrain in four layers and a "
                                        + "layer is not a material — water and stained glass are "
                                        + "the same layer, so are grass and torches and rails — "
                                        + "and the vertex carries position, colour, texture and "
                                        + "light and nothing else. The one moment anything knows "
                                        + "that a particular block is water is while that block is "
                                        + "being turned into triangles, so that is where it is "
                                        + "written down. Costs a branch per block on the building "
                                        + "threads, and the diagnostics log says what it measured "
                                        + "rather than leaving that to be believed.",
                                Cost.of(Level.NONE, Level.NONE, Level.LOW),
                                "Everything below it in this section that tells one block from "
                                        + "another needs it, starting with how foliage is lit.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isMaterialTags();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setMaterialTags(value);
                                    }
                                }),
                        new VSwitchOption("Dynamic Lights",
                                "Let a carried torch, a dropped glowing block or a burning "
                                        + "creature light the ground around it. The light is added "
                                        + "while the world is being shaded rather than written "
                                        + "into it, so no chunk is rebuilt — and rebuilding chunks "
                                        + "is exactly what the frame is already waiting on while "
                                        + "you move, which is what makes the usual approach to "
                                        + "this cost so much. Any block that gives off light does, "
                                        + "including modded ones, because the value is read from "
                                        + "the block itself. Mobs standing in the light, the "
                                        + "particles a broken block throws off and the view from "
                                        + "first person are all lit to match. Nothing is written "
                                        + "into the world and nothing is sent anywhere: this is "
                                        + "worked out on your machine while the frame is drawn, so "
                                        + "it changes nothing about mob spawning or daylight "
                                        + "sensors and works on any server. Another player carrying "
                                        + "a torch lights the ground for you without needing this "
                                        + "mod themselves — only the one looking needs it.",
                                Cost.of(Level.LOW, Level.LOW, Level.NONE), null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isDynamicLights();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setDynamicLights(value);
                                    }
                                }),
                        new VRangeOption("Directional Light",
                                "How far dynamic light goes towards caring which way a surface is "
                                        + "turned. The game's own light is one number per block "
                                        + "and knows nothing about orientation, so a dropped torch "
                                        + "lights the underside of the floor it is lying on "
                                        + "exactly as brightly as the top of it. This renderer can "
                                        + "work the face out from how the surface changes across "
                                        + "the screen — every quad in a block model is flat, so "
                                        + "that is the real face rather than a guess — and dim "
                                        + "what is turned away from the light. Nothing goes fully "
                                        + "dark and nothing switches on at once: the flame is "
                                        + "treated as having width, so its light wraps around a "
                                        + "corner you are standing next to and stops at one across "
                                        + "the room. Costs nothing at all while dynamic lights are "
                                        + "off.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Only does anything while Dynamic Lights is on.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getDirectionalLight();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setDirectionalLight(value);
                                    }
                                }),
                        new VRangeOption("Height Fog",
                                "How much colour the ground below you gives up to fog. This is a "
                                        + "look rather than a fix, and it is honest about its "
                                        + "limits: it fades towards the game's own fog colour and "
                                        + "only where the game already has fog, so it cannot "
                                        + "invent a haze the sky disagrees with. What it cannot "
                                        + "reach is everything this renderer does not draw — "
                                        + "entities and particles are fogged by OpenGL, which "
                                        + "knows nothing about height, so a mob standing in a "
                                        + "fogged valley stays clearer than the ground under it.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE), null,
                                // A single percent, not a doubled one: this
                                // string is drawn as it is rather than passed
                                // through the game's formatter, because its key
                                // slugs to nothing and no translation can exist
                                // for it.
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getHeightFog();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setHeightFog(value);
                                    }
                                }),
                        new VRangeOption("Water Reflection",
                                "How much of a water surface turns into a reflection of the sky as "
                                        + "you look along it. Looking straight down you see the "
                                        + "bottom; looking along the water you see the horizon, and "
                                        + "the change between the two is steep and happens near the "
                                        + "end — which is how water actually behaves and something "
                                        + "the game has never done. What it reflects is the game's "
                                        + "own fog colour, and that is not a stand-in: at a grazing "
                                        + "angle what flat water shows you is the horizon, and the "
                                        + "fog colour is the horizon, so this follows sunrise, "
                                        + "weather and being underwater without being told about "
                                        + "any of them.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Vulkan Water and Glass on; the OpenGL copy of the water "
                                        + "knows nothing about this.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWaterReflection();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setWaterReflection(value);
                                    }
                                }),
                        new VRangeOption("Screen Reflections",
                                "How much of a water reflection is the world that is actually "
                                        + "standing there, rather than the flat fog colour the "
                                        + "row above puts on it. The reflected ray is followed "
                                        + "across the picture that has already been drawn — which "
                                        + "is possible at all only because water is drawn in a "
                                        + "pass of its own, after the opaque world is finished and "
                                        + "handed back, so the colour and depth of everything "
                                        + "behind the surface exist by the time a water pixel is "
                                        + "being shaded. Nothing is traced against the world "
                                        + "itself, which is what makes this cost a loop rather "
                                        + "than a second copy of the world in memory. What it can "
                                        + "find is exactly what is on screen and no more: a ray "
                                        + "leaving the edge of the frame, or turning back towards "
                                        + "you where nothing was ever drawn, has no answer, and "
                                        + "the fog colour finishes it — which is not a patch, "
                                        + "since the fog colour is the horizon and the horizon is "
                                        + "what flat water shows at that angle anyway. Creatures "
                                        + "are missing from it for the same reason they are "
                                        + "missing from everything else here: the game draws them "
                                        + "after this renderer has finished. Reflecting what is "
                                        + "off screen needs rays into the world itself, which is a "
                                        + "different thing entirely and is not this.",
                                Cost.of(Level.NONE, Level.MEDIUM, Level.NONE),
                                "Experimental. Needs Vulkan Water and Glass on, and Water "
                                        + "Reflection above zero.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getScreenReflections();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setScreenReflections(value);
                                    }
                                }),
                        new VRangeOption("Ambient Occlusion",
                                "How much a point is darkened by how little of its surroundings it "
                                        + "can see. The game shades a face by which way it points "
                                        + "and by nothing else, so an inside corner is lit exactly "
                                        + "like an open wall and a room has no shape to it. What is "
                                        + "missing is a question about the neighbourhood rather "
                                        + "than about the surface, which is what a depth buffer "
                                        + "answers — and the depth buffer is already here, so this "
                                        + "costs no geometry and no second pass over the world. "
                                        + "Eight neighbours are asked whether they stand in front "
                                        + "of the surface, at half resolution and blurred, because "
                                        + "the answer is about corners and crevices rather than "
                                        + "about texels. This is not the only occlusion in the "
                                        + "picture and is scaled knowing it: the game bakes its "
                                        + "own into the corners of every block while the chunk is "
                                        + "built, and what this adds lands on top of that rather "
                                        + "than instead of it, so a seam darkened twice comes out "
                                        + "blacker than anything else in a room. The whole length "
                                        + "of the slider is meant to be usable. Terrain only: "
                                        + "entities are drawn by the game after this renderer has "
                                        + "finished, so a creature casts no shadow into the corner "
                                        + "it stands in.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW),
                                "Needs Vulkan Terrain on.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getAmbientOcclusion();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setAmbientOcclusion(value);
                                    }
                                }),
                        new VRangeOption("Ambient Occlusion Reach",
                                "How far a corner's shadow reaches, in blocks. The row above says "
                                        + "how dark, this one says how far, and the second is what "
                                        + "decides whether it reads as shadow at all: a reach under "
                                        + "a block draws a dark line along the seam where a wall "
                                        + "meets a ceiling rather than a shadow fading out of it, "
                                        + "because everything the effect has to say is then said "
                                        + "within a few pixels. Two blocks is a little under the "
                                        + "height of a doorway, which is the scale a room's corners "
                                        + "are read at. Larger is softer and reaches further; it "
                                        + "costs nothing extra, the same sixteen neighbours are "
                                        + "asked, only further apart — so the wider it goes the "
                                        + "coarser the answer, and past four blocks a small alcove "
                                        + "is missed entirely by a set of samples spread across a "
                                        + "room.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Ambient Occlusion above zero.",
                                1, 6, 1, " blocks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getAoRadius();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setAoRadius(value);
                                    }
                                }),
                        new VRangeOption("Bloom",
                                "How much light spills off a glowing surface into the pixels "
                                        + "around it. Lava, torches, glowstone and any modded "
                                        + "block that gives off light — and only those. What "
                                        + "glows is not guessed at from how bright a pixel is, "
                                        + "which is the usual way and the wrong one here: snow "
                                        + "and sand in sunlight are as bright on screen as lava "
                                        + "and are not lights. The terrain shader knows the "
                                        + "difference while it is shading — the game's own block "
                                        + "light says whether a surface is lit from outside or is "
                                        + "the source — so it writes the answer into the one part "
                                        + "of an opaque pixel that was carrying a constant, and "
                                        + "the glow is pulled out of that. Three passes over half "
                                        + "the screen, on the GPU only. "
                                        + "The glow is added once the game has finished drawing "
                                        + "the world — after entities, particles, weather and "
                                        + "water, and before the hand — so a mob standing in front "
                                        + "of lava is inside the glow rather than pasted over it, "
                                        + "and a torch throws light onto the sky, which is not "
                                        + "drawn until long after this mod's own frame is "
                                        + "finished. What still does not glow is the creatures "
                                        + "themselves: a burning creeper spills no light, because "
                                        + "knowing which pixels of an entity are a light needs "
                                        + "something the game does not record anywhere this can "
                                        + "reach.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW),
                                "Needs Vulkan Terrain and Material Tags on.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getBloom();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setBloom(value);
                                    }
                                }),
                        new VRangeOption("Foliage Sway",
                                "How far the top of a plant leans in the wind. The vertex is moved "
                                        + "rather than the shading faked, and only the top pair of "
                                        + "corners of each quad: the bottom of a plant is in the "
                                        + "ground and stays there. Nothing had to be stored to know "
                                        + "which corners those are — the game builds every quad's "
                                        + "four in one fixed order, and its own table gives the "
                                        + "same answer for all four vertical faces, so the corner "
                                        + "number is the marker and it costs nothing. Grass, "
                                        + "flowers, saplings and crops only. Leaves are a solid "
                                        + "cube whose top face would tear in half under the same "
                                        + "rule, and a plant taller than one block has the top of "
                                        + "its lower half and the bottom of its upper half at the "
                                        + "same height, so it would come apart at the seam — both "
                                        + "are left still until there is somewhere to record how "
                                        + "far up its own plant a block is.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Material Tags on; nothing else can tell grass from a torch.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getFoliageSway();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setFoliageSway(value);
                                    }
                                }),
                        new VRangeOption("Water Waves",
                                "How much a moving wave pattern tilts the surface of water. "
                                        + "Nothing is displaced and nothing is built: the water "
                                        + "stays exactly where the game put it, a boat floats where "
                                        + "it always did, and what moves is only which way the "
                                        + "surface is treated as facing. That is enough, because "
                                        + "every answer this renderer has about water already comes "
                                        + "from that direction — the sky reflection breaks up along "
                                        + "the crests instead of lying flat, and a torch held over "
                                        + "the water scatters across it rather than landing as one "
                                        + "smooth patch. Only the top of a water block waves; the "
                                        + "sides are the walls of the channel it runs in. The "
                                        + "pattern repeats every sixteen blocks, which is the price "
                                        + "of it staying still while you walk instead of swimming "
                                        + "along behind you.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Vulkan Water and Glass on; the OpenGL copy of the water "
                                        + "knows nothing about this.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWaterWaves();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setWaterWaves(value);
                                    }
                                }),
                        new VRangeOption("Height Fog Depth",
                                "How far below you the ground has to be before height fog has "
                                        + "taken nearly all of the colour the setting above lets "
                                        + "it take. The two work together: one says how much, this "
                                        + "says how soon. Lower it and a valley a few blocks under "
                                        + "your feet is already hazy; raise it and only the floor "
                                        + "of a deep ravine is.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Only does anything while Height Fog is above 0.",
                                4, 96, 4, " blocks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getHeightFogDepth();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setHeightFogDepth(value);
                                    }
                                }),
                        new VRangeOption("Dynamic Light Distance",
                                "How far away a light source may be and still be drawn, in blocks. "
                                        + "This is not how far the light reaches — that comes from "
                                        + "the source itself, and a torch lights about fifteen "
                                        + "blocks around it whatever this says. What it decides is "
                                        + "whether a distant torch lights the ground it stands on "
                                        + "at all, and a pool of light on the ground is visible "
                                        + "from as far away as the ground is. An early version cut "
                                        + "this off at 24 blocks, reasoning that a level-15 light "
                                        + "reaches 15, and lights visibly winked out as you flew "
                                        + "away from torches that were still in plain sight. "
                                        + "Lowering it costs nothing and buys nothing except fewer "
                                        + "distant lights: every loaded entity is looked at either "
                                        + "way, and only the nearest 32 sources are ever drawn.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Only does anything while Dynamic Lights is on.",
                                1, 200, 1, " blocks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getDynamicLightDistance();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setDynamicLightDistance(value);
                                    }
                                })),
                new VOptionBlock("Time and Weather",
                        new VCyclingOption("Time Control",
                                "Show a different time of day than the world is at. Local to this "
                                        + "screen and nowhere else: nothing is sent to the server, "
                                        + "nothing is written to the world, and no other player "
                                        + "sees it. Mobs still burn at dawn. Frozen holds the hour "
                                        + "you switched it on at; Fixed uses the slider below.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                VCyclingOption.Choices.of("Off", "Frozen", "Fixed"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTimeControl();
                                    }

                                    @Override
                                    public void set(int index) {
                                        VulkanConfig.setTimeControl(index);
                                    }
                                }),
                        new VRangeOption("Time Of Day",
                                "Which hour to show. The day is kept, so the moon keeps the phase "
                                        + "it was going to have. Every effect in this mod looks "
                                        + "different at a different hour, and this is how to see "
                                        + "two hours without waiting for one.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Time Control set to Fixed.",
                                0, 23, 1, ":00", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTimeOfDay();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setTimeOfDay(value);
                                    }
                                }),
                        new VCyclingOption("Weather Control",
                                "Show weather of your own instead of the world's. Local, like the "
                                        + "time above: a storm the server believes in still charges "
                                        + "a creeper, and one you turn on here charges nothing.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                VCyclingOption.Choices.of("Off", "Clear", "Rain", "Storm"),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWeatherControl();
                                    }

                                    @Override
                                    public void set(int index) {
                                        VulkanConfig.setWeatherControl(index);
                                    }
                                })),
                new VOptionBlock("Sky and Water",
                        new VRangeOption("Scene Tone",
                                "Grades the finished picture: contrast lifted in the middle, "
                                        + "warmth put into the balance. This is the thing that "
                                        + "separates a shader pack's frame from the game's before "
                                        + "any single effect is named. It runs after the world is "
                                        + "drawn in full, so it reaches creatures, particles and "
                                        + "weather as well as blocks, and stops before the hand "
                                        + "and the interface. The game's frame is eight bits a "
                                        + "channel, so this is colour grading and not a film curve "
                                        + "— there is no headroom above white to burn.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW), null,
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSceneTone();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSceneTone(value);
                                    }
                                }),
                        new VRangeOption("Scene Warmth",
                                "Which way the grading leans. The middle is neutral, above it "
                                        + "warm, below it cold. Red gains exactly what blue gives "
                                        + "up, so a warm scene does not read as a brighter one.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs Scene Tone above zero.",
                                0, 100, 5, "%", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSceneWarmth();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSceneWarmth(value);
                                    }
                                }),
                        new VRangeOption("Water Refraction",
                                "How much the surface of water bends what is seen through it. "
                                        + "Reflection and refraction are two halves of one thing "
                                        + "and only one of them was here: a pond whose mirror "
                                        + "moves while its bed stays perfectly still reads as "
                                        + "glass laid over a photograph, and the bed is the "
                                        + "giveaway. What is behind the water comes from the same "
                                        + "picture the reflection searches, so it shows the world "
                                        + "but not creatures, which this renderer does not draw. "
                                        + "Turned up too far, straight edges under water start to "
                                        + "look like jelly.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Vulkan Water and Glass on.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWaterRefraction();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setWaterRefraction(value);
                                    }
                                }),
                        new VRangeOption("Water Caustics",
                                "The bands of light that gather on the bed of shallow water. "
                                        + "Real ones are the surface working as a lens on the "
                                        + "light going through it; this brightens the bed in the "
                                        + "same pattern the waves are already shaded by, which "
                                        + "costs a handful of instructions instead of a second "
                                        + "pass. Left out of the traced shader on purpose — see "
                                        + "the note on Ice Shine.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Water Refraction above zero, which is what fetches the "
                                        + "bed. Does nothing while ray tracing is on.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWaterCaustics();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setWaterCaustics(value);
                                    }
                                }),
                        new VRangeOption("Sun and Moon Glint",
                                "The sun itself sliding along the ripples, and the moon doing "
                                        + "the same at night. This is not the reflection and no "
                                        + "reflection can produce it: the sun is a light rather "
                                        + "than a surface drawn into the scene for a ray to find, "
                                        + "so mirroring the sky where it stands gives its colour "
                                        + "and not its shape. One of the plainest marks of a "
                                        + "shader pack on open water.\n\nIt fades with distance "
                                        + "on purpose. A specular highlight physically widens "
                                        + "towards the horizon as the eye rises, which is why the "
                                        + "first version of this grew until it filled the view "
                                        + "when you flew up over an ocean — correct, and wrong "
                                        + "for a world where nothing else grows when you climb."
                                        + "\n\nNot built into the traced terrain shader, where it "
                                        + "costs more than every other effect on that pass "
                                        + "together and once lost the graphics device.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Vulkan Water and Glass on. Does nothing while ray tracing "
                                        + "is on.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getCelestialGlint();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setCelestialGlint(value);
                                    }
                                }),
                        new VRangeOption("Ice Shine",
                                "How much of the sky a sheet of ice gathers on itself. The game "
                                        + "draws ice as a flat blue pane; the one thing that makes "
                                        + "it the most recognisable surface in a shader pack is "
                                        + "what water already has here — it looks along itself the "
                                        + "way a polished floor does. Cheaper than water: ice does "
                                        + "not ripple, so there are no waves to shade and no ray "
                                        + "to march.\n\nNot built into the traced version of the "
                                        + "terrain shader. A specular term of this shape, in this "
                                        + "branch, lost the graphics device outright while rays "
                                        + "were being traced, and that pass has no room left in "
                                        + "it. With ray tracing on this says so rather than "
                                        + "quietly doing nothing.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs Vulkan Water and Glass on, and Material Tags. Does nothing "
                                        + "while ray tracing is on.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getIceShine();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setIceShine(value);
                                    }
                                }),
                        new VRangeOption("Wet Surfaces",
                                "How much rain makes the ground gather the sky. A wet surface "
                                        + "does two things and needs both: it darkens, because the "
                                        + "film of water carries light down into the material "
                                        + "instead of scattering it back, and it catches the sky "
                                        + "at a grazing angle, because the film is smooth where "
                                        + "the block is rough. Only faces pointing up, and only in "
                                        + "proportion to the sky light they already receive — "
                                        + "there is no test for what is over a particular block, "
                                        + "so a lit cave mouth dampens a little. Weather rather "
                                        + "than simulation.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Only while it is raining.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getWetSurfaces();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setWetSurfaces(value);
                                    }
                                }),
                        new VRangeOption("Sun Haze",
                                "Warms the fog towards the sun and cools it away from it. The "
                                        + "game fogs everything to one colour whichever way you "
                                        + "are facing, and the sky it hangs under does not: air "
                                        + "scatters short wavelengths sideways and long ones "
                                        + "forwards, so haze into the sun is bright and warm and "
                                        + "haze behind you is cool. This leans the colour the game "
                                        + "already chose rather than replacing it, so it cannot "
                                        + "disagree with the sky.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs the game to have fog of its own to lean, and daylight.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSunHaze();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSunHaze(value);
                                    }
                                }),
                        new VRangeOption("Shadows Through Leaves",
                                "How much light a canopy lets through in a traced shadow. Without "
                                        + "it a leaf block stops a shadow ray exactly as stone "
                                        + "does, because a ray does not read textures — so a tree "
                                        + "that is mostly holes throws a solid slab of shade, "
                                        + "which is the thing that tells this apart from a shader "
                                        + "pack at a glance. Instead of asking where a leaf's "
                                        + "holes are, this asks how much of it is holes and lets "
                                        + "light past that often; the frame averaging turns the "
                                        + "speckle into dapple. Grass and flowers cast a shadow "
                                        + "at all for the first time here, and a light one. "
                                        + "Halfway between is a thinner canopy rather than a "
                                        + "worse one.",
                                Cost.of(Level.NONE, Level.MEDIUM, Level.NONE),
                                "Needs Ray Tracing and Sun Shadows. Costs most where the screen "
                                        + "is full of trees.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getLeafShadows();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setLeafShadows(value);
                                    }
                                }),
                        new VSwitchOption("Occlusion Over Everything",
                                "Darken the corners of the whole picture instead of the blocks' "
                                        + "alone. Without it the shading is worked out inside "
                                        + "this mod's own pass, from a depth image that holds "
                                        + "terrain and nothing else — so a chest casts nothing "
                                        + "into the floor it stands on, and neither does a mob or "
                                        + "a modded block drawn by its own renderer. This reads "
                                        + "the game's finished depth, where every one of them is. "
                                        + "Nothing is taken away from any mod by it: the picture "
                                        + "is already drawn, and this only reads it. Needs "
                                        + "Ambient Occlusion above zero, and uses that same "
                                        + "slider.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW),
                                "Needs Ambient Occlusion above zero.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isSceneOcclusion();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setSceneOcclusion(value);
                                    }
                                }),
                        new VRangeOption("Contact Shadows",
                                "A short shadow where a thing meets the ground, cast towards the "
                                        + "sun. This is the gap the traced shadows leave: they "
                                        + "come from the acceleration structures, which hold "
                                        + "terrain and creatures, so a chest, a sign and every "
                                        + "modded machine cast nothing at all. This is worked out "
                                        + "from the depth of the finished picture instead, where "
                                        + "all of them are, and nothing is taken away from any "
                                        + "mod to get it — the picture is already drawn and this "
                                        + "only reads it. What it cannot do is the other half: an "
                                        + "occluder has to be on the screen and within about a "
                                        + "block of the surface, so this fills in the contact and "
                                        + "the long shadows stay the traced ones' work. Rides the "
                                        + "ambient occlusion pass, so it costs a loop rather than "
                                        + "a pass of its own, and fades out as the sun reaches "
                                        + "the horizon, where a shadow along the ground would "
                                        + "stretch past everything on the screen. Turn on "
                                        + "Occlusion Over Everything with it, or it sees blocks "
                                        + "and nothing else.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "Needs the sun above the horizon. Turn on Occlusion Over "
                                        + "Everything for it to reach anything but blocks.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getContactShadows();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setContactShadows(value);
                                    }
                                }),
                        new VRangeOption("Cloud Shadows",
                                "The shade of the clouds overhead, moving across the world. "
                                        + "Nothing about it is invented: it reads the very sheet "
                                        + "the game draws its clouds from, at the height the "
                                        + "world reports, with the drift the game itself counts "
                                        + "— so the dark patch lands under the cloud that cast "
                                        + "it rather than beside it, and it costs one texture "
                                        + "read on a pass that already exists. A sky with clouds "
                                        + "that leave no mark on the ground is the flattest "
                                        + "thing in the picture, and this is the cheapest way to "
                                        + "answer that without drawing a single cloud of its "
                                        + "own. Falls on whatever is in the picture, this mod's "
                                        + "blocks and another mod's machine alike. Needs the "
                                        + "game's clouds turned on.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs clouds on and the sun above the horizon.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getCloudShadows();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setCloudShadows(value);
                                    }
                                }),
                        new VRangeOption("Light Shafts",
                                "Sunlight made visible in the air itself, in lanes through a "
                                        + "canopy or a cave mouth. The oldest trick there is for "
                                        + "this and still the right one here: the walk from a "
                                        + "pixel towards the sun adds up what the sky shows "
                                        + "through, so anything standing in the line leaves a "
                                        + "dark lane behind it and a gap leaves a bright one. "
                                        + "Nothing about it involves geometry, a second view of "
                                        + "the world or a ray — it reads a picture the game has "
                                        + "already finished, which is what makes it the one "
                                        + "version of this effect that cannot break another mod, "
                                        + "and it means whatever a mod drew casts its own shafts "
                                        + "for nothing. What it cannot do is show a shaft whose "
                                        + "sun is off the screen: it fades out as the sun leaves "
                                        + "the view rather than switching off, because switching "
                                        + "off would be a flicker. Runs at half resolution, "
                                        + "twenty-four samples a pixel.",
                                Cost.of(Level.NONE, Level.MEDIUM, Level.LOW),
                                "Needs the sun above the horizon and roughly in front of you.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getGodRays();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setGodRays(value);
                                    }
                                }),
                        new VSwitchOption("High Dynamic Range",
                                "Ask the game for a frame with room above white in it. This is "
                                        + "the floor under every other effect here that has to "
                                        + "do with light: the world is drawn into eight bits a "
                                        + "channel, so a highlight a hundred times brighter than "
                                        + "the grass beside it arrives already flattened into "
                                        + "the same white, before anything on this page ever "
                                        + "sees it. That is why the glow has no light of its own "
                                        + "to add, why the sun on water had to be dimmed rather "
                                        + "than left to burn, and why the tone row below can "
                                        + "tilt colours but cannot shape light the way a film "
                                        + "curve does. With this on the frame carries sixteen "
                                        + "bits a channel and the tone pass closes that range "
                                        + "back down at the end, along a curve with a shoulder "
                                        + "instead of a cliff. The driver is asked first and the "
                                        + "log says plainly if it will not have it. Applies "
                                        + "at once, and costs video memory the size of your "
                                        + "screen twice over. It also undoes itself: the frame "
                                        + "goes back to eight bits the moment the Vulkan "
                                        + "renderer stops drawing, because the pass that closes "
                                        + "the range back down lives there, and a frame with "
                                        + "nothing to close it is worse than no headroom at all.",
                                Cost.of(Level.NONE, Level.LOW, Level.MEDIUM),
                                "Needs the Vulkan renderer to be drawing.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isHdrFrame();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setHdrFrame(value);
                                    }
                                }),
                        new VRangeOption("Exposure",
                                "How much light is let in before the film curve closes the range "
                                        + "back down. The middle of the slider is no change, and "
                                        + "each step either side is the same size as the last, "
                                        + "because that is how light behaves. Only means "
                                        + "anything with High Dynamic Range on — without the "
                                        + "headroom there is nothing above white to bring down, "
                                        + "and opening up would only wash the picture out.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE),
                                "Needs High Dynamic Range.",
                                0, 100, 5, "%", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getExposure();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setExposure(value);
                                    }
                                }),
                        new VRangeOption("Sky Gradient",
                                "Deepens the sky away from the horizon. Vanilla's is one colour "
                                        + "from the horizon to straight overhead, and every "
                                        + "shader pack darkens the top of it — the sky is the "
                                        + "largest thing on the screen and the flattest. The "
                                        + "colour is the game's own fog colour taken down towards "
                                        + "a night sky rather than a colour of this mod's "
                                        + "choosing, so the top cannot disagree with the horizon "
                                        + "under it. Painted only where nothing else drew, so a "
                                        + "hilltop against the sky keeps its own colour. It "
                                        + "follows the screen rather than the true direction of "
                                        + "the sky: look straight up and the deepest part is "
                                        + "across the middle rather than overhead. That is what "
                                        + "makes it a look rather than a sky.",
                                Cost.of(Level.NONE, Level.LOW, Level.NONE),
                                "One fullscreen pass over the part of the frame with no terrain.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSkyGradient();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSkyGradient(value);
                                    }
                                }),
                        new VRangeOption("Cloud Tint",
                                "How much of the sky's colour the clouds take. Vanilla clouds are "
                                        + "white at noon and white at sunset, hanging in an orange "
                                        + "sky. This mixes in two colours the game has already "
                                        + "worked out for the moment — the sky colour, and the "
                                        + "sunrise and sunset band, which exists only while there "
                                        + "is one. Nothing is invented, so it cannot disagree with "
                                        + "the sky behind it. The volumetric clouds of a shader "
                                        + "pack are a different and much larger thing; this is the "
                                        + "half of that look which is free.",
                                Cost.of(Level.NONE, Level.NONE, Level.NONE), null,
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getCloudTint();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setCloudTint(value);
                                    }
                                }),
                        new VSwitchOption("Round Sun",
                                "Draw the sun as a round, warm disc instead of vanilla's square "
                                        + "one. The picture is built by this mod rather than "
                                        + "shipped as a file, which is what makes its size and "
                                        + "warmth sliders. Nothing else about the sky changes — "
                                        + "the quad, where it is, how it blends and the moon are "
                                        + "all still the game's, and a mod that draws its own sky "
                                        + "never reaches this at all.",
                                Cost.FREE, null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isRoundSun();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setRoundSun(value);
                                    }
                                }),
                        new VSwitchOption("Round Moon",
                                "Draw the moon as a round disc with a soft glow. The game does "
                                        + "not draw a moon so much as one cell of an "
                                        + "eight-picture sheet chosen by tonight's phase, so all "
                                        + "eight are drawn here: a lit disc with the shadow "
                                        + "creeping across it, which is one circle cut by a "
                                        + "moving ellipse. A single disc in its place would be "
                                        + "full every night of the month.",
                                Cost.FREE, null,
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isRoundMoon();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setRoundMoon(value);
                                    }
                                }),
                        new VRangeOption("Moon Size",
                                "How large the moon is drawn. Same trick as the sun: the quad the "
                                        + "game gives it cannot be resized from here, but how "
                                        + "much of its picture the disc fills can.",
                                Cost.FREE, "Needs Round Moon.",
                                0, 100, 5, "%", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getMoonSize();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setMoonSize(value);
                                    }
                                }),
                        new VRangeOption("Sun Size",
                                "How large the disc is drawn. The quad the game gives the sun "
                                        + "cannot be resized from here, but how much of it the "
                                        + "disc fills can, which comes to the same thing. The "
                                        + "middle of the range is close to where vanilla put it.",
                                Cost.FREE, "Needs Round Sun.",
                                0, 100, 5, "%", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSunSize();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSunSize(value);
                                    }
                                }),
                        new VRangeOption("Sun Warmth",
                                "How far towards orange the rim of the sun goes. 0 leaves it "
                                        + "white. The centre stays bright either way: a sun that "
                                        + "is one flat colour looks painted on, and the game's "
                                        + "own is not flat either.",
                                Cost.FREE, "Needs Round Sun.",
                                0, 100, 5, "%", "White",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSunWarmth();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSunWarmth(value);
                                    }
                                }),
                        new VCyclingOption("Sky Pictures From Pack",
                                "Take the sun and the moon from a shader pack in your "
                                        + "shaderpacks folder. Only the pictures: a pack's shader "
                                        + "code is written against a loader that does not exist "
                                        + "here and cannot be run at all, but a sun is a PNG, and "
                                        + "reading one out of a pack you already have copies "
                                        + "nothing into this mod. Packs that ship no sun have "
                                        + "none to lend, and the game's own is used instead — the "
                                        + "log says which happened.",
                                Cost.FREE, null,
                                VCyclingOption.Choices.fromMachine(
                                        net.vulkanmod112.client.ShaderPackSkins.names()),
                                new VCyclingOption.Access() {
                                    @Override
                                    public int get() {
                                        return net.vulkanmod112.client.ShaderPackSkins
                                                .indexOf(VulkanConfig.getSkinPack());
                                    }

                                    @Override
                                    public void set(int index) {
                                        VulkanConfig.setSkinPack(
                                                net.vulkanmod112.client.ShaderPackSkins
                                                        .nameAt(index));
                                    }
                                })));
    }

    private static VOptionPage rayTracingPage(final Minecraft mc) {
        return new VOptionPage("Ray Tracing",
                new VOptionBlock("Ray Tracing",
                        new VSwitchOption("Terrain Acceleration Structures",
                                "Build the structures a traced ray needs over the terrain this mod "
                                        + "draws. Nothing uses them yet, and the world looks "
                                        + "exactly the same with this on. What it produces is a "
                                        + "measurement: the obstacle to ray tracing in this game "
                                        + "has always been that the structure has to be rebuilt "
                                        + "whenever a chunk is, and rebuilding chunks is already "
                                        + "the largest cost in a moving frame — this turns that "
                                        + "sentence into a number in the diagnostics report. Needs "
                                        + "Vulkan 1.2 and the acceleration-structure extension; if "
                                        + "the card cannot, the report says which part is missing.",
                                Cost.of(Level.LOW, Level.MEDIUM, Level.HIGH),
                                "Applies after the game restarts.",
                                new VSwitchOption.Access() {
                                    @Override
                                    public boolean get() {
                                        return VulkanConfig.isRayTracing();
                                    }

                                    @Override
                                    public void set(boolean value) {
                                        VulkanConfig.setRayTracing(value);
                                    }
                                }),
                        new VRangeOption("Ray Traced Sun Shadows",
                                "Shadows cast by the world onto itself, traced against the terrain "
                                        + "rather than guessed from the screen. Needs Terrain "
                                        + "Acceleration Structures on and a card that can trace "
                                        + "from a shader; without either it does nothing and says "
                                        + "so in the diagnostics report. The shadow lowers how "
                                        + "much sky light a surface receives instead of darkening "
                                        + "the finished picture — that is how this game shades, "
                                        + "and it is why a cave lit by a torch is left alone and "
                                        + "why night changes nothing. Creatures do not cast one "
                                        + "yet: the mod does not own them.",
                                Cost.of(Level.NONE, Level.HIGH, Level.NONE),
                                "Needs Terrain Acceleration Structures.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getSunShadows();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setSunShadows(value);
                                    }
                                }),
                        new VRangeOption("Shadow Softness",
                                "How soft the edge of a traced shadow is. One ray gives one answer "
                                        + "per pixel, so at zero the edge follows the pixel grid "
                                        + "exactly — accurate, and a staircase. Higher values "
                                        + "spread that same ray over a disc, which trades the "
                                        + "staircase for a dithered band. It costs nothing either "
                                        + "way: the number of rays does not change, only where the "
                                        + "one ray is aimed.",
                                Cost.FREE, "Needs Ray Traced Sun Shadows.",
                                0, 100, 5, "%", "HARD",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getShadowSoftness();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setShadowSoftness(value);
                                    }
                                }),
                        new VRangeOption("Shadow Reach",
                                "How far from you the world carries the structures a ray can hit, "
                                        + "in blocks. This decides both what is able to cast a "
                                        + "shadow onto you and what the whole thing costs in video "
                                        + "memory and in building time — past it there is nothing "
                                        + "to hit, so shadows fade out over the last quarter "
                                        + "rather than ending at a circle drawn around you. Raise "
                                        + "it if you can see where the shadows stop.",
                                Cost.of(Level.LOW, Level.MEDIUM, Level.MEDIUM), null,
                                32, 256, 16, " blocks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getRayTracingRadius();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setRayTracingRadius(value);
                                    }
                                }),
                        new VRangeOption("Traced Light Shadows",
                                "How many moving lights a surface may ask whether anything stands "
                                        + "in the way. A carried torch, a burning creature or a "
                                        + "dropped glowing block is added to the world as a "
                                        + "straight line from the source with a falloff — nothing "
                                        + "in this game's lighting knows what is between two "
                                        + "points, which is why a torch lights the far side of a "
                                        + "wall and the room around a corner. Tracing that line is "
                                        + "what stops it, and it is one ray per light per surface, "
                                        + "which is what this number is buying. Most surfaces have "
                                        + "one such light near them or none. Needs Terrain "
                                        + "Acceleration Structures.",
                                Cost.of(Level.NONE, Level.MEDIUM, Level.NONE),
                                "Needs Terrain Acceleration Structures.",
                                0, 8, 1, " lights", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTracedLights();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setTracedLights(value);
                                    }
                                }),
                        new VRangeOption("Light Shadow Softness",
                                "How soft the edge of a shadow cast by a torch or a fire is, kept "
                                        + "apart from the sun's: a small flame a step away and a "
                                        + "star a long way off are not the same kind of source and "
                                        + "do not want the same edge. Zero gives the perfectly "
                                        + "crisp one.",
                                Cost.FREE, "Needs Traced Light Shadows.",
                                0, 100, 5, "%", "HARD",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getLightSoftness();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setLightSoftness(value);
                                    }
                                }),
                        new VRangeOption("Traced Block Light",
                                "How much of the game's own block light to replace with light "
                                        + "traced from the blocks that emit it. Vanilla's block "
                                        + "light is a flood fill through air: it reaches around "
                                        + "corners correctly and it is completely flat, because it "
                                        + "is one number per block with no idea where the light "
                                        + "came from — a torch on one wall lights a room exactly "
                                        + "as a torch on the other does. Traced light has a "
                                        + "direction and casts a shadow. What it costs is range: "
                                        + "only sources near you are found and only the nearest "
                                        + "thirty-two fit, so turned up fully, a cave lit from "
                                        + "further off goes dark. Needs Traced Light Shadows above "
                                        + "zero.",
                                Cost.of(Level.LOW, Level.HIGH, Level.NONE),
                                "Needs Terrain Acceleration Structures.",
                                0, 100, 5, "%", "OFF",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTracedBlockLight();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setTracedBlockLight(value);
                                    }
                                }),
                        new VRangeOption("Block Light Search",
                                "How far around you light-emitting blocks are looked for, in "
                                        + "blocks. The search runs a few times a second rather "
                                        + "than every frame, and is spread across frames — "
                                        + "placing a torch lights the room within a fraction of "
                                        + "a second instead of within a frame, which nobody can "
                                        + "see. Whole sixteen-block sections holding no block "
                                        + "light are dismissed without being read, so widening "
                                        + "this costs far less than the volume suggests.",
                                Cost.of(Level.LOW, Level.NONE, Level.NONE),
                                "Needs Traced Block Light.",
                                4, 50, 2, " blocks", null,
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getBlockLightRadius();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setBlockLightRadius(value);
                                    }
                                }),
                        new VRangeOption("Frame Accumulation",
                                "How much of what a pixel looked like last frame it keeps. A "
                                        + "traced shadow is worked out from one ray per pixel, "
                                        + "and one ray is grain; the rays are aimed differently "
                                        + "each frame and averaged here, which is what turns them "
                                        + "into a soft edge. History is thrown away wherever it "
                                        + "would smear instead of smooth — off the edge of the "
                                        + "screen, on anything that has just changed, and more "
                                        + "the faster you are turning. Costs one fullscreen pass "
                                        + "and does nothing at all unless something is traced.",
                                Cost.of(Level.NONE, Level.LOW, Level.LOW),
                                "Needs a traced effect to be on.",
                                0, 100, 5, "%", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getTemporalAccumulation();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setTemporalAccumulation(value);
                                    }
                                })));
    }
}
