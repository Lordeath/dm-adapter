package com.github.dmadapter.mybatis;

import java.util.regex.Pattern;

final class MapperLocationPattern {
    private final Pattern pattern;

    private MapperLocationPattern(Pattern pattern) {
        this.pattern = pattern;
    }

    static MapperLocationPattern from(String location) {
        return new MapperLocationPattern(Pattern.compile(toRegex(toResourcePattern(location))));
    }

    boolean matches(String resourcesRelativePath) {
        return pattern.matcher(resourcesRelativePath).matches();
    }

    private static String toResourcePattern(String location) {
        String pattern = location.trim()
                .replace('\\', '/')
                .replaceFirst("^classpath\\*?:", "")
                .replaceFirst("^file:", "");
        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        if (pattern.startsWith("src/main/resources/")) {
            pattern = pattern.substring("src/main/resources/".length());
        }
        return pattern;
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char current = glob.charAt(i);
            if (current == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    if (slashAfter) {
                        regex.append("(?:.*/)?");
                        i += 2;
                    } else {
                        regex.append(".*");
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        regex.append("$");
        return regex.toString();
    }
}
