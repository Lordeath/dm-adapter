package com.github.dmadapter.cli;

import org.mozilla.universalchardet.prober.distributionanalysis.Big5DistributionAnalysis;
import org.mozilla.universalchardet.prober.distributionanalysis.GB2312DistributionAnalysis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

final class ShortChineseEncodingDetector {
    private static final int UNKNOWN_RANK = 20_000;

    private ShortChineseEncodingDetector() {
    }

    static String detect(byte[] bytes) {
        // The universal detector requires more than four frequent Chinese characters before
        // considering its Chinese models. Short SQL literals often do not meet that minimum.
        List<Integer> pairs = new ArrayList<>();
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] >= 0) {
                continue;
            }
            if (index + 1 == bytes.length || pairs.size() == 16) {
                return null;
            }
            pairs.add(index++);
        }
        if (pairs.size() < 2) {
            return null;
        }
        GbFrequency gb = new GbFrequency();
        Big5Frequency big5 = new Big5Frequency();
        double gbRank = averageLogRank(bytes, pairs, "GB18030", index -> gb.rank(bytes, index));
        double big5Rank = averageLogRank(bytes, pairs, "Big5", index -> big5.rank(bytes, index));
        // Prefer Chinese only when its actual character frequencies are common and clearly
        // distinguish the two encodings; otherwise leave the decision to the general detector.
        if (gbRank < Math.log(1024) && big5Rank - gbRank > Math.log(4)) {
            return "GB18030";
        }
        if (big5Rank < Math.log(1024) && gbRank - big5Rank > Math.log(4)) {
            return "Big5";
        }
        return null;
    }

    private static double averageLogRank(
            byte[] bytes, List<Integer> pairs, String encoding, IntUnaryOperator rank
    ) {
        try {
            Charset.forName(encoding).newDecoder().decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            return Double.POSITIVE_INFINITY;
        }
        return pairs.stream().mapToDouble(index -> Math.log(rank.applyAsInt(index) + 1)).average()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static final class GbFrequency extends GB2312DistributionAnalysis {
        int rank(byte[] bytes, int index) {
            int order = getOrder(bytes, index);
            return order >= 0 && order < charToFreqOrder.length ? charToFreqOrder[order] : UNKNOWN_RANK;
        }
    }

    private static final class Big5Frequency extends Big5DistributionAnalysis {
        int rank(byte[] bytes, int index) {
            int order = getOrder(bytes, index);
            return order >= 0 && order < charToFreqOrder.length ? charToFreqOrder[order] : UNKNOWN_RANK;
        }
    }
}
