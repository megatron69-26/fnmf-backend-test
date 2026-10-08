package com.llmgateway.controller;

import com.llmgateway.dto.mobile.MobileAiAnalysisDto;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.mobile.MobileNewsDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.dto.news.NewsFeedItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ===========================================================================================
 * MOBILE SYNC CONTROLLER - API ĐỒNG BỘ CHUYÊN DỤNG CHO ANDROID (BẠN MẠNH)
 * ===========================================================================================
 *
 * Controller này cung cấp endpoint trả dữ liệu ở FORMAT KHỚP 100% với Room Database
 * của bạn Mạnh (DungQuenTenAnh-1.0.0), bao gồm:
 *   - MobileNewsDto       ←→  Room Entity "News" (newsId, title, url, publishedAt)
 *   - MobileAiAnalysisDto ←→  Room Entity "AI_Analysis" (newsId, summary, sentiment, confidenceScore, reason)
 *
 * Luồng xử lý:
 *   1. Đọc dữ liệu từ CSDL (NEWS_AI_CACHE) - cùng nguồn duy nhất với /api/news/feed
 *   2. Chuyển đổi (map) các trường sang đúng format Room DB của Mạnh
 *   3. Trả JSON cho Android → Android insert thẳng vào Room DB → Hiển thị Offline
 *
 * ĐẢM BẢO: KHÔNG GÂY XUNG ĐỘT VỚI CÁC API GỐC CỦA KHÔI.
 * ===========================================================================================
 */
@RestController
@RequestMapping("/api/mobile/news")
@Tag(name = "📱 Mobile Sync (Mạnh - Room DB)", description = "API đồng bộ dữ liệu cho Android Room Database - Format khớp 100% với Entity News & AI_Analysis của bạn Mạnh")
public class MobileSyncController {

    private final AiNewsService aiNewsService;
    private final com.llmgateway.service.NewsCacheService newsCacheService;

    public MobileSyncController(AiNewsService aiNewsService, com.llmgateway.service.NewsCacheService newsCacheService) {
        this.aiNewsService = aiNewsService;
        this.newsCacheService = newsCacheService;
    }

    /**
     * GET /api/mobile/news/sync?symbol=BTCUSDT&limit=5
     *
     * API chính để Android gọi lấy danh sách tin tức + AI phân tích,
     * trả về format khớp 100% với Room DB của Mạnh.
     */
    @GetMapping("/sync")
    @Operation(
            summary = "Đồng bộ Tin tức + AI Analysis cho Room DB Android",
            description = "Lấy danh sách bài báo thật từ Alpha Vantage + Kết quả phân tích Gemini AI, " +
                    "trả về format JSON khớp 100% với Room Entity News & AI_Analysis của bạn Mạnh. " +
                    "Android chỉ cần gọi API này rồi insert thẳng vào Room DB để hiển thị offline."
    )
    public ResponseEntity<?> syncNewsForMobile(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "" + AiNewsService.DEFAULT_LIMIT) int limit) {

        if (!AiNewsService.isValidLimit(limit)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT,
                    "data", List.of()
            ));
        }

        try {
            // Bước 1: Lấy dữ liệu từ CSDL Cache qua NewsCacheService (Cache-only)
            List<NewsAiCache> cachedNews;
            if (symbol != null && !symbol.isBlank()) {
                cachedNews = newsCacheService.findBySymbolOrderByPublishedAtDesc(symbol, limit);
            } else {
                cachedNews = newsCacheService.findTopByOrderByPublishedAtDesc(limit);
            }

            // Bước 2: Nếu rỗng cho symbol cụ thể, fallback sang tin thị trường chung trong cache
            if (cachedNews.isEmpty()) {
                cachedNews = newsCacheService.findTopByOrderByPublishedAtDesc(limit);
                if (cachedNews.isEmpty()) {
                    cachedNews = newsCacheService.findAll(limit);
                }
            }

            // Bước 3: Cache-only invariant: Kể cả khi cache rỗng, TUYỆT ĐỐI KHÔNG gọi LLM hoặc external provider
            if (cachedNews.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }

            List<MobileNewsBundleResponse> result = cachedNews.stream()
                    .limit(limit)
                    .map(this::mapToMobileBundle)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", e.getMessage() != null ? e.getMessage() : "Tham số limit không hợp lệ",
                    "data", List.of()
            ));
        }
    }

    /**
     * GET /api/mobile/news/{newsId}
     *
     * Lấy chi tiết 1 bài báo theo newsId (format "NEWS_xxx").
     */
    @GetMapping("/{newsId}")
    @Operation(
            summary = "Lấy chi tiết 1 bài báo kèm AI Analysis theo News ID",
            description = "Trả về chi tiết bài báo theo format Room Entity"
    )
    public ResponseEntity<MobileNewsBundleResponse> getNewsById(@PathVariable String newsId) {
        // Parse ID từ format "NEWS_xxx" → Long
        String idStr = newsId.replace("NEWS_", "");
        Long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }

        List<NewsAiCache> allCached = aiNewsService.getAllCachedNews();
        return allCached.stream()
                .filter(item -> item.getId() != null && item.getId().equals(id))
                .findFirst()
                .map(item -> ResponseEntity.ok(mapToMobileBundle(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/mobile/news/analysis-only?symbol=BTCUSDT&limit=10
     *
     * Chỉ trả về danh sách AI_Analysis (không kèm News), phù hợp cho
     * trường hợp bạn Mạnh chỉ muốn cập nhật bảng AI_Analysis trong Room DB.
     */
    @GetMapping("/analysis-only")
    @Operation(
            summary = "Chỉ lấy danh sách AI Analysis (không kèm News)",
            description = "Trả về chỉ phần AI phân tích, format khớp 100% với Room Entity AI_Analysis"
    )
    public ResponseEntity<?> getAnalysisOnly(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "10") int limit) {

        if (!AiNewsService.isValidLimit(limit)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT,
                    "data", List.of()
            ));
        }

        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();

        List<MobileAiAnalysisDto> result = cachedNews.stream()
                .filter(item -> symbol == null || symbol.isEmpty() ||
                        (item.getSymbol() != null && item.getSymbol().equalsIgnoreCase(symbol)))
                .limit(limit)
                .map(this::mapToMobileAnalysis)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE MAPPER METHODS: Backend Entity → Room DB Format
    // ═══════════════════════════════════════════════════════════════

    /**
     * Chuyển đổi 1 bản ghi NEWS_AI_CACHE → Bundle {News + AI_Analysis} cho Room DB.
     */
    private MobileNewsBundleResponse mapToMobileBundle(NewsAiCache entity) {
        MobileNewsDto newsDto = mapToMobileNews(entity);
        MobileAiAnalysisDto analysisDto = mapToMobileAnalysis(entity);
        return new MobileNewsBundleResponse(newsDto, analysisDto);
    }

    /**
     * Map: NEWS_AI_CACHE → Room Entity "News"
     *
     * Chuyển đổi:
     *   - id (Long)           → newsId (String "NEWS_xxx")
     *   - title (String)      → title (String) ✅ KHỚP
     *   - articleUrl (String)  → url (String) ✅ ĐỔI TÊN
     *   - publishedAt (LocalDateTime) → publishedAt (long, Unix epoch ms) ✅ ĐỔI KIỂU
     */
    private MobileNewsDto mapToMobileNews(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        long publishedAtEpoch = entity.getPublishedAt() != null
                ? entity.getPublishedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : System.currentTimeMillis();

        // Ưu tiên displayTitleVi nếu có; nếu Qwen lỗi hoặc chưa dịch, dùng tiêu đề gốc (fail-closed)
        String effectiveTitle = (entity.getDisplayTitleVi() != null && !entity.getDisplayTitleVi().isBlank())
                ? entity.getDisplayTitleVi().trim()
                : ((entity.getOriginalTitle() != null && !entity.getOriginalTitle().isBlank())
                    ? entity.getOriginalTitle().trim()
                    : (entity.getTitle() != null ? entity.getTitle().trim() : ""));

        return new MobileNewsDto(
                newsId,
                effectiveTitle,
                entity.getArticleUrl(),
                publishedAtEpoch
        );
    }

    /**
     * Map: NEWS_AI_CACHE → Room Entity "AI_Analysis"
     *
     * Chuyển đổi:
     *   - id (Long)              → newsId (String "NEWS_xxx") ✅ FK MAPPING
     *   - summaryPoints (CLOB)   → summary (String) ✅ ĐỔI TÊN
     *   - sentiment (String)     → sentiment (String) ✅ KHỚP
     *   - confidencePct (BigDecimal) → confidenceScore (int) ✅ ĐỔI KIỂU
     *   - reason (CLOB)          → reason (String) ✅ KHỚP
     */
    private MobileAiAnalysisDto mapToMobileAnalysis(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        int confidence = entity.getConfidencePct() != null
                ? entity.getConfidencePct().intValue()
                : 80;

        // Ưu tiên displaySummaryVi nếu có; nếu Qwen lỗi, dùng originalSummary bài gốc
        String effectiveSummary = (entity.getDisplaySummaryVi() != null && !entity.getDisplaySummaryVi().isBlank())
                ? entity.getDisplaySummaryVi().trim()
                : ((entity.getSummaryPoints() != null && !entity.getSummaryPoints().isBlank())
                    ? entity.getSummaryPoints().trim()
                    : ((entity.getOriginalSummary() != null && !entity.getOriginalSummary().isBlank())
                        ? entity.getOriginalSummary().trim()
                        : ""));

        return new MobileAiAnalysisDto(
                newsId,
                effectiveSummary,
                entity.getSentiment() != null ? entity.getSentiment() : "NEUTRAL",
                confidence,
                entity.getReason() != null ? entity.getReason() : ""
        );
    }
}
