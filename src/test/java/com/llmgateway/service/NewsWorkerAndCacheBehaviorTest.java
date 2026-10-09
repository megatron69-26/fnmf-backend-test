package com.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.MobileSyncController;
import com.llmgateway.controller.NewsAiController;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.mobile.MobileNewsDto;
import com.llmgateway.dto.mobile.MobileAiAnalysisDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        worker.setSynchronousMode(true);
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
                entity.getReason() != null && entity.getReason().startsWith("QWEN_ERROR: FAILED_OR_INVALID") &&
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
        testWorker.setSynchronousMode(true);
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

    // =========================================================================
    // 11. CẤU HÌNH CHU KỲ 2 GIỜ (7,200,000 MS)
    // =========================================================================

    @Test
    @DisplayName("11. Cấu hình chu kỳ nạp tin: Mặc định 2 giờ (7,200,000 ms), hỗ trợ ghi đè qua property/env")
    public void test11_worker_intervalConfigurable_twoHoursDefault() {
        assertEquals(7200000L, worker.getIntervalMs());
        worker.setIntervalMs(3600000L); // 1 giờ
        assertEquals(3600000L, worker.getIntervalMs());
    }

    // =========================================================================
    // 12. BÀI MỚI KÍCH HOẠT QWEN NGAY SAU KHI LƯU VÀO DB (KHÔNG ĐỢI 2 GIỜ)
    // =========================================================================

    @Test
    @DisplayName("12. Immediate Trigger: Ngay khi bài mới lưu thành công vào DB, đưa vào hàng đợi và xử lý ngay")
    public void test12_articleIngested_immediatelyTriggersQwenProcessing() {
        RssNewsFetcher.RssArticleItem item = new RssNewsFetcher.RssArticleItem(
                "Bitcoin Surges to New High", "https://coindesk.com/btc-surge-immediate", "BTC broke resistance level.",
                LocalDateTime.now(), "Reporter", "CoinDesk", null
        );
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(List.of(item));
        when(newsAiCacheRepository.findByArticleUrl("https://coindesk.com/btc-surge-immediate")).thenReturn(Optional.empty());

        NewsAiCache savedEntity = new NewsAiCache();
        savedEntity.setId(555L);
        savedEntity.setArticleUrl("https://coindesk.com/btc-surge-immediate");
        savedEntity.setOriginalTitle(item.getTitle());
        savedEntity.setTitle(item.getTitle());
        savedEntity.setOriginalSummary(item.getDescription());
        savedEntity.setReason("RAW_PENDING");

        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenReturn(savedEntity);
        when(newsAiCacheRepository.findById(555L)).thenReturn(Optional.of(savedEntity));

        when(qwenLocalClient.translateAndSummarize(eq("Bitcoin Surges to New High"), eq("BTC broke resistance level.")))
                .thenReturn(Optional.of(new QwenLocalClient.QwenTranslationResult(
                        "Bitcoin tăng vọt lên mức đỉnh mới",
                        "Giá BTC đã bứt phá qua ngưỡng kháng cự quan trọng.",
                        List.of("Dòng vốn vào mạnh mẽ", "Khối lượng giao dịch tăng cao"),
                        "[\"Dòng vốn vào mạnh mẽ\", \"Khối lượng giao dịch tăng cao\"]",
                        "BULLISH",
                        90
                )));

        int processed = worker.runWorkerCycle();
        assertEquals(1, processed);

        // Xác nhận Qwen đã được gọi ngay lập tức
        verify(qwenLocalClient, times(1)).translateAndSummarize(eq("Bitcoin Surges to New High"), anyString());
        assertEquals("PROCESSED_BY_QWEN", savedEntity.getReason());
        assertEquals("Bitcoin tăng vọt lên mức đỉnh mới", savedEntity.getDisplayTitleVi());
    }

    // =========================================================================
    // 13. BÀI TRÙNG KHÔNG XỬ LÝ LẠI VÔ ÍCH
    // =========================================================================

    @Test
    @DisplayName("13. Deduplication: Bài đã có bản dịch tiếng Việt hợp lệ thì bỏ qua hoàn toàn, không gọi lại Qwen")
    public void test13_duplicateArticle_doesNotInvokeQwenAgain() {
        RssNewsFetcher.RssArticleItem item = new RssNewsFetcher.RssArticleItem(
                "Fed Interest Decision", "https://coindesk.com/fed-decision", "Fed keeps rates unchanged.",
                LocalDateTime.now(), "Analyst", "CoinDesk", null
        );
        when(rssNewsFetcher.fetchRssFeed(anyString())).thenReturn(List.of(item));

        NewsAiCache existing = new NewsAiCache();
        existing.setId(777L);
        existing.setArticleUrl("https://coindesk.com/fed-decision");
        existing.setDisplayTitleVi("Cục Dự trữ Liên bang giữ nguyên lãi suất");
        existing.setReason("PROCESSED_BY_QWEN");
        when(newsAiCacheRepository.findByArticleUrl("https://coindesk.com/fed-decision")).thenReturn(Optional.of(existing));

        int processed = worker.runWorkerCycle();
        assertEquals(0, processed);

        verify(newsAiCacheRepository, never()).save(any());
        verify(qwenLocalClient, never()).translateAndSummarize(anyString(), anyString());
    }

    // =========================================================================
    // 14. SINGLE-FLIGHT: CHỈ 1 INFERENCE TẠI MỘT THỜI ĐIỂM, CÁC BÀI XẾP HÀNG TUẦN TỰ
    // =========================================================================

    @Test
    @DisplayName("14. Single-Flight: Khi model đang bận, bài mới xếp hàng chờ và được xử lý lần lượt")
    public void test14_singleFlight_processesArticlesSequentiallyWithoutOverlap() {
        NewsAiCache article1 = new NewsAiCache();
        article1.setId(801L);
        article1.setOriginalTitle("Article 1");
        article1.setOriginalSummary("Summary 1");
        article1.setReason("RAW_PENDING");

        NewsAiCache article2 = new NewsAiCache();
        article2.setId(802L);
        article2.setOriginalTitle("Article 2");
        article2.setOriginalSummary("Summary 2");
        article2.setReason("RAW_PENDING");

        when(newsAiCacheRepository.findById(801L)).thenReturn(Optional.of(article1));
        when(newsAiCacheRepository.findById(802L)).thenReturn(Optional.of(article2));

        java.util.concurrent.atomic.AtomicInteger concurrentInferences = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger(0);

        when(qwenLocalClient.translateAndSummarize(anyString(), anyString())).thenAnswer(invocation -> {
            int current = concurrentInferences.incrementAndGet();
            maxConcurrent.updateAndGet(m -> Math.max(m, current));
            Thread.sleep(20); // Mô phỏng thời gian suy luận
            concurrentInferences.decrementAndGet();
            return Optional.of(new QwenLocalClient.QwenTranslationResult(
                    "Bản dịch tiếng Việt", "Tóm tắt tiếng Việt",
                    List.of("Điểm 1"), "[\"Điểm 1\"]", "NEUTRAL", 85
            ));
        });

        // Đưa 2 bài vào queue
        worker.enqueueArticle(801L);
        worker.enqueueArticle(802L);
        assertEquals(2, worker.getProcessingQueueSize());

        // Chạy tuần tự
        int processed = worker.processQueueSynchronously();
        assertEquals(2, processed);
        assertEquals(1, maxConcurrent.get(), "Chỉ được phép có tối đa 1 lượt suy luận Qwen tại một thời điểm (Single-Flight)");
        assertEquals(0, worker.getProcessingQueueSize());
    }

    // =========================================================================
    // 15. RETRY SAU LỖI CÓ GIỚI HẠN & BACKOFF, BÀI LỖI KHÔNG CHẶN BÀI KHÁC
    // =========================================================================

    @Test
    @DisplayName("15. Bounded Retry & Backoff: Qwen lỗi thì ghi nhận backoff, không chặn bài tiếp theo, dừng sau 3 lần")
    public void test15_boundedRetryAndBackoff_doesNotBlockOtherArticles() {
        NewsAiCache failingArticle = new NewsAiCache();
        failingArticle.setId(901L);
        failingArticle.setOriginalTitle("Failing Article");
        failingArticle.setOriginalSummary("Will cause Qwen timeout");
        failingArticle.setReason("RAW_PENDING");

        NewsAiCache healthyArticle = new NewsAiCache();
        healthyArticle.setId(902L);
        healthyArticle.setOriginalTitle("Healthy Article");
        healthyArticle.setOriginalSummary("Will succeed");
        healthyArticle.setReason("RAW_PENDING");

        when(newsAiCacheRepository.findById(901L)).thenReturn(Optional.of(failingArticle));
        when(newsAiCacheRepository.findById(902L)).thenReturn(Optional.of(healthyArticle));

        // Bài 1 thất bại (timeout/JSON), bài 2 thành công
        when(qwenLocalClient.translateAndSummarize(eq("Failing Article"), anyString())).thenReturn(Optional.empty());
        when(qwenLocalClient.translateAndSummarize(eq("Healthy Article"), anyString())).thenReturn(Optional.of(
                new QwenLocalClient.QwenTranslationResult(
                        "Bài báo thành công", "Tóm tắt thành công",
                        List.of("Ý 1"), "[\"Ý 1\"]", "NEUTRAL", 85
                )
        ));

        worker.enqueueArticle(901L);
        worker.enqueueArticle(902L);

        int processed = worker.processQueueSynchronously();
        assertEquals(1, processed, "Chỉ bài 2 xử lý thành công, bài 1 bị lỗi");

        // Bài 1 ghi nhận lỗi lần 1 kèm backoff, bài gốc không bị xóa
        assertTrue(failingArticle.getReason().startsWith("QWEN_ERROR: FAILED_OR_INVALID (retry=1/3"));
        assertEquals("Failing Article", failingArticle.getOriginalTitle());
        assertNull(failingArticle.getDisplayTitleVi());

        // Bài 2 vẫn được xử lý bình thường (không bị bài 1 chặn)
        assertEquals("PROCESSED_BY_QWEN", healthyArticle.getReason());
        assertEquals("Bài báo thành công", healthyArticle.getDisplayTitleVi());

        // Mô phỏng bài 1 thử lại đến lần thứ 3 -> chuyển sang QWEN_FAILED_EXHAUSTED
        failingArticle.setReason("QWEN_ERROR: FAILED_OR_INVALID (retry=2/3, next_retry=2026-10-09T20:00:00)");
        worker.enqueueArticle(901L);
        worker.processQueueSynchronously();

        assertTrue(failingArticle.getReason().startsWith("QWEN_FAILED_EXHAUSTED"),
                "Khi đạt max retries (3/3), bài viết phải chuyển sang trạng thái EXHAUSTED để không thử lại vô tận");
    }

    // =========================================================================
    // 16. PHỤC HỒI HÀNG ĐỢI SAU RESTART (STARTUP RECOVERY)
    // =========================================================================

    @Test
    @DisplayName("16. Startup Recovery: Khởi động lại tự động nạp các bài RAW_PENDING và QWEN_ERROR còn trong DB")
    public void test16_startupRecovery_reloadsPendingAndErrorArticlesFromDb() {
        NewsAiCache pending1 = new NewsAiCache();
        pending1.setId(1001L);
        pending1.setOriginalTitle("Pending After Restart");
        pending1.setReason("RAW_PENDING");

        NewsAiCache error1 = new NewsAiCache();
        error1.setId(1002L);
        error1.setOriginalTitle("Error After Restart");
        error1.setReason("QWEN_ERROR: FAILED_OR_INVALID (retry=1/3, next_retry=2026-10-09T10:00:00)");

        NewsAiCache exhausted = new NewsAiCache();
        exhausted.setId(1003L);
        exhausted.setOriginalTitle("Exhausted Article");
        exhausted.setReason("QWEN_FAILED_EXHAUSTED: max retries reached (3/3)");

        when(newsAiCacheRepository.findPendingOrErrorArticles()).thenReturn(List.of(pending1, error1, exhausted));

        int recovered = worker.recoverPendingArticles();
        assertEquals(2, recovered, "Chỉ phục hồi 2 bài (RAW_PENDING và QWEN_ERROR chưa hết lượt thử), bỏ qua bài EXHAUSTED");
        assertEquals(2, worker.getProcessingQueueSize());
    }

    // =========================================================================
    // 17. APP FEED CACHE-ONLY & CHỈ ĐƯA BÀI TIẾNG VIỆT ĐẠT CHUẨN
    // =========================================================================

    @Test
    @DisplayName("17. App Feed Invariant: Tuyệt đối không đưa bài tiếng Anh chưa đạt chuẩn lên feed; không bịa nội dung")
    public void test17_appFeed_onlyIncludesFullyLocalizedVietnameseArticles() {
        // Bài 1: Tiếng Việt đạt chuẩn
        NewsAiCache validVi = new NewsAiCache();
        validVi.setId(2001L);
        validVi.setArticleUrl("https://coindesk.com/valid-vi");
        validVi.setOriginalTitle("Bitcoin Breaks Record");
        validVi.setTitle("Bitcoin lập kỷ lục mới");
        validVi.setDisplayTitleVi("Bitcoin lập kỷ lục mới");
        validVi.setDisplaySummaryVi("Giá Bitcoin đã vượt đỉnh lịch sử trong phiên giao dịch hôm nay.");
        validVi.setBulletPointsVi("[\"Vốn hóa tăng trưởng mạnh mẽ.\", \"Dòng tiền từ các quỹ ETF tiếp tục đổ vào.\"]");
        validVi.setSummaryPoints("[\"Vốn hóa tăng trưởng mạnh mẽ.\", \"Dòng tiền từ các quỹ ETF tiếp tục đổ vào.\"]");
        validVi.setSentiment("BULLISH");
        validVi.setConfidencePct(BigDecimal.valueOf(95));
        validVi.setReason("PROCESSED_BY_QWEN");
        validVi.setPublishedAt(LocalDateTime.now());
        validVi.setAnalyzedAt(LocalDateTime.now());

        // Bài 2: Bài gốc tiếng Anh chưa dịch xong (RAW_PENDING)
        NewsAiCache rawEnglish = new NewsAiCache();
        rawEnglish.setId(2002L);
        rawEnglish.setArticleUrl("https://coindesk.com/raw-english");
        rawEnglish.setOriginalTitle("Ethereum ETF Inflows Double");
        rawEnglish.setTitle("Ethereum ETF Inflows Double");
        rawEnglish.setOriginalSummary("Inflows doubled over the past week.");
        rawEnglish.setDisplayTitleVi(null); // Chưa có bản dịch tiếng Việt
        rawEnglish.setDisplaySummaryVi(null);
        rawEnglish.setBulletPointsVi(null);
        rawEnglish.setReason("RAW_PENDING");
        rawEnglish.setPublishedAt(LocalDateTime.now());

        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(validVi, rawEnglish));

        NewsSyncResult result = aiNewsService.getCacheOnlyNewsSyncResult("BTCUSDT", 5);
        assertEquals("ok", result.getStatus());
        assertTrue(result.isFromCache());

        List<NewsFeedItemDto> feedItems = result.getItems();
        assertEquals(1, feedItems.size(), "Feed chỉ được phép chứa bài tiếng Việt đạt chuẩn, bài tiếng Anh bị loại bỏ");
        assertEquals("Bitcoin lập kỷ lục mới", feedItems.get(0).getTitle());
        assertEquals("Bitcoin lập kỷ lục mới", feedItems.get(0).getDisplayTitleVi());
        assertNotNull(feedItems.get(0).getDisplaySummaryVi());

        // Xác nhận bài tiếng Anh RAW_PENDING không xuất hiện trên feed
        boolean containsEnglish = feedItems.stream().anyMatch(i -> "Ethereum ETF Inflows Double".equals(i.getTitle()));
        assertFalse(containsEnglish, "Tuyệt đối không đưa bài tiếng Anh chưa dịch lên feed ứng dụng");
    }

    // =========================================================================
    // 18. API MOBILE SYNC THỰC TẾ: LOẠI BỎ RAW_PENDING & CHỈ TRẢ TIẾNG VIỆT
    // =========================================================================

    @Test
    @DisplayName("18. MobileSyncController /api/mobile/news/sync loại bỏ hoàn toàn RAW_PENDING, QWEN_ERROR và bài tiếng Anh")
    public void test18_mobileSyncApi_rejectsRawPendingAndError_servesOnlyValidVietnamese() {
        // Bài 1: RAW_PENDING với tiêu đề tiếng Anh gốc
        NewsAiCache rawPending = new NewsAiCache();
        rawPending.setId(3001L);
        rawPending.setArticleUrl("https://coindesk.com/raw-pending-news");
        rawPending.setOriginalTitle("Federal Reserve Rate Decision");
        rawPending.setTitle("Federal Reserve Rate Decision");
        rawPending.setOriginalSummary("Fed holds interest rates steady.");
        rawPending.setDisplayTitleVi(null);
        rawPending.setDisplaySummaryVi(null);
        rawPending.setReason("RAW_PENDING");
        rawPending.setPublishedAt(LocalDateTime.now());

        // Bài 2: QWEN_ERROR
        NewsAiCache errorNews = new NewsAiCache();
        errorNews.setId(3002L);
        errorNews.setArticleUrl("https://coindesk.com/error-news");
        errorNews.setOriginalTitle("Market Volatility Spikes");
        errorNews.setTitle("Market Volatility Spikes");
        errorNews.setDisplayTitleVi(null);
        errorNews.setReason("QWEN_ERROR: timeout");
        errorNews.setPublishedAt(LocalDateTime.now());

        // Bài 3: Tiếng Việt đạt chuẩn hoàn tất
        NewsAiCache validVi = new NewsAiCache();
        validVi.setId(3003L);
        validVi.setArticleUrl("https://coindesk.com/valid-vi-news");
        validVi.setOriginalTitle("Binance Expands Global Support");
        validVi.setTitle("Binance mở rộng hỗ trợ toàn cầu");
        validVi.setDisplayTitleVi("Binance mở rộng hỗ trợ toàn cầu");
        validVi.setDisplaySummaryVi("Sàn giao dịch Binance công bố bổ sung hàng loạt dịch vụ thanh toán mới.");
        validVi.setBulletPointsVi("[\"Bổ sung 5 phương thức nạp tiền.\", \"Tối ưu hóa phí giao dịch cho người dùng.\"]");
        validVi.setSummaryPoints("[\"Bổ sung 5 phương thức nạp tiền.\", \"Tối ưu hóa phí giao dịch cho người dùng.\"]");
        validVi.setSentiment("BULLISH");
        validVi.setConfidencePct(BigDecimal.valueOf(90));
        validVi.setReason("PROCESSED_BY_QWEN");
        validVi.setPublishedAt(LocalDateTime.now());

        when(newsCacheService.findBySymbolOrderByPublishedAtDesc(anyString(), anyInt()))
                .thenReturn(List.of(rawPending, errorNews, validVi));
        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt()))
                .thenReturn(List.of(rawPending, errorNews, validVi));

        MobileSyncController controller = new MobileSyncController(aiNewsService, newsCacheService, objectMapper);

        ResponseEntity<?> response = controller.syncNewsForMobile("BTCUSDT", 5);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        List<MobileNewsBundleResponse> bundles = (List<MobileNewsBundleResponse>) response.getBody();

        // 1. Chỉ duy nhất bài tiếng Việt đạt chuẩn được trả về
        assertEquals(1, bundles.size(), "API Mobile Sync chỉ được phép trả về bài tiếng Việt đạt chuẩn");
        MobileNewsBundleResponse bundle = bundles.get(0);

        // 2. Tiêu đề và tóm tắt phải là tiếng Việt, tuyệt đối không có tiếng Anh
        assertEquals("NEWS_3003", bundle.getNews().getNewsId());
        assertEquals("Binance mở rộng hỗ trợ toàn cầu", bundle.getNews().getTitle());
        assertEquals("Sàn giao dịch Binance công bố bổ sung hàng loạt dịch vụ thanh toán mới.", bundle.getAiAnalysis().getSummary());
        assertEquals("BULLISH", bundle.getAiAnalysis().getSentiment());

        // 3. Kiểm tra getNewsById cho bài RAW_PENDING -> Phải trả về 404 Not Found (chưa sẵn sàng phục vụ app)
        when(aiNewsService.getAllCachedNews()).thenReturn(List.of(rawPending, validVi));
        ResponseEntity<MobileNewsBundleResponse> rawPendingResponse = controller.getNewsById("NEWS_3001");
        assertEquals(404, rawPendingResponse.getStatusCode().value(),
                "Bài RAW_PENDING chưa sẵn sàng tuyệt đối không được trả qua API detail của app");
    }

    // =========================================================================
    // 19. ĐÁNH THỨC RETRY ĐÚNG THỜI ĐIỂM BACKOFF (KHÔNG ĐỢI 2 GIỜ)
    // =========================================================================

    @Test
    @DisplayName("19. Retry Wakeup: Bài lỗi được đánh thức đúng mốc backoff, không đợi lượt quét 2 giờ sau")
    public void test19_retryWakeup_triggersAtExactBackoffTimeWithoutWaitingTwoHours() {
        NewsAiCache dueErrorArticle = new NewsAiCache();
        dueErrorArticle.setId(4001L);
        dueErrorArticle.setOriginalTitle("Due Error Article");
        dueErrorArticle.setOriginalSummary("Failed earlier, ready for retry");
        // Giả lập mốc next_retry đã quá hạn 1 phút so với hiện tại
        LocalDateTime pastRetryTime = LocalDateTime.now().minusMinutes(1);
        dueErrorArticle.setReason(String.format("QWEN_ERROR: FAILED_OR_INVALID (retry=1/3, next_retry=%s)", pastRetryTime));

        NewsAiCache waitingErrorArticle = new NewsAiCache();
        waitingErrorArticle.setId(4002L);
        waitingErrorArticle.setOriginalTitle("Waiting Error Article");
        waitingErrorArticle.setOriginalSummary("Still in backoff period");
        // Giả lập mốc next_retry còn 10 phút nữa mới đến hạn
        LocalDateTime futureRetryTime = LocalDateTime.now().plusMinutes(10);
        waitingErrorArticle.setReason(String.format("QWEN_ERROR: FAILED_OR_INVALID (retry=1/3, next_retry=%s)", futureRetryTime));

        when(newsAiCacheRepository.findPendingOrErrorArticles())
                .thenReturn(List.of(dueErrorArticle, waitingErrorArticle));
        when(newsAiCacheRepository.findById(4001L)).thenReturn(Optional.of(dueErrorArticle));
        when(qwenLocalClient.translateAndSummarize(eq("Due Error Article"), anyString())).thenReturn(Optional.of(
                new QwenLocalClient.QwenTranslationResult(
                        "Bài lỗi đã dịch thành công", "Tóm tắt thử lại",
                        List.of("Ý 1"), "[\"Ý 1\"]", "BULLISH", 85
                )
        ));

        // Gọi phương thức quét đánh thức retry (mô phỏng scheduler 60s hoặc lịch đánh thức)
        int wokenCount = worker.triggerDueRetries();

        assertEquals(1, wokenCount, "Chỉ đánh thức bài đã đến hạn (4001L), bài chưa đến hạn (4002L) phải giữ nguyên");
        assertEquals("PROCESSED_BY_QWEN", dueErrorArticle.getReason());
        assertEquals("Bài lỗi đã dịch thành công", dueErrorArticle.getDisplayTitleVi());
    }

    // =========================================================================
    // 20. KHÔNG COI REASONING_CONTENT LÀ JSON ĐÁP ÁN KHI CONTENT RỖNG
    // =========================================================================

    @Test
    @DisplayName("20. Qwen Client: Không chấp nhận reasoning_content làm đáp án khi content rỗng; coi là lỗi để retry")
    public void test20_qwenClient_rejectsReasoningContentAsAnswer() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Object> mockResponse = mock(HttpResponse.class);

        // Giả lập phản hồi llama-server: content rỗng, reasoning_content có nội dung suy luận
        String responseBody = """
            {
              "choices": [
                {
                  "message": {
                    "content": "",
                    "reasoning_content": "Tôi đang suy nghĩ về tình hình thị trường Bitcoin..."
                  }
                }
              ]
            }
            """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseBody);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        QwenInferenceCoordinator coordinator = new QwenInferenceCoordinator();
        QwenLocalClient localClient = new QwenLocalClient(objectMapper, mockHttpClient, coordinator);

        Optional<QwenLocalClient.QwenTranslationResult> result =
                localClient.translateAndSummarize("Bitcoin News", "Short summary");

        // BẮT BUỘC coi là lỗi (Optional.empty) để kích hoạt retry, TUYỆT ĐỐI không đọc reasoning_content
        assertTrue(result.isEmpty(),
                "Khi content rỗng, client tuyệt đối không được đọc reasoning_content làm JSON đáp án");

        // Kiểm tra tương tự với QwenForecastClient
        QwenForecastClient forecastClient = new QwenForecastClient(objectMapper, mockHttpClient, coordinator);
        com.llmgateway.dto.market.MarketPriceDto price = new com.llmgateway.dto.market.MarketPriceDto();
        price.setSymbol("BTCUSDT");
        price.setPrice(BigDecimal.valueOf(90000.0));

        assertThrows(com.llmgateway.exception.ForecastUnavailableException.class, () -> {
            forecastClient.requestMarketForecast(List.of(price), Collections.emptyList(), Collections.emptyList(), "24H_7D");
        }, "QwenForecastClient phải ném ForecastUnavailableException khi content rỗng, không coi reasoning_content là nhận định");
    }

    // =========================================================================
    // 21. ĐIỀU PHỐI SINGLE-FLIGHT CHUNG GIỮA NEWS VÀ FORECAST CHO QWEN 4B
    // =========================================================================

    @Test
    @DisplayName("21. QwenInferenceCoordinator: Đảm bảo News và Forecast loại trừ lẫn nhau, không gọi LLM đồng thời")
    public void test21_qwenInferenceCoordinator_coordinatesSharedSingleFlight() throws Exception {
        QwenInferenceCoordinator coordinator = new QwenInferenceCoordinator();

        CountDownLatch newsLockHeldLatch = new CountDownLatch(1);
        CountDownLatch forecastAttemptDoneLatch = new CountDownLatch(1);
        AtomicBoolean forecastFailedDueToBusy = new AtomicBoolean(false);

        // Luồng 1 (News): Chiếm khóa suy luận Qwen trong 300ms
        Thread newsThread = new Thread(() -> {
            try {
                coordinator.executeWithLock("NEWS_THREAD", Duration.ofSeconds(5), () -> {
                    newsLockHeldLatch.countDown();
                    Thread.sleep(300); // Giả lập đang gọi llama-server
                    return "NEWS_DONE";
                });
            } catch (Exception e) {
                fail("News task should succeed");
            }
        });

        // Luồng 2 (Forecast): Cố gắng gọi Qwen khi News đang giữ khóa với timeout rất ngắn (50ms)
        Thread forecastThread = new Thread(() -> {
            try {
                newsLockHeldLatch.await(); // Đợi News đã lấy khóa
                coordinator.executeWithLock("FORECAST_THREAD", Duration.ofMillis(50), () -> "FORECAST_DONE");
            } catch (IllegalStateException e) {
                // Kỳ vọng: Bị từ chối vì News đang chạy
                forecastFailedDueToBusy.set(true);
            } catch (Exception ignored) {
            } finally {
                forecastAttemptDoneLatch.countDown();
            }
        });

        newsThread.start();
        forecastThread.start();

        newsThread.join();
        forecastThread.join();

        assertTrue(forecastFailedDueToBusy.get(),
                "Khi News đang giữ khóa Qwen, Forecast không thể chiếm khóa đồng thời mà phải đợi hoặc bị từ chối");
        assertFalse(coordinator.isLocked(), "Sau khi cả 2 kết thúc, khóa điều phối phải được giải phóng hoàn toàn");
    }

    // =========================================================================
    // 22. KIỂM ĐỊNH BẮT BUỘC TÓM TẮT TIẾNG VIỆT TRÊN MOBILE SYNC CONTROLLER
    // =========================================================================

    @Test
    @DisplayName("22. MobileSync: Bắt buộc tóm tắt tiếng Việt đạt chuẩn; loại bỏ bài có summary rỗng hoặc tiếng Anh dù có tiêu đề Việt")
    public void test22_mobileSync_requiresValidVietnameseSummary_rejectsBlankOrEnglishSummary() {
        // Bài 1: Tiêu đề Việt hợp lệ, nhưng displaySummaryVi là null hoặc rỗng
        NewsAiCache emptySummary = new NewsAiCache();
        emptySummary.setId(5001L);
        emptySummary.setArticleUrl("https://coindesk.com/empty-summary");
        emptySummary.setOriginalTitle("Solana Network Milestone");
        emptySummary.setTitle("Mạng lưới Solana đạt cột mốc mới");
        emptySummary.setDisplayTitleVi("Mạng lưới Solana đạt cột mốc mới");
        emptySummary.setOriginalSummary("Solana records highest daily active addresses.");
        emptySummary.setDisplaySummaryVi(""); // RỖNG
        emptySummary.setBulletPointsVi(""); // Không có bullets
        emptySummary.setReason("PROCESSED_BY_QWEN");
        emptySummary.setPublishedAt(LocalDateTime.now());

        // Bài 2: Tiêu đề Việt hợp lệ, nhưng displaySummaryVi là tiếng Anh (chưa dịch)
        NewsAiCache englishSummary = new NewsAiCache();
        englishSummary.setId(5002L);
        englishSummary.setArticleUrl("https://coindesk.com/english-summary");
        englishSummary.setOriginalTitle("Ethereum Gas Fees Drop");
        englishSummary.setTitle("Phí gas Ethereum giảm mạnh");
        englishSummary.setDisplayTitleVi("Phí gas Ethereum giảm mạnh");
        englishSummary.setOriginalSummary("Gas fees have dropped significantly this week.");
        englishSummary.setDisplaySummaryVi("Gas fees have dropped significantly this week."); // TIẾNG ANH
        englishSummary.setBulletPointsVi("");
        englishSummary.setReason("PROCESSED_BY_QWEN");
        englishSummary.setPublishedAt(LocalDateTime.now());

        // Bài 3: Tiêu đề Việt và tóm tắt Việt đều đạt chuẩn (bullets rỗng)
        NewsAiCache validBoth = new NewsAiCache();
        validBoth.setId(5003L);
        validBoth.setArticleUrl("https://coindesk.com/valid-both");
        validBoth.setOriginalTitle("Cardano Upgrade Goes Live");
        validBoth.setTitle("Bản nâng cấp Cardano chính thức vận hành");
        validBoth.setDisplayTitleVi("Bản nâng cấp Cardano chính thức vận hành");
        validBoth.setOriginalSummary("Cardano upgrade was successfully deployed today.");
        validBoth.setDisplaySummaryVi("Bản nâng cấp mới của Cardano đã được kích hoạt thành công trên mainnet.");
        validBoth.setBulletPointsVi("");
        validBoth.setReason("PROCESSED_BY_QWEN");
        validBoth.setPublishedAt(LocalDateTime.now());

        MobileSyncController controller = new MobileSyncController(aiNewsService, newsCacheService, objectMapper);

        // Kiểm tra trực tiếp hàm isQualifiedForMobile
        assertFalse(controller.isQualifiedForMobile(emptySummary),
                "Bài có tiêu đề Việt nhưng summary rỗng TUYỆT ĐỐI không được qua bộ lọc mobile");
        assertFalse(controller.isQualifiedForMobile(englishSummary),
                "Bài có tiêu đề Việt nhưng summary bằng tiếng Anh TUYỆT ĐỐI không được qua bộ lọc mobile");
        assertTrue(controller.isQualifiedForMobile(validBoth),
                "Bài có tiêu đề và tóm tắt tiếng Việt hợp lệ được chấp nhận");

        // Kiểm tra qua API sync
        when(newsCacheService.findBySymbolOrderByPublishedAtDesc(anyString(), anyInt()))
                .thenReturn(List.of(emptySummary, englishSummary, validBoth));
        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt()))
                .thenReturn(List.of(emptySummary, englishSummary, validBoth));

        ResponseEntity<?> response = controller.syncNewsForMobile("ADAUSDT", 5);
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<MobileNewsBundleResponse> bundles = (List<MobileNewsBundleResponse>) response.getBody();
        assertNotNull(bundles);
        assertEquals(1, bundles.size(), "Chỉ duy nhất bài có tóm tắt tiếng Việt chuẩn được trả về");
        assertEquals("NEWS_5003", bundles.get(0).getNews().getNewsId());
        assertEquals("Bản nâng cấp mới của Cardano đã được kích hoạt thành công trên mainnet.",
                bundles.get(0).getAiAnalysis().getSummary());
    }

    // =========================================================================
    // 23. TÁCH BIỆT THỜI GIAN CHỜ KHÓA NGẮN VÀ TIMEOUT SUY LUẬN TRONG FORECAST
    // =========================================================================

    @Test
    @DisplayName("23. QwenForecastClient: Chờ khóa ngắn (fail-fast) khi News đang giữ khóa, không bị treo theo timeout suy luận")
    public void test23_qwenForecastClient_failsFastOnShortLockWait_doesNotBlockOnInferenceTimeout() throws Exception {
        QwenInferenceCoordinator coordinator = new QwenInferenceCoordinator();
        HttpClient mockHttpClient = mock(HttpClient.class);

        QwenForecastClient forecastClient = new QwenForecastClient(objectMapper, mockHttpClient, coordinator);
        // Cấu hình: lock wait timeout = 1 giây, inference timeout = 240 giây
        forecastClient.setQwenLockWaitSeconds(1);
        forecastClient.setQwenTimeoutSeconds(240);

        CountDownLatch newsLockHeldLatch = new CountDownLatch(1);
        CountDownLatch newsCanReleaseLatch = new CountDownLatch(1);

        // Giả lập News Worker đang chiếm khóa và thực hiện tác vụ dịch tin lâu
        Thread newsHoldingThread = new Thread(() -> {
            try {
                coordinator.executeWithLock("NEWS_WORKER", Duration.ofSeconds(5), () -> {
                    newsLockHeldLatch.countDown(); // Báo hiệu đã giữ khóa
                    newsCanReleaseLatch.await(5, TimeUnit.SECONDS); // Giữ khóa
                    return "NEWS_FINISHED";
                });
            } catch (Exception ignored) {
            }
        });
        newsHoldingThread.start();

        // Đợi News đã chắc chắn giữ khóa
        assertTrue(newsLockHeldLatch.await(2, TimeUnit.SECONDS), "News thread phải chiếm khóa thành công");

        com.llmgateway.dto.market.MarketPriceDto price = new com.llmgateway.dto.market.MarketPriceDto();
        price.setSymbol("BTCUSDT");
        price.setPrice(BigDecimal.valueOf(95000.0));

        long startTime = System.currentTimeMillis();
        // Forecast gọi requestMarketForecast trong khi News đang giữ khóa
        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () -> {
            forecastClient.requestMarketForecast(List.of(price), Collections.emptyList(), Collections.emptyList(), "24H_7D");
        }, "Forecast phải fail-fast và ném ForecastUnavailableException khi không lấy được khóa");

        long durationMs = System.currentTimeMillis() - startTime;

        // Xác nhận thời gian chờ chỉ xấp xỉ lock wait timeout (1 giây ~ 1000ms), TUYỆT ĐỐI không chờ 240s
        assertTrue(durationMs < 3000,
                String.format("Forecast phải kết thúc nhanh (<3s) theo lock wait timeout thay vì bị treo 240s, thực tế: %d ms", durationMs));
        assertTrue(ex.getMessage().contains("Chưa thể tạo nhận định lúc này"),
                "Thông điệp thân thiện trả về cho người dùng khi Qwen bận");

        // Giải phóng khóa News
        newsCanReleaseLatch.countDown();
        newsHoldingThread.join(2000);

        // Xác nhận sau khi News nhả khóa, coordinator đã sẵn sàng
        assertFalse(coordinator.isLocked(), "Khóa điều phối phải được giải phóng hoàn toàn sau khi News kết thúc");
    }
}
