package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Client giao tiếp cục bộ qua loopback với Qwen2.5-1.5B (llama-server trên 127.0.0.1:8080).
 * Tuyệt đối không gọi qua mạng ngoài, có timeout nghiêm ngặt (45 giây) và fail-closed khi lỗi.
 */
@Component
public class QwenLocalClient {

    private static final Logger log = LoggerFactory.getLogger(QwenLocalClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${news.worker.qwen-url:http://127.0.0.1:8080/v1/chat/completions}")
    private String qwenUrl = "http://127.0.0.1:8080/v1/chat/completions";

    @Value("${news.worker.qwen-model:qwen2.5-1.5b-instruct}")
    private String qwenModel = "qwen2.5-1.5b-instruct";

    public static class QwenTranslationResult {
        private final String displayTitleVi;
        private final String displaySummaryVi;
        private final List<String> bulletPointsVi;
        private final String bulletPointsViJson;
        private final String sentiment;
        private final int confidencePct;

        public QwenTranslationResult(String displayTitleVi, String displaySummaryVi,
                                    List<String> bulletPointsVi, String bulletPointsViJson,
                                    String sentiment, int confidencePct) {
            this.displayTitleVi = displayTitleVi;
            this.displaySummaryVi = displaySummaryVi;
            this.bulletPointsVi = bulletPointsVi;
            this.bulletPointsViJson = bulletPointsViJson;
            this.sentiment = sentiment;
            this.confidencePct = confidencePct;
        }

        public String getDisplayTitleVi() { return displayTitleVi; }
        public String getDisplaySummaryVi() { return displaySummaryVi; }
        public List<String> getBulletPointsVi() { return bulletPointsVi; }
        public String getBulletPointsViJson() { return bulletPointsViJson; }
        public String getSentiment() { return sentiment; }
        public int getConfidencePct() { return confidencePct; }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QwenLocalClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public QwenLocalClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void setQwenUrl(String url) {
        this.qwenUrl = url;
    }

    public Optional<QwenTranslationResult> translateAndSummarize(String rawTitle, String rawSummary) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return Optional.empty();
        }

        String safeSummary = rawSummary != null ? rawSummary.trim() : "";
        String systemPrompt = "Bạn là chuyên gia dịch thuật và tóm tắt tin tức tài chính sang tiếng Việt.\n" +
                "Bạn chỉ nhận được tiêu đề và phần mô tả tóm tắt (snippet) từ nguồn RSS, không phải toàn văn bài báo.\n" +
                "Tuyệt đối không tuyên bố là đã dịch toàn bài báo. Giữ nguyên số liệu chính xác từ bài gốc, không bịa đặt số liệu mới.\n" +
                "Tuyệt đối không đưa ra khuyến nghị mua bán hoặc lời khuyên đầu tư.\n" +
                "Trả về định dạng JSON thuần không dùng markdown.";

        String userPrompt = "Tiêu đề gốc: " + rawTitle + "\n" +
                "Mô tả gốc: " + safeSummary + "\n\n" +
                "Hãy dịch và tóm tắt theo cấu trúc JSON sau:\n" +
                "{\n" +
                "  \"display_title_vi\": \"Tiêu đề bài viết bằng tiếng Việt chuẩn có dấu\",\n" +
                "  \"display_summary_vi\": \"Đoạn tóm tắt tiếng Việt 1-2 câu từ mô tả trên\",\n" +
                "  \"bullet_points_vi\": [\"Ý chính 1 bằng tiếng Việt\", \"Ý chính 2 bằng tiếng Việt\"],\n" +
                "  \"sentiment\": \"BULLISH\" hoặc \"BEARISH\" hoặc \"NEUTRAL\",\n" +
                "  \"confidence_pct\": 85\n" +
                "}";

        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", qwenModel);
            reqBody.put("temperature", 0.2);
            reqBody.put("max_tokens", 512);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
            reqBody.put("messages", messages);

            String jsonPayload = objectMapper.writeValueAsString(reqBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(qwenUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Qwen llama-server trả HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("Qwen phản hồi không có choices: {}", response.body());
                return Optional.empty();
            }

            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }

            // Dọn dẹp code fence ```json ... ``` nếu có
            content = stripCodeFences(content.trim());

            JsonNode parsed = objectMapper.readTree(content);
            String titleVi = parsed.path("display_title_vi").asText(null);
            String summaryVi = parsed.path("display_summary_vi").asText(null);
            String sentiment = parsed.path("sentiment").asText("NEUTRAL").toUpperCase(Locale.ROOT);
            int confidence = parsed.path("confidence_pct").asInt(80);

            List<String> bullets = new ArrayList<>();
            JsonNode bulletsNode = parsed.path("bullet_points_vi");
            if (bulletsNode.isArray()) {
                for (JsonNode b : bulletsNode) {
                    String bText = b.asText();
                    if (bText != null && !bText.isBlank()) {
                        bullets.add(bText.trim());
                    }
                }
            }

            // Kiểm định chất lượng tiếng Việt
            if (!NewsLocalizationQualityPolicy.isValidDisplayTitleVi(titleVi, rawTitle)) {
                log.warn("Qwen display_title_vi không hợp lệ: '{}'", titleVi);
                return Optional.empty();
            }

            if (!NewsLocalizationQualityPolicy.isValidDisplaySummaryVi(summaryVi, safeSummary, titleVi)) {
                log.warn("Qwen display_summary_vi không hợp lệ: '{}'", summaryVi);
                return Optional.empty();
            }

            if (!NewsLocalizationQualityPolicy.isValidBullets(bullets, titleVi)) {
                log.warn("Qwen bullet_points_vi không hợp lệ: {}", bullets);
                return Optional.empty();
            }

            // Kiểm định số liệu, không sinh khuyến nghị mua bán, không nhận toàn bài
            if (!NewsMetricQualityPolicy.isValidQwenOutput(rawTitle, safeSummary, titleVi, summaryVi, bullets)) {
                log.warn("Qwen vi phạm NewsMetricQualityPolicy (chứa khuyến nghị mua bán hoặc bịa số liệu): title='{}'", titleVi);
                return Optional.empty();
            }

            if (!"BULLISH".equals(sentiment) && !"BEARISH".equals(sentiment) && !"NEUTRAL".equals(sentiment)) {
                sentiment = "NEUTRAL";
            }
            if (confidence < 0 || confidence > 100) {
                confidence = 80;
            }

            String bulletsJson = objectMapper.writeValueAsString(bullets);
            return Optional.of(new QwenTranslationResult(
                    titleVi.trim(),
                    summaryVi.trim(),
                    bullets,
                    bulletsJson,
                    sentiment,
                    confidence
            ));
        } catch (Exception e) {
            log.error("Ngoại lệ khi gọi Qwen xử lý bài báo '{}': {}", rawTitle, e.getMessage());
            return Optional.empty();
        }
    }

    private String stripCodeFences(String text) {
        String s = text.trim();
        if (s.startsWith("```json")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
