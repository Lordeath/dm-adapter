package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;

import java.util.List;

public interface SqlConverter {
    SqlConversionResult convert(String sql);

    default SqlConversionResult convert(String sql, List<String> upsertKeyColumns) {
        return convert(sql);
    }
}
