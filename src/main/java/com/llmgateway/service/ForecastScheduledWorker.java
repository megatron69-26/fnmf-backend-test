package com.llmgateway.service;

import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker chạy ngầm theo chu kỳ định cấu hình (mặc định 1 giờ) để tạo nhận định thị trường bằng Qwen2.5-1.5B:
 * 1. Thu thập giá BTC, các tài sản Binance, nến 30 ngày và tin tức vĩ mô.
 * 2. Gọi Qwen qua loopback cục bộ (127.0.0.1:8080).
 * 3. Kiểm định chất lượng nghiêm ngặt (ForecastQualityPolicy).
 * 4. Lưu bản nhận định vào PostgreSQL (bảng MARKET_FORECASTS).
 * 5. Khi người dùng mở app, API chỉ đọc bản có sẵn từ DB cache (không bắt người dùng chờ LLM).
 */
@Component
public class ForecastScheduledWorker {

    private static final Logger log = LoggerFactory.getLogger(ForecastScheduledWorker.class);

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final QwenForecastClient qwenForecastClient;
    private final MarketDataService marketDataService;
    private final ForecastService forecastService;
    private final ForecastCacheService forecastCacheService;
    private final NewsAiCacheRepository newsAiCacheRepository;

    @Value("${forecast.worker.enabled:false}")
    private boolean enabled = false;

    @Autowired
    public ForecastScheduledWorker(QwenForecastClient qwenForecastClient,
                                  MarketDataService marketDataService,
                                  ForecastService forecastService,
                                  ForecastCacheService forecastCacheService,
                                  NewsAiCacheRepository newsAiCacheRepository) {
        this.qwenForecastClient = qwenForecastClient;
        this.marketDataService = marketDataService;
        this.forecastService = forecastService;
        this.forecastCacheService = forecastCacheService;
        this.newsAiCacheRepository = newsAiCacheRepository;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${forecast.worker.interval-ms:3600000}",
            initialDelayString = "${forecast.worker.initial-delay-ms:15000}"
    )
    public boolean runWorkerCycle() {
        if (!enabled) {
            log.info("ForecastScheduledWorker đang bị vô hiệu hóa bởi cấu hình (forecast.worker.enabled=false).");
            return false;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.warn("FORECAST WORKER CONCURRENCY GUARD: Đợt worker trước vẫn đang xử lý, bỏ qua lượt này!");
            return false;
        }

        log.info("BẮT ĐẦU CHU KỲ TẠO NHẬN ĐỊNH THỊ TRƯỜNG NGẦM BẰNG QWEN2.5-1.5B");
        try {
            List<MarketPriceDto> allPrices = marketDataService.getAllPrices();
            List<CandleDto> candles = marketDataService.getCandles("BTCUSDT", "daily");
            List<NewsAiCache> recentNews = forecastService.fetchMarketNews();

            ForecastResponse response = qwenForecastClient.requestMarketForecast(allPrices, candles, recentNews, "24H_7D");
            if (response != null) {
                ForecastQualityPolicy.validateOrThrow(response);
                forecastCacheService.saveForecast(response);
                log.info("FORECAST WORKER: Đã tạo và lưu nhận định thị trường mới thành công (source={}, trend={}, rec={})",
                        response.getAnalysisSource(), response.getTrendPrediction(), response.getRecommendation());
                return true;
            }
        } catch (Exception ex) {
            log.warn("Lỗi trong chu kỳ ForecastScheduledWorker: {}", ex.getMessage());
        } finally {
            isRunning.set(false);
        }
        return false;
    }
}
