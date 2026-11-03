package dev.celestialresonance94.minecraftexporter.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandSource;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.resources.IResourceManager;
import net.minecraft.resources.IResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Mod.EventBusSubscriber(modid = "minecraftexporter", value = Dist.CLIENT)
public final class ExportCommands {
    private ExportCommands() {}

    private static final SuggestionProvider<CommandSource> REGISTRY_SUGGESTIONS =
            (context, builder) -> ISuggestionProvider.suggest(getRegistryNames(), builder);
    private static final SuggestionProvider<CommandSource> LANGUAGE_SUGGESTIONS =
            (context, builder) -> ISuggestionProvider.suggest(getLanguageCodes(), builder);
    private static final SuggestionProvider<CommandSource> MOD_SUGGESTIONS =
            (context, builder) -> ISuggestionProvider.suggest(getModIds(), builder);

    private static class RegistryArgumentType implements ArgumentType<String> {
        @Override
        public String parse(StringReader reader) {
            int start = reader.getCursor();
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
            if (reader.getCursor() == start) throw new IllegalArgumentException("Expected registry ID");
            return reader.getString().substring(start, reader.getCursor());
        }
        @Override public Collection<String> getExamples() { return java.util.Collections.singletonList("all"); }
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSource> root = net.minecraft.command.Commands.literal("export")
                .executes(context -> runExport("all", "en_us", "all"));

        root.then(net.minecraft.command.Commands.argument("registry", new RegistryArgumentType())
                .suggests(REGISTRY_SUGGESTIONS)
                .executes(context -> runExport(context.getArgument("registry", String.class), "en_us", "all"))
                .then(net.minecraft.command.Commands.argument("language", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .executes(context -> runExport(context.getArgument("registry", String.class),
                                StringArgumentType.getString(context, "language"), "all"))
                        .then(net.minecraft.command.Commands.argument("modid", StringArgumentType.word())
                                .suggests(MOD_SUGGESTIONS)
                                .executes(context -> runExport(context.getArgument("registry", String.class),
                                        StringArgumentType.getString(context, "language"),
                                        StringArgumentType.getString(context, "modid"))))));
        event.getDispatcher().register(root);

        event.getDispatcher().register(net.minecraft.command.Commands.literal("render")
                .then(net.minecraft.command.Commands.argument("modid", StringArgumentType.word())
                        .suggests(MOD_SUGGESTIONS)
                        .executes(context -> RenderImageExporter.startExport(
                                StringArgumentType.getString(context, "modid"))))
                .executes(context -> RenderImageExporter.startExport("all")));
    }

    private static int runExport(String registryArgument, String languageCode, String modId) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            Map<ResourceLocation, Registry<?>> registries = collectRegistries();
            Map<String, String> translations = loadTranslations(minecraft.getResourceManager(), languageCode);
            ResourceLocation requestedId = "all".equalsIgnoreCase(registryArgument)
                    ? null : new ResourceLocation(registryArgument);
            if (requestedId != null && !registries.containsKey(requestedId)) {
                tell("Unknown registry: " + registryArgument);
                return 0;
            }

            TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (Map.Entry<ResourceLocation, Registry<?>> entry : registries.entrySet()) {
                if (requestedId != null && !requestedId.equals(entry.getKey())) continue;
                Registry<?> registry = entry.getValue();
                for (Object value : registry) {
                    ResourceLocation id = getEntryId(registry, value);
                    if (id == null || (!"all".equalsIgnoreCase(modId) && !id.getNamespace().equals(modId))) continue;
                    String key = findTranslationKey(entry.getKey(), id, value);
                    String translated = translations.get(key);
                    if (translated != null && !translated.trim().isEmpty()) names.add(translated.trim());
                }
            }

            Path dir = minecraft.gameDir.toPath().resolve("exports");
            Files.createDirectories(dir);
            String safeRegistry = requestedId == null ? "all" :
                    registryArgument.replace(':', '_').replace('/', '_');
            String safeMod = "all".equalsIgnoreCase(modId) ? "all" : modId;
            Path output = dir.resolve("export_" + safeRegistry + "_" + languageCode + "_" + safeMod + ".txt");
            Files.write(output, names, StandardCharsets.UTF_8);
            tell("Exported " + names.size() + " names to " + output);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            tell("Export failed: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendMessage(new StringTextComponent(message), mc.player.getUUID());
    }

    private static Map<ResourceLocation, Registry<?>> collectRegistries() {
        Map<ResourceLocation, Registry<?>> found = new TreeMap<ResourceLocation, Registry<?>>();
        for (Map.Entry<ResourceLocation, Registry<?>> entry : Registry.REGISTRY.entrySet()) {
            found.put(entry.getKey(), entry.getValue());
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static ResourceLocation getEntryId(Registry<?> registry, Object value) {
        return ((Registry<Object>) registry).getKey(value);
    }

    private static List<String> getRegistryNames() {
        TreeSet<String> names = new TreeSet<String>();
        names.add("all");
        Minecraft mc = Minecraft.getInstance();
        try {
            Map<String, String> translations = loadTranslations(mc.getResourceManager(),
                    mc.getLanguageManager().getSelected());
            for (Map.Entry<ResourceLocation, Registry<?>> entry : collectRegistries().entrySet()) {
                for (Object value : entry.getValue()) {
                    ResourceLocation id = getEntryId(entry.getValue(), value);
                    if (id != null && translations.containsKey(findTranslationKey(entry.getKey(), id, value))) {
                        names.add(entry.getKey().toString());
                        break;
                    }
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
        return new ArrayList<String>(names);
    }

    private static List<String> getModIds() {
        TreeSet<String> ids = new TreeSet<String>();
        ids.add("all");
        for (Registry<?> registry : collectRegistries().values()) {
            for (Object value : registry) {
                ResourceLocation id = getEntryId(registry, value);
                if (id != null) ids.add(id.getNamespace());
            }
        }
        return new ArrayList<String>(ids);
    }

    private static List<String> getLanguageCodes() {
        TreeSet<String> codes = new TreeSet<String>();
        codes.add("en_us");
        Collection<ResourceLocation> files = Minecraft.getInstance().getResourceManager()
                .getAllResourceLocations("lang", path -> path.endsWith(".json"));
        for (ResourceLocation file : files) {
            String path = file.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            codes.add(name.substring(0, name.length() - 5));
        }
        return new ArrayList<String>(codes);
    }

    private static Map<String, String> loadTranslations(IResourceManager manager, String language) throws IOException {
        Map<String, String> result = new TreeMap<String, String>();
        String path = "lang/" + language.toLowerCase() + ".json";
        for (String namespace : manager.getNamespaces()) {
            ResourceLocation location = new ResourceLocation(namespace, path);
            if (!manager.hasResource(location)) continue;
            IResource resource = manager.getResource(location);
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } finally { resource.close(); }
        }
        return result;
    }

    private static String findTranslationKey(ResourceLocation registryId, ResourceLocation entryId, Object value) {
        String[] methods = {"getDescriptionId", "getTranslationKey"};
        for (String methodName : methods) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = method.invoke(value);
                if (result instanceof String && !((String) result).isEmpty()) return (String) result;
            } catch (ReflectiveOperationException ignored) {}
        }
        String type = registryId.getPath();
        int slash = type.lastIndexOf('/');
        if (slash >= 0) type = type.substring(slash + 1);
        return type + "." + entryId.getNamespace() + "." + entryId.getPath();
    }
}
