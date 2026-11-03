package dev.celestialresonance94.minecraftexporter.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.RegisterCommandEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.Language;
import net.minecraft.resources.IResourceManager;

/**
 * Forge 1.16.5 port of the registry-name exporter.
 *
 * Notable version differences handled here:
 *  - Client commands register through {@link RegisterCommandEvent} with a
 *    {@link CommandSource} source type (there is no CommandSourceStack and no
 *    RegisterClientCommandsEvent on 1.16.5).
 *  - Registries come from {@link ForgeRegistries} (BuiltInRegistries does not
 *    exist yet); dynamic/worldgen registries are not walkable client-side.
 *  - Translations are read straight from the lang JSON files because
 *    Language.loadFromJson() is private in this version.
 */
@Mod.EventBusSubscriber(
        modid = "minecraftexporter",
        value = Dist.CLIENT
)
public final class ExportCommands {
    private ExportCommands() {}

    private static final SuggestionProvider<CommandSource>
            REGISTRY_SUGGESTIONS = (context, builder) ->
            ISuggestionProvider.suggest(getRegistryNames(), builder);

    private static final SuggestionProvider<CommandSource>
            LANGUAGE_SUGGESTIONS = (context, builder) ->
            ISuggestionProvider.suggest(getLanguageCodes(), builder);

    private static final SuggestionProvider<CommandSource>
            MOD_SUGGESTIONS = (context, builder) ->
            ISuggestionProvider.suggest(getModIds(), builder);

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
            List<String> examples = new ArrayList<>();
            examples.add("all");
            return examples;
        }
    }

    /** A registry the exporter can walk. */
    private interface WalkableRegistry {
        Iterable<?> values();

        ResourceLocation keyOf(Object value);
    }

    private static final class ForgeWalk<T extends IForgeRegistryEntry<T>> implements WalkableRegistry {
        private final IForgeRegistry<T> registry;

        ForgeWalk(IForgeRegistry<T> registry) {
            this.registry = registry;
        }

        @Override
        public Iterable<?> values() {
            return this.registry.getValues();
        }

        @Override
        public ResourceLocation keyOf(Object value) {
            return this.registry.getKey(value);
        }
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterCommandEvent event) {
        LiteralArgumentBuilder<CommandSource> root =
                net.minecraft.command.Commands.literal("export")
                        .executes(context -> runExport(context, "all", "en_us", "all"));

        root.then(net.minecraft.command.Commands.argument("registry", new RegistryArgumentType())
                .suggests(REGISTRY_SUGGESTIONS)
                .executes(context -> runExport(context,
                        context.getArgument("registry", String.class), "en_us", "all"))
                .then(net.minecraft.command.Commands.argument("language", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .executes(context -> runExport(context,
                                context.getArgument("registry", String.class),
                                StringArgumentType.getString(context, "language"), "all"))
                        .then(net.minecraft.command.Commands.argument("modid", StringArgumentType.word())
                                .suggests(MOD_SUGGESTIONS)
                                .executes(context -> runExport(context,
                                        context.getArgument("registry", String.class),
                                        StringArgumentType.getString(context, "language"),
                                        StringArgumentType.getString(context, "modid"))))));

        event.getDispatcher().register(root);

        // Separate command for exporting rendered block and item previews.
        event.getDispatcher().register(
                net.minecraft.command.Commands.literal("render")
                        .then(net.minecraft.command.Commands.argument("modid", StringArgumentType.word())
                                .suggests(MOD_SUGGESTIONS)
                                .executes(context -> RenderImageExporter.startExport(
                                        StringArgumentType.getString(context, "modid")
                                )))
                        .executes(context -> RenderImageExporter.startExport("all"))
        );
    }

    private static int runExport(CommandContext<CommandSource> context,
                                 String registryArgument, String languageCode, String modId) {
        Minecraft minecraft = Minecraft.getInstance();
        IResourceManager resources = minecraft.getResourceManager();

        try {
            Map<ResourceLocation, WalkableRegistry> registries = collectRegistries();
            Map<String, String> translations = loadTranslations(resources, languageCode);

            String requestedRegistry = normalizeRegistryArgument(registryArgument);
            ResourceLocation requestedRegistryId = requestedRegistry.equals("all")
                    ? null
                    : toResourceLocation(requestedRegistry);

            if (!requestedRegistry.equals("all")
                    && (requestedRegistryId == null || !registries.containsKey(requestedRegistryId))) {
                tell(new StringTextComponent("Unknown registry: " + registryArgument));
                return 0;
            }

            TreeSet<String> exportedNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<ResourceLocation, WalkableRegistry> registryEntry : registries.entrySet()) {
                ResourceLocation registryId = registryEntry.getKey();

                if (!requestedRegistry.equals("all") && !registryId.toString().equals(requestedRegistry)) {
                    continue;
                }

                WalkableRegistry registry = registryEntry.getValue();
                for (Object value : registry.values()) {
                    ResourceLocation entryId = registry.keyOf(value);
                    if (entryId == null) continue;
                    if (!modId.equalsIgnoreCase("all") && !entryId.getNamespace().equals(modId)) continue;

                    String translationKey = findTranslationKey(registryId, entryId, value);
                    if (translationKey == null) continue;

                    String translatedName = translations.get(translationKey);
                    if (translatedName == null || translatedName.trim().isEmpty()) continue;

                    exportedNames.add(translatedName.trim());
                }
            }

            Path exportDirectory = minecraft.gameDir.toPath().resolve("exports");
            Files.createDirectories(exportDirectory);

            String safeRegistry = requestedRegistry.equals("all")
                    ? "all"
                    : requestedRegistry.replace(':', '_').replace('/', '_');
            String safeMod = modId.equalsIgnoreCase("all") ? "all" : modId;
            String fileName = "export_" + safeRegistry + "_" + languageCode + "_" + safeMod + ".txt";
            Path output = exportDirectory.resolve(fileName);

            Files.write(output, exportedNames, StandardCharsets.UTF_8);
            tell(new StringTextComponent("Exported " + exportedNames.size() + " names to " + output));
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            tell(new StringTextComponent("Export failed: " + exception.getMessage()));
            exception.printStackTrace();
            return 0;
        }
    }

    private static void tell(ITextComponent message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendMessage(message, false);
        }
    }

    private static String normalizeRegistryArgument(String argument) {
        return argument.equalsIgnoreCase("all") ? "all" : argument;
    }

    private static ResourceLocation toResourceLocation(String id) {
        try {
            ResourceLocation parsed = new ResourceLocation(id);
            return parsed.getNamespace().isEmpty() || parsed.getPath().isEmpty() ? null : parsed;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Map<ResourceLocation, WalkableRegistry> collectRegistries() {
        Map<ResourceLocation, WalkableRegistry> found = new TreeMap<>();

        // Forge object registries (items, blocks, entity types, ...). On
        // 1.16.5 these also mirror all vanilla content, so the old
        // BuiltInRegistries / dynamic-registry walk is unnecessary.
        for (IForgeRegistry<?> registry : ForgeRegistries.REGISTRIES.getValues()) {
            ResourceLocation registryId = registry.getRegistryName();
            if (registryId != null) {
                found.put(registryId, wrap(registry));
            }
        }

        return found;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WalkableRegistry wrap(IForgeRegistry<?> registry) {
        return new ForgeWalk((IForgeRegistry) registry);
    }

    private static List<String> getRegistryNames() {
        TreeSet<String> names = new TreeSet<>();
        names.add("all");

        Minecraft minecraft = Minecraft.getInstance();

        String language = minecraft.languageManager.getCurrentLanguage().getCode();
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

        for (Map.Entry<ResourceLocation, WalkableRegistry> entry
                : collectRegistries().entrySet()) {

            ResourceLocation registryId = entry.getKey();
            WalkableRegistry registry = entry.getValue();

            if (hasTranslatedEntries(registryId, registry, translations)) {
                names.add(registryId.toString());
            }
        }

        return new ArrayList<>(names);
    }

    private static boolean hasTranslatedEntries(
            ResourceLocation registryId,
            WalkableRegistry registry,
            Map<String, String> translations
    ) {
        for (Object value : registry.values()) {
            ResourceLocation entryId = registry.keyOf(value);
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
        for (Map.Entry<ResourceLocation, WalkableRegistry> entry : collectRegistries().entrySet()) {
            for (Object value : entry.getValue().values()) {
                ResourceLocation id = entry.getValue().keyOf(value);
                if (id != null) ids.add(id.getNamespace());
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> getLanguageCodes() {
        TreeSet<String> codes = new TreeSet<>();
        codes.add("en_us");
        Minecraft minecraft = Minecraft.getInstance();
        for (Language language : minecraft.languageManager.getLanguages()) {
            codes.add(language.getCode());
        }
        return new ArrayList<>(codes);
    }

    private static Map<String, String> loadTranslations(IResourceManager manager, String languageCode)
            throws IOException {
        Map<String, String> translations = new TreeMap<>();
        String path = "lang/" + languageCode.toLowerCase(Locale.ROOT) + ".json";

        // Merge every namespace's copy of the file, vanilla first, so that
        // mod-provided entries layer over the base language like in-game.
        List<ResourceLocation> files = new ArrayList<>(
                manager.getResourceLocations(net.minecraft.resource.ResourceType.CLIENT, path)
        );
        files.sort((a, b) -> Boolean.compare(
                "minecraft".equals(a.getNamespace()), "minecraft".equals(b.getNamespace())));

        for (ResourceLocation file : files) {
            try (InputStream input = manager.getResource(file).getStream()) {
                loadJsonTranslations(input, translations);
            } catch (IOException ignored) {
                // Namespace does not actually contain this file.
            }
        }
        return translations;
    }

    private static void loadJsonTranslations(InputStream input, Map<String, String> target)
            throws IOException {
        JsonObject json = new Gson().fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        if (json == null) return;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            target.put(entry.getKey(), entry.getValue().getAsString());
        }
    }

    /**
     * Most game content classes expose getTranslationKey(). The extra method
     * names cover a few vanilla classes that use other accessors.
     */
    private static String findTranslationKey(
            ResourceLocation registryId,
            ResourceLocation entryId,
            Object value
    ) {
        // First, try the object's own translation key.
        for (String methodName : LIST_OF_KEY_METHODS) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = method.invoke(value);

                if (result instanceof String && !((String) result).trim().isEmpty()) {
                    return (String) result;
                }
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // This object doesn't expose a translation-key method.
            }
        }

        // Fallback: type.modid.resource for any registry entry.
        String type = registryId.getPath();

        int slashIndex = type.lastIndexOf('/');
        if (slashIndex >= 0) {
            type = type.substring(slashIndex + 1);
        }

        return type + "."
                + entryId.getNamespace() + "."
                + entryId.getPath();
    }

    private static final List<String> LIST_OF_KEY_METHODS;

    static {
        List<String> methods = new ArrayList<>();
        methods.add("getTranslationKey");
        methods.add("getDescriptionId");
        LIST_OF_KEY_METHODS = methods;
    }
}
