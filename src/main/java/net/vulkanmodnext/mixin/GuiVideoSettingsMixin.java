package net.vulkanmodnext.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.vulkanmodnext.client.GuiVulkanSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/** Adds the VulkanMod button where OptiFine users expect it: Video Settings. */
@Mixin(GuiVideoSettings.class)
public abstract class GuiVideoSettingsMixin extends GuiScreen {

    private static final int VULKANMOD112_SETTINGS = 11202;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void vulkanmodnext$addButton(CallbackInfo ci) {
        // The options list owns y=32..height-32 and Done sits at height-27, so
        // the only free space is the strip above the list.
        this.buttonList.add(new GuiButton(VULKANMOD112_SETTINGS, this.width - 104, 6,
                100, 20, "VulkanModNext"));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void vulkanmodnext$openSettings(GuiButton button, CallbackInfo ci) throws IOException {
        if (button.id == VULKANMOD112_SETTINGS) {
            this.mc.displayGuiScreen(new GuiVulkanSettings((GuiScreen) (Object) this));
            ci.cancel();
        }
    }
}
