package com.marketai.news.repository;

import com.marketai.news.entity.News;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsRepository extends JpaRepository<News, Long> {
    boolean existsByUrl(String url);
    Page<News> findByOrderByPublishedAtDesc(Pageable pageable);
    List<News> findByRelatedSymbolOrderByPublishedAtDesc(String symbol);
}
