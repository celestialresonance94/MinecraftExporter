package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import org.joml.Matrix4f;
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

/**
 * Batch exports registered item models as individual transparent PNGs.
 * Block items are included in the item registry, so their inventory models are
 * exported into the items folder. No separate world-block rendering is performed.
 *
 * The off-screen rendering setup follows the pattern used by Wiki Zoomer
 * Unofficial's 1.20.1 Forge exporter (ExportManager): TextureTarget,
 * orthographic projection with Z compression, rotX/rotY camera pose,
 * usesBlockLight-based Lighting.setupFor3DItems/setupForFlatItems selection,
 * ItemRenderer.render in the GUI context, and OpenGL texture readback.
 */
@Mod.EventBusSubscriber(modid = "minecraftexporter", value = Dist.CLIENT)
public final class RenderImageExporter {
    private static final int IMAGE_SIZE = 256;

    // Wiki Zoomer's default item camera angles (ZoomerSessionConfig.ItemConfig
    // defaults): rotX = 0, rotY = 0. The 30/45 pair belongs to its ENTITY
    // defaults only. With zero rotation, vanilla's GUI display context supplies
    // the standard inventory orientation for every model - flat sprites stay
    // upright and block items get vanilla's built-in cube tilt - while the
    // usesBlockLight-based Lighting selection below fixes the shading.
    private static final float ROT_X_DEGREES = 0.0F;
    private static final float ROT_Y_DEGREES = 0.0F;

    private static final Deque<RenderEntry> QUEUE = new ArrayDeque<>();

    private static TextureTarget renderTarget;
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

        String selectedMod = modId.toLowerCase(java.util.Locale.ROOT);
        if (!selectedMod.equals("all") && !selectedMod.matches("[a-z0-9_.-]+")) {
            tell("Invalid mod ID: " + modId);
            return 0;
        }

        List<RenderEntry> entries = collectItems(selectedMod);
        if (entries.isEmpty()) {
            tell("No renderable items found for mod ID: " + selectedMod);
            return 0;
        }

        exportDirectory = minecraft.gameDirectory.toPath().resolve("exports");
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
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || !matchesMod(id, modId)) {
                continue;
            }

            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                entries.add(new RenderEntry(id, stack));
            }
        }

        entries.sort(Comparator
                .comparing((RenderEntry entry) -> entry.id().getNamespace())
                .thenComparing(entry -> entry.id().getPath()));
        return entries;
    }

    private static boolean matchesMod(ResourceLocation id, String modId) {
        return modId.equals("all") || id.getNamespace().equals(modId);
    }

    /**
     * Process one image per client tick, matching Wiki Zoomer's queue-based exporter.
     * In Forge 1.20.1 the client tick event is TickEvent.ClientTickEvent (from
     * net.minecraftforge.event), fired with a phase (START/END); END runs after ticking.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!running || renderQueued) {
            return;
        }

        if (QUEUE.isEmpty()) {
            finishExport();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderEntry entry = QUEUE.removeFirst();
        renderQueued = true;

        Runnable task = () -> {
            boolean success = false;
            try {
                renderOne(minecraft, entry);
                success = true;
            } catch (Exception exception) {
                failed++;
                System.err.println("[MinecraftExporter] Failed to render "
                        + entry.id() + ": " + exception);
                exception.printStackTrace();
            } finally {
                if (success) {
                    completed++;
                }
                renderQueued = false;

                int processed = completed + failed;
                if (processed >= total) {
                    finishExport();
                } else if (processed % 25 == 0) {
                    tell("Render export progress: " + processed + "/" + total);
                }
            }
        };

        if (RenderSystem.isOnRenderThread()) {
            task.run();
        } else {
            RenderSystem.recordRenderCall(task::run);
        }
    }

    private static void renderOne(Minecraft minecraft, RenderEntry entry) throws IOException {
        RenderSystem.assertOnRenderThread();

        ensureRenderTarget();

        Path output = exportDirectory
                .resolve(entry.id().getNamespace())
                .resolve(entry.id().getPath().replace('/', '_') + ".png");
        Files.createDirectories(output.getParent());

        RenderTarget previousTarget = minecraft.getMainRenderTarget();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        boolean projectionChanged = false;
        boolean targetBound = false;

        try {
            // Match the target clear/bind/viewport sequence used by Wiki Zoomer's exporter.
            renderTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            renderTarget.clear(Minecraft.ON_OSX);
            renderTarget.bindWrite(true);
            targetBound = true;

            RenderSystem.viewport(0, 0, IMAGE_SIZE, IMAGE_SIZE);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            // Wiki Zoomer disables culling for export renders. With culling on,
            // any model whose winding ends up reversed after our pose rotations
            // disappears entirely or shows only one side, so follow its lead.
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // 1.20.1's RenderSystem requires a VertexSorting alongside the matrix.
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(
                            0.0F, IMAGE_SIZE,
                            IMAGE_SIZE, 0.0F,
                            1000.0F, 3000.0F
                    ),
                    VertexSorting.ORTHOGRAPHIC_Z
            );
            projectionChanged = true;

            PoseStack poseStack = new PoseStack();
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.0F, -2000.0F);
            // Wiki Zoomer applies this Z compression between the screen-space
            // translate and the item render. Without it, depth values written
            // by rotated 3D block models fall outside the orthographic near/far
            // range and the depth test rejects whole faces mid-rotation.
            poseStack.scale(1.0F, 1.0F, 0.01F);

            renderItemCentered(
                    minecraft,
                    poseStack,
                    bufferSource,
                    entry.stack(),
                    IMAGE_SIZE
            );

            bufferSource.endBatch();
            // Restore the game's own lighting state before popping/returning,
            // so subsequent in-game rendering is not left on the fixed-brightness
            // setup we applied inside renderItemCentered.
            Lighting.setupFor3DItems();
            poseStack.popPose();

            // Restore the game's projection before reading pixels, as Wiki Zoomer does.
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorting.ORTHOGRAPHIC_Z);
            projectionChanged = false;

            BufferedImage image = readPixels(IMAGE_SIZE, IMAGE_SIZE, renderTarget);
            ImageIO.write(image, "png", output.toFile());
        } finally {
            if (projectionChanged) {
                RenderSystem.setProjectionMatrix(previousProjection, VertexSorting.ORTHOGRAPHIC_Z);
            }

            if (targetBound) {
                renderTarget.unbindWrite();
            }

            previousTarget.bindWrite(true);
            RenderSystem.viewport(
                    0, 0,
                    minecraft.getWindow().getWidth(),
                    minecraft.getWindow().getHeight()
            );

            // Restore predictable state for the following game frame.
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            Lighting.setupFor3DItems();
        }
    }

    private static void ensureRenderTarget() {
        if (renderTarget == null) {
            renderTarget = new TextureTarget(IMAGE_SIZE, IMAGE_SIZE, true, Minecraft.ON_OSX);
        }
    }

    private static void renderItemCentered(
            Minecraft minecraft,
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ItemStack stack,
            int size
    ) {
        // Wiki Zoomer uses zoomPercent * 1.92 with its default 100% zoom,
        // which equals size * 0.96 in these screen-pixel units. This also
        // leaves the small margin around the model that wiki images have.
        float scale = size * 0.96F;

        poseStack.pushPose();
        poseStack.translate(size / 2.0F, size / 2.0F, 100.0F);
        poseStack.scale(scale, -scale, scale);

        // Same rotation sequence as Wiki Zoomer's renderItemCentered with its
        // default camera angles (rotX=30 pitch down, rotY=45 yaw). Combined
        // with vanilla's own GUI transforms this yields the familiar
        // three-quarter inventory view: top face on top, front and side faces
        // angled symmetrically toward the viewer.
        poseStack.mulPose(Axis.XP.rotationDegrees(ROT_X_DEGREES));
        poseStack.mulPose(Axis.YP.rotationDegrees(ROT_Y_DEGREES));

        BakedModel model = minecraft.getItemRenderer().getModel(stack, minecraft.level, null, 0);

        // Lighting selection copied from Wiki Zoomer: models that use block
        // light (all gui-3d block items) get vanilla's two-light directional
        // setup via Lighting.setupFor3DItems(), flat sprites get uniform
        // brightness via setupForFlatItems(). In 1.20.1 isGui3d() implies
        // usesBlockLight(), so this check covers both branches exactly as
        // Wiki Zoomer does.
        if (!model.usesBlockLight()) {
            Lighting.setupForFlatItems();
        } else {
            Lighting.setupFor3DItems();
        }

        // Forge 1.20.1: ItemRenderer.render(ItemStack, ItemDisplayContext, boolean leftHanded,
        // PoseStack, MultiBufferSource, int light, int overlay, BakedModel).
        // GUI, exactly like Wiki Zoomer's call: vanilla then applies its own
        // standard inventory transforms (the extra yaw for block-item cubes,
        // sprite centering for flat items) on top of our explicit pose above.
        // Sprite/override resolution is unaffected because
        // ForgeHooksClient.handleRenderState already maps GUI to NONE when we
        // fetched the baked model above.
        minecraft.getItemRenderer().render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model
        );

        poseStack.popPose();
    }

    private static BufferedImage readPixels(int width, int height, RenderTarget target) {
        int size = width * height;

        if (pixelBuffer == null || pixelBuffer.capacity() < size) {
            pixelBuffer = BufferUtils.createIntBuffer(size);
            pixelValues = new int[size];
        }

        target.bindRead();
        RenderSystem.bindTexture(target.getColorTextureId());

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
            minecraft.player.displayClientMessage(Component.literal(message), false);
        }
    }

    private record RenderEntry(ResourceLocation id, ItemStack stack) {
    }
}
