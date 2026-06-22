package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.mybatis.SqlRewriteConfig;

import java.util.List;
import java.util.Optional;

record SqlRewriteConfigUpdate(
        SqlRewriteConfig rewriteConfig,
        Optional<FileChange> fileChange,
        List<String> warnings
) {
    SqlRewriteConfigUpdate {
        rewriteConfig = rewriteConfig == null ? SqlRewriteConfig.empty() : rewriteConfig;
        fileChange = fileChange == null ? Optional.empty() : fileChange;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
