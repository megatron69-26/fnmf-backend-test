package com.llmgateway.service;

import java.util.List;

/**
 * Đơn giản hóa NewsLocalizationQualityPolicy theo yêu cầu:
 * - displayTitleVi không rỗng;
 * - previewBulletsVi có 2–4 phần tử;
 * - bullet không rỗng;
 * - bullet không trùng title;
 * - không chứa boilerplate;
 * - BỎ bộ nhận diện ngôn ngữ regex/token phức tạp.
 * - Publisher không ảnh hưởng việc loại bài.
 */
public class NewsLocalizationQualityPolicy {

    private static final java.util.regex.Pattern VIETNAMESE_DIACRITICS = java.util.regex.Pattern.compile(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ]"
    );

    public static boolean hasExcessiveEnglishTokens(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        // Bắt các từ/cụm từ tiếng Anh chưa dịch phổ biến hoặc dịch sai lệch thuật ngữ
        String[] untranslatedEnglishTokens = {
                "locally listed", "listed locally", "russia", "russian",
                "breaking news", "breaking:", "surges", "surging",
                "plunges", "plunging", "inflows", "outflows",
                "crypto exchange", "crypto exchanges",
                "quỹ giao dịch trái phiếu"
        };
        for (String token : untranslatedEnglishTokens) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasVietnameseCharacteristics(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return VIETNAMESE_DIACRITICS.matcher(text).find();
    }

    public static boolean isLikelyEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return !hasVietnameseCharacteristics(text);
    }

    public static boolean isValidDisplayTitleVi(String displayTitleVi, String originalTitle) {
        if (displayTitleVi == null || displayTitleVi.isBlank()) {
            return false;
        }
        String trimmedDisplay = displayTitleVi.trim();
        if (NewsSummaryQualityPolicy.isBoilerplate(trimmedDisplay)) {
            return false;
        }
        if (!hasVietnameseCharacteristics(trimmedDisplay)) {
            return false;
        }
        if (hasExcessiveEnglishTokens(trimmedDisplay)) {
            return false;
        }
        if (originalTitle != null && !originalTitle.isBlank()) {
            String trimmedOrig = originalTitle.trim();
            if (trimmedDisplay.equalsIgnoreCase(trimmedOrig) && trimmedOrig.length() > 5) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidDisplaySummaryVi(String displaySummaryVi, String originalSummary, String displayTitleVi) {
        if (displaySummaryVi == null || displaySummaryVi.isBlank()) {
            return false;
        }
        String trimmedSummary = displaySummaryVi.trim();
        if (NewsSummaryQualityPolicy.isBoilerplate(trimmedSummary)) {
            return false;
        }
        if (!hasVietnameseCharacteristics(trimmedSummary)) {
            return false;
        }
        if (hasExcessiveEnglishTokens(trimmedSummary)) {
            return false;
        }
        if (originalSummary != null && !originalSummary.isBlank()) {
            String trimmedOrig = originalSummary.trim();
            if (trimmedSummary.equalsIgnoreCase(trimmedOrig) && trimmedOrig.length() > 10) {
                return false;
            }
        }
        if (displayTitleVi != null && !displayTitleVi.isBlank()) {
            if (trimmedSummary.equalsIgnoreCase(displayTitleVi.trim())) {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingBullet(String text) {
        String s = text.trim();
        while (s.startsWith("•") || s.startsWith("-") || s.startsWith("*") || s.startsWith("–")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    public static boolean isValidBullets(List<String> bullets, String title) {
        if (bullets == null || bullets.size() < 2 || bullets.size() > 4) {
            return false;
        }
        for (String b : bullets) {
            if (b == null || b.isBlank()) return false;
            String cleanB = stripLeadingBullet(b);
            if (cleanB.isBlank()) return false;
            if (NewsSummaryQualityPolicy.isBoilerplate(cleanB)) return false;
            if (!hasVietnameseCharacteristics(cleanB)) return false;
            if (hasExcessiveEnglishTokens(cleanB)) return false;
            if (title != null && cleanB.equalsIgnoreCase(title.trim())) return false;
        }
        return true;
    }

    public static boolean isFullyLocalized(String displayTitleVi, String originalTitle, List<String> bullets) {
        return isValidDisplayTitleVi(displayTitleVi, originalTitle) && isValidBullets(bullets, displayTitleVi);
    }

    public static boolean isFullyLocalized(
            String displayTitleVi,
            String originalTitle,
            String displaySummaryVi,
            String originalSummary,
            List<String> bullets
    ) {
        return isFullyLocalized(displayTitleVi, originalTitle, displaySummaryVi, originalSummary, bullets, null);
    }

    public static boolean isFullyLocalized(
            String displayTitleVi,
            String originalTitle,
            String displaySummaryVi,
            String originalSummary,
            List<String> bullets,
            String publisher
    ) {
        if (!isValidDisplayTitleVi(displayTitleVi, originalTitle)) {
            return false;
        }
        if (displaySummaryVi != null && !displaySummaryVi.isBlank()) {
            if (!isValidDisplaySummaryVi(displaySummaryVi, originalSummary, displayTitleVi)) {
                return false;
            }
        }
        if (!isValidBullets(bullets, displayTitleVi)) {
            return false;
        }
        return true;
    }
}
