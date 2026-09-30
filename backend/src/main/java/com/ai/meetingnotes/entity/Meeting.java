package com.ai.meetingnotes.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Meeting {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "raw_transcript", columnDefinition = "TEXT", nullable = false)
    private String rawTranscript;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "objectives", columnDefinition = "JSON")
    private List<String> objectives;

    @Column(name = "key_points", columnDefinition = "JSON")
    private List<String> keyPoints;

    @Column(name = "decisions", columnDefinition = "JSON")
    private List<String> decisions;

    @Column(name = "outcomes", columnDefinition = "JSON")
    private List<String> outcomes;

    @Column(name = "action_items", columnDefinition = "JSON")
    private List<String> actionItems;

    @Column(name = "next_steps", columnDefinition = "JSON")
    private List<String> nextSteps;

    @Column(name = "pending_discussions", columnDefinition = "JSON")
    private List<String> pendingDiscussions;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}