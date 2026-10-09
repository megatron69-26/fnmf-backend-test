package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.dto.stock.StockCatalogDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.entity.Wallet;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.exception.UnsupportedSymbolException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.BinanceMarketClient;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.StockMarketService;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.service.ForecastCacheService;
import com.llmgateway.service.ForecastQualityPolicy;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.GeminiForecastClient;
import com.llmgateway.service.TradeOrderExecutor;
import com.llmgateway.service.TradeService;
import com.llmgateway.service.provider.AlpacaStockDataProvider;
import com.llmgateway.service.provider.AlphaVantageStockDataProvider;
import com.llmgateway.service.provider.BinanceMarketDataProvider;
import com.llmgateway.service.provider.FixedMarketCacheManager;
import com.llmgateway.service.provider.FixedMarketProviderRouter;
import com.llmgateway.service.provider.GeminiShardRouter;
import com.llmgateway.service.provider.TwelveDataStockDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FixedProviderShardingTest {

    private ObjectMapper objectMapper;
    private NewsAiCacheRepository newsAiCacheRepository;
    private HttpClient mockHttpClient;

    private BinanceMarketClient mockBinanceClient;
    private BinanceMarketDataProvider binanceProvider;
    private AlpacaStockDataProvider alpacaProvider;
    private TwelveDataStockDataProvider twelveDataProvider;
    private AlphaVantageStockDataProvider alphaVantageProvider;

    private FixedMarketProviderRouter router;
    private FixedMarketCacheManager cacheManager;
    private GeminiShardRouter geminiRouter;
    private StockMarketService stockMarketService;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        mockHttpClient = mock(HttpClient.class);

        mockBinanceClient = mock(BinanceMarketClient.class);
        binanceProvider = new BinanceMarketDataProvider(mockBinanceClient);

        alpacaProvider = new AlpacaStockDataProvider(objectMapper, mockHttpClient);
        alpacaProvider.setCredentials("mock-alpaca-key", "mock-alpaca-secret");

        twelveDataProvider = new TwelveDataStockDataProvider(objectMapper, mockHttpClient);
        twelveDataProvider.setApiKey("mock-twelve-key");

        alphaVantageProvider = new AlphaVantageStockDataProvider(objectMapper, mockHttpClient);
        alphaVantageProvider.setApiKey("mock-alpha-key");

        router = new FixedMarketProviderRouter(binanceProvider, alpacaProvider, twelveDataProvider, alphaVantageProvider);
        cacheManager = new FixedMarketCacheManager();

        geminiRouter = new GeminiShardRouter();
        geminiRouter.setShardKeys("mock-shard-1", "mock-shard-2", "mock-shard-3");

        stockMarketService = new StockMarketService(objectMapper, newsAiCacheRepository, router, cacheManager, geminiRouter, mockHttpClient);
    }

    // =========================================================================
    // 1. FIXED PROVIDER MAPPING TESTS (KHÔNG GỌI NHẦM, KHÔNG FAILOVER)
    // =========================================================================

    @Test
    @DisplayName("Từng symbol định tuyến cố định chính xác vào đúng Market Data Provider")
    public void testFixedProviderRouting() {
        // Binance: BTCUSDT, ETHUSDT, XAUUSD
        assertEquals("BINANCE", router.resolveProviderName("BTCUSDT"));
        assertEquals("BINANCE", router.resolveProviderName("ETHUSDT"));
        assertEquals("BINANCE", router.resolveProviderName("XAUUSD"));

        // Alpaca: AAPL, MSFT, NVDA, GOOGL
        assertEquals("ALPACA", router.resolveProviderName("AAPL"));
        assertEquals("ALPACA", router.resolveProviderName("MSFT"));
        assertEquals("ALPACA", router.resolveProviderName("NVDA"));
        assertEquals("ALPACA", router.resolveProviderName("GOOGL"));

        // Twelve Data: TSLA, AMZN, META, JPM
        assertEquals("TWELVE_DATA", router.resolveProviderName("TSLA"));
        assertEquals("TWELVE_DATA", router.resolveProviderName("AMZN"));
        assertEquals("TWELVE_DATA", router.resolveProviderName("META"));
        assertEquals("TWELVE_DATA", router.resolveProviderName("JPM"));

        // Unsupported symbol throws exception
        assertThrows(UnsupportedSymbolException.class, () -> router.getProvider("UNKNOWN"));
        assertThrows(UnsupportedSymbolException.class, () -> router.getProvider("USOIL"));
    }

    @Test
    @DisplayName("Gemini Shard Router định tuyến chính xác 3 shards cố định theo symbol")
    public void testGeminiShardRouting() {
        // Shard 1: BTCUSDT, ETHUSDT, XAUUSD
        assertEquals(GeminiShardRouter.SHARD_1, geminiRouter.resolveShardName("BTCUSDT"));
        assertEquals(GeminiShardRouter.SHARD_1, geminiRouter.resolveShardName("ETHUSDT"));
        assertEquals(GeminiShardRouter.SHARD_1, geminiRouter.resolveShardName("XAUUSD"));
        assertEquals("mock-shard-1", geminiRouter.resolveShard("BTCUSDT").apiKey());

        // Shard 2: AAPL, MSFT, NVDA, GOOGL
        assertEquals(GeminiShardRouter.SHARD_2, geminiRouter.resolveShardName("AAPL"));
        assertEquals(GeminiShardRouter.SHARD_2, geminiRouter.resolveShardName("MSFT"));
        assertEquals(GeminiShardRouter.SHARD_2, geminiRouter.resolveShardName("NVDA"));
        assertEquals(GeminiShardRouter.SHARD_2, geminiRouter.resolveShardName("GOOGL"));
        assertEquals("mock-shard-2", geminiRouter.resolveShard("AAPL").apiKey());

        // Shard 3: TSLA, AMZN, META, JPM
        assertEquals(GeminiShardRouter.SHARD_3, geminiRouter.resolveShardName("TSLA"));
        assertEquals(GeminiShardRouter.SHARD_3, geminiRouter.resolveShardName("AMZN"));
        assertEquals(GeminiShardRouter.SHARD_3, geminiRouter.resolveShardName("META"));
        assertEquals(GeminiShardRouter.SHARD_3, geminiRouter.resolveShardName("JPM"));
        assertEquals("mock-shard-3", geminiRouter.resolveShard("TSLA").apiKey());
    }

    // =========================================================================
    // 2. CATALOG & ISOLATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Catalog 5 cặp Binance là danh mục tĩnh, tuyệt đối không gọi provider")
    public void testCatalogDoesNotCallProvider() throws Exception {
        List<StockCatalogDto> catalog = stockMarketService.getStockCatalog();
        assertNotNull(catalog);
        assertEquals(5, catalog.size());

        // Verify zero HTTP calls made
        verify(mockHttpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Khi chọn một mã, chỉ tải dữ liệu của đúng mã đó")
    public void testSelectedSymbolOnlyFetchesTargetSymbol() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T14:00:00Z","o":220.0,"h":225.0,"l":219.0,"c":224.0,"v":10000}
                        ]
                    }
                }
                """);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        stockMarketService.fetchAndCacheStock("AAPL", "1m");

        // Verify AAPL was cached
        assertNotNull(cacheManager.getPriceEntry("ALPACA", "AAPL"));
        // Verify MSFT or other symbols were NOT touched
        assertNull(cacheManager.getPriceEntry("ALPACA", "MSFT"));
        assertNull(cacheManager.getPriceEntry("TWELVE_DATA", "TSLA"));
    }

    // =========================================================================
    // 3. CACHE ISOLATION & TTL TESTS
    // =========================================================================

    @Test
    @DisplayName("Cache phân tách độc lập theo provider, symbol và interval")
    public void testCacheIsolationByProviderSymbolInterval() {
        String keyAapl1m = cacheManager.buildCandleKey("ALPACA", "AAPL", "1m");
        String keyAaplDaily = cacheManager.buildCandleKey("ALPACA", "AAPL", "daily");
        String keyTsla1m = cacheManager.buildCandleKey("TWELVE_DATA", "TSLA", "1m");

        assertEquals("ALPACA:AAPL:1m", keyAapl1m);
        assertEquals("ALPACA:AAPL:daily", keyAaplDaily);
        assertEquals("TWELVE_DATA:TSLA:1m", keyTsla1m);

        assertNotEquals(keyAapl1m, keyAaplDaily);
        assertNotEquals(keyAapl1m, keyTsla1m);

        assertEquals(60_000L, cacheManager.resolveCandleTtl("1m"));
        assertEquals(6 * 3600 * 1000L, cacheManager.resolveCandleTtl("daily"));
        assertEquals(30_000L, FixedMarketCacheManager.PRICE_TTL_MS);
        assertEquals(6 * 3600 * 1000L, FixedMarketCacheManager.NEWS_TTL_MS);
    }

    // =========================================================================
    // 4. RATE LIMITING 429 & NO FAILOVER TESTS
    // =========================================================================

    @Test
    @DisplayName("Khi provider bị 429, không failover sang provider khác và kích hoạt cooldown")
    public void test429DoesNotFailover() throws Exception {
        HttpResponse<String> mock429 = mock(HttpResponse.class);
        when(mock429.statusCode()).thenReturn(429);
        doReturn(mock429).when(mockHttpClient).send(any(HttpRequest.class), any());

        // Gọi TSLA (Twelve Data) bị 429 -> không chuyển sang Alpaca hay Alpha Vantage
        assertThrows(MarketDataUnavailableException.class, () -> {
            twelveDataProvider.getCandles("TSLA", "1m", 10);
        });

        // Provider tiếp tục từ chối trong cooldown
        assertThrows(MarketDataUnavailableException.class, () -> {
            twelveDataProvider.getCandles("TSLA", "1m", 10);
        });

        // Xác nhận router vẫn chỉ trả về TWELVE_DATA cho TSLA, không failover
        assertEquals("TWELVE_DATA", router.resolveProviderName("TSLA"));
    }

    // =========================================================================
    // 5. ZERO FAKE NEWS TESTS
    // =========================================================================

    @Test
    @DisplayName("Khi endpoint tin tức rỗng hoặc lỗi, trả danh sách rỗng, không tự bịa tin giả")
    public void testEmptyNewsDoesNotFabricateFakeNews() throws Exception {
        HttpResponse<String> mockEmpty = mock(HttpResponse.class);
        when(mockEmpty.statusCode()).thenReturn(200);
        when(mockEmpty.body()).thenReturn("{\"status\": \"ok\", \"press_releases\": []}");
        doReturn(mockEmpty).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<NewsArticleDto> news = twelveDataProvider.getLatestNews("TSLA", 5);
        assertNotNull(news);
        assertTrue(news.isEmpty(), "Tuyệt đối không sinh tin tức giả khi provider trả rỗng");
    }

    // =========================================================================
    // 6. TRADE INTEGRITY TESTS (TỪ CHỐI KHI GIÁ THIẾU HOẶC STALE)
    // =========================================================================

    @Test
    @DisplayName("Khớp lệnh giao dịch từ chối khi giá null hoặc giá stale=true")
    public void testTradeRejectsMissingOrStalePrice() {
        WalletRepository walletRepo = mock(WalletRepository.class);
        HoldingRepository holdingRepo = mock(HoldingRepository.class);
        TransactionRepository txRepo = mock(TransactionRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        TradeOrderExecutor executor = mock(TradeOrderExecutor.class);

        TradeService tradeService = new TradeService(walletRepo, holdingRepo, txRepo, marketDataService, executor);

        OrderRequest request = new OrderRequest("BNBUSDT", "BUY", BigDecimal.ONE, "client-order-uuid-1234");

        // 1. Giá null -> từ chối
        assertThrows(MarketDataUnavailableException.class, () -> {
            tradeService.executeOrder(1L, request, null);
        });

        // 2. Giá stale=true -> từ chối
        MarketPriceDto stalePrice = new MarketPriceDto("BNBUSDT", "BNB", "CRYPTO", BigDecimal.valueOf(550.0),
                BigDecimal.ZERO, BigDecimal.valueOf(550.0), BigDecimal.valueOf(550.0),
                "2026-09-16T14:00:00", true, "CACHE_BINANCE", "2026-09-16T14:00:00");

        assertThrows(MarketDataUnavailableException.class, () -> {
            tradeService.executeOrder(1L, request, stalePrice);
        });

        // 3. Giá hợp lệ (stale=false, price > 0)
        MarketPriceDto validPrice = new MarketPriceDto("BNBUSDT", "BNB", "CRYPTO", BigDecimal.valueOf(550.0),
                BigDecimal.ZERO, BigDecimal.valueOf(550.0), BigDecimal.valueOf(550.0),
                "2026-09-16T14:00:00", false, "BINANCE", "2026-09-16T14:00:00");

        Wallet wallet = new Wallet(1L, BigDecimal.valueOf(1000.0));
        when(walletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(walletRepo.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        assertDoesNotThrow(() -> {
            tradeService.executeOrder(1L, request, validPrice);
        });

        // 4. Lệnh cho cổ phiếu cũ AAPL bị từ chối với UnsupportedSymbolException
        OrderRequest aaplRequest = new OrderRequest("AAPL", "BUY", BigDecimal.ONE, "client-order-uuid-aapl");
        assertThrows(UnsupportedSymbolException.class, () -> {
            tradeService.executeOrder(1L, aaplRequest, validPrice);
        });
    }

    // =========================================================================
    // 7. BLOCKER 1: FORECAST METADATA & CACHE POLICY (ANALYSIS_SOURCE = GEMINI)
    // =========================================================================

    @Test
    @DisplayName("Blocker 1: GeminiForecastClient với từng shard luôn trả analysisSource='GEMINI', aiShard được set, validateOrThrow PASS, cache lưu được")
    public void testForecastMetadataAndShardIsolation() throws Exception {
        MarketForecastRepository mockForecastRepo = mock(MarketForecastRepository.class);
        ForecastCacheService cacheService = new ForecastCacheService(mockForecastRepo, objectMapper);

        GeminiForecastClient client = new GeminiForecastClient(objectMapper, mockHttpClient);
        client.setGeminiShardRouter(geminiRouter);

        String validAiJson = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\\"trendPrediction\\":\\"BULLISH_UPTREND\\",\\"supportLevel\\":220.0,\\"resistanceLevel\\":240.0,\\"recommendation\\":\\"BUY\\",\\"confidenceScore\\":85,\\"keyDrivers\\":[\\"Giá tiếp tục giữ vững trên vùng hỗ trợ ngắn hạn.\\",\\"Áp lực bán giảm dần trong các phiên điều chỉnh.\\",\\"Cấu trúc thị trường duy trì đà hồi phục tích cực.\\"],\\"technicalOutlook\\":\\"Giá đang giữ vững trên vùng hỗ trợ trong khi áp lực bán suy yếu.\\",\\"fundamentalOutlook\\":\\"Tâm lý của các nhà đầu tư nhìn chung khá tích cực và kỳ vọng tăng trưởng.\\"}"
                  }
                }
              ]
            }
            """;

        HttpResponse<String> mockAiResponse = mock(HttpResponse.class);
        when(mockAiResponse.statusCode()).thenReturn(200);
        // Each mocked forecast must bracket the actual asset price under the
        // shared quality policy; one 220/240 fixture cannot represent BTC.
        String btcAiJson = validAiJson.replace("220.0", "58000.0").replace("240.0", "62000.0");
        String tslaAiJson = validAiJson.replace("240.0", "260.0").replace("220.0", "240.0");
        when(mockAiResponse.body()).thenReturn(btcAiJson, validAiJson, tslaAiJson);
        doReturn(mockAiResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<CandleDto> candles = List.of(
                new CandleDto("2026-09-16T14:00:00Z", BigDecimal.valueOf(220), BigDecimal.valueOf(225), BigDecimal.valueOf(219), BigDecimal.valueOf(224), BigDecimal.valueOf(1000))
        );

        // Shard 1 (BTCUSDT)
        MarketPriceDto btcPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", BigDecimal.valueOf(60000), BigDecimal.ZERO, false, "BINANCE");
        ForecastResponse resp1 = client.requestForecast("BTCUSDT", btcPrice, candles, Collections.emptyList(), "24H_7D");
        assertEquals("GEMINI", resp1.getAnalysisSource());
        assertEquals("GEMINI_SHARD_1", resp1.getAiShard());
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(resp1));
        cacheService.saveForecast(resp1);

        // Shard 2 (AAPL)
        MarketPriceDto aaplPrice = new MarketPriceDto("AAPL", "Apple Inc.", BigDecimal.valueOf(224), BigDecimal.ZERO, false, "ALPACA");
        ForecastResponse resp2 = client.requestForecast("AAPL", aaplPrice, candles, Collections.emptyList(), "24H_7D");
        assertEquals("GEMINI", resp2.getAnalysisSource());
        assertEquals("GEMINI_SHARD_2", resp2.getAiShard());
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(resp2));
        cacheService.saveForecast(resp2);

        // Shard 3 (TSLA)
        MarketPriceDto tslaPrice = new MarketPriceDto("TSLA", "Tesla Inc.", BigDecimal.valueOf(250), BigDecimal.ZERO, false, "TWELVE_DATA");
        ForecastResponse resp3 = client.requestForecast("TSLA", tslaPrice, candles, Collections.emptyList(), "24H_7D");
        assertEquals("GEMINI", resp3.getAnalysisSource());
        assertEquals("GEMINI_SHARD_3", resp3.getAiShard());
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(resp3));
        cacheService.saveForecast(resp3);

        // Xác nhận ForecastCacheService lưu đủ 3 bản ghi với analysisSource = "GEMINI"
        ArgumentCaptor<MarketForecast> captor = ArgumentCaptor.forClass(MarketForecast.class);
        verify(mockForecastRepo, times(3)).save(captor.capture());
        for (MarketForecast saved : captor.getAllValues()) {
            assertEquals("GEMINI", saved.getAnalysisSource());
        }
    }

    // =========================================================================
    // 8. BLOCKER 2: CẤM FALLBACK CHÉO (FAIL-CLOSED)
    // =========================================================================

    @Test
    @DisplayName("Blocker 2: Cấm fallback chéo - Shard 2 thiếu key không gọi Shard 1; Shard 3 lỗi không gọi shard khác")
    public void testZeroGeminiCrossFallback() throws Exception {
        GeminiShardRouter routerPartial = new GeminiShardRouter();
        routerPartial.setShardKeys("valid-shard-1-key", "", "valid-shard-3-key");

        GeminiForecastClient client = new GeminiForecastClient(objectMapper, mockHttpClient);
        client.setGeminiShardRouter(routerPartial);

        List<CandleDto> candles = List.of(
                new CandleDto("2026-09-16T14:00:00Z", BigDecimal.valueOf(220), BigDecimal.valueOf(225), BigDecimal.valueOf(219), BigDecimal.valueOf(224), BigDecimal.valueOf(1000))
        );
        MarketPriceDto aaplPrice = new MarketPriceDto("AAPL", "Apple Inc.", BigDecimal.valueOf(224), BigDecimal.ZERO, false, "ALPACA");

        // 1. Shard 2 (AAPL) thiếu key -> Phải fail-closed ném ForecastUnavailableException, KHÔNG gọi Shard 1
        assertThrows(ForecastUnavailableException.class, () -> {
            client.requestForecast("AAPL", aaplPrice, candles, Collections.emptyList(), "24H_7D");
        });

        // 2. Shard 3 (TSLA) gặp lỗi mạng 500 -> Fail-closed, KHÔNG thử lại bằng Shard 1 hay Shard 2
        HttpResponse<String> mockError500 = mock(HttpResponse.class);
        when(mockError500.statusCode()).thenReturn(500);
        doReturn(mockError500).when(mockHttpClient).send(any(HttpRequest.class), any());

        MarketPriceDto tslaPrice = new MarketPriceDto("TSLA", "Tesla Inc.", BigDecimal.valueOf(250), BigDecimal.ZERO, false, "TWELVE_DATA");
        assertThrows(ForecastUnavailableException.class, () -> {
            client.requestForecast("TSLA", tslaPrice, candles, Collections.emptyList(), "24H_7D");
        });

        // Verify không bao giờ gửi request mang Shard 1 key cho AAPL hoặc TSLA
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient, atMost(1)).send(requestCaptor.capture(), any());
        if (!requestCaptor.getAllValues().isEmpty()) {
            HttpRequest sent = requestCaptor.getValue();
            assertEquals("Bearer valid-shard-3-key", sent.headers().firstValue("Authorization").orElse(""));
        }
    }

    // =========================================================================
    // 9. BLOCKER 3: CACHE INTERVAL ISOLATION REGRESSION
    // =========================================================================

    @Test
    @DisplayName("Blocker 3: Phân tách độc lập cache interval (AAPL 1m -> AAPL daily -> AAPL 1m)")
    public void testIntervalCacheIsolationRegression() throws Exception {
        HttpResponse<String> mockResponse1m = mock(HttpResponse.class);
        when(mockResponse1m.statusCode()).thenReturn(200);
        when(mockResponse1m.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T14:00:00Z","o":220.0,"h":221.0,"l":219.5,"c":220.5,"v":100}
                        ]
                    }
                }
                """);

        HttpResponse<String> mockResponseDaily = mock(HttpResponse.class);
        when(mockResponseDaily.statusCode()).thenReturn(200);
        when(mockResponseDaily.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T00:00:00Z","o":220.0,"h":225.0,"l":219.0,"c":224.0,"v":60000},
                            {"t":"2026-09-15T00:00:00Z","o":215.0,"h":222.0,"l":214.0,"c":220.0,"v":50000}
                        ]
                    }
                }
                """);

        // Request 1: AAPL 1m -> trả về nến 1m
        doReturn(mockResponse1m).when(mockHttpClient).send(any(HttpRequest.class), any());
        StockMarketService.CachedStockData data1m = stockMarketService.fetchAndCacheStock("AAPL", "1m");
        assertEquals(1, data1m.getCandles().size());
        assertEquals("2026-09-16 14:00", data1m.getCandles().get(0).getTime());

        // Request 2: AAPL daily -> trả về nến daily
        doReturn(mockResponseDaily).when(mockHttpClient).send(any(HttpRequest.class), any());
        StockMarketService.CachedStockData dataDaily = stockMarketService.fetchAndCacheStock("AAPL", "daily");
        assertEquals(2, dataDaily.getCandles().size());
        assertEquals("2026-09-16", dataDaily.getCandles().get(1).getTime());

        // Request 3: AAPL 1m phải trả về đúng nến 1m từ cache, KHÔNG bị đè bởi nến daily!
        StockMarketService.CachedStockData data1mSecond = stockMarketService.fetchAndCacheStock("AAPL", "1m");
        assertEquals(1, data1mSecond.getCandles().size(), "AAPL 1m không được trả về nến daily");
        assertEquals("2026-09-16 14:00", data1mSecond.getCandles().get(0).getTime());
        assertFalse(data1mSecond.isStale());
    }

    // =========================================================================
    // 10. BLOCKER 4: REAL NEWS CACHE (6H TTL PREVENTS POLLING SPAM)
    // =========================================================================

    @Test
    @DisplayName("Blocker 4: News cache 6h - polling liên tục chỉ gọi provider news đúng 1 lần")
    public void testNewsCachePreventsProviderSpam() throws Exception {
        AlpacaStockDataProvider spyAlpaca = spy(alpacaProvider);
        doReturn(List.of(
                new NewsArticleDto("Tin tức Apple", "Tóm tắt Apple", "https://news.com/aapl", "2026-09-16T10:00:00Z", "ALPACA")
        )).when(spyAlpaca).getLatestNews(eq("AAPL"), anyInt());

        FixedMarketProviderRouter customRouter = new FixedMarketProviderRouter(binanceProvider, spyAlpaca, twelveDataProvider, alphaVantageProvider);
        StockMarketService customService = new StockMarketService(objectMapper, newsAiCacheRepository, customRouter, cacheManager, geminiRouter, mockHttpClient);

        HttpResponse<String> mockCandleResp = mock(HttpResponse.class);
        when(mockCandleResp.statusCode()).thenReturn(200);
        when(mockCandleResp.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T14:00:00Z","o":220.0,"h":225.0,"l":219.0,"c":224.0,"v":10000}
                        ]
                    }
                }
                """);
        doReturn(mockCandleResp).when(mockHttpClient).send(any(HttpRequest.class), any());

        // Poll 1
        customService.fetchAndCacheStock("AAPL", "1m");
        // Poll 2
        customService.fetchAndCacheStock("AAPL", "1m");
        // Poll 3
        customService.fetchAndCacheStock("AAPL", "1m");

        // Provider news chỉ được gọi đúng 1 lần trong 6h
        verify(spyAlpaca, times(1)).getLatestNews(eq("AAPL"), anyInt());

        // Cache news tồn tại
        FixedMarketCacheManager.CachedEntry<List<NewsArticleDto>> cachedNews = cacheManager.getNewsEntry("ALPACA", "AAPL");
        assertNotNull(cachedNews);
        assertEquals(1, cachedNews.getData().size());
    }

    // =========================================================================
    // 11. BLOCKER 5: FORECAST NEWS SYMBOL ISOLATION
    // =========================================================================

    @Test
    @DisplayName("Blocker 5: Forecast news cho đúng symbol - AAPL không bao giờ chứa tin của BTC hoặc TSLA")
    public void testForecastNewsSymbolIsolation() {
        MarketForecastRepository mockForecastRepo = mock(MarketForecastRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);
        GeminiForecastClient mockGeminiClient = mock(GeminiForecastClient.class);
        ForecastCacheService mockCacheService = mock(ForecastCacheService.class);

        NewsAiCache aaplNews = new NewsAiCache();
        aaplNews.setSymbol("AAPL");
        aaplNews.setTitle("Apple ra mắt sản phẩm mới");
        aaplNews.setSentiment("BULLISH");

        NewsAiCache btcNews = new NewsAiCache();
        btcNews.setSymbol("BTCUSDT");
        btcNews.setTitle("Bitcoin vượt đỉnh mới");
        btcNews.setSentiment("BULLISH");

        NewsAiCache tslaNews = new NewsAiCache();
        tslaNews.setSymbol("TSLA");
        tslaNews.setTitle("Tesla tăng sản lượng xe");
        tslaNews.setSentiment("NEUTRAL");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc("AAPL")).thenReturn(List.of(aaplNews));
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc("BTCUSDT")).thenReturn(List.of(btcNews));

        ForecastService forecastService = new ForecastService(
                mockForecastRepo,
                newsAiCacheRepository,
                mockMarketData,
                mockCacheService,
                mockGeminiClient,
                router,
                cacheManager
        );

        // Fetch news cho AAPL
        List<NewsAiCache> resultAapl = forecastService.fetchNewsForSymbol("AAPL");
        assertFalse(resultAapl.isEmpty());
        for (NewsAiCache n : resultAapl) {
            assertEquals("AAPL", n.getSymbol(), "Tin tức trong forecast AAPL phải thuộc về AAPL");
            assertNotEquals("BTCUSDT", n.getSymbol());
            assertNotEquals("TSLA", n.getSymbol());
        }

        // Fetch news cho mã không có tin -> trả về rỗng, tuyệt đối không lấy tin của mã khác
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc("NVDA")).thenReturn(Collections.emptyList());
        List<NewsAiCache> resultNvda = forecastService.fetchNewsForSymbol("NVDA");
        assertNotNull(resultNvda);
        assertTrue(resultNvda.isEmpty(), "Mã không có tin phải trả về list rỗng, không mượn tin mã khác");

        // Verify findTop10ByOrderByPublishedAtDesc KHÔNG BAO GIỜ được gọi
        verify(newsAiCacheRepository, never()).findTop10ByOrderByPublishedAtDesc();
    }

    // =========================================================================
    // 12. BLOCKER 6: SANITIZE (TWELVE DATA ERROR MASKING)
    // =========================================================================

    @Test
    @DisplayName("Blocker 6: Sanitize - Twelve Data không để lộ chuỗi raw error message trong exception")
    public void testTwelveDataSanitizedErrorMessage() throws Exception {
        HttpResponse<String> mockError = mock(HttpResponse.class);
        when(mockError.statusCode()).thenReturn(200);
        when(mockError.body()).thenReturn("{\"status\": \"error\", \"code\": 400, \"message\": \"apikey parameter is invalid or missing\"}");
        doReturn(mockError).when(mockHttpClient).send(any(HttpRequest.class), any());

        MarketDataUnavailableException ex = assertThrows(MarketDataUnavailableException.class, () -> {
            twelveDataProvider.getCandles("TSLA", "1m", 10);
        });

        // Exception message chỉ chứa thông báo chung, không để lộ error message raw hay API key
        assertFalse(ex.getMessage().contains("apikey"));
        assertFalse(ex.getMessage().contains("parameter is invalid"));
        assertTrue(ex.getMessage().contains("Twelve Data báo lỗi cho mã: TSLA"));
    }

    // =========================================================================
    // 13. BLOCKER RUNTIME 1: ALPACA BAR WINDOW (SORT=DESC, START DATE, OLDEST->NEWEST)
    // =========================================================================

    @Test
    @DisplayName("Runtime Blocker 1: Alpaca gọi bars với sort=desc, start date lùi ngày và trả về thứ tự tăng dần")
    public void testAlpacaBarWindowAndAscendingOrder() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T14:05:00Z","o":224.0,"h":226.0,"l":223.5,"c":225.0,"v":12000},
                            {"t":"2026-09-16T14:00:00Z","o":220.0,"h":224.5,"l":219.0,"c":224.0,"v":10000}
                        ]
                    }
                }
                """);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<CandleDto> candles = alpacaProvider.getCandles("AAPL", "1m", 30);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(requestCaptor.capture(), any());

        HttpRequest sent = requestCaptor.getValue();
        String uriStr = sent.uri().toString();
        assertTrue(uriStr.contains("sort=desc"), "URI phải có tham số sort=desc để lấy nến gần nhất");
        assertTrue(uriStr.contains("start="), "URI phải có tham số start để không bị mặc định đầu ngày");
        assertTrue(uriStr.contains("timeframe=1Min"));
        assertTrue(uriStr.contains("limit=30"));

        // Kiểm tra thứ tự nến trả về: phải là tăng dần (oldest -> newest) sau khi reverse
        assertEquals(2, candles.size());
        assertEquals("2026-09-16 14:00", candles.get(0).getTime());
        assertEquals("2026-09-16 14:05", candles.get(1).getTime());
        assertEquals(new BigDecimal("224.00"), candles.get(0).getClose());
        assertEquals(new BigDecimal("225.00"), candles.get(1).getClose());
    }

    // =========================================================================
    // 14. BLOCKER RUNTIME 3: GEMINI POLLING RETRY PROTECTION (ZERO SPAM)
    // =========================================================================

    @Test
    @DisplayName("Runtime Blocker 3: Polling liên tục không spam Gemini khi khuyến nghị đã thất bại hoặc đã tạo trong 24h")
    public void testGeminiPollingRetryProtection() throws Exception {
        // Mock Alpaca candles response
        HttpResponse<String> mockCandleResp = mock(HttpResponse.class);
        when(mockCandleResp.statusCode()).thenReturn(200);
        when(mockCandleResp.body()).thenReturn("""
                {
                    "bars": {
                        "AAPL": [
                            {"t":"2026-09-16T14:00:00Z","o":220.0,"h":225.0,"l":219.0,"c":224.0,"v":10000}
                        ]
                    }
                }
                """);

        // Mock Gemini response: lỗi 500
        HttpResponse<String> mockGemini500 = mock(HttpResponse.class);
        when(mockGemini500.statusCode()).thenReturn(500);

        when(mockHttpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest req = invocation.getArgument(0);
            if (req.uri().toString().contains("alpaca")) {
                return mockCandleResp;
            }
            // Gemini call
            return mockGemini500;
        });

        // Poll 1: Gọi Alpaca + Gọi Gemini (thất bại -> recommendation = null)
        StockMarketService.CachedStockData poll1 = stockMarketService.fetchAndCacheStock("AAPL", "1m");
        assertNull(poll1.getRecommendation());

        // Poll 2 (60 giây sau): Polling tiếp -> KHÔNG gọi Gemini lần 2
        StockMarketService.CachedStockData poll2 = stockMarketService.fetchAndCacheStock("AAPL", "1m");
        assertNull(poll2.getRecommendation());

        // Poll 4: Khi chuyển sang interval khác (daily) nạp nến mới từ Alpaca, geminiAttemptTimestampMap vẫn bảo vệ không gọi lại Gemini
        StockMarketService.CachedStockData poll4 = stockMarketService.fetchAndCacheStock("AAPL", "daily");
        assertNull(poll4.getRecommendation());

        // Xác nhận: Request gửi tới Gemini API chỉ được gọi đúng 1 lần duy nhất trong toàn bộ quy trình
        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient, atLeast(1)).send(reqCaptor.capture(), any());

        long geminiCalls = reqCaptor.getAllValues().stream()
                .filter(r -> r.uri().toString().contains("generativelanguage.googleapis.com"))
                .count();
        assertEquals(1, geminiCalls, "Chỉ được gọi Gemini đúng 1 lần khi polling liên tục hoặc nạp nến mới trong 24h");
    }

    // =========================================================================
    // 15. BLOCKER RUNTIME 4: PERSIST & RESTORE aiShard ACROSS DB CACHE
    // =========================================================================

    @Test
    @DisplayName("Runtime Blocker 4: ForecastCacheService lưu aiShard và phục hồi chính xác từ DB cache")
    public void testForecastCacheAiShardPersistenceAndRestoration() {
        MarketForecastRepository mockForecastRepo = mock(MarketForecastRepository.class);
        ForecastCacheService cacheService = new ForecastCacheService(mockForecastRepo, objectMapper, geminiRouter);

        ForecastResponse resp = new ForecastResponse(
                "AAPL",
                "Apple Inc.",
                BigDecimal.valueOf(224.0),
                "BULLISH_UPTREND",
                "24H_7D",
                BigDecimal.valueOf(220.0),
                BigDecimal.valueOf(240.0),
                "BUY",
                85,
                List.of("Động lực tăng trưởng duy trì tốt.", "Vùng hỗ trợ vững chắc.", "Khối lượng tích lũy gia tăng."),
                "Triển vọng kỹ thuật tích cực.",
                "Cơ bản ổn định.",
                "GEMINI",
                30,
                false,
                java.time.LocalDateTime.now()
        );
        resp.setAiShard("GEMINI_SHARD_2");

        // 1. Lưu dự báo
        cacheService.saveForecast(resp);

        ArgumentCaptor<MarketForecast> captor = ArgumentCaptor.forClass(MarketForecast.class);
        verify(mockForecastRepo).save(captor.capture());
        MarketForecast saved = captor.getValue();
        assertEquals("GEMINI_SHARD_2", saved.getAiShard(), "aiShard phải được lưu vào MarketForecast entity");

        // 2. Phục hồi dự báo từ cache
        when(mockForecastRepo.findTopBySymbolOrderByCreatedAtDesc("AAPL")).thenReturn(Optional.of(saved));
        MarketPriceDto priceDto = new MarketPriceDto("AAPL", "Apple Inc.", BigDecimal.valueOf(224.0), BigDecimal.ZERO, false, "ALPACA");

        Optional<ForecastResponse> restoredOpt = cacheService.getFreshForecast("AAPL", priceDto);
        assertTrue(restoredOpt.isPresent());
        ForecastResponse restored = restoredOpt.get();
        assertEquals("GEMINI_SHARD_2", restored.getAiShard(), "aiShard phải được phục hồi chính xác từ CSDL");
        assertEquals("GEMINI", restored.getAnalysisSource());

        // 3. Fallback: Nếu CSDL cũ có ai_shard = null, getFreshForecast tự phục hồi qua router
        saved.setAiShard(null);
        Optional<ForecastResponse> fallbackOpt = cacheService.getFreshForecast("AAPL", priceDto);
        assertTrue(fallbackOpt.isPresent());
        assertEquals("GEMINI_SHARD_2", fallbackOpt.get().getAiShard(), "Khi entity thiếu aiShard, router tự động gán shard tương ứng");
    }
}
