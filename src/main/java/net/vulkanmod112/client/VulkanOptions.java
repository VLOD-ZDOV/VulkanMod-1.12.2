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
    private static final String[] BUDGET_LABELS = {"Auto", "256 MiB", "512 MiB", "1 GiB", "2 GiB", "4 GiB"};

    private VulkanOptions() {
    }

    static VOptionPage[] buildPages(final Minecraft mc) {
        return new VOptionPage[]{
                renderingPage(mc),
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
                        new VRangeOption("Render Distance",
                                "How far chunks are drawn. Beyond 32 the vanilla chunk grid itself "
                                        + "costs a lot of CPU and RAM before this mod sees anything, "
                                        + "and servers may cap it anyway. The single most expensive "
                                        + "setting in the game, on all three resources at once.",
                                Cost.of(Level.HIGH, Level.HIGH, Level.HIGH), null,
                                2, 64, 1, " chunks", null,
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
                        new VSwitchOption("Dynamic Lights",
                                "Let a carried torch, a dropped glowing block or a burning "
                                        + "creature light the ground around it. The light is added "
                                        + "while the world is being shaded rather than written "
                                        + "into it, so no chunk is rebuilt — and rebuilding chunks "
                                        + "is exactly what the frame is already waiting on while "
                                        + "you move, which is what makes the usual approach to "
                                        + "this cost so much. Any block that gives off light does, "
                                        + "including modded ones, because the value is read from "
                                        + "the block itself. It lights the terrain only: entities "
                                        + "and everything else still drawn by OpenGL are "
                                        + "unaffected.",
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
                        new VRangeOption("Visibility Walk Interval",
                                "How often the game may redo its visibility search while chunks are "
                                        + "still being rebuilt. That search decides which chunks are on "
                                        + "screen, and measured with the game's own profiler at render "
                                        + "distance 64 it is a quarter to a half of the entire frame — "
                                        + "eight times what drawing the world costs. It reruns whenever "
                                        + "the camera moves, which is fair, and also whenever any chunk "
                                        + "is queued for rebuild, which while a world fills in means "
                                        + "every frame even standing perfectly still. This limits only "
                                        + "the second case; camera movement always gets a fresh search. "
                                        + "The cost is that a chunk which just finished building can "
                                        + "wait this long before it appears, so raising it too far makes "
                                        + "the world visibly arrive in steps. Off by default.",
                                Cost.of(Level.HIGH, Level.NONE, Level.NONE), null,
                                0, 500, 10, " ms", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return VulkanConfig.getVisibilityWalkInterval();
                                    }

                                    @Override
                                    public void set(int value) {
                                        VulkanConfig.setVisibilityWalkInterval(value);
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
                                        + "difference is that vanilla builds a fresh queue, a fresh "
                                        + "set and an object per visited chunk every time, and this "
                                        + "reuses all of it. The game's own profiler puts its "
                                        + "version at a quarter to a half of the entire frame at "
                                        + "render distance 64, against 4.5% for drawing the world, "
                                        + "so it is the largest single item there is. Off by "
                                        + "default because it replaces the game's logic rather than "
                                        + "this mod's, and the way that goes wrong is that "
                                        + "something quietly stops being drawn. Turn it off again "
                                        + "if you see a chunk missing that comes back when you "
                                        + "approach it.",
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
                        new VCyclingOption("Particles",
                                "How many particles the game spawns. Minimal is a large win near "
                                        + "fire, potions and redstone.",
                                Cost.of(Level.HIGH, Level.MEDIUM, Level.NONE), null,
                                new String[]{"All", "Decreased", "Minimal"},
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
                                new String[]{"Fast", "Fancy"},
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
                                new String[]{"Off", "Minimum", "Maximum"},
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
                                "Applies after the texture atlas reloads.",
                                0, 4, 1, "", "Off",
                                new VRangeOption.Access() {
                                    @Override
                                    public int get() {
                                        return mc.gameSettings.mipmapLevels;
                                    }

                                    @Override
                                    public void set(int value) {
                                        if (value == mc.gameSettings.mipmapLevels) {
                                            return;
                                        }
                                        mc.gameSettings.mipmapLevels = value;
                                        mc.getTextureMapBlocks().setMipmapLevels(value);
                                        mc.getTextureManager().bindTexture(
                                                net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
                                        mc.getTextureMapBlocks().setBlurMipmapDirect(false, value > 0);
                                        mc.scheduleResourcesRefresh();
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
}
