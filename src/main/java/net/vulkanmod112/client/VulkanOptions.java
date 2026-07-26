package net.vulkanmod112.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.vulkanmod112.client.gui.VCyclingOption;
import net.vulkanmod112.client.gui.VOption;
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
 */
final class VulkanOptions {

    private VulkanOptions() {
    }

    static VOptionPage[] buildPages(final Minecraft mc) {
        return new VOptionPage[]{
                renderingPage(mc),
                optimizationsPage(mc),
                qualityPage(mc),
                advancedPage()
        };
    }

    private static VOptionPage renderingPage(final Minecraft mc) {
        return new VOptionPage("Rendering",
                new VOptionBlock("Vulkan",
                        new VSwitchOption("Vulkan Terrain",
                                "Draw the opaque world through Vulkan instead of OpenGL. "
                                        + "Turning it off returns to vanilla rendering immediately.",
                                VOption.Impact.HIGH, null,
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
                                        + "costs a lot of CPU and RAM, and servers may cap it anyway.",
                                VOption.Impact.HIGH, null, 2, 64, 1, " chunks", null,
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
                                        + "rate lowers GPU load and heat.",
                                VOption.Impact.MEDIUM, null, 10, 260, 10, " fps", null,
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
                        new VSwitchOption("VSync",
                                "Lock the framerate to the monitor's refresh rate. Removes tearing, "
                                        + "and caps FPS at your refresh rate.",
                                VOption.Impact.HIGH, null,
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
                                        + "farms and busy servers.",
                                VOption.Impact.HIGH, null, 0, 256, 8, " blocks", "Vanilla",
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
                                VOption.Impact.HIGH, null, 0, 128, 8, " blocks", "Vanilla",
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
                                VOption.Impact.LOW, null,
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
                new VOptionBlock("Textures",
                        new VSwitchOption("Animated Textures",
                                "Water, lava, fire, portals and every animated modded block upload a "
                                        + "new frame every tick, on screen or not. Turning them off is "
                                        + "a straight win in modpacks; the blocks just stop moving.",
                                VOption.Impact.MEDIUM, null,
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
                                VOption.Impact.MEDIUM, null,
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
                                        + "and chunk-build win, since it removes geometry.",
                                VOption.Impact.MEDIUM, null,
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
                                        + "time, not frame time.",
                                VOption.Impact.LOW, null,
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
                                        + "far away and is easier on the texture cache.",
                                VOption.Impact.LOW, "Applies after the texture atlas reloads.",
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

    private static VOptionPage advancedPage() {
        return new VOptionPage("Advanced",
                new VOptionBlock("Compositing",
                        new VSwitchOption("Depth Blit",
                                "Copy Vulkan's depth buffer into the game's with hardware blit "
                                        + "instead of writing it per pixel in a shader. Faster, but "
                                        + "needs a driver that can share a 24-bit depth target.",
                                VOption.Impact.MEDIUM, "Applies after the window is resized or the game restarts.",
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
                                        + "diagnose missing or inside-out geometry.",
                                VOption.Impact.MEDIUM, "Applies after the game restarts.",
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
                                VOption.Impact.LOW, null,
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
                                VOption.Impact.NONE, null, 1, 60, 1, " s", null,
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
                                VOption.Impact.LOW, null,
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

    /** Kept so the class is obviously client-side only. */
    static GameSettings settings(Minecraft mc) {
        return mc.gameSettings;
    }
}
