package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies deterministic descriptor encoding and rejection of malformed payloads.
 */
class DescriptorCodecTest {

    @Test
    void roundTripsUnicodeAndEncodesDeterministically() {
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:diamond_pickaxe")
                .amount(1)
                .addDisplayText("鉱山道具")
                .addLore("Werkzeug")
                .addEnchantment("minecraft:mending", 1)
                .addEnchantment("minecraft:efficiency", 5)
                .addScalarMetadata("custom:id", "example:miner")
                .build();

        byte[] first = DescriptorCodec.encode(descriptor);
        byte[] second = DescriptorCodec.encode(descriptor);

        assertArrayEquals(first, second);
        assertEquals(descriptor, DescriptorCodec.decode(first));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = DescriptorCodec.encode(
                ItemDescriptor.builder().materialKey("minecraft:stone").amount(1).build());
        byte[] malformed = java.util.Arrays.copyOf(encoded, encoded.length + 1);

        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decode(malformed));
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] malformed = {1, 0, 0, 0, 1, (byte) 0x80};

        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decode(malformed));
    }

    @Test
    void rejectsUnpairedSurrogateWhileEncoding() {
        ItemDescriptor descriptor = ItemDescriptor.builder()
                .materialKey("minecraft:stone")
                .amount(1)
                .addDisplayText("\uD800")
                .build();

        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(descriptor));
    }

    @Test
    void rejectsEncodedPayloadOverLimit() {
        String maximumString = "a".repeat(DescriptorCodec.MAX_STRING_LENGTH);
        ItemDescriptor.Builder builder = ItemDescriptor.builder()
                .materialKey("minecraft:stone")
                .amount(1);
        int entries = DescriptorCodec.MAX_PAYLOAD_BYTES
                / (DescriptorCodec.MAX_STRING_LENGTH + Integer.BYTES) + 1;
        for (int index = 0; index < entries; index++) {
            builder.addDisplayText(maximumString);
        }

        ItemDescriptor descriptor = builder.build();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(descriptor));
    }

    @Test
    void rejectsDuplicateDecodedMapKeys() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            writeString(output, "minecraft:stone");
            output.writeInt(1);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(2);
            writeString(output, "minecraft:mending");
            output.writeInt(1);
            writeString(output, "minecraft:mending");
            output.writeInt(2);
        }

        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decode(bytes.toByteArray()));
    }

    @Test
    void rejectsUnsupportedNullAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decode(new byte[] {2}));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decode(null));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decode(
                        new byte[DescriptorCodec.MAX_PAYLOAD_BYTES + 1]));
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
