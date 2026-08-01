package dev.jlo.kitsune.embedding;

import dev.jlo.kitsune.model.ItemDescriptor;
import dev.jlo.kitsune.api.embedding.Embedding;
import dev.jlo.kitsune.embedding.SparseEmbedding;
import dev.jlo.kitsune.embedding.SparseTagEmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SparseTagEmbeddingProviderTest {

    private final SparseTagEmbeddingProvider provider = new SparseTagEmbeddingProvider();

    private ItemDescriptor descriptor(String material, Set<String> traits, Set<String> enchantments) {
        ItemDescriptor.Builder builder = ItemDescriptor.builder().materialKey(material).amount(1);
        traits.forEach(builder::addTrait);
        enchantments.forEach(key -> builder.addEnchantment("minecraft:" + key, 1));
        return builder.build();
    }

    private ItemDescriptor realisticMendingPickaxe() {
        return ItemDescriptor.builder()
            .materialKey("minecraft:diamond_pickaxe")
            .amount(1)
            .addTrait("item")
            .addTrait("tool:pickaxe")
            .addEnchantment("minecraft:mending", 1)
            .addScalarMetadata("material_max_stack_size", "1")
            .addScalarMetadata("material_max_durability", "1561")
            .addScalarMetadata("unbreakable", "false")
            .build();
    }

    @Test
    void semanticMiningQueryRanksPickaxeAboveFood() {
        var pickaxe = descriptor("minecraft:diamond_pickaxe", Set.of("tool", "mining"), Set.of());
        var beef = descriptor("minecraft:cooked_beef", Set.of("food"), Set.of());
        var query = provider.embedQuery("mining tool");
        assertTrue(query.cosine(provider.embed(pickaxe)) > query.cosine(provider.embed(beef)));
    }

    @Test
    void metadataQueryMatchesEnchantment() {
        var enchanted = descriptor("minecraft:diamond_pickaxe", Set.of("tool"), Set.of("mending"));
        var plain = descriptor("minecraft:diamond_pickaxe", Set.of("tool"), Set.of());
        var query = provider.embedQuery("mending");
        assertTrue(query.cosine(provider.embed(enchanted)) > query.cosine(provider.embed(plain)));
    }

    @Test
    void defaultThresholdMatchesMaterialTokenInRealisticDescriptor() {
        double score = provider.embedQuery("diamond").cosine(
            provider.embed(realisticMendingPickaxe())
        );

        assertTrue(score >= 0.30, () -> "diamond score was " + score);
    }

    @Test
    void defaultThresholdMatchesEnchantmentTokenInRealisticDescriptor() {
        double score = provider.embedQuery("mending").cosine(
            provider.embed(realisticMendingPickaxe())
        );

        assertTrue(score >= 0.30, () -> "mending score was " + score);
    }

    @Test
    void semanticVocabularyUsesSecondProviderVersion() {
        assertEquals(2, provider.version());
    }

    @Test
    void blankOrPunctuationOnlyQueryHasZeroNorm() {
        assertEquals(0.0, provider.embedQuery("---").norm());
    }

    @Test
    void aliasesAndWeightsAreDeterministic() {
        var a1 = provider.embedQuery("pickaxe");
        var a2 = provider.embedQuery("pickaxe");
        assertArrayEquals(a1.encode(), a2.encode());
        assertEquals(a1.norm(), a2.norm(), 1e-12);
    }

    @Test
    void sameLogicalVectorEncodesByteIdentically() {
        var d1 = descriptor("minecraft:stone_pickaxe", Set.of("tool", "mining"), Set.of());
        var d2 = descriptor("minecraft:stone_pickaxe", Set.of("tool", "mining"), Set.of());
        assertArrayEquals(provider.embed(d1).encode(), provider.embed(d2).encode());
    }

    @Test
    void codecRoundTrips() {
        var emb = provider.embed(descriptor("minecraft:iron_axe", Set.of("tool", "weapon", "chopping"), Set.of("sharpness")));
        var decoded = provider.decode(emb.encode(), emb.norm());
        assertEquals(emb.norm(), decoded.norm(), 1e-12);
        assertArrayEquals(emb.encode(), decoded.encode());
    }

    @Test
    void decodeRejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> provider.decode(new byte[]{-1}, 1.0));
        assertThrows(IllegalArgumentException.class, () -> provider.decode(new byte[]{}, 1.0));
        assertThrows(IllegalArgumentException.class, () -> provider.decode(new byte[]{1}, 1.0));
    }

    @Test
    void decodeRejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> provider.decode(payloadFromIntAndStrings(List.of("token:same", "token:same"), List.of(1.0, 2.0)), 1.0));
    }

    @Test
    void decodeRejectsNonFiniteAndNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> provider.decode(payloadFromIntAndStrings(List.of("token:a"), List.of(Double.NaN)), 1.0));
        assertThrows(IllegalArgumentException.class, () -> provider.decode(payloadFromIntAndStrings(List.of("token:a"), List.of(0.0)), 1.0));
        assertThrows(IllegalArgumentException.class, () -> provider.decode(payloadFromIntAndStrings(List.of("token:a"), List.of(-1.0)), 1.0));
    }

    @Test
    void decodeRejectsTrailingBytes() {
        byte[] payload = payloadFromIntAndStrings(List.of("token:a"), List.of(1.0));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var dos = new java.io.DataOutputStream(bos)) {
            dos.write(payload);
            dos.writeByte(0);
        } catch (Exception ex) {
            fail(ex);
        }
        assertThrows(IllegalArgumentException.class, () -> provider.decode(bos.toByteArray(), 1.0));
    }

    @Test
    void decodeRejectsNormMismatch() {
        var emb = provider.embed(descriptor("minecraft:stone", Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> provider.decode(emb.encode(), emb.norm() + 1.0));
    }

    @Test
    void cosineRejectsProviderMismatch() {
        var sparse = SparseEmbedding.of("other", 1, Map.of("token:a", 1.0));
        assertThrows(IllegalArgumentException.class, () -> provider.embedQuery("test").cosine(sparse));
    }

    @Test
    void cosineRejectsVersionMismatch() {
        var sparse = SparseEmbedding.of(
            SparseTagEmbeddingProvider.ID,
            SparseTagEmbeddingProvider.VERSION + 1,
            Map.of("token:a", 1.0)
        );
        assertThrows(IllegalArgumentException.class, () -> provider.embedQuery("test").cosine(sparse));
    }

    @Test
    void cosineReturnsZeroForZeroNorm() {
        var zero = SparseEmbedding.of(
            SparseTagEmbeddingProvider.ID,
            SparseTagEmbeddingProvider.VERSION,
            Map.of()
        );
        assertEquals(0.0, provider.embedQuery("test").cosine(zero));
        assertEquals(0.0, zero.cosine(provider.embedQuery("test")));
    }

    @Test
    void cosineClampsBetweenZeroAndOne() {
        var a = SparseEmbedding.of(SparseTagEmbeddingProvider.ID, 1, Map.of("token:a", 1.0));
        assertEquals(1.0, a.cosine(a));
    }

    @Test
    void punctuationOnlyQueryIsZeroNorm() {
        var q = provider.embedQuery("!!!");
        assertEquals(0.0, q.norm());
    }

    @Test
    void pickaxeAliasMatchesWithoutExplicitTraits() {
        var pickaxe = descriptor("minecraft:diamond_pickaxe", Set.of(), Set.of());
        var query = provider.embedQuery("pickaxe");
        assertTrue(query.cosine(provider.embed(pickaxe)) > 0.0);
    }

    @Test
    void exactCategoryWeights() {
        var descriptor = ItemDescriptor.builder().materialKey("materialword").amount(1)
            .addDisplayText("displayword")
            .addCustomTag("customword")
            .addEnchantment("enchantword", 1)
            .addAttribute("attributeword", 1.0)
            .addTrait("traitword")
            .addScalarMetadata("metakey", "metaval")
            .addLore("loreword")
            .build();
        var emb = provider.embed(descriptor);
        SparseEmbedding sparse = (SparseEmbedding) emb;
        var values = sparse.values();
        assertEquals(8.0, values.get("token:materialword"));
        assertEquals(8.0, values.get("token:displayword"));
        assertEquals(6.0, values.get("token:customword"));
        assertEquals(5.0, values.get("token:enchantword"));
        assertEquals(5.0, values.get("token:attributeword"));
        assertEquals(4.0, values.get("token:traitword"));
        assertEquals(3.0, values.get("token:metakey"));
        assertEquals(3.0, values.get("token:metaval"));
        assertEquals(2.0, values.get("token:loreword"));
    }

    @Test
    void duplicateTokensWithinCategoryAddWeights() {
        var descriptor = ItemDescriptor.builder().materialKey("materialword").amount(1)
            .addDisplayText("materialword")
            .build();
        var emb = provider.embed(descriptor);
        var values = ((SparseEmbedding) emb).values();
        assertEquals(16.0, values.get("token:materialword"));
    }

    @Test
    void cosineCustomSameIdVersionIsOne() {
        var a = SparseEmbedding.of("custom:x", 7, Map.of("token:a", 2.0));
        var b = SparseEmbedding.of("custom:x", 7, Map.of("token:a", 2.0));
        assertEquals(1.0, a.cosine(b));
    }

    @Test
    void cosineCustomMismatchedIdOrVersionThrows() {
        var a = SparseEmbedding.of("custom:x", 7, Map.of("token:a", 1.0));
        var b = SparseEmbedding.of("custom:y", 7, Map.of("token:a", 1.0));
        assertThrows(IllegalArgumentException.class, () -> a.cosine(b));
        var c = SparseEmbedding.of("custom:x", 8, Map.of("token:a", 1.0));
        assertThrows(IllegalArgumentException.class, () -> a.cosine(c));
    }

    @Test
    void decodeRejectsOversizedCount() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var dos = new java.io.DataOutputStream(bos)) {
            dos.writeInt(16385);
        } catch (Exception ex) {
            fail(ex);
        }
        assertThrows(IllegalArgumentException.class, () -> provider.decode(bos.toByteArray(), 1.0));
    }

    @Test
    void decodeRejectsOversizedKey() {
        String longKey = "token:" + "a".repeat(256);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var dos = new java.io.DataOutputStream(bos)) {
            dos.writeInt(1);
            dos.writeUTF(longKey);
            dos.writeDouble(1.0);
        } catch (Exception ex) {
            fail(ex);
        }
        assertThrows(IllegalArgumentException.class, () -> provider.decode(bos.toByteArray(), 1.0));
    }

    @Test
    void decodeRejectsOversizedPayload() {
        byte[] payload = new byte[17 * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> provider.decode(payload, 1.0));
    }

    @Test
    void cosineRejectsForeignEmbeddingImplementation() {
        var foreign = new Embedding() {
            @Override public String providerId() { return SparseTagEmbeddingProvider.ID; }
            @Override public int providerVersion() { return SparseTagEmbeddingProvider.VERSION; }
            @Override public double norm() { return 1.0; }
            @Override public byte[] encode() { return new byte[0]; }
            @Override public double cosine(Embedding other) { return 0.0; }
        };
        assertThrows(IllegalArgumentException.class, () -> provider.embedQuery("test").cosine(foreign));
    }

    @Test
    void constructorRejectsNormUnderflow() {
        var bad = Map.of("token:a", Double.MIN_VALUE);
        assertThrows(IllegalArgumentException.class, () -> SparseEmbedding.of(SparseTagEmbeddingProvider.ID, 1, bad));
    }

    @Test
    void decodeRejectsNormUnderflow() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var dos = new java.io.DataOutputStream(bos)) {
            dos.writeInt(1);
            dos.writeUTF("token:a");
            dos.writeDouble(Double.MIN_VALUE);
        } catch (Exception ex) {
            fail(ex);
        }
        assertThrows(IllegalArgumentException.class, () -> provider.decode(bos.toByteArray(), 0.0));
    }

    @Test
    void constructorRejectsNullKey() {
        var bad = new HashMap<String, Double>();
        bad.put(null, 1.0);
        assertThrows(IllegalArgumentException.class, () -> SparseEmbedding.of(SparseTagEmbeddingProvider.ID, 1, bad));
    }

    private static byte[] payloadFromIntAndStrings(List<String> strings, List<Double> values) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var dos = new java.io.DataOutputStream(bos)) {
            dos.writeInt(strings.size());
            for (int i = 0; i < strings.size(); i++) {
                dos.writeUTF(strings.get(i));
                dos.writeDouble(values.get(i));
            }
        } catch (Exception ex) {
            fail(ex);
        }
        return bos.toByteArray();
    }
}
