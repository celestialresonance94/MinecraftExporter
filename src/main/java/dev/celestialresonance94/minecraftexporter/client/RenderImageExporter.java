package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Batch exports registered item models as individual transparent PNGs.
 * Block items are included in the item registry, so their inventory models
 * are exported into the items folder. No separate world-block rendering is
 * performed.
 *
 * The off-screen rendering setup follows the pattern used by Wiki Zoomer
 * Unofficial's exporter, adapted to the 1.16.5 (Forge) render pipeline:
 * framebuffer object, orthographic projection, ItemRenderer.renderOverlaidRecord-style
 * rendering via renderGeneric, and OpenGL texture readback.
 */
@Mod.EventBusSubscriber(modid = "minecraftexporter", value = Dist.CLIENT)
public final class RenderImageExporter {
    private static final int IMAGE_SIZE = 256;

    private static final Deque<RenderEntry> QUEUE = new ArrayDeque<>();

    private static int frameBufferId = -1;
    private static int colorTextureId = -1;
    private static int depthRenderBufferId = -1;
    private static IntBuffer pixelBuffer;
    private static int[] pixelValues;

    private static boolean running;
    private static int completed;
    private static int failed;
    private static int total;
    private static String activeModId = "all";
    private static Path exportDirectory;

    private RenderImageExporter() {
    }

    /**
     * Starts an export. "all" exports every namespace; otherwise only the given mod ID.
     */
    public static int startExport(String modId) {
        Minecraft minecraft = Minecraft.getInstance();

        if (running) {
            tell("A render export is already running.");
            return 0;
        }

        String selectedMod = modId.toLowerCase(Locale.ROOT);
        if (!selectedMod.equals("all") && !selectedMod.matches("[a-z0-9_.-]+")) {
            tell("Invalid mod ID: " + modId);
            return 0;
        }

        List<RenderEntry> entries = collectItems(selectedMod);
        if (entries.isEmpty()) {
            tell("No renderable items found for mod ID: " + selectedMod);
            return 0;
        }

        exportDirectory = minecraft.getFile("exports").toPath();
        try {
            Files.createDirectories(exportDirectory);
        } catch (IOException exception) {
            tell("Could not create export folder: " + exception.getMessage());
            exception.printStackTrace();
            return 0;
        }

        QUEUE.clear();
        QUEUE.addAll(entries);
        activeModId = selectedMod;
        completed = 0;
        failed = 0;
        total = entries.size();
        running = true;

        tell("Starting render export for '" + selectedMod + "' (" + total
                + " images). Output: .minecraft/exports/");
        return 1;
    }

    private static List<RenderEntry> collectItems(String modId) {
        List<RenderEntry> entries = new ArrayList<>();

        // Block items are deliberately included here. They use Minecraft's
        // inventory item model, which is the rendering path that works correctly.
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || !matchesMod(id, modId)) {
                continue;
            }

            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                entries.add(new RenderEntry(id, stack));
            }
        }

        entries.sort(Comparator
                .comparing((RenderEntry entry) -> entry.id.getNamespace())
                .thenComparing(entry -> entry.id.getPath()));
        return entries;
    }

    private static boolean matchesMod(ResourceLocation id, String modId) {
        return modId.equals("all") || id.getNamespace().equals(modId);
    }

    /**
     * Process one image per frame while an export is running. Rendering has
     * to happen inside the render loop (not a tick event) so the GL context
     * and the texture atlas are valid.
     */
    @SubscribeEvent
    public static void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        if (!running || event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        processNextEntry();
    }

    private static void processNextEntry() {
        RenderEntry entry = QUEUE.poll();
        if (entry == null) {
            finishExport();
            return;
        }

        try {
            BufferedImage image = renderItem(entry.stack);
            Path namespaceDir = exportDirectory.resolve(activeModId.equals("all")
                    ? entry.id.getNamespace()
                    : activeModId);
            Files.createDirectories(namespaceDir);
            Path output = namespaceDir.resolve(entry.id.getPath() + ".png");
            ImageIO.write(image, "png", output.toFile());
            completed++;
        } catch (Exception exception) {
            failed++;
            exception.printStackTrace();
        }
    }

    /**
     * Renders a single stack into the off-screen framebuffer using the same
     * pipeline the game uses for the inventory GUI (flat GUI-style quad for
     * block items, 3D baked transform for plain items).
     */
    private static BufferedImage renderItem(ItemStack stack) throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();

        ensureRenderTarget();

        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        IntBuffer previousViewport = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frameBufferId);
        GL11.glViewport(0, 0, IMAGE_SIZE, IMAGE_SIZE);

        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // The entity cutout shader reads the block-break overlay from texture
        // unit 1; bind the enchantment glint there so it stays transparent.
        RenderSystem.activeTexture(GL20.GL_TEXTURE0 + 1);
        minecraft.getTextureManager().bindTexture(ItemRenderer.ENABLE_ENTITY_GLINT_LOCATION);
        RenderSystem.activeTexture(GL20.GL_TEXTURE0);

        MatrixStack matrixStack = new MatrixStack();
        matrixStack.push();

        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        // Orthographic projection mapping the target to clip space [-1, 1].
        // Matrix4f stores values column-major; y points up in GL space (the
        // framebuffer readback below is flipped back to top-down order).
        float[] ortho = {
                2.0F / IMAGE_SIZE, 0.0F, 0.0F, 0.0F,
                0.0F, 2.0F / IMAGE_SIZE, 0.0F, 0.0F,
                0.0F, 0.0F, -2.0F, 0.0F,
                -1.0F, -1.0F, -1.0F, 1.0F
        };
        RenderSystem.multMatrix(new com.mojang.blaze3d.matrix.Matrix4f(ortho));

        // Model-view: center the image inside the render target.
        matrixStack.translate(IMAGE_SIZE / 2.0F, IMAGE_SIZE / 2.0F, 0.0F);
        matrixStack.scale(IMAGE_SIZE, IMAGE_SIZE, 1.0F);
        RenderSystem.multMatrix(matrixStack.getLast().getMatrix());

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        RenderSystem.disableRescaleNextFrame(true);

        IRenderTypeBuffer.Impl buffer = minecraft.getRenderBuffers().bufferSource();

        boolean renderedFlat = false;
        if (itemRenderer.isItemRenderedAsBlockEntity(stack)) {
            try {
                net.minecraft.client.renderer.model.IBakedModel model =
                        itemRenderer.getBlockItemModel(stack);
                // renderQuadsFlat draws with whatever texture is bound; the
                // block inventory model lives in the stitched atlas.
                minecraft.getTextureManager().bindTexture(
                        net.minecraft.client.renderer.Atlases.LOCATION_BLOCKS);
                itemRenderer.renderQuadsFlat(matrixStack, buffer, model,
                        0x000F00F0, OverlayTexture.NO_OVERLAY, true, true);
                renderedFlat = true;
            } catch (Exception ignored) {
                // Fall back to the generic item path below.
            }
        }

        if (!renderedFlat) {
            // Re-center for the 3D path: bake the gui3d transform around origin.
            matrixStack.pop();
            matrixStack.push();
            matrixStack.translate(0.5F, 0.5F, 0.0F);
            matrixStack.scale(IMAGE_SIZE, IMAGE_SIZE, 1.0F);
            RenderSystem.multMatrix(matrixStack.getLast().getMatrix());

            itemRenderer.renderGenericItem(
                    stack,
                    minecraft.gameRenderer.getActiveRenderInfo(),
                    true,
                    matrixStack,
                    buffer,
                    0x000F00F0, // full block + sky light
                    OverlayTexture.NO_OVERLAY,
                    itemRenderer.getItemModelWithOverrides(stack, null, null));
        }

        buffer.endBatch();
        matrixStack.pop();
        RenderSystem.popMatrix();
        RenderSystem.enableRescaleNextFrame(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);

        // Restore state and read back the pixels.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL11.glViewport(previousViewport.get(0), previousViewport.get(1),
                previousViewport.get(2), previousViewport.get(3));

        return readPixels(IMAGE_SIZE, IMAGE_SIZE);
    }

    private static void ensureRenderTarget() {
        if (frameBufferId < 0) {
            int fbo = GL30.glGenFramebuffers();
            int texture = GL11.glGenTextures();
            int rbo = GL30.glGenRenderbuffers();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, IMAGE_SIZE, IMAGE_SIZE,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT,
                    IMAGE_SIZE, IMAGE_SIZE);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER, rbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            frameBufferId = fbo;
            colorTextureId = texture;
            depthRenderBufferId = rbo;
            // Force a fresh readback buffer for the (possibly new) target.
            pixelBuffer = null;
        }
    }

    private static BufferedImage readPixels(int width, int height) {
        int size = width * height;

        if (pixelBuffer == null || pixelBuffer.capacity() < size) {
            pixelBuffer = BufferUtils.createIntBuffer(size);
            pixelValues = new int[size];
        }

        // glGetTexImage is unavailable in LWJGL 2, so read the pixels back
        // through the framebuffer the color texture is attached to.
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, frameBufferId);
        pixelBuffer.clear();
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        // glReadPixels returns rows bottom-up, so fill the image from the
        // last row upward instead of copying a pre-flipped array.
        pixelBuffer.get(pixelValues);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            // Bottom-up GL rows -> top-down image rows.
            image.setRGB(0, height - 1 - y, width, 1, pixelValues, y * width, width);
        }
        return image;
    }

    private static void finishExport() {
        running = false;
        QUEUE.clear();

        tell("Render export finished for '" + activeModId + "': "
                + completed + " PNGs saved, " + failed + " failed. Output: "
                + exportDirectory);
    }

    private static void tell(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendMessage(new StringTextComponent(message));
        }
    }

    private static final class RenderEntry {
        final ResourceLocation id;
        final ItemStack stack;

        RenderEntry(ResourceLocation id, ItemStack stack) {
            this.id = id;
            this.stack = stack;
        }
    }
}
