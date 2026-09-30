package com.ai.meetingnotes.controller;

import com.ai.meetingnotes.dto.ChatRequest;
import com.ai.meetingnotes.dto.ChatResponse;
import com.ai.meetingnotes.entity.User;
import com.ai.meetingnotes.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * RAG chatbot over the user's own stored meetings. Open to free and Pro users alike
 * (unlike the WhatsApp channel, which stays Pro-only — see MeetingService).
 *
 * POST /api/chat { "question": "..." } → { "answer": "...", "sources": [...] }
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final RagService ragService;

    @PostMapping
    public ResponseEntity<?> ask(@AuthenticationPrincipal User user, @Valid @RequestBody ChatRequest request) {
        try {
            RagService.RagAnswer result = ragService.answer(user, request.getQuestion().strip());
            return ResponseEntity.ok(ChatResponse.from(result));
        } catch (Exception e) {
            log.error("RAG chat failed for {}: {}", user.getEmail(), e.getMessage(), e);
            return ResponseEntity.status(502)
                    .body(new MeetingController.ErrorResponse(
                            "Sorry, I couldn't answer that right now. Please try again.", false));
        }
    }
}
