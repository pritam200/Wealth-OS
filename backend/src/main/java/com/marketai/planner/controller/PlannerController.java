package com.marketai.planner.controller;

import com.marketai.auth.entity.User;
import com.marketai.planner.dto.PlannerDtos.*;
import com.marketai.planner.service.PlannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/planner")
@RequiredArgsConstructor
public class PlannerController {

    private final PlannerService plannerService;

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> listCategories(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(plannerService.listCategories(user.getId()));
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryResponse> addCategory(@AuthenticationPrincipal User user, @RequestBody CategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(plannerService.addCategory(user.getId(), req));
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(@AuthenticationPrincipal User user, @PathVariable Long id,
                                                            @RequestBody CategoryRequest req) {
        return ResponseEntity.ok(plannerService.updateCategory(user.getId(), id, req));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deactivateCategory(@AuthenticationPrincipal User user, @PathVariable Long id) {
        plannerService.deactivateCategory(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<SettingsResponse> getSettings(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(plannerService.getSettings(user.getId()));
    }

    @PutMapping("/settings")
    public ResponseEntity<SettingsResponse> updateSettings(@AuthenticationPrincipal User user, @RequestBody SettingsRequest req) {
        return ResponseEntity.ok(plannerService.updateSettings(user.getId(), req));
    }

    @GetMapping("/plan")
    public ResponseEntity<MonthlyPlanResponse> getPlan(@AuthenticationPrincipal User user,
                                                       @RequestParam(required = false) Integer year,
                                                       @RequestParam(required = false) Integer month) {
        LocalDate now = LocalDate.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        return ResponseEntity.ok(plannerService.getMonthlyPlan(user.getId(), y, m));
    }

    @GetMapping("/reflection")
    public ResponseEntity<ReflectionResponse> getReflection(@AuthenticationPrincipal User user,
                                                            @RequestParam(required = false) Integer year,
                                                            @RequestParam(required = false) Integer month) {
        LocalDate now = LocalDate.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        return ResponseEntity.ok(plannerService.getReflection(user.getId(), y, m));
    }

    @PutMapping("/reflection")
    public ResponseEntity<ReflectionResponse> updateReflection(@AuthenticationPrincipal User user,
                                                                @RequestParam Integer year,
                                                                @RequestParam Integer month,
                                                                @RequestBody ReflectionRequest req) {
        return ResponseEntity.ok(plannerService.updateReflection(user.getId(), year, month, req));
    }
}
