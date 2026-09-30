package com.ai.meetingnotes.controller;

import com.ai.meetingnotes.dto.MeetingRequest;
import com.ai.meetingnotes.dto.MeetingResponse;
import com.ai.meetingnotes.entity.User;
import com.ai.meetingnotes.service.MeetingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
@Slf4j
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    public ResponseEntity<?> createMeeting(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody MeetingRequest request,
            @RequestHeader(value = "X-Agent-Channel", required = false) String agentChannel) {
        try {
            // Pro-only gate for OpenClaw WhatsApp agent requests (see OpenClaw.md §7).
            // Detected via the X-Agent-Channel: whatsapp header that the workspace
            // scripts (openclaw-workspace/scripts/agent-create-meeting.*) always send.
            meetingService.requireProForAgentChannel(user, agentChannel);
            MeetingResponse response = meetingService.createMeeting(user, request);
            return ResponseEntity.status(201).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage(), true));
        } catch (Exception e) {
            log.error("Meeting creation failed for {} (channel={}): {}",
                    user.getEmail(), agentChannel, e.getMessage());
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Failed to generate summary. Please try again.", false));
        }
    }

    @GetMapping
    public ResponseEntity<?> getMeetings(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        Page<MeetingResponse> meetings = meetingService.getUserMeetings(user, pageable);
        return ResponseEntity.ok(meetings);
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllMeetings(@AuthenticationPrincipal User user) {
        List<MeetingResponse> meetings = meetingService.getAllUserMeetings(user);
        return ResponseEntity.ok(meetings);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getMeeting(
            @AuthenticationPrincipal User user,
            @PathVariable String id) {
        try {
            MeetingResponse meeting = meetingService.getMeetingById(user, id);
            return ResponseEntity.ok(meeting);
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).body(new ErrorResponse("Meeting not found", false));
        }
    }

    public record ErrorResponse(String error, boolean limitReached) {
    }
}