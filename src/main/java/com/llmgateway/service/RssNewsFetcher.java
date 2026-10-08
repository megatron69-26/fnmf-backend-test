package com.llmgateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Thu thập và bóc tách tin tức từ RSS feed thực tế (mặc định CoinDesk RSS).
 * Sử dụng DocumentBuilder an toàn chống XXE.
 */
@Component
public class RssNewsFetcher {

    private static final Logger log = LoggerFactory.getLogger(RssNewsFetcher.class);
    private static final Pattern HTML_TAGS_PATTERN = Pattern.compile("<[^>]*>");

    private final HttpClient httpClient;

    public static class RssArticleItem {
        private final String title;
        private final String link;
        private final String description;
        private final LocalDateTime pubDate;
        private final String author;
        private final String source;
        private final String bannerImage;

        public RssArticleItem(String title, String link, String description,
                              LocalDateTime pubDate, String author, String source, String bannerImage) {
            this.title = title;
            this.link = link;
            this.description = description;
            this.pubDate = pubDate;
            this.author = author;
            this.source = source;
            this.bannerImage = bannerImage;
        }

        public String getTitle() { return title; }
        public String getLink() { return link; }
        public String getDescription() { return description; }
        public LocalDateTime getPubDate() { return pubDate; }
        public String getAuthor() { return author; }
        public String getSource() { return source; }
        public String getBannerImage() { return bannerImage; }
    }

    public RssNewsFetcher() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public RssNewsFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<RssArticleItem> fetchRssFeed(String rssUrl) {
        if (rssUrl == null || rssUrl.isBlank()) {
            return Collections.emptyList();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rssUrl.trim()))
                    .header("User-Agent", "FNMF-NewsWorker/1.0 (Android-Backend-Bridge)")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Lỗi khi tải RSS từ {} | HTTP status: {}", rssUrl, response.statusCode());
                return Collections.emptyList();
            }

            return parseRssXml(response.body(), rssUrl);
        } catch (Exception e) {
            log.error("Ngoại lệ khi tải RSS từ {}: {}", rssUrl, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public List<RssArticleItem> parseRssXml(String xmlContent, String sourceUrl) {
        if (xmlContent == null || xmlContent.isBlank()) {
            return Collections.emptyList();
        }

        List<RssArticleItem> items = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Cấu hình chống XXE bảo mật tuyệt đối
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
            doc.getDocumentElement().normalize();

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                if (itemNodes.item(i) instanceof Element element) {
                    String title = getElementText(element, "title");
                    String link = getElementText(element, "link");
                    String rawDesc = getElementText(element, "description");
                    String cleanDesc = cleanHtml(rawDesc);
                    String pubDateStr = getElementText(element, "pubDate");
                    String creator = getElementText(element, "dc:creator");
                    if (creator == null || creator.isBlank()) {
                        creator = getElementText(element, "author");
                    }

                    String bannerImage = null;
                    NodeList mediaNodes = element.getElementsByTagName("media:content");
                    if (mediaNodes.getLength() > 0 && mediaNodes.item(0) instanceof Element mediaEl) {
                        bannerImage = mediaEl.getAttribute("url");
                    }
                    if (bannerImage == null || bannerImage.isBlank()) {
                        NodeList enclosureNodes = element.getElementsByTagName("enclosure");
                        if (enclosureNodes.getLength() > 0 && enclosureNodes.item(0) instanceof Element encEl) {
                            bannerImage = encEl.getAttribute("url");
                        }
                    }

                    LocalDateTime pubDateTime = parsePubDate(pubDateStr);
                    String publisher = NewsPublisherResolver.resolvePublisher(null, link != null ? link : sourceUrl);
                    if (publisher == null || publisher.isBlank()) {
                        publisher = "CoinDesk";
                    }

                    if (title != null && !title.isBlank() && link != null && !link.isBlank()) {
                        items.add(new RssArticleItem(
                                title.trim(),
                                link.trim(),
                                cleanDesc != null ? cleanDesc.trim() : "",
                                pubDateTime,
                                creator != null && !creator.isBlank() ? creator.trim() : null,
                                publisher,
                                bannerImage != null && !bannerImage.isBlank() ? bannerImage.trim() : null
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Lỗi khi parse XML RSS: {}", e.getMessage(), e);
        }
        return items;
    }

    private String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0 && list.item(0) != null) {
            return list.item(0).getTextContent();
        }
        return null;
    }

    private String cleanHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = HTML_TAGS_PATTERN.matcher(text).replaceAll(" ");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    private LocalDateTime parsePubDate(String pubDateStr) {
        if (pubDateStr == null || pubDateStr.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            // Định dạng RFC 1123 chuẩn RSS: "Thu, 08 Oct 2026 12:00:00 GMT"
            return ZonedDateTime.parse(pubDateStr.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(pubDateStr.trim(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception ignored) {
                return LocalDateTime.now();
            }
        }
    }
}
