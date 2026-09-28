package io.github.pharaphara.lbc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One mapper for the whole tool, and the two shapes we ever read. */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> ARRAY = new TypeReference<>() {
    };

    private Json() {
    }

    public static Map<String, Object> object(String json) {
        return MAPPER.readValue(json, OBJECT);
    }

    public static List<Map<String, Object>> array(String json) {
        return MAPPER.readValue(json, ARRAY);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static String pretty(Object value) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public static Map<String, Object> read(Path path) {
        return object(readString(path));
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new LbcException(LbcException.Kind.DISK, "cannot read " + path + ": " + e);
        }
    }

    /** A nested map, or an empty one. Reading the site's JSON means a lot of this. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
