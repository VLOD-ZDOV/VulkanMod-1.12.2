package net.vulkanmodnext.vkimpl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
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
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Stage 2: the first actual Vulkan rendering in Minecraft 1.12.2.
 *
 * Renders a colored triangle into an offscreen VRAM image, then reads the
 * pixels back to a host buffer. The game side uploads them into an OpenGL
 * texture for display. This is deliberately the simplest possible end-to-end
 * pipeline: render pass, graphics pipeline, SPIR-V shaders, submit, readback.
 * Chunk rendering will replace the readback with zero-copy GL interop later.
 */
final class VkDemoRenderer {

    private static final Logger LOGGER = LogManager.getLogger("VulkanModNext/Demo");

    private final VulkanContextImpl ctx;
    private final int width;
    private final int height;

    private long image;
    private long imageMemory;
    private long imageView;
    private long renderPass;
    private long framebuffer;
    private long pipelineLayout;
    private long pipeline;
    private long commandPool;
    private long stagingBuffer;
    private long stagingMemory;
    private long fence;
    private VkCommandBuffer commandBuffer;
    private boolean ready;

    VkDemoRenderer(VulkanContextImpl ctx, int width, int height) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
    }

    private VkDevice device() {
        return ctx.getDevice();
    }

    synchronized void init() {
        if (ready) {
            return;
        }
        long start = System.nanoTime();
        createTargetImage();
        createRenderPass();
        createFramebuffer();
        createPipeline();
        createCommandPool();
        createStagingBuffer();
        createFence();
        ready = true;
        LOGGER.info("Demo pipeline ready in {} ms ({}x{})", (System.nanoTime() - start) / 1_000_000, width, height);
    }

    /** Renders one frame and returns tightly packed RGBA8 pixels (row-major, top row first). */
    synchronized ByteBuffer renderFrame() {
        init();
        try (MemoryStack stack = stackPush()) {
            recordCommandBuffer(stack);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(commandBuffer));
            VkQueue queue = ctx.getGraphicsQueue();
            check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit");
            check(vkWaitForFences(device(), fence, true, 1_000_000_000L), "vkWaitForFences");
            vkResetFences(device(), fence);

            int byteCount = width * height * 4;
            PointerBuffer ppData = stack.mallocPointer(1);
            check(vkMapMemory(device(), stagingMemory, 0, byteCount, 0, ppData), "vkMapMemory");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(ppData.get(0), byteCount);
            ByteBuffer pixels = ByteBuffer.allocateDirect(byteCount);
            pixels.put(mapped);
            pixels.rewind();
            vkUnmapMemory(device(), stagingMemory);
            return pixels;
        }
    }

    private void recordCommandBuffer(MemoryStack stack) {
        vkResetCommandPool(device(), commandPool, 0);

        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        check(vkBeginCommandBuffer(commandBuffer, begin), "vkBeginCommandBuffer");

        VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
        clear.get(0).color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);

        VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(renderPass)
                .framebuffer(framebuffer)
                .renderArea(VkRect2D.calloc(stack)
                        .extent(VkExtent2D.calloc(stack).width(width).height(height)))
                .pClearValues(clear);

        vkCmdBeginRenderPass(commandBuffer, rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        // Render pass finalLayout is TRANSFER_SRC_OPTIMAL: copy straight to the staging buffer
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageExtent(e -> e.width(width).height(height).depth(1));
        region.get(0).imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stagingBuffer, region);

        check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
    }

    private void createTargetImage() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().width(width).height(height).depth(1);

            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device(), imageInfo, null, pImage), "vkCreateImage");
            image = pImage.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device(), image, req);
            imageMemory = allocate(stack, req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
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
        }
    }

    private void createRenderPass() {
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
                    .finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

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
        }
    }

    private void createFramebuffer() {
        try (MemoryStack stack = stackPush()) {
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
        }
    }

    private void createPipeline() {
        try (MemoryStack stack = stackPush()) {
            long vertModule = createShaderModule(stack, "vulkanmodnext/shaders/demo.vert.spv");
            long fragModule = createShaderModule(stack, "vulkanmodnext/shaders/demo.frag.spv");

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
                    .viewportCount(1)
                    .pViewports(viewport)
                    .scissorCount(1)
                    .pScissors(scissor);

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

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
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
        try (InputStream in = VkDemoRenderer.class.getClassLoader().getResourceAsStream(resource)) {
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

    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
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
        }
    }

    private void createStagingBuffer() {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size((long) width * height * 4)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device(), bufferInfo, null, pBuffer), "vkCreateBuffer");
            stagingBuffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device(), stagingBuffer, req);
            stagingMemory = allocate(stack, req,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            check(vkBindBufferMemory(device(), stagingBuffer, stagingMemory, 0), "vkBindBufferMemory");
        }
    }

    private void createFence() {
        try (MemoryStack stack = stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device(), fenceInfo, null, pFence), "vkCreateFence");
            fence = pFence.get(0);
        }
    }

    private long allocate(MemoryStack stack, VkMemoryRequirements req, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device().getPhysicalDevice(), memProps);

        int typeIndex = -1;
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            boolean allowed = (req.memoryTypeBits() & (1 << i)) != 0;
            boolean matches = (memProps.memoryTypes(i).propertyFlags() & properties) == properties;
            if (allowed && matches) {
                typeIndex = i;
                break;
            }
        }
        if (typeIndex < 0) {
            throw new IllegalStateException("No suitable memory type (flags 0x" + Integer.toHexString(properties) + ")");
        }

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(typeIndex);
        LongBuffer pMemory = stack.mallocLong(1);
        check(vkAllocateMemory(device(), allocInfo, null, pMemory), "vkAllocateMemory");
        return pMemory.get(0);
    }

    synchronized void destroy() {
        if (!ready) {
            return;
        }
        VkDevice device = device();
        vkDestroyFence(device, fence, null);
        vkDestroyBuffer(device, stagingBuffer, null);
        vkFreeMemory(device, stagingMemory, null);
        vkDestroyCommandPool(device, commandPool, null);
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
