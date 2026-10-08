package com.llmgateway.repository;

import com.llmgateway.entity.NewsAiCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NewsAiCacheRepository extends JpaRepository<NewsAiCache, Long> {

    Optional<NewsAiCache> findByArticleUrl(String articleUrl);

    boolean existsByArticleUrl(String articleUrl);

    Optional<NewsAiCache> findByTitle(String title);

    List<NewsAiCache> findTop10ByOrderByPublishedAtDesc();

    List<NewsAiCache> findTop50ByOrderByPublishedAtDesc();

    List<NewsAiCache> findBySymbolOrderByPublishedAtDesc(String symbol);

    @org.springframework.data.jpa.repository.Query("SELECT n FROM NewsAiCache n WHERE n.reason = 'RAW_PENDING' OR n.reason LIKE 'QWEN_ERROR%' ORDER BY n.publishedAt DESC")
    List<NewsAiCache> findPendingOrErrorArticles();
}
