package com.github.dmadapter.mybatis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/** Splits only ordinary DML whose separators are outside complete MyBatis XML nodes. */
final class DynamicSqlStatementSplitter {
    private static final Set<String> DYNAMIC_TAGS = Set.of(
            "if", "choose", "when", "otherwise", "where", "set", "trim", "foreach", "bind");

    private DynamicSqlStatementSplitter() {
    }

    // An empty result means the caller must retain its existing whole-body conversion path.
    static List<String> split(String body) {
        if (body.contains("${") || body.contains("<![CDATA[")) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        Deque<String> tags = new ArrayDeque<>();
        int parentheses = 0;
        int start = 0;
        int index = 0;
        boolean hasSql = false;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (body.startsWith("<!--", index) || body.startsWith("/*", index)) {
                String closing = body.startsWith("<!--", index) ? "-->" : "*/";
                int end = body.indexOf(closing, index + (closing.equals("-->") ? 4 : 2));
                if (end < 0) {
                    return List.of();
                }
                index = end + closing.length();
            } else if (body.startsWith("--", index)
                    || (current == '#' && !body.startsWith("#{", index))) {
                while (index < body.length() && body.charAt(index) != '\n' && body.charAt(index) != '\r') {
                    index++;
                }
            } else if (current == '<') {
                int end = tagEnd(body, index);
                if (end < 0) {
                    return List.of();
                }
                String tag = body.substring(index + 1, end).strip();
                boolean closing = tag.startsWith("/");
                boolean selfClosing = tag.endsWith("/");
                String name = tag.substring(closing ? 1 : 0).split("[\\s/]", 2)[0];
                if (!DYNAMIC_TAGS.contains(name)
                        || ((name.equals("foreach") || name.equals("trim"))
                        && (tag.contains(";") || tag.contains("&")))) {
                    return List.of();
                }
                if (closing) {
                    if (tags.isEmpty() || !tags.pop().equals(name)) {
                        return List.of();
                    }
                } else if (!selfClosing) {
                    tags.push(name);
                }
                index = end + 1;
            } else {
                if (!hasSql) {
                    if (!tags.isEmpty() || !startsDml(body, index)) {
                        return List.of();
                    }
                    hasSql = true;
                }
                if (body.startsWith("#{", index)) {
                    int end = body.indexOf('}', index + 2);
                    if (end < 0) {
                        return List.of();
                    }
                    index = end + 1;
                    continue;
                }
                CharacterToken token = characterAt(body, index);
                if (token == null) {
                    return List.of();
                }
                current = token.value();
                if (current == '\'' || current == '"' || current == '`') {
                    index = quotedEnd(body, token.end(), current);
                    if (index < 0) {
                        return List.of();
                    }
                } else if (token.end() > index + 1) {
                    // Entities may encode SQL syntax too. Only harmless operators are admitted
                    // outside literals; encoded delimiters or comments require the original path.
                    if (current != '<' && current != '>' && current != '&') {
                        return List.of();
                    }
                    index = token.end();
                } else if (current == '(') {
                    parentheses++;
                    index++;
                } else if (current == ')') {
                    if (--parentheses < 0) {
                        return List.of();
                    }
                    index++;
                } else if (current == ';') {
                    if (parentheses != 0 || !tags.isEmpty()) {
                        return List.of();
                    }
                    statements.add(body.substring(start, ++index));
                    start = index;
                    hasSql = false;
                } else {
                    index++;
                }
            }
        }
        if (parentheses != 0 || !tags.isEmpty()) {
            return List.of();
        }
        if (hasSql) {
            statements.add(body.substring(start));
        } else if (!statements.isEmpty()) {
            int last = statements.size() - 1;
            statements.set(last, statements.get(last) + body.substring(start));
        }
        return statements.size() > 1 ? List.copyOf(statements) : List.of();
    }

    private static boolean startsDml(String body, int index) {
        for (String keyword : List.of("INSERT", "UPDATE", "DELETE", "SELECT")) {
            int end = index + keyword.length();
            if (body.regionMatches(true, index, keyword, 0, keyword.length())
                    && (end == body.length() || !Character.isJavaIdentifierPart(body.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static int tagEnd(String body, int index) {
        char quote = '\0';
        for (int i = index + 1; i < body.length(); i++) {
            char current = body.charAt(i);
            if (quote != '\0') {
                if (current == quote) {
                    quote = '\0';
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int quotedEnd(String body, int index, char quote) {
        while (index < body.length()) {
            // A raw XML node inside a SQL literal must not be consumed as ordinary text.
            if (body.charAt(index) == '<') {
                return -1;
            }
            CharacterToken token = characterAt(body, index);
            if (token == null) {
                return -1;
            }
            index = token.end();
            if (token.value() == quote) {
                CharacterToken next = index < body.length() ? characterAt(body, index) : null;
                if (next == null || next.value() != quote) {
                    return index;
                }
                index = next.end();
            } else if (token.value() == '\\') {
                CharacterToken escaped = index < body.length() ? characterAt(body, index) : null;
                if (escaped == null) {
                    return -1;
                }
                index = escaped.end();
            }
        }
        return -1;
    }

    private static CharacterToken characterAt(String body, int index) {
        if (body.charAt(index) != '&') {
            return new CharacterToken(body.charAt(index), index + 1);
        }
        int end = body.indexOf(';', index + 1);
        if (end < 0 || end - index > 12) {
            return null;
        }
        String entity = body.substring(index + 1, end);
        int value;
        try {
            value = switch (entity) {
                case "lt" -> '<';
                case "gt" -> '>';
                case "amp" -> '&';
                case "apos" -> '\'';
                case "quot" -> '"';
                default -> entity.startsWith("#x") ? Integer.parseInt(entity.substring(2), 16)
                        : entity.startsWith("#") ? Integer.parseInt(entity.substring(1)) : -1;
            };
        } catch (NumberFormatException e) {
            return null;
        }
        return value < 0 || value > Character.MAX_VALUE ? null : new CharacterToken((char) value, end + 1);
    }

    private record CharacterToken(char value, int end) {
    }
}
