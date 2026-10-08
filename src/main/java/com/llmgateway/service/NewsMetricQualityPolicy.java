package com.llmgateway.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm định chất lượng số liệu và nội dung đầu ra của Qwen AI trước khi lưu vào CSDL:
 * 1. Không tự tạo/bịa đặt số liệu so với bài gốc (giữ nguyên độ tin cậy số liệu).
 * 2. Phân biệt nội dung RSS cung cấp với toàn văn bài báo (tuyệt đối không nhận là đã dịch toàn bài khi chỉ nhận tiêu đề/mô tả ngắn).
 * 3. Tuyệt đối không sinh khuyến nghị mua bán hoặc lời khuyên giao dịch tài chính.
 */
public final class NewsMetricQualityPolicy {

    private static final Pattern FINANCIAL_ADVICE_PATTERN = Pattern.compile(
            "(khuyến nghị mua|khuyến nghị bán|khuyên mua|khuyên bán|hãy mua|hãy bán|đặt lệnh mua|đặt lệnh bán|tín hiệu mua|tín hiệu bán|lời khuyên đầu tư|khuyên nhà đầu tư nên mua|khuyên nhà đầu tư nên bán)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern FULL_ARTICLE_CLAIM_PATTERN = Pattern.compile(
            "(toàn văn bài báo|dịch toàn bộ bài báo|dịch toàn văn|bản dịch toàn văn|toàn bộ nội dung bài viết|full article)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile(
            "\\b\\d+(?:[.,]\\d+)?\\b"
    );

    private NewsMetricQualityPolicy() {
    }

    /**
     * Kiểm tra bản dịch của Qwen có chứa khuyến nghị mua bán tài chính không.
     */
    public static boolean containsFinancialAdvice(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return FINANCIAL_ADVICE_PATTERN.matcher(text).find();
    }

    /**
     * Kiểm tra bản dịch có tuyên bố sai lệch là đã dịch toàn bài hay không.
     */
    public static boolean claimsFullArticleTranslation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return FULL_ARTICLE_CLAIM_PATTERN.matcher(text).find();
    }

    /**
     * Trích xuất các số liệu thực tế xuất hiện trong văn bản.
     */
    public static List<String> extractNumbers(String text) {
        List<String> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        Matcher matcher = NUMBER_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            list.add(matcher.group());
        }
        return list;
    }

    /**
     * Kiểm tra tính nhất quán số liệu:
     * Mọi số liệu có ý nghĩa (>= 3 chữ số hoặc có phần thập phân) trong văn bản tóm tắt
     * phải tương ứng hoặc hiện diện trong bài gốc để tránh ảo giác (hallucination) tạo số liệu mới.
     */
    public static boolean areMetricsConsistent(String rawText, String qwenText) {
        if (qwenText == null || qwenText.isBlank()) {
            return true;
        }
        if (rawText == null || rawText.isBlank()) {
            // Không có văn bản gốc mà qwen lại sinh ra số liệu lớn -> không hợp lệ
            List<String> qwenNums = extractNumbers(qwenText);
            return qwenNums.isEmpty();
        }

        List<String> rawNums = extractNumbers(rawText);
        List<String> qwenNums = extractNumbers(qwenText);

        // Với mỗi số trong Qwen có từ 3 chữ số trở lên (hoặc có số thập phân)
        for (String qNum : qwenNums) {
            String cleanQ = qNum.replace(",", ".");
            if (cleanQ.length() >= 3 || cleanQ.contains(".")) {
                boolean foundInRaw = false;
                for (String rNum : rawNums) {
                    String cleanR = rNum.replace(",", ".");
                    if (cleanR.equals(cleanQ) || rawText.contains(qNum)) {
                        foundInRaw = true;
                        break;
                    }
                }
                // Cho phép năm 2024..2027 là năm hiện hành
                if (!foundInRaw) {
                    try {
                        int val = Integer.parseInt(cleanQ);
                        if (val >= 2024 && val <= 2027) {
                            foundInRaw = true;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!foundInRaw) {
                    // Số liệu hoàn toàn mới được Qwen bịa đặt
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Kiểm định toàn diện trước khi lưu bản dịch của Qwen.
     */
    public static boolean isValidQwenOutput(String rawTitle, String rawSummary,
                                            String displayTitleVi, String displaySummaryVi,
                                            List<String> bulletsVi) {
        if (displayTitleVi == null || displayTitleVi.isBlank()) {
            return false;
        }
        String combinedRaw = (rawTitle != null ? rawTitle : "") + " " + (rawSummary != null ? rawSummary : "");
        String combinedQwen = displayTitleVi + " " + (displaySummaryVi != null ? displaySummaryVi : "");
        if (bulletsVi != null) {
            combinedQwen += " " + String.join(" ", bulletsVi);
        }

        // 1. Không chứa khuyến nghị đầu tư mua/bán
        if (containsFinancialAdvice(combinedQwen)) {
            return false;
        }

        // 2. Không tuyên bố dịch toàn bộ bài báo
        if (claimsFullArticleTranslation(combinedQwen)) {
            return false;
        }

        // 3. Số liệu phải nhất quán với nguồn cung cấp
        if (!areMetricsConsistent(combinedRaw, combinedQwen)) {
            return false;
        }

        return true;
    }
}
