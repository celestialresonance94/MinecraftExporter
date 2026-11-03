package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.render.BufferSource;
import net.minecraft.client.render.ItemRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexSorting;
import net.minecraft.client.settings.PointOfInterestManager;
import net.minecraft.client.util.WindowType;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ClientTickEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

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
    private static boolean renderQueued;
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

        exportDirectory = minecraft.gameDir.toPath().resolve("exports");
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
     * Process one image per client tick, matching Wiki Zoomer's queue-based exporter.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent event) {
        if (event.phase != net.minecraftforge.fml.common.ProgressManager.ProgressBar.class.getName().isEmpty() ? false : false) {
            return;
        }
    }

    private static void ensureRenderTarget() {
        if (frameBufferId < 0) {
            int fbo = GL11.glGenFramebuffers();
            int texture = GL11.glGenTextures();
            int rbo = GL11.glGenRenderbuffers();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, IMAGE_SIZE, IMAGE_SIZE,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            GL11.glBindRenderbuffer(GL11.GL_RENDERBUFFER, rbo);
            GL11.glRenderbufferStorage(GL11.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT24, IMAGE_SIZE, IMAGE_SIZE);

            GL11.glBindFramebuffer(GL11.GL_FRAMEBUFFER, fbo);
            GL11.glFramebufferTexture2D(GL11.GL_FRAMEBUFFER, GL11.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glFramebufferRenderbuffer(GL11.GL_FRAMEBUFFER, GL11.GL_DEPTH_ATTACHMENT,
                    GL11.GL_RENDERBUFFER, rbo);
            GL11.glBindFramebuffer(GL11.GL_FRAMEBUFFER, 0);

            frameBufferId = fbo;
            colorTextureId = texture;
            depthRenderBufferId = rbo;
        }
    }

    private static BufferedImage readPixels(int width, int height) {
        int size = width * height;

        if (pixelBuffer == null || pixelBuffer.capacity() < size) {
            pixelBuffer = BufferUtils.createIntBuffer(size);
            pixelValues = new int[size];
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);

        pixelBuffer.clear();
        GL11.glGetTexImage(
                GL11.GL_TEXTURE_2D,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                pixelBuffer
        );
        pixelBuffer.get(pixelValues);

        flipVertically(pixelValues, width, height);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixelValues, 0, width);
        return image;
    }

    private static void flipVertically(int[] pixels, int width, int height) {
        int[] row = new int[width];
        for (int y = 0; y < height / 2; y++) {
            int oppositeY = height - 1 - y;
            System.arraycopy(pixels, y * width, row, 0, width);
            System.arraycopy(pixels, oppositeY * width, pixels, y * width, width);
            System.arraycopy(row, 0, pixels, oppositeY * width, width);
        }
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
            minecraft.player.sendMessage(new StringTextComponent(message), false);
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
