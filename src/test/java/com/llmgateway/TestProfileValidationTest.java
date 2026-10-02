package com.llmgateway;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test kiểm tra tính toàn vẹn và bảo mật của profile Test (application-test.properties).
 * Kiểm tra nguyên tắc Fail-Closed, schema protection, và chặn hoàn toàn thông tin production.
 */
public class TestProfileValidationTest {

    @Test
    public void testTestProperties_enforcesFailClosedSecretsAndPostgres() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-test.properties")) {
            assertNotNull(is, "application-test.properties phải tồn tại trong classpath");
            props.load(is);
        }

        // 1. Kiểm tra nguyên tắc Fail-Closed: Bắt buộc biến môi trường cho Secret và Password, không ghim default
        assertEquals("${TEST_JWT_SECRET}", props.getProperty("jwt.secret"),
                "JWT Secret phải bắt buộc qua biến môi trường TEST_JWT_SECRET (fail-closed)");
        assertEquals("${TEST_DATASOURCE_PASSWORD}", props.getProperty("spring.datasource.password"),
                "Mật khẩu DB phải bắt buộc qua biến môi trường TEST_DATASOURCE_PASSWORD (fail-closed)");

        // 2. Kiểm tra không chứa URL Railway production
        String dbUrl = props.getProperty("spring.datasource.url");
        assertNotNull(dbUrl);
        assertFalse(dbUrl.contains("railway.app"), "Datasource URL tuyệt đối không được trỏ về Railway");
        assertTrue(dbUrl.contains("127.0.0.1") || dbUrl.contains("localhost"), "Datasource URL phải là localhost/127.0.0.1");

        // 3. Kiểm tra bảo vệ Schema: Flyway bật và Hibernate ddl-auto là validate
        assertEquals("${TEST_FLYWAY_ENABLED:true}", props.getProperty("spring.flyway.enabled"),
                "Flyway phải được kích hoạt để kiểm soát schema");
        assertEquals("${TEST_DDL_AUTO:validate}", props.getProperty("spring.jpa.hibernate.ddl-auto"),
                "ddl-auto phải là validate để tránh tự ý thay đổi bảng");

        // 4. Kiểm tra dịch vụ ngoài không chứa key giả lập lừa dối
        assertEquals("${TEST_ALPHAVANTAGE_API_KEY:}", props.getProperty("alphavantage.api.key"),
                "Không được hardcode DEMO_KEY");
        assertEquals("${TEST_OPENAI_API_KEY:}", props.getProperty("openai.api.key"),
                "Không được hardcode MOCK_GEMINI_KEY");

        // 5. Kiểm tra tắt H2 console và swagger mặc định
        assertEquals("false", props.getProperty("spring.h2.console.enabled"));
        assertEquals("${TEST_SWAGGER_ENABLED:false}", props.getProperty("springdoc.swagger-ui.enabled"));
    }
}
