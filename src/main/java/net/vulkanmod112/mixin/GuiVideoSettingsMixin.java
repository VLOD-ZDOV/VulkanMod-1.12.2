package net.vulkanmod112.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.vulkanmod112.client.GuiVulkanSettings;
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
    private void vulkanmod112$addButton(CallbackInfo ci) {
        this.buttonList.add(new GuiButton(VULKANMOD112_SETTINGS, this.width / 2 - 100,
                this.height - 52, 200, 20, "VulkanMod112 Settings..."));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void vulkanmod112$openSettings(GuiButton button, CallbackInfo ci) throws IOException {
        if (button.id == VULKANMOD112_SETTINGS) {
            this.mc.displayGuiScreen(new GuiVulkanSettings((GuiScreen) (Object) this));
            ci.cancel();
        }
    }
}
