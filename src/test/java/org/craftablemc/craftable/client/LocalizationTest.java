package org.craftablemc.craftable.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.craftablemc.craftable.Craftable;
import org.craftablemc.craftable.config.ConfigSettings;
import org.craftablemc.craftable.config.CraftableClientConfig;
import org.craftablemc.craftable.config.CraftableServerConfig;
import org.craftablemc.craftable.config.DirectPreparationPolicy;
import org.craftablemc.craftable.planner.CraftRequest;
import org.junit.jupiter.api.Test;

/**
 * 检查随包双语资源的键、占位符、选项行与命名边界，不复制整段可编辑文案；真实字体排版另由客户端场景验证。
 * Check shipped bilingual keys, placeholders, choice lines and naming boundaries without copying editorial prose; client scenarios verify real font layout.
 */
class LocalizationTest {
    @Test
    void organizationIdentityMatchesPublicationMetadataAndDeclaredMixinClasses() throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("org.craftablemc", properties.getProperty("mod_group_id"));
        assertEquals(properties.getProperty("mod_group_id") + ".craftable", Craftable.class.getPackageName());
        assertEquals("craftable", properties.getProperty("mod_id"));
        assertEquals("Craftable", properties.getProperty("mod_name"));

        try (var resource = LocalizationTest.class.getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(resource);
            String metadata = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("authors=\"CraftableMC\""));
            assertTrue(metadata.contains("displayURL=\"https://github.com/CraftableMC\""));
            for (var entry : Map.of("craftable.mixins.json", "client.mixin",
                    "craftable-common.mixins.json", "menu.mixin").entrySet()) {
                String file = entry.getKey();
                assertTrue(metadata.contains("config=\"" + file + "\""), file);
                try (var stream = LocalizationTest.class.getResourceAsStream("/" + file)) {
                    assertNotNull(stream, file);
                    var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                    String mixinPackage = config.get("package").getAsString();
                    assertEquals(Craftable.class.getPackageName() + "." + entry.getValue(), mixinPackage, file);
                    // 检查声明指向实际编译类，但不在纯单测中加载客户端 Mixin 或启动 Minecraft。
                    // Verify declared compiled resources without loading client Mixins or bootstrapping Minecraft in a unit test.
                    for (var side : List.of("mixins", "client", "server")) {
                        if (!config.has(side)) continue;
                        for (var name : config.getAsJsonArray(side)) {
                            String path = "/" + (mixinPackage + "." + name.getAsString()).replace('.', '/') + ".class";
                            assertNotNull(LocalizationTest.class.getResource(path), file + ": " + path);
                        }
                    }
                }
            }
        }
        for (var spec : List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC))
            for (var value : ConfigSettings.values(spec))
                assertTrue(value.getSpec().getTranslationKey().startsWith("config.craftable."), value.getPath().toString());
    }

    @Test
    void localizedInGameNamesAreSeparateFromTheModIdentity() throws Exception {
        var chinese = language("zh_cn");
        var english = language("en_us");
        assertEquals("craftable", org.craftablemc.craftable.Craftable.MOD_ID);
        assertEquals("配方书", chinese.get("mod.craftable.name").getAsString());
        assertEquals("Recipe Book", english.get("mod.craftable.name").getAsString());
        assertEquals("配方书", chinese.get("key.categories.craftable").getAsString());
        assertEquals("Recipe Book", english.get("key.categories.craftable").getAsString());
        assertTrue(english.get("command.craftable.environment.summary").getAsString()
                .startsWith(english.get("mod.craftable.name").getAsString()));
        // 这是可合成性结论而非名称，必须保留原意，不能随模组显示名一起替换。
        // This is a craftability verdict, not a name; preserve its meaning instead of renaming it with the mod display name.
        assertEquals("Craftable", english.get("status.craftable.craftable").getAsString());
        try (var metadata = LocalizationTest.class.getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(metadata);
            String text = new String(metadata.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("modId=\"craftable\""));
            assertTrue(text.contains("displayName=\"Craftable\""));
        }
        for (var key : List.of("screen.craftable.settings.title", "craftable.configuration.title")) {
            assertEquals("配方书设置", chinese.get(key).getAsString(), key);
            assertEquals("Recipe Book Settings", english.get(key).getAsString(), key);
        }
        assertEquals("配方书个人设置", chinese.get("craftable.configuration.section.craftable.client.toml.title").getAsString());
        assertEquals("配方书世界规则", chinese.get("craftable.configuration.section.craftable.server.toml.title").getAsString());
        assertEquals("Recipe Book Personal Settings", english.get("craftable.configuration.section.craftable.client.toml.title").getAsString());
        assertEquals("Recipe Book World Rules", english.get("craftable.configuration.section.craftable.server.toml.title").getAsString());
    }

    @Test
    void languagesHaveMatchingKeysAndArguments() throws Exception {
        var chinese = language("zh_cn");
        var english = language("en_us");
        assertEquals(chinese.keySet(), english.keySet());
        for (var key : chinese.keySet()) {
            assertEquals(argumentIndexes(chinese.get(key).getAsString()), argumentIndexes(english.get(key).getAsString()), key);
        }
    }

    @Test
    void placeholderValidationAllowsReorderingButRejectsSharedFormatMistakes() {
        assertEquals(List.of(0), argumentIndexes("%s"));
        assertEquals(argumentIndexes("%s / %s"), argumentIndexes("%2$s / %1$s"));
        assertEquals(List.of(), argumentIndexes("100%%"));
        for (var malformed : List.of("%d", "%0$s", "%"))
            assertThrows(AssertionError.class, () -> argumentIndexes(malformed), malformed);
    }

    @Test
    void nativeConfigSectionButtonsHaveExplicitTranslations() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            for (var section : List.of("presentation", "environment", "crafting")) {
                String key = "craftable.configuration." + section + ".button";
                assertTrue(language.has(key), locale + ": " + key);
                assertFalse(language.get(key).getAsString().isBlank(), locale + ": " + key);
            }
        }
    }

    @Test
    void messagesAreNonemptyAndOnlyDeclaredTooltipsHaveLineBreaks() throws Exception {
        var multilineKeys = enumConfigValues().stream()
                .map(value -> value.getSpec().getTranslationKey() + ".tooltip").collect(Collectors.toSet());
        multilineKeys.add("screen.craftable.settings.direct.choices.tooltip");
        multilineKeys.add("screen.craftable.settings.advanced.tooltip");
        for (var locale : List.of("zh_cn", "en_us")) {
            for (var entry : language(locale).entrySet()) {
                assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(), entry.getKey());
                var value = entry.getValue().getAsString();
                assertFalse(value.isBlank(), entry.getKey());
                assertFalse(value.contains("\r"), entry.getKey());
                // 原版与NeoForge提示都经Tooltip的font.split处理并支持LF，其余消息仍用独立组件；仅声明的提示可嵌入换行。
                // Native/NeoForge tooltips both use Tooltip's LF-aware font.split; other messages use separate Components, so only declared tooltips embed LF.
                if (value.contains("\n")) assertTrue(multilineKeys.contains(entry.getKey()), entry.getKey());
            }
        }
    }

    @Test
    void advancedTooltipKeepsSeparateNonemptyGuidanceLines() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var lines = language(locale).get("screen.craftable.settings.advanced.tooltip").getAsString().lines().toList();
            assertTrue(lines.size() > 1, locale);
            assertTrue(lines.stream().noneMatch(String::isBlank), locale);
        }
    }

    @Test
    void everyAcceptedConfigEnumChoiceStartsItsOwnTooltipLine() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            for (var value : enumConfigValues()) {
                String key = value.getSpec().getTranslationKey() + ".tooltip";
                var lines = List.of(language.get(key).getAsString().split("\n", -1));
                assertTrue(lines.size() > 1, locale + ": " + key);
                assertTrue(lines.stream().noneMatch(String::isBlank), locale + ": " + key);
                var type = ConfigSettings.type(value);
                String prefix;
                if (type == CraftRequest.PartialPolicy.class) prefix = "screen.craftable.settings.policy.";
                else if (type == CraftableServerConfig.SurplusDelivery.class) prefix = "screen.craftable.settings.surplus.";
                else if (type == DirectPreparationPolicy.Mode.class) prefix = "screen.craftable.settings.direct_mode.";
                else throw new AssertionError("Add tooltip choice labels for registered enum " + type);
                var labels = new java.util.HashSet<String>();
                for (var candidate : type.getEnumConstants()) {
                    if (!value.getSpec().test(candidate)) continue;
                    String name = ((Enum<?>) candidate).name().toLowerCase(Locale.ROOT);
                    String label = language.get(prefix + name).getAsString();
                    assertTrue(labels.add(label), locale + ": " + key + ": ambiguous choice label " + label);
                    String start = label + (locale.equals("zh_cn") ? "：" : ": ");
                    assertEquals(1, lines.stream().filter(line -> line.startsWith(start)).count(),
                            locale + ": " + key + ": " + label);
                }
                if (locale.equals("zh_cn") && type == DirectPreparationPolicy.Mode.class)
                    assertTrue(lines.stream().allMatch(line -> line.endsWith("。")), key);
            }
        }
    }

    @Test
    void preparationChoicesDescribeOnlyTheTwoEffectiveOutcomes() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            var lines = language.get("screen.craftable.settings.direct.choices.tooltip").getAsString().lines().toList();
            assertEquals(2, lines.size(), locale);
            assertFalse(language.has("screen.craftable.settings.direct.inherit"), locale);
            for (var choice : List.of("allow", "confirm")) {
                String label = language.get("screen.craftable.settings.direct." + choice).getAsString();
                String start = label + (locale.equals("zh_cn") ? "：" : ": ");
                assertEquals(1, lines.stream().filter(line -> line.startsWith(start)).count(), locale + ": " + choice);
            }
            assertTrue(lines.stream().noneMatch(String::isBlank), locale);
        }
    }

    @Test
    void registeredSettingsHaveLocalizedMetadataAndNoRetiredParallelControls() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var messages = language(locale);
            for (var spec : List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC)) {
                for (var value : ConfigSettings.values(spec)) {
                    String key = value.getSpec().getTranslationKey();
                    assertNotNull(key, value.getPath().toString());
                    assertTrue(messages.has(key), locale + ": " + key);
                    assertTrue(messages.has(key + ".tooltip"), locale + ": " + key);
                }
            }
            assertEquals(messages.get("config.craftable.server.direct_preparation_mode").getAsString(),
                    messages.get("config.craftable.client.partial_policy").getAsString(),
                    locale + ": personal and world modes must share their localized setting name");
            assertFalse(messages.has("config.craftable.server.direct_preparation_confirmation_recipes"), locale);
            assertFalse(messages.has("config.craftable.server.direct_preparation_confirmation_recipes.tooltip"), locale);
            assertFalse(messages.has("screen.craftable.settings.direct.baseline"), locale);
            assertFalse(messages.has("screen.craftable.settings.direct.scope"), locale);
            assertFalse(messages.has("config.craftable.server.partial_execution"), locale);
            assertFalse(messages.has("config.craftable.server.partial_execution.tooltip"), locale);
        }
    }

    @Test
    void whitelistDirectoryCountAcceptsOnlyTheRecipeCount() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var messages = language(locale);
            String count = messages.get("screen.craftable.settings.direct.count").getAsString();
            assertEquals(List.of(0), argumentIndexes(count), locale);
        }
    }

    @Test
    void numericDescriptionsDoNotDuplicateMetadataBoundsOrDefaults() throws Exception {
        var numeric = List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC).stream()
                .flatMap(spec -> ConfigSettings.values(spec).stream())
                .filter(value -> value.getDefault() instanceof Number && value.getSpec().getRange() != null).toList();
        assertTrue(numeric.stream().anyMatch(value -> value.getPath().getLast().equals("diagnosticCacheTicks")));
        assertTrue(numeric.stream().anyMatch(value -> value.getPath().getLast().equals("doublePressMillis")));
        var bounds = java.util.regex.Pattern.compile("(?:range|范围)[\\s:：]+[0-9]|[0-9]+\\s*[–～~]\\s*[0-9]+",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            assertEquals(List.of(0, 1), argumentIndexes(language.get("screen.craftable.settings.range").getAsString()));
            assertEquals(List.of(0), argumentIndexes(language.get("screen.craftable.settings.default_value").getAsString()));
            for (var value : numeric) {
                String key = value.getSpec().getTranslationKey() + ".tooltip";
                String body = language.get(key).getAsString();
                assertFalse(body.toLowerCase(Locale.ROOT).contains("default") || body.contains("默认"), locale + ": " + key);
                assertFalse(bounds.matcher(body).find(), locale + ": " + key);
            }
        }
    }

    @Test
    void disabledModeExplanationIsSharedByPersonalAndWorldSettings() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var language = language(locale);
            String separator = locale.equals("zh_cn") ? "：" : ": ";
            String prefix = language.get("screen.craftable.settings.direct_mode.disabled").getAsString() + separator;
            String client = language.get("config.craftable.client.partial_policy.tooltip").getAsString()
                    .lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
            String server = language.get("config.craftable.server.direct_preparation_mode.tooltip").getAsString()
                    .lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
            assertEquals(client, server, locale);
        }
    }

    @Test
    void playerMessagesDoNotUseRetiredImplementationTerms() throws Exception {
        for (var locale : List.of("zh_cn", "en_us")) {
            var terms = locale.equals("zh_cn")
                    ? List.of("交付", "配方输入", "配方产出", "事务", "回滚", "非默认组件", "根配方批", "下游", "需求位置")
                    : List.of("delivery", "recipe inputs", "recipe outputs", "transaction", "non-default components", "root batches", "demand positions");
            for (var entry : language(locale).entrySet()) {
                // /craftable environment是显式诊断命令，其代次/来源术语对开发者有用，不应套用玩家文案的术语限制。
                // /craftable environment is explicit diagnostics; generation/source terms remain useful to developers and are exempt from player-prose restrictions.
                if (entry.getKey().startsWith("command.")) continue;
                var value = entry.getValue().getAsString().toLowerCase(Locale.ROOT);
                for (var term : terms) assertFalse(value.contains(term), entry.getKey() + ": " + term);
            }
        }
    }

    /**
     * Minecraft支持%s、位置参数%n$s和字面量%%；比较参数索引允许翻译重排，但两种语言犯同一种格式错误也必须失败。
     * Minecraft accepts %s, positional %n$s and literal %%; compare argument indexes to allow reordering while rejecting even shared formatting mistakes.
     */
    private static List<Integer> argumentIndexes(String text) {
        var placeholders = java.util.regex.Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)").matcher(text);
        var indexes = new java.util.TreeSet<Integer>();
        int nextImplicit = 0;
        int previousEnd = 0;
        while (placeholders.find()) {
            assertFalse(text.substring(previousEnd, placeholders.start()).contains("%"), text);
            String conversion = placeholders.group(2);
            assertTrue(conversion.equals("s") || conversion.equals("%"), text);
            if (conversion.equals("%")) assertNull(placeholders.group(1), text);
            else {
                int index = placeholders.group(1) == null ? nextImplicit++
                        : Integer.parseInt(placeholders.group(1)) - 1;
                assertTrue(index >= 0, text);
                indexes.add(index);
            }
            previousEnd = placeholders.end();
        }
        assertFalse(text.substring(previousEnd).contains("%"), text);
        return List.copyOf(indexes);
    }

    private static List<ModConfigSpec.ConfigValue<?>> enumConfigValues() {
        return List.of(CraftableClientConfig.SPEC, CraftableServerConfig.SPEC).stream()
                .flatMap(spec -> ConfigSettings.values(spec).stream())
                .filter(value -> ConfigSettings.type(value).isEnum()).toList();
    }

    private static JsonObject language(String locale) throws Exception {
        var stream = LocalizationTest.class.getResourceAsStream("/assets/craftable/lang/" + locale + ".json");
        assertNotNull(stream, locale);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
