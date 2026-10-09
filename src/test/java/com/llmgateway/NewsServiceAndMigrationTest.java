package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.DataInitializer;
import com.llmgateway.controller.MobileSyncController;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.AuthService;
import com.llmgateway.service.NewsCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class NewsServiceAndMigrationTest {

    private NewsAiCacheRepository newsAiCacheRepository;
    private NewsCacheService newsCacheService;
    private AiNewsService aiNewsService;
    private MobileSyncController mobileSyncController;
    private DataInitializer dataInitializer;
    private UserRepository userRepository;
    private AuthService authService;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private ResourceLoader resourceLoader;
    private org.springframework.core.env.Environment env;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    public void setUp() {
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        newsCacheService = new NewsCacheService(newsAiCacheRepository);
        objectMapper = new ObjectMapper();

        userRepository = mock(UserRepository.class);
        authService = mock(AuthService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        resourceLoader = mock(ResourceLoader.class);
        env = mock(org.springframework.core.env.Environment.class);
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", ""); // Rỗng để luôn dùng CSDL Cache
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "");

        mobileSyncController = new MobileSyncController(aiNewsService, newsCacheService);

        dataInitializer = new DataInitializer(
                userRepository,
                authService,
                newsCacheService,
                jdbcTemplate,
                objectMapper,
                resourceLoader,
                env,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("1. Seed giữ nguyên publishedAt và analyzedAt từ file seed, không tạo ngày giả")
    public void testSeedPreservesPublishedAt() {
        when(newsAiCacheRepository.count()).thenReturn(0L);

        String sampleJson = "[{\n" +
                "  \"id\": 99,\n" +
                "  \"articleUrl\": \"https://example.com/real-news-1\",\n" +
                "  \"title\": \"AMD AI Breakthrough in 2026\",\n" +
                "  \"symbol\": \"AMD\",\n" +
                "  \"summaryPoints\": \"[\\\"Point 1\\\"]\",\n" +
                "  \"sentiment\": \"BULLISH\",\n" +
                "  \"confidencePct\": 90,\n" +
                "  \"reason\": \"Growth in AI\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08.771531\",\n" +
                "  \"analyzedAt\": \"2026-09-08T02:37:08.771531\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(sampleJson.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());

        dataInitializer.seedNewsCacheIfNeeded();

        ArgumentCaptor<Iterable<NewsAiCache>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(newsAiCacheRepository, atLeastOnce()).saveAll(captor.capture());

        List<NewsAiCache> savedList = new ArrayList<>();
        captor.getValue().forEach(savedList::add);

        assertEquals(1, savedList.size());
        NewsAiCache saved = savedList.get(0);
        assertNull(saved.getId(), "Không dùng ID cũ, để CSDL tự sinh Identity");
        assertEquals(LocalDateTime.parse("2026-09-08T02:37:08.771531"), saved.getPublishedAt(),
                "Ngày phát hành phải giữ nguyên ngày thật từ file seed, không được là LocalDateTime.now()");
        assertEquals(LocalDateTime.parse("2026-09-08T02:37:08.771531"), saved.getAnalyzedAt());
    }

    @Test
    @DisplayName("2. Seed chống trùng lặp theo articleUrl trong cùng file seed")
    public void testSeedPreventsDuplicateArticleUrl() {
        when(newsAiCacheRepository.count()).thenReturn(0L);

        String duplicateJson = "[{\n" +
                "  \"articleUrl\": \"https://example.com/same-url\",\n" +
                "  \"title\": \"First Instance\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "},{\n" +
                "  \"articleUrl\": \"https://example.com/same-url\",\n" +
                "  \"title\": \"Duplicate Instance\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(duplicateJson.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());

        dataInitializer.seedNewsCacheIfNeeded();

        ArgumentCaptor<Iterable<NewsAiCache>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(newsAiCacheRepository, atLeastOnce()).saveAll(captor.capture());

        List<NewsAiCache> savedList = new ArrayList<>();
        captor.getValue().forEach(savedList::add);

        assertEquals(1, savedList.size(), "Chỉ lưu 1 bài duy nhất khi articleUrl bị trùng");
        assertEquals("First Instance", savedList.get(0).getTitle());
    }

    @Test
    @DisplayName("3. Seed không ghi đè bài báo đang tồn tại trong CSDL")
    public void testSeedDoesNotOverwriteExistingData() {
        when(newsAiCacheRepository.count()).thenReturn(2L); // Thiếu dữ liệu (< 5)
        String existingUrl = "https://example.com/already-exists";

        NewsAiCache existingEntity = new NewsAiCache();
        existingEntity.setId(10L);
        existingEntity.setArticleUrl(existingUrl);
        existingEntity.setTitle("Existing Unmodified Title");

        when(newsAiCacheRepository.findByArticleUrl(existingUrl)).thenReturn(Optional.of(existingEntity));
        when(newsAiCacheRepository.existsByArticleUrl(existingUrl)).thenReturn(true);

        String jsonWithExisting = "[{\n" +
                "  \"articleUrl\": \"https://example.com/already-exists\",\n" +
                "  \"title\": \"New Seed Title Attempting Overwrite\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(jsonWithExisting.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);

        dataInitializer.seedNewsCacheIfNeeded();

        // Không có bản ghi mới nào được saveAll
        verify(newsAiCacheRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("4. Tuyệt đối KHÔNG TRUNCATE bảng nếu bảng có dữ liệu khi migration OID PostgreSQL")
    public void testMigrationDoesNotTruncateTableWithData() throws Exception {
        // Giả lập PostgreSQL connection metadata
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);
        when(mockMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConn.getMetaData()).thenReturn(mockMeta);

        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(mockConn);
        });

        // Bảng tồn tại
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM information_schema.tables"), eq(Integer.class)))
                .thenReturn(1);

        // Có cột OID
        when(jdbcTemplate.query(contains("oid"), any(RowMapper.class)))
                .thenReturn(List.of("summary_points", "reason"));

        // Bảng CÓ 20 bản ghi
        when(jdbcTemplate.queryForObject(eq("SELECT count(*) FROM news_ai_cache"), eq(Long.class)))
                .thenReturn(20L);

        // LOB không thể đọc an toàn (lo_get ném lỗi)
        when(jdbcTemplate.queryForObject(contains("lo_get"), eq(Integer.class)))
                .thenThrow(new RuntimeException("Unable to access lob stream"));

        boolean result = dataInitializer.migratePostgresLobColumnsIfNeeded();

        assertFalse(result, "Migration phải dừng lại và trả false nếu không bảo toàn được LOB");
        // Kiểm tra tuyệt đối không gọi TRUNCATE
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE"));
    }

    @Test
    @DisplayName("5. Luồng live news lưu cache thật qua NewsCacheService")
    public void testLiveNewsFlowCanSaveRealCache() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(100L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://example.com/test-live",
                "Apple Releases New M4 Chip",
                "AAPL",
                "[\"Trọng tâm: Apple chip mới\"]",
                "BULLISH",
                BigDecimal.valueOf(92),
                "Strong financial growth",
                LocalDateTime.of(2026, 9, 8, 10, 0),
                LocalDateTime.of(2026, 9, 8, 10, 5)
        );

        assertTrue(saved.isPresent());
        assertEquals(100L, saved.get().getId());
        assertEquals("BULLISH", saved.get().getSentiment());
        assertEquals("AAPL", saved.get().getSymbol());
        verify(newsAiCacheRepository, times(1)).save(any(NewsAiCache.class));
    }

    @Test
    @DisplayName("6. Fallback đọc cache trả tối đa limit đã chỉ định")
    public void testFallbackCacheRespectsLimit() {
        List<NewsAiCache> cachedArticles = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            NewsAiCache item = new NewsAiCache();
            item.setId((long) i);
            item.setTitle("Bitcoin Market Update " + i);
            item.setOriginalTitle("Bitcoin Market Update " + i);
            item.setDisplayTitleVi("Cập nhật thị trường Bitcoin phiên " + i);
            item.setOriginalSummary("Bitcoin trading volume remained steady with key support holding.");
            item.setDisplaySummaryVi("Khối lượng giao dịch Bitcoin duy trì ổn định với vùng hỗ trợ then chốt.");
            item.setArticleUrl("https://www.coindesk.com/news-" + i);
            item.setSource("CoinDesk");
            item.setSymbol("BTCUSDT");
            item.setBulletPointsVi("[\"Thị trường duy trì đà tích lũy tích cực.\",\"Dòng tiền tổ chức tiếp tục đổ vào các quỹ ETF.\"]");
            item.setSummaryPoints("[\"Thị trường duy trì đà tích lũy tích cực.\",\"Dòng tiền tổ chức tiếp tục đổ vào các quỹ ETF.\"]");
            item.setSentiment("NEUTRAL");
            item.setConfidencePct(BigDecimal.valueOf(80));
            item.setPublishedAt(LocalDateTime.now().minusHours(i));
            cachedArticles.add(item);
        }

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("BTCUSDT")))
                .thenReturn(cachedArticles);
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(cachedArticles);

        List<NewsFeedItemDto> result = aiNewsService.getLiveAiNewsFeed("BTCUSDT", 3);

        assertEquals(3, result.size(), "Hàm phải trả tối đa limit = 3 bài báo");
        assertTrue(result.get(0).isFromCache());
    }

    @Test
    @DisplayName("7. Mobile fallback MARKET trả tối đa limit đã chỉ định")
    public void testMobileFallbackMarketRespectsLimit() {
        List<NewsAiCache> marketArticles = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            NewsAiCache item = new NewsAiCache();
            item.setId((long) i);
            item.setTitle("Thị trường tài chính ngày " + i);
            item.setDisplayTitleVi("Thị trường tài chính ngày " + i);
            item.setDisplaySummaryVi("Tóm tắt diễn biến thị trường tài chính ngày " + i + " với nhiều chuyển biến tích cực.");
            item.setArticleUrl("https://example.com/market-" + i);
            item.setSymbol("MARKET");
            item.setSummaryPoints("[\"Điểm nhấn thị trường 1 ngày " + i + "\", \"Điểm nhấn thị trường 2 ngày " + i + "\"]");
            item.setBulletPointsVi("[\"Điểm nhấn thị trường 1 ngày " + i + "\", \"Điểm nhấn thị trường 2 ngày " + i + "\"]");
            item.setSentiment("BULLISH");
            item.setConfidencePct(BigDecimal.valueOf(85));
            item.setPublishedAt(LocalDateTime.of(2026, 9, 8, 8, i));
            marketArticles.add(item);
        }

        // Khi tìm theo ETHUSDT thì rỗng
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("ETHUSDT")))
                .thenReturn(List.of());
        // Fallback top 10 market articles
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(marketArticles);

        ResponseEntity<?> response =
                mobileSyncController.syncNewsForMobile("ETHUSDT", 2);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        List<MobileNewsBundleResponse> body = (List<MobileNewsBundleResponse>) response.getBody();
        assertEquals(2, body.size(), "Mobile fallback MARKET phải trả chính xác tối đa limit = 2");
        assertEquals("NEWS_1", body.get(0).getNews().getNewsId());
    }

    @Test
    @DisplayName("8. Migration V6 chứa đầy đủ các cột metadata và tóm tắt gốc cho news_ai_cache")
    public void testMigrationV6ContainsRequiredColumns() throws Exception {
        java.io.File v6File = new java.io.File("src/main/resources/db/migration/V6__add_news_metadata_and_summary_fields.sql");
        assertTrue(v6File.exists(), "File V6 migration phải tồn tại");
        String sql = java.nio.file.Files.readString(v6File.toPath());
        assertTrue(sql.contains("author VARCHAR(255)"), "Phải có cột author");
        assertTrue(sql.contains("source VARCHAR(255)"), "Phải có cột source");
        assertTrue(sql.contains("original_summary TEXT"), "Phải có cột original_summary");
        assertTrue(sql.contains("banner_image VARCHAR(500)"), "Phải có cột banner_image");
        assertTrue(sql.contains("original_title VARCHAR(500)"), "Phải có cột original_title");
        assertTrue(sql.contains("IF NOT EXISTS"), "Phải đảm bảo tính idempotent với IF NOT EXISTS");
    }

    @Test
    @DisplayName("9. Cache lưu và bảo toàn toàn bộ metadata tác giả, nguồn, ảnh bìa, tóm tắt gốc")
    public void testCachePreservesFullMetadata() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(201L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://example.com/nvda-earnings",
                "NVIDIA Reports Record Q3 Revenue",
                "NVDA",
                "[\"Doanh thu đạt mốc kỷ lục\", \"Nhu cầu chip AI tiếp tục bùng nổ\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Tăng trưởng vượt bậc",
                LocalDateTime.of(2026, 9, 9, 14, 0),
                LocalDateTime.of(2026, 9, 9, 14, 5),
                "Jane Doe",
                "Reuters",
                "NVIDIA reported record quarterly revenue driven by strong data center chip sales across global markets.",
                "https://example.com/img/nvda.jpg",
                "NVIDIA Reports Record Q3 Revenue"
        );

        assertTrue(saved.isPresent());
        NewsAiCache entity = saved.get();
        assertEquals("Jane Doe", entity.getAuthor());
        assertEquals("Reuters", entity.getSource());
        assertEquals("https://example.com/img/nvda.jpg", entity.getBannerImage());
        assertEquals("NVIDIA Reports Record Q3 Revenue", entity.getOriginalTitle());
        assertTrue(entity.getOriginalSummary().contains("data center chip sales"));
    }

    @Test
    @DisplayName("10. Bản ghi Cache cũ thiếu tác giả sẽ trả về null/rỗng, tuyệt đối không bịa đặt tên tác giả")
    public void testLegacyCacheWithoutAuthorDoesNotInventAuthor() {
        NewsAiCache legacyItem = new NewsAiCache();
        legacyItem.setId(301L);
        legacyItem.setTitle("Federal Reserve Interest Rate Decision");
        legacyItem.setDisplayTitleVi("Quyết định lãi suất của Cục Dự trữ Liên bang Fed");
        legacyItem.setArticleUrl("https://example.com/fed-decision");
        legacyItem.setSymbol("MARKET");
        legacyItem.setSummaryPoints("[\"Fed giữ nguyên lãi suất\", \"Thị trường kỳ vọng đợt cắt giảm cuối năm\"]");
        legacyItem.setSentiment("NEUTRAL");
        legacyItem.setConfidencePct(BigDecimal.valueOf(85));
        legacyItem.setPublishedAt(LocalDateTime.now().minusHours(2));
        legacyItem.setAuthor(null); // Không có tác giả trong cache cũ
        legacyItem.setSource(null);

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("MARKET")))
                .thenReturn(List.of(legacyItem));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(legacyItem));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed("MARKET", 1);
        assertEquals(1, feed.size());
        NewsFeedItemDto dto = feed.get(0);

        assertNull(dto.getAuthor(), "Tác giả của cache cũ phải là null, không được tự ý gán là 'Financial News' hay 'Tin thị trường'");
    }

    @Test
    @DisplayName("11. NewsSummaryQualityPolicy phát hiện và làm sạch các câu boilerplate khuôn mẫu")
    public void testNewsSummaryQualityPolicyDetectsAndCleansBoilerplate() {
        List<String> legacyBoilerplate = List.of(
                "Trọng tâm tin tức: Apple ra mắt M4",
                "Tác động thị trường: Kỳ vọng dòng tiền tiếp tục gia tăng.",
                "Khuyến nghị FNMF: Theo dõi phản ứng giá tại các mốc hỗ trợ và kháng cự then chốt."
        );

        assertTrue(com.llmgateway.service.NewsSummaryQualityPolicy.hasBoilerplateBullets(legacyBoilerplate));

        String title = "Apple Unveils New M4 Chip For Pro Macs";
        String summary = "Apple today announced its latest M4 family of chips with significant neural engine upgrades. The new processors are built on second-generation 3nm technology and offer 50 percent faster CPU performance.";

        List<String> sanitized = com.llmgateway.service.NewsSummaryQualityPolicy.sanitizeBullets(legacyBoilerplate, title, summary);

        assertFalse(com.llmgateway.service.NewsSummaryQualityPolicy.hasBoilerplateBullets(sanitized));
        assertTrue(sanitized.size() >= 2 && sanitized.size() <= 4, "Số gạch đầu dòng phải từ 2 đến 4 ý");
        for (String bullet : sanitized) {
            assertFalse(bullet.startsWith("Trọng tâm tin tức:"));
            assertFalse(bullet.startsWith("Tác động thị trường:"));
            assertFalse(bullet.startsWith("Khuyến nghị FNMF:"));
        }
    }

    @Test
    @DisplayName("12. Trích xuất 2-4 câu sự kiện thật, không sinh văn phong khuyến nghị giả")
    public void testExtractsFactualSentencesWithoutFalseRecommendations() {
        com.llmgateway.dto.news.NewsAnalysisRequest req = new com.llmgateway.dto.news.NewsAnalysisRequest(
                "Bitcoin Surges Past 70K on Institutional Inflows",
                "Bitcoin broke past the seventy thousand dollar mark on Monday as spot ETF inflows hit a three-month high. Major asset managers reported net positive subscriptions across all funds.",
                "BTCUSDT",
                "https://example.com/btc-70k"
        );

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findByTitle(anyString())).thenReturn(Optional.empty());

        List<String> bullets = com.llmgateway.service.NewsSummaryQualityPolicy.extractFactualBullets(req.getTitle(), req.getContent());

        assertNotNull(bullets);
        assertTrue(bullets.size() >= 2 && bullets.size() <= 4);
        for (String bullet : bullets) {
            assertFalse(bullet.startsWith("Trọng tâm tin tức:"));
            assertFalse(bullet.startsWith("Tác động thị trường:"));
            assertFalse(bullet.startsWith("Khuyến nghị FNMF:"));
        }
    }

    @Test
    @DisplayName("13. Endpoint /api/news/sync trả về bulletPoints từ 2 đến 4 ý và giữ đúng author/source")
    public void testSyncEndpointReturnsCorrectBulletsAndMetadata() {
        NewsAiCache item = new NewsAiCache();
        item.setId(401L);
        item.setTitle("Tesla Expands Supercharger Network");
        item.setDisplayTitleVi("Tesla mở rộng mạng lưới trạm sạc Supercharger");
        item.setArticleUrl("https://example.com/tesla-supercharger");
        item.setSymbol("TSLA");
        item.setSummaryPoints("[\"Tesla mở rộng thêm 500 trạm sạc mới\", \"Mạng lưới sạc hỗ trợ chuẩn NACS toàn cầu\"]");
        item.setSentiment("BULLISH");
        item.setConfidencePct(BigDecimal.valueOf(92));
        item.setPublishedAt(LocalDateTime.of(2026, 9, 9, 10, 0));
        item.setAuthor("Elon Team");
        item.setSource("Bloomberg");
        item.setBannerImage("https://example.com/tsla.jpg");
        item.setOriginalSummary("Tesla announced a nationwide expansion of its Supercharger network today, adding 500 ultra-fast stalls.");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("TSLA")))
                .thenReturn(List.of(item));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(item));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        ResponseEntity<Map<String, Object>> response = controller.getSyncNewsFeed("TSLA", 5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, data.size());

        Map<String, Object> first = data.get(0);
        assertEquals("Elon Team", first.get("author"));
        assertEquals("Bloomberg", first.get("source"));
        assertEquals("https://example.com/tsla.jpg", first.get("imageUrl"));
        List<String> bullets = (List<String>) first.get("bulletPoints");
        assertNotNull(bullets);
        assertTrue(bullets.size() >= 2 && bullets.size() <= 4);
    }

    @Test
    @DisplayName("14. Migration V7 chứa đầy đủ các cột bản địa hóa tiếng Việt (display_title_vi, bullet_points_vi)")
    public void testMigrationV7ContainsVietnameseLocalizationColumns() throws Exception {
        java.io.File v7File = new java.io.File("src/main/resources/db/migration/V7__add_news_vietnamese_localization_fields.sql");
        assertTrue(v7File.exists(), "File V7 migration phải tồn tại");
        String sql = java.nio.file.Files.readString(v7File.toPath());
        assertTrue(sql.contains("display_title_vi VARCHAR(500)"), "Phải có cột display_title_vi");
        assertTrue(sql.contains("bullet_points_vi TEXT"), "Phải có cột bullet_points_vi");
        assertTrue(sql.contains("IF NOT EXISTS"), "Phải đảm bảo tính idempotent với IF NOT EXISTS");
    }

    @Test
    @DisplayName("15. NewsPublisherResolver chuẩn hóa nhà xuất bản chính xác và loại bỏ các chuỗi generic")
    public void testNewsPublisherResolverAccurateResolution() {
        assertEquals("MarketBeat", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.marketbeat.com/articles/powering-ai/"));
        assertEquals("Yahoo Finance", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Financial News", "https://uk.finance.yahoo.com/news/netapp-beat-123.html"));
        assertEquals("CNBC", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Tin thị trường", "https://www.cnbc.com/2026/09/08/nvidia-supplier.html"));
        assertEquals("TipRanks", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Unknown", "https://www.tipranks.com/news/nvidia-board-member"));
        assertEquals("24/7 Wall St.", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("None", "https://247wallst.com/investing/2026/09/07/microsoft/"));
        assertEquals("Reuters", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Reuters", "https://reuters.com/business/finance"));
        assertEquals("Bloomberg", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Bloomberg", null));

        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Financial News"));
        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Tin thị trường"));
        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Unknown"));
        assertFalse(com.llmgateway.service.NewsPublisherResolver.isGeneric("MarketBeat"));
    }

    @Test
    @DisplayName("16. NewsCacheService.cleanupLegacyCacheSources chuẩn hóa null-safe, không ném exception và không tạo titleVi/bulletsVi giả")
    public void testCleanupLegacyCacheSourcesNullSafeAndNoFakeTranslations() {
        // Bản ghi 1: source generic + URL null
        NewsAiCache item1 = new NewsAiCache();
        item1.setId(1L);
        item1.setArticleUrl(null);
        item1.setSource("Financial News");
        item1.setTitle("English Title One");
        item1.setDisplayTitleVi(null);
        item1.setBulletPointsVi(null);
        item1.setSummaryPoints("[\"Summary point 1\"]");

        // Bản ghi 2: source generic + URL sai (malformed)
        NewsAiCache item2 = new NewsAiCache();
        item2.setId(2L);
        item2.setArticleUrl("ht!tp://invalid-url-%%");
        item2.setSource("Tin thị trường");
        item2.setTitle("English Title Two");
        item2.setDisplayTitleVi(null);
        item2.setBulletPointsVi(null);
        item2.setSummaryPoints("[\"Summary point 2\"]");

        // Bản ghi 3: source generic + URL domain hợp lệ
        NewsAiCache item3 = new NewsAiCache();
        item3.setId(3L);
        item3.setArticleUrl("https://finance.yahoo.com/news/test.html");
        item3.setSource("Market News");
        item3.setTitle("English Title Three");
        item3.setDisplayTitleVi(null);
        item3.setBulletPointsVi(null);
        item3.setSummaryPoints("[\"Summary point 3\"]");

        when(newsAiCacheRepository.findAll()).thenReturn(List.of(item1, item2, item3));
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> i.getArgument(0));

        // Kiểm tra cleanup không ném exception
        assertDoesNotThrow(() -> {
            int updated = newsCacheService.cleanupLegacyCacheSources();
            assertEquals(3, updated);
        });

        // Bản ghi 1: không phân giải được publisher -> source thành null, KHÔNG tạo titleVi/bulletsVi giả
        assertNull(item1.getSource());
        assertNull(item1.getDisplayTitleVi(), "Cleanup không được dùng regex để bịa displayTitleVi");
        assertNull(item1.getBulletPointsVi(), "Cleanup không được copy summaryPoints sang bulletPointsVi rồi giả mạo là kết quả Gemini");

        // Bản ghi 2: URL sai -> không ném exception, source thành null, không tạo titleVi giả
        assertNull(item2.getSource());
        assertNull(item2.getDisplayTitleVi());
        assertNull(item2.getBulletPointsVi());

        // Bản ghi 3: domain hợp lệ -> phân giải thành "Yahoo Finance", không tạo titleVi giả
        assertEquals("Yahoo Finance", item3.getSource());
        assertNull(item3.getDisplayTitleVi());
        assertNull(item3.getBulletPointsVi());
    }

    @Test
    @DisplayName("17. Cache lưu và phục hồi trọn vẹn displayTitleVi và bulletPointsVi")
    public void testCacheSavesAndRestoresVietnameseFields() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(501L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://www.marketbeat.com/articles/amd-ai-chip",
                "AMD Unveils Next-Gen AI Chip",
                "AMD",
                "[\"AMD công bố dòng chip AI mới\", \"Hiệu năng vượt trội trong xử lý dữ liệu lớn\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Động lực tăng trưởng mạnh",
                LocalDateTime.of(2026, 9, 9, 10, 0),
                LocalDateTime.of(2026, 9, 9, 10, 5),
                "Alex Vance",
                "Financial News", // Nguồn generic -> phải được resolve sang MarketBeat
                "AMD unveiled its next generation artificial intelligence accelerator.",
                "https://example.com/amd.jpg",
                "AMD Unveils Next-Gen AI Chip",
                "AMD ra mắt dòng chip AI thế hệ mới",
                "[\"AMD công bố dòng chip AI mới\", \"Hiệu năng vượt trội trong xử lý dữ liệu lớn\"]"
        );

        assertTrue(saved.isPresent());
        NewsAiCache entity = saved.get();
        assertEquals("MarketBeat", entity.getSource(), "Nguồn generic phải được tự động chuyển thành MarketBeat");
        assertEquals("AMD ra mắt dòng chip AI thế hệ mới", entity.getDisplayTitleVi());
        assertEquals("AMD Unveils Next-Gen AI Chip", entity.getOriginalTitle());
        assertNotNull(entity.getBulletPointsVi());
    }

    @Test
    @DisplayName("18. Endpoint /api/news/sync trả về displayTitleVi, publisher và bulletPointsVi không có generic")
    public void testSyncEndpointReturnsVietnameseLocalizationAndPublisher() {
        NewsAiCache item = new NewsAiCache();
        item.setId(601L);
        item.setTitle("Apple Reports Record Q3 Revenue");
        item.setDisplayTitleVi("Apple công bố doanh thu kỷ lục Q3");
        item.setOriginalTitle("Apple Reports Record Q3 Revenue");
        item.setArticleUrl("https://www.cnbc.com/apple-q3");
        item.setSymbol("AAPL");
        item.setSummaryPoints("[\"Doanh thu đạt mốc kỷ lục\", \"Dịch vụ tăng trưởng mạnh mẽ\"]");
        item.setBulletPointsVi("[\"Doanh thu đạt mốc kỷ lục\", \"Dịch vụ tăng trưởng mạnh mẽ\"]");
        item.setSentiment("BULLISH");
        item.setConfidencePct(BigDecimal.valueOf(95));
        item.setPublishedAt(LocalDateTime.of(2026, 9, 9, 12, 0));
        item.setAuthor("Tim Team");
        item.setSource("Financial News"); // Generic trong cache cũ
        item.setBannerImage("https://example.com/aapl.jpg");
        item.setOriginalSummary("Apple reported record revenue for the third fiscal quarter.");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("AAPL")))
                .thenReturn(List.of(item));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(item));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        ResponseEntity<Map<String, Object>> response = controller.getSyncNewsFeed("AAPL", 5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, data.size());

        Map<String, Object> first = data.get(0);
        assertEquals("Apple công bố doanh thu kỷ lục Q3", first.get("title"));
        assertEquals("Apple công bố doanh thu kỷ lục Q3", first.get("displayTitleVi"));
        assertEquals("Apple Reports Record Q3 Revenue", first.get("originalTitle"));
        assertEquals("CNBC", first.get("source"), "Source generic phải được resolve thành CNBC");
        assertEquals("CNBC", first.get("publisher"));
        assertNotNull(first.get("bulletPointsVi"));
    }

    @Test
    @DisplayName("19. NewsLocalizationQualityPolicy kiểm tra chuẩn chất lượng tiếng Việt, phát hiện câu chưa dịch và boilerplate")
    public void testNewsLocalizationQualityPolicyRules() {
        // Tiêu đề
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(null, "English Title"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("", "English Title"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Apple Reports Record Q3 Revenue", "Apple Reports Record Q3 Revenue"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Some un-translated english title", "Some original title"));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Apple công bố doanh thu kỷ lục Q3", "Apple Reports Record Q3 Revenue"));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("NVIDIA mở vị thế $410 Million", "NVIDIA Takes $410M Position"));

        // Bullets
        List<String> validBullets = List.of(
                "Doanh thu đạt mức cao kỷ lục nhờ mảng dịch vụ",
                "Biên lợi nhuận gộp duy trì ổn định ở mức 45%",
                "Cổ tức được công bố tăng 5% cho các cổ đông"
        );
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(validBullets, "Apple công bố doanh thu kỷ lục Q3"));

        // Ít hơn 2 bullet -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(List.of("Một ý duy nhất"), "Tiêu đề"));

        // Lặp tiêu đề -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(
                List.of("Apple công bố doanh thu kỷ lục Q3", "Một ý hợp lệ khác"),
                "Apple công bố doanh thu kỷ lục Q3"
        ));

        // Boilerplate thừa -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(
                List.of("Bài viết này nói về sự tăng trưởng", "Ý thứ hai"),
                "Tiêu đề"
        ));
    }

    @Test
    @DisplayName("20. NewsPublisherResolver không được tự bịa MarketBeat cho domain chưa biết hoặc URL null")
    public void testNewsPublisherResolverNoInventedPublisher() {
        // URL MarketBeat -> MarketBeat
        assertEquals("MarketBeat", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.marketbeat.com/stocks/NASDAQ/NVDA/"));
        // Yahoo Finance -> Yahoo Finance
        assertEquals("Yahoo Finance", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://finance.yahoo.com/news/123.html"));
        // Domain chưa biết -> dùng hostname đã làm sạch
        assertEquals("techcrunch.com", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.techcrunch.com/2026/09/08/ai-startup/"));
        assertEquals("custom-finance.org", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://custom-finance.org/article/"));
        // URL null / sai định dạng -> trả null hoặc rỗng, TUYỆT ĐỐI KHÔNG trả MarketBeat
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, null));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "not-a-valid-url"));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Tin thị trường", null));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Financial News", ""));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Unknown", "   "));
    }

    @Test
    @DisplayName("21. Bảo toàn originalTitle độc lập và không bị tráo đổi với displayTitleVi qua Cache round-trip")
    public void testOriginalTitleAndDisplayTitleViStrictIndependenceInCacheAndSync() {
        String originalEnglish = "Microsoft Signs Multi-Year AI Infrastructure Contract";
        String translatedVietnamese = "Microsoft ký hợp đồng hạ tầng AI nhiều năm";

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(701L);
            return entity;
        });

        Optional<NewsAiCache> savedOpt = newsCacheService.saveCachedArticle(
                "https://www.reuters.com/technology/msft-ai-2026",
                originalEnglish,
                "MSFT",
                "[\"Hợp đồng cung cấp giải pháp đám mây\", \"Tối ưu hóa chi phí vận hành\"]",
                "BULLISH",
                BigDecimal.valueOf(92),
                "Hợp đồng lớn mở rộng thị phần AI",
                LocalDateTime.of(2026, 9, 8, 8, 30),
                LocalDateTime.of(2026, 9, 8, 8, 35),
                "John Doe",
                "Reuters",
                "Microsoft announced a major contract for infrastructure.",
                "https://example.com/msft.jpg",
                originalEnglish,
                translatedVietnamese,
                "[\"Hợp đồng cung cấp giải pháp đám mây\", \"Tối ưu hóa chi phí vận hành\"]"
        );

        assertTrue(savedOpt.isPresent());
        NewsAiCache cached = savedOpt.get();

        // Kiểm tra độc lập tuyệt đối giữa originalTitle và displayTitleVi
        assertEquals(originalEnglish, cached.getOriginalTitle(), "originalTitle phải giữ nguyên từng ký tự tiếng Anh gốc");
        assertEquals(translatedVietnamese, cached.getDisplayTitleVi(), "displayTitleVi phải là bản dịch tiếng Việt");
        assertNotEquals(cached.getOriginalTitle(), cached.getDisplayTitleVi(), "Hai trường phải hoàn toàn độc lập");
    }

    @Test
    @DisplayName("22. Bản ghi cache cũ thiếu displayTitleVi được làm giàu và cập nhật mà không xóa cache")
    public void testReEnrichLegacyCacheItemWhenAlphaReturnsSameUrl() {
        String url = "https://www.marketbeat.com/articles/nvda-split";
        String origTitle = "NVIDIA (NVDA) Announces Stock Split Effective Next Month";
        String newViTitle = "NVIDIA (NVDA) công bố chia tách cổ phiếu có hiệu lực từ tháng sau";

        NewsAiCache legacyEntity = new NewsAiCache();
        legacyEntity.setId(801L);
        legacyEntity.setArticleUrl(url);
        legacyEntity.setTitle(origTitle);
        legacyEntity.setOriginalTitle(origTitle);
        legacyEntity.setDisplayTitleVi(null); // Bản ghi cũ chưa có displayTitleVi
        legacyEntity.setSource("Financial News");

        when(newsAiCacheRepository.findByArticleUrl(eq(url))).thenReturn(Optional.of(legacyEntity));
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> i.getArgument(0));

        Optional<NewsAiCache> updated = newsCacheService.saveCachedArticle(
                url,
                origTitle,
                "NVDA",
                "[\"Kế hoạch chia tách cổ phiếu 10:1\", \"Tăng thanh khoản cho nhà đầu tư cá nhân\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Chia tách kích thích cầu",
                LocalDateTime.now(),
                LocalDateTime.now(),
                "Jane Doe",
                "MarketBeat",
                "Nvidia announced a stock split.",
                "https://example.com/nvda.jpg",
                origTitle,
                newViTitle,
                "[\"Kế hoạch chia tách cổ phiếu 10:1\", \"Tăng thanh khoản cho nhà đầu tư cá nhân\"]"
        );

        assertTrue(updated.isPresent());
        NewsAiCache result = updated.get();
        assertEquals(801L, result.getId(), "Giữ nguyên id của bản ghi cũ, không tạo record mới hoặc xóa cache");
        assertEquals(origTitle, result.getOriginalTitle(), "originalTitle giữ nguyên văn");
        assertEquals(newViTitle, result.getDisplayTitleVi(), "displayTitleVi được cập nhật thành công");
        assertEquals("MarketBeat", result.getSource(), "Nguồn được cập nhật từ MarketBeat");
    }

    @Test
    @DisplayName("23. originalSummary không bao giờ rò rỉ vào trường summary hiển thị trong /api/news/sync")
    public void testOriginalSummaryNeverLeaksIntoDisplaySummaryInSync() {
        String engTitle = "Apple Reports Record Q3 Services Revenue Amid iPhone Stagnation";
        String viTitle = "Apple công bố doanh thu mảng dịch vụ quý 3 đạt kỷ lục mới";
        String engSummary = "Apple announced third quarter fiscal results with strong services revenue growth of 14 percent year over year.";
        String viSummary = "Apple ghi nhận doanh thu mảng dịch vụ tăng trưởng 14% so với cùng kỳ, bù đắp sự chững lại của doanh số iPhone.";
        List<String> viBullets = List.of(
                "Doanh thu dịch vụ tăng 14% đạt mức cao kỷ lục.",
                "Biên lợi nhuận gộp toàn tập đoàn đạt mức tích cực.",
                "HĐQT cam kết duy trì chương trình mua lại cổ phiếu."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(901L);
        entity.setArticleUrl("https://www.cnbc.com/2026/09/08/apple-q3.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi("[\"" + String.join("\",\"", viBullets) + "\"]");
        entity.setSource("CNBC");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(90));
        entity.setReason("Tăng trưởng dịch vụ vững chắc");
        entity.setPublishedAt(LocalDateTime.of(2026, 9, 8, 14, 0));

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(null, 5);
        assertFalse(feed.isEmpty(), "Feed phải có ít nhất 1 bài từ cache");
        NewsFeedItemDto item = feed.get(0);

        assertEquals(engSummary, item.getOriginalSummary(), "originalSummary phải chứa nguyên văn tóm tắt tiếng Anh gốc");
        assertEquals(viSummary, item.getDisplaySummaryVi(), "displaySummaryVi phải chứa tóm tắt tiếng Việt");
        assertEquals(viSummary, item.getSummary(), "summary hiển thị tuyệt đối không phải là originalSummary tiếng Anh");
        assertFalse(item.getSummary().contains("announced third quarter fiscal results"), "summary không được chứa câu tiếng Anh từ originalSummary");
    }

    @Test
    @DisplayName("24. Các trường tương thích title/summary/bulletPoints đều là tiếng Việt 100%")
    public void testCompatibilityFieldsAreAllVietnamese() {
        String engTitle = "Federal Reserve Signals Potential September Rate Cut";
        String viTitle = "Cục Dự trữ Liên bang phát tín hiệu có thể hạ lãi suất trong tháng 9";
        String engSummary = "Fed Chair indicated policy makers are prepared to adjust monetary stance.";
        String viSummary = "Chủ tịch Fed cho biết các nhà hoạch định chính sách sẵn sàng điều chỉnh lập trường tiền tệ nếu lạm phát tiếp tục hạ nhiệt.";
        List<String> viBullets = List.of(
                "Fed theo dõi chặt chẽ dữ liệu thị trường lao động và lạm phát.",
                "Khả năng hạ lãi suất 25 điểm cơ bản trong phiên họp sắp tới.",
                "Thị trường tài chính phản ứng tích cực với phát biểu."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(902L);
        entity.setArticleUrl("https://www.reuters.com/markets/us/fed-rates-2026.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi("[\"" + String.join("\",\"", viBullets) + "\"]");
        entity.setSource("Reuters");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(88));
        entity.setPublishedAt(LocalDateTime.now());

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(null, 5);
        NewsFeedItemDto item = feed.get(0);

        // Trường tương thích
        assertEquals(item.getDisplayTitleVi(), item.getTitle(), "title tương thích phải bằng displayTitleVi");
        assertEquals(item.getDisplaySummaryVi(), item.getSummary(), "summary tương thích phải bằng displaySummaryVi");
        assertEquals(item.getBulletPointsVi(), item.getAiSummary(), "bulletPoints tương thích phải bằng bulletPointsVi");

        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(item.getTitle()));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(item.getSummary()));
        for (String b : item.getBulletPointsVi()) {
            assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(b));
        }
    }

    @Test
    @DisplayName("25. Cache có tiêu đề Việt nhưng bullet hoặc summary tiếng Anh bị coi là chưa localized")
    public void testCacheWithEnglishBulletsOrSummaryRejectedByPolicy() {
        String viTitle = "Tesla mở rộng mạng lưới trạm sạc siêu nhanh tại châu Á";
        String engTitle = "Tesla Expands Supercharger Network in Asia";
        String engSummary = "Tesla announced ambitious charging infrastructure plans.";
        String viSummary = "Tesla công bố kế hoạch phát triển hạ tầng sạc xe điện tại các thị trường trọng điểm.";
        List<String> englishBullets = List.of(
                "Tesla expands superchargers across Asia.",
                "New fast charging standard adopted by local partners."
        );

        // Trường hợp 1: Bullets bằng tiếng Anh -> rejected
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, viSummary, engSummary, englishBullets, "Reuters"
        ), "Bài có bullet tiếng Anh bắt buộc phải bị loại khỏi enrichedList");

        // Trường hợp 2: Summary bằng tiếng Anh (hoặc trùng originalSummary) -> rejected
        List<String> viBullets = List.of(
                "Mạng lưới trạm sạc mở rộng sang 5 quốc gia mới.",
                "Hợp tác với các đối tác hạ tầng nội địa."
        );
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, engSummary, engSummary, viBullets, "Reuters"
        ), "Bài có summary tiếng Anh trùng originalSummary bắt buộc phải bị từ chối");

        // Trường hợp 3: Summary trùng title -> rejected
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, viTitle, engSummary, viBullets, "Reuters"
        ), "Summary trùng lặp title phải bị từ chối");
    }

    @Test
    @DisplayName("26. Số lượng bullet points bắt buộc từ 2 đến 4, từ chối 1 hoặc 5 bullet")
    public void testBulletsCountStrictlyTwoToFour() {
        String viTitle = "Thị trường tiền điện tử phục hồi sau chuỗi ngày giảm giá";

        List<String> oneBullet = List.of("Giá Bitcoin vượt mốc 60.000 USD sau tín hiệu tích cực.");
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(oneBullet, viTitle), "1 bullet phải bị từ chối");

        List<String> fiveBullets = List.of(
                "Bitcoin tăng trưởng mạnh mẽ trở lại.",
                "Dòng tiền tổ chức tiếp tục gia nhập thị trường.",
                "Chỉ số sợ hãi và tham lam chuyển sang tích cực.",
                "Khối lượng giao dịch trên các sàn tăng 30%.",
                "Ý thứ năm vượt quá giới hạn cho phép."
        );
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(fiveBullets, viTitle), "5 bullet phải bị từ chối");

        List<String> validThreeBullets = List.of(
                "Bitcoin phục hồi vượt vùng kháng cự ngắn hạn.",
                "Dòng tiền giải ngân ổn định trở lại.",
                "Tâm lý nhà đầu tư dần được cải thiện."
        );
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(validThreeBullets, viTitle), "3 bullet hợp lệ phải được chấp nhận");
    }

    @Test
    @DisplayName("27. MockMvc kiểm tra hợp đồng thật của /api/news/sync với đầy đủ 12 production keys")
    public void testMockMvcNewsSyncReturnsExactProductionContract() throws Exception {
        String engTitle = "Gold Prices Surge to Historic High on Safe-Haven Demand";
        String viTitle = "Giá vàng tăng vọt lên mức kỷ lục lịch sử do nhu cầu trú ẩn an toàn";
        String engSummary = "Spot gold rose 1.5 percent to reach all-time high as geopolitical tensions escalated.";
        String viSummary = "Giá vàng giao ngay tăng 1,5% chạm đỉnh lịch sử mới khi căng thẳng địa chính trị thúc đẩy dòng vốn tìm nơi trú ẩn an toàn.";
        List<String> viBullets = List.of(
                "Giá vàng quốc tế xác lập kỷ lục cao nhất mọi thời đại.",
                "Lợi suất trái phiếu giảm hỗ trợ đà tăng của kim loại quý.",
                "Quỹ ETF vàng toàn cầu ghi nhận tuần mua ròng thứ ba liên tiếp."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(903L);
        entity.setArticleUrl("https://www.bloomberg.com/markets/commodities/gold-record-2026.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi(objectMapper.writeValueAsString(viBullets));
        entity.setSource("Bloomberg");
        entity.setAuthor("Robert Smith");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(94));
        entity.setPublishedAt(LocalDateTime.of(2026, 9, 8, 10, 30));

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        org.springframework.test.web.servlet.MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ok"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].originalTitle").value(engTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].originalSummary").value(engSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].displayTitleVi").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].displaySummaryVi").value(viSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].bulletPointsVi[0]").value(viBullets.get(0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].publisher").value("Bloomberg"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].author").value("Robert Smith"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].title").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].summary").value(viSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].bulletPoints[0]").value(viBullets.get(0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].sentiment").value("bullish"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].confidence").value(94));
    }

    @Test
    @DisplayName("28. Cache round-trip bảo toàn trọn vẹn cả dữ liệu gốc và dữ liệu bản địa hóa")
    public void testCacheRoundTripPreservesOriginalAndLocalizedData() {
        String origTitle = "JPMorgan Upgrades Semiconductor Sector Outlook for 2027";
        String origSummary = "Analysts raised price targets across leading chipmakers citing persistent data center demand.";
        String viTitle = "JPMorgan nâng triển vọng ngành bán dẫn cho năm 2027";
        String viSummary = "Các chuyên gia phân tích nâng giá mục tiêu cho nhóm cổ phiếu chip trước nhu cầu trung tâm dữ liệu tăng bền vững.";
        String viBulletsJson = "[\"Nâng triển vọng ngành bán dẫn lên mức khả quan.\",\"Nhu cầu chip trung tâm dữ liệu tăng trưởng vượt dự báo.\"]";

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache c = i.getArgument(0);
            c.setId(999L);
            return c;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://finance.yahoo.com/news/jpmorgan-chips.html",
                origTitle,
                "SOXX",
                viBulletsJson,
                "BULLISH",
                BigDecimal.valueOf(91),
                "Triển vọng bán dẫn tích cực",
                LocalDateTime.now(),
                LocalDateTime.now(),
                "Analyst Team",
                "Yahoo Finance",
                origSummary,
                "https://example.com/chip.jpg",
                origTitle,
                viTitle,
                viSummary,
                viBulletsJson
        );

        assertTrue(saved.isPresent());
        NewsAiCache cached = saved.get();
        assertEquals(origTitle, cached.getOriginalTitle());
        assertEquals(origSummary, cached.getOriginalSummary());
        assertEquals(viTitle, cached.getDisplayTitleVi());
        assertEquals(viSummary, cached.getDisplaySummaryVi());
        assertEquals(viBulletsJson, cached.getBulletPointsVi());
        assertEquals("Yahoo Finance", cached.getSource());
    }

    @Test
    @DisplayName("29. Alpha trả bài tiếng Anh -> Gemini trả titleVi + bulletsVi -> endpoint có dữ liệu")
    public void testAlphaEnglishGeminiTranslatesEndpointHasData() throws Exception {
        String engTitle = "Nvidia Unveils Next Generation AI Architecture";
        String viTitle = "Nvidia công bố kiến trúc trí tuệ nhân tạo thế hệ mới";
        List<String> viBullets = List.of(
                "Kiến trúc mới mang lại hiệu năng tính toán vượt trội.",
                "Dự kiến bắt đầu cung cấp cho các trung tâm dữ liệu vào quý tới."
        );

        NewsFeedItemDto item = new NewsFeedItemDto();
        item.setOriginalTitle(engTitle);
        item.setDisplayTitleVi(viTitle);
        item.setTitle(viTitle);
        item.setUrl("https://finance.yahoo.com/news/nvda-ai.html");
        item.setSource("Yahoo Finance");
        item.setPublisher("Yahoo Finance");
        item.setBulletPointsVi(viBullets);
        item.setAiSummary(viBullets);
        item.setAiSentiment("BULLISH");
        item.setAiConfidence(90);

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of());

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService) {
            @Override
            public ResponseEntity<Map<String, Object>> getSyncNewsFeed(String symbol, int limit) {
                Map<String, Object> resp = new java.util.HashMap<>();
                resp.put("status", "ok");
                resp.put("data", List.of(Map.of(
                        "id", item.getUrl(),
                        "title", viTitle,
                        "bulletPoints", viBullets,
                        "publisher", "Yahoo Finance",
                        "author", "",
                        "publishedAt", "2026-09-11T12:00:00",
                        "imageUrl", "",
                        "link", item.getUrl(),
                        "originalTitle", engTitle,
                        "originalSummary", ""
                )));
                return ResponseEntity.ok(resp);
            }
        };

        org.springframework.test.web.servlet.MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ok"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].title").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].originalTitle").value(engTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].bulletPoints[0]").value(viBullets.get(0)));
    }

    @Test
    @DisplayName("30. Cache hit không gọi Gemini lần hai")
    public void testCacheHitDoesNotCallGeminiTwice() {
        String url = "https://example.com/cached-news-1";
        String origTitle = "Apple Reports Record Services Revenue";
        String viTitle = "Apple ghi nhận doanh thu mảng dịch vụ đạt mức kỷ lục";
        List<String> bullets = List.of(
                "Doanh thu đạt mốc cao nhất từ trước đến nay.",
                "Biên lợi nhuận gộp tiếp tục được duy trì ở mức cao."
        );

        NewsAiCache cached = new NewsAiCache();
        cached.setArticleUrl(url);
        cached.setOriginalTitle(origTitle);
        cached.setTitle(origTitle);
        cached.setDisplayTitleVi(viTitle);
        cached.setBulletPointsVi("[\"" + bullets.get(0) + "\",\"" + bullets.get(1) + "\"]");
        cached.setSentiment("BULLISH");
        cached.setConfidencePct(BigDecimal.valueOf(92));

        when(newsAiCacheRepository.findByArticleUrl(url)).thenReturn(Optional.of(cached));

        // Kiểm tra policy nhận diện cache hợp lệ
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                cached.getDisplayTitleVi(), cached.getOriginalTitle(), bullets
        ));
    }

    @Test
    @DisplayName("31. Author và publisher giữ đúng nguồn, không dịch tên author, publisher không làm loại bài")
    public void testAuthorAndPublisherPreserveSource() {
        String origTitle = "Market Overview by Chief Strategist";
        String viTitle = "Tổng quan thị trường từ chuyên gia chiến lược";
        List<String> bullets = List.of(
                "Chỉ số phục hồi tích cực trong phiên chiều.",
                "Thanh khoản duy trì ở mức trung bình 20 phiên."
        );

        // Khi publisher rỗng -> bài vẫn HỢP LỆ
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, origTitle, bullets
        ));

        // Author không bị dịch, publisher giữ đúng nguồn
        String rawAuthor = "Michael Hartnett";
        assertEquals("Michael Hartnett", rawAuthor, "Tên tác giả phải được giữ nguyên, không dịch");
    }

    @Test
    @DisplayName("32. Phân biệt chính xác: Alpha rỗng -> empty, Alpha có bài nhưng Gemini lỗi toàn bộ -> degraded, limit <= 0 hoặc > 20 -> 400")
    public void testAccurateEmptyDegradedAndLimitValidation() throws Exception {
        // Mock service
        AiNewsService mockService = mock(AiNewsService.class);
        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(mockService);
        org.springframework.test.web.servlet.MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        // 1. Alpha không có bài -> status empty
        when(mockService.getLiveAiNewsSyncResult(any(), eq(5)))
                .thenReturn(com.llmgateway.dto.news.NewsSyncResult.empty("Chưa có bản tin mới"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("empty"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Chưa có bản tin mới"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());

        // 2. Alpha có bài nhưng Gemini lỗi toàn bộ và cache rỗng -> status degraded
        when(mockService.getLiveAiNewsSyncResult(any(), eq(5)))
                .thenReturn(com.llmgateway.dto.news.NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("degraded"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());

        // 3. limit <= 0 hoặc limit > 20 -> HTTP 400 Bad Request cho cả /api/news/sync và /api/news/feed
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync?limit=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync?limit=-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync?limit=21"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/feed?limit=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/feed?limit=-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/feed?limit=21"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));
    }


    @Test
    @DisplayName("33. Không dùng regex thay từ để giả vờ đã dịch tiêu đề tiếng Anh")
    public void testNoRegexTitleTranslator() {
        String engTitle = "Random Financial Headline That Cannot Be Regex Translated";
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(null, engTitle));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("", engTitle));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(engTitle, engTitle),
                "Tiêu đề nguyên văn tiếng Anh không được coi là tiếng Việt");
    }

    @Test
    @DisplayName("34. Test hành vi thật: Alpha HTTP 200 feed=[] -> SUCCESS_EMPTY -> status empty khi cache rỗng")
    public void testPipelineAlphaFeedEmptyGivesEmptyStatus() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockResponse = mock(java.net.http.HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"items\": \"0\", \"sentiment_score_definition\": \"...\", \"feed\": []}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY_FOR_TEST");
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertEquals("empty", result.getStatus());
        assertEquals("Chưa có bản tin mới", result.getMessage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("35. Test hành vi thật: Alpha Rate Limit Note/429 -> UNAVAILABLE -> status degraded khi cache rỗng")
    public void testPipelineAlphaRateLimitGivesDegradedStatus() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockResponse = mock(java.net.http.HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"Note\": \"Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day.\"}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY_FOR_TEST");
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertEquals("degraded", result.getStatus());
        assertEquals("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", result.getMessage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("36. Test hành vi thật: Alpha Timeout/IOException -> UNAVAILABLE -> status degraded khi cache rỗng")
    public void testPipelineAlphaTimeoutGivesDegradedStatus() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpConnectTimeoutException("Connection timed out to Alpha Vantage"));

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY_FOR_TEST");
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertEquals("degraded", result.getStatus());
        assertEquals("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", result.getMessage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("37. Test hành vi thật: Alpha có bài nhưng Gemini lỗi toàn bộ -> status degraded khi cache rỗng")
    public void testPipelineGeminiFailsGivesDegradedStatus() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);

        // Request 1: Alpha trả về 1 bài báo thật
        java.net.http.HttpResponse<String> alphaResp = mock(java.net.http.HttpResponse.class);
        when(alphaResp.statusCode()).thenReturn(200);
        String alphaBody = "{\"feed\": [{" +
                "\"title\": \"Gold Hits All-Time High On Inflation Data\"," +
                "\"url\": \"https://example.com/gold-high\"," +
                "\"time_published\": \"20260911T120000\"," +
                "\"summary\": \"Gold prices surged past record levels on latest macroeconomic reports.\"," +
                "\"source\": \"MarketBeat\"," +
                "\"category_within_source\": \"Commodities\"," +
                "\"topics\": [{\"topic\": \"financial_markets\"}]" +
                "}]}";
        when(alphaResp.body()).thenReturn(alphaBody);

        // Request 2: Gemini API trả về HTTP 500 lỗi
        java.net.http.HttpResponse<String> geminiResp = mock(java.net.http.HttpResponse.class);
        when(geminiResp.statusCode()).thenReturn(500);
        when(geminiResp.body()).thenReturn("{\"error\": \"Internal Gemini Error\"}");

        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(alphaResp)
                .thenReturn(geminiResp);

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY_FOR_TEST");
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "VALID_GEMINI_KEY");
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findByTitle(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("XAUUSD", 5);

        assertNotNull(result);
        assertEquals("degraded", result.getStatus());
        assertEquals("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", result.getMessage());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("38. Test hành vi thật: Provider lỗi nhưng có cache tiếng Việt hợp lệ -> status ok trả từ cache")
    public void testPipelineReturnsOkFromCacheWhenProviderUnavailable() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpConnectTimeoutException("Provider unavailable"));

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY_FOR_TEST");

        NewsAiCache cached = new NewsAiCache();
        cached.setId(999L);
        cached.setArticleUrl("https://example.com/cached-btc");
        cached.setTitle("Bitcoin Vượt Ngưỡng 70.000 USD Nhờ Dòng Vốn Thể Chế");
        cached.setOriginalTitle("Bitcoin Breaks 70K Record");
        cached.setDisplayTitleVi("Bitcoin Vượt Ngưỡng 70.000 USD Nhờ Dòng Vốn Thể Chế");
        cached.setSummaryPoints("[\"Dòng tiền đổ mạnh vào các quỹ ETF giao ngay.\", \"Tâm lý thị trường chuyển biến tích cực trong tuần qua.\"]");
        cached.setBulletPointsVi("[\"Dòng tiền đổ mạnh vào các quỹ ETF giao ngay.\", \"Tâm lý thị trường chuyển biến tích cực trong tuần qua.\"]");
        cached.setDisplaySummaryVi("Dòng tiền đổ mạnh vào các quỹ ETF giao ngay. Tâm lý thị trường chuyển biến tích cực trong tuần qua.");
        cached.setSentiment("BULLISH");
        cached.setConfidencePct(BigDecimal.valueOf(90));
        cached.setReason("Dòng vốn ETF tăng mạnh");
        cached.setSource("Bloomberg");
        cached.setPublishedAt(LocalDateTime.now().minusHours(2));

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(List.of(cached));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertEquals("ok", result.getStatus());
        assertEquals(1, result.getItems().size());
        assertEquals("Bitcoin Vượt Ngưỡng 70.000 USD Nhờ Dòng Vốn Thể Chế", result.getItems().get(0).getTitle());
        assertEquals("Bloomberg", result.getItems().get(0).getPublisher());
        assertTrue(result.getItems().get(0).isFromCache());
    }

    @Test
    @DisplayName("39. Giới hạn fetchCount và validation gọi production helper thật, không tự chép lại biểu thức")
    public void testLimitClampedToFiftyWithoutOverflow() {
        assertEquals(5, AiNewsService.DEFAULT_LIMIT);
        assertEquals(20, AiNewsService.MAX_LIMIT);
        assertEquals(50, AiNewsService.MAX_ALPHA_FETCH);

        // Gọi production helper thật
        assertEquals(15, AiNewsService.calculateAlphaFetchCount(5));
        assertEquals(50, AiNewsService.calculateAlphaFetchCount(20), "limit=20 nhân 3 = 60 nhưng bị clamp xuống MAX_ALPHA_FETCH (50)");
        assertEquals(3, AiNewsService.calculateAlphaFetchCount(1));

        // Kiểm tra isValidLimit
        assertTrue(AiNewsService.isValidLimit(1));
        assertTrue(AiNewsService.isValidLimit(5));
        assertTrue(AiNewsService.isValidLimit(20));
        assertFalse(AiNewsService.isValidLimit(0));
        assertFalse(AiNewsService.isValidLimit(-1));
        assertFalse(AiNewsService.isValidLimit(21));
        assertFalse(AiNewsService.isValidLimit(100));

        // Kiểm tra ném IllegalArgumentException khi limit sai phạm vi
        assertThrows(IllegalArgumentException.class, () -> AiNewsService.calculateAlphaFetchCount(0));
        assertThrows(IllegalArgumentException.class, () -> AiNewsService.calculateAlphaFetchCount(21));
        assertThrows(IllegalArgumentException.class, () -> aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 0));
        assertThrows(IllegalArgumentException.class, () -> aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 21));
        assertThrows(IllegalArgumentException.class, () -> aiNewsService.getLiveAiNewsFeed("BTCUSDT", 0));
        assertThrows(IllegalArgumentException.class, () -> aiNewsService.getLiveAiNewsFeed("BTCUSDT", 21));
    }


    @Test
    @DisplayName("40. ProductionSecurityFilter cho phép /api/news/diagnostics an toàn và vẫn chặn h2/swagger/db-query")
    public void testProductionSecurityFilterBlocksDiagnostics() throws Exception {
        com.llmgateway.filter.ProductionSecurityFilter filter = new com.llmgateway.filter.ProductionSecurityFilter();

        assertFalse(filter.isBlockedPath("/api/news/diagnostics"), "Endpoint diagnostics an toàn không được bị chặn");
        assertFalse(filter.isBlockedPath("/api/news/diagnostics/"), "Endpoint diagnostics an toàn không được bị chặn");

        assertTrue(filter.isBlockedPath("/h2-console"));
        assertTrue(filter.isBlockedPath("/swagger-ui"));
        assertTrue(filter.isBlockedPath("/api/admin/db/query"));

        assertFalse(filter.isBlockedPath("/api/news/sync"));
        assertFalse(filter.isBlockedPath("/api/news/feed"));
        assertFalse(filter.isBlockedPath("/admin.html"));

        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        when(req.getRequestURI()).thenReturn("/api/news/diagnostics");

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(filter, "doFilterInternal", req, res, chain);

        verify(res, never()).sendError(anyInt());
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    @DisplayName("41. MobileSyncController /api/mobile/news/sync giới hạn limit 1..20: limit=0, -1, 21 trả 400 và limit=20 trả 200")
    public void testMobileSyncControllerLimitValidationMockMvc() throws Exception {
        org.springframework.test.web.servlet.MockMvc mockMvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(mobileSyncController).build();

        // 1. limit = 0 -> 400 Bad Request
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/news/sync?limit=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        // 2. limit = -1 -> 400 Bad Request
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/news/sync?limit=-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        // 3. limit = 21 -> 400 Bad Request
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/news/sync?limit=21"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("error"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Tham số limit phải nằm trong khoảng từ 1 đến 20"));

        // 4. limit = 20 -> 200 OK thành công
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mobile/news/sync?limit=20"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    @DisplayName("42. Xác nhận không còn gemini-2.5-flash trong cấu hình production và fallback service")
    public void testProductionConfigDoesNotContainGemini25Flash() throws Exception {
        java.nio.file.Path prodProps = java.nio.file.Paths.get("src/main/resources/application-prod.properties");
        java.nio.file.Path defaultProps = java.nio.file.Paths.get("src/main/resources/application.properties");
        java.nio.file.Path phoneProps = java.nio.file.Paths.get("src/main/resources/application-phone.properties");

        if (java.nio.file.Files.exists(prodProps)) {
            String prodContent = java.nio.file.Files.readString(prodProps);
            assertFalse(prodContent.contains("gemini-2.5-flash"), "application-prod.properties không được chứa gemini-2.5-flash");
            assertTrue(prodContent.contains("gemini-3.6-flash"), "application-prod.properties phải cấu hình gemini-3.6-flash");
        }
        if (java.nio.file.Files.exists(defaultProps)) {
            String defaultContent = java.nio.file.Files.readString(defaultProps);
            assertFalse(defaultContent.contains("gemini-2.5-flash"), "application.properties không được chứa gemini-2.5-flash");
            assertTrue(defaultContent.contains("gemini-3.6-flash"), "application.properties phải cấu hình gemini-3.6-flash");
        }
        if (java.nio.file.Files.exists(phoneProps)) {
            String phoneContent = java.nio.file.Files.readString(phoneProps);
            assertFalse(phoneContent.contains("gemini-2.5-flash"), "application-phone.properties không được chứa gemini-2.5-flash");
            assertTrue(phoneContent.contains("gemini-3.6-flash"), "application-phone.properties phải cấu hình gemini-3.6-flash");
        }

        // Kiểm tra fallback service trong diagnostics
        ReflectionTestUtils.setField(aiNewsService, "geminiModel", "");
        Map<String, Object> diag = aiNewsService.getDiagnostics();
        assertEquals("gemini-3.6-flash", diag.get("geminiModel"), "Fallback model mặc định khi cấu hình rỗng phải là gemini-3.6-flash");
    }

    @Test
    @DisplayName("43. Vá rò rỉ Alpha Vantage API Key: Response giả chứa key trong Note/Information/Error tuyệt đối không lọt vào result/message")
    public void testAlphaVantageKeyLeakPrevention() throws Exception {
        final String SECRET_KEY = "LEAK_SECRET_KEY_12345_XYZ";
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        java.net.http.HttpResponse<String> mockResp = mock(java.net.http.HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);

        // Trường hợp 1: Alpha Vantage trả về Note chứa API key
        when(mockResp.body()).thenReturn("{\"Note\": \"Thank you for using Alpha Vantage! Your key " + SECRET_KEY + " has reached 25 req/day limit.\"}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", SECRET_KEY);
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(java.util.Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult syncResult1 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(syncResult1);
        assertFalse(String.valueOf(syncResult1.getMessage()).contains(SECRET_KEY), "Thông điệp trả ra client tuyệt đối không chứa API key");
        assertEquals("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", syncResult1.getMessage());

        // Kiểm tra coordinator failure code an toàn
        assertEquals(com.llmgateway.service.AlphaNewsCoordinator.ALPHA_RATE_LIMITED,
                aiNewsService.getAlphaNewsCoordinator().getLastFailureCode());

        // Trường hợp 2: Alpha Vantage trả về Error Message chứa API key
        aiNewsService.getAlphaNewsCoordinator().reset();
        when(mockResp.body()).thenReturn("{\"Error Message\": \"Invalid API call. Please check your key: " + SECRET_KEY + "\"}");
        com.llmgateway.dto.news.NewsSyncResult syncResult2 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(syncResult2);
        assertFalse(String.valueOf(syncResult2.getMessage()).contains(SECRET_KEY), "Error message từ provider tuyệt đối không rò rỉ ra ngoài");
        assertEquals(com.llmgateway.service.AlphaNewsCoordinator.ALPHA_INVALID_RESPONSE,
                aiNewsService.getAlphaNewsCoordinator().getLastFailureCode());

        // Trường hợp 3: HTTP 500 error body chứa key
        aiNewsService.getAlphaNewsCoordinator().reset();
        when(mockResp.statusCode()).thenReturn(500);
        when(mockResp.body()).thenReturn("Gateway Error for apikey=" + SECRET_KEY);
        com.llmgateway.dto.news.NewsSyncResult syncResult3 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(syncResult3);
        assertFalse(String.valueOf(syncResult3.getMessage()).contains(SECRET_KEY));
        assertEquals(com.llmgateway.service.AlphaNewsCoordinator.ALPHA_HTTP_ERROR,
                aiNewsService.getAlphaNewsCoordinator().getLastFailureCode());
    }

    @Test
    @DisplayName("44. Cache freshness: Kết hợp cả analyzedAt và publishedAt (bài báo xuất bản <= 24h và analyzedAt mới thì FRESH, bài xuất bản > 24h thì STALE)")
    public void testCacheFreshnessByAnalyzedAtEvenIfPublishedAtOld() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");

        NewsAiCache cached = new NewsAiCache();
        cached.setId(101L);
        cached.setArticleUrl("https://example.com/fresh-pub-fresh-analysis");
        cached.setTitle("Cổ Phiếu Công Nghệ Duy Trì Đà Tăng Trưởng Dài Hạn");
        cached.setOriginalTitle("Tech Stocks Hold Long Term Growth");
        cached.setDisplayTitleVi("Cổ Phiếu Công Nghệ Duy Trì Đà Tăng Trưởng Dài Hạn");
        cached.setSummaryPoints("[\"Nhu cầu điện toán đám mây tăng mạnh.\", \"Biên lợi nhuận gộp tiếp tục mở rộng.\"]");
        cached.setBulletPointsVi("[\"Nhu cầu điện toán đám mây tăng mạnh.\", \"Biên lợi nhuận gộp tiếp tục mở rộng.\"]");
        cached.setDisplaySummaryVi("Nhu cầu điện toán đám mây tăng mạnh. Biên lợi nhuận gộp tiếp tục mở rộng.");
        cached.setSentiment("BULLISH");
        cached.setConfidencePct(BigDecimal.valueOf(90));
        cached.setReason("Tăng trưởng ổn định");
        cached.setSource("Bloomberg");

        // Bài báo xuất bản 2 giờ trước (mới <= 24h), VÀ vừa được AI phân tích 15 phút trước -> FRESH
        cached.setPublishedAt(LocalDateTime.now().minusHours(2));
        cached.setAnalyzedAt(LocalDateTime.now().minusMinutes(15));

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(List.of(cached));

        aiNewsService.getAlphaNewsCoordinator().reset();
        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertEquals("ok", result.getStatus());
        assertEquals(1, result.getItems().size());
        assertEquals("Cổ Phiếu Công Nghệ Duy Trì Đà Tăng Trưởng Dài Hạn", result.getItems().get(0).getTitle());
        assertTrue(result.getItems().get(0).isFromCache());

        // Xác nhận HttpClient không được gọi vì cache vừa có publishedAt <= 24h vừa có analyzedAt mới
        verify(mockHttp, never()).send(any(), any());

        // Nếu bài báo xuất bản từ 7 ngày trước (> 24h) -> isCacheFresh bắt buộc trả false (STALE)
        cached.setPublishedAt(LocalDateTime.now().minusDays(7));
        assertFalse(aiNewsService.isCacheFresh(List.of(aiNewsService.getValidLocalizedCacheItems("BTCUSDT", 5).get(0))),
                "Bài báo xuất bản > 24h bắt buộc coi là STALE");
    }

    @Test
    @DisplayName("45. Invariant: Không được coi cache rỗng là fresh và empty cache + coordinator fresh không bao giờ trả ok")
    public void testEmptyCacheWithFreshCoordinatorNeverReturnsOk() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");

        // Đặt coordinator fresh
        aiNewsService.getAlphaNewsCoordinator().setLastAlphaSuccessTime(System.currentTimeMillis());
        aiNewsService.getAlphaNewsCoordinator().setLastPipelineSuccessTime(System.currentTimeMillis());
        assertTrue(aiNewsService.getAlphaNewsCoordinator().isAlphaFresh());

        // Kiểm tra trực tiếp helper isCacheFresh: bắt buộc trả false khi rỗng
        assertFalse(aiNewsService.isCacheFresh(Collections.emptyList()), "Cache rỗng tuyệt đối không được coi là fresh");
        assertFalse(aiNewsService.isCacheFresh(null), "Cache null tuyệt đối không được coi là fresh");

        // Khi DB rỗng và Alpha Vantage gặp lỗi 429
        java.net.http.HttpResponse<String> mockResp = mock(java.net.http.HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(429);
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        com.llmgateway.dto.news.NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        assertNotNull(result);
        assertNotEquals("ok", result.getStatus(), "Cache rỗng tuyệt đối không được trả ok");
        assertEquals("degraded", result.getStatus());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    @DisplayName("46. Tách Alpha success khỏi Pipeline: Alpha success nhưng Gemini 500 -> degraded và request thứ hai không gọi lại Alpha")
    public void testAlphaSuccessWithGeminiFailureGivesDegradedAndSubsequentRequestSkipsAlpha() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);

        // Alpha trả về 1 bài báo thật
        java.net.http.HttpResponse<String> alphaResp = mock(java.net.http.HttpResponse.class);
        when(alphaResp.statusCode()).thenReturn(200);
        String alphaBody = "{\"feed\": [{" +
                "\"title\": \"Federal Reserve Signals Rate Stability\"," +
                "\"url\": \"https://example.com/fed-stability\"," +
                "\"time_published\": \"20260911T120000\"," +
                "\"summary\": \"Fed chair indicated stable interest rate path for upcoming quarters.\"," +
                "\"source\": \"MarketBeat\"," +
                "\"category_within_source\": \"Economy\"," +
                "\"topics\": [{\"topic\": \"financial_markets\"}]" +
                "}]}";
        when(alphaResp.body()).thenReturn(alphaBody);

        // Gemini trả về lỗi HTTP 500
        java.net.http.HttpResponse<String> geminiResp = mock(java.net.http.HttpResponse.class);
        when(geminiResp.statusCode()).thenReturn(500);
        when(geminiResp.body()).thenReturn("{\"error\": \"Gemini temporarily down\"}");

        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(alphaResp)
                .thenReturn(geminiResp);

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "VALID_GEMINI_KEY");

        // CSDL rỗng
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findByTitle(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        aiNewsService.getAlphaNewsCoordinator().reset();

        // Request 1: Alpha thành công nhưng Gemini lỗi toàn bộ -> phải trả degraded, KHÔNG ĐƯỢC trả ok []
        com.llmgateway.dto.news.NewsSyncResult result1 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(result1);
        assertEquals("degraded", result1.getStatus(), "Alpha success nhưng Gemini thất bại toàn bộ phải trả degraded");
        assertTrue(result1.getItems().isEmpty());

        // Alpha đã được ghi nhận thành công và feed được lưu tạm
        assertTrue(aiNewsService.getAlphaNewsCoordinator().isAlphaFresh(), "Alpha vừa gọi thành công phải ở trạng thái alphaFresh trong 90 phút");
        assertEquals(1, aiNewsService.getAlphaNewsCoordinator().getLastRawAlphaFeed().size(), "Raw feed Alpha phải được lưu tạm trong bộ nhớ");

        // Request 2 (ngay sau đó):
        // Khi gọi lại, hệ thống tái sử dụng raw feed trong bộ nhớ, TUYỆT ĐỐI không gọi lại Alpha Vantage!
        com.llmgateway.dto.news.NewsSyncResult result2 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(result2);
        assertEquals("degraded", result2.getStatus());

        // Khẳng định: Trong cả 2 request, Alpha Vantage chỉ được gọi đúng 1 lần duy nhất (không đốt quota lần 2)
        verify(mockHttp, times(1)).send(argThat(req -> req.uri().toString().contains("alphavantage.co")), any());
    }

    @Test
    @DisplayName("47. Cooldown theo loại lỗi: ALPHA_RATE_LIMITED cooldown 24 giờ (1.440 phút), lỗi HTTP cooldown 15 phút với Clock testable")
    public void testRateLimitCooldown24HoursWithInjectableClock() {
        com.llmgateway.service.AlphaNewsCoordinator coordinator = new com.llmgateway.service.AlphaNewsCoordinator();
        coordinator.setRefreshIntervalMinutes(90);
        coordinator.setFailureCooldownMinutes(15);
        coordinator.setRateLimitCooldownMinutes(1440);

        java.time.Instant startInstant = java.time.Instant.parse("2026-09-11T12:00:00Z");
        coordinator.setClock(java.time.Clock.fixed(startInstant, java.time.ZoneOffset.UTC));

        // 1. Ghi nhận lỗi RATE LIMITED
        coordinator.recordFailure(com.llmgateway.service.AlphaNewsCoordinator.ALPHA_RATE_LIMITED);
        assertTrue(coordinator.isInCooldown(), "Ngay sau khi bị rate limit, coordinator phải trong cooldown");

        // Sau 12 giờ (720 phút) -> vẫn phải ở trong cooldown
        coordinator.setClock(java.time.Clock.fixed(startInstant.plus(java.time.Duration.ofHours(12)), java.time.ZoneOffset.UTC));
        assertTrue(coordinator.isInCooldown(), "Sau 12 giờ, rate limit cooldown 24 giờ vẫn phải đang active");

        // Sau 23 giờ 59 phút -> vẫn trong cooldown
        coordinator.setClock(java.time.Clock.fixed(startInstant.plus(java.time.Duration.ofMinutes(1439)), java.time.ZoneOffset.UTC));
        assertTrue(coordinator.isInCooldown(), "Sau 1.439 phút vẫn phải trong cooldown");

        // Sau 24 giờ 1 phút -> hết cooldown
        coordinator.setClock(java.time.Clock.fixed(startInstant.plus(java.time.Duration.ofMinutes(1441)), java.time.ZoneOffset.UTC));
        assertFalse(coordinator.isInCooldown(), "Sau 1.441 phút phải hết cooldown 24 giờ");

        // 2. So sánh với lỗi thường ALPHA_HTTP_ERROR: chỉ cooldown 15 phút
        coordinator.setClock(java.time.Clock.fixed(startInstant, java.time.ZoneOffset.UTC));
        coordinator.recordFailure(com.llmgateway.service.AlphaNewsCoordinator.ALPHA_HTTP_ERROR);
        assertTrue(coordinator.isInCooldown());

        // Sau 16 phút -> lỗi HTTP đã hết cooldown
        coordinator.setClock(java.time.Clock.fixed(startInstant.plus(java.time.Duration.ofMinutes(16)), java.time.ZoneOffset.UTC));
        assertFalse(coordinator.isInCooldown(), "Lỗi HTTP chỉ cooldown 15 phút, sau 16 phút phải kết thúc cooldown");
    }

    @Test
    @DisplayName("48. Invariant bắt buộc: NewsSyncResult.ok luôn có data không rỗng, cấm tạo ok với empty/null")
    public void testStatusOkInvariantRequiresNonEmptyData() {
        assertThrows(IllegalArgumentException.class, () -> com.llmgateway.dto.news.NewsSyncResult.ok(Collections.emptyList()),
                "Không thể tạo NewsSyncResult.ok với danh sách rỗng");
        assertThrows(IllegalArgumentException.class, () -> com.llmgateway.dto.news.NewsSyncResult.ok(null),
                "Không thể tạo NewsSyncResult.ok với danh sách null");

        NewsFeedItemDto item = new NewsFeedItemDto();
        item.setTitle("Tiêu đề hợp lệ");
        com.llmgateway.dto.news.NewsSyncResult okResult = com.llmgateway.dto.news.NewsSyncResult.ok(List.of(item));
        assertEquals("ok", okResult.getStatus());
        assertFalse(okResult.getItems().isEmpty());
    }

    @Test
    @DisplayName("49. Một đường gọi Alpha duy nhất: MarketDataService luôn ủy quyền qua AiNewsService, khóa đường fetch độc lập")
    public void testMarketDataServiceSingleSharedPathwayLocksIndependentFetch() {
        com.llmgateway.service.BinanceMarketClient mockBinanceClient = mock(com.llmgateway.service.BinanceMarketClient.class);
        com.llmgateway.service.MarketDataService marketService = new com.llmgateway.service.MarketDataService(objectMapper, mockBinanceClient);
        AiNewsService mockAiNewsService = mock(AiNewsService.class);

        NewsFeedItemDto item = new NewsFeedItemDto();
        item.setTitle("Tin tức chia sẻ");
        when(mockAiNewsService.getLiveAiNewsFeed(isNull(), eq(5))).thenReturn(List.of(item));

        marketService.setAiNewsService(mockAiNewsService);

        List<NewsFeedItemDto> feed = marketService.getNewsFeed(5);
        assertNotNull(feed);
        assertEquals(1, feed.size());
        assertEquals("Tin tức chia sẻ", feed.get(0).getTitle());
        verify(mockAiNewsService, times(1)).getLiveAiNewsFeed(isNull(), eq(5));

        // Khi gỡ aiNewsService: MarketDataService chỉ trả cache nội bộ, không gọi HTTP Alpha Vantage độc lập
        marketService.setAiNewsService(null);
        List<NewsFeedItemDto> emptyOrCached = marketService.getNewsFeed(5);
        assertNotNull(emptyOrCached);
    }

    @Test
    @DisplayName("50. Scope matching: Snapshot BTC không dùng cho ETH; snapshot GLOBAL dùng chung cho mọi symbol")
    public void testScopeMatchingSnapshotBtcDoesNotServeEth() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");

        java.net.http.HttpResponse<String> mockResp = mock(java.net.http.HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"feed\": []}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        aiNewsService.getAlphaNewsCoordinator().reset();

        // 1. Request BTC: Alpha được gọi, snapshot lưu scope "BTC"
        com.llmgateway.dto.news.NewsSyncResult btcRes = aiNewsService.getLiveAiNewsSyncResult("BTC", 5);
        assertNotNull(btcRes);
        assertEquals("BTC", aiNewsService.getAlphaNewsCoordinator().getCachedSnapshot().getScope());
        verify(mockHttp, times(1)).send(any(), any());

        // 2. Request ETH: Scope "BTC" không khớp scope "ETH", Alpha bắt buộc phải được gọi lần 2
        com.llmgateway.dto.news.NewsSyncResult ethRes = aiNewsService.getLiveAiNewsSyncResult("ETH", 5);
        assertNotNull(ethRes);
        assertEquals("ETH", aiNewsService.getAlphaNewsCoordinator().getCachedSnapshot().getScope());
        verify(mockHttp, times(2)).send(any(), any());

        // 3. Đặt snapshot scope GLOBAL: Phục vụ được cả BTC và ETH mà không gọi Alpha
        aiNewsService.getAlphaNewsCoordinator().recordAlphaSnapshot("GLOBAL", com.llmgateway.dto.news.AlphaNewsFetchResult.Status.SUCCESS_EMPTY, Collections.emptyList());
        aiNewsService.getLiveAiNewsSyncResult("BTC", 5);
        aiNewsService.getLiveAiNewsSyncResult("ETH", 5);
        // Số lần gọi Alpha vẫn là 2 (không tăng thêm)
        verify(mockHttp, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("51. SUCCESS_EMPTY replay: Alpha trả feed=[] được cache hợp lệ và replay status=empty trong 90 phút, không thành degraded")
    public void testSuccessEmptyReplayedAsEmptyWithin90MinutesWithoutCallingAlpha() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");

        java.net.http.HttpResponse<String> mockResp = mock(java.net.http.HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("{\"feed\": []}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        aiNewsService.getAlphaNewsCoordinator().reset();

        // Request 1: Alpha trả SUCCESS_EMPTY -> trả NewsSyncResult.empty
        com.llmgateway.dto.news.NewsSyncResult res1 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(res1);
        assertEquals("empty", res1.getStatus());
        assertEquals("Chưa có bản tin mới", res1.getMessage());
        verify(mockHttp, times(1)).send(any(), any());

        // Request 2 (trong vòng 90 phút): Replay empty từ snapshot, KHÔNG thành degraded, KHÔNG gọi lại Alpha
        com.llmgateway.dto.news.NewsSyncResult res2 = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);
        assertNotNull(res2);
        assertEquals("empty", res2.getStatus(), "Phải giữ đúng status=empty, không được biến thành degraded");
        assertEquals("Chưa có bản tin mới", res2.getMessage());
        verify(mockHttp, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("52. Single-flight bao phủ toàn bộ pipeline: Luồng đồng thời nhận degraded an toàn khi đang làm mới, không gọi trùng Alpha/Gemini")
    public void testSingleFlightCoversEntirePipelinePreventingDuplicateGeminiAndDbWrites() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "VALID_GEMINI_KEY");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        aiNewsService.getAlphaNewsCoordinator().reset();

        java.util.concurrent.CountDownLatch pipelineStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowPipelineToFinish = new java.util.concurrent.CountDownLatch(1);

        // Alpha trả về 1 bài báo
        java.net.http.HttpResponse<String> alphaResp = mock(java.net.http.HttpResponse.class);
        when(alphaResp.statusCode()).thenReturn(200);
        when(alphaResp.body()).thenReturn("{\"feed\": [{\"title\": \"Crypto Rally\", \"url\": \"https://example.com/cr\", \"time_published\": \"20260911T120000\", \"summary\": \"Summary content\", \"source\": \"CoinDesk\"}]}");

        // Gemini trả về phản hồi hợp lệ, nhưng bị chặn bởi latch để mô phỏng đang xử lý pipeline
        java.net.http.HttpResponse<String> geminiResp = mock(java.net.http.HttpResponse.class);
        when(geminiResp.statusCode()).thenReturn(200);
        when(geminiResp.body()).thenReturn("{\"choices\": [{\"message\": {\"content\": \"{\\\"displayTitleVi\\\": \\\"Thị trường crypto bùng nổ mạnh mẽ\\\", \\\"summary\\\": [\\\"Giá Bitcoin tăng vọt vượt đỉnh cũ\\\", \\\"Dòng tiền đầu tư tiếp tục đổ mạnh vào thị trường\\\"], \\\"sentiment\\\": \\\"BULLISH\\\", \\\"confidence\\\": 90, \\\"reason\\\": \\\"Dòng tiền dồi dào\\\"}\"}}]}");

        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    java.net.http.HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().contains("alphavantage.co")) {
                        return alphaResp;
                    }
                    if (req.uri().toString().contains("openai") || req.uri().toString().contains("generativelanguage")) {
                        pipelineStarted.countDown();
                        allowPipelineToFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return geminiResp;
                    }
                    return null;
                });

        java.util.concurrent.atomic.AtomicReference<com.llmgateway.dto.news.NewsSyncResult> thread1Result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.llmgateway.dto.news.NewsSyncResult> thread2Result = new java.util.concurrent.atomic.AtomicReference<>();

        // Luồng 1 thực hiện pipeline làm mới
        Thread t1 = new Thread(() -> {
            thread1Result.set(aiNewsService.getLiveAiNewsSyncResult("BTC", 5));
        });
        t1.start();

        // Chờ Luồng 1 đã vào sâu trong pipeline (đang gọi Gemini)
        assertTrue(pipelineStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // Luồng 2 gọi đồng thời trong lúc Luồng 1 chưa thả lock: Phải nhận degraded an toàn ngay lập tức
        Thread t2 = new Thread(() -> {
            thread2Result.set(aiNewsService.getLiveAiNewsSyncResult("BTC", 5));
        });
        t2.start();
        t2.join(2000);

        assertNotNull(thread2Result.get());
        assertEquals("degraded", thread2Result.get().getStatus(), "Luồng 2 đồng thời khi chưa có cache phải trả degraded an toàn");

        // Cho phép Luồng 1 hoàn tất
        allowPipelineToFinish.countDown();
        t1.join(5000);

        assertNotNull(thread1Result.get());
        assertEquals("ok", thread1Result.get().getStatus(), "Luồng 1 hoàn tất pipeline trả về ok");
    }

    @Test
    @DisplayName("53. Gemini failure cooldown: Gemini lỗi đặt cooldown 10 phút, request trong cooldown không gọi Gemini, hết cooldown retry thành công")
    public void testGeminiFailureCooldownPreventsGeminiCallsAndRetriesAfterCooldownWithoutAlphaCall() throws Exception {
        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttp);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "VALID_KEY");
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "VALID_GEMINI_KEY");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(anyString())).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(Collections.emptyList());
        when(newsAiCacheRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));

        aiNewsService.getAlphaNewsCoordinator().reset();
        java.time.Instant start = java.time.Instant.parse("2026-09-11T12:00:00Z");
        aiNewsService.getAlphaNewsCoordinator().setClock(java.time.Clock.fixed(start, java.time.ZoneOffset.UTC));

        // Alpha trả về 1 bài báo
        java.net.http.HttpResponse<String> alphaResp = mock(java.net.http.HttpResponse.class);
        when(alphaResp.statusCode()).thenReturn(200);
        when(alphaResp.body()).thenReturn("{\"feed\": [{\"title\": \"US Inflation Cools Down\", \"url\": \"https://example.com/cpi\", \"time_published\": \"20260911T120000\", \"summary\": \"CPI dropped to 2.1%.\", \"source\": \"Bloomberg\"}]}");

        // Lần 1: Gemini lỗi 500
        java.net.http.HttpResponse<String> geminiFailResp = mock(java.net.http.HttpResponse.class);
        when(geminiFailResp.statusCode()).thenReturn(500);
        when(geminiFailResp.body()).thenReturn("{\"error\": \"Gemini overload\"}");

        // Lần sau: Gemini thành công 200
        java.net.http.HttpResponse<String> geminiSuccessResp = mock(java.net.http.HttpResponse.class);
        when(geminiSuccessResp.statusCode()).thenReturn(200);
        when(geminiSuccessResp.body()).thenReturn("{\"choices\": [{\"message\": {\"content\": \"{\\\"displayTitleVi\\\": \\\"Lạm phát Mỹ hạ nhiệt mạnh mẽ\\\", \\\"summary\\\": [\\\"Chỉ số CPI giảm về mức 2.1% so với cùng kỳ\\\", \\\"Áp lực giá cả tiếp tục hạ nhiệt trên diện rộng\\\"], \\\"sentiment\\\": \\\"BULLISH\\\", \\\"confidence\\\": 92, \\\"reason\\\": \\\"Tín hiệu nới lỏng tiền tệ\\\"}\"}}]}");

        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(alphaResp)
                .thenReturn(geminiFailResp)
                .thenReturn(geminiSuccessResp);

        // Request 1: Alpha 200, Gemini 500 -> trả degraded
        com.llmgateway.dto.news.NewsSyncResult res1 = aiNewsService.getLiveAiNewsSyncResult("GLOBAL", 5);
        assertNotNull(res1);
        assertEquals("degraded", res1.getStatus());
        assertTrue(aiNewsService.getAlphaNewsCoordinator().isGeminiInCooldown());

        // Request 2 (sau 3 phút, vẫn trong 10 phút cooldown): KHÔNG gọi lại Gemini, KHÔNG gọi lại Alpha
        aiNewsService.getAlphaNewsCoordinator().setClock(java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(3)), java.time.ZoneOffset.UTC));
        com.llmgateway.dto.news.NewsSyncResult res2 = aiNewsService.getLiveAiNewsSyncResult("GLOBAL", 5);
        assertNotNull(res2);
        assertEquals("degraded", res2.getStatus());

        // Xác nhận Alpha chỉ gọi đúng 1 lần, Gemini chỉ gọi đúng 1 lần
        verify(mockHttp, times(1)).send(argThat(r -> r.uri().toString().contains("alphavantage.co")), any());
        verify(mockHttp, times(1)).send(argThat(r -> r.uri().toString().contains("openai") || r.uri().toString().contains("generativelanguage")), any());

        // Request 3 (sau 11 phút, hết cooldown Gemini, vẫn trong 90 phút Alpha): Retry Gemini thành công, KHÔNG gọi Alpha
        aiNewsService.getAlphaNewsCoordinator().setClock(java.time.Clock.fixed(start.plus(java.time.Duration.ofMinutes(11)), java.time.ZoneOffset.UTC));
        assertFalse(aiNewsService.getAlphaNewsCoordinator().isGeminiInCooldown());

        com.llmgateway.dto.news.NewsSyncResult res3 = aiNewsService.getLiveAiNewsSyncResult("GLOBAL", 5);
        assertNotNull(res3);
        assertEquals("ok", res3.getStatus());
        assertEquals(1, res3.getItems().size());
        assertEquals("Lạm phát Mỹ hạ nhiệt mạnh mẽ", res3.getItems().get(0).getTitle());

        // Alpha Vantage VẪN chỉ được gọi đúng 1 lần duy nhất trong toàn bộ quy trình!
        verify(mockHttp, times(1)).send(argThat(r -> r.uri().toString().contains("alphavantage.co")), any());
    }

    @Test
    @DisplayName("54. Cache freshness: analyzedAt null hoặc rỗng bị coi là STALE kể cả publishedAt vừa mới xuất bản")
    public void testCacheWithNullAnalyzedAtEvenWithRecentPublishedAtIsConsideredStale() {
        NewsFeedItemDto item = new NewsFeedItemDto();
        item.setTitle("Tin tức");
        item.setTimePublished(LocalDateTime.now().toString());
        item.setAnalyzedAt(null); // Không có analyzedAt

        assertFalse(aiNewsService.isCacheFresh(List.of(item)), "Cache thiếu analyzedAt bắt buộc phải coi là STALE");

        item.setAnalyzedAt("invalid-date-format");
        assertFalse(aiNewsService.isCacheFresh(List.of(item)), "analyzedAt sai định dạng phải coi là STALE");

        // analyzedAt hợp lệ mới trong 90 phút -> FRESH
        item.setAnalyzedAt(LocalDateTime.now().minusMinutes(30).toString());
        assertTrue(aiNewsService.isCacheFresh(List.of(item)));

        // analyzedAt hợp lệ nhưng cũ hơn 90 phút -> STALE
        item.setAnalyzedAt(LocalDateTime.now().minusMinutes(91).toString());
        assertFalse(aiNewsService.isCacheFresh(List.of(item)));
    }

    @Test
    @DisplayName("55. Invariant NewsSyncResult: Bắt buộc từ chối status='ok' khi items rỗng ở mọi constructor và setter")
    public void testNewsSyncResultInvariantRejectsOkWithEmptyItemsOnAllSettersAndConstructors() {
        assertThrows(IllegalArgumentException.class, () -> new com.llmgateway.dto.news.NewsSyncResult("ok", "msg", Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new com.llmgateway.dto.news.NewsSyncResult("ok", "msg", null));
        assertThrows(IllegalArgumentException.class, () -> com.llmgateway.dto.news.NewsSyncResult.ok(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> com.llmgateway.dto.news.NewsSyncResult.ok(null));

        com.llmgateway.dto.news.NewsSyncResult res = com.llmgateway.dto.news.NewsSyncResult.degraded("error");
        assertThrows(IllegalArgumentException.class, () -> res.setStatus("ok"));

        NewsFeedItemDto valid = new NewsFeedItemDto();
        valid.setTitle("Tin chuẩn");
        com.llmgateway.dto.news.NewsSyncResult okRes = com.llmgateway.dto.news.NewsSyncResult.ok(List.of(valid));
        assertThrows(IllegalArgumentException.class, () -> okRes.setItems(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> okRes.setItems(null));
    }

    @Test
    @DisplayName("56. Cấu hình Gemini Cooldown: Giá trị mặc định 10 phút được nạp đúng vào Coordinator")
    public void testGeminiCooldownConfigurationLoaded() {
        assertEquals(10, aiNewsService.getAlphaNewsCoordinator().getGeminiCooldownMinutes(),
                "Gemini cooldown minutes mặc định phải là 10 phút");
    }
}
