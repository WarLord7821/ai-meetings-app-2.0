package com.ai.meetingnotes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local, file-based vector store for RAG over meeting summaries — mirrors the
 * philosophy of {@link MeetingFileService} (no DB, one JSON index file) rather
 * than introducing a dedicated vector database, since a single user's meeting
 * corpus is small enough (tens to low thousands) for in-memory cosine similarity
 * to be instant.
 *
 * One row per meeting: {@code {meetingId, userEmail, embedding[1536], indexedAt}}.
 * Looked up by meeting id for backfill checks, and listed per-user for search.
 */
@Service
@Slf4j
public class EmbeddingStoreService {

    @Value("${meetings.storage.dir:./meetings-data}")
    private String storageDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path jsonPath;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        jsonPath = dir.resolve("embeddings.json");
        if (!Files.exists(jsonPath)) {
            Files.writeString(jsonPath, "[]");
            log.info("Created embeddings index: {}", jsonPath.toAbsolutePath());
        }
    }

    public synchronized void upsert(String meetingId, String userEmail, float[] embedding) {
        List<EmbeddingRecord> all = new ArrayList<>(readAll());
        all.removeIf(r -> r.meetingId().equals(meetingId));
        all.add(new EmbeddingRecord(meetingId, userEmail, embedding));
        writeAll(all);
    }

    public synchronized Optional<EmbeddingRecord> findByMeetingId(String meetingId) {
        return readAll().stream().filter(r -> r.meetingId().equals(meetingId)).findFirst();
    }

    public synchronized List<EmbeddingRecord> listByUser(String userEmail) {
        return readAll().stream().filter(r -> r.userEmail().equals(userEmail)).toList();
    }

    private List<EmbeddingRecord> readAll() {
        try {
            String content = Files.readString(jsonPath);
            if (content.isBlank()) return new ArrayList<>();
            return mapper.readValue(content, new TypeReference<List<EmbeddingRecord>>() {});
        } catch (Exception e) {
            log.error("Failed to read embeddings.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeAll(List<EmbeddingRecord> all) {
        try {
            Files.writeString(jsonPath, mapper.writeValueAsString(all));
        } catch (Exception e) {
            throw new RuntimeException("Failed to write embeddings.json", e);
        }
    }

    /** Cosine similarity between two equal-length vectors; -1..1, higher is more similar. */
    public static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record EmbeddingRecord(String meetingId, String userEmail, float[] embedding) {}
}
