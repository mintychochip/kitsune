package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies item-path encoding round-trips and rejection of malformed payloads.
 */
class ItemPathCodecTest {

    @Test
    void roundTripsPath() {
        ItemPath path = new ItemPath(List.of(
                new ItemPathStep("Chest", 4),
                new ItemPathStep("Purple Shulker Box", 12)));

        assertEquals(path, ItemPathCodec.decode(ItemPathCodec.encode(path)));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] encoded = ItemPathCodec.encode(
                new ItemPath(List.of(new ItemPathStep("Chest", 1))));
        byte[] malformed = java.util.Arrays.copyOf(encoded, encoded.length + 1);

        assertThrows(IllegalArgumentException.class, () -> ItemPathCodec.decode(malformed));
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] malformed = {
                1,
                0, 0, 0, 1,
                0, 0, 0, 2,
                (byte) 0xFF, (byte) 0xFE
        };

        assertThrows(IllegalArgumentException.class, () -> ItemPathCodec.decode(malformed));
    }

    @Test
    void rejectsUnpairedSurrogateWhileEncoding() {
        ItemPath path = new ItemPath(List.of(new ItemPathStep("\uD800", 0)));

        assertThrows(IllegalArgumentException.class, () -> ItemPathCodec.encode(path));
    }

    @Test
    void rejectsEncodedPayloadOverLimit() {
        String maximumLabel = "a".repeat(ItemPathCodec.MAX_STEP_LABEL_BYTES);
        int stepsRequired = ItemPathCodec.MAX_PAYLOAD_BYTES
                / (ItemPathCodec.MAX_STEP_LABEL_BYTES + 2 * Integer.BYTES) + 1;
        List<ItemPathStep> steps = new ArrayList<>(stepsRequired);
        for (int slot = 0; slot < stepsRequired; slot++) {
            steps.add(new ItemPathStep(maximumLabel, slot));
        }

        ItemPath path = new ItemPath(steps);
        assertThrows(IllegalArgumentException.class, () -> ItemPathCodec.encode(path));
    }

    @Test
    void rejectsUnsupportedNullAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> ItemPathCodec.decode(new byte[] {2}));
        assertThrows(IllegalArgumentException.class,
                () -> ItemPathCodec.decode(null));
        assertThrows(IllegalArgumentException.class,
                () -> ItemPathCodec.decode(
                        new byte[ItemPathCodec.MAX_PAYLOAD_BYTES + 1]));
    }
}
