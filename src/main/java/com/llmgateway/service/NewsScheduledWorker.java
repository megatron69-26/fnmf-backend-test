package com.llmgateway.service;

import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker chạy ngầm theo chu kỳ định cấu hình (mặc định ~5 giờ) để chuẩn bị tin tức bằng Qwen:
 * 1. Lấy tin từ nguồn tin thật (CoinDesk RSS).
 * 2. Chuẩn hóa URL và chống trùng bài viết.
 * 3. Lưu bài gốc vào PostgreSQL trước (Fail-Closed).
 * 4. Gọi Qwen2.5-1.5B (127.0.0.1:8080) dịch và tóm tắt sang tiếng Việt.
 * 5. Nếu Qwen lỗi/timeout/JSON sai: giữ nguyên bài gốc, đánh dấu lỗi, tuyệt đối không bịa nội dung.
 * 6. Khóa chạy đồng thời (single-flight) chống 2 worker chồng lên nhau.
 */
@Component
public class NewsScheduledWorker {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduledWorker.class);

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final RssNewsFetcher rssNewsFetcher;
    private final QwenLocalClient qwenLocalClient;
    private final NewsAiCacheRepository newsAiCacheRepository;

    @Value("${news.worker.enabled:false}")
    private boolean enabled = false;

    @Value("${news.worker.rss-url:https://www.coindesk.com/arc/outboundfeeds/rss}")
    private String rssUrl = "https://www.coindesk.com/arc/outboundfeeds/rss";

    @Value("${news.worker.max-articles-per-run:5}")
    private int maxArticlesPerRun = 5;

    @Autowired
    public NewsScheduledWorker(RssNewsFetcher rssNewsFetcher,
                               QwenLocalClient qwenLocalClient,
                               NewsAiCacheRepository newsAiCacheRepository) {
        this.rssNewsFetcher = rssNewsFetcher;
        this.qwenLocalClient = qwenLocalClient;
        this.newsAiCacheRepository = newsAiCacheRepository;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setRssUrl(String rssUrl) {
        this.rssUrl = rssUrl;
    }

    public void setMaxArticlesPerRun(int maxArticlesPerRun) {
        this.maxArticlesPerRun = maxArticlesPerRun;
    }

    /**
     * Chu kỳ chạy ngầm (mặc định 5 giờ = 18,000,000 ms, khởi động sau 15 giây).
     */
    @Scheduled(
            fixedDelayString = "${news.worker.interval-ms:18000000}",
            initialDelayString = "${news.worker.initial-delay-ms:15000}"
    )
    public int runWorkerCycle() {
        if (!enabled) {
            log.info("NewsScheduledWorker đang bị vô hiệu hóa bởi cấu hình (news.worker.enabled=false).");
            return 0;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.warn("NEWS WORKER CONCURRENCY GUARD: Đợt worker trước vẫn đang xử lý, bỏ qua lượt chạy này!");
            return 0;
        }

        log.info("BẮT ĐẦU CHU KỲ NEWS SCHEDULED WORKER | Nguồn: {} | Tối đa bài mới: {}", rssUrl, maxArticlesPerRun);
        int processedCount = 0;

        try {
            List<RssNewsFetcher.RssArticleItem> items = rssNewsFetcher.fetchRssFeed(rssUrl);
            if (items != null && !items.isEmpty()) {
                for (RssNewsFetcher.RssArticleItem item : items) {
                    if (processedCount >= maxArticlesPerRun) {
                        log.info("Đã đạt giới hạn bài mới cho mỗi chu kỳ ({}), tạm dừng", maxArticlesPerRun);
                        break;
                    }

                String cleanUrl = NewsUrlNormalizer.normalizeUrl(item.getLink());
                if (cleanUrl == null || cleanUrl.isBlank()) {
                    continue;
                }

                // 1. Kiểm tra chống trùng lặp theo URL đã chuẩn hóa
                Optional<NewsAiCache> existingOpt = newsAiCacheRepository.findByArticleUrl(cleanUrl);
                if (existingOpt.isPresent()) {
                    NewsAiCache existing = existingOpt.get();
                    boolean hasTranslation = existing.getDisplayTitleVi() != null && !existing.getDisplayTitleVi().isBlank();
                    boolean isPendingOrError = "RAW_PENDING".equals(existing.getReason())
                            || (existing.getReason() != null && existing.getReason().startsWith("QWEN_ERROR"));
                    if (hasTranslation && !isPendingOrError) {
                        log.debug("Bài viết đã tồn tại với bản dịch hoàn tất, bỏ qua: {}", cleanUrl);
                        continue;
                    }
                    log.info("Phát hiện bài viết cần xử lý lại (RAW_PENDING hoặc QWEN_ERROR) | id={} | reason='{}' | url='{}'",
                            existing.getId(), existing.getReason(), cleanUrl);
                }

                // 2. Lưu bài gốc vào PostgreSQL TRƯỚC (Fail-Closed) nếu chưa có trong DB
                NewsAiCache entity = existingOpt.orElseGet(NewsAiCache::new);
                if (entity.getId() == null) {
                    entity.setArticleUrl(cleanUrl);
                    entity.setOriginalTitle(item.getTitle());
                    entity.setTitle(item.getTitle()); // Lưu title bằng bài gốc để app đọc được
                    entity.setOriginalSummary(item.getDescription());
                    entity.setSource(item.getSource());
                    entity.setAuthor(item.getAuthor());
                    entity.setBannerImage(item.getBannerImage());
                    entity.setPublishedAt(item.getPubDate());
                    entity.setAnalyzedAt(LocalDateTime.now());
                    entity.setReason("RAW_PENDING");
                    entity.setSentiment("NEUTRAL");
                    entity.setConfidencePct(BigDecimal.valueOf(80));
                    entity = newsAiCacheRepository.save(entity);
                    log.info("ĐÃ LƯU BÀI GỐC VÀO DB | id={} | url='{}'", entity.getId(), cleanUrl);
                }

                // 3. Gọi Qwen2.5-1.5B qua loopback để dịch và tóm tắt
                String titleToProcess = (entity.getOriginalTitle() != null && !entity.getOriginalTitle().isBlank())
                        ? entity.getOriginalTitle()
                        : item.getTitle();
                String summaryToProcess = (entity.getOriginalSummary() != null && !entity.getOriginalSummary().isBlank())
                        ? entity.getOriginalSummary()
                        : item.getDescription();

                log.info("GỌI QWEN XỬ LÝ BÀI BÁO | id={} | title='{}'", entity.getId(), titleToProcess);
                Optional<QwenLocalClient.QwenTranslationResult> qwenOpt =
                        qwenLocalClient.translateAndSummarize(titleToProcess, summaryToProcess);

                if (qwenOpt.isPresent()) {
                    QwenLocalClient.QwenTranslationResult res = qwenOpt.get();
                    entity.setDisplayTitleVi(res.getDisplayTitleVi());
                    entity.setDisplaySummaryVi(res.getDisplaySummaryVi());
                    entity.setBulletPointsVi(res.getBulletPointsViJson());
                    entity.setSummaryPoints(res.getBulletPointsViJson());
                    entity.setSentiment(res.getSentiment());
                    entity.setConfidencePct(BigDecimal.valueOf(res.getConfidencePct()));
                    entity.setReason("PROCESSED_BY_QWEN");
                    entity.setAnalyzedAt(LocalDateTime.now());
                    newsAiCacheRepository.save(entity);
                    log.info("QWEN XỬ LÝ THÀNH CÔNG | id={} | titleVi='{}'", entity.getId(), res.getDisplayTitleVi());
                    processedCount++;
                } else {
                    // Qwen lỗi hoặc trả định dạng sai: Đánh dấu lỗi, bảo toàn bài gốc trong CSDL
                    entity.setReason("QWEN_ERROR: FAILED_OR_INVALID");
                    newsAiCacheRepository.save(entity);
                    log.warn("QWEN THẤT BẠI | id={} | Giữ nguyên bài gốc cho app đọc, không bịa nội dung: '{}'",
                            entity.getId(), cleanUrl);
                }
            }
        } else {
            log.info("Không lấy được bài viết mới nào từ RSS feed {}, quét bài chờ/lỗi trong CSDL", rssUrl);
        }

            // 4. Nếu chưa đủ quota chu kỳ, quét lại các bài RAW_PENDING hoặc QWEN_ERROR sẵn có trong DB
            if (processedCount < maxArticlesPerRun) {
                List<NewsAiCache> pendingOrErrorList = newsAiCacheRepository.findPendingOrErrorArticles();
                if (pendingOrErrorList != null) {
                    for (NewsAiCache pending : pendingOrErrorList) {
                        if (processedCount >= maxArticlesPerRun) {
                            break;
                        }
                        log.info("XỬ LÝ LẠI BÀI TỒN TẠI TRONG DB | id={} | reason='{}'", pending.getId(), pending.getReason());
                        String pTitle = (pending.getOriginalTitle() != null && !pending.getOriginalTitle().isBlank())
                                ? pending.getOriginalTitle()
                                : pending.getTitle();
                        String pSummary = (pending.getOriginalSummary() != null && !pending.getOriginalSummary().isBlank())
                                ? pending.getOriginalSummary()
                                : (pending.getDisplaySummaryVi() != null ? pending.getDisplaySummaryVi() : "");

                        Optional<QwenLocalClient.QwenTranslationResult> qwenOpt =
                                qwenLocalClient.translateAndSummarize(pTitle, pSummary);

                        if (qwenOpt.isPresent()) {
                            QwenLocalClient.QwenTranslationResult res = qwenOpt.get();
                            pending.setDisplayTitleVi(res.getDisplayTitleVi());
                            pending.setDisplaySummaryVi(res.getDisplaySummaryVi());
                            pending.setBulletPointsVi(res.getBulletPointsViJson());
                            pending.setSummaryPoints(res.getBulletPointsViJson());
                            pending.setSentiment(res.getSentiment());
                            pending.setConfidencePct(BigDecimal.valueOf(res.getConfidencePct()));
                            pending.setReason("PROCESSED_BY_QWEN");
                            pending.setAnalyzedAt(LocalDateTime.now());
                            newsAiCacheRepository.save(pending);
                            log.info("XỬ LÝ LẠI THÀNH CÔNG | id={} | titleVi='{}'", pending.getId(), res.getDisplayTitleVi());
                            processedCount++;
                        } else {
                            pending.setReason("QWEN_ERROR: FAILED_OR_INVALID");
                            newsAiCacheRepository.save(pending);
                            log.warn("XỬ LÝ LẠI THẤT BẠI | id={} | Giữ nguyên bài gốc trong CSDL", pending.getId());
                        }
                    }
                }
            }

            log.info("HOÀN TẤT CHU KỲ NEWS WORKER | Số bài mới xử lý thành công: {}", processedCount);
            return processedCount;
        } catch (Exception e) {
            log.error("Lỗi trong chu kỳ NewsScheduledWorker: {}", e.getMessage(), e);
            return processedCount;
        } finally {
            isRunning.set(false);
        }
    }
}
