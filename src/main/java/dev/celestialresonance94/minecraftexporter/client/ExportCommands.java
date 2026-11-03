package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

@EventBusSubscriber(
        modid = "minecraftexporter",
        value = Dist.CLIENT
)
public final class ExportCommands {
    private ExportCommands() {}

    private static final SuggestionProvider<net.minecraft.commands.CommandSourceStack>
            REGISTRY_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(getRegistryNames(), builder);

    private static final SuggestionProvider<net.minecraft.commands.CommandSourceStack>
            LANGUAGE_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(getLanguageCodes(), builder);

    private static final SuggestionProvider<net.minecraft.commands.CommandSourceStack>
            MOD_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(getModIds(), builder);

    private static class RegistryArgumentType implements ArgumentType<String> {

        @Override
        public String parse(StringReader reader) {
            int start = reader.getCursor();

            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }

            if (reader.getCursor() == start) {
                throw new IllegalArgumentException("Expected registry ID");
            }

            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public Collection<String> getExamples() {
            return List.of(
                    "all"
            );
        }
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> root =
                net.minecraft.commands.Commands.literal("export")
                        .executes(context -> runExport(context, "all", "en_us", "all"));

        root.then(net.minecraft.commands.Commands.argument("registry", new RegistryArgumentType())
                .suggests(REGISTRY_SUGGESTIONS)
                .executes(context -> runExport(context,
                        context.getArgument("registry", String.class), "en_us", "all"))
                .then(net.minecraft.commands.Commands.argument("language", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .executes(context -> runExport(context,
                                context.getArgument("registry", String.class),
                                StringArgumentType.getString(context, "language"), "all"))
                        .then(net.minecraft.commands.Commands.argument("modid", StringArgumentType.word())
                                .suggests(MOD_SUGGESTIONS)
                                .executes(context -> runExport(context,
                                        context.getArgument("registry", String.class),
                                        StringArgumentType.getString(context, "language"),
                                        StringArgumentType.getString(context, "modid"))))));

        event.getDispatcher().register(root);

        // Separate command for exporting rendered block and item previews.
        event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("render")
                        .then(net.minecraft.commands.Commands.argument("modid", StringArgumentType.word())
                                .suggests(MOD_SUGGESTIONS)
                                .executes(context -> RenderImageExporter.startExport(
                                        StringArgumentType.getString(context, "modid")
                                )))
                        .executes(context -> RenderImageExporter.startExport("all"))
        );
    }

    private static int runExport(CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                 String registryArgument, String languageCode, String modId) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resources = minecraft.getResourceManager();

        try {
            Map<ResourceLocation, Registry<?>> registries = collectRegistries();
            Map<String, String> translations = loadTranslations(resources, languageCode);

            String requestedRegistry = normalizeRegistryArgument(registryArgument);
            ResourceLocation requestedRegistryId = requestedRegistry.equals("all")
                    ? null
                    : ResourceLocation.tryParse(requestedRegistry);

            if (!requestedRegistry.equals("all")
                    && (requestedRegistryId == null || !registries.containsKey(requestedRegistryId))) {
                tell(Component.literal("Unknown registry: " + registryArgument));
                return 0;
            }

            TreeSet<String> exportedNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<ResourceLocation, Registry<?>> registryEntry : registries.entrySet()) {
                ResourceLocation registryId = registryEntry.getKey();

                if (!requestedRegistry.equals("all") && !registryId.toString().equals(requestedRegistry)) {
                    continue;
                }

                Registry<?> registry = registryEntry.getValue();
                for (Object value : registry) {
                    ResourceLocation entryId = getEntryId(registry, value);
                    if (entryId == null) continue;
                    if (!modId.equalsIgnoreCase("all") && !entryId.getNamespace().equals(modId)) continue;

                    String translationKey = findTranslationKey(registryId, entryId, value);;
                    if (translationKey == null) continue;

                    String translatedName = translations.get(translationKey);
                    if (translatedName == null || translatedName.isBlank()) continue;

                    exportedNames.add(translatedName.trim());
                }
            }

            Path exportDirectory = minecraft.gameDirectory.toPath().resolve("exports");
            Files.createDirectories(exportDirectory);

            String safeRegistry = requestedRegistry.equals("all")
                    ? "all"
                    : requestedRegistry.replace(':', '_').replace('/', '_');
            String safeMod = modId.equalsIgnoreCase("all") ? "all" : modId;
            String fileName = "export_" + safeRegistry + "_" + languageCode + "_" + safeMod + ".txt";
            Path output = exportDirectory.resolve(fileName);

            Files.write(output, exportedNames, StandardCharsets.UTF_8);
            tell(Component.literal("Exported " + exportedNames.size() + " names to " + output));
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            tell(Component.literal("Export failed: " + exception.getMessage()));
            exception.printStackTrace();
            return 0;
        }
    }

    private static void tell(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        }
    }

    private static String normalizeRegistryArgument(String argument) {
        return argument.equalsIgnoreCase("all") ? "all" : argument;
    }

    private static Map<ResourceLocation, Registry<?>> collectRegistries() {
        Map<ResourceLocation, Registry<?>> found = new TreeMap<>();

        var rootRegistry = net.minecraft.core.registries.BuiltInRegistries.REGISTRY;

        for (var entry : rootRegistry.entrySet()) {
            ResourceLocation key = entry.getKey().location();
            Registry<?> registry = entry.getValue();

            found.put(key, registry);
        }

        // Include dynamic registries from the currently joined world.
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level != null) {
            minecraft.level.registryAccess().registries().forEach(entry ->
                    found.put(entry.key().location(), entry.value()));
        }

        return found;
    }

    private static ResourceLocation getEntryId(Registry<?> registry, Object value) {
        @SuppressWarnings("unchecked")
        Registry<Object> typed = (Registry<Object>) registry;
        return typed.getKey(value);
    }

    private static List<String> getRegistryNames() {
        TreeSet<String> names = new TreeSet<>();
        names.add("all");

        Minecraft minecraft = Minecraft.getInstance();

        String language = minecraft.getLanguageManager().getSelected();
        Map<String, String> translations;

        try {
            translations = loadTranslations(
                    minecraft.getResourceManager(),
                    language
            );
        } catch (IOException exception) {
            exception.printStackTrace();
            return new ArrayList<>(names);
        }

        for (Map.Entry<ResourceLocation, Registry<?>> entry
                : collectRegistries().entrySet()) {

            ResourceLocation registryId = entry.getKey();
            Registry<?> registry = entry.getValue();

            if (hasTranslatedEntries(registryId, registry, translations)) {
                names.add(registryId.toString());
            }
        }

        return new ArrayList<>(names);
    }

    private static boolean hasTranslatedEntries(
            ResourceLocation registryId,
            Registry<?> registry,
            Map<String, String> translations
    ) {
        for (Object value : registry) {
            ResourceLocation entryId = getEntryId(registry, value);
            if (entryId == null) continue;

            String key = findTranslationKey(registryId, entryId, value);

            if (key != null && translations.containsKey(key)) {
                return true;
            }
        }

        return false;
    }

    private static List<String> getModIds() {
        TreeSet<String> ids = new TreeSet<>();
        ids.add("all");
        for (Map.Entry<ResourceLocation, Registry<?>> entry : collectRegistries().entrySet()) {
            for (Object value : entry.getValue()) {
                ResourceLocation id = getEntryId(entry.getValue(), value);
                if (id != null) ids.add(id.getNamespace());
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> getLanguageCodes() {
        TreeSet<String> codes = new TreeSet<>();
        codes.add("en_us");
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        for (ResourceLocation file : manager.listResources(
                "lang",
                path -> path.getPath().endsWith(".json")
        ).keySet()) {
            String path = file.getPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (filename.endsWith(".json")) {
                codes.add(filename.substring(0, filename.length() - 5));
            }
        }
        return new ArrayList<>(codes);
    }

    private static Map<String, String> loadTranslations(ResourceManager manager, String languageCode)
            throws IOException {
        Map<String, String> translations = new TreeMap<>();
        String path = "lang/" + languageCode.toLowerCase() + ".json";

        // Merge all namespaces. A higher-priority resource for a namespace is returned by getResource.
        for (String namespace : manager.getNamespaces()) {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace, path);
            var resource = manager.getResource(file);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().open()) {
                    Language.loadFromJson(input, translations::put);
                }
            }
        }
        return translations;
    }

    /**
     * Most game content classes expose getDescriptionId(). The fallback method name
     * covers a few vanilla classes that expose descriptionId() instead.
     */
    private static String findTranslationKey(
            ResourceLocation registryId,
            ResourceLocation entryId,
            Object value
    ) {
        // First, try the object's own translation key.
        for (String methodName : List.of("getDescriptionId", "descriptionId")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = method.invoke(value);

                if (result instanceof String key && !key.isBlank()) {
                    return key;
                }
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // This object doesn't expose a translation-key method.
            }
        }

        // Fallback: try type.modid.resource for any registry entry.
        String type = registryId.getPath();

        int slashIndex = type.lastIndexOf('/');
        if (slashIndex >= 0) {
            type = type.substring(slashIndex + 1);
        }

        String fallbackKey = type + "."
                + entryId.getNamespace() + "."
                + entryId.getPath();

        return fallbackKey;
    }
}
