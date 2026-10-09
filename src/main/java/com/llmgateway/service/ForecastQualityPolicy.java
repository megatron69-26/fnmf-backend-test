package com.llmgateway.service;

import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.exception.ForecastUnavailableException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chính sách kiểm soát chất lượng nhận định và dự báo thị trường (Market Forecasting).
 * RÀNG BUỘC CHẤT LƯỢNG NGHIÊM NGẶT (Zero Slop / Zero Fake):
 * - Hợp nhất 1 implementation duy nhất: isValid() và validateOrThrow() đồng bộ 100%.
 * - Bắt buộc xu hướng thuộc: BULLISH_UPTREND, BEARISH_DOWNTREND, hoặc SIDEWAYS_CONSOLIDATION.
 * - Bắt buộc nguồn phân tích: analysisSource == 'GEMINI'.
 * - Bắt buộc số lượng nến: candleCount trong phạm vi 1..30.
 * - Bắt buộc mã tài sản (symbol) không rỗng, giá hiện tại (currentPrice) > 0, thời gian tạo (generatedAt) hợp lệ.
 * - Bắt buộc ngưỡng hỗ trợ & kháng cự > 0 và Support <= Resistance.
 * - Bắt buộc độ tin cậy confidenceScore trong phạm vi 0..100.
 * - Bắt buộc khuyến nghị thuộc: STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL.
 * - Bắt buộc keyDrivers (2-5 ý), technicalOutlook và fundamentalOutlook phải là TIẾNG VIỆT THỰC CHẤT:
 *   + Từ chối nội dung chủ yếu là tiếng Anh chèn một vài từ tiếng Việt (mixed language).
 *   + Cho phép tên riêng, mã ticker, chỉ báo kỹ thuật và thuật ngữ nghiệp vụ hợp lý:
 *     Nvidia, Apple, Bitcoin, ETF, BTC, ETH, USD, RSI, MACD...
 */
public class ForecastQualityPolicy {

    public static final Set<String> ALLOWED_RECOMMENDATIONS = Set.of(
            "STRONG_BUY",
            "BUY",
            "HOLD",
            "SELL",
            "STRONG_SELL"
    );

    public static final Set<String> ALLOWED_TRENDS = Set.of(
            "BULLISH_UPTREND",
            "BEARISH_DOWNTREND",
            "SIDEWAYS_CONSOLIDATION"
    );

    public static final String REQUIRED_ANALYSIS_SOURCE = "GEMINI";
    public static final Set<String> ALLOWED_ANALYSIS_SOURCES = Set.of(
            "GEMINI",
            "QWEN",
            "QWEN_LOCAL"
    );

    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "vui lòng đợi",
            "chưa có thông tin",
            "không có thông tin chi tiết",
            "dữ liệu giả lập",
            "heuristic",
            "placeholder",
            "đang cập nhật",
            "cam kết lợi nhuận",
            "lãi 100%",
            "chắc chắn mua",
            "chắc chắn bán",
            "không thể lỗ"
    );

    private static final Pattern VIETNAMESE_CHAR_PATTERN = Pattern.compile(
            "[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđĐ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Set<String> ALLOWED_FINANCIAL_TOKENS = Set.of(
            "btc", "eth", "usd", "usdt", "vnd", "etf", "rsi", "macd", "fed", "sec", "fomc",
            "gdp", "cpi", "dxy", "ema", "sma", "ohlcv", "nvidia", "apple", "microsoft", "tesla",
            "binance", "coinbase", "bitcoin", "ethereum", "solana", "fnmf",
            "bnb", "sol", "xrp", "ada", "doge", "cardano", "dogecoin"
    );

    private static final Set<String> COMMON_ENGLISH_WORDS = Set.of(
            "the", "is", "are", "and", "of", "to", "in", "for", "with", "on", "at", "from",
            "by", "about", "as", "into", "like", "through", "after", "over", "between", "out",
            "against", "during", "without", "before", "under", "around", "among", "reports",
            "reported", "revenue", "quarterly", "market", "markets", "outlook", "volatile",
            "earnings", "price", "prices", "growth", "shares", "investors", "sentiment",
            "analysts", "forecast", "expectations", "target", "because", "which", "their",
            "higher", "lower", "remains", "showing", "continued", "strong", "weak",
            "bullish", "bearish", "sideways", "support", "resistance", "long", "short",
            "volume", "action", "plan"
    );

    private static final Pattern FORBIDDEN_UNTRANSLATED_TERMS_PATTERN = Pattern.compile(
            "\\b(bullish|bearish|sideways|support|resistance|long|short|volume|outlook|sentiment|forecast|action\\s+plan)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Kiểm tra tính hợp lệ của bản dự báo thị trường.
     * Sử dụng chung 1 implementation duy nhất với validateOrThrow.
     */
    public static boolean isValid(ForecastResponse forecast) {
        try {
            validateOrThrow(forecast);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Xác thực dự báo, nếu không đạt bất kỳ tiêu chuẩn chất lượng nào thì ném ForecastUnavailableException.
     * Đây là implementation xác thực duy nhất cho toàn hệ thống.
     */
    public static void validateOrThrow(ForecastResponse forecast) {
        if (forecast == null) {
            throw new ForecastUnavailableException("Dữ liệu dự báo rỗng");
        }

        // 1. Mã tài sản
        if (forecast.getSymbol() == null || forecast.getSymbol().trim().isEmpty()) {
            throw new ForecastUnavailableException("Mã tài sản (symbol) không được rỗng");
        }

        // 2. Giá hiện tại phải dương
        BigDecimal price = forecast.getCurrentPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ForecastUnavailableException("Giá hiện tại phải lớn hơn 0");
        }

        // 3. Nguồn phân tích bắt buộc là QWEN hoặc GEMINI
        if (forecast.getAnalysisSource() == null || !ALLOWED_ANALYSIS_SOURCES.contains(forecast.getAnalysisSource().trim().toUpperCase())) {
            throw new ForecastUnavailableException("Nguồn phân tích không hợp lệ (bắt buộc QWEN hoặc GEMINI): " + forecast.getAnalysisSource());
        }

        // 4. Số lượng nến thực tế từ 1 đến 30
        Integer candleCount = forecast.getCandleCount();
        if (candleCount == null || candleCount < 1 || candleCount > 30) {
            throw new ForecastUnavailableException("Số lượng nến phân tích phải trong khoảng 1 đến 30: " + candleCount);
        }

        // 5. Xu hướng thị trường bắt buộc thuộc tập hợp chuẩn
        String trend = forecast.getTrendPrediction();
        if (trend == null || !ALLOWED_TRENDS.contains(trend.trim().toUpperCase())) {
            throw new ForecastUnavailableException("Xu hướng dự báo không hợp lệ: " + trend);
        }

        // 6. Khuyến nghị đầu tư bắt buộc thuộc tập hợp chuẩn
        String rec = forecast.getRecommendation();
        if (rec == null || !ALLOWED_RECOMMENDATIONS.contains(rec.trim().toUpperCase())) {
            throw new ForecastUnavailableException("Khuyến nghị dự báo không hợp lệ: " + rec);
        }

        // 7. Độ tin cậy trong phạm vi 0-100
        Integer confidence = forecast.getConfidenceScore();
        if (confidence == null || confidence < 0 || confidence > 100) {
            throw new ForecastUnavailableException("Độ tin cậy dự báo nằm ngoài phạm vi 0-100: " + confidence);
        }

        // 8. Ngưỡng hỗ trợ & kháng cự phải là số dương và Support <= Resistance
        BigDecimal support = forecast.getSupportLevel();
        BigDecimal resistance = forecast.getResistanceLevel();
        if (support == null || resistance == null || support.compareTo(BigDecimal.ZERO) <= 0 || resistance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ForecastUnavailableException("Ngưỡng hỗ trợ hoặc kháng cự không hợp lệ");
        }
        if (support.compareTo(resistance) > 0) {
            throw new ForecastUnavailableException("Ngưỡng hỗ trợ vượt quá ngưỡng kháng cự");
        }

        // 9. Thời gian tạo không được null và không được ở tương lai xa
        LocalDateTime genAt = forecast.getGeneratedAt();
        if (genAt == null || genAt.isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new ForecastUnavailableException("Thời gian tạo dự báo không hợp lệ");
        }

        // 10. Luận điểm then chốt (Key Drivers) từ 2 đến 5 ý, phải là tiếng Việt thực chất
        List<String> drivers = forecast.getKeyDrivers();
        if (drivers == null || drivers.size() < 2 || drivers.size() > 5) {
            throw new ForecastUnavailableException("Danh sách luận điểm trọng yếu phải gồm 2-5 ý");
        }
        for (String driver : drivers) {
            if (driver == null || driver.trim().length() < 5 || containsForbiddenPhrase(driver) || containsForbiddenUntranslatedTerm(driver)) {
                throw new ForecastUnavailableException("Luận điểm phân tích chứa nội dung không đạt chuẩn, placeholder hoặc thuật ngữ tiếng Anh chưa dịch: " + driver);
            }
            if (!isSubstantialVietnamese(driver)) {
                throw new ForecastUnavailableException("Luận điểm phân tích phải được viết bằng tiếng Việt thực chất: " + driver);
            }
        }

        // 11. Nhận định kỹ thuật: dài ít nhất 10 ký tự, tiếng Việt thực chất, không placeholder
        String tech = forecast.getTechnicalOutlook();
        if (tech == null || tech.trim().length() < 10 || containsForbiddenPhrase(tech) || containsForbiddenUntranslatedTerm(tech)) {
            throw new ForecastUnavailableException("Nhận định kỹ thuật không đạt chuẩn, placeholder hoặc chứa thuật ngữ tiếng Anh chưa dịch");
        }
        if (!isSubstantialVietnamese(tech)) {
            throw new ForecastUnavailableException("Nhận định kỹ thuật phải được viết bằng tiếng Việt thực chất");
        }

        // 12. Nhận định vĩ mô: dài ít nhất 10 ký tự, tiếng Việt thực chất, không placeholder
        String fund = forecast.getFundamentalOutlook();
        if (fund == null || fund.trim().length() < 10 || containsForbiddenPhrase(fund) || containsForbiddenUntranslatedTerm(fund)) {
            throw new ForecastUnavailableException("Nhận định vĩ mô không đạt chuẩn, placeholder hoặc chứa thuật ngữ tiếng Anh chưa dịch");
        }
        if (!isSubstantialVietnamese(fund)) {
            throw new ForecastUnavailableException("Nhận định vĩ mô phải được viết bằng tiếng Việt thực chất");
        }
    }

    /**
     * Kiểm tra sự xuất hiện của thuật ngữ tài chính tiếng Anh chưa được dịch sang tiếng Việt.
     */
    public static boolean containsForbiddenUntranslatedTerm(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return FORBIDDEN_UNTRANSLATED_TERMS_PATTERN.matcher(text).find();
    }

    /**
     * Nhận diện nội dung tiếng Việt thực chất:
     * - Từ chối nội dung chủ yếu là tiếng Anh chèn một vài từ tiếng Việt (mixed language).
     * - Cho phép tên riêng, mã ticker, chỉ báo kỹ thuật và thuật ngữ nghiệp vụ hợp lý:
     *   Nvidia, Apple, Bitcoin, ETF, BTC, ETH, USD, RSI, MACD...
     */
    public static boolean isSubstantialVietnamese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (containsForbiddenUntranslatedTerm(text)) {
            return false;
        }

        String[] tokens = text.split("[\\p{Punct}\\s]+");
        int vietnameseDiacriticWordCount = 0;
        int englishWordCount = 0;
        int nonNeutralWordCount = 0;

        for (String rawToken : tokens) {
            String token = rawToken.trim().toLowerCase();
            if (token.isEmpty()) {
                continue;
            }

            // 1. Kiểm tra nếu là số hoặc ký hiệu tài chính trung tính
            if (token.matches("\\d+([.,]\\d+)?%?") || ALLOWED_FINANCIAL_TOKENS.contains(token)) {
                continue;
            }

            nonNeutralWordCount++;

            // 2. Kiểm tra có ký tự tiếng Việt có dấu
            if (VIETNAMESE_CHAR_PATTERN.matcher(token).find()) {
                vietnameseDiacriticWordCount++;
            } else if (COMMON_ENGLISH_WORDS.contains(token)) {
                englishWordCount++;
            }
        }

        if (nonNeutralWordCount == 0) {
            return false;
        }

        // Bắt buộc:
        // - Phải có ít nhất 2 từ tiếng Việt có dấu
        // - Số từ tiếng Việt có dấu phải vượt trội so với số từ tiếng Anh đặc trưng
        // - Tỷ lệ từ tiếng Việt có dấu trên tổng số từ không trung tính phải đạt tối thiểu 35%
        if (vietnameseDiacriticWordCount < 2) {
            return false;
        }
        if (vietnameseDiacriticWordCount <= englishWordCount) {
            return false;
        }
        double ratio = (double) vietnameseDiacriticWordCount / nonNeutralWordCount;
        return ratio >= 0.35;
    }

    /**
     * Tương thích ngược: ủy quyền cho isSubstantialVietnamese.
     */
    public static boolean isVietnameseText(String text) {
        return isSubstantialVietnamese(text);
    }

    private static boolean containsForbiddenPhrase(String text) {
        if (text == null) return true;
        String lower = text.toLowerCase();
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
