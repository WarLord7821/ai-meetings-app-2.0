package com.ai.meetingnotes.service;

import com.ai.meetingnotes.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Retrieval-Augmented Generation over a user's stored meetings: "what were the
 * objectives of my meeting with Sarah on the 19th" → embed the question, rank the
 * user's meetings by cosine similarity against their pre-computed summary embeddings,
 * build a context block from the closest few, and ask OpenRouter to answer using
 * only that context.
 * <p>
 * Retrieval unit is one whole meeting (its summary + structured fields), not a
 * chunked transcript — meeting summaries here are already short (a few hundred
 * tokens), so one embedding per meeting is enough to find the right one(s); the
 * raw transcript is not embedded or searched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final MeetingFileService fileService;
    private final EmbeddingStoreService embeddingStore;
    private final OpenRouterClient openRouterClient;

    @Value("${openrouter.embedding-model:openai/text-embedding-3-small}")
    private String embeddingModel;

    @Value("${openrouter.chat-model:openai/gpt-oss-120b}")
    private String chatModel;

    @Value("${openrouter.chat-fallback-models:}")
    private String chatFallbackModels;

    /** How many of the user's most relevant meetings to feed as context. */
    private static final int TOP_K = 5;
    /** Below this similarity, a meeting is considered irrelevant to the question. */
    private static final double MIN_SIMILARITY = 0.15;

    /**
     * Embeds and stores the vector for a just-saved meeting. Called right after
     * {@link MeetingService#createMeeting} persists the record, so new meetings are
     * searchable immediately. Failures are logged, not thrown — a missing embedding
     * only means this one meeting is skipped in future searches (self-heals via
     * {@link #ensureIndexed}), it must never fail meeting creation itself.
     */
    public void indexMeeting(User user, MeetingFileService.MeetingRecord record) {
        try {
            float[] vector = openRouterClient.embed(embeddingModel, buildIndexText(record));
            embeddingStore.upsert(record.id(), user.getEmail(), vector);
            log.info("Indexed meeting for RAG [id={}, user={}]", record.id(), user.getEmail());
        } catch (Exception e) {
            log.error("Failed to index meeting for RAG [id={}]: {}", record.id(), e.getMessage());
        }
    }

    /**
     * Answers a natural-language question about the user's meetings.
     *
     * @return the answer text plus the meetings actually used as context (for citation)
     */
    public RagAnswer answer(User user, String question) {
        List<MeetingFileService.MeetingRecord> userMeetings = fileService.listByUser(user.getEmail());
        if (userMeetings.isEmpty()) {
            return new RagAnswer(
                    "You don't have any saved meetings yet, so I have nothing to search. "
                            + "Create a meeting summary first, then ask me about it.",
                    List.of());
        }

        ensureIndexed(user, userMeetings);

        float[] questionVector = openRouterClient.embed(embeddingModel, question);
        Map<String, EmbeddingStoreService.EmbeddingRecord> byId = embeddingStore.listByUser(user.getEmail())
                .stream()
                .collect(Collectors.toMap(EmbeddingStoreService.EmbeddingRecord::meetingId, r -> r));

        List<MeetingFileService.MeetingRecord> ranked = userMeetings.stream()
                .filter(m -> byId.containsKey(m.id()))
                .sorted(Comparator.comparingDouble(
                        (MeetingFileService.MeetingRecord m) ->
                                EmbeddingStoreService.cosineSimilarity(questionVector, byId.get(m.id()).embedding()))
                        .reversed())
                .toList();

        List<MeetingFileService.MeetingRecord> topMatches = new ArrayList<>();
        for (MeetingFileService.MeetingRecord m : ranked) {
            double similarity = EmbeddingStoreService.cosineSimilarity(questionVector, byId.get(m.id()).embedding());
            if (similarity < MIN_SIMILARITY && !topMatches.isEmpty()) break;
            topMatches.add(m);
            if (topMatches.size() >= TOP_K) break;
        }
        if (topMatches.isEmpty()) {
            // Nothing cleared the similarity bar at all — fall back to the most recent
            // few meetings so a vague question still gets a useful answer.
            topMatches = userMeetings.stream()
                    .sorted(Comparator.comparing(MeetingFileService.MeetingRecord::createdAt).reversed())
                    .limit(TOP_K)
                    .toList();
        }

        String context = topMatches.stream()
                .map(this::buildContextEntry)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = """
                You are a helpful assistant answering questions about the user's past meetings.
                Answer ONLY using the meeting context provided below — never invent details that
                are not in it. If the context does not contain the answer, say so plainly instead
                of guessing. Keep answers concise and conversational (plain text, no markdown
                headers). When useful, mention which meeting(s) your answer comes from by title
                and date.

                Meeting context:
                """ + context;

        List<String> models = new ArrayList<>();
        models.add(chatModel);
        Arrays.stream(chatFallbackModels.split(","))
                .map(String::trim)
                .filter(m -> !m.isEmpty() && !m.equals(chatModel))
                .forEach(models::add);

        String answer = openRouterClient.chatComplete(models, systemPrompt, question, 0.2, 800, false);
        return new RagAnswer(answer.strip(), topMatches);
    }

    /**
     * Embeds any of the user's meetings that don't yet have a stored vector — covers
     * meetings created before this feature existed, or ones whose indexing failed
     * at save time.
     */
    private void ensureIndexed(User user, List<MeetingFileService.MeetingRecord> meetings) {
        for (MeetingFileService.MeetingRecord m : meetings) {
            if (embeddingStore.findByMeetingId(m.id()).isEmpty()) {
                indexMeeting(user, m);
            }
        }
    }

    private String buildIndexText(MeetingFileService.MeetingRecord r) {
        return buildContextEntry(r);
    }

    private String buildContextEntry(MeetingFileService.MeetingRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(r.title()).append("\n");
        sb.append("Date: ").append(r.createdAt().format(DATE_FMT)).append("\n");
        sb.append("Summary: ").append(r.summary()).append("\n");
        appendListField(sb, "Objectives", r.objectives());
        appendListField(sb, "Key Points", r.keyPoints());
        appendListField(sb, "Decisions", r.decisions());
        appendListField(sb, "Outcomes", r.outcomes());
        appendListField(sb, "Action Items", r.actionItems());
        appendListField(sb, "Next Steps", r.nextSteps());
        appendListField(sb, "Pending Discussions", r.pendingDiscussions());
        return sb.toString();
    }

    private void appendListField(StringBuilder sb, String label, List<String> items) {
        if (items != null && !items.isEmpty()) {
            sb.append(label).append(": ").append(String.join("; ", items)).append("\n");
        }
    }

    public record RagAnswer(String answer, List<MeetingFileService.MeetingRecord> sources) {}
}
