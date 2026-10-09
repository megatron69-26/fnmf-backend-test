package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.ForecastController;
import com.llmgateway.controller.NewsAiController;
import com.llmgateway.controller.RefreshQuotaController;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.ContentRefreshEvent;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.exception.DailyRefreshLimitReachedException;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.GlobalExceptionHandler;
import com.llmgateway.repository.ContentRefreshEventRepository;
import com.llmgateway.repository.UserDailyRefreshQuotaRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.service.ForecastService;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_quota_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_key",
        "openai.api.key=test_key",
        "gateway.rate-limit.max-requests=1000",
        "gateway.rate-limit.window-seconds=60"
})
public class ContentRefreshQuotaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private ContentRefreshQuotaService quotaService;

    @Autowired
    private UserDailyRefreshQuotaRepository quotaRepository;

    @Autowired
    private ContentRefreshEventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ForecastService forecastService;

    @MockBean
    private AiNewsService aiNewsService;

    private User user1;
    private User user2;
    private String token1;
    private String token2;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        quotaRepository.deleteAll();
        userRepository.deleteAll();

        user1 = userRepository.save(new User("trader1@fnmf.com", "hash1", "Trader One", UserRole.USER));
        user2 = userRepository.save(new User("trader2@fnmf.com", "hash2", "Trader Two", UserRole.USER));

        token1 = jwtUtil.generateToken(user1.getEmail(), user1.getId());
        token2 = jwtUtil.generateToken(user2.getEmail(), user2.getId());

        ForecastResponse dummyForecast = new ForecastResponse(
                "BTCUSDT", "Bitcoin", BigDecimal.valueOf(65000), "BULLISH_UPTREND",
                "24H_7D", BigDecimal.valueOf(64000), BigDecimal.valueOf(68000),
                "BUY", 85, List.of("Driver 1", "Driver 2", "Driver 3"),
                "Xu huong tang ky thuat tot.", "Dong tien vi mo tich cuc.",
                false, java.time.LocalDateTime.now()
        );
        when(forecastService.generateForecast(any(ForecastRequest.class), anyBoolean())).thenReturn(dummyForecast);
        when(forecastService.generateForecast(any(ForecastRequest.class))).thenReturn(dummyForecast);
        when(forecastService.getFreshForecastFromCacheOnly(anyString())).thenReturn(Optional.of(dummyForecast));

        NewsFeedItemDto dummyNews = new NewsFeedItemDto();
        dummyNews.setUrl("https://news.example.com/1");
        dummyNews.setTitle("Tin tức mới");
        dummyNews.setDisplayTitleVi("Tin tức mới tiếng Việt");
        dummyNews.setDisplaySummaryVi("Tóm tắt tiếng Việt");
        dummyNews.setSource("Reuters");
        dummyNews.setPublisher("Reuters");
        dummyNews.setTimePublished("20260915T000000");
        dummyNews.setAiSentiment("bullish");
        dummyNews.setAiConfidence(90);
        dummyNews.setBulletPointsVi(List.of("Ý 1", "Ý 2", "Ý 3"));

        when(aiNewsService.getLiveAiNewsSyncResult(any(), anyInt(), anyBoolean()))
                .thenReturn(NewsSyncResult.ok(List.of(dummyNews)));
        when(aiNewsService.getLiveAiNewsSyncResult(any(), anyInt()))
                .thenReturn(NewsSyncResult.ok(List.of(dummyNews)));
    }

    @Test
    @DisplayName("1. Forecast và News cùng trừ một pool hạn mức 5 lượt")
    void testForecastAndNewsShareSameQuotaPool() throws Exception {
        // User 1 kiểm tra hạn mức ban đầu
        RefreshQuotaDto initial = quotaService.getQuotaStatus(user1.getId());
        assertEquals(5, initial.getMaxDailyRefreshes());
        assertEquals(0, initial.getUsedRefreshes());
        assertEquals(5, initial.getRemainingRefreshes());

        // Lượt 1: Forecast refresh
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(1))
                .andExpect(jsonPath("$.remainingRefreshes").value(4));

        // Lượt 2: News refresh
        mockMvc.perform(post("/api/news/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(2))
                .andExpect(jsonPath("$.remainingRefreshes").value(3));

        // Lượt 3: Forecast refresh
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("ETHUSDT", "24H_7D"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(3))
                .andExpect(jsonPath("$.remainingRefreshes").value(2));

        // Kiểm tra status endpoint không làm tăng used count
        RefreshQuotaDto current = quotaService.getQuotaStatus(user1.getId());
        assertEquals(3, current.getUsedRefreshes());
        assertEquals(2, current.getRemainingRefreshes());
    }

    @Test
    @DisplayName("2. 5 request khác ID được chấp nhận; request thứ 6 trả 429 DAILY_REFRESH_LIMIT_REACHED")
    void testFiveRequestsAcceptedSixthRejectedWith429() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/forecast/refresh")
                            .header("Authorization", "Bearer " + token1)
                            .header("Client-Request-ID", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usedRefreshes").value(i))
                    .andExpect(jsonPath("$.remainingRefreshes").value(5 - i));
        }

        // Lần thứ 6 phải bị từ chối với HTTP 429
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_REFRESH_LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value("Bạn đã dùng hết lượt làm mới hôm nay"))
                .andExpect(jsonPath("$.usedRefreshes").value(5))
                .andExpect(jsonPath("$.remainingRefreshes").value(0));

        // News cũng bị từ chối với HTTP 429
        mockMvc.perform(post("/api/news/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_REFRESH_LIMIT_REACHED"));
    }

    @Test
    @DisplayName("3. Replay cùng Client-Request-ID trả lại quyết định cũ và không trừ thêm")
    void testReplaySameClientRequestIdDoesNotDeductTwice() throws Exception {
        String sameRequestId = UUID.randomUUID().toString();

        // Gửi lần đầu
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", sameRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(1))
                .andExpect(jsonPath("$.remainingRefreshes").value(4));

        // Replay lần 2 với cùng requestId
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", sameRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(1))
                .andExpect(jsonPath("$.remainingRefreshes").value(4));

        // Kiểm tra trong CSDL: used_count vẫn là 1
        RefreshQuotaDto status = quotaService.getQuotaStatus(user1.getId());
        assertEquals(1, status.getUsedRefreshes());
        assertEquals(4, status.getRemainingRefreshes());

        // Bổ sung verify tổng số lần gọi provider: đúng 1 lần sau request đầu và replay
        org.mockito.Mockito.verify(forecastService, org.mockito.Mockito.times(1))
                .generateForecast(any(ForecastRequest.class), org.mockito.Mockito.anyBoolean());
        org.mockito.Mockito.verify(forecastService, org.mockito.Mockito.never())
                .generateForecast(any(ForecastRequest.class));

        // Kiểm tra tương tự với News: Replay không được gọi live pipeline lần thứ hai
        String sameNewsRequestId = UUID.randomUUID().toString();
        NewsFeedItemDto dummyNews = new NewsFeedItemDto(
                "Tiêu đề tin tức tiếng Việt",
                "https://example.com/news/1",
                "2026-09-15 10:00:00",
                "Tóm tắt tin tức tiếng Việt chuẩn chỉ.",
                "https://example.com/img.jpg",
                "Báo Tài Chính",
                "Crypto",
                List.of("BTC"),
                "BULLISH",
                0.8
        );
        when(aiNewsService.getLiveAiNewsSyncResult(any(), anyInt(), anyBoolean()))
                .thenReturn(NewsSyncResult.ok(List.of(dummyNews)));

        mockMvc.perform(post("/api/news/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", sameNewsRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(2))
                .andExpect(jsonPath("$.remainingRefreshes").value(3));

        mockMvc.perform(post("/api/news/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", sameNewsRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(2))
                .andExpect(jsonPath("$.remainingRefreshes").value(3));

        org.mockito.Mockito.verify(aiNewsService, org.mockito.Mockito.times(1))
                .getLiveAiNewsSyncResult(any(), anyInt(), org.mockito.Mockito.eq(true));
    }

    @Test
    @DisplayName("3c. Replay khi request đầu bị lỗi provider: tự động hoàn trả quota, lỗi không mất lượt")
    void testReplayWhenProviderFailedOnFirstRequestDoesNotCallProviderSecondTime() throws Exception {
        String sameErrorRequestId = UUID.randomUUID().toString();

        // Giả lập provider ném ForecastUnavailableException khi làm mới cưỡng bức
        when(forecastService.generateForecast(any(ForecastRequest.class), anyBoolean()))
                .thenThrow(new ForecastUnavailableException("Gemini 429 quota exhausted"));
        // Đảm bảo cache rỗng
        when(forecastService.getFreshForecastFromCacheOnly(anyString()))
                .thenReturn(Optional.empty());

        // 1. Request đầu: bị lỗi provider -> quota được hoàn trả tự động, không mất lượt
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", sameErrorRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.usedRefreshes").value(0))
                .andExpect(jsonPath("$.remainingRefreshes").value(5));

        // Kiểm tra used_count trong DB vẫn là 0 vì đã được hoàn trả
        RefreshQuotaDto statusAfterFirst = quotaService.getQuotaStatus(user1.getId());
        assertEquals(0, statusAfterFirst.getUsedRefreshes());
        assertEquals(5, statusAfterFirst.getRemainingRefreshes());
    }

    @Test
    @DisplayName("3b. Hai request đồng thời ở lượt đầu tiên khi chưa có bản ghi quota: không bị lỗi transaction race")
    void testConcurrentRequestsOnFirstTurnWhenNoQuotaRowExists() throws Exception {
        quotaRepository.deleteAll();
        eventRepository.deleteAll();
        assertTrue(quotaRepository.findByUserIdAndQuotaDate(user2.getId(), quotaService.getCurrentVietnamDate()).isEmpty());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            latch.await();
            try {
                quotaService.acquireRefreshQuota(user2.getId(), UUID.randomUUID().toString(), "FORECAST");
                successCount.incrementAndGet();
            } catch (Exception ex) {
                errorCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            latch.await();
            try {
                quotaService.acquireRefreshQuota(user2.getId(), UUID.randomUUID().toString(), "NEWS");
                successCount.incrementAndGet();
            } catch (Exception ex) {
                errorCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        latch.countDown();
        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(2, successCount.get(), "Cả 2 request hợp lệ lượt đầu phải thành công");
        assertEquals(0, errorCount.get(), "Không được có lỗi abort transaction do unique constraint");

        RefreshQuotaDto status = quotaService.getQuotaStatus(user2.getId());
        assertEquals(2, status.getUsedRefreshes());
        assertEquals(3, status.getRemainingRefreshes());
    }

    @Test
    @DisplayName("4. Hai request đồng thời khi còn một lượt: đúng một request thành công")
    void testConcurrentRequestsWhenOnlyOneRemaining() throws Exception {
        // Sử dụng 4 lượt trước
        for (int i = 0; i < 4; i++) {
            quotaService.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "FORECAST");
        }

        RefreshQuotaDto current = quotaService.getQuotaStatus(user1.getId());
        assertEquals(4, current.getUsedRefreshes());
        assertEquals(1, current.getRemainingRefreshes());

        // Hai luồng tranh chấp lượt thứ 5 cùng lúc
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            latch.await();
            try {
                quotaService.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "FORECAST");
                successCount.incrementAndGet();
            } catch (DailyRefreshLimitReachedException ex) {
                rejectedCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            latch.await();
            try {
                quotaService.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "NEWS");
                successCount.incrementAndGet();
            } catch (DailyRefreshLimitReachedException ex) {
                rejectedCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        latch.countDown(); // Kích hoạt đồng thời
        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Đúng 1 request phải thành công");
        assertEquals(1, rejectedCount.get(), "Đúng 1 request phải bị từ chối 429");

        RefreshQuotaDto finalStatus = quotaService.getQuotaStatus(user1.getId());
        assertEquals(5, finalStatus.getUsedRefreshes());
        assertEquals(0, finalStatus.getRemainingRefreshes());
    }

    @Test
    @DisplayName("5. Reset đúng 00:00 Asia/Ho_Chi_Minh bằng injectable Clock")
    void testResetAtMidnightVietnamTimeWithInjectableClock() {
        ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
        // 23:59:50 ngày 2026-09-14 tại Việt Nam
        ZonedDateTime beforeMidnight = ZonedDateTime.of(2026, 9, 14, 23, 59, 50, 0, vnZone);
        Clock customClock = Clock.fixed(beforeMidnight.toInstant(), vnZone);

        ContentRefreshQuotaService serviceWithCustomClock = new ContentRefreshQuotaService(
                quotaRepository, eventRepository, customClock, transactionManager
        );

        // Dùng hết 5 lượt trước nửa đêm
        for (int i = 0; i < 5; i++) {
            serviceWithCustomClock.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "FORECAST");
        }

        RefreshQuotaDto statusBefore = serviceWithCustomClock.getQuotaStatus(user1.getId());
        assertEquals(5, statusBefore.getUsedRefreshes());
        assertEquals(0, statusBefore.getRemainingRefreshes());
        assertEquals("2026-09-14", statusBefore.getQuotaDate());

        // Chuyển Clock sang 00:00:05 ngày 2026-09-15 tại Việt Nam (sang ngày mới)
        ZonedDateTime afterMidnight = ZonedDateTime.of(2026, 9, 15, 0, 0, 5, 0, vnZone);
        ContentRefreshQuotaService serviceAfterMidnight = new ContentRefreshQuotaService(
                quotaRepository, eventRepository, Clock.fixed(afterMidnight.toInstant(), vnZone), transactionManager
        );

        RefreshQuotaDto statusAfter = serviceAfterMidnight.getQuotaStatus(user1.getId());
        assertEquals(0, statusAfter.getUsedRefreshes(), "Phải tự động reset về 0 lượt đã dùng khi sang ngày mới");
        assertEquals(5, statusAfter.getRemainingRefreshes(), "Phải có lại 5 lượt làm mới");
        assertEquals("2026-09-15", statusAfter.getQuotaDate());

        // Lượt mới được thực hiện trơn tru
        assertDoesNotThrow(() -> {
            serviceAfterMidnight.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "FORECAST");
        });
    }

    @Test
    @DisplayName("6. Initial load và GET quota status không trừ lượt")
    void testInitialLoadAndGetQuotaDoNotDeduct() throws Exception {
        // Gọi GET quota status
        mockMvc.perform(get("/api/refresh-quota/status")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedRefreshes").value(0))
                .andExpect(jsonPath("$.remainingRefreshes").value(5));

        // Gọi GET forecast theo symbol (tải thông thường)
        mockMvc.perform(get("/api/forecast/BTCUSDT"))
                .andExpect(status().isOk());

        // Gọi GET news sync (tải thông thường)
        mockMvc.perform(get("/api/news/sync"))
                .andExpect(status().isOk());

        // Xác nhận không bị trừ bất kỳ lượt nào
        RefreshQuotaDto current = quotaService.getQuotaStatus(user1.getId());
        assertEquals(0, current.getUsedRefreshes());
        assertEquals(5, current.getRemainingRefreshes());
    }

    @Test
    @DisplayName("7. Provider failure sau khi bắt đầu được hoàn trả lượt (lỗi không mất lượt)")
    void testProviderFailureAfterAcquisitionRefundsQuota() throws Exception {
        // Giả lập provider gặp lỗi sau khi đã gọi
        when(forecastService.generateForecast(any(ForecastRequest.class), eq(true)))
                .thenThrow(new ForecastUnavailableException("Gemini quota error"));

        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer " + token1)
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.usedRefreshes").value(0))
                .andExpect(jsonPath("$.remainingRefreshes").value(5));

        // Kiểm tra trong DB: lượt đã được hoàn trả, không bị mất lượt
        RefreshQuotaDto status = quotaService.getQuotaStatus(user1.getId());
        assertEquals(0, status.getUsedRefreshes());
        assertEquals(5, status.getRemainingRefreshes());
    }

    @Test
    @DisplayName("8. Request chưa được xác thực không tạo quota event")
    void testUnauthenticatedRequestDoesNotCreateQuotaEvent() throws Exception {
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Client-Request-ID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForecastRequest("BTCUSDT", "24H_7D"))))
                .andExpect(status().isUnauthorized());

        // Xác nhận không có event nào được lưu trong DB
        assertEquals(0, eventRepository.count());
        assertEquals(0, quotaRepository.count());
    }

    @Test
    @DisplayName("9. Hai tài khoản có hạn mức hoàn toàn độc lập")
    void testTwoAccountsHaveIndependentQuotas() throws Exception {
        // User 1 dùng 3 lượt
        for (int i = 0; i < 3; i++) {
            quotaService.acquireRefreshQuota(user1.getId(), UUID.randomUUID().toString(), "FORECAST");
        }

        // User 2 dùng 1 lượt
        quotaService.acquireRefreshQuota(user2.getId(), UUID.randomUUID().toString(), "NEWS");

        RefreshQuotaDto q1 = quotaService.getQuotaStatus(user1.getId());
        assertEquals(3, q1.getUsedRefreshes());
        assertEquals(2, q1.getRemainingRefreshes());

        RefreshQuotaDto q2 = quotaService.getQuotaStatus(user2.getId());
        assertEquals(1, q2.getUsedRefreshes());
        assertEquals(4, q2.getRemainingRefreshes());
    }

    @Test
    @DisplayName("10. Flyway Migration V9 chạy an toàn trên H2 PostgreSQL mode")
    void testFlywayV9MigrationRunsCleanlyOnH2PostgreSql() throws Exception {
        String dbUrl = "jdbc:h2:mem:v9_migration_test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            // Tạo bảng users giả lập
            stmt.execute("CREATE TABLE users (" +
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "email VARCHAR(255) NOT NULL)");

            // Đọc và chạy nội dung file V9__add_content_refresh_quota.sql
            try (InputStream is = getClass().getResourceAsStream("/db/migration/V9__add_content_refresh_quota.sql")) {
                assertNotNull(is, "File V9 migration phải tồn tại trong classpath db/migration");
                String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // Chạy từng lệnh SQL
                for (String command : sql.split(";")) {
                    String trimmed = command.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
            }

            // Kiểm tra bảng và unique constraint hoạt động
            stmt.execute("INSERT INTO users (id, email) VALUES (1, 'test@fnmf.com')");
            stmt.execute("INSERT INTO user_daily_refresh_quotas (user_id, quota_date, used_count) VALUES (1, CURRENT_DATE, 1)");

            // Chèn trùng lặp user_id và quota_date phải ném lỗi Unique Constraint
            assertThrows(Exception.class, () -> {
                stmt.execute("INSERT INTO user_daily_refresh_quotas (user_id, quota_date, used_count) VALUES (1, CURRENT_DATE, 2)");
            });

            // Kiểm tra bảng content_refresh_events
            stmt.execute("INSERT INTO content_refresh_events (user_id, quota_date, client_request_id, module, status) " +
                    "VALUES (1, CURRENT_DATE, 'req-uuid-1', 'FORECAST', 'ACCEPTED')");

            // Chèn trùng client_request_id phải ném lỗi
            assertThrows(Exception.class, () -> {
                stmt.execute("INSERT INTO content_refresh_events (user_id, quota_date, client_request_id, module, status) " +
                        "VALUES (1, CURRENT_DATE, 'req-uuid-1', 'NEWS', 'ACCEPTED')");
            });
        }
    }
}
