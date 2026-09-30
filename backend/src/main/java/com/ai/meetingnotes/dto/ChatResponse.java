package com.ai.meetingnotes.dto;

import com.ai.meetingnotes.service.MeetingFileService;
import com.ai.meetingnotes.service.RagService;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {
    private String answer;
    private List<Source> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Source {
        private String id;
        private String title;
        private LocalDateTime createdAt;

        public static Source from(MeetingFileService.MeetingRecord r) {
            return Source.builder().id(r.id()).title(r.title()).createdAt(r.createdAt()).build();
        }
    }

    public static ChatResponse from(RagService.RagAnswer answer) {
        return ChatResponse.builder()
                .answer(answer.answer())
                .sources(answer.sources().stream().map(Source::from).toList())
                .build();
    }
}
