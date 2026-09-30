package com.ai.meetingnotes.service;

import com.ai.meetingnotes.dto.MeetingRequest;
import com.ai.meetingnotes.dto.MeetingResponse;
import com.ai.meetingnotes.entity.User;
import com.ai.meetingnotes.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingService {

    private final MeetingFileService fileService;
    private final UserRepository userRepository;
    private final RagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openrouter.api.key}")
    private String openrouterApiKey;

    @Value("${openrouter.api.url:https://openrouter.ai/api/v1/chat/completions}")
    private String openrouterApiUrl;

    @Value("${openrouter.model:google/gemma-4-31b-it:free}")
    private String openrouterModel;

    /**
     * Comma-separated models OpenRouter tries, in order, when the primary model is
     * unavailable or rate-limited (free models are frequently throttled or retired).
     */
    @Value("${openrouter.fallback-models:}")
    private String openrouterFallbackModels;

    @Value("${openrouter.max-tokens:4096}")
    private int openrouterMaxTokens;

    /** Long transcripts on free models can take well over a minute. */
    @Value("${openrouter.timeout-seconds:120}")
    private int openrouterTimeoutSeconds;

    /** Sent as HTTP-Referer so requests are attributed to this app on OpenRouter. */
    @Value("${openrouter.app-url:http://localhost:4200}")
    private String openrouterAppUrl;

    private static final int FREE_DAILY_LIMIT = 3;

    /**
     * Value of the {@code X-Agent-Channel} header that identifies requests
     * originated by the OpenClaw WhatsApp agent (see {@code openclaw-workspace/AGENTS.md}).
     * <p>
     * The WhatsApp channel is Pro-only by design (see {@code OpenClaw.md}): free users
     * must be rejected BEFORE any summarization work happens, even when they still have
     * free web-UI quota.
     */
    public static final String AGENT_CHANNEL_WHATSAPP = "whatsapp";

    /**
     * Enforces the Pro-only gate for requests that arrive from the OpenClaw WhatsApp
     * agent (detected via the {@code X-Agent-Channel: whatsapp} header set by the
     * {@code openclaw-workspace/scripts/agent-create-meeting.*} scripts).
     * <p>
     * This is the API-level second layer of the gate (the first layer is behavioral: the
     * agent itself refuses non-Pro users after login). Even if someone scripts the API
     * directly and bypasses the agent, the backend still refuses free users with a 403.
     * <p>
     * Header absent or any value other than {@code whatsapp} → ordinary web-UI behavior
     * (quota / credits logic in {@link #createMeeting(User, MeetingRequest)} applies).
     *
     * @throws IllegalStateException when the caller is not an active Pro subscriber
     *                               (mapped to {@code 403} by the controller)
     */
    public void requireProForAgentChannel(User user, String agentChannel) {
        if (!AGENT_CHANNEL_WHATSAPP.equalsIgnoreCase(agentChannel)) {
            return; // not a WhatsApp-agent request — behave like the web UI
        }
        boolean isPro = user.getPlanTier() == User.PlanTier.PRO
                && user.getSubscriptionStatus() == User.SubscriptionStatus.ACTIVE;
        if (!isPro) {
            throw new IllegalStateException(
                    "WhatsApp agent is a Pro-only feature. Upgrade to Pro to use summaries via WhatsApp.");
        }
    }

    public MeetingResponse createMeeting(User user, MeetingRequest request) {
        // ─── Access Priority Logic ────────────────────────────────────────────
        // 1. Active Pro subscription → unlimited summaries
        // 2. Summary credits available → consume 1 credit (race-safe atomic UPDATE)
        // 3. Free plan + summaryCount < 3 → allow
        // 4. Else → throw (→ 403)
        // ─────────────────────────────────────────────────────────────────────

        boolean isPro = user.getPlanTier() == User.PlanTier.PRO
                && user.getSubscriptionStatus() == User.SubscriptionStatus.ACTIVE;

        boolean creditConsumed = false;
        if (!isPro) {
            if (user.getSummaryCredits() > 0) {
                // Atomically decrement; returns 0 if a concurrent request consumed the last credit
                int updated = userRepository.decrementSummaryCredits(user.getId());
                if (updated == 0) {
                    // Race condition: credits were just exhausted — fall back to free limit check
                    checkFreePlanLimit(user);
                } else {
                    creditConsumed = true;
                }
                // Credit consumed successfully — proceed to summary generation
            } else {
                checkFreePlanLimit(user);
            }
        }
        // isPro == true → no restriction, fall through


        // Generate summary using OpenRouter
        String summary = "";
        List<String> objectives = List.of();
        List<String> keyPoints = List.of();
        List<String> decisions = List.of();
        List<String> outcomes = List.of();
        List<String> actionItems = List.of();
        List<String> nextSteps = List.of();
        List<String> pendingDiscussions = List.of();

        try {
            String aiResponse = callOpenRouterApi(request.getTranscript());
            String cleanedResponse = extractJsonObject(aiResponse);
            log.debug("OpenRouter raw response: {}", aiResponse);
            log.debug("OpenRouter cleaned response: {}", cleanedResponse);
            JsonNode parsed = objectMapper.readTree(cleanedResponse);
            summary = parsed.path("summary").asText("");
            objectives = parseStringList(parsed, "objectives");
            keyPoints = parseStringList(parsed, "keyPoints");
            decisions = parseStringList(parsed, "decisions");
            outcomes = parseStringList(parsed, "outcomes");
            actionItems = parseStringList(parsed, "actionItems");
            nextSteps = parseStringList(parsed, "nextSteps");
            pendingDiscussions = parseStringList(parsed, "pendingDiscussions");
        } catch (Exception e) {
            log.error("OpenRouter summarization failed for user {}: {}", user.getEmail(), e.getMessage(), e);
            if (creditConsumed) {
                // The user paid for a summary they did not get — give the credit back
                userRepository.addSummaryCredits(user.getId(), 1);
            }
            throw new RuntimeException("Failed to generate summary. Please try again.", e);
        }

        String title = (request.getTitle() != null && !request.getTitle().trim().isEmpty())
                ? request.getTitle().trim()
                : "Untitled Meeting";

        // Persist to local .md / .json files (no DB)
        MeetingFileService.MeetingRecord record = fileService.save(
                user,
                UUID.randomUUID().toString(),
                title,
                request.getTranscript(),
                summary,
                objectives,
                keyPoints,
                decisions,
                outcomes,
                actionItems,
                nextSteps,
                pendingDiscussions
        );

        // Atomically increment lifetime counter in DB
        userRepository.incrementSummaryCount(user.getId());

        // Make this meeting searchable by the RAG chatbot. Best-effort: a failure here
        // never fails meeting creation (see RagService.indexMeeting), and any meeting
        // left unindexed gets picked up lazily the next time it's searched.
        ragService.indexMeeting(user, record);

        return MeetingResponse.from(record);
    }

    public Page<MeetingResponse> getUserMeetings(User user, Pageable pageable) {
        List<MeetingResponse> all = fileService.listByUser(user.getEmail())
                .stream()
                .map(MeetingResponse::from)
                .toList();
        int start = (int) pageable.getOffset();
        int end   = Math.min(start + pageable.getPageSize(), all.size());
        List<MeetingResponse> page = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(page, pageable, all.size());
    }

    public List<MeetingResponse> getAllUserMeetings(User user) {
        return fileService.listByUser(user.getEmail())
                .stream()
                .map(MeetingResponse::from)
                .toList();
    }

    public MeetingResponse getMeetingById(User user, String id) {
        return fileService.findByIdAndUser(id, user.getEmail())
                .map(MeetingResponse::from)
                .orElseThrow(() -> new RuntimeException("Meeting not found"));
    }

    // -------------------------------------------------------------------------
    // OpenRouter API (OpenAI-compatible chat completions)
    // -------------------------------------------------------------------------

    private String callOpenRouterApi(String transcript) {
        String systemPrompt = """
                You are an expert meeting analyst. Analyze meeting transcripts and produce a detailed,
                structured summary. Respond ONLY with valid JSON — no markdown formatting, no code fences,
                no extra text — in exactly this shape:
                {
                  "summary": "2-4 sentence high-level overview of the meeting (plain prose paragraph, not a list)",
                  "objectives": ["what the meeting was intended to accomplish"],
                  "keyPoints": ["main topic or important detail discussed"],
                  "decisions": ["conclusion or agreement reached"],
                  "outcomes": ["what was actually accomplished during the meeting"],
                  "actionItems": ["who is responsible for what, with due date when mentioned"],
                  "nextSteps": ["immediate follow-up or the very next thing that will happen"],
                  "pendingDiscussions": ["unanswered question, unresolved topic, or deferred item"]
                }
                Rules:
                - Always include every field as a JSON array; use an empty array [] when a section has no content, never null.
                - Use concise, informative bullet-style phrases; start each one with a verb where possible.
                - Preserve names, dates, numbers, and deadlines exactly as stated in the transcript.
                """;

        String userPrompt = """
                Analyze the following meeting transcript and produce the detailed structured summary described
                in the system prompt. Cover all of the following:
                - Objectives: what the meeting was intended to accomplish.
                - Key Points: the main topics discussed and important details.
                - Decisions: any conclusions or agreements reached during the meeting.
                - Outcomes: what was actually accomplished by the end of the meeting.
                - Action Items: who is responsible for what, including due dates when mentioned.
                - Next Steps: immediate follow-ups or the very next things that will happen.
                - Pending Discussions: any unanswered questions, unresolved topics, or items explicitly deferred.

                Meeting transcript:
                """ + transcript;

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", openrouterModel);
        List<String> fallbacks = Arrays.stream(openrouterFallbackModels.split(","))
                .map(String::trim)
                .filter(m -> !m.isEmpty() && !m.equals(openrouterModel))
                .toList();
        if (!fallbacks.isEmpty()) {
            // OpenRouter model routing: tries each model in order until one succeeds
            ArrayNode models = requestBody.putArray("models");
            models.add(openrouterModel);
            fallbacks.forEach(models::add);
        }
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", openrouterMaxTokens);
        requestBody.putObject("response_format").put("type", "json_object");

        Timeout timeout = Timeout.ofSeconds(openrouterTimeoutSeconds);
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(10))
                                .setSocketTimeout(timeout)
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
                .build()) {
            HttpPost httpPost = new HttpPost(openrouterApiUrl);
            httpPost.setHeader("Authorization", "Bearer " + openrouterApiKey);
            httpPost.setHeader("HTTP-Referer", openrouterAppUrl);
            httpPost.setHeader("X-Title", "AI Meeting Notes");
            httpPost.setEntity(new StringEntity(
                    objectMapper.writeValueAsString(requestBody), ContentType.APPLICATION_JSON));

            return httpClient.execute(httpPost, response -> {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                int statusCode = response.getCode();
                if (statusCode >= 400) {
                    log.error("OpenRouter API error: {} - {}", statusCode, responseBody);
                    throw new IllegalArgumentException("OpenRouter API error: " + statusCode);
                }
                // OpenRouter can answer HTTP 200 with an error object (e.g. upstream provider failure)
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                if (jsonNode.hasNonNull("error")) {
                    log.error("OpenRouter returned an error payload: {}", jsonNode.get("error"));
                    throw new IllegalArgumentException("OpenRouter error: "
                            + jsonNode.path("error").path("message").asText("unknown"));
                }
                JsonNode choice = jsonNode.path("choices").path(0);
                String content = choice.path("message").path("content").asText("");
                if (content.isBlank()) {
                    throw new IllegalArgumentException("OpenRouter returned an empty completion (finish_reason="
                            + choice.path("finish_reason").asText("?") + ")");
                }
                if ("length".equals(choice.path("finish_reason").asText())) {
                    log.warn("OpenRouter completion truncated at max_tokens={} (model {})",
                            openrouterMaxTokens, jsonNode.path("model").asText());
                }
                log.info("OpenRouter summary generated by model {}", jsonNode.path("model").asText());
                return content;
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to call OpenRouter API: " + e.getMessage(), e);
        }
    }

    /**
     * Safely extracts a list of strings from a JSON field.
     * Returns an empty list when the field is missing or not an array, so a
     * malformed or partial LLM response never breaks persistence.
     */
    private List<String> parseStringList(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isArray()) {
            return objectMapper.convertValue(
                    node.get(field),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        return List.of();
    }

    /**
     * Extracts the JSON object from an LLM reply. Models often wrap the JSON in
     * markdown code fences or add a sentence before/after it despite being told not to,
     * so keep everything from the first '{' to the last '}'.
     */
    private String extractJsonObject(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw.strip();
    }

    /**
     * Throws IllegalStateException if the user has exhausted their free-plan lifetime quota.
     * Called when: (a) user has no credits, or (b) a concurrent request consumed the last credit.
     */
    private void checkFreePlanLimit(User user) {
        if (user.getSummaryCount() >= FREE_DAILY_LIMIT) {
            throw new IllegalStateException(
                    "Free plan limit reached (" + FREE_DAILY_LIMIT + " summaries lifetime). "
                            + "Upgrade to Pro ($49/month) for unlimited summaries, "
                            + "or buy a summary credit ($1 each).");
        }
    }
}