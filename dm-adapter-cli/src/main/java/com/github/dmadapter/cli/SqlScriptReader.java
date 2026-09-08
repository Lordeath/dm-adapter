package com.github.dmadapter.cli;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SqlScriptReader {
    static final String FALLBACK_ENCODING_PROPERTY = "dm.adapter.sqlScriptFallbackEncoding";
    private static final String FALLBACK_ENCODING_ENV = "DM_SQL_SCRIPT_FALLBACK_ENCODING";

    private SqlScriptReader() {
    }

    static String read(Path file) throws IOException {
        String encoding = "自动识别";
        try {
            byte[] bytes = Files.readAllBytes(file);
            // UTF-32LE starts with the UTF-16LE BOM, so check four-byte BOMs first.
            Bom bom = bom(bytes);
            if (bom != null) {
                encoding = bom.encoding();
                return decode(bytes, bom.length(), Charset.forName(encoding));
            }
            String unicode = unicodeWithoutBom(bytes);
            if (unicode != null) {
                encoding = unicode;
                return decode(bytes, 0, Charset.forName(encoding));
            }
            CharacterCodingException utf8Failure;
            try {
                return decode(bytes, 0, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                utf8Failure = e;
            }
            String configured = System.getProperty(FALLBACK_ENCODING_PROPERTY, System.getenv(FALLBACK_ENCODING_ENV));
            if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured.trim())) {
                encoding = configured.trim();
                return decode(bytes, 0, Charset.forName(encoding));
            }
            UniversalDetector detector = new UniversalDetector();
            detector.handleData(bytes);
            detector.dataEnd();
            String detected = detector.getDetectedCharset();
            if (detected == null || !isMultibyteEncoding(detected)) {
                String chinese = ShortChineseEncodingDetector.detect(bytes);
                if (chinese != null) {
                    detected = chinese;
                }
            }
            if (detected == null) {
                throw new IOException("无法可靠识别文件编码；UTF-8 解码失败："
                        + utf8Failure.getClass().getSimpleName() + ": " + utf8Failure.getMessage(), utf8Failure);
            }
            encoding = detected;
            return decode(bytes, 0, Charset.forName(encoding));
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException("读取 SQL 文件失败：" + file.toAbsolutePath().normalize()
                    + "；编码：" + encoding
                    + "；原因：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + "。请检查文件是否损坏；非 Unicode 文件可通过 " + FALLBACK_ENCODING_ENV
                    + " 或 -D" + FALLBACK_ENCODING_PROPERTY + " 指定实际编码，或另存为 UTF-8 后重试。", e);
        }
    }

    private static boolean isMultibyteEncoding(String encoding) {
        return switch (encoding.toUpperCase(java.util.Locale.ROOT)) {
            case "GB18030", "BIG5", "SHIFT_JIS", "EUC-JP", "EUC-KR", "EUC-TW" -> true;
            default -> encoding.startsWith("UTF-") || encoding.startsWith("ISO-2022");
        };
    }

    private static String decode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    private static Bom bom(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) {
            return new Bom("UTF-32LE", 4);
        }
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) {
            return new Bom("UTF-32BE", 4);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Bom("UTF-8", 3);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Bom("UTF-16LE", 2);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Bom("UTF-16BE", 2);
        }
        return null;
    }

    private static String unicodeWithoutBom(byte[] bytes) {
        // SQL contains ASCII keywords and punctuation. Repeated zero-byte lanes distinguish
        // BOM-less UTF-16/32 from UTF-8 before UTF-8 decoding can accept the embedded NULs.
        for (int width : new int[] {4, 2}) {
            int units = Math.min(bytes.length, 8192) / width;
            if (units < 4) {
                continue;
            }
            for (boolean littleEndian : new boolean[] {true, false}) {
                int asciiUnits = 0;
                for (int unit = 0; unit < units; unit++) {
                    int start = unit * width;
                    int valueIndex = littleEndian ? 0 : width - 1;
                    int value = Byte.toUnsignedInt(bytes[start + valueIndex]);
                    boolean ascii = value == '\t' || value == '\n' || value == '\r'
                            || value >= 0x20 && value <= 0x7E;
                    for (int lane = 0; lane < width && ascii; lane++) {
                        if (lane != valueIndex && bytes[start + lane] != 0) {
                            ascii = false;
                        }
                    }
                    if (ascii) {
                        asciiUnits++;
                    }
                }
                if (asciiUnits > units * 0.6) {
                    return "UTF-" + (width * 8) + (littleEndian ? "LE" : "BE");
                }
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private record Bom(String encoding, int length) {
    }
}
