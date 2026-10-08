package com.llmgateway.controller;

import com.llmgateway.dto.news.NewsAnalysisRequest;
import com.llmgateway.dto.news.NewsAnalysisResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.UnauthorizedException;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.service.NewsPublisherResolver;
import com.llmgateway.util.JwtUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsAiController {

    private static final Logger log = LoggerFactory.getLogger(NewsAiController.class);

    private final AiNewsService aiNewsService;
    private final ContentRefreshQuotaService quotaService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public NewsAiController(AiNewsService aiNewsService) {
        this(aiNewsService, null, null, null);
    }

    public NewsAiController(AiNewsService aiNewsService,
                            ContentRefreshQuotaService quotaService,
                            JwtUtil jwtUtil) {
        this(aiNewsService, quotaService, jwtUtil, null);
    }

    @Autowired
    public NewsAiController(AiNewsService aiNewsService,
                            ContentRefreshQuotaService quotaService,
                            JwtUtil jwtUtil,
                            UserRepository userRepository) {
        this.aiNewsService = aiNewsService;
        this.quotaService = quotaService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /**
     * GET /api/news/feed?symbol=BTCUSDT&limit=5
     */
    @GetMapping("/feed")
    public ResponseEntity<?> getLiveAiNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
        if (!AiNewsService.isValidLimit(limit)) {
            Map<String, Object> errResp = new HashMap<>();
            errResp.put("status", "error");
            errResp.put("message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT);
            errResp.put("data", Collections.emptyList());
            return ResponseEntity.badRequest().body(errResp);
        }
        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(symbol, limit);
        if (limit > 0 && feed.size() > limit) {
            feed = feed.subList(0, limit);
        }
        return ResponseEntity.ok(feed);
    }

    /**
     * GET /api/news/sync?limit=5
     * Endpoint cho Android APK (tải thông thường, ưu tiên cache, không trừ hạn mức)
     */
    @GetMapping("/sync")
    public ResponseEntity<Map<String, Object>> getSyncNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
        if (!AiNewsService.isValidLimit(limit)) {
            Map<String, Object> errResp = new HashMap<>();
            errResp.put("status", "error");
            errResp.put("message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT);
            errResp.put("data", Collections.emptyList());
            return ResponseEntity.badRequest().body(errResp);
        }

        NewsSyncResult syncResult = aiNewsService.getLiveAiNewsSyncResult(symbol, limit);
        if (!"ok".equals(syncResult.getStatus())) {
            Map<String, Object> statusResp = new HashMap<>();
            statusResp.put("status", syncResult.getStatus());
            statusResp.put("message", syncResult.getMessage());
            statusResp.put("stale", syncResult.isStale());
            statusResp.put("fromCache", syncResult.isFromCache());
            statusResp.put("dataAsOf", syncResult.getDataAsOf());
            statusResp.put("latestPublishedAt", syncResult.getLatestPublishedAt());
            List<NewsFeedItemDto> items = syncResult.getItems();
            if (items != null && !items.isEmpty()) {
                statusResp.put("data", formatFeedItems(items, limit));
            } else {
                statusResp.put("data", Collections.emptyList());
            }
            return ResponseEntity.ok(statusResp);
        }

        List<NewsFeedItemDto> feed = syncResult.getItems();
        if (feed == null || feed.isEmpty()) {
            Map<String, Object> emptyResp = new HashMap<>();
            emptyResp.put("status", "empty");
            emptyResp.put("message", "Chưa có bản tin mới");
            emptyResp.put("stale", syncResult.isStale());
            emptyResp.put("fromCache", syncResult.isFromCache());
            emptyResp.put("dataAsOf", syncResult.getDataAsOf());
            emptyResp.put("latestPublishedAt", syncResult.getLatestPublishedAt());
            emptyResp.put("data", Collections.emptyList());
            return ResponseEntity.ok(emptyResp);
        }

        List<Map<String, Object>> data = formatFeedItems(feed, limit);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        if (syncResult.getMessage() != null) {
            response.put("message", syncResult.getMessage());
        }
        response.put("stale", syncResult.isStale());
        response.put("fromCache", syncResult.isFromCache());
        response.put("dataAsOf", syncResult.getDataAsOf());
        response.put("latestPublishedAt", syncResult.getLatestPublishedAt());
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/news/refresh
     * Làm mới thủ công tin tức (Pull-to-refresh) có kiểm soát hạn mức dùng chung.
     * Yêu cầu JWT hợp lệ và Client-Request-ID duy nhất.
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshNews(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "Client-Request-ID", required = false) String headerRequestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = extractUserId(authHeader);

        String clientRequestId = headerRequestId;
        if (clientRequestId == null || clientRequestId.isBlank()) {
            clientRequestId = idempotencyKey;
        }
        if ((clientRequestId == null || clientRequestId.isBlank()) && body != null && body.containsKey("clientRequestId")) {
            clientRequestId = String.valueOf(body.get("clientRequestId"));
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("Thiếu Header Client-Request-ID hoặc Idempotency-Key cho thao tác làm mới!");
        }

        if (body != null) {
            if (symbol == null && body.containsKey("symbol")) {
                symbol = String.valueOf(body.get("symbol"));
            }
            if (body.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(String.valueOf(body.get("limit")));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (!AiNewsService.isValidLimit(limit)) {
            limit = 5;
        }

        if (quotaService == null) {
            throw new IllegalStateException("ContentRefreshQuotaService chưa được cấu hình");
        }

        // Nếu hệ thống đang chạy chế độ Cache-only (tin tức do worker chuẩn bị sẵn),
        // thao tác Refresh trên app chỉ đọc cache và TUYỆT ĐỐI KHÔNG trừ quota của người dùng!
        if (aiNewsService.isCacheOnlyApi()) {
            RefreshQuotaDto quota = quotaService.getQuotaStatus(userId);
            NewsSyncResult syncResult = aiNewsService.getCacheOnlyNewsSyncResult(symbol, limit);
            Map<String, Object> response = new HashMap<>();
            response.put("maxDailyRefreshes", quota.getMaxDailyRefreshes());
            response.put("usedRefreshes", quota.getUsedRefreshes());
            response.put("remainingRefreshes", quota.getRemainingRefreshes());
            response.put("quotaDate", quota.getQuotaDate());
            response.put("stale", syncResult.isStale());
            response.put("fromCache", syncResult.isFromCache());
            response.put("dataAsOf", syncResult.getDataAsOf());
            response.put("latestPublishedAt", syncResult.getLatestPublishedAt());

            if (!"ok".equals(syncResult.getStatus())) {
                response.put("status", syncResult.getStatus());
                response.put("message", syncResult.getMessage());
                List<NewsFeedItemDto> items = syncResult.getItems();
                if (items != null && !items.isEmpty()) {
                    response.put("data", formatFeedItems(items, limit));
                } else {
                    response.put("data", Collections.emptyList());
                }
                return ResponseEntity.ok(response);
            }

            List<NewsFeedItemDto> feed = syncResult.getItems();
            if (feed == null || feed.isEmpty()) {
                response.put("status", "empty");
                response.put("message", "Chưa có bản tin mới");
                response.put("data", Collections.emptyList());
                return ResponseEntity.ok(response);
            }

            List<Map<String, Object>> data = formatFeedItems(feed, limit);
            response.put("status", "ok");
            if (syncResult.getMessage() != null) {
                response.put("message", syncResult.getMessage());
            }
            response.put("data", data);
            return ResponseEntity.ok(response);
        }

        // 1. Trừ lượt hạn mức dùng chung (Idempotent & Concurrency-safe)
        RefreshQuotaDto quota = quotaService.acquireRefreshQuota(userId, clientRequestId, "NEWS");

        // 2. Replay cùng Client-Request-ID: Tuyệt đối không gọi lại News provider
        if (quota.isReplay()) {
            log.info("REPLAY NEWS REQUEST | userId={} | clientRequestId={} -> Trả kết quả từ cache, không gọi News provider",
                    userId, clientRequestId);
            List<NewsFeedItemDto> cachedItems = aiNewsService.getValidLocalizedCacheItems(symbol, limit);
            Map<String, Object> response = new HashMap<>();
            response.put("maxDailyRefreshes", quota.getMaxDailyRefreshes());
            response.put("usedRefreshes", quota.getUsedRefreshes());
            response.put("remainingRefreshes", quota.getRemainingRefreshes());
            response.put("quotaDate", quota.getQuotaDate());
            response.put("stale", true);
            response.put("fromCache", true);
            String latestPub = (cachedItems != null && !cachedItems.isEmpty() && cachedItems.get(0).getTimePublished() != null)
                    ? cachedItems.get(0).getTimePublished() : null;
            String dataAsOf = (cachedItems != null && !cachedItems.isEmpty() && cachedItems.get(0).getAnalyzedAt() != null)
                    ? cachedItems.get(0).getAnalyzedAt() : null;
            response.put("dataAsOf", dataAsOf);
            response.put("latestPublishedAt", latestPub);
            if (cachedItems != null && !cachedItems.isEmpty()) {
                response.put("status", "ok");
                response.put("message", "Đang hiển thị tin đã lưu gần nhất");
                response.put("data", formatFeedItems(cachedItems, limit));
            } else {
                response.put("status", "empty");
                response.put("message", "Chưa có bản tin mới");
                response.put("data", Collections.emptyList());
            }
            return ResponseEntity.ok(response);
        }

        // 3. Thực hiện làm mới tin tức qua aiNewsService
        try {
            NewsSyncResult syncResult = aiNewsService.getLiveAiNewsSyncResult(symbol, limit, true);
            Map<String, Object> response = new HashMap<>();
            response.put("maxDailyRefreshes", quota.getMaxDailyRefreshes());
            response.put("usedRefreshes", quota.getUsedRefreshes());
            response.put("remainingRefreshes", quota.getRemainingRefreshes());
            response.put("quotaDate", quota.getQuotaDate());
            response.put("stale", syncResult.isStale());
            response.put("fromCache", syncResult.isFromCache());
            response.put("dataAsOf", syncResult.getDataAsOf());
            response.put("latestPublishedAt", syncResult.getLatestPublishedAt());

            if (!"ok".equals(syncResult.getStatus())) {
                response.put("status", syncResult.getStatus());
                response.put("message", syncResult.getMessage());
                List<NewsFeedItemDto> items = syncResult.getItems();
                if (items != null && !items.isEmpty()) {
                    response.put("data", formatFeedItems(items, limit));
                } else {
                    response.put("data", Collections.emptyList());
                }
                return ResponseEntity.ok(response);
            }

            List<NewsFeedItemDto> feed = syncResult.getItems();
            if (feed == null || feed.isEmpty()) {
                response.put("status", "empty");
                response.put("message", "Chưa có bản tin mới");
                response.put("data", Collections.emptyList());
                return ResponseEntity.ok(response);
            }

            List<Map<String, Object>> data = formatFeedItems(feed, limit);
            response.put("status", "ok");
            if (syncResult.getMessage() != null) {
                response.put("message", syncResult.getMessage());
            }
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.warn("Lỗi provider khi làm mới News sau khi đã trừ lượt: {}", ex.getMessage());
            Map<String, Object> errResp = new HashMap<>();
            errResp.put("status", "degraded");
            errResp.put("message", "Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
            errResp.put("stale", true);
            errResp.put("fromCache", true);
            errResp.put("data", Collections.emptyList());
            errResp.put("maxDailyRefreshes", quota.getMaxDailyRefreshes());
            errResp.put("usedRefreshes", quota.getUsedRefreshes());
            errResp.put("remainingRefreshes", quota.getRemainingRefreshes());
            errResp.put("quotaDate", quota.getQuotaDate());
            return ResponseEntity.ok(errResp);
        }
    }

    /**
     * GET /api/news/diagnostics
     * Xác minh an toàn hệ thống tin tức & pipeline (Yêu cầu quyền ADMIN để bảo vệ thông tin hạ tầng)
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<?> getDiagnostics(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "ERROR", "message", "Authorization token is missing or malformed"));
        }
        String token = authHeader.substring(7).trim();
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (jwtUtil == null || !jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "ERROR", "message", "Invalid or expired JWT token"));
        }
        String email = jwtUtil.getEmailFromToken(token);
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "ERROR", "message", "Invalid token claims"));
        }
        if (userRepository != null) {
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            }
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "ERROR", "message", "User associated with token not found"));
            }
            if (user.getRole() != UserRole.ADMIN) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("status", "ERROR", "message", "Access Denied: You do not have ADMIN privileges"));
            }
        }
        return ResponseEntity.ok(aiNewsService.getDiagnostics());
    }

    /**
     * POST /api/news/analyze
     */
    @PostMapping("/analyze")
    public ResponseEntity<NewsAnalysisResponse> analyzeNews(@Valid @RequestBody NewsAnalysisRequest request) {
        NewsAnalysisResponse response = aiNewsService.analyzeNews(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/news/cache
     */
    @GetMapping("/cache")
    public ResponseEntity<List<NewsAiCache>> getCachedNews() {
        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();
        return ResponseEntity.ok(cachedNews);
    }

    private List<Map<String, Object>> formatFeedItems(List<NewsFeedItemDto> feed, int limit) {
        List<Map<String, Object>> data = new ArrayList<>();
        int idCounter = 1;
        for (NewsFeedItemDto item : feed) {
            Map<String, Object> map = new HashMap<>();
            String idVal = (item.getUrl() != null && !item.getUrl().isBlank())
                    ? item.getUrl()
                    : String.valueOf(idCounter++);
            map.put("id", idVal);
            String origTitle = (item.getOriginalTitle() != null && !item.getOriginalTitle().isBlank())
                    ? item.getOriginalTitle().trim()
                    : "";
            String displayTitleVi = (item.getDisplayTitleVi() != null && !item.getDisplayTitleVi().isBlank())
                    ? item.getDisplayTitleVi().trim()
                    : "";
            String displayTitle = !displayTitleVi.isBlank()
                    ? displayTitleVi
                    : (item.getTitle() != null && !item.getTitle().isBlank() ? item.getTitle().trim() : origTitle);
            map.put("originalTitle", origTitle);
            map.put("displayTitleVi", displayTitleVi);
            map.put("title", displayTitle);

            String origSummary = (item.getOriginalSummary() != null && !item.getOriginalSummary().isBlank())
                    ? item.getOriginalSummary().trim()
                    : "";
            map.put("originalSummary", origSummary);

            List<String> bulletsVi = (item.getBulletPointsVi() != null && !item.getBulletPointsVi().isEmpty())
                    ? item.getBulletPointsVi()
                    : (item.getAiSummary() != null ? item.getAiSummary() : new ArrayList<>());

            String displaySummaryVi = (item.getDisplaySummaryVi() != null && !item.getDisplaySummaryVi().isBlank())
                    ? item.getDisplaySummaryVi().trim()
                    : "";
            String displaySummary = !displaySummaryVi.isBlank()
                    ? displaySummaryVi
                    : ((bulletsVi != null && !bulletsVi.isEmpty()) ? String.join(" ", bulletsVi).trim() : origSummary);

            map.put("displaySummaryVi", displaySummaryVi);
            map.put("summary", displaySummary);

            String publisher = (item.getPublisher() != null && !item.getPublisher().isBlank())
                    ? item.getPublisher().trim()
                    : NewsPublisherResolver.resolvePublisher(item.getSource(), item.getUrl());
            if (NewsPublisherResolver.isGeneric(publisher)) {
                publisher = "";
            }
            map.put("source", publisher != null ? publisher : "");
            map.put("publisher", publisher != null ? publisher : "");
            map.put("publishedAt", item.getTimePublished() != null ? item.getTimePublished() : "");
            map.put("imageUrl", item.getBannerImage() != null ? item.getBannerImage() : "");
            String sentiment = item.getAiSentiment() != null ? item.getAiSentiment().toLowerCase() : "neutral";
            map.put("sentiment", sentiment);
            map.put("confidence", item.getAiConfidence() != null ? item.getAiConfidence() : 0);
            map.put("bulletPoints", bulletsVi);
            map.put("bulletPointsVi", bulletsVi);
            map.put("author", item.getAuthor() != null ? item.getAuthor() : "");
            map.put("link", item.getUrl() != null ? item.getUrl() : "");
            data.add(map);
            if (limit > 0 && data.size() >= limit) {
                break;
            }
        }
        return data;
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new UnauthorizedException("Vui lòng đính kèm Bearer Token hợp lệ trong Header Authorization!");
        }
        String token = authHeader.trim();
        if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
            token = token.substring(7).trim();
        }
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (jwtUtil == null || !jwtUtil.validateToken(token)) {
            log.warn("Xác thực Bearer token thất bại hoặc token đã hết hạn");
            throw new UnauthorizedException("Token không hợp lệ hoặc đã hết hạn! Vui lòng đăng nhập lại để lấy token mới.");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new UnauthorizedException("Không thể xác định danh tính người dùng từ Token!");
        }
        return userId;
    }
}
