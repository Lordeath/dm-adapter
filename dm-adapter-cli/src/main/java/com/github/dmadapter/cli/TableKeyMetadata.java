package com.github.dmadapter.cli;

import java.util.List;

record TableKeyMetadata(
        String tableName,
        List<TableConstraint> constraints
) {
    TableKeyMetadata {
        tableName = tableName == null ? "" : tableName.trim();
        constraints = List.copyOf(constraints == null ? List.of() : constraints);
    }

    List<TableConstraint> primaryKeys() {
        return constraints.stream()
                .filter(constraint -> constraint.type() == TableConstraint.ConstraintType.PRIMARY_KEY)
                .toList();
    }

    List<TableConstraint> uniqueKeys() {
        return constraints.stream()
                .filter(constraint -> constraint.type() == TableConstraint.ConstraintType.UNIQUE_KEY)
                .toList();
    }
}
