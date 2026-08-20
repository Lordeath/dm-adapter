package com.github.dmadapter.core;

public record DamengTargetCapabilities(
        String compatibleMode,
        String caseSensitive,
        String blankPadMode,
        String plSqlStrip,
        String source
) {
    public DamengTargetCapabilities {
        compatibleMode = value(compatibleMode);
        caseSensitive = value(caseSensitive);
        blankPadMode = value(blankPadMode);
        plSqlStrip = value(plSqlStrip);
        source = value(source);
    }

    public static DamengTargetCapabilities unknown() {
        return new DamengTargetCapabilities("", "", "", "", "UNKNOWN");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
