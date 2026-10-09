package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.ForecastController;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.ContentRefreshEvent;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.entity.UserDailyRefreshQuota;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.GlobalExceptionHandler;
import com.llmgateway.repository.ContentRefreshEventRepository;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.UserDailyRefreshQuotaRepository;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.service.ForecastCacheService;
import com.llmgateway.service.ForecastQualityPolicy;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.QwenForecastClient;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class QwenForecastAndQuotaPolicyBehavioralTest {

    private MarketForecastRepository forecastRepository;
    private NewsAiCacheRepository newsAiCacheRepository;
    private MarketDataService marketDataService;
    private QwenForecastClient qwenForecastClient;
    private ContentRefreshQuotaService quotaService;
    private UserDailyRefreshQuotaRepository quotaRepository;
    private ContentRefreshEventRepository eventRepository;
    private JwtUtil jwtUtil;
    private ForecastCacheService forecastCacheService;
    private ForecastService forecastService;
    private ForecastController forecastController;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() {
        forecastRepository = mock(MarketForecastRepository.class);
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        marketDataService = mock(MarketDataService.class);
        quotaService = mock(ContentRefreshQuotaService.class);
        quotaRepository = mock(UserDailyRefreshQuotaRepository.class);
        eventRepository = mock(ContentRefreshEventRepository.class);
        jwtUtil = mock(JwtUtil.class);
        objectMapper = new ObjectMapper();
        mockHttpClient = mock(HttpClient.class);

        qwenForecastClient = new QwenForecastClient(objectMapper, mockHttpClient);
        forecastCacheService = new ForecastCacheService(forecastRepository, objectMapper);
        forecastService = new ForecastService(
                forecastRepository,
                newsAiCacheRepository,
                marketDataService,
                forecastCacheService,
                null,
                qwenForecastClient
        );

        forecastController = new ForecastController(forecastService, quotaService, jwtUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(forecastController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("1. Qwen Timeout: Ném ForecastUnavailableException, fail-closed, không tạo dự báo giả")
    void testQwenTimeout_FailClosed_NoFakeData() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("Connection timed out to 127.0.0.1:8080"));

        List<MarketPriceDto> prices = List.of(
                new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), BigDecimal.ZERO, false, "BINANCE")
        );

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () ->
                qwenForecastClient.requestMarketForecast(prices, Collections.emptyList(), Collections.emptyList(), "24H_7D"));

        assertTrue(ex.getMessage().contains("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));
        // Không chứa giá giả lập
        assertFalse(ex.getMessage().contains("60000"));
    }

    @Test
    @DisplayName("2. Qwen JSON lỗi/sai định dạng: Ném ForecastUnavailableException, fail-closed")
    void testQwenJsonMalformed_FailClosed() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"Invalid non-json response text\"}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        List<MarketPriceDto> prices = List.of(
                new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), BigDecimal.ZERO, false, "BINANCE")
        );

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () ->
                qwenForecastClient.requestMarketForecast(prices, Collections.emptyList(), Collections.emptyList(), "24H_7D"));

        assertTrue(ex.getMessage().contains("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));
    }

    @Test
    @DisplayName("3. Bản dịch/Nội dung không đạt tiếng Việt: ForecastQualityPolicy từ chối")
    void testNonVietnameseContentRejected() {
        ForecastResponse englishOnly = new ForecastResponse(
                "MARKET",
                "Nhận định toàn thị trường",
                new BigDecimal("65000"),
                "BULLISH_UPTREND",
                "24H_7D",
                new BigDecimal("63000"),
                new BigDecimal("68000"),
                "HOLD",
                75,
                List.of("Strong institutional inflows observed across exchanges", "Trading volume is expanding sustainably"),
                "Technical momentum indicates positive outlook above support",
                "Macro indicators remain favorable for crypto markets",
                "QWEN",
                30,
                false,
                LocalDateTime.now()
        );

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () ->
                ForecastQualityPolicy.validateOrThrow(englishOnly));

        assertTrue(ex.getMessage().contains("tiếng Việt") || ex.getMessage().contains("chưa dịch"));
    }

    @Test
    @DisplayName("4. Cache rỗng: Trả trạng thái rõ ràng FORECAST_UNAVAILABLE (503), không tạo dự báo giả")
    void testEmptyCacheReturnsClearStatusWithoutFakeData() throws Exception {
        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Collections.emptyList());
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(null);
        when(marketDataService.getAllPrices()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/forecast/market"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));
    }

    @Test
    @DisplayName("5. Dữ liệu cũ (> 24h): Trả bản ghi có nhãn thời gian và cờ stale = true")
    void testStaleCacheReturnsWithTimestampAndStaleTrue() {
        LocalDateTime pastTime = LocalDateTime.now().minusHours(36);
        MarketForecast oldRecord = new MarketForecast(
                "MARKET",
                new BigDecimal("62000.00"),
                "SIDEWAYS_CONSOLIDATION",
                "24H_7D",
                new BigDecimal("60000.00"),
                new BigDecimal("64000.00"),
                "HOLD",
                new BigDecimal("70"),
                "[\"Dòng vốn thị trường đi ngang\", \"Khối lượng tích lũy ổn định\"]",
                "Đường giá dao động trong biên độ hẹp quanh ngưỡng hỗ trợ",
                "Thông tin vĩ mô ổn định không có đột biến",
                "QWEN",
                30
        );
        oldRecord.setCreatedAt(pastTime);

        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(List.of(oldRecord));

        Optional<ForecastResponse> result = forecastService.getMarketForecastFromDb("24H_7D");
        assertTrue(result.isPresent());
        ForecastResponse response = result.get();
        assertTrue(response.getStale(), "Bản ghi quá 24h phải có stale = true");
        assertTrue(response.isFromCache());
        assertEquals(pastTime, response.getCreatedAt(), "Nhãn thời gian gốc phải được giữ nguyên");
        assertEquals("QWEN", response.getAnalysisSource());
    }

    @Test
    @DisplayName("6. Lỗi làm mới không mất lượt: Quota được hoàn trả tự động")
    void testRefreshErrorRefundsQuota_NoTurnLost() throws Exception {
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(100L);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);

        String clientReqId = "req-error-123";
        // Ban đầu acquire trả về lượt đã trừ (used=1, remaining=4)
        when(quotaService.acquireRefreshQuota(eq(100L), eq(clientReqId), eq("FORECAST")))
                .thenReturn(new RefreshQuotaDto(5, 1, 4, LocalDate.now().toString(), false));

        // Khi lỗi xảy ra, refundRefreshQuota được gọi và hoàn trả (used=0, remaining=5)
        when(quotaService.refundRefreshQuota(eq(100L), eq(clientReqId), eq("FORECAST")))
                .thenReturn(new RefreshQuotaDto(5, 0, 5, LocalDate.now().toString(), false));

        // Mock marketDataService lỗi không có giá BTC
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(null);
        when(marketDataService.getAllPrices()).thenReturn(Collections.emptyList());
        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Collections.emptyList());

        ForecastRequest req = new ForecastRequest("MARKET", "24H_7D", clientReqId);

        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .header("Client-Request-ID", clientReqId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.usedRefreshes").value(0))
                .andExpect(jsonPath("$.remainingRefreshes").value(5));

        verify(quotaService, times(1)).refundRefreshQuota(100L, clientReqId, "FORECAST");
    }

    @Test
    @DisplayName("7. refundRefreshQuota: Chỉ hoàn trả đúng bản ghi đã trừ, không reset hàng loạt")
    void testRefundRefreshQuota_SpecificRecordOnly() {
        Clock fixedClock = Clock.fixed(
                LocalDateTime.of(2026, 9, 20, 10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                ZoneId.of("Asia/Ho_Chi_Minh")
        );
        ContentRefreshQuotaService realQuotaService = new ContentRefreshQuotaService(
                quotaRepository, eventRepository, fixedClock, null
        );

        Long userId = 200L;
        String reqId = "req-specific-1";
        LocalDate today = LocalDate.now(fixedClock);

        // Trường hợp 1: Không có event -> Bỏ qua, không refund
        when(eventRepository.findByUserIdAndClientRequestId(userId, reqId)).thenReturn(Optional.empty());
        when(quotaRepository.findByUserIdAndQuotaDate(userId, today))
                .thenReturn(Optional.of(new UserDailyRefreshQuota(userId, today, 3)));

        RefreshQuotaDto status = realQuotaService.refundRefreshQuota(userId, reqId, "FORECAST");
        assertEquals(3, status.getUsedRefreshes());
        assertEquals(2, status.getRemainingRefreshes());

        // Trường hợp 2: Có event nhưng status là REJECTED -> Bỏ qua, không refund
        ContentRefreshEvent rejectedEvent = new ContentRefreshEvent(userId, today, reqId, "FORECAST", "REJECTED");
        when(eventRepository.findByUserIdAndClientRequestId(userId, reqId)).thenReturn(Optional.of(rejectedEvent));

        status = realQuotaService.refundRefreshQuota(userId, reqId, "FORECAST");
        assertEquals(3, status.getUsedRefreshes());
        assertEquals(2, status.getRemainingRefreshes());

        // Trường hợp 3: Event ACCEPTED -> Hoàn trả đúng 1 lượt
        ContentRefreshEvent acceptedEvent = new ContentRefreshEvent(userId, today, reqId, "FORECAST", "ACCEPTED");
        UserDailyRefreshQuota quotaRow = new UserDailyRefreshQuota(userId, today, 3);
        when(eventRepository.findByUserIdAndClientRequestId(userId, reqId)).thenReturn(Optional.of(acceptedEvent));
        when(quotaRepository.findByUserIdAndQuotaDateForUpdate(userId, today)).thenReturn(Optional.of(quotaRow));

        status = realQuotaService.refundRefreshQuota(userId, reqId, "FORECAST");
        assertEquals("REFUNDED", acceptedEvent.getStatus());
        assertEquals(2, status.getUsedRefreshes());
        assertEquals(3, status.getRemainingRefreshes());
        verify(quotaRepository, times(1)).saveAndFlush(quotaRow);
    }
}
