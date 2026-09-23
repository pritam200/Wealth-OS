package com.marketai.rent.controller;

import com.marketai.auth.entity.User;
import com.marketai.rent.dto.RentRequest;
import com.marketai.rent.dto.RentResponse;
import com.marketai.rent.dto.RentScheduleRequest;
import com.marketai.rent.dto.RentScheduleResponse;
import com.marketai.rent.service.RentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rent")
@RequiredArgsConstructor
public class RentController {

    private final RentService rentService;

    @GetMapping
    public ResponseEntity<List<RentResponse>> listRent(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(rentService.listRent(user.getId()));
    }

    @PostMapping
    public ResponseEntity<RentResponse> recordPayment(
            @AuthenticationPrincipal User user,
            @RequestBody @jakarta.validation.Valid RentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentService.recordPayment(user.getId(), req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRent(@AuthenticationPrincipal User user, @PathVariable Long id) {
        rentService.deleteRent(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/schedule")
    public ResponseEntity<List<RentScheduleResponse>> listSchedules(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(rentService.listSchedules(user.getId()));
    }

    @PostMapping("/schedule")
    public ResponseEntity<RentScheduleResponse> addSchedule(
            @AuthenticationPrincipal User user,
            @RequestBody @jakarta.validation.Valid RentScheduleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentService.addSchedule(user.getId(), req));
    }

    @PatchMapping("/schedule/{id}")
    public ResponseEntity<RentScheduleResponse> setScheduleActive(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "'active' is required");
        }
        return ResponseEntity.ok(rentService.setScheduleActive(user.getId(), id, active));
    }

    @DeleteMapping("/schedule/{id}")
    public ResponseEntity<Void> deleteSchedule(@AuthenticationPrincipal User user, @PathVariable Long id) {
        rentService.deleteSchedule(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
