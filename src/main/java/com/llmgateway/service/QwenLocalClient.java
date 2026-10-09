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

    @Value("${news.worker.qwen-model:qwen3.5-4b}")
    private String qwenModel = "qwen3.5-4b";

    @Value("${news.worker.qwen-timeout-seconds:120}")
    private int qwenTimeoutSeconds = 120;

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

    private final QwenInferenceCoordinator coordinator;

    @org.springframework.beans.factory.annotation.Autowired
    public QwenLocalClient(ObjectMapper objectMapper,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) QwenInferenceCoordinator coordinator) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), coordinator);
    }

    public QwenLocalClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this(objectMapper, httpClient, new QwenInferenceCoordinator());
    }

    public QwenLocalClient(ObjectMapper objectMapper, HttpClient httpClient, QwenInferenceCoordinator coordinator) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.coordinator = coordinator != null ? coordinator : new QwenInferenceCoordinator();
    }

    public void setQwenUrl(String url) {
        this.qwenUrl = url;
    }

    public Optional<QwenTranslationResult> translateAndSummarize(String rawTitle, String rawSummary) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return Optional.empty();
        }

        try {
            return coordinator.executeWithLock("NEWS_LOCAL_CLIENT", Duration.ofSeconds(qwenTimeoutSeconds), () -> {
                return doTranslateAndSummarize(rawTitle, rawSummary);
            });
        } catch (Exception e) {
            log.warn("Lỗi hoặc không lấy được khóa điều phối Qwen cho tin tức: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<QwenTranslationResult> doTranslateAndSummarize(String rawTitle, String rawSummary) {
        String safeSummary = rawSummary != null ? rawSummary.trim() : "";
        if (safeSummary.length() > 250) {
            safeSummary = safeSummary.substring(0, 250).trim();
        }
        String systemPrompt = "Dịch tin tài chính sang tiếng Việt chuẩn và xuất JSON thuần.\n" +
                "- Không để sót tiếng Anh: 'locally listed'->'niêm yết trong nước', 'Russia'->'Nga', 'surges'->'tăng vọt', 'inflows'->'dòng vốn vào', 'outflows'->'dòng vốn rút ra'.\n" +
                "- 'ETF' dịch là 'quỹ ETF' hoặc 'quỹ hoán đổi danh mục', TUYỆT ĐỐI không dịch thành 'quỹ trái phiếu'.\n" +
                "- Giữ nguyên số liệu bài gốc, không bịa đặt, không khuyên mua bán, không dùng markdown fences.";

        String userPrompt = "Tiêu đề: " + rawTitle + "\n" +
                "Mô tả: " + safeSummary + "\n" +
                "Trả về duy nhất JSON:\n" +
                "{\n" +
                "  \"display_title_vi\": \"Tiêu đề tiếng Việt có dấu\",\n" +
                "  \"display_summary_vi\": \"Tóm tắt 1-2 câu tiếng Việt\",\n" +
                "  \"bullet_points_vi\": [\"Ý chính 1 tiếng Việt\", \"Ý chính 2 tiếng Việt\"],\n" +
                "  \"sentiment\": \"BULLISH\"|\"BEARISH\"|\"NEUTRAL\",\n" +
                "  \"confidence_pct\": 85\n" +
                "}";

        try {
            Map<String, Object> reqBody = new HashMap<>();
            reqBody.put("model", qwenModel);
            reqBody.put("temperature", 0.2);
            reqBody.put("max_tokens", 200);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userPrompt));
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
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("Qwen phản hồi không có choices: {}", response.body());
                return Optional.empty();
            }

            JsonNode messageNode = choices.get(0).path("message");
            String content = messageNode.path("content").asText(null);
            // Invariant: reasoning_content là nội dung suy luận nội bộ, TUYỆT ĐỐI không coi là JSON đáp án
            if (content == null || content.isBlank()) {
                log.warn("Qwen phản hồi nội dung content rỗng (không chấp nhận reasoning_content làm đáp án): messageNode={}", messageNode);
                return Optional.empty();
            }

            // Loại bỏ reasoning tag <think>...</think> nếu có
            content = stripReasoning(content);

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

    private String stripReasoning(String text) {
        if (text == null) return null;
        String s = text.trim();
        int thinkStart = s.indexOf("<think>");
        int thinkEnd = s.indexOf("</think>");
        if (thinkStart != -1 && thinkEnd != -1 && thinkEnd > thinkStart) {
            s = s.substring(0, thinkStart) + s.substring(thinkEnd + 8);
        } else if (thinkStart != -1 && thinkEnd == -1) {
            s = s.substring(thinkStart + 7);
        }
        return s.trim();
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

    public String getQwenModel() {
        return qwenModel;
    }

    public void setQwenModel(String qwenModel) {
        this.qwenModel = qwenModel;
    }

    public int getQwenTimeoutSeconds() {
        return qwenTimeoutSeconds;
    }

    public void setQwenTimeoutSeconds(int qwenTimeoutSeconds) {
        this.qwenTimeoutSeconds = qwenTimeoutSeconds;
    }
}
