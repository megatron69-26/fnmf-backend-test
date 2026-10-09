package com.llmgateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.mobile.MobileAiAnalysisDto;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.mobile.MobileNewsDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.NewsCacheService;
import com.llmgateway.service.NewsLocalizationQualityPolicy;
import com.llmgateway.service.NewsSummaryQualityPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.Collections;
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
 *   1. Đọc dữ liệu từ CSDL (NEWS_AI_CACHE) - Cache-Only, tuyệt đối không gọi LLM hay mạng ngoài.
 *   2. Kiểm định chất lượng nghiêm ngặt (Fail-Closed):
 *      - LOẠI BỎ 100% bài chưa dịch (RAW_PENDING), bài lỗi (QWEN_ERROR), hoặc bài chưa đạt chuẩn tiếng Việt.
 *      - TUYỆT ĐỐI KHÔNG fallback sang tiêu đề hoặc tóm tắt tiếng Anh gốc trên Mobile App.
 *   3. Chuyển đổi (map) các trường sang đúng format Room DB của Mạnh.
 *   4. Trả JSON cho Android → Android insert thẳng vào Room DB → Hiển thị Offline.
 * ===========================================================================================
 */
@RestController
@RequestMapping("/api/mobile/news")
@Tag(name = "📱 Mobile Sync (Mạnh - Room DB)", description = "API đồng bộ dữ liệu cho Android Room Database - Format khớp 100% với Entity News & AI_Analysis của bạn Mạnh")
public class MobileSyncController {

    private final AiNewsService aiNewsService;
    private final NewsCacheService newsCacheService;
    private final ObjectMapper objectMapper;

    @Autowired
    public MobileSyncController(AiNewsService aiNewsService,
                                NewsCacheService newsCacheService,
                                @Autowired(required = false) ObjectMapper objectMapper) {
        this.aiNewsService = aiNewsService;
        this.newsCacheService = newsCacheService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public MobileSyncController(AiNewsService aiNewsService, NewsCacheService newsCacheService) {
        this(aiNewsService, newsCacheService, new ObjectMapper());
    }

    /**
     * GET /api/mobile/news/sync?symbol=BTCUSDT&limit=5
     *
     * API chính để Android gọi lấy danh sách tin tức + AI phân tích.
     * Invariant: Chỉ trả về các bài tiếng Việt đạt chuẩn. Bài tiếng Anh, RAW_PENDING hoặc lỗi bị loại bỏ hoàn toàn.
     */
    @GetMapping("/sync")
    @Operation(
            summary = "Đồng bộ Tin tức + AI Analysis cho Room DB Android",
            description = "Lấy danh sách tin tức đã được dịch và phân tích tiếng Việt đạt chuẩn từ CSDL Cache. " +
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
            // Bước 1: Lấy danh sách ứng viên từ CSDL Cache qua NewsCacheService (Cache-only)
            int fetchPool = Math.max(limit * 5, 50);
            List<NewsAiCache> candidateNews;
            if (symbol != null && !symbol.isBlank()) {
                candidateNews = newsCacheService.findBySymbolOrderByPublishedAtDesc(symbol, fetchPool);
            } else {
                candidateNews = newsCacheService.findTopByOrderByPublishedAtDesc(fetchPool);
            }

            // Bước 2: Lọc NGHIÊM NGẶT chỉ lấy bài tiếng Việt đạt chuẩn (loại bỏ RAW_PENDING, QWEN_ERROR, bài tiếng Anh)
            List<NewsAiCache> qualifiedNews = candidateNews.stream()
                    .filter(this::isQualifiedForMobile)
                    .collect(Collectors.toList());

            // Bước 3: Nếu rỗng cho symbol cụ thể, fallback sang tin thị trường chung trong cache
            if (qualifiedNews.isEmpty()) {
                List<NewsAiCache> marketCandidates = newsCacheService.findTopByOrderByPublishedAtDesc(fetchPool);
                qualifiedNews = marketCandidates.stream()
                        .filter(this::isQualifiedForMobile)
                        .collect(Collectors.toList());
                if (qualifiedNews.isEmpty()) {
                    qualifiedNews = newsCacheService.findAll(fetchPool).stream()
                            .filter(this::isQualifiedForMobile)
                            .collect(Collectors.toList());
                }
            }

            // Bước 4: Cache-only invariant: Kể cả khi cache rỗng, TUYỆT ĐỐI KHÔNG gọi LLM hoặc external provider
            if (qualifiedNews.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }

            List<MobileNewsBundleResponse> result = qualifiedNews.stream()
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
                .filter(this::isQualifiedForMobile)
                .findFirst()
                .map(item -> ResponseEntity.ok(mapToMobileBundle(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/mobile/news/analysis-only?symbol=BTCUSDT&limit=10
     *
     * Chỉ trả về danh sách AI_Analysis (không kèm News), phù hợp cho
     * trường hợp chỉ muốn cập nhật bảng AI_Analysis trong Room DB.
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
                .filter(this::isQualifiedForMobile)
                .filter(item -> symbol == null || symbol.isEmpty() ||
                        (item.getSymbol() != null && item.getSymbol().equalsIgnoreCase(symbol)))
                .limit(limit)
                .map(this::mapToMobileAnalysis)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // KIỂM ĐỊNH CHẤT LƯỢNG CHO MOBILE APP: FAIL-CLOSED TIẾNG VIỆT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Kiểm tra xem một bản ghi NewsAiCache có đủ điều kiện để hiển thị trên Mobile App không.
     * Quy tắc:
     * 1. Không nhận bài đang chờ dịch (RAW_PENDING).
     * 2. Không nhận bài bị lỗi dịch (QWEN_ERROR, QWEN_FAILED).
     * 3. Bắt buộc có tiêu đề tiếng Việt chuẩn có dấu (displayTitleVi).
     * 4. Tiêu đề tiếng Việt phải vượt qua kiểm định NewsLocalizationQualityPolicy.
     * 5. Danh sách điểm chính (bullets) và tóm tắt phải là tiếng Việt đạt chuẩn.
     */
    public boolean isQualifiedForMobile(NewsAiCache entity) {
        if (entity == null) {
            return false;
        }

        String reason = entity.getReason();
        if ("RAW_PENDING".equalsIgnoreCase(reason)) {
            return false;
        }
        if (reason != null && (reason.startsWith("QWEN_ERROR") || reason.startsWith("QWEN_FAILED"))) {
            return false;
        }

        String displayTitleVi = entity.getDisplayTitleVi();
        if (displayTitleVi == null || displayTitleVi.isBlank()) {
            return false;
        }

        String origTitle = entity.getOriginalTitle();
        if (origTitle != null && !origTitle.isBlank()) {
            if (!NewsLocalizationQualityPolicy.isValidDisplayTitleVi(displayTitleVi, origTitle)) {
                return false;
            }
        } else {
            if (!NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(displayTitleVi)
                    || NewsSummaryQualityPolicy.isBoilerplate(displayTitleVi)) {
                return false;
            }
        }

        // Bắt buộc kiểm định tóm tắt tiếng Việt đạt chuẩn (fail-closed nếu thiếu hoặc là tiếng Anh)
        String displaySummaryVi = entity.getDisplaySummaryVi();
        if (displaySummaryVi == null || displaySummaryVi.isBlank()) {
            return false;
        }
        String origSummary = entity.getOriginalSummary();
        if (!NewsLocalizationQualityPolicy.isValidDisplaySummaryVi(displaySummaryVi, origSummary, displayTitleVi)) {
            return false;
        }

        String bulletsToParse = (entity.getBulletPointsVi() != null && !entity.getBulletPointsVi().isBlank())
                ? entity.getBulletPointsVi()
                : entity.getSummaryPoints();
        if (bulletsToParse != null && !bulletsToParse.isBlank()) {
            List<String> rawBullets = parseBullets(bulletsToParse);
            if (!rawBullets.isEmpty() && !NewsLocalizationQualityPolicy.isValidBullets(rawBullets, displayTitleVi)) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseBullets(String bulletsJson) {
        if (bulletsJson == null || bulletsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(bulletsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(bulletsJson.trim());
        }
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
     * Invariant: effectiveTitle TUYỆT ĐỐI là tiếng Việt (displayTitleVi), KHÔNG fallback sang tiếng Anh.
     */
    private MobileNewsDto mapToMobileNews(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        long publishedAtEpoch = entity.getPublishedAt() != null
                ? entity.getPublishedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : System.currentTimeMillis();

        String effectiveTitle = (entity.getDisplayTitleVi() != null && !entity.getDisplayTitleVi().isBlank())
                ? entity.getDisplayTitleVi().trim()
                : "";

        return new MobileNewsDto(
                newsId,
                effectiveTitle,
                entity.getArticleUrl(),
                publishedAtEpoch
        );
    }

    /**
     * Map: NEWS_AI_CACHE → Room Entity "AI_Analysis"
     * Invariant: effectiveSummary TUYỆT ĐỐI là tiếng Việt (displaySummaryVi), KHÔNG fallback sang tiếng Anh.
     */
    private MobileAiAnalysisDto mapToMobileAnalysis(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        int confidence = entity.getConfidencePct() != null
                ? entity.getConfidencePct().intValue()
                : 80;

        String effectiveSummary = (entity.getDisplaySummaryVi() != null && !entity.getDisplaySummaryVi().isBlank())
                ? entity.getDisplaySummaryVi().trim()
                : "";

        return new MobileAiAnalysisDto(
                newsId,
                effectiveSummary,
                entity.getSentiment() != null ? entity.getSentiment() : "NEUTRAL",
                confidence,
                entity.getReason() != null ? entity.getReason() : ""
        );
    }
}
