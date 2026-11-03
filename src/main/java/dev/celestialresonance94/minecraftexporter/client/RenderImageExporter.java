package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.registry.Registry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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

@Mod.EventBusSubscriber(modid = "minecraftexporter", value = Dist.CLIENT)
public final class RenderImageExporter {
    private static final int IMAGE_SIZE = 256;
    private static final Deque<RenderEntry> QUEUE = new ArrayDeque<RenderEntry>();
    private static TextureTarget target;
    private static IntBuffer pixelBuffer;
    private static int[] pixels;
    private static boolean running;
    private static int completed, failed, total;
    private static String activeMod = "all";
    private static Path exportDirectory;

    private RenderImageExporter() {}

    public static int startExport(String modId) {
        Minecraft mc = Minecraft.getInstance();
        if (running) { tell("A render export is already running."); return 0; }
        String selected = modId.toLowerCase(java.util.Locale.ROOT);
        if (!"all".equals(selected) && !selected.matches("[a-z0-9_.-]+")) {
            tell("Invalid mod ID: " + modId); return 0;
        }
        List<RenderEntry> entries = collectItems(selected);
        if (entries.isEmpty()) { tell("No renderable items found for mod ID: " + selected); return 0; }
        exportDirectory = mc.gameDir.toPath().resolve("exports");
        try { Files.createDirectories(exportDirectory); }
        catch (IOException e) { tell("Could not create export folder: " + e.getMessage()); return 0; }
        QUEUE.clear(); QUEUE.addAll(entries);
        activeMod = selected; completed = 0; failed = 0; total = entries.size(); running = true;
        tell("Starting render export for '" + selected + "' (" + total + " images). Output: .minecraft/exports/");
        return 1;
    }

    private static List<RenderEntry> collectItems(String modId) {
        List<RenderEntry> entries = new ArrayList<RenderEntry>();
        for (Item item : Registry.ITEM) {
            ResourceLocation id = Registry.ITEM.getKey(item);
            if (id == null || (!"all".equals(modId) && !id.getNamespace().equals(modId))) continue;
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) entries.add(new RenderEntry(id, stack));
        }
        CollectionsSort.sort(entries);
        return entries;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) return;
        if (QUEUE.isEmpty()) { finishExport(); return; }
        Minecraft mc = Minecraft.getInstance();
        RenderEntry entry = QUEUE.removeFirst();
        Runnable renderTask = () -> {
            try { renderOne(mc, entry); completed++; }
            catch (Exception e) {
                failed++;
                System.err.println("[MinecraftExporter] Failed to render " + entry.id + ": " + e);
                e.printStackTrace();
            }
            int processed = completed + failed;
            if (processed >= total) finishExport();
            else if (processed % 25 == 0) tell("Render export progress: " + processed + "/" + total);
        };
        if (RenderSystem.isOnRenderThread()) renderTask.run();
        else RenderSystem.recordRenderCall(renderTask::run);
    }

    private static void renderOne(Minecraft mc, RenderEntry entry) throws IOException {
        if (target == null) target = new TextureTarget(IMAGE_SIZE, IMAGE_SIZE, true);
        Path output = exportDirectory.resolve(entry.id.getNamespace())
                .resolve(entry.id.getPath().replace('/', '_') + ".png");
        Files.createDirectories(output.getParent());

        RenderTarget previous = mc.getMainRenderTarget();
        IRenderTypeBuffer.Impl buffers = mc.getRenderTypeBuffers().getBufferSource();
        target.setClearColor(0, 0, 0, 0);
        target.clear(Minecraft.IS_RUNNING_ON_MAC);
        target.bindWrite(true);
        RenderSystem.viewport(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.color4f(1, 1, 1, 1);

        // 1.16.5 still uses the legacy projection stack. Save and restore it around
        // the off-screen render so the normal game frame is not affected.
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, IMAGE_SIZE, 0, IMAGE_SIZE, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        try {
            MatrixStack matrix = new MatrixStack();
            matrix.translate(0, 0, -2000);
            matrix.translate(IMAGE_SIZE / 2.0D, IMAGE_SIZE / 2.0D, 100.0D);
            matrix.scale(IMAGE_SIZE * 0.75F, -IMAGE_SIZE * 0.75F, IMAGE_SIZE * 0.75F);
            ItemStack stack = entry.stack;
            IBakedModel model = mc.getItemRenderer().getItemModelWithOverrides(stack, mc.world, null);
            if (model.isGui3d()) RenderHelper.setupFor3DItems();
            else RenderHelper.setupForFlatItems();
            mc.getItemRenderer().render(stack, ItemCameraTransforms.TransformType.GUI, false,
                    matrix, buffers, 15728880, OverlayTexture.NO_OVERLAY, model);
            buffers.finish();
            BufferedImage image = readPixels(target);
            ImageIO.write(image, "png", output.toFile());
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            target.unbindWrite();
            previous.bindWrite(true);
            RenderSystem.viewport(0, 0, mc.getMainWindow().getWidth(), mc.getMainWindow().getHeight());
            RenderSystem.color4f(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderHelper.setupFor3DItems();
        }
    }

    private static BufferedImage readPixels(RenderTarget renderTarget) {
        int size = IMAGE_SIZE * IMAGE_SIZE;
        if (pixelBuffer == null || pixelBuffer.capacity() < size) {
            pixelBuffer = BufferUtils.createIntBuffer(size);
            pixels = new int[size];
        }
        renderTarget.bindRead();
        RenderSystem.bindTexture(renderTarget.getColorTextureId());
        pixelBuffer.clear();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        pixelBuffer.get(pixels);
        for (int y = 0; y < IMAGE_SIZE / 2; y++) {
            int opposite = IMAGE_SIZE - 1 - y;
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int temp = pixels[y * IMAGE_SIZE + x];
                pixels[y * IMAGE_SIZE + x] = pixels[opposite * IMAGE_SIZE + x];
                pixels[opposite * IMAGE_SIZE + x] = temp;
            }
        }
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, IMAGE_SIZE, IMAGE_SIZE, pixels, 0, IMAGE_SIZE);
        return image;
    }

    private static void finishExport() {
        running = false; QUEUE.clear();
        tell("Render export finished for '" + activeMod + "': " + completed + " PNGs saved, "
                + failed + " failed. Output: " + exportDirectory);
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendMessage(new StringTextComponent(message), mc.player.getUUID());
    }

    private static class RenderEntry {
        final ResourceLocation id; final ItemStack stack;
        RenderEntry(ResourceLocation id, ItemStack stack) { this.id = id; this.stack = stack; }
    }

    private static class CollectionsSort {
        static void sort(List<RenderEntry> entries) {
            entries.sort(Comparator.comparing((RenderEntry e) -> e.id.getNamespace())
                    .thenComparing(e -> e.id.getPath()));
        }
    }
}
