# FNMF Backend & AI Gateway

Tai lieu doc ma nguon theo luong nghiep vu: [FNMF Codebase Walkthrough](docs/codebase-walkthrough/README.md)

**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, High-Integrity Financial Data Pipeline & AI Processing Layer*

- **Nguoi thuc hien:** Dang Duc Khoi (Backend & Data Developer)
- **Cong nghe cot loi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, H2 Database (Local Dev/Test) / PostgreSQL (Railway Production), Flyway Migrations (V1 - V10), Spring Security (BCrypt), JJWT, Binance REST & WebSocket, Alpaca Market Data API (IEX Feed, sort=desc newest bars), Twelve Data API, Google Gemini AI (3 Shards độc lập), OpenAPI 3.0.
- **Base URL Local:** `http://localhost:8083`
- **Base URL Production (Railway):** `https://fnmf-backend-production.up.railway.app/`
- **Swagger UI (Local Dev):** `http://localhost:8083/swagger-ui.html`

---

## 1. Kiến Trúc Fixed Provider Sharding & Gemini AI Shards (v1.1.23)

Hệ thống định tuyến dữ liệu cố định (Fixed Provider Sharding) đối xứng 1-to-1 giữa Nhà cung cấp dữ liệu thị trường và Shard Gemini AI:

| Phân Nhóm Tài Sản | Danh Mục Mã | Nhà Cung Cấp Dữ Liệu | Gemini AI Shard | Đặc Tả Kỹ Thuật |
| :--- | :--- | :--- | :--- | :--- |
| **Crypto & Vàng** (3 mã) | `BTCUSDT`, `ETHUSDT`, `XAUUSD` | **Binance** | **Shard 1** (`GEMINI_SHARD_1`) | REST Klines + WebSocket tick real-time |
| **Cổ Phiếu Mỹ Nhóm A** (4 mã) | `AAPL`, `MSFT`, `NVDA`, `GOOGL` | **Alpaca** | **Shard 2** (`GEMINI_SHARD_2`) | IEX feed, `sort=desc`, window 5d/90d lấy 30 nến mới nhất |
| **Cổ Phiếu Mỹ Nhóm B** (4 mã) | `TSLA`, `AMZN`, `META`, `JPM` | **Twelve Data** | **Shard 3** (`GEMINI_SHARD_3`) | `/time_series` intraday 1m & daily |

### Quy Tắc Bất Biến Về Độ Tin Cậy & Bảo Mật:
1. **Zero Fake Data:** Không tự bịa nến, giá giả hay tin tức giả. Khi provider lỗi $\to$ trả HTTP 503 hoặc cache cũ kèm `stale=true`.
2. **Cấm Fallback Chéo Shard:** Khi một Gemini Shard gặp sự cố (403, 503, thiếu key), hệ thống **fail-closed** ngay với `FORECAST_UNAVAILABLE`. Tuyệt đối không gọi chéo sang shard khác.
3. **Bảo Vệ Polling Retry (24h Cooldown):** `StockMarketService` lưu `geminiAttemptTimestampMap`. Khuyến nghị AI (kể cả khi thất bại/null) được giữ trong 24h, ngăn việc vòng lặp polling 60s của client gọi lại Gemini liên tục.
4. **Phân Tách Bộ Nhớ Đệm (Cache Isolation):**
   - **Giá Real-time:** 30 giây (`FixedMarketCacheManager.PRICE_TTL_MS`)
   - **Nến 1 phút:** 60 giây (`CANDLE_1M_TTL_MS`)
   - **Nến ngày (Daily):** 6 giờ (`CANDLE_DAILY_TTL_MS`)
   - **Tin tức thực tế:** 6 giờ (`NEWS_TTL_MS`)
   - **Dự báo AI CSDL:** 15 phút (`ForecastCacheService.FORECAST_CACHE_MINUTES`)
   - **Khuyến nghị AI:** 24 giờ (`StockMarketService.RECOMMENDATION_TTL_MS`)

---

## 2. Bảng Ánh Xạ Module, Endpoint & Mã Nguồn

| Module | Chức năng | Phương thức & Đường dẫn API | File Controller & Service |
| :--- | :--- | :--- | :--- |
| **Auth** | Đăng ký tài khoản Email-only | `POST /api/auth/register` | `AuthController.java` / `AuthService.java` |
| **Auth** | Đăng nhập tài khoản & Nhận JWT | `POST /api/auth/login` | `AuthController.java` / `AuthService.java` |
| **Auth** | Xem thông tin User & Số dư | `GET /api/auth/me` | `AuthController.java` / `AuthService.java` |
| **Market** | Giá thị trường thời gian thực (Crypto & Vàng) | `GET /api/market/prices` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Giá một mã cụ thể (Authoritative check) | `GET /api/market/price/{symbol}` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Chuỗi nến thật (1m / daily) cho tất cả tài sản | `GET /api/market/candles?symbol={symbol}&interval=1m` | `MarketController.java` / `MarketDataService.java` |
| **Stocks** | Danh mục 8 cổ phiếu tĩnh (Zero provider call) | `GET /api/stocks` | `StockController.java` / `StockMarketService.java` |
| **Stocks** | Chi tiết cổ phiếu (Giá, nến, tin tức, khuyến nghị) | `GET /api/stocks/{symbol}` | `StockController.java` / `StockMarketService.java` |
| **AI News** | Pipeline AI News tự động (PostgreSQL cache) | `GET /api/news/feed?limit=5` | `NewsAiController.java` / `AiNewsService.java` |
| **Watchlist** | Lấy / Thêm / Xóa danh mục theo dõi | `GET / POST / DELETE /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Trade** | Đặt lệnh Paper Trading (Pessimistic Lock & Idempotency) | `POST /api/trade/order` | `TradeController.java` / `TradeService.java` |
| **Trade** | Danh mục tài sản & Lịch sử lệnh | `GET /api/trade/portfolio`, `GET /api/trade/history` | `TradeController.java` / `TradeService.java` |
| **Forecast** | Dự báo AI xu hướng đa chiều (`analysisSource=GEMINI`) | `GET /api/forecast/{symbol}?timeframe=24H_7D` | `ForecastController.java` / `ForecastService.java` |
| **Payment** | Nạp / Rút tiền VNPay Sandbox Banking | `POST /api/payments/deposits`, `POST /api/payments/withdrawals` | `PaymentController.java` / `PaymentService.java` |

---

## 3. Cơ Sở Dữ Liệu & Quản Lý Migration (Flyway V1 - V10)

- **V1__init_schema.sql:** Khởi tạo bảng người dùng, ví vốn, danh mục nắm giữ, lịch sử giao dịch, watchlist, cache tin tức và dự báo thị trường.
- **V2__normalize_and_enforce_user_email.sql:** Chuẩn hóa email-only và ràng buộc `LOWER(email)`.
- **V3__holding_unique_constraint_and_client_order_id.sql:** Ràng buộc `(WALLET_ID, SYMBOL)` chống nhân đôi tài sản, `CLIENT_ORDER_ID` chống trùng lặp lệnh.
- **V4__add_payment_orders_and_checkout_token.sql:** Khởi tạo bảng `PAYMENT_ORDERS` và checkout token.
- **V5__add_wallet_ledger_audit.sql:** Sổ cái biến động số dư bất biến `WALLET_LEDGER`.
- **V6__add_payment_events_and_scale_guards.sql:** Bảng lưu vết sự kiện `PAYMENT_EVENTS` và kiểm soát scale số tiền.
- **V7__harden_payment_flow_and_order_indexes.sql:** Chỉ mục thanh toán và kiểm soát hết hạn giao dịch.
- **V8__add_forecast_source_and_metadata.sql:** Cột phân loại nguồn dự báo (`source`), metadata phân tích và chỉ số chất lượng forecast.
- **V9__add_vnpay_fields_to_payment_orders.sql:** Bổ sung trường phục vụ VNPay Sandbox Payment Gateway.
- **V10__add_ai_shard_to_market_forecasts.sql:** Bổ sung cột `ai_shard VARCHAR(50)` vào bảng `market_forecasts` để kiểm toán và bảo toàn Shard AI xuyên suốt DB cache.

---

## 4. Kiểm Thử & Xác Minh Độc Lập (v1.1.23)
- **Targeted Unit Tests:** `FixedProviderShardingTest` đạt 17/17 PASS.
- **Toàn Bộ Test Suite:** 334/334 test PASS (`mvn test`).
- **Production Deployment:** Hoàn tất triển khai trên Railway (`SUCCESS`). Migration V10 đã áp dụng thành công trên PostgreSQL.

---

## 5. Trạng thái Thử nghiệm Qwen3.5-4B & Pipeline Tin tức (Môi trường Test Note 10+)

- **Chu kỳ nạp tin:** Chuyển sang 2 giờ/lần, cấu hình linh hoạt qua `NEWS_WORKER_INTERVAL_MS`.
- **Hàng đợi & Kích hoạt tức thì:** Tách biệt bước nạp RSS và bước suy luận Qwen. Bài mới lưu thành công được đưa vào hàng đợi xử lý ngay lập tức.
- **Bộ điều phối Đơn chuyến (Shared Single-Flight):** `QwenInferenceCoordinator` điều phối dùng chung giữa News Worker và Forecast Service, ngăn ngừa xung đột tài nguyên cục bộ trên Note 10+. Request người dùng không bị phong tỏa vô tận.
- **Kiểm định chất lượng Tiếng Việt (Fail-Closed):** Endpoint Mobile `/api/mobile/news/sync` kiểm định nghiêm ngặt, loại bỏ bài `RAW_PENDING`, lỗi dịch, hoặc sót từ tiếng Anh (`locally listed`, `Russia`, ETF dịch sai). App chỉ hiển thị nội dung tiếng Việt đạt chuẩn.
- **Xử lý lỗi & Phục hồi sau Restart:** Lỗi hoặc timeout được lưu trạng thái, áp dụng retry backoff có giới hạn (5 phút -> 15 phút), không chặn các bài sau và tự động phục hồi hàng đợi khi backend khởi động lại.
- **Trạng thái thực tế & Hạn chế còn lại trên Note 10+:**
  - Mô hình `Qwen3.5-4B` đã chạy thử nghiệm thực tế với `llama-server`.
  - Lượt suy luận Forecast 4B vẫn gặp hiện tượng timeout do tổng thời gian suy luận kéo dài; **chưa được nghiệm thu chạy thường trực**.
  - Khi thiết bị hoạt động tải nặng liên tục, hiện tượng điều tiết nhiệt phần cứng (thermal throttling) làm giảm tốc độ sinh token. Dự án **chưa giải quyết thermal throttling** trên phần cứng Note 10+ và giữ nguyên cấu hình rollback Qwen2.5-1.5B cho phương án dự phòng.