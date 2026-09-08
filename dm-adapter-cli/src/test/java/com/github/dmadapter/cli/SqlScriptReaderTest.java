package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlScriptReaderTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
            "GBK, 中文",
            "GBK, 你好",
            "GB18030, 𠮷😀",
            "Big5, 中文測試",
            "windows-1252, café",
            "windows-1251, Привет мир",
            "KOI8-R, Привет мир",
            "EUC-KR, 한국어 문자 인코딩 테스트",
            "UTF-16LE, 中文",
            "UTF-16BE, 中文",
            "UTF-32LE, 中文",
            "UTF-32BE, 中文"
    })
    void readsShortSqlWithoutLosingCharacters(String encoding, String value) throws Exception {
        Path file = tempDir.resolve("short.sql");
        String sql = "SELECT '" + value + "';";
        Files.writeString(file, sql, Charset.forName(encoding));

        assertThat(SqlScriptReader.read(file)).isEqualTo(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"})
    void rejectsTruncatedUnicodeInsteadOfReplacingBytesOrGuessingAnotherEncoding(String encoding) throws Exception {
        Path file = tempDir.resolve("损坏.sql");
        byte[] bytes = "\uFEFFSELECT '中".getBytes(Charset.forName(encoding));
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));

        assertThatThrownBy(() -> SqlScriptReader.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining(encoding)
                .hasMessageContaining("MalformedInputException")
                .hasCauseInstanceOf(CharacterCodingException.class);
    }

    @Test
    void readsEmptyAsciiAndBomOnlyFiles() throws Exception {
        Path file = tempDir.resolve("empty.sql");
        Files.writeString(file, "");
        assertThat(SqlScriptReader.read(file)).isEmpty();
        Files.writeString(file, "SELECT 1;\r\n");
        assertThat(SqlScriptReader.read(file)).isEqualTo("SELECT 1;\r\n");
        for (String encoding : new String[] {"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"}) {
            Files.writeString(file, "\uFEFF", Charset.forName(encoding));
            assertThat(SqlScriptReader.read(file)).as(encoding).isEmpty();
        }
    }

    @Test
    void fileReadFailureIncludesPathAndUnderlyingReason() {
        Path file = tempDir.resolve("不存在.sql");

        assertThatThrownBy(() -> SqlScriptReader.read(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("读取 SQL 文件失败")
                .hasMessageContaining(file.toString())
                .hasMessageContaining("NoSuchFileException");
    }

    @Test
    void explicitFallbackDecodesLegacyFilesInDirectoryWithUtf8AndReportsInvalidConfiguration() throws Exception {
        String previous = System.getProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY);
        try {
            System.setProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY, "GBK");
            Path file = tempDir.resolve("mixed.sql");
            String sql = "SELECT '中文';";
            Files.writeString(file, sql, Charset.forName("GBK"));
            assertThat(SqlScriptReader.read(file)).isEqualTo(sql);
            Files.writeString(file, sql, StandardCharsets.UTF_8);
            assertThat(SqlScriptReader.read(file)).isEqualTo(sql);
            Files.writeString(file, "\uFEFF" + sql, StandardCharsets.UTF_16LE);
            assertThat(SqlScriptReader.read(file)).isEqualTo(sql);

            Files.writeString(file, sql, Charset.forName("GBK"));
            System.setProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY, "unknown-encoding");
            assertThatThrownBy(() -> SqlScriptReader.read(file))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(file.toString())
                    .hasMessageContaining("unknown-encoding")
                    .hasMessageContaining("UnsupportedCharsetException");

            System.setProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY, "US-ASCII");
            assertThatThrownBy(() -> SqlScriptReader.read(file))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("US-ASCII")
                    .hasCauseInstanceOf(CharacterCodingException.class);
        } finally {
            if (previous == null) {
                System.clearProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY);
            } else {
                System.setProperty(SqlScriptReader.FALLBACK_ENCODING_PROPERTY, previous);
            }
        }
    }
}
