package org.craftablemc.craftable.recipe;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 用临时目录检查静态配方关系缓存的保真、损坏回退、世代失效与写入隔离，防止磁盘缓存变成旧知识授权或破坏已有目标。
 * Use temporary directories to catch stale-knowledge authorization, corruption handling, generation/write isolation and destination damage in static recipe caches.
 * 缓存命中不证明世界资源可用；每例独立目录、关闭写入器并等待任务结束，不触碰玩家实际缓存。
 * Cache hits do not establish world-resource availability; per-case directories, writer closure and idle waits isolate tests from real player caches.
 */
class RecipeKnowledgeCacheTest {
    @TempDir Path directory;
    private static final String FP = "a".repeat(64);
    private Path file() { return directory.resolve("craftable/cache/recipe-knowledge-v1.json"); }
    private RecipeKnowledgeCache cache() { return new RecipeKnowledgeCache(file(), ignored -> {}); }
    private RecipeKnowledgeCache.Snapshot snapshot() {
        return new RecipeKnowledgeCache.Snapshot(FP, Map.of(
                List.of("minecraft:stick"), List.of("minecraft:stick", "minecraft:stick_from_bamboo_item"),
                List.of("minecraft:bedrock"), List.of()));
    }

    @Test void roundTripRebindsOnlyStringsAndDistinguishesEmptyFromMissing() {
        try (var cache = cache()) {
            long epoch = cache.advance();
            cache.submit(epoch, snapshot());
            assertTrue(cache.awaitIdle());
        }
        try (var cache = cache()) {
            var restored = cache.load(FP);
            assertEquals(snapshot(), restored);
            assertEquals(List.of(), restored.relations().get(List.of("minecraft:bedrock")));
            assertNull(restored.relations().get(List.of("minecraft:diamond")));
            assertThrows(UnsupportedOperationException.class, () -> restored.relations().clear());
        }
    }

    @Test void changedFingerprintUnknownVersionAndChecksumCannotAuthorizeOldData() throws Exception {
        try (var cache = cache()) {
            cache.submit(cache.advance(), snapshot());
            assertTrue(cache.awaitIdle());
            assertNull(cache.load("b".repeat(64)));
            String original = Files.readString(file());
            Files.writeString(file(), original.replace("\"version\":1", "\"version\":2"));
            assertNull(cache.load(FP));
            Files.writeString(file(), original.replace("minecraft:bedrock", "minecraft:diamond"));
            assertNull(cache.load(FP));
        }
    }

    @Test void malformedNestedDuplicateAndTrailingDataFailClosedToCacheMiss() throws Exception {
        try (var cache = cache()) {
            cache.submit(cache.advance(), snapshot());
            assertTrue(cache.awaitIdle());
            String original = Files.readString(file());
            for (String corrupted : List.of(original.substring(0, original.length() / 2),
                    original + "{}", original.replace("\"items\":[", "\"items\":[["),
                    original.replace("\"minecraft:stick\",\"minecraft:stick_from_bamboo_item\"",
                            "\"minecraft:stick\",\"minecraft:stick\""))) {
                Files.writeString(file(), corrupted);
                assertNull(cache.load(FP));
            }
        }
    }

    @Test void oversizedFileIsRejectedBeforeParsing() throws Exception {
        Files.createDirectories(file().getParent());
        try (var oversized = new java.io.RandomAccessFile(file().toFile(), "rw")) {
            oversized.setLength(RecipeKnowledgeCache.MAX_BYTES + 1L);
        }
        try (var cache = cache()) { assertNull(cache.load(FP)); }
    }

    @Test void invalidAndDuplicateIdsAreRejectedAtSnapshotBoundary() {
        for (var ids : List.of(List.of("../secret"), List.of("minecraft:stick", "minecraft:stick"),
                List.of("minecraft:z", "minecraft:a"), List.of("minecraft:" + "a".repeat(513)))) {
            assertThrows(IllegalArgumentException.class, () -> new RecipeKnowledgeCache.Snapshot(FP, Map.of(ids, List.of())));
        }
    }

    @Test void referenceAndKeyLimitsCannotCreateUnboundedSnapshots() {
        var tooMany = java.util.stream.IntStream.range(0, RecipeKnowledgeCache.MAX_REFERENCES + 1)
                .mapToObj(i -> "minecraft:item_" + String.format("%07d", i)).toList();
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeKnowledgeCache.Snapshot(FP, Map.of(tooMany, List.of())));
        var keys = new java.util.HashMap<List<String>, List<String>>();
        for (int i = 0; i <= RecipeKnowledgeCache.MAX_KEYS; i++) keys.put(List.of("minecraft:a" + i), List.of());
        assertThrows(IllegalArgumentException.class, () -> new RecipeKnowledgeCache.Snapshot(FP, keys));
    }

    @Test void atomicReplaceFailureDoesNotDeleteExistingDestination() throws Exception {
        // 故意把目标占为含哨兵的目录，可靠触发替换失败，并确保失败清理不删除已有用户数据。
        // Occupy the destination with a sentinel directory to trigger replacement failure reliably and prove failure cleanup preserves existing user data.
        Files.createDirectories(file());
        var sentinel = file().resolve("keep.txt");
        Files.writeString(sentinel, "user data");
        try (var cache = cache()) {
            cache.submit(cache.advance(), snapshot());
            assertTrue(cache.awaitIdle());
            assertEquals("user data", Files.readString(sentinel));
            assertNull(cache.load(FP));
        }
    }

    @Test void ioFailureAndMissingFileAreCacheMissesNotGameplayErrors() throws Exception {
        try (var cache = cache()) { assertNull(cache.load(FP)); }
        Files.createDirectories(directory.resolve("craftable"));
        Files.writeString(directory.resolve("craftable/cache"), "not a directory");
        try (var cache = cache()) {
            cache.submit(cache.advance(), snapshot());
            assertTrue(cache.awaitIdle());
            assertNull(cache.load(FP));
        }
    }

    @Test void oldGenerationAndCoalescedWritesCannotOverwriteNewGeneration() {
        // 交错提交旧/新世代而非只等一次写入，捕获异步合并队列把过期任务放回最终文件的回归。
        // Interleave old/new generations rather than awaiting one write to catch coalesced asynchronous work restoring an obsolete final file.
        try (var cache = cache()) {
            long old = cache.advance();
            cache.submit(old, snapshot());
            long current = cache.advance();
            var newer = new RecipeKnowledgeCache.Snapshot("b".repeat(64), Map.of(List.of("minecraft:diamond"), List.of()));
            for (int i = 0; i < 100; i++) {
                cache.submit(current, newer);
                cache.submit(old, snapshot());
            }
            assertTrue(cache.awaitIdle());
            assertEquals(newer, cache.load(newer.fingerprint()));
        }
    }

    @Test void canonicalHashIgnoresObjectOrderButNotArrayOrderOrComponents() {
        assertEquals(hash("{\"b\":2,\"a\":[1,2]}"), hash("{\"a\":[1,2],\"b\":2}"));
        assertNotEquals(hash("[1,2]"), hash("[2,1]"));
        assertNotEquals(hash("{\"count\":1}"), hash("{\"count\":2}"));
        assertNotEquals(hash("{\"components\":{}}"), hash("{\"components\":{\"minecraft:damage\":1}}"));
    }

    private String hash(String value) {
        var digest = RecipeKnowledgeCache.digest();
        RecipeKnowledgeCache.json(digest, JsonParser.parseString(value));
        return RecipeKnowledgeCache.hex(digest);
    }
}
