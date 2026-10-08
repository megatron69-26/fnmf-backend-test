package com.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.MobileSyncController;
import com.llmgateway.controller.NewsAiController;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class NewsWorkerAndCacheBehaviorTest {

    private NewsAiCacheRepository newsAiCacheRepository;
    private NewsCacheService newsCacheService;
    private AiNewsService aiNewsService;
    private RssNewsFetcher rssNewsFetcher;
    private QwenLocalClient qwenLocalClient;
    private NewsScheduledWorker worker;
    private ObjectMapper objectMapper;
    private ContentRefreshQuotaService quotaService;
    private JwtUtil jwtUtil;

    @BeforeEach
    public void setUp() {
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        newsCacheService = mock(NewsCacheService.class);
        rssNewsFetcher = mock(RssNewsFetcher.class);
        qwenLocalClient = mock(QwenLocalClient.class);
        objectMapper = new ObjectMapper();
        quotaService = mock(ContentRefreshQuotaService.class);
        jwtUtil = mock(JwtUtil.class);

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(aiNewsService, "cacheOnlyApi", true);
        worker = new NewsScheduledWorker(rssNewsFetcher, qwenLocalClient, newsAiCacheRepository);
        worker.setEnabled(true);
    }

    // =========================================================================
    // 1. URL NORMALIZATION & DEDUPLICATION TEST
    // =========================================================================

    @Test
    @DisplayName("1. URL Normalizer: Xóa query tracking, lowercase scheme/host, xóa trailing slash và fragment")
    public void test01_urlNormalizer_cleansTrackingAndPath() {
        String raw = "HTTPS://WWW.COINDESK.COM/markets/2026/10/08/btc-etf/?utm_source=twitter&utm_medium=social&ref=123#comments";
        String normalized = NewsUrlNormalizer.normalizeUrl(raw);
        assertEquals("https://www.coindesk.com/markets/2026/10/08/btc-etf", normalized);

        // Giữ lại query quan trọng không phải tracking
        String rawWithQuery = "https://example.com/article/?id=456&utm_campaign=spring";
        String normWithQuery = NewsUrlNormalizer.normalizeUrl(rawWithQuery);
        assertEquals("https://example.com/article?id=456", normWithQuery);
    }

    // =========================================================================
    // 2. KHÔNG CÓ BÀI MỚI (ALL DUPLICATE)
    // =========================================================================

    @Test
    @DisplayName("2. Worker: Khi tất cả bài trong RSS đã tồn tại và dịch xong -> Bỏ qua, 0 bài mới")
    public void test02_worker_noNewArticles_skipsAll() {
        RssNewsFetcher.RssArticleItem item = new RssNewsFetcher.RssArticleItem(
                "Bitcoin Hits 100K", "https://coindesk.com/btc100k", "Snippet",
                LocalDateTime.now(), "Author", "CoinDesk", "https://img.com/1.jpg"
        );
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(List.of(item));

        NewsAiCache existing = new NewsAiCache();
        existing.setId(10L);
        existing.setArticleUrl("https://coindesk.com/btc100k");
        existing.setDisplayTitleVi("Bitcoin đạt 100K USD");
        existing.setReason("PROCESSED_BY_QWEN");
        when(newsAiCacheRepository.findByArticleUrl("https://coindesk.com/btc100k")).thenReturn(Optional.of(existing));

        int processed = worker.runWorkerCycle();
        assertEquals(0, processed);
        verify(qwenLocalClient, never()).translateAndSummarize(anyString(), anyString());
    }

    // =========================================================================
    // 3. CHỐNG TRÙNG URL CHUẨN HÓA (DEDUPLICATION)
    // =========================================================================

    @Test
    @DisplayName("3. Worker: Chống trùng theo URL đã chuẩn hóa kể cả khi nguồn thêm tham số UTM")
    public void test03_worker_duplicateUrlWithUtm_deduplicated() {
        RssNewsFetcher.RssArticleItem item = new RssNewsFetcher.RssArticleItem(
                "Ethereum Upgrade", "https://coindesk.com/eth-upgrade/?utm_source=rss", "Snippet",
                LocalDateTime.now(), "Author", "CoinDesk", null
        );
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(List.of(item));

        NewsAiCache existing = new NewsAiCache();
        existing.setId(20L);
        existing.setArticleUrl("https://coindesk.com/eth-upgrade");
        existing.setDisplayTitleVi("Nâng cấp Ethereum");
        when(newsAiCacheRepository.findByArticleUrl("https://coindesk.com/eth-upgrade")).thenReturn(Optional.of(existing));

        int processed = worker.runWorkerCycle();
        assertEquals(0, processed);
        verify(qwenLocalClient, never()).translateAndSummarize(anyString(), anyString());
    }

    // =========================================================================
    // 4. QWEN LỖI / TIMEOUT / JSON HỎNG -> BẢO TOÀN BÀI GỐC (FAIL-CLOSED)
    // =========================================================================

    @Test
    @DisplayName("4. Worker: Khi Qwen timeout hoặc trả JSON lỗi -> Giữ nguyên bài gốc trong DB, không bịa nội dung")
    public void test04_worker_qwenFails_persistsRawArticleFailClosed() {
        RssNewsFetcher.RssArticleItem item = new RssNewsFetcher.RssArticleItem(
                "Solana Ecosystem Grows", "https://coindesk.com/solana-growth", "Solana transactions reached 50M daily.",
                LocalDateTime.now(), "Staff", "CoinDesk", null
        );
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(List.of(item));
        when(newsAiCacheRepository.findByArticleUrl("https://coindesk.com/solana-growth")).thenReturn(Optional.empty());

        // Mô phỏng save trả về entity có ID
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(invocation -> {
            NewsAiCache entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(101L);
            return entity;
        });

        // Qwen trả về empty (lỗi timeout hoặc JSON hỏng)
        when(qwenLocalClient.translateAndSummarize(anyString(), anyString())).thenReturn(Optional.empty());

        int processed = worker.runWorkerCycle();
        assertEquals(0, processed);

        // Xác nhận đã lưu bài gốc trước
        verify(newsAiCacheRepository, atLeastOnce()).save(argThat(entity ->
                "https://coindesk.com/solana-growth".equals(entity.getArticleUrl()) &&
                "Solana Ecosystem Grows".equals(entity.getOriginalTitle()) &&
                "Solana Ecosystem Grows".equals(entity.getTitle()) &&
                "QWEN_ERROR: FAILED_OR_INVALID".equals(entity.getReason()) &&
                entity.getDisplayTitleVi() == null
        ));
    }

    // =========================================================================
    // 5. NGUỒN TIN MẤT MẠNG -> XỬ LÝ AN TOÀN, FAIL-CLOSED
    // =========================================================================

    @Test
    @DisplayName("5. Worker: Nguồn tin RSS mất mạng (HTTP 503 / IOException) -> Xử lý êm, không sập")
    public void test05_worker_sourceNetworkError_handledSafely() {
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(Collections.emptyList());

        int processed = worker.runWorkerCycle();
        assertEquals(0, processed);
        verify(newsAiCacheRepository, never()).save(any());
    }

    // =========================================================================
    // 6. CACHE RỖNG: API TRẢ EMPTY CACHE-ONLY, KHÔNG GỌI LLM
    // =========================================================================

    @Test
    @DisplayName("6. Cache-Only: Khi CSDL rỗng, cả /api/news/sync và /api/mobile/news/sync đều trả rỗng, không gọi LLM")
    public void test06_cacheEmpty_returnsEmptyWithoutCallingLlm() {
        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(Collections.emptyList());
        when(newsCacheService.findAll(anyInt())).thenReturn(Collections.emptyList());

        // 1. Test AiNewsService.getCacheOnlyNewsSyncResult
        NewsSyncResult syncRes = aiNewsService.getCacheOnlyNewsSyncResult("BTCUSDT", 5);
        assertEquals("empty", syncRes.getStatus());
        assertFalse(syncRes.isFromCache());
        assertTrue(syncRes.getItems().isEmpty());

        // 2. Test MobileSyncController
        MobileSyncController mobileController = new MobileSyncController(aiNewsService, newsCacheService);
        ResponseEntity<?> mobileRes = mobileController.syncNewsForMobile("BTCUSDT", 5);
        assertTrue(mobileRes.getStatusCode().is2xxSuccessful());
        assertNotNull(mobileRes.getBody());
        assertTrue(((List<?>) mobileRes.getBody()).isEmpty());

        // 3. Test NewsAiController
        NewsAiController newsController = new NewsAiController(aiNewsService, quotaService, jwtUtil);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        ResponseEntity<Map<String, Object>> newsSyncRes = newsController.getSyncNewsFeed("BTCUSDT", 5);
        assertEquals("empty", newsSyncRes.getBody().get("status"));
        assertFalse((Boolean) newsSyncRes.getBody().get("fromCache"));
        assertEquals(0, ((List<?>) newsSyncRes.getBody().get("data")).size());
    }

    // =========================================================================
    // 7. HAI LƯỢT WORKER CHẠY CHỒNG NHAU (CONCURRENCY GUARD)
    // =========================================================================

    @Test
    @DisplayName("7. Concurrency: Hai lượt worker chạy chồng nhau -> Lượt sau bị chặn bởi AtomicBoolean")
    public void test07_worker_concurrencyLock_blocksSecondRun() {
        // Mô phỏng lượt chạy đầu tiên đang giữ lock
        worker.runWorkerCycle(); // Chạy bình thường
        assertFalse(worker.isRunning());

        // Test thủ công trạng thái lock khi isRunning = true
        java.util.concurrent.atomic.AtomicReference<NewsScheduledWorker> ref = new java.util.concurrent.atomic.AtomicReference<>();
        RssNewsFetcher slowFetcher = mock(RssNewsFetcher.class);
        NewsScheduledWorker testWorker = new NewsScheduledWorker(slowFetcher, qwenLocalClient, newsAiCacheRepository);
        testWorker.setEnabled(true);
        ref.set(testWorker);

        when(slowFetcher.fetchRssFeed(anyString())).thenAnswer(invocation -> {
            assertTrue(ref.get().isRunning(), "Trong lúc fetch RSS, worker phải đang giữ running lock");
            // Gọi lượt thứ 2 song song trong khi lượt 1 đang chạy
            int secondRun = ref.get().runWorkerCycle();
            assertEquals(0, secondRun, "Lượt chạy thứ hai phải bị từ chối và trả về 0 ngay lập tức");
            return Collections.emptyList();
        });

        testWorker.runWorkerCycle();
        assertFalse(testWorker.isRunning());
    }

    // =========================================================================
    // 8. APP REFRESH KHÔNG KÍCH HOẠT LLM (CACHE-ONLY REFRESH)
    // =========================================================================

    @Test
    @DisplayName("8. App Refresh: Khi cache-only, Refresh đọc cache và KHÔNG tốn quota (acquireRefreshQuota không bị gọi)")
    public void test08_appRefresh_doesNotTriggerLlmAndDoesNotConsumeQuota() {
        NewsAiCache cached = new NewsAiCache();
        cached.setId(50L);
        cached.setArticleUrl("https://example.com/news1");
        cached.setTitle("Bitcoin ổn định mức cao");
        cached.setDisplayTitleVi("Bitcoin ổn định mức cao");
        cached.setOriginalTitle("Bitcoin holds high levels");
        cached.setOriginalSummary("BTC remains above resistance.");
        cached.setDisplaySummaryVi("Bitcoin tiếp tục giữ vững trên ngưỡng hỗ trợ quan trọng.");
        cached.setBulletPointsVi("[\"Giá Bitcoin dao động hẹp.\", \"Thanh khoản thị trường duy trì ổn định.\"]");
        cached.setSource("CoinDesk");
        cached.setPublishedAt(LocalDateTime.now());
        cached.setAnalyzedAt(LocalDateTime.now());

        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cached));

        RefreshQuotaDto quotaStatus = new RefreshQuotaDto(5, 0, 5, "2026-10-08", false);
        when(quotaService.getQuotaStatus(8888L)).thenReturn(quotaStatus);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(8888L);

        NewsAiController newsController = new NewsAiController(aiNewsService, quotaService, jwtUtil);
        ResponseEntity<Map<String, Object>> resp = newsController.refreshNews(
                "Bearer test-jwt-token", "req-12345", null, "BTCUSDT", 5, null
        );

        assertEquals(200, resp.getStatusCode().value());
        assertTrue((Boolean) resp.getBody().get("fromCache"));
        assertEquals("ok", resp.getBody().get("status"));
        assertEquals(0, resp.getBody().get("usedRefreshes"), "Không được trừ quota của người dùng khi chỉ đọc cache");
        assertEquals(5, resp.getBody().get("remainingRefreshes"));
        List<?> data = (List<?>) resp.getBody().get("data");
        assertEquals(1, data.size());

        // Xác nhận acquireRefreshQuota KHÔNG BAO GIỜ bị gọi
        verify(quotaService, never()).acquireRefreshQuota(any(), anyString(), anyString());
        // Xác nhận không có lời gọi nào đến Qwen hay Alpha Vantage
        verify(qwenLocalClient, never()).translateAndSummarize(anyString(), anyString());
    }

    // =========================================================================
    // 9. CHẤT LƯỢNG SỐ LIỆU & BẢO VỆ CHỐNG HALLUCINATION / KHUYẾN NGHỊ MUA BÁN
    // =========================================================================

    @Test
    @DisplayName("9. Metric Quality Policy: Từ chối khuyến nghị mua bán, từ chối nhận dịch toàn bài, từ chối bịa số liệu")
    public void test09_metricQualityPolicy_invariants() {
        String raw = "BlackRock Bitcoin ETF reached $10 billion in net inflows during 2026.";

        // 1. Phát hiện khuyến nghị mua bán
        assertTrue(NewsMetricQualityPolicy.containsFinancialAdvice("Chúng tôi khuyến nghị mua mạnh cổ phiếu này"));
        assertTrue(NewsMetricQualityPolicy.containsFinancialAdvice("Hãy mua Bitcoin ngay hôm nay"));
        assertFalse(NewsMetricQualityPolicy.containsFinancialAdvice("Quỹ ETF BlackRock ghi nhận dòng vốn kỷ lục"));

        // 2. Phát hiện tuyên bố dịch toàn bài
        assertTrue(NewsMetricQualityPolicy.claimsFullArticleTranslation("Dưới đây là toàn văn bài báo về Bitcoin"));
        assertFalse(NewsMetricQualityPolicy.claimsFullArticleTranslation("Tóm tắt các điểm đáng chú ý từ tiêu đề"));

        // 3. Số liệu nhất quán vs bịa đặt
        assertTrue(NewsMetricQualityPolicy.areMetricsConsistent(raw, "Quỹ ETF đạt 10 tỷ USD vốn trong năm 2026"));
        // Bịa số 500 tỷ USD không có trong bài gốc
        assertFalse(NewsMetricQualityPolicy.areMetricsConsistent(raw, "Quỹ ETF đạt 500 tỷ USD vốn trong năm 2026"));

        // 4. Kiểm tra tổng thể isValidQwenOutput
        assertTrue(NewsMetricQualityPolicy.isValidQwenOutput(
                "BlackRock ETF 10B", "Inflows reached 10B",
                "Quỹ ETF BlackRock đạt 10 tỷ USD", "Dòng vốn tiếp tục tăng trưởng mạnh",
                List.of("Dòng vốn ròng đạt 10 tỷ USD.", "Nhu cầu thị trường duy trì tích cực.")
        ));

        // Vi phạm vì chứa khuyến nghị mua
        assertFalse(NewsMetricQualityPolicy.isValidQwenOutput(
                "BlackRock ETF 10B", "Inflows reached 10B",
                "Quỹ ETF BlackRock đạt 10 tỷ USD", "Khuyến nghị mua ngay lập tức",
                List.of("Dòng vốn ròng đạt 10 tỷ USD.", "Hãy mua nhanh.")
        ));
    }

    // =========================================================================
    // 10. XỬ LÝ LẠI BÀI RAW_PENDING VÀ QWEN_ERROR
    // =========================================================================

    @Test
    @DisplayName("10. Worker: Cho phép xử lý lại bài RAW_PENDING và QWEN_ERROR có sẵn trong CSDL")
    public void test10_worker_reprocesses_rawPendingAndQwenError() {
        NewsAiCache rawPending = new NewsAiCache();
        rawPending.setId(101L);
        rawPending.setArticleUrl("https://coindesk.com/pending1");
        rawPending.setOriginalTitle("Pending News Title");
        rawPending.setTitle("Pending News Title");
        rawPending.setOriginalSummary("Pending News Summary");
        rawPending.setReason("RAW_PENDING");

        NewsAiCache qwenError = new NewsAiCache();
        qwenError.setId(102L);
        qwenError.setArticleUrl("https://coindesk.com/error1");
        qwenError.setOriginalTitle("Error News Title");
        qwenError.setTitle("Error News Title");
        qwenError.setOriginalSummary("Error News Summary");
        qwenError.setReason("QWEN_ERROR: FAILED_OR_INVALID");

        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findPendingOrErrorArticles()).thenReturn(List.of(rawPending, qwenError));
        when(qwenLocalClient.translateAndSummarize(anyString(), anyString()))
                .thenReturn(Optional.of(new QwenLocalClient.QwenTranslationResult(
                        "Tiêu đề tiếng Việt",
                        "Tóm tắt tiếng Việt",
                        List.of("Ý 1", "Ý 2"),
                        "[\"Ý 1\", \"Ý 2\"]",
                        "BULLISH",
                        85
                )));

        int processed = worker.runWorkerCycle();
        assertEquals(2, processed);
        assertEquals("PROCESSED_BY_QWEN", rawPending.getReason());
        assertEquals("Tiêu đề tiếng Việt", rawPending.getDisplayTitleVi());
        assertEquals("PROCESSED_BY_QWEN", qwenError.getReason());
        assertEquals("Tiêu đề tiếng Việt", qwenError.getDisplayTitleVi());
        verify(newsAiCacheRepository, atLeast(2)).save(any(NewsAiCache.class));
    }
}
