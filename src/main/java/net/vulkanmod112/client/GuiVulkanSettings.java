package net.vulkanmod112.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.vulkanmod112.Tags;
import net.vulkanmod112.VulkanBridge;
import net.vulkanmod112.VulkanLoader;

import java.io.IOException;

/** OptiFine-style compact settings page, opened from Video Settings. */
public final class GuiVulkanSettings extends GuiScreen {

    private static final int TERRAIN = 1;
    private static final int OVERLAY = 2;
    private static final int DONE = 200;
    private final GuiScreen parent;
    private GuiSlider renderDistance;
    private GuiButton terrainButton;
    private GuiButton overlayButton;

    public GuiVulkanSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int left = this.width / 2 - 155;
        this.renderDistance = new GuiSlider(0, left, 54, 310, 20,
                "Render Distance: ", " chunks", 2, 64,
                this.mc.gameSettings.renderDistanceChunks, true, true,
                new GuiSlider.ISlider() {
                    @Override
                    public void onChangeSliderValue(GuiSlider slider) {
                        int distance = slider.getValueInt();
                        mc.gameSettings.renderDistanceChunks = distance;
                        mc.gameSettings.saveOptions();
                    }
                });
        this.buttonList.add(this.renderDistance);
        this.terrainButton = new GuiButton(TERRAIN, left, 86, 150, 20, "");
        this.overlayButton = new GuiButton(OVERLAY, left + 160, 86, 150, 20, "");
        this.buttonList.add(this.terrainButton);
        this.buttonList.add(this.overlayButton);
        this.buttonList.add(new GuiButton(DONE, this.width / 2 - 100, this.height - 28, I18n.format("gui.done")));
        updateLabels();
    }

    private void updateLabels() {
        this.terrainButton.displayString = "Vulkan Terrain: " + (VulkanConfig.isTerrainEnabled() ? "ON" : "OFF");
        this.overlayButton.displayString = "Diagnostic Overlay: " + (VulkanConfig.isOverlayEnabled() ? "ON" : "OFF");
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) return;
        if (button.id == TERRAIN) {
            VulkanConfig.setTerrainEnabled(!VulkanConfig.isTerrainEnabled());
            updateLabels();
        } else if (button.id == OVERLAY) {
            VulkanConfig.setOverlayEnabled(!VulkanConfig.isOverlayEnabled());
            updateLabels();
        } else if (button.id == DONE) {
            this.mc.gameSettings.saveOptions();
            this.mc.displayGuiScreen(this.parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "VulkanMod112 Settings", this.width / 2, 15, 0xFFFFFF);
        VulkanBridge bridge = VulkanLoader.bridgeIfReady();
        String gpu = bridge == null ? "Vulkan is unavailable" : "Vulkan " + bridge.gpuSummary();
        this.drawCenteredString(this.fontRenderer, gpu, this.width / 2, 30, 0xA0A0A0);
        this.drawCenteredString(this.fontRenderer, "64 chunks can require a great deal of RAM and CPU; multiplayer servers may cap it.",
                this.width / 2, 122, 0xE0B060);
        this.drawCenteredString(this.fontRenderer, "Terrain toggle applies immediately. Restart after changing GPU or driver.",
                this.width / 2, 136, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
