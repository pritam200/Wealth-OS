package com.marketai.reminder.controller;

import com.marketai.auth.entity.User;
import com.marketai.reminder.dto.ReminderResponse;
import com.marketai.reminder.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService service;

    @GetMapping
    public ResponseEntity<List<ReminderResponse>> reminders(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.getReminders(user.getId()));
    }
}
