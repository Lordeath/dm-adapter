package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;

public interface SqlConverter {
    SqlConversionResult convert(String sql);
}
