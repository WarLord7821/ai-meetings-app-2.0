package com.ai.meetingnotes.service;

import com.ai.meetingnotes.entity.User;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replaces MySQL meeting storage with two local files:
 *  - meetings.md  : human-readable log of every meeting
 *  - meetings.json: structured index used for API queries (list, count, find)
 *
 * All file operations are synchronized to handle concurrent requests safely.
 */
@Service
@Slf4j
public class MeetingFileService {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${meetings.storage.dir:./meetings-data}")
    private String storageDir;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private Path jsonPath;
    private Path mdPath;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        jsonPath = dir.resolve("meetings.json");
        mdPath   = dir.resolve("meetings.md");

        if (!Files.exists(jsonPath)) {
            Files.writeString(jsonPath, "[]");
            log.info("Created meetings index: {}", jsonPath.toAbsolutePath());
        }
        if (!Files.exists(mdPath)) {
            Files.writeString(mdPath, "# AI Meeting Notes Log\n\n");
            log.info("Created meetings log: {}", mdPath.toAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Persists a new meeting record to both files and returns the saved record.
     */
    public synchronized MeetingRecord save(User user,
                                           String id,
                                           String title,
                                           String rawTranscript,
                                           String summary,
                                           List<String> objectives,
                                           List<String> keyPoints,
                                           List<String> decisions,
                                           List<String> outcomes,
                                           List<String> actionItems,
                                           List<String> nextSteps,
                                           List<String> pendingDiscussions) {
        MeetingRecord record = new MeetingRecord(
                id,
                user.getEmail(),
                user.getPlanTier().name(),
                title,
                rawTranscript,
                summary,
                objectives,
                keyPoints,
                decisions,
                outcomes,
                actionItems,
                nextSteps,
                pendingDiscussions,
                LocalDateTime.now()
        );

        appendToJson(record);
        appendToMd(record);
        log.info("Meeting saved [id={}, user={}, title={}]", id, user.getEmail(), title);
        return record;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /** All meetings for a given user, newest first. */
    public synchronized List<MeetingRecord> listByUser(String email) {
        return readAll().stream()
                .filter(r -> email.equals(r.userEmail()))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** Count meetings for a user created on or after {@code since} (for rate limiting). */
    public synchronized long countByUserSince(String email, LocalDateTime since) {
        return readAll().stream()
                .filter(r -> email.equals(r.userEmail()))
                .filter(r -> !r.createdAt().isBefore(since))
                .count();
    }

    /** Find a specific meeting by ID scoped to a user. */
    public synchronized Optional<MeetingRecord> findByIdAndUser(String id, String email) {
        return readAll().stream()
                .filter(r -> id.equals(r.id()) && email.equals(r.userEmail()))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private List<MeetingRecord> readAll() {
        try {
            String content = Files.readString(jsonPath);
            if (content.isBlank()) return new ArrayList<>();
            return mapper.readValue(content, new TypeReference<List<MeetingRecord>>() {});
        } catch (Exception e) {
            log.error("Failed to read meetings.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void appendToJson(MeetingRecord record) {
        try {
            List<MeetingRecord> all = readAll();
            all.add(record);
            Files.writeString(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        } catch (Exception e) {
            throw new RuntimeException("Failed to write meetings.json", e);
        }
    }

    private void appendToMd(MeetingRecord r) {
        try {
            String entry = buildMdEntry(r);
            Files.writeString(mdPath, entry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write meetings.md", e);
        }
    }

    private void appendSection(StringBuilder sb, String heading, List<String> items) {
        sb.append("## ").append(heading).append("\n\n");
        if (items == null || items.isEmpty()) {
            sb.append("_None identified._\n\n");
        } else {
            items.forEach(item -> sb.append("- ").append(item).append("\n"));
            sb.append("\n");
        }
    }

    private String buildMdEntry(MeetingRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n---\n\n");
        sb.append("# ").append(r.title()).append("\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        sb.append("| **ID** | `").append(r.id()).append("` |\n");
        sb.append("| **Date** | ").append(r.createdAt().format(DISPLAY_FMT)).append(" |\n");
        sb.append("| **User** | ").append(r.userEmail()).append(" |\n");
        sb.append("| **Plan** | ").append(r.planTier()).append(" |\n\n");

        sb.append("## Summary\n\n").append(r.summary()).append("\n\n");

        appendSection(sb, "Objectives", r.objectives());
        appendSection(sb, "Key Points", r.keyPoints());
        appendSection(sb, "Decisions", r.decisions());
        appendSection(sb, "Outcomes", r.outcomes());
        appendSection(sb, "Action Items", r.actionItems());
        appendSection(sb, "Next Steps", r.nextSteps());
        appendSection(sb, "Pending Discussions", r.pendingDiscussions());

        sb.append("## Raw Transcript\n\n");
        sb.append("```\n").append(r.rawTranscript()).append("\n```\n\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    /**
     * Immutable record representing a single meeting stored in meetings.json.
     * Jackson serialises / deserialises this automatically.
     */
    public record MeetingRecord(
            String id,
            String userEmail,
            String planTier,
            String title,
            String rawTranscript,
            String summary,
            List<String> objectives,
            List<String> keyPoints,
            List<String> decisions,
            List<String> outcomes,
            List<String> actionItems,
            List<String> nextSteps,
            List<String> pendingDiscussions,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime createdAt
    ) {}
}
