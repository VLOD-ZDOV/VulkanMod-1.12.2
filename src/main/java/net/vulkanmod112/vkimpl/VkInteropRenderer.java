package net.vulkanmod112.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;

/**
 * Zero-copy Vulkan → OpenGL presentation path (stage 3.1).
 *
 * The color image lives in VRAM once: Vulkan renders into it, OpenGL samples
 * it as a regular texture. Sharing works through VK_KHR_external_memory_fd on
 * the Vulkan side and GL_EXT_memory_object_fd on the GL side; ordering is
 * enforced GPU-side with a pair of shared semaphores (VK signal → GL wait for
 * each frame, GL signal → VK wait so the next frame never overwrites pixels
 * still being displayed).
 *
 * The OpenGL calls here use LWJGL 3's GL bindings against the game's own GL
 * context (created by LWJGL 2) — a GL context does not care which binding
 * library talks to it. GL object ids produced here are context-global, so the
 * texture id crosses the bridge to the game side as a plain int.
 *
 * This same machinery will present the Vulkan-rendered terrain in stage 3.3;
 * for now it draws the rotating triangle to prove per-frame operation.
 */
final class VkInteropRenderer {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod112/Interop");

    private final VulkanContextImpl ctx;
    private final int width;
    private final int height;

    // Vulkan side
    private long image;
    private long imageMemory;
    private long imageView;
    private long renderPass;
    private long framebuffer;
    private long pipelineLayout;
    private long pipeline;
    private long commandPool;
    private long fence;
    private long vkSignalSemaphore; // VK signals when the frame is rendered, GL waits
    private long vkWaitSemaphore;   // GL signals when the frame was displayed, VK waits
    private VkCommandBuffer commandBuffer;

    // OpenGL side
    private int glMemoryObject;
    private int glTexture = -1;
    private int glWaitSemaphore;   // GL name of vkSignalSemaphore
    private int glSignalSemaphore; // GL name of vkWaitSemaphore

    private boolean ready;
    private boolean firstFrame = true;

    VkInteropRenderer(VulkanContextImpl ctx, int width, int height) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    int glTextureId() {
        return glTexture;
    }

    /** Must run on the client thread with the game's GL context current. */
    synchronized void init() {
        if (ready) {
            return;
        }
        long start = System.nanoTime();

        ctx.ensureGlCapabilities();

        long exportedSize = createExportedImage();
        importImageIntoGL(exportedSize);
        createSemaphores();
        createRenderPassAndPipeline();
        createCommands();

        ready = true;
        LOGGER.info("Zero-copy interop ready in {} ms: VK image <-> GL texture {} ({}x{}), {} KiB shared VRAM",
                (System.nanoTime() - start) / 1_000_000, glTexture, width, height, exportedSize / 1024);
    }

    /** Renders one frame on the Vulkan queue and queues the GL-side wait. */
    synchronized void renderFrame(float timeSeconds) {
        try (MemoryStack stack = stackPush()) {
            check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences");
            vkResetFences(device(), fence);

            recordCommandBuffer(stack, timeSeconds);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer))
                    .pSignalSemaphores(stack.longs(vkSignalSemaphore));
            if (!firstFrame) {
                submit.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(vkWaitSemaphore))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            }
            firstFrame = false;
            check(vkQueueSubmit(ctx.getGraphicsQueue(), submit, fence), "vkQueueSubmit");

            // GL command stream: wait for Vulkan before anything samples the texture
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            EXTSemaphore.glWaitSemaphoreEXT(glWaitSemaphore, noBuffers, textures, layouts);
        }
    }

    /** Call after the game has drawn the texture: lets Vulkan start the next frame. */
    synchronized void frameDisplayed() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer noBuffers = stack.mallocInt(0);
            IntBuffer textures = stack.ints(glTexture);
            IntBuffer layouts = stack.ints(EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT);
            EXTSemaphore.glSignalSemaphoreEXT(glSignalSemaphore, noBuffers, textures, layouts);
            GL11C.glFlush();
        }
    }

    private long createExportedImage() {
        try (MemoryStack stack = stackPush()) {
            VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                    .handleTypes(Interop.MEMORY_HANDLE_TYPE);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .pNext(external.address())
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(width).height(height).depth(1);

            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage");
            image = pImage.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device(), image, req);

            VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                    .image(image);
            VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                    .pNext(dedicated.address())
                    .handleTypes(Interop.MEMORY_HANDLE_TYPE);

            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .pNext(export.address())
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(stack, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device(), alloc, null, pMemory), "vkAllocateMemory(exported)");
            imageMemory = pMemory.get(0);
            check(vkBindImageMemory(device(), image, imageMemory, 0), "vkBindImageMemory");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device(), viewInfo, null, pView), "vkCreateImageView");
            imageView = pView.get(0);

            return req.size();
        }
    }

    private void importImageIntoGL(long size) {
        try (MemoryStack stack = stackPush()) {
            // Vulkan allocated with VkMemoryDedicatedAllocateInfo, so GL is
            // told so before the import (see Interop).
            glMemoryObject = Interop.importMemoryToGL(stack, device(), imageMemory, size, true);

            int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            glTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT,
                    EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, org.lwjgl.opengl.GL11.GL_RGBA8,
                    width, height, glMemoryObject, 0);
            // Leave the game's cached binding untouched
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);

            int glError = GL11C.glGetError();
            if (glError != 0) {
                throw new IllegalStateException("GL error 0x" + Integer.toHexString(glError) + " importing VK memory");
            }
        }
    }

    private void createSemaphores() {
        try (MemoryStack stack = stackPush()) {
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                    .handleTypes(Interop.SEMAPHORE_HANDLE_TYPE);
            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                    .pNext(export.address());

            LongBuffer pSem = stack.mallocLong(1);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(signal)");
            vkSignalSemaphore = pSem.get(0);
            check(vkCreateSemaphore(device(), semInfo, null, pSem), "vkCreateSemaphore(wait)");
            vkWaitSemaphore = pSem.get(0);

            glWaitSemaphore = importSemaphore(stack, vkSignalSemaphore);
            glSignalSemaphore = importSemaphore(stack, vkWaitSemaphore);
        }
    }

    private int importSemaphore(MemoryStack stack, long vkSemaphore) {
        return Interop.importSemaphoreToGL(stack, device(), vkSemaphore);
    }

    private void createRenderPassAndPipeline() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachment)
                    .pSubpasses(subpass);
            LongBuffer pRenderPass = stack.mallocLong(1);
            check(vkCreateRenderPass(device(), rpInfo, null, pRenderPass), "vkCreateRenderPass");
            renderPass = pRenderPass.get(0);

            VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(imageView))
                    .width(width)
                    .height(height)
                    .layers(1);
            LongBuffer pFb = stack.mallocLong(1);
            check(vkCreateFramebuffer(device(), fbInfo, null, pFb), "vkCreateFramebuffer");
            framebuffer = pFb.get(0);

            long vertModule = createShaderModule(stack, "vulkanmod112/shaders/interop.vert.spv");
            long fragModule = createShaderModule(stack, "vulkanmod112/shaders/interop.frag.spv");

            ByteBuffer entryPoint = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModule)
                    .pName(entryPoint);
            stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModule)
                    .pName(entryPoint);

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent(VkExtent2D.calloc(stack).width(width).height(height));
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).pViewports(viewport)
                    .scissorCount(1).pScissors(scissor);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .lineWidth(1.0f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blendAttachment);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(4);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pushRange);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device(), layoutInfo, null, pLayout), "vkCreatePipelineLayout");
            pipelineLayout = pLayout.get(0);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pColorBlendState(blend)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "vkCreateGraphicsPipelines");
            pipeline = pPipeline.get(0);

            vkDestroyShaderModule(device(), vertModule, null);
            vkDestroyShaderModule(device(), fragModule, null);
        }
    }

    private void createCommands() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.getGraphicsQueueFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), poolInfo, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device(), allocInfo, pCmd), "vkAllocateCommandBuffers");
            commandBuffer = new VkCommandBuffer(pCmd.get(0), device());

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence");
            fence = pFence.get(0);
        }
    }

    private void recordCommandBuffer(MemoryStack stack, float timeSeconds) {
        vkResetCommandBuffer(commandBuffer, 0);
        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");

        VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
        clear.get(0).color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 0.85f);

        VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(framebuffer)
                .renderArea(VkRect2D.calloc(stack)
                        .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                .pClearValues(clear);

        vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0,
                stack.floats(timeSeconds));
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);
        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    private long createShaderModule(MemoryStack stack, String resource) {
        byte[] code = readResource(resource);
        ByteBuffer buf = MemoryUtil.memAlloc(code.length);
        buf.put(code);
        buf.rewind();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buf);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device(), info, null, pModule), "vkCreateShaderModule " + resource);
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = VkInteropRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Shader " + resource + " not found on classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read shader " + resource, e);
        }
    }

    private int findMemoryType(MemoryStack stack, int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new IllegalStateException("No suitable memory type (flags 0x" + Integer.toHexString(properties) + ")");
    }

    synchronized void destroy() {
        if (!ready) {
            return;
        }
        VkDevice device = device();
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroySemaphore(device, vkSignalSemaphore, null);
        vkDestroySemaphore(device, vkWaitSemaphore, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyFramebuffer(device, framebuffer, null);
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyImageView(device, imageView, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, imageMemory, null);
        ready = false;
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed with VkResult " + result);
        }
    }

}
