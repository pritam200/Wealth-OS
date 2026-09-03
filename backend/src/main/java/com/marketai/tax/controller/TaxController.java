package com.marketai.tax.controller;

import com.marketai.auth.entity.User;
import com.marketai.tax.dto.TaxResponse;
import com.marketai.tax.service.TaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tax")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService service;

    @GetMapping("/summary")
    public ResponseEntity<TaxResponse> summary(@AuthenticationPrincipal User user,
                                               @RequestParam(required = false) Integer fyStartYear) {
        return ResponseEntity.ok(service.summary(user.getId(), fyStartYear));
    }
}
