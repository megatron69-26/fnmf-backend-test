package com.llmgateway.controller;

import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AdminService;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.AuthService;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * SECURE FNMF CLOUD ADMIN CONTROL CENTER
 * =====================================================================
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final AiNewsService aiNewsService;

    @Autowired
    private Environment env;

    public AdminController(AdminService adminService,
                           JdbcTemplate jdbcTemplate,
                           JwtUtil jwtUtil,
                           UserRepository userRepository,
                           AiNewsService aiNewsService) {
        this.adminService = adminService;
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.aiNewsService = aiNewsService;
    }

    private static class AdminAuthResult {
        final HttpStatus status;
        final User adminUser;
        final String message;

        AdminAuthResult(HttpStatus status, User adminUser, String message) {
            this.status = status;
            this.adminUser = adminUser;
            this.message = message;
        }

        boolean isAuthorized() {
            return status == HttpStatus.OK && adminUser != null;
        }

        ResponseEntity<Map<String, Object>> toErrorResponse() {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", message);
            return ResponseEntity.status(status).body(err);
        }
    }

    /**
     * Xác thực Token và Role ADMIN:
     * - Thiếu token / sai format / token không hợp lệ / hết hạn -> 401 Unauthorized
     * - User không tồn tại -> 401 Unauthorized
     * - User tồn tại nhưng role != ADMIN -> 403 Forbidden
     * - User có role == ADMIN -> 200 OK
     */
    private AdminAuthResult verifyAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Authorization token is missing or malformed");
        }
        String token = authHeader.substring(7).trim();
        if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.isEmpty() || !jwtUtil.validateToken(token)) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Invalid or expired JWT token");
        }
        String email = jwtUtil.getEmailFromToken(token);
        if (email == null || email.isBlank()) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Invalid token claims");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        }
        if (user == null) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "User associated with token not found");
        }

        if (user.getRole() != UserRole.ADMIN) {
            return new AdminAuthResult(HttpStatus.FORBIDDEN, user, "Access Denied: You do not have ADMIN privileges");
        }

        return new AdminAuthResult(HttpStatus.OK, user, null);
    }

    private String extractTargetEmailOrId(Map<String, Object> body) {
        if (body == null) return null;
        Object val = body.get("email");
        if (val == null) val = body.get("userId");
        if (val == null) val = body.get("identifier");
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    @PatchMapping("/users/{userId}/email")
    public ResponseEntity<Map<String, Object>> updateUserEmail(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long userId,
            @RequestBody Map<String, String> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        if (body == null || !body.containsKey("email") || body.get("email") == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Email mới không được để trống"));
        }

        String newEmail = body.get("email");
        try {
            Map<String, Object> res = adminService.updateUserEmail(auth.adminUser.getEmail(), userId, newEmail);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/set-balance")
    public ResponseEntity<Map<String, Object>> setBalance(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                          @RequestBody Map<String, Object> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String target = extractTargetEmailOrId(body);
        if (target == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Email người dùng không được để trống"));
        }

        if (body == null || !body.containsKey("balance") || body.get("balance") == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không được để trống"));
        }

        Object balObj = body.get("balance");
        if (balObj instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không hợp lệ"));
        }
        if (balObj instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không hợp lệ"));
        }

        String balStr = balObj.toString().trim();
        if (balStr.isEmpty() || balStr.equalsIgnoreCase("NaN") || balStr.contains("Infinity")) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không hợp lệ"));
        }

        BigDecimal balance;
        try {
            balance = new BigDecimal(balStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không hợp lệ"));
        }

        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư phải lớn hơn hoặc bằng 0"));
        }

        log.info("ADMIN AUDIT | admin={} | action=SET_BALANCE | target={} | balance={}",
                auth.adminUser.getEmail(), target, balance);

        try {
            Map<String, Object> res = adminService.setBalance(auth.adminUser.getEmail(), target, balance);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/db/query")
    @Profile({"dev", "local"})
    public ResponseEntity<Map<String, Object>> executeDbQuery(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                              @RequestBody Map<String, String> body) {
        if (env != null && (Arrays.asList(env.getActiveProfiles()).contains("prod") || Arrays.asList(env.getActiveProfiles()).contains("test"))) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint is strictly disabled in PROD and TEST environments.");
        }

        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String sql = body.getOrDefault("sql", "SELECT * FROM USERS").trim();
        String sqlUpper = sql.toUpperCase();

        if (sqlUpper.contains("UPDATE ") || sqlUpper.contains("DELETE ") ||
            sqlUpper.contains("DROP ") || sqlUpper.contains("TRUNCATE ") ||
            sqlUpper.contains("ALTER ") || sqlUpper.contains("INSERT ")) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", "Security Policy: Data manipulation commands (UPDATE/DELETE/DROP/etc) are strictly prohibited.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        Map<String, Object> res = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            res.put("status", "SUCCESS");
            res.put("type", "SELECT");
            res.put("rowCount", rows.size());
            res.put("data", rows);
        } catch (Exception e) {
            res.put("status", "ERROR");
            res.put("error", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }

    @GetMapping("/db/overview")
    public ResponseEntity<Map<String, Object>> getDatabaseOverview(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        Map<String, Object> res = new HashMap<>();
        try {
            List<Map<String, Object>> rawUsers = jdbcTemplate.queryForList(
                    "SELECT id, email, full_name, avatar_url, role, created_at FROM USERS ORDER BY id ASC"
            );
            List<Map<String, Object>> users = new java.util.ArrayList<>();
            for (Map<String, Object> u : rawUsers) {
                Map<String, Object> userMap = new HashMap<>(u);
                Object emailObj = userMap.get("email");
                if (emailObj == null) emailObj = userMap.get("EMAIL");
                String emailStr = emailObj != null ? emailObj.toString() : "";
                boolean valid = AuthService.isValidEmail(emailStr);
                userMap.put("needsEmailUpdate", !valid);
                userMap.put("validEmail", valid);
                users.add(userMap);
            }

            List<Map<String, Object>> wallets = jdbcTemplate.queryForList(
                    "SELECT id, user_id, balance_usd, initial_balance, created_at, updated_at FROM WALLETS ORDER BY id ASC"
            );

            res.put("users", users);
            res.put("wallets", wallets);
            return ResponseEntity.ok(res);
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("ADMIN DATABASE OVERVIEW FAILED | admin={} | error={}",
                    auth.adminUser.getEmail(), e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", "Không thể tải dữ liệu quản trị");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        } catch (Exception e) {
            log.error("ADMIN DATABASE OVERVIEW UNEXPECTED ERROR | admin={} | error={}",
                    auth.adminUser.getEmail(), e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", "Không thể tải dữ liệu quản trị");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }
}
