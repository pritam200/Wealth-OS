package com.marketai.amfi.controller;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.service.AmfiNavService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mf-nav")
@RequiredArgsConstructor
public class AmfiNavController {

    private final AmfiNavService amfiNavService;

    @GetMapping
    public ResponseEntity<AmfiNavResult> lookup(@RequestParam String name) {
        AmfiNavResult result = amfiNavService.findByName(name);
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }
}
