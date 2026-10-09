package com.llmgateway.service;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.provider.FixedMarketCacheManager;
import com.llmgateway.service.provider.FixedMarketProviderRouter;
import com.llmgateway.service.provider.MarketContentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final MarketForecastRepository forecastRepository;
    private final NewsAiCacheRepository newsAiCacheRepository;
    private final MarketDataService marketDataService;
    private final ForecastCacheService forecastCacheService;
    private final GeminiForecastClient geminiForecastClient;
    private QwenForecastClient qwenForecastClient;
    private final FixedMarketProviderRouter fixedMarketProviderRouter;
    private final FixedMarketCacheManager fixedMarketCacheManager;

    @org.springframework.beans.factory.annotation.Value("${forecast.cache-only-api:false}")
    private boolean cacheOnlyApi = false;

    public boolean isCacheOnlyApi() {
        return cacheOnlyApi;
    }

    public void setCacheOnlyApi(boolean cacheOnlyApi) {
        this.cacheOnlyApi = cacheOnlyApi;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setQwenForecastClient(QwenForecastClient qwenForecastClient) {
        this.qwenForecastClient = qwenForecastClient;
    }

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             new ForecastCacheService(forecastRepository, objectMapper),
             new GeminiForecastClient(objectMapper),
             null,
             null);
    }

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             forecastCacheService,
             geminiForecastClient,
             null,
             null);
    }

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient,
                           QwenForecastClient qwenForecastClient) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             forecastCacheService,
             geminiForecastClient,
             null,
             null);
        this.qwenForecastClient = qwenForecastClient;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) FixedMarketProviderRouter fixedMarketProviderRouter,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) FixedMarketCacheManager fixedMarketCacheManager) {
        this.forecastRepository = forecastRepository;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.marketDataService = marketDataService;
        this.forecastCacheService = forecastCacheService;
        this.geminiForecastClient = geminiForecastClient;
        this.fixedMarketProviderRouter = fixedMarketProviderRouter;
        this.fixedMarketCacheManager = fixedMarketCacheManager;
    }

    /**
     * Tạo hoặc lấy bản nhận định toàn thị trường AI thống nhất (MARKET-WIDE FORECAST).
     * 1. Cache key duy nhất: MARKET.
     * 2. Phân tích tổng hợp từ toàn bộ 8 tài sản Binance + nến thực tế + tin tức thị trường chung.
     * 3. Chỉ gọi Gemini khi chưa có cache hợp lệ (15 phút) hoặc pull-to-refresh (bypassCache = true).
     * 4. Khi Gemini lỗi (hết quota, timeout, network):
     *    - Trả về bản dự báo MARKET thành công gần nhất từ CSDL với stale = true, fromCache = true.
     *    - Nếu chưa từng có bản dự báo nào trong CSDL: ném ForecastUnavailableException (503).
     */
    public ForecastResponse generateMarketForecast(String timeframe, boolean bypassCache) {
        String tf = (timeframe != null && !timeframe.isBlank()) ? timeframe : "24H_7D";

        // 1. Kiểm tra CSDL cache (chấp nhận nguồn QWEN hoặc GEMINI)
        // Zero external calls: Không gọi resolveMarketBenchmarkPrice(), không gọi Binance, không gọi LLM khi có cache
        if (!bypassCache) {
            Optional<ForecastResponse> cached = getMarketForecastFromDb(tf);
            if (cached.isPresent()) {
                return cached.get();
            }
            if (cacheOnlyApi) {
                throw new ForecastUnavailableException("Đang cập nhật nhận định thị trường. Vui lòng thử lại sau.");
            }
        }

        // 2. Thu thập dữ liệu toàn bộ 8 tài sản Binance + nến BTC + tin tức vĩ mô thị trường
        Exception lastException = null;
        try {
            resolveMarketBenchmarkPrice();
            List<MarketPriceDto> allPrices = marketDataService.getAllPrices();
            List<CandleDto> candles = marketDataService.getCandles("BTCUSDT", "daily");
            List<NewsAiCache> recentNews = fetchMarketNews();

            // 3. Phân tích qua Qwen2.5-1.5B (hoặc Gemini fallback) với dữ liệu thực tế
            ForecastResponse response = null;
            if (qwenForecastClient != null) {
                response = qwenForecastClient.requestMarketForecast(allPrices, candles, recentNews, tf);
            } else if (geminiForecastClient != null) {
                response = geminiForecastClient.requestMarketForecast(allPrices, candles, recentNews, tf);
            }

            if (response != null) {
                ForecastQualityPolicy.validateOrThrow(response);
                // 4. Lưu bản dự báo vào CSDL
                forecastCacheService.saveForecast(response);
                response.setFromCache(false);
                response.setStale(false);
                return response;
            }
        } catch (Exception e) {
            lastException = e;
            log.warn("Lỗi khi tạo nhận định toàn thị trường từ provider: {}", e.getClass().getSimpleName());
        }

        // 5. Fallback khi provider lỗi hoặc không có giá BTC thật:
        // Tìm bản ghi MARKET hợp lệ gần nhất trong CSDL (bản cũ có nhãn thời gian)
        Optional<ForecastResponse> fallbackOpt = getMarketForecastFromDb(tf);
        if (fallbackOpt.isPresent()) {
            ForecastResponse fallback = fallbackOpt.get();
            fallback.setStale(true); // Cưỡng bức stale khi rơi vào fallback sau sự cố
            return fallback;
        }

        // 6. Nếu không có giá thật và không có cache hợp lệ: trả lỗi 503 an toàn cố định
        throw new ForecastUnavailableException(
                "Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.",
                lastException);
    }

    public ForecastResponse generateForecast(ForecastRequest request) {
        return generateForecast(request, false);
    }

    public ForecastResponse generateForecast(ForecastRequest request, boolean bypassCache) {
        String timeframe = (request != null && request.getTimeframe() != null) ? request.getTimeframe() : "24H_7D";
        return generateMarketForecast(timeframe, bypassCache);
    }

    public List<NewsAiCache> fetchMarketNews() {
        List<NewsAiCache> result = new ArrayList<>();
        if (newsAiCacheRepository != null) {
            try {
                List<NewsAiCache> all = newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc();
                if (all != null) {
                    for (NewsAiCache item : all) {
                        result.add(item);
                        if (result.size() >= 5) break;
                    }
                }
            } catch (Exception e) {
                log.debug("Lỗi tra cứu tin tức thị trường: {}", e.getClass().getSimpleName());
            }
        }
        return result;
    }

    public MarketPriceDto resolveMarketBenchmarkPrice() {
        try {
            MarketPriceDto btc = marketDataService.getPriceBySymbol("BTCUSDT");
            if (btc != null && !btc.isStale() && btc.getPrice() != null && btc.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                return new MarketPriceDto(
                        "MARKET",
                        "Nhận định toàn thị trường",
                        "MARKET",
                        btc.getPrice(),
                        btc.getChange24h() != null ? btc.getChange24h() : BigDecimal.ZERO,
                        btc.getBidPrice(),
                        btc.getAskPrice(),
                        btc.getLastUpdated(),
                        false,
                        "BINANCE",
                        btc.getPriceAsOf()
                );
            }
        } catch (Exception e) {
            log.warn("Không thể lấy giá BTCUSDT làm benchmark thị trường: {}", e.getClass().getSimpleName());
        }
        throw new MarketDataUnavailableException("Không có dữ liệu giá BTC thực tế để làm mốc tham chiếu nhận định");
    }

    /**
     * Thu thập tối đa 3 tin tức CHÍNH XÁC của symbol được yêu cầu.
     * Cấm dùng findTop10ByOrderByPublishedAtDesc() toàn bộ sàn.
     * Cấm dùng tin tức của symbol khác.
     */
    public List<NewsAiCache> fetchNewsForSymbol(String cleanSymbol) {
        if (cleanSymbol == null || cleanSymbol.isBlank()) {
            return Collections.emptyList();
        }

        String canonical = MarketSymbolConfig.isSupported(cleanSymbol)
                ? MarketSymbolConfig.getCanonicalSymbol(cleanSymbol)
                : cleanSymbol.trim().toUpperCase();
        List<NewsAiCache> result = new ArrayList<>();

        if (MarketSymbolConfig.isSupported(cleanSymbol) && MarketSymbolConfig.isStock(canonical)) {
            // Cổ phiếu: tra cứu qua FixedMarketCacheManager / FixedMarketProviderRouter
            if (fixedMarketProviderRouter != null) {
                try {
                    MarketContentProvider provider = fixedMarketProviderRouter.getProvider(canonical);
                    String providerName = provider.providerName();
                    List<NewsArticleDto> providerNews = null;

                    if (fixedMarketCacheManager != null) {
                        FixedMarketCacheManager.CachedEntry<List<NewsArticleDto>> cachedNews = fixedMarketCacheManager.getNewsEntry(providerName, canonical);
                        if (cachedNews != null && cachedNews.isFresh(FixedMarketCacheManager.NEWS_TTL_MS)) {
                            providerNews = cachedNews.getData();
                        }
                    }

                    if (providerNews == null) {
                        providerNews = provider.getLatestNews(canonical, 3);
                        if (providerNews == null) {
                            providerNews = Collections.emptyList();
                        }
                        if (fixedMarketCacheManager != null) {
                            fixedMarketCacheManager.putNews(providerName, canonical, providerNews);
                        }
                    }

                    if (providerNews != null && !providerNews.isEmpty()) {
                        for (NewsArticleDto art : providerNews) {
                            if (result.size() >= 3) break;
                            NewsAiCache item = new NewsAiCache();
                            item.setSymbol(canonical);
                            item.setTitle(art.title());
                            item.setOriginalSummary(art.summary());
                            item.setSentiment("NEUTRAL");
                            item.setReason("Tin tức từ " + providerName);
                            item.setArticleUrl(art.url());
                            result.add(item);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Không thể lấy tin tức từ provider cho cổ phiếu {}: {}", canonical, e.getClass().getSimpleName());
                }
            }

            // Fallback tra cứu DB riêng cho canonical symbol nếu có
            if (result.isEmpty() && newsAiCacheRepository != null) {
                try {
                    List<NewsAiCache> dbNews = newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(canonical);
                    if (dbNews != null) {
                        for (NewsAiCache item : dbNews) {
                            if (canonical.equalsIgnoreCase(item.getSymbol())) {
                                result.add(item);
                                if (result.size() >= 3) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Lỗi tra cứu tin tức CSDL cho {}: {}", canonical, e.getClass().getSimpleName());
                }
            }
        } else {
            // Crypto: tra cứu trong newsAiCacheRepository đúng cho canonical symbol
            if (newsAiCacheRepository != null) {
                try {
                    List<NewsAiCache> dbNews = newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(canonical);
                    if (dbNews != null) {
                        for (NewsAiCache item : dbNews) {
                            if (canonical.equalsIgnoreCase(item.getSymbol())) {
                                result.add(item);
                                if (result.size() >= 3) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Lỗi tra cứu tin tức crypto cho {}: {}", canonical, e.getClass().getSimpleName());
                }
            }
        }

        return result;
    }

    public List<MarketForecast> getForecastHistory(String symbol) {
        if (symbol == null || symbol.isBlank() || "MARKET".equalsIgnoreCase(symbol.trim())) {
            return forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        }
        String cleanSymbol = symbol.trim().toUpperCase();
        List<MarketForecast> history = forecastRepository.findBySymbolOrderByCreatedAtDesc(cleanSymbol);
        if (history.isEmpty()) {
            return forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        }
        return history;
    }

    public List<MarketForecast> getLatestForecasts() {
        return forecastRepository.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Truy vấn CSDL PostgreSQL trước:
     * - Nếu có bản MARKET nguồn GEMINI hợp lệ, trả ngay với fromCache=true.
     * - Zero external calls: không gọi Binance, Gemini hay News.
     * - TTL 24h: bản trong 24 giờ là stale=false; bản quá 24 giờ là stale=true.
     */
    public Optional<ForecastResponse> getMarketForecastFromDb(String timeframe) {
        List<MarketForecast> records = forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        if (records == null || records.isEmpty()) {
            Optional<MarketForecast> topRecord = forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET");
            if (topRecord.isPresent()) {
                records = List.of(topRecord.get());
            } else {
                return Optional.empty();
            }
        }

        for (MarketForecast record : records) {
            String source = record.getAnalysisSource();
            if ("QWEN".equalsIgnoreCase(source) || "QWEN_LOCAL".equalsIgnoreCase(source) || "GEMINI".equalsIgnoreCase(source)) {
                List<String> keyDrivers = forecastCacheService.parseKeyDrivers(record.getAnalysisSummary());
                if (keyDrivers == null || keyDrivers.isEmpty()) {
                    log.warn("Bỏ qua bản ghi MARKET nguồn {} thiếu key drivers", source);
                    continue;
                }
                ForecastResponse resp = new ForecastResponse(
                        "MARKET",
                        "Nhận định toàn thị trường",
                        record.getCurrentPrice(),
                        record.getTrendPrediction(),
                        record.getTimeframe() != null ? record.getTimeframe() : timeframe,
                        record.getSupportLevel(),
                        record.getResistanceLevel(),
                        record.getRecommendation(),
                        record.getConfidenceScore() != null ? record.getConfidenceScore().intValue() : 50,
                        keyDrivers,
                        record.getTechnicalOutlook(),
                        record.getFundamentalOutlook(),
                        source != null ? source : "QWEN",
                        record.getCandleCount() != null ? record.getCandleCount() : 30,
                        true,
                        record.getCreatedAt()
                );
                resp.setAiShard(record.getAiShard());
                resp.setFromCache(true);

                // TTL 24h: Bản trong 24 giờ là stale=false; bản quá 24 giờ là stale=true
                boolean isFresh = record.getCreatedAt() != null &&
                        record.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24));
                resp.setStale(!isFresh);

                if (ForecastQualityPolicy.isValid(resp)) {
                    log.info("LẤY DỰ BÁO MARKET TỪ DATABASE CACHE | id={} | source={} | stale={} | createdAt={}",
                            record.getId(), source, resp.isStale(), record.getCreatedAt());
                    return Optional.of(resp);
                } else {
                    log.warn("Bản ghi MARKET nguồn {} không thỏa mãn chính sách chất lượng", source);
                }
            } else {
                log.warn("Bỏ qua bản ghi MARKET không thuộc nguồn hợp lệ: {}", record.getAnalysisSource());
            }
        }

        return Optional.empty();
    }

    /**
     * Lấy bản dự báo từ cache CSDL mà không gọi Gemini Provider hay Market Data Provider (dùng cho Replay).
     * Tuyệt đối không gọi resolveMarketBenchmarkPrice() hoặc bất kỳ provider nào.
     */
    public Optional<ForecastResponse> getFreshForecastFromCacheOnly(String symbol) {
        return getMarketForecastFromDb("24H_7D");
    }
}
