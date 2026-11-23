package com.github.dmadapter.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class ConditionalPreparedBindings {
    static final String FILE_NAME = "conditional-prepared-bindings.properties";
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_$]*";

    private final Map<Key, ValueSource> sources;

    private ConditionalPreparedBindings(Map<Key, ValueSource> sources) {
        this.sources = Map.copyOf(sources);
    }

    static ConditionalPreparedBindings load(Path sqlRoot) throws IOException {
        Path file = sqlRoot.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new ConditionalPreparedBindings(Map.of());
        }
        Properties properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("条件动态 SQL 绑定重复。");
                }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Map<Key, ValueSource> sources = new LinkedHashMap<>();
        for (String entry : properties.stringPropertyNames()) {
            String[] key = entry.split("\\|", -1);
            String[] value = properties.getProperty(entry).strip().split("\\s*,\\s*", -1);
            if (key.length != 3 || !key[0].endsWith(".sql") || !key[1].matches(IDENTIFIER)
                    || !key[2].matches(IDENTIFIER) || value.length != 3
                    || !value[0].matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")?")
                    || !value[1].matches(IDENTIFIER) || !value[2].matches("-?\\d+")) {
                throw new IllegalArgumentException("无效的条件动态 SQL 绑定，格式应为 脚本|SQL变量|取值变量=表,列,下限。");
            }
            Path relative = Path.of(key[0]);
            if (relative.isAbsolute() || relative.startsWith("..") || !relative.equals(relative.normalize())) {
                throw new IllegalArgumentException("条件动态 SQL 绑定必须使用 SQL 根目录下的相对路径。");
            }
            ValueSource source = new ValueSource(value[0], value[1], Long.parseLong(value[2]));
            if (sources.put(key(relative, key[1], key[2]), source) != null) {
                throw new IllegalArgumentException("条件动态 SQL 绑定重复。");
            }
        }
        return new ConditionalPreparedBindings(sources);
    }

    ValueSource source(Path file, String preparedVariable, String variable) {
        return sources.get(key(file, preparedVariable, variable));
    }

    private static Key key(Path file, String preparedVariable, String variable) {
        return new Key(file.toString().replace('\\', '/'), preparedVariable.toLowerCase(Locale.ROOT),
                variable.toLowerCase(Locale.ROOT));
    }

    private record Key(String file, String preparedVariable, String variable) {
    }

    record ValueSource(String table, String column, long minimum) {
        String query() {
            return "SELECT GREATEST(COALESCE(MAX(" + column + "), " + minimum + "), "
                    + minimum + ") FROM " + table;
        }
    }
}
