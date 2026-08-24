package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary codec for {@link ItemDescriptor} serialization.
 *
 * <p>Encoding writes a version byte followed by the material key, amount, and all descriptor
 * collections. Decoding reads the same layout, enforces the version, element-size and total
 * payload limits, and rejects trailing bytes. The encoded form is limited to
 * {@link #MAX_PAYLOAD_BYTES} bytes.
 */
final class DescriptorCodec {

    private static final byte VERSION = 1;

    /** Maximum number of characters in a single string. */
    static final int MAX_STRING_LENGTH = 65535;
    /** Maximum number of elements in a string list. */
    static final int MAX_STRING_LIST_SIZE = 65535;
    /** Maximum number of entries in a serialized map. */
    static final int MAX_MAP_ENTRIES = 65535;
    /** Maximum allowed size, in bytes, of an encoded descriptor payload. */
    static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

    private DescriptorCodec() {}

    /**
     * Encodes a descriptor for storage, preserving its configured amount.
     *
     * @param descriptor the descriptor to encode, must not be null
     * @return binary encoding of the descriptor
     * @throws IllegalArgumentException if the encoded payload exceeds the size limit
     */
    static byte[] encode(ItemDescriptor descriptor) {
        return encode(descriptor, descriptor.amount());
    }

    /**
     * Encodes a descriptor for semantic comparison, forcing the amount to 1.
     *
     * @param descriptor the descriptor to encode, must not be null
     * @return binary encoding of the descriptor with amount 1
     * @throws IllegalArgumentException if the encoded payload exceeds the size limit
     */
    static byte[] encodeSemantic(ItemDescriptor descriptor) {
        return encode(descriptor, 1);
    }

    private static byte[] encode(ItemDescriptor descriptor, int amount) {
        try {
            var bytes = new BoundedByteArrayOutputStream(
                    MAX_PAYLOAD_BYTES, "Descriptor payload too large");
            try (var output = new DataOutputStream(bytes)) {
                output.writeByte(VERSION);
                writeString(output, descriptor.materialKey());
                output.writeInt(amount);
                writeStrings(output, descriptor.displayText());
                writeStrings(output, descriptor.lore());
                writeStringIntMap(output, descriptor.enchantments());
                writeStringDoubleMap(output, descriptor.attributes());
                writeStrings(output, new ArrayList<>(descriptor.traits()));
                writeStringStringMap(output, descriptor.scalarMetadata());
                writeStrings(output, new ArrayList<>(descriptor.customTags()));
            }
            if (bytes.size() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Descriptor payload too large");
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Restores a descriptor from the payload produced by {@link #encode}.
     *
     * @param payload binary descriptor payload, must not be null and no larger than
     *        {@link #MAX_PAYLOAD_BYTES}
     * @return the decoded descriptor
     * @throws IllegalArgumentException if the payload is null, oversized, has an unsupported
     *         version, exceeds element-size limits, or contains trailing bytes
     */
    static ItemDescriptor decode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Null descriptor payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Descriptor payload too large");
        }
        try {
            try (var dis = new DataInputStream(new ByteArrayInputStream(payload))) {
                byte version = dis.readByte();
                if (version != VERSION) {
                    throw new IllegalArgumentException("Unsupported descriptor version: " + version);
                }
                String materialKey = readString(dis);
                int amount = dis.readInt();
                List<String> displayText = readStrings(dis);
                List<String> lore = readStrings(dis);
                Map<String, Integer> enchantments = readStringIntMap(dis);
                Map<String, Double> attributes = readStringDoubleMap(dis);
                List<String> traits = readStrings(dis);
                Map<String, String> scalarMetadata = readStringStringMap(dis);
                List<String> customTags = readStrings(dis);

                if (dis.available() != 0) {
                    throw new IllegalArgumentException("Trailing descriptor bytes");
                }

                ItemDescriptor.Builder builder = ItemDescriptor.builder()
                        .materialKey(materialKey)
                        .amount(amount);
                for (String text : displayText) builder.addDisplayText(text);
                for (String line : lore) builder.addLore(line);
                for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                    builder.addEnchantment(entry.getKey(), entry.getValue());
                }
                for (Map.Entry<String, Double> entry : attributes.entrySet()) {
                    builder.addAttribute(entry.getKey(), entry.getValue());
                }
                for (String trait : traits) builder.addTrait(trait);
                for (Map.Entry<String, String> entry : scalarMetadata.entrySet()) {
                    builder.addScalarMetadata(entry.getKey(), entry.getValue());
                }
                for (String tag : customTags) builder.addCustomTag(tag);
                return builder.build();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Malformed descriptor", ex);
        }
    }

    private static void writeString(DataOutput output, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Null string");
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("String too long");
        }
        byte[] encoded = encodeUtf8(value);
        if (encoded.length > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("String too long");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static byte[] encodeUtf8(String value) {
        try {
            java.nio.ByteBuffer encoded = java.nio.charset.StandardCharsets.UTF_8
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
        if (length < 0 || length > MAX_STRING_LENGTH) {
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

    private static void writeStrings(DataOutput out, List<String> values) throws IOException {
        if (values == null) throw new IllegalArgumentException("Null list");
        if (values.size() > MAX_STRING_LIST_SIZE) throw new IllegalArgumentException("Too many strings");
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_STRING_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid string list size");
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(readString(in));
        }
        return values;
    }

    private static void writeStringIntMap(DataOutput out, Map<String, Integer> values) throws IOException {
        if (values == null) throw new IllegalArgumentException("Null map");
        if (values.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException("Too many map entries");
        out.writeInt(values.size());
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            writeString(out, key);
            out.writeInt(values.get(key));
        }
    }

    private static Map<String, Integer> readStringIntMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException("Invalid map size");
        }
        Map<String, Integer> values = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(in);
            int value = in.readInt();
            Integer previous = values.put(key, value);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate map key");
            }
        }
        return values;
    }

    private static void writeStringDoubleMap(DataOutput out, Map<String, Double> values) throws IOException {
        if (values == null) throw new IllegalArgumentException("Null map");
        if (values.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException("Too many map entries");
        out.writeInt(values.size());
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            writeString(out, key);
            out.writeDouble(values.get(key));
        }
    }

    private static Map<String, Double> readStringDoubleMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException("Invalid map size");
        }
        Map<String, Double> values = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(in);
            double value = in.readDouble();
            Double previous = values.put(key, value);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate map key");
            }
        }
        return values;
    }

    private static void writeStringStringMap(DataOutput out, Map<String, String> values) throws IOException {
        if (values == null) throw new IllegalArgumentException("Null map");
        if (values.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException("Too many map entries");
        out.writeInt(values.size());
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            writeString(out, key);
            writeString(out, values.get(key));
        }
    }

    private static Map<String, String> readStringStringMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException("Invalid map size");
        }
        Map<String, String> values = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(in);
            String value = readString(in);
            String previous = values.put(key, value);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate map key");
            }
        }
        return values;
    }
}
