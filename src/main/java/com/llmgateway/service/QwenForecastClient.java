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

    @Value("${forecast.worker.qwen-model:qwen3.5-4b}")
    private String qwenModel = "qwen3.5-4b";

    @Value("${forecast.worker.qwen-timeout-seconds:180}")
    private int qwenTimeoutSeconds = 180;

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

        // 2. Chuẩn bị prompt súc tích cho Qwen
        String systemPrompt = "Bạn là chuyên gia phân tích thị trường tài chính tiếng Việt chuẩn.\n" +
                "Nhiệm vụ: Trả về duy nhất 1 JSON nhận định thị trường theo đúng dữ liệu giá thực tế.\n" +
                "YÊU CẦU BẮT BUỘC:\n" +
                "1. Tiếng Việt có dấu đầy đủ, nghiêm cấm từ ngữ placeholder.\n" +
                "2. Không đưa khuyến nghị chắc chắn: recommendation chỉ chọn 'HOLD', 'BUY', 'SELL' (ưu tiên 'HOLD').\n" +
                "3. trend_prediction chỉ chọn 'BULLISH_UPTREND', 'BEARISH_DOWNTREND', hoặc 'SIDEWAYS_CONSOLIDATION'.\n" +
                "4. support_level <= giá BTC hiện tại <= resistance_level (support và resistance là số thực dương bám sát giá BTC hiện tại).\n" +
                "5. confidence_score là số nguyên 0-100.\n" +
                "6. Trả về đúng 1 JSON hợp lệ, không dùng markdown fences hay giải thích thêm.";

        BigDecimal lowestCandlePrice = null;
        BigDecimal highestCandlePrice = null;
        if (candles != null && !candles.isEmpty()) {
            for (CandleDto c : candles) {
                if (c.getLow() != null && c.getLow().compareTo(BigDecimal.ZERO) > 0) {
                    if (lowestCandlePrice == null || c.getLow().compareTo(lowestCandlePrice) < 0) {
                        lowestCandlePrice = c.getLow();
                    }
                }
                if (c.getHigh() != null && c.getHigh().compareTo(BigDecimal.ZERO) > 0) {
                    if (highestCandlePrice == null || c.getHigh().compareTo(highestCandlePrice) > 0) {
                        highestCandlePrice = c.getHigh();
                    }
                }
            }
        }

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Giá BTC hiện tại: ").append(btcPrice).append(" USDT\n");
        if (lowestCandlePrice != null && highestCandlePrice != null) {
            userPrompt.append("Biên độ nến 30 ngày: ").append(lowestCandlePrice).append(" - ").append(highestCandlePrice).append(" USDT\n");
        }
        if (allPrices != null && !allPrices.isEmpty()) {
            userPrompt.append("Top tài sản: ");
            int c = 0;
            for (MarketPriceDto p : allPrices) {
                if (c >= 3) break;
                userPrompt.append(p.getSymbol()).append("=").append(p.getPrice()).append(" ");
                c++;
            }
            userPrompt.append("\n");
        }
        if (recentNews != null && !recentNews.isEmpty()) {
            for (NewsAiCache n : recentNews) {
                String title = (n.getDisplayTitleVi() != null && !n.getDisplayTitleVi().isBlank())
                        ? n.getDisplayTitleVi() : n.getTitle();
                if (title != null && !title.isBlank()) {
                    userPrompt.append("Tin tham khảo: ").append(title).append("\n");
                    break;
                }
            }
        }
        userPrompt.append("YÊU CẦU: Mọi luận điểm (key_drivers) phải viết bằng TIẾNG VIỆT CÓ DẤU, tuyệt đối không chép lại tiếng Anh.\n");
        userPrompt.append("Mẫu JSON trả về:\n")
                .append("{\n")
                .append("  \"trend_prediction\": \"BULLISH_UPTREND\" | \"BEARISH_DOWNTREND\" | \"SIDEWAYS_CONSOLIDATION\",\n")
                .append("  \"recommendation\": \"HOLD\" | \"BUY\" | \"SELL\",\n")
                .append("  \"confidence_score\": <số nguyên 0-100>,\n")
                .append("  \"support_level\": <số thực <= ").append(btcPrice).append(">,\n")
                .append("  \"resistance_level\": <số thực >= ").append(btcPrice).append(">,\n")
                .append("  \"key_drivers\": [\"<luận điểm 1 tiếng Việt>\", \"<luận điểm 2 tiếng Việt>\"],\n")
                .append("  \"technical_outlook\": \"<nhận định kỹ thuật 1-2 câu tiếng Việt>\",\n")
                .append("  \"fundamental_outlook\": \"<nhận định vĩ mô 1-2 câu tiếng Việt>\"\n")
                .append("}");

        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", qwenModel);
            reqBody.put("temperature", 0.2);
            reqBody.put("max_tokens", 256);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt.toString()));
            reqBody.put("messages", messages);

            String jsonPayload = objectMapper.writeValueAsString(reqBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(qwenUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(qwenTimeoutSeconds))
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

            // Kiểm tra bắt buộc có các trường số liệu, không tự điền fallback bừa bãi
            if (!parsed.has("confidence_score") || !parsed.get("confidence_score").isNumber()) {
                throw new ForecastUnavailableException("Qwen phản hồi thiếu điểm tin cậy (confidence_score) hợp lệ");
            }
            if (!parsed.has("support_level") || !parsed.get("support_level").isNumber()) {
                throw new ForecastUnavailableException("Qwen phản hồi thiếu ngưỡng hỗ trợ (support_level) hợp lệ");
            }
            if (!parsed.has("resistance_level") || !parsed.get("resistance_level").isNumber()) {
                throw new ForecastUnavailableException("Qwen phản hồi thiếu ngưỡng kháng cự (resistance_level) hợp lệ");
            }

            int confidence = parsed.path("confidence_score").asInt();
            BigDecimal support = BigDecimal.valueOf(parsed.path("support_level").asDouble());
            BigDecimal resistance = BigDecimal.valueOf(parsed.path("resistance_level").asDouble());
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
