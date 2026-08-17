package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.model.ItemPathStep;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes nested-item paths for compact persistence.
 */
final class ItemPathCodec {

    /** Maximum number of steps accepted in one path. */
    static final int MAX_STEP_COUNT = 65535;
    /** Maximum UTF-8 byte length of one step label. */
    static final int MAX_STEP_LABEL_BYTES = 65535;
    /** Maximum encoded payload size for one path. */
    static final int MAX_PAYLOAD_BYTES = 1 * 1024 * 1024;

    private ItemPathCodec() {}

    /**
     * Encodes a path using the versioned binary representation.
     *
     * @param path path to encode
     * @return encoded path payload
     */
    static byte[] encode(ItemPath path) {
        try {
            var bytes = new BoundedByteArrayOutputStream(
                    MAX_PAYLOAD_BYTES, "Path payload too large");
            try (var output = new DataOutputStream(bytes)) {
                output.writeByte(1);
                List<ItemPathStep> steps = path.steps();
                if (steps.size() > MAX_STEP_COUNT) {
                    throw new IllegalArgumentException("Too many path steps");
                }
                output.writeInt(steps.size());
                for (ItemPathStep step : steps) {
                    writeString(output, step.label());
                    output.writeInt(step.slot());
                }
            }
            if (bytes.size() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Path payload too large");
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Decodes a versioned payload back into a path.
     *
     * @param payload encoded path payload
     * @return the decoded path
     */
    static ItemPath decode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Null path payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Path payload too large");
        }
        try {
            try (var dis = new DataInputStream(new ByteArrayInputStream(payload))) {
                byte version = dis.readByte();
                if (version != 1) {
                    throw new IllegalArgumentException("Unsupported path version: " + version);
                }
                int size = dis.readInt();
                if (size < 0 || size > MAX_STEP_COUNT) {
                    throw new IllegalArgumentException("Invalid step count");
                }
                List<ItemPathStep> steps = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String label = readString(dis);
                    int slot = dis.readInt();
                    steps.add(new ItemPathStep(label, slot));
                }
                if (dis.available() != 0) {
                    throw new IllegalArgumentException("Trailing path bytes");
                }
                return new ItemPath(steps);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Malformed path", ex);
        }
    }

    private static void writeString(DataOutput output, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Null string");
        }
        if (value.length() > MAX_STEP_LABEL_BYTES) {
            throw new IllegalArgumentException("String too long");
        }
        byte[] encoded = encodeUtf8(value);
        if (encoded.length > MAX_STEP_LABEL_BYTES) {
            throw new IllegalArgumentException("String too long");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static byte[] encodeUtf8(String value) {
        try {
            java.nio.ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("Malformed UTF-16 string", exception);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STEP_LABEL_BYTES) {
            throw new IllegalArgumentException("Invalid string length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Malformed string payload");
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new IllegalArgumentException("Malformed UTF-8 string", ex);
        }
    }
}
