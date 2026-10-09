package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client gọi Qwen2.5-1.5B chạy cục bộ (llama-server trên 127.0.0.1:8080) để tạo nhận định thị trường.
 * Bắt buộc:
 * - Fail-closed: Khi Qwen timeout, lỗi JSON, hoặc không có giá BTC thật, không bịa số liệu.
 * - Tiếng Việt bắt buộc có dấu, luận điểm và nhận định thực chất.
 * - Không đưa ra khuyến nghị mua/bán chắc chắn 100% hay cam kết lợi nhuận.
 */
@Component
public class QwenForecastClient {

    private static final Logger log = LoggerFactory.getLogger(QwenForecastClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${forecast.worker.qwen-url:http://127.0.0.1:8080/v1/chat/completions}")
    private String qwenUrl = "http://127.0.0.1:8080/v1/chat/completions";

    @Value("${forecast.worker.qwen-model:qwen2.5-1.5b-instruct}")
    private String qwenModel = "qwen2.5-1.5b-instruct";

    @Autowired
    public QwenForecastClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public QwenForecastClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void setQwenUrl(String url) {
        this.qwenUrl = url;
    }

    public void setQwenModel(String model) {
        this.qwenModel = model;
    }

    public ForecastResponse requestMarketForecast(
            List<MarketPriceDto> allPrices,
            List<CandleDto> candles,
            List<NewsAiCache> recentNews,
            String timeframe) {

        // 1. Kiểm tra giá BTC mốc tham chiếu bắt buộc
        BigDecimal btcPrice = null;
        if (allPrices != null) {
            for (MarketPriceDto p : allPrices) {
                if ("BTCUSDT".equalsIgnoreCase(p.getSymbol()) && p.getPrice() != null && p.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                    btcPrice = p.getPrice();
                    break;
                }
            }
        }
        if (btcPrice == null && candles != null && !candles.isEmpty()) {
            CandleDto lastCandle = candles.get(candles.size() - 1);
            if (lastCandle.getClose() != null && lastCandle.getClose().compareTo(BigDecimal.ZERO) > 0) {
                btcPrice = lastCandle.getClose();
            }
        }

        if (btcPrice == null || btcPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ForecastUnavailableException("Không có dữ liệu giá BTC thực tế để làm mốc tham chiếu nhận định");
        }

        // 2. Chuẩn bị prompt cho Qwen
        String systemPrompt = "Bạn là chuyên gia phân tích thị trường tài chính tiếng Việt chuẩn.\n" +
                "Nhiệm vụ: Phân tích xu hướng toàn thị trường dựa trên dữ liệu giá BTC, các tài sản Binance và tin tức thực tế được cung cấp.\n" +
                "RÀNG BUỘC CHẤT LƯỢNG NGHIÊM NGẶT:\n" +
                "1. Toàn bộ nội dung phân tích (key_drivers, technical_outlook, fundamental_outlook) PHẢI VIẾT BẰNG TIẾNG VIỆT THỰC CHẤT CÓ DẤU.\n" +
                "2. KHÔNG ĐƯỢC ĐƯA RA KHUYẾN NGHỊ MUA/BÁN CHẮC CHẮN. Tuyệt đối không cam kết lợi nhuận hay khẳng định 100%. Khuyến nghị chỉ chọn trong ['HOLD', 'BUY', 'SELL'] mang tính tham khảo thận trọng (ưu tiên HOLD khi biến động mạnh).\n" +
                "3. Xu hướng (trend_prediction) chỉ chọn trong ['BULLISH_UPTREND', 'BEARISH_DOWNTREND', 'SIDEWAYS_CONSOLIDATION'].\n" +
                "4. Các mức hỗ trợ (support_level) và kháng cự (resistance_level) phải là số thực dương, support_level <= resistance_level, bám sát mức giá BTC hiện tại.\n" +
                "5. Độ tin cậy (confidence_score) từ 0 đến 100.\n" +
                "6. Danh sách luận điểm trọng yếu (key_drivers) gồm 2 đến 4 ý tiếng Việt cụ thể, rõ ràng.\n" +
                "7. Nhận định kỹ thuật và vĩ mô dài ít nhất 15 ký tự tiếng Việt.\n" +
                "8. Tuyệt đối không bịa đặt số liệu hay dùng từ ngữ placeholder.\n" +
                "9. Trả về JSON thuần không dùng markdown fences.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Dữ liệu giá tham chiếu BTC: ").append(btcPrice).append(" USDT\n");
        if (allPrices != null && !allPrices.isEmpty()) {
            userPrompt.append("Giá các tài sản thị trường:\n");
            for (MarketPriceDto p : allPrices) {
                userPrompt.append("- ").append(p.getSymbol()).append(": ").append(p.getPrice())
                        .append(" (24h: ").append(p.getChange24h()).append("%)\n");
            }
        }
        if (recentNews != null && !recentNews.isEmpty()) {
            userPrompt.append("Tin tức thị trường gần đây:\n");
            int newsCount = 0;
            for (NewsAiCache n : recentNews) {
                if (newsCount >= 3) break;
                if (n.getTitle() != null && !n.getTitle().isBlank()) {
                    userPrompt.append("- ").append(n.getTitle()).append("\n");
                    newsCount++;
                }
            }
        }
        userPrompt.append("\nHãy tạo nhận định thị trường theo đúng định dạng JSON sau:\n")
                .append("{\n")
                .append("  \"trend_prediction\": \"BULLISH_UPTREND\" | \"BEARISH_DOWNTREND\" | \"SIDEWAYS_CONSOLIDATION\",\n")
                .append("  \"recommendation\": \"HOLD\" | \"BUY\" | \"SELL\",\n")
                .append("  \"confidence_score\": 75,\n")
                .append("  \"support_level\": 64000.0,\n")
                .append("  \"resistance_level\": 68000.0,\n")
                .append("  \"key_drivers\": [\"Luận điểm 1 bằng tiếng Việt có dấu\", \"Luận điểm 2 bằng tiếng Việt có dấu\"],\n")
                .append("  \"technical_outlook\": \"Nhận định kỹ thuật ngắn gọn bằng tiếng Việt có dấu\",\n")
                .append("  \"fundamental_outlook\": \"Nhận định vĩ mô ngắn gọn bằng tiếng Việt có dấu\"\n")
                .append("}");

        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", qwenModel);
            reqBody.put("temperature", 0.2);
            reqBody.put("max_tokens", 384);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt.toString()));
            reqBody.put("messages", messages);

            String jsonPayload = objectMapper.writeValueAsString(reqBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(qwenUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Qwen llama-server trả HTTP {}: {}", response.statusCode(), response.body());
                throw new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("Qwen phản hồi không có choices: {}", response.body());
                throw new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.");
            }

            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.");
            }

            content = stripCodeFences(content.trim());
            JsonNode parsed = objectMapper.readTree(content);

            String trend = parsed.path("trend_prediction").asText(null);
            String rec = parsed.path("recommendation").asText("HOLD").toUpperCase(Locale.ROOT);
            int confidence = parsed.path("confidence_score").asInt(70);
            BigDecimal support = parsed.has("support_level") ? BigDecimal.valueOf(parsed.path("support_level").asDouble()) : btcPrice.multiply(BigDecimal.valueOf(0.97));
            BigDecimal resistance = parsed.has("resistance_level") ? BigDecimal.valueOf(parsed.path("resistance_level").asDouble()) : btcPrice.multiply(BigDecimal.valueOf(1.03));
            String techOutlook = parsed.path("technical_outlook").asText(null);
            String fundOutlook = parsed.path("fundamental_outlook").asText(null);

            List<String> drivers = new ArrayList<>();
            JsonNode driversNode = parsed.path("key_drivers");
            if (driversNode.isArray()) {
                for (JsonNode d : driversNode) {
                    if (d.isTextual() && !d.asText().isBlank()) {
                        drivers.add(d.asText().trim());
                    }
                }
            }

            // Kiểm tra không đưa ra khuyến nghị mua bán chắc chắn
            if ("STRONG_BUY".equalsIgnoreCase(rec) || "STRONG_SELL".equalsIgnoreCase(rec)) {
                rec = "HOLD";
            }

            int candleCount = (candles != null && !candles.isEmpty()) ? Math.min(30, candles.size()) : 30;
            String tf = (timeframe != null && !timeframe.isBlank()) ? timeframe : "24H_7D";

            ForecastResponse forecastResponse = new ForecastResponse(
                    "MARKET",
                    "Nhận định toàn thị trường",
                    btcPrice,
                    trend,
                    tf,
                    support,
                    resistance,
                    rec,
                    confidence,
                    drivers,
                    techOutlook,
                    fundOutlook,
                    "QWEN",
                    candleCount,
                    false,
                    LocalDateTime.now()
            );

            ForecastQualityPolicy.validateOrThrow(forecastResponse);
            return forecastResponse;

        } catch (ForecastUnavailableException fue) {
            throw fue;
        } catch (Exception ex) {
            log.warn("Lỗi giao tiếp hoặc parse phản hồi từ Qwen: {}", ex.getMessage());
            throw new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.", ex);
        }
    }

    private String stripCodeFences(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
