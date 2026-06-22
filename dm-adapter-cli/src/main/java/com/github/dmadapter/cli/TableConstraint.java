package com.github.dmadapter.cli;

import java.util.List;

record TableConstraint(
        String name,
        ConstraintType type,
        List<String> columns
) {
    TableConstraint {
        name = name == null ? "" : name.trim();
        columns = List.copyOf(columns == null ? List.of() : columns);
    }

    enum ConstraintType {
        PRIMARY_KEY,
        UNIQUE_KEY
    }
}
