package com.ai.meetingnotes.dto;

import com.ai.meetingnotes.entity.Meeting;
import com.ai.meetingnotes.service.MeetingFileService;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingResponse {
    private String id;
    private String title;
    private String summary;
    private List<String> objectives;
    private List<String> keyPoints;
    private List<String> decisions;
    private List<String> outcomes;
    private List<String> actionItems;
    private List<String> nextSteps;
    private List<String> pendingDiscussions;
    private String rawTranscript;
    private LocalDateTime createdAt;

    public static MeetingResponse from(Meeting meeting) {
        return MeetingResponse.builder()
                .id(meeting.getId())
                .title(meeting.getTitle())
                .summary(meeting.getSummary())
                .objectives(meeting.getObjectives())
                .keyPoints(meeting.getKeyPoints())
                .decisions(meeting.getDecisions())
                .outcomes(meeting.getOutcomes())
                .actionItems(meeting.getActionItems())
                .nextSteps(meeting.getNextSteps())
                .pendingDiscussions(meeting.getPendingDiscussions())
                .rawTranscript(meeting.getRawTranscript())
                .createdAt(meeting.getCreatedAt())
                .build();
    }

    public static MeetingResponse from(MeetingFileService.MeetingRecord record) {
        return MeetingResponse.builder()
                .id(record.id())
                .title(record.title())
                .summary(record.summary())
                .objectives(nullToEmpty(record.objectives()))
                .keyPoints(nullToEmpty(record.keyPoints()))
                .decisions(nullToEmpty(record.decisions()))
                .outcomes(nullToEmpty(record.outcomes()))
                .actionItems(nullToEmpty(record.actionItems()))
                .nextSteps(nullToEmpty(record.nextSteps()))
                .pendingDiscussions(nullToEmpty(record.pendingDiscussions()))
                .rawTranscript(record.rawTranscript())
                .createdAt(record.createdAt())
                .build();
    }

    private static List<String> nullToEmpty(List<String> items) {
        return items == null ? List.of() : items;
    }
}