package com.llmgateway.service;

import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker chạy ngầm theo chu kỳ định cấu hình (mặc định 2 giờ = 7,200,000 ms) để chuẩn bị tin tức bằng Qwen:
 * 1. Bước Ingestion (Nạp bài):
 *    - Lấy tin từ CoinDesk RSS định kỳ 2 giờ/lần.
 *    - Chuẩn hóa URL và chống trùng bài viết.
 *    - Lưu bài gốc vào PostgreSQL trước (Fail-Closed).
 *    - Giới hạn số bài mới nạp mỗi lượt để không quá tải Note 10+.
 *    - NGAY KHI một bài mới lưu thành công vào DB, đưa vào hàng đợi xử lý Qwen và kích hoạt xử lý ngay.
 * 2. Bước Processing (Hàng đợi xử lý Qwen):
 *    - Tách biệt hoàn toàn với bước Ingestion.
 *    - Khóa Single-Flight: Chỉ 1 tác vụ suy luận Qwen chạy tại một thời điểm, các bài còn lại xếp hàng.
 *    - Bounded Retry & Backoff: Nếu Qwen lỗi/timeout/JSON sai hoặc tiếng Việt không đạt,
 *      ghi trạng thái lỗi, thử lại có giới hạn (mặc định 3 lần) kèm backoff.
 *      Không để một bài lỗi chặn các bài khác.
 *    - Startup Recovery: Sau khi backend khởi động lại, tự động nạp các bài đang chờ từ DB vào hàng đợi.
 */
@Component
public class NewsScheduledWorker {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduledWorker.class);

    private final AtomicBoolean isIngesting = new AtomicBoolean(false);
    private final AtomicBoolean isProcessingQwen = new AtomicBoolean(false);

    private final Queue<Long> processingQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> enqueuedIds = ConcurrentHashMap.newKeySet();

    private final RssNewsFetcher rssNewsFetcher;
    private final QwenLocalClient qwenLocalClient;
    private final NewsAiCacheRepository newsAiCacheRepository;

    private ExecutorService queueExecutor;
    private boolean synchronousMode = false;

    private final Map<Long, NewsAiCache> ingestedEntityCache = new ConcurrentHashMap<>();

    @Value("${news.worker.enabled:false}")
    private boolean enabled = false;

    @Value("${news.worker.interval-ms:7200000}")
    private long intervalMs = 7200000;

    @Value("${news.worker.rss-url:https://www.coindesk.com/arc/outboundfeeds/rss}")
    private String rssUrl = "https://www.coindesk.com/arc/outboundfeeds/rss";

    @Value("${news.worker.max-articles-per-run:5}")
    private int maxArticlesPerRun = 5;

    @Value("${news.worker.max-retries:3}")
    private int maxRetries = 3;

    @Value("${news.worker.backoff-initial-minutes:5}")
    private int backoffInitialMinutes = 5;

    @Autowired
    public NewsScheduledWorker(RssNewsFetcher rssNewsFetcher,
                               QwenLocalClient qwenLocalClient,
                               NewsAiCacheRepository newsAiCacheRepository) {
        this.rssNewsFetcher = rssNewsFetcher;
        this.qwenLocalClient = qwenLocalClient;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.queueExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "qwen-news-queue-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void init() {
        if (enabled) {
            log.info("NewsScheduledWorker khởi động: Phục hồi hàng đợi bài viết chờ/lỗi từ CSDL...");
            recoverPendingArticles();
        }
    }

    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "news-retry-wakeup");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void destroy() {
        if (queueExecutor != null && !queueExecutor.isShutdown()) {
            queueExecutor.shutdown();
            try {
                if (!queueExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    queueExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                queueExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (retryScheduler != null && !retryScheduler.isShutdown()) {
            retryScheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return isIngesting.get() || isProcessingQwen.get();
    }

    public boolean isIngesting() {
        return isIngesting.get();
    }

    public boolean isProcessingQwen() {
        return isProcessingQwen.get();
    }

    public int getProcessingQueueSize() {
        return processingQueue.size();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setRssUrl(String rssUrl) {
        this.rssUrl = rssUrl;
    }

    public void setMaxArticlesPerRun(int maxArticlesPerRun) {
        this.maxArticlesPerRun = maxArticlesPerRun;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public boolean isSynchronousMode() {
        return synchronousMode;
    }

    public void setSynchronousMode(boolean synchronousMode) {
        this.synchronousMode = synchronousMode;
    }

    // =========================================================================
    // 1. CHU KỲ INGESTION (NẠP BÀI VÀO DB ĐỊNH KỲ 2 GIỜ/LẦN)
    // =========================================================================

    /**
     * Chu kỳ nạp tin ngầm (mặc định 2 giờ = 7,200,000 ms, khởi động sau 15 giây).
     */
    @Scheduled(
            fixedDelayString = "${news.worker.interval-ms:7200000}",
            initialDelayString = "${news.worker.initial-delay-ms:15000}"
    )
    public int runWorkerCycle() {
        if (!enabled) {
            log.info("NewsScheduledWorker đang bị vô hiệu hóa bởi cấu hình (news.worker.enabled=false).");
            return 0;
        }

        if (!isIngesting.compareAndSet(false, true)) {
            log.warn("NEWS WORKER CONCURRENCY GUARD: Đợt nạp tin trước vẫn đang chạy, bỏ qua lượt nạp này!");
            return 0;
        }

        log.info("BẮT ĐẦU CHU KỲ NẠP TIN MỚI (2 GIỜ/LẦN) | Nguồn: {} | Tối đa bài mới: {}", rssUrl, maxArticlesPerRun);
        int newIngestedCount = 0;

        try {
            List<RssNewsFetcher.RssArticleItem> items = rssNewsFetcher.fetchRssFeed(rssUrl);
            if (items != null && !items.isEmpty()) {
                for (RssNewsFetcher.RssArticleItem item : items) {
                    if (newIngestedCount >= maxArticlesPerRun) {
                        log.info("Đã đạt giới hạn bài mới cho chu kỳ ({}), tạm dừng nạp thêm", maxArticlesPerRun);
                        break;
                    }

                    String cleanUrl = NewsUrlNormalizer.normalizeUrl(item.getLink());
                    if (cleanUrl == null || cleanUrl.isBlank()) {
                        continue;
                    }

                    // Kiểm tra chống trùng lặp theo URL đã chuẩn hóa
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
                        // Nếu là bài cũ bị lỗi hoặc pending, đưa vào hàng đợi nếu chưa có
                        if (isPendingOrError && existing.getId() != null) {
                            ingestedEntityCache.put(existing.getId(), existing);
                            enqueueArticle(existing.getId());
                        }
                        continue;
                    }

                    // Lưu bài gốc vào PostgreSQL TRƯỚC (Fail-Closed)
                    NewsAiCache entity = new NewsAiCache();
                    entity.setArticleUrl(cleanUrl);
                    entity.setOriginalTitle(item.getTitle());
                    entity.setTitle(item.getTitle());
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

                    if (entity.getId() != null) {
                        ingestedEntityCache.put(entity.getId(), entity);
                        enqueueArticle(entity.getId());
                        newIngestedCount++;
                    }
                }
            } else {
                log.info("Không lấy được bài viết mới nào từ RSS feed {}, quét bài chờ/lỗi trong CSDL", rssUrl);
            }

            // Phục hồi các bài chờ/lỗi cũ còn trong DB
            recoverPendingArticles();

            log.info("HOÀN TẤT CHU KỲ NẠP TIN MỚI | Số bài mới nạp vào DB: {} | Số bài đang chờ xử lý: {}",
                    newIngestedCount, processingQueue.size());

            // Tiến hành xử lý hàng đợi
            if (synchronousMode) {
                return processQueueSynchronously();
            } else {
                triggerQueueProcessing();
                return newIngestedCount;
            }
        } catch (Exception e) {
            log.error("Lỗi trong chu kỳ NewsScheduledWorker (Ingestion): {}", e.getMessage(), e);
            return 0;
        } finally {
            isIngesting.set(false);
        }
    }

    // =========================================================================
    // 2. HÀNG ĐỢI XỬ LÝ QWEN (SINGLE-FLIGHT SEQUENTIAL PROCESSOR)
    // =========================================================================

    /**
     * Đưa bài vào hàng đợi (chưa trigger ngay).
     */
    public void enqueueArticle(Long articleId) {
        if (articleId == null) return;
        if (enqueuedIds.add(articleId)) {
            processingQueue.offer(articleId);
            log.info("ĐÃ THÊM BÀI VÀO HÀNG ĐỢI XỬ LÝ QWEN | id={} | Độ dài hàng đợi: {}",
                    articleId, processingQueue.size());
        }
    }

    /**
     * Đưa bài vào hàng đợi và kích hoạt xử lý ngay lập tức (không đợi chu kỳ 2 giờ).
     */
    public void enqueueAndTrigger(Long articleId) {
        enqueueArticle(articleId);
        triggerQueueProcessing();
    }

    /**
     * Kích hoạt xử lý hàng đợi theo cơ chế Single-Flight.
     */
    public void triggerQueueProcessing() {
        if (synchronousMode) {
            // Chế độ đồng bộ (dành cho Unit Test)
            processQueueSynchronously();
            return;
        }

        if (isProcessingQwen.compareAndSet(false, true)) {
            if (queueExecutor != null && !queueExecutor.isShutdown()) {
                queueExecutor.submit(this::processQueueLoop);
            } else {
                // Fallback nếu executor không sẵn sàng
                new Thread(this::processQueueLoop, "qwen-news-fallback-worker").start();
            }
        } else {
            log.debug("Qwen hiện đang bận xử lý bài trước, bài mới đã nằm trong hàng đợi chờ tới lượt.");
        }
    }

    /**
     * Thực thi toàn bộ hàng đợi đồng bộ (dành cho kiểm thử hoặc gọi trực tiếp).
     */
    public int processQueueSynchronously() {
        if (!isProcessingQwen.compareAndSet(false, true)) {
            log.debug("Qwen đang bận xử lý");
            return 0;
        }
        return processQueueLoop();
    }

    /**
     * Vòng lặp lấy bài từ hàng đợi và xử lý tuần tự (Single-Flight).
     */
    private int processQueueLoop() {
        int processedSuccessCount = 0;
        try {
            while (true) {
                Long articleId = processingQueue.poll();
                if (articleId == null) {
                    break;
                }
                try {
                    boolean success = processSingleArticle(articleId);
                    if (success) {
                        processedSuccessCount++;
                    }
                } catch (Exception e) {
                    log.error("Ngoại lệ không mong muốn khi xử lý articleId {}: {}", articleId, e.getMessage(), e);
                } finally {
                    enqueuedIds.remove(articleId);
                }
            }
        } finally {
            isProcessingQwen.set(false);
            // Phòng ngừa race condition: nếu có bài vừa vào queue ngay lúc loop kết thúc
            if (!processingQueue.isEmpty() && isProcessingQwen.compareAndSet(false, true)) {
                if (synchronousMode) {
                    processQueueLoop();
                } else if (queueExecutor != null && !queueExecutor.isShutdown()) {
                    queueExecutor.submit(this::processQueueLoop);
                }
            }
        }
        return processedSuccessCount;
    }

    /**
     * Xử lý 1 bài viết bằng Qwen:
     * - Nếu thành công: Cập nhật bản dịch tiếng Việt, đánh dấu PROCESSED_BY_QWEN.
     * - Nếu thất bại (timeout/JSON/tiếng Việt vi phạm): Ghi nhận lỗi có backoff/retry, không làm gián đoạn bài khác.
     */
    public boolean processSingleArticle(Long articleId) {
        if (articleId == null) return false;

        Optional<NewsAiCache> opt = newsAiCacheRepository.findById(articleId);
        NewsAiCache entity = opt.orElseGet(() -> ingestedEntityCache.get(articleId));
        if (entity == null) {
            log.warn("Không tìm thấy bài viết trong CSDL | id={}", articleId);
            return false;
        }

        // Nếu bài đã có bản dịch hợp lệ hoàn tất, bỏ qua
        if ("PROCESSED_BY_QWEN".equals(entity.getReason())
                && entity.getDisplayTitleVi() != null
                && !entity.getDisplayTitleVi().isBlank()) {
            log.debug("Bài viết đã được xử lý thành công trước đó, bỏ qua: id={}", articleId);
            return true;
        }

        // Kiểm tra xem bài có đang trong thời gian backoff không
        if (isInBackoff(entity.getReason())) {
            log.info("Bài viết id={} đang trong thời gian backoff, hoãn xử lý cho chu kỳ kế tiếp.", articleId);
            return false;
        }

        String titleToProcess = (entity.getOriginalTitle() != null && !entity.getOriginalTitle().isBlank())
                ? entity.getOriginalTitle()
                : entity.getTitle();
        String summaryToProcess = (entity.getOriginalSummary() != null && !entity.getOriginalSummary().isBlank())
                ? entity.getOriginalSummary()
                : (entity.getDisplaySummaryVi() != null ? entity.getDisplaySummaryVi() : "");

        log.info("BẮT ĐẦU XỬ LÝ QWEN CHO BÀI BÁO | id={} | title='{}'", entity.getId(), titleToProcess);

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
            return true;
        } else {
            // Thất bại: Tính toán retry count và backoff, bảo toàn bài gốc trong CSDL
            handleProcessingFailure(entity, "FAILED_OR_INVALID");
            return false;
        }
    }

    /**
     * Ghi nhận lỗi xử lý kèm retry count và backoff.
     */
    private void handleProcessingFailure(NewsAiCache entity, String errorDetail) {
        int currentRetry = parseCurrentRetryCount(entity.getReason());
        int nextRetry = currentRetry + 1;

        if (nextRetry < maxRetries) {
            long backoffMinutes = calculateBackoffMinutes(nextRetry);
            LocalDateTime nextRetryTime = LocalDateTime.now().plusMinutes(backoffMinutes);
            String newReason = String.format("QWEN_ERROR: FAILED_OR_INVALID (retry=%d/%d, next_retry=%s)",
                    nextRetry, maxRetries, nextRetryTime.toString());
            entity.setReason(newReason);
            entity.setAnalyzedAt(LocalDateTime.now());
            newsAiCacheRepository.save(entity);
            log.warn("QWEN THẤT BẠI (Lần {}/{}) | id={} | Thử lại sau {} phút ({}) | Giữ nguyên bài gốc",
                    nextRetry, maxRetries, entity.getId(), backoffMinutes, nextRetryTime);

            // Đặt lịch đánh thức retry đúng mốc thời gian (không phải đợi lượt quét 2 giờ sau)
            scheduleRetryWakeup(entity.getId(), backoffMinutes * 60L, TimeUnit.SECONDS);
        } else {
            String exhaustedReason = String.format("QWEN_FAILED_EXHAUSTED: max retries reached (%d/%d), err=%s",
                    nextRetry, maxRetries, errorDetail);
            entity.setReason(exhaustedReason);
            entity.setAnalyzedAt(LocalDateTime.now());
            newsAiCacheRepository.save(entity);
            log.error("QWEN HẾT LƯỢT THỬ (Lần {}/{}) | id={} | Dừng thử lại tự động, bảo toàn bài gốc trong CSDL",
                    nextRetry, maxRetries, entity.getId());
        }
    }

    /**
     * Đặt lịch đánh thức một bài viết lỗi đúng sau khoảng thời gian backoff.
     */
    public void scheduleRetryWakeup(Long articleId, long delay, TimeUnit unit) {
        if (articleId == null) return;
        try {
            retryScheduler.schedule(() -> {
                log.info("LỊCH ĐÁNH THỨC RETRY ĐÃ ĐẾN HẠN: id={}, kích hoạt đưa vào hàng đợi xử lý ngay", articleId);
                enqueueAndTrigger(articleId);
            }, delay, unit);
            log.info("ĐÃ LÊN LỊCH ĐÁNH THỨC RETRY CHO BÀI id={} sau {} {}", articleId, delay, unit);
        } catch (Exception e) {
            log.warn("Không thể lập lịch đánh thức retry cho articleId={}: {}", articleId, e.getMessage());
        }
    }

    /**
     * Quét định kỳ (mặc định mỗi 60 giây) để đánh thức các bài lỗi đã hết hạn backoff trong CSDL.
     * Đảm bảo phục hồi sau khi backend khởi động lại mà không cần đợi lượt quét 2 giờ.
     */
    @Scheduled(
            fixedDelayString = "${news.worker.retry-poll-interval-ms:60000}",
            initialDelayString = "${news.worker.retry-poll-initial-delay-ms:10000}"
    )
    public int pollAndWakeupDueRetries() {
        return triggerDueRetries();
    }

    /**
     * Đánh thức tất cả các bài lỗi trong CSDL đã hết hạn backoff.
     */
    public int triggerDueRetries() {
        try {
            List<NewsAiCache> pendingOrErrorList = newsAiCacheRepository.findPendingOrErrorArticles();
            int wokenCount = 0;
            LocalDateTime now = LocalDateTime.now();
            if (pendingOrErrorList != null) {
                for (NewsAiCache item : pendingOrErrorList) {
                    if (item.getId() == null) continue;
                    String reason = item.getReason();
                    if (reason == null || reason.startsWith("QWEN_FAILED_EXHAUSTED")) {
                        continue;
                    }
                    if (reason.startsWith("QWEN_ERROR")) {
                        LocalDateTime nextRetry = parseNextRetryTime(reason);
                        if (nextRetry != null && !now.isBefore(nextRetry)) {
                            if (!enqueuedIds.contains(item.getId())) {
                                log.info("QUÉT ĐÁNH THỨC RETRY: Bài id={} đã qua mốc {} (hiện tại: {}), đưa vào hàng đợi",
                                        item.getId(), nextRetry, now);
                                ingestedEntityCache.put(item.getId(), item);
                                enqueueArticle(item.getId());
                                wokenCount++;
                            }
                        }
                    } else if ("RAW_PENDING".equals(reason)) {
                        if (!enqueuedIds.contains(item.getId())) {
                            ingestedEntityCache.put(item.getId(), item);
                            enqueueArticle(item.getId());
                            wokenCount++;
                        }
                    }
                }
            }
            if (wokenCount > 0) {
                triggerQueueProcessing();
            }
            return wokenCount;
        } catch (Exception e) {
            log.warn("Lỗi khi quét bài retry đến hạn: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Tính thời gian backoff theo số lần thử: Lần 1: 5 phút, Lần 2: 15 phút, Lần 3+: 30 phút.
     */
    private long calculateBackoffMinutes(int retryCount) {
        if (retryCount <= 1) return backoffInitialMinutes;
        if (retryCount == 2) return backoffInitialMinutes * 3L;
        return 30L;
    }

    /**
     * Trích xuất số lần đã thử từ trường reason.
     */
    public static int parseCurrentRetryCount(String reason) {
        if (reason == null || reason.isBlank() || "RAW_PENDING".equals(reason)) {
            return 0;
        }
        int idx = reason.indexOf("retry=");
        if (idx != -1) {
            int slashIdx = reason.indexOf("/", idx);
            if (slashIdx != -1) {
                try {
                    String numStr = reason.substring(idx + 6, slashIdx).trim();
                    return Integer.parseInt(numStr);
                } catch (Exception ignored) {
                }
            }
        }
        if (reason.startsWith("QWEN_ERROR")) {
            return 1;
        }
        return 0;
    }

    /**
     * Phân tích thời điểm retry tiếp theo từ chuỗi reason.
     */
    public static LocalDateTime parseNextRetryTime(String reason) {
        if (reason == null) return null;
        int idx = reason.indexOf("next_retry=");
        if (idx != -1) {
            int commaIdx = reason.indexOf(",", idx);
            int parenIdx = reason.indexOf(")", idx);
            int endIdx = reason.length();
            if (commaIdx != -1) endIdx = Math.min(endIdx, commaIdx);
            if (parenIdx != -1) endIdx = Math.min(endIdx, parenIdx);
            String timeStr = reason.substring(idx + 11, endIdx).trim();
            try {
                return LocalDateTime.parse(timeStr);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Kiểm tra xem bài có đang trong thời gian backoff không.
     */
    public static boolean isInBackoff(String reason) {
        LocalDateTime next = parseNextRetryTime(reason);
        if (next == null) return false;
        return LocalDateTime.now().isBefore(next);
    }

    // =========================================================================
    // 3. STARTUP RECOVERY (PHỤC HỒI HÀNG ĐỢI SAU KHI RESTART)
    // =========================================================================

    /**
     * Phục hồi các bài đang chờ hoặc lỗi từ DB đưa vào hàng đợi/lịch đánh thức khi khởi động lại.
     */
    public int recoverPendingArticles() {
        List<NewsAiCache> pendingOrErrorList = newsAiCacheRepository.findPendingOrErrorArticles();
        int recoveredCount = 0;
        LocalDateTime now = LocalDateTime.now();
        if (pendingOrErrorList != null) {
            for (NewsAiCache item : pendingOrErrorList) {
                String reason = item.getReason();
                // Bỏ qua bài đã vượt quá tối đa lượt thử
                if (reason != null && reason.startsWith("QWEN_FAILED_EXHAUSTED")) {
                    continue;
                }
                if (item.getId() != null) {
                    ingestedEntityCache.put(item.getId(), item);
                    // Nếu là bài đang trong backoff, lên lịch đánh thức vào đúng thời điểm còn lại
                    if (reason != null && reason.startsWith("QWEN_ERROR")) {
                        LocalDateTime nextRetry = parseNextRetryTime(reason);
                        if (nextRetry != null && now.isBefore(nextRetry)) {
                            long remainingSeconds = java.time.Duration.between(now, nextRetry).toSeconds();
                            scheduleRetryWakeup(item.getId(), Math.max(1, remainingSeconds), TimeUnit.SECONDS);
                            recoveredCount++;
                            continue;
                        }
                    }
                    enqueueArticle(item.getId());
                    recoveredCount++;
                }
            }
        }
        if (recoveredCount > 0) {
            log.info("ĐÃ PHỤC HỒI {} BÀI VIẾT CHỜ XỬ LÝ TỪ CSDL VÀO HÀNG ĐỢI/LỊCH ĐÁNH THỨC", recoveredCount);
        }
        return recoveredCount;
    }
}
