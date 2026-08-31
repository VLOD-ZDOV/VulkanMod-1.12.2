#!/usr/bin/env python3
"""Copies a file of the Vulkan half over from the 1.12.2 mod and adapts it.

The Vulkan half is meant to travel unchanged — it knows about Vulkan, not about
Minecraft — and on the whole it does. It travels with a handful of differences,
and they belong here rather than in the copies: **a hand edit made in a copied
file is undone, silently, the next time this runs.**

Two kinds of difference:

* **Mechanical.** 1.12.2 ships LWJGL 3.3, where a struct is allocated on a
  scratch stack with `Struct.malloc(stack)`; the game on 1.16.5 brings 3.2.2,
  where the same call is `Struct.mallocStack(stack)`. Neither spelling compiles
  against both. There are over three hundred of them, so a regex does it.
* **Named.** Everything else is listed below, each with the reason it exists.

Order matters twice over. Substitutions run **before** the mechanical rewrite,
because they are written against the 1.12.2 source as it sits on disk; and
within the list, a rule that rewrites part of what a later rule matches will
stop that later rule from matching at all.

Rules are scoped to the file they are about, so "this rule matched nothing" is
worth saying out loud: it means the 1.12.2 source moved and the copy is now
missing a change somebody decided it needed.

Usage: tools/port-from-112.py VkFile.java [more...]
"""

import io
import os
import re
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(os.path.dirname(HERE),
                      "src", "main", "java", "net", "vulkanmodnext", "vkimpl")
TARGET = os.path.join(HERE, "src", "main", "java", "net", "vulkanmodnext", "vkimpl")

# A struct allocation on a stack, in either of the two shapes 3.3 uses.
ONE_ARG = re.compile(r"\.(malloc|calloc)\(stack\)")
TWO_ARG = re.compile(r"\.(malloc|calloc)\(([^;]*?), stack\)")

GLOBAL = [
    # Only the setup half of VulkanContextImpl came across, under a name that
    # says so. Everything that took a reference to the old one wants this.
    ("VulkanContextImpl", "VkContext"),

    # Logger names are read by whoever is looking at a log and deciding which
    # mod to blame. Two mods logging under one name is a real confusion.
    ('LogManager.getLogger("VulkanModNext/', 'LogManager.getLogger("VulkanModNext/'),

    # A qualified stack push. Caught before the unqualified one below, or it
    # becomes MemoryStack.push() — a real method on a real class, which fails
    # only at the point of use.
    ("MemoryStack.stackPush()", "net.vulkanmodnext.VkStack.push()"),
]

PER_FILE = OrderedDict()

PER_FILE["Interop.java"] = [
    # org.lwjgl.system.windows.Kernel32 arrived after 3.2.2. Exactly one
    # function address is wanted from it, and APIUtil.apiCreateLibrary is
    # spelled the same way in both versions. Windows-only either way.
    ("import org.lwjgl.system.windows.Kernel32;",
     "import org.lwjgl.system.APIUtil;"),
    ('Kernel32.getLibrary().getFunctionAddress("CloseHandle")',
     'APIUtil.apiCreateLibrary("kernel32").getFunctionAddress("CloseHandle")'),
]

PER_FILE["VertexLayout.java"] = [
    # A vanilla chunk vertex is 28 bytes on 1.12.2 and 32 here. The difference
    # is a three-byte normal and a byte of padding appended at the end, so the
    # first 28 bytes — position, colour, texture, light — are laid out
    # identically and every field offset in the packing is already right. Only
    # the step from one vertex to the next changes.
    #
    # It is one constant, and it is the one that hurts if it is wrong: a stride
    # off by four bytes raises no error at all, it draws a world of spikes.
    ("public static final int SOURCE_STRIDE = 28;",
     "public static final int SOURCE_STRIDE = 32;"),
]

PER_FILE["VkChunkMirror.java"] = [
    # Ray tracing: a flag that only means something once an acceleration
    # structure is going to be built. Turned into a constant false rather than
    # deleted — the code around it, where the flag goes and when the buffer has
    # to be reallocated because of it, is what somebody will need on the day
    # this version wants ray tracing.
    ("""    private static final int RAY_TRACING_BUFFER_USAGE =
            org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | org.lwjgl.vulkan.KHRAccelerationStructure
                            .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;""",
     """    // Zero here, and the two constants that belong in it live in Vulkan 1.2
    // and KHR_acceleration_structure — neither of which exists in the bindings
    // this version of the game brings. See VkContext.pickApiVersion.
    private static final int RAY_TRACING_BUFFER_USAGE = 0;"""),
    ("this.rayTracing = ctx.isRayTracingEnabled();",
     "this.rayTracing = false; // see RAY_TRACING_BUFFER_USAGE"),
    ("                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)",
     "                        .flags(0) // VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT, when 1.2 is reachable"),

    # The staging ring is built lazily on 1.12.2, by the render thread, the
    # first time it mirrors a chunk itself — and it always does, because that
    # version uploads some chunks inline. Here every chunk arrives on a builder
    # thread and nobody was left to build it: two hundred layers offered, none
    # taken, and not one error anywhere. stageFromWorker is written never to
    # allocate and never to take a slow lock, which is worth keeping, so the
    # ring is made once, up front, by a thread that is allowed to.
    ("    private void ensureStagingRing(int needed) {",
     """    /** Creates the staging ring before any builder thread asks for a range. */
    synchronized void prime() {
        ensureStagingRing((int) STAGING_RING_MIN);
    }

    private void ensureStagingRing(int needed) {"""),
]

PER_FILE["VkTerrainRenderer.java"] = [
    # The one block that cannot be kept: writing an acceleration structure into
    # a descriptor set needs a struct type that does not exist in these
    # bindings, and unlike a flag there is nothing to point a constant at. The
    # body goes; the method, its guard, its caller and the words describing what
    # it did all stay, because the placement is the part that took the work —
    # one structure per frame slot, rewritten only when the handle changes,
    # because pointing descriptors at it stops the device.
    #
    # Listed before the descriptorCount rule below, which would otherwise
    # rewrite a line inside this block and stop it matching.
    ("""        vkDeviceWaitIdle(device());
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.longs(structure);
            org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR structureInfo =
                    org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType(org.lwjgl.vulkan.KHRAccelerationStructure
                                    .VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                            .pAccelerationStructures(handle);
            // Only this slot's sets: the other slots name the structures their
            // own frames are still reading.
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(BATCHES_PER_FRAME, stack);
            for (int i = 0; i < BATCHES_PER_FRAME; i++) {
                writes.get(i)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .pNext(structureInfo.address())
                        .dstSet(drawDescriptorSets[slot * BATCHES_PER_FRAME + i]).dstBinding(7)
                        // Not taken from pAccelerationStructures: the count in
                        // the write is what the driver reads, and the chained
                        // structure carries the handles it counts.
                        .descriptorCount(1)
                        .descriptorType(org.lwjgl.vulkan.KHRAccelerationStructure
                                .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }
            vkUpdateDescriptorSets(device(), writes, null);
        }
        LOGGER.info("Shaders can now trace against the terrain");""",
     """        // What this does on 1.12.2, and cannot do here: wait for the device,
        // chain a VkWriteDescriptorSetAccelerationStructureKHR onto one
        // descriptor write per batch of this frame slot, and point binding 7 at
        // the structure. Only this slot's sets are touched — the other slots
        // name structures their own frames are still reading.
        //
        // None of those types exist in LWJGL 3.2.2, and unlike the flags
        // elsewhere there is no constant to stand in for a struct. Nothing
        // reaches this either: no structure is ever built.
        throw new UnsupportedOperationException(
                "no acceleration structure on this version; see VkContext.rayTracingStatus()");"""),

    # The descriptor layout and pool entries for the structure. Both sit behind
    # a `tracing` flag that is always false here.
    ("""            bindings.get(6).binding(7)
                    .descriptorType(org.lwjgl.vulkan.KHRAccelerationStructure
                            .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)""",
     """            bindings.get(6).binding(7)
                    .descriptorType(0) // VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR"""),
    ("""            poolSizes.get(3).type(org.lwjgl.vulkan.KHRAccelerationStructure
                    .VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)""",
     """            poolSizes.get(3).type(0) // VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR"""),

    # Buffer usage flags for geometry an acceleration structure would read.
    ("""                usage |= org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | org.lwjgl.vulkan.KHRAccelerationStructure
                                .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;""",
     """                usage |= 0; // the 1.2 and KHR_acceleration_structure bits, when reachable"""),
    ("""                            ? org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | org.lwjgl.vulkan.KHRAccelerationStructure
                                    .VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR""",
     """                            ? 0 // the 1.2 and KHR bits, when reachable"""),

    # In 3.2.2 a *descriptor write* does not take a count: it derives one from
    # the capacity of whichever pXInfo buffer is attached, and every site here
    # attaches one. The setter arrived later.
    #
    # Written as a pattern, and narrowly, because the first version of this rule
    # was a plain ".descriptorCount(1)" and it hit seventeen places instead of
    # eight. The other nine are on VkDescriptorSetLayoutBinding, where the
    # setter exists in both versions and is *required* — and a layout binding
    # with a count of zero has no descriptors at all, so the shader reads
    # nothing and the frame comes out black.
    #
    # It cost an evening, and nothing in the log said so: the driver drew the
    # empty frame without complaint. The validation layer named it in one line.
    # The lesson is the rule's shape, not the rule: a blanket text replacement
    # in a hundred thousand lines will find things it was not looking for.
    (re.compile(r"(\.dstBinding\(\w+\))\.descriptorCount\(1\)"), r"\1"),

    # A way in to the image Vulkan draws into, for the probe that reads it
    # back. It is the one question that splits a black screen in half: terrain
    # in this image and not on the screen is the composite's fault; nothing in
    # either is the Vulkan draw's. Kept in the script rather than the copy so
    # that re-porting does not take the instrument away.
    ("    private void composite() {",
     """    /** The OpenGL name of the image Vulkan draws the terrain into. */
    public synchronized int sharedColourTexture() {
        return glColorTexture;
    }

    private void composite() {"""),

    # The same memory-allocation flag as in the mirror, twice. Zero rather than
    # deleted for the same reason: where it goes is the part worth keeping.
    ("                        .flags(org.lwjgl.vulkan.VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT)",
     "                        .flags(0) // VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT, when 1.2 is reachable"),

    # Smart animations are a game-side feature and are not ported yet. A report
    # that prints a section for something that does not exist is worse than one
    # that omits it.
    ("""        String animations = net.vulkanmodnext.client.AnimatedSprites.stats();
        if (animations != null && net.vulkanmodnext.client.VulkanConfig.isSmartAnimations()) {
            sb.append(animations).append('\\n');
        }""",
     """        // Smart animations are a game-side feature and are not ported yet."""),
]


def convert(text, rules, named):
    counts = {}

    for before, after in rules:
        # A rule is either a literal or a compiled pattern. Patterns are for the
        # cases where a literal would be too greedy — see descriptorCount.
        if hasattr(before, "sub"):
            text, hits = before.subn(after, text)
        elif before in text:
            hits = text.count(before)
            text = text.replace(before, after)
        else:
            hits = 0
        if hits:
            counts["renamed calls"] = counts.get("renamed calls", 0) + hits
        elif (before, after) in named:
            # A rule written for this very file found nothing: the 1.12.2 source
            # moved under this script, and the copy is now missing a change
            # somebody decided it needed. Silence here is a compile error four
            # hundred lines away, or worse, none at all.
            counts["RULES THAT MATCHED NOTHING"] = counts.get(
                    "RULES THAT MATCHED NOTHING", 0) + 1

    def one(match):
        counts["stack allocations"] = counts.get("stack allocations", 0) + 1
        return ".%sStack(stack)" % match.group(1)

    def two(match):
        counts["sized stack allocations"] = counts.get("sized stack allocations", 0) + 1
        return ".%sStack(%s, stack)" % (match.group(1), match.group(2))

    text = ONE_ARG.sub(one, text)
    text = TWO_ARG.sub(two, text)

    pushes = text.count("stackPush()")
    if pushes:
        counts["stack frames"] = pushes
        text = text.replace("import static org.lwjgl.system.MemoryStack.stackPush;",
                            "import static net.vulkanmodnext.VkStack.push;")
        text = text.replace("stackPush()", "push()")
    return text, counts


def main(names):
    if not names:
        print(__doc__)
        return 2
    os.makedirs(TARGET, exist_ok=True)
    failed = False
    for name in names:
        source = os.path.join(SOURCE, name)
        if not os.path.exists(source):
            print("not in the 1.12.2 mod: " + name, file=sys.stderr)
            return 1
        text = io.open(source, encoding="utf-8").read()
        named = PER_FILE.get(name, [])
        converted, counts = convert(text, GLOBAL + named, named)
        io.open(os.path.join(TARGET, name), "w", encoding="utf-8").write(converted)
        summary = ", ".join("%d %s" % (v, k) for k, v in sorted(counts.items())) or "nothing"
        print("%-28s %d lines, adapted: %s" % (name, text.count("\n") + 1, summary))
        failed = failed or "RULES THAT MATCHED NOTHING" in counts
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
