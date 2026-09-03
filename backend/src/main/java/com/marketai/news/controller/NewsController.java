package com.marketai.news.controller;

import com.marketai.news.entity.News;
import com.marketai.news.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
@Tag(name = "News", description = "Market news with sentiment analysis")
public class NewsController {

    private final NewsService newsService;

    @GetMapping
    @Operation(summary = "Get latest market news (paginated)")
    public ResponseEntity<Page<News>> getLatestNews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(newsService.getLatestNews(page, Math.min(size, 50)));
    }

    @GetMapping("/symbol/{symbol}")
    @Operation(summary = "Get news related to a specific stock")
    public ResponseEntity<List<News>> getNewsBySymbol(@PathVariable String symbol) {
        return ResponseEntity.ok(newsService.getNewsBySymbol(symbol.toUpperCase()));
    }
}
