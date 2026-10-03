package com.llmgateway;

import com.llmgateway.filter.RateLimitFilter;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử toàn diện các ranh giới bảo mật đã gia cố:
 * 1. Nguyên tắc Fail-Closed cho JWT Secret (không nhận secret rỗng, thiếu hoặc quá ngắn).
 * 2. Rate Limiting trên /api/auth/login, /api/trade/*, /api/admin/* và API tốn tài nguyên.
 * 3. Chống giả mạo IP (IP Spoofing) qua Header X-Forwarded-For do client tự gửi.
 * 4. Kiểm thử Bot burst nhận 429 trong khi người dùng hợp lệ khác vẫn truy cập bình thường.
 * 5. Kiểm tra bind loopback 127.0.0.1 và bảo vệ các cổng/admin endpoint.
 */
public class SecurityAndRateLimitBoundaryTest {

    private RateLimitFilter rateLimitFilter;
    private JwtUtil validJwtUtil;
    private static final String STRONG_JWT_SECRET = "StrongTestJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345";

    @BeforeEach
    public void setUp() {
        validJwtUtil = new JwtUtil(STRONG_JWT_SECRET, 86400000L);
        rateLimitFilter = new RateLimitFilter(validJwtUtil);
        rateLimitFilter.clearCounters();
    }

    // =========================================================================
    // 1. JWT FAIL-CLOSED VALIDATION
    // =========================================================================

    @Test
    @DisplayName("JWT Fail-Closed: Khởi tạo với secret null phải ném ngoại lệ IllegalStateException")
    public void testJwtUtil_nullSecret_failsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            new JwtUtil(null, 86400000L);
        });
        assertTrue(ex.getMessage().contains("FAIL-CLOSED"));
    }

    @Test
    @DisplayName("JWT Fail-Closed: Khởi tạo với secret rỗng hoặc khoảng trắng phải ném ngoại lệ")
    public void testJwtUtil_emptySecret_failsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            new JwtUtil("   ", 86400000L);
        });
        assertTrue(ex.getMessage().contains("FAIL-CLOSED"));
    }

    @Test
    @DisplayName("JWT Fail-Closed: Khởi tạo với secret ngắn hơn 32 ký tự (< 256 bits) phải bị từ chối")
    public void testJwtUtil_shortSecret_failsClosed() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            new JwtUtil("too_short_secret_key_12345", 86400000L);
        });
        assertTrue(ex.getMessage().contains("FAIL-CLOSED"));
        assertTrue(ex.getMessage().contains("32 characters"));
    }

    @Test
    @DisplayName("JWT Hợp lệ: Secret đủ độ dài tạo và giải mã token chính xác")
    public void testJwtUtil_validSecret_succeeds() {
        String token = validJwtUtil.generateToken("security@fnmf.com", 777L);
        assertNotNull(token);
        assertTrue(validJwtUtil.validateToken(token));
        assertEquals("security@fnmf.com", validJwtUtil.getEmailFromToken(token));
        assertEquals(777L, validJwtUtil.getUserIdFromToken(token));
    }

    // =========================================================================
    // 2. RATE LIMITING: LOGIN / AUTH BRUTE FORCE & BOT BURST
    // =========================================================================

    @Test
    @DisplayName("Rate Limit Login: Bot gọi liên tiếp 15 lần phải nhận HTTP 429 từ lần thứ 11")
    public void testRateLimit_authLogin_botBurstGets429() throws Exception {
        String botIp = "192.0.2.10";

        // 10 request đầu tiên phải được chuyển tiếp qua filter (chain.doFilter được gọi)
        for (int i = 1; i <= 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
            req.setRemoteAddr(botIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            rateLimitFilter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus(), "Request thứ " + i + " của bot không được nhận 429");
        }

        // Request thứ 11 của bot phải bị chặn trả về HTTP 429
        MockHttpServletRequest blockedReq = new MockHttpServletRequest("POST", "/api/auth/login");
        blockedReq.setRemoteAddr(botIp);
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        rateLimitFilter.doFilter(blockedReq, blockedRes, chain);
        assertEquals(429, blockedRes.getStatus(), "Request thứ 11 của bot bắt buộc phải nhận HTTP 429");
        assertTrue(blockedRes.getContentAsString().contains("Too many requests"));
    }

    // =========================================================================
    // 3. ANTI-IP-SPOOFING: KHÔNG TIN X-FORWARDED-FOR TỪ CLIENT
    // =========================================================================

    @Test
    @DisplayName("Anti-IP-Spoofing: Bot cố tình fake header X-Forwarded-For vẫn bị chặn 429 dựa trên RemoteAddr")
    public void testRateLimit_antiSpoofing_ignoresXForwardedFor() throws Exception {
        String botIp = "192.0.2.55";

        // Gửi 10 request để chạm ngưỡng
        for (int i = 1; i <= 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
            req.setRemoteAddr(botIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, new MockFilterChain());
        }

        // Request thứ 11: Bot cố tình gắn IP giả qua X-Forwarded-For để lách rào
        MockHttpServletRequest spoofedReq = new MockHttpServletRequest("POST", "/api/auth/login");
        spoofedReq.setRemoteAddr(botIp); // IP thật của kết nối socket
        spoofedReq.addHeader("X-Forwarded-For", "203.0.113.88, 10.0.0.1"); // IP giả mạo
        MockHttpServletResponse spoofedRes = new MockHttpServletResponse();

        rateLimitFilter.doFilter(spoofedReq, spoofedRes, new MockFilterChain());

        assertEquals(429, spoofedRes.getStatus(),
                "Hệ thống tuyệt đối không được tin header X-Forwarded-For; bot vẫn phải nhận 429");
    }

    // =========================================================================
    // 4. USER ISOLATION: NGƯỜI DÙNG HỢP LỆ VẪN HOẠT ĐỘNG BÌNH THƯỜNG
    // =========================================================================

    @Test
    @DisplayName("User Isolation: Trong khi bot bị chặn 429, người dùng hợp lệ từ IP khác vẫn thực hiện login bình thường")
    public void testRateLimit_legitimateUserRemainsOperationalDuringAttack() throws Exception {
        String botIp = "192.0.2.99";
        String legitimateUserIp = "198.51.100.12";

        // Bot làm ngập limit
        for (int i = 1; i <= 11; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
            req.setRemoteAddr(botIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, new MockFilterChain());
            if (i > 10) {
                assertEquals(429, res.getStatus());
            }
        }

        // Người dùng hợp lệ gửi request ngay sau đó từ IP riêng
        MockHttpServletRequest userReq = new MockHttpServletRequest("POST", "/api/auth/login");
        userReq.setRemoteAddr(legitimateUserIp);
        MockHttpServletResponse userRes = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        rateLimitFilter.doFilter(userReq, userRes, chain);

        assertEquals(200, userRes.getStatus(),
                "Người dùng hợp lệ từ IP khác tuyệt đối không bị ảnh hưởng bởi bot bị rate-limit");
    }

    // =========================================================================
    // 5. RATE LIMITING: TRADE & ADMIN ENDPOINTS
    // =========================================================================

    @Test
    @DisplayName("Rate Limit Trade: Lệnh giao dịch (/api/trade/order) bị giới hạn tối đa 30 req/phút")
    public void testRateLimit_tradeEndpoint_limitedTo30Requests() throws Exception {
        String userToken = "Bearer " + validJwtUtil.generateToken("trader@fnmf.com", 123L);

        for (int i = 1; i <= 30; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/trade/order");
            req.addHeader("Authorization", userToken);
            req.setRemoteAddr("10.10.10.1");
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }

        // Request 31 phải nhận 429
        MockHttpServletRequest req31 = new MockHttpServletRequest("POST", "/api/trade/order");
        req31.addHeader("Authorization", userToken);
        req31.setRemoteAddr("10.10.10.1");
        MockHttpServletResponse res31 = new MockHttpServletResponse();
        rateLimitFilter.doFilter(req31, res31, new MockFilterChain());

        assertEquals(429, res31.getStatus(), "Thao tác giao dịch thứ 31 của cùng user phải bị chặn 429");
    }

    @Test
    @DisplayName("Rate Limit Admin: Thao tác admin (/api/admin/set-balance) bị giới hạn 20 req/phút")
    public void testRateLimit_adminEndpoint_limitedTo20Requests() throws Exception {
        String adminToken = "Bearer " + validJwtUtil.generateToken("admin@fnmf.com", 1L);

        for (int i = 1; i <= 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/admin/set-balance");
            req.addHeader("Authorization", adminToken);
            req.setRemoteAddr("10.10.10.2");
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }

        MockHttpServletRequest req21 = new MockHttpServletRequest("POST", "/api/admin/set-balance");
        req21.addHeader("Authorization", adminToken);
        req21.setRemoteAddr("10.10.10.2");
        MockHttpServletResponse res21 = new MockHttpServletResponse();
        rateLimitFilter.doFilter(req21, res21, new MockFilterChain());

        assertEquals(429, res21.getStatus(), "Thao tác admin thứ 21 phải bị chặn 429");
    }

    @Test
    @DisplayName("Rate Limit Heavy Resource: /api/forecast/analyze bị giới hạn 10 req/phút")
    public void testRateLimit_forecastHeavyResource_limitedTo10Requests() throws Exception {
        String clientIp = "10.10.10.9";

        for (int i = 1; i <= 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/forecast/analyze");
            req.setRemoteAddr(clientIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitFilter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }

        MockHttpServletRequest req11 = new MockHttpServletRequest("POST", "/api/forecast/analyze");
        req11.setRemoteAddr(clientIp);
        MockHttpServletResponse res11 = new MockHttpServletResponse();
        rateLimitFilter.doFilter(req11, res11, new MockFilterChain());

        assertEquals(429, res11.getStatus(), "API forecast nặng thứ 11 phải bị chặn 429");
    }

    // =========================================================================
    // 6. CONFIGURATION VERIFICATION (LOOPBACK BINDING & FAIL-CLOSED)
    // =========================================================================

    @Test
    @DisplayName("Cấu hình application.properties: Bind loopback 127.0.0.1 và H2 console không cho phép kết nối ngoài")
    public void testApplicationProperties_bindsLoopbackAndDisablesExternalConsole() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(is, "application.properties phải tồn tại");
            props.load(is);
        }

        assertEquals("${SERVER_ADDRESS:127.0.0.1}", props.getProperty("server.address"),
                "Mặc định server phải bind loopback 127.0.0.1");
        assertEquals("${JWT_SECRET}", props.getProperty("jwt.secret"),
                "JWT Secret phải fail-closed, không fallback rỗng");
        assertEquals("false", props.getProperty("spring.h2.console.settings.web-allow-others"),
                "H2 console tuyệt đối không cho phép truy cập từ máy ngoài");
    }

    @Test
    @DisplayName("Cấu hình application-test.properties: Khóa chặt DB loopback và tắt Swagger mặc định")
    public void testTestProperties_loopbackAndSwaggerProtection() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-test.properties")) {
            assertNotNull(is, "application-test.properties phải tồn tại");
            props.load(is);
        }

        assertEquals("${SERVER_ADDRESS:127.0.0.1}", props.getProperty("server.address"));
        assertEquals("${TEST_SWAGGER_ENABLED:false}", props.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("${TEST_SWAGGER_ENABLED:false}", props.getProperty("springdoc.api-docs.enabled"));
        assertTrue(props.getProperty("spring.datasource.url").contains("127.0.0.1"),
                "PostgreSQL trong test profile phải kết nối loopback 127.0.0.1:5432");
    }
}
