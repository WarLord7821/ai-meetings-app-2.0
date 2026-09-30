package com.ai.meetingnotes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared low-level HTTP client for OpenRouter's OpenAI-compatible REST API.
 * Centralises connection/timeout config and error handling used by both
 * chat-completions ({@link MeetingService}, {@link RagService}) and the
 * separate {@code /embeddings} endpoint ({@link RagService} indexing/search).
 */
@Service
@Slf4j
public class OpenRouterClient {

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${openrouter.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${openrouter.app-url:http://localhost:4200}")
    private String appUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * POSTs a JSON body to an OpenRouter endpoint path (e.g. "/chat/completions",
     * "/embeddings") and returns the parsed response. Throws
     * {@link IllegalArgumentException} on any non-2xx status or an
     * HTTP-200-with-error-payload response (OpenRouter does both).
     */
    public JsonNode post(String path, ObjectNode body) {
        Timeout timeout = Timeout.ofSeconds(timeoutSeconds);
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(10))
                                .setSocketTimeout(timeout)
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
                .build()) {
            HttpPost httpPost = new HttpPost(baseUrl + path);
            httpPost.setHeader("Authorization", "Bearer " + apiKey);
            httpPost.setHeader("HTTP-Referer", appUrl);
            httpPost.setHeader("X-Title", "AI Meeting Notes");
            httpPost.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

            return httpClient.execute(httpPost, response -> {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                int statusCode = response.getCode();
                if (statusCode >= 400) {
                    log.error("OpenRouter {} error: {} - {}", path, statusCode, responseBody);
                    throw new IllegalArgumentException("OpenRouter API error: " + statusCode);
                }
                JsonNode jsonNode = mapper.readTree(responseBody);
                if (jsonNode.hasNonNull("error")) {
                    log.error("OpenRouter {} returned an error payload: {}", path, jsonNode.get("error"));
                    throw new IllegalArgumentException("OpenRouter error: "
                            + jsonNode.path("error").path("message").asText("unknown"));
                }
                return jsonNode;
            });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to call OpenRouter " + path + ": " + e.getMessage(), e);
        }
    }

    /** Embeds a single text via {@code POST /embeddings} and returns the vector. */
    public float[] embed(String model, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        JsonNode response = post("/embeddings", body);
        JsonNode vectorNode = response.path("data").path(0).path("embedding");
        if (!vectorNode.isArray() || vectorNode.isEmpty()) {
            throw new IllegalArgumentException("OpenRouter embeddings response had no vector");
        }
        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) vectorNode.get(i).asDouble();
        }
        return vector;
    }

    /**
     * Sends a chat-completion request with an ordered list of models (primary + fallbacks,
     * capped at 3 total per OpenRouter's {@code models} routing array limit) and returns the
     * assistant's raw text content.
     */
    public String chatComplete(List<String> models, String systemPrompt, String userPrompt,
                                double temperature, int maxTokens, boolean jsonMode) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", models.get(0));
        if (models.size() > 1) {
            ArrayNode modelsArray = body.putArray("models");
            models.forEach(modelsArray::add);
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (jsonMode) {
            body.putObject("response_format").put("type", "json_object");
        }

        JsonNode response = post("/chat/completions", body);
        JsonNode choice = response.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalArgumentException("OpenRouter returned an empty completion (finish_reason="
                    + choice.path("finish_reason").asText("?") + ")");
        }
        if ("length".equals(choice.path("finish_reason").asText())) {
            log.warn("OpenRouter completion truncated at max_tokens={} (model {})",
                    maxTokens, response.path("model").asText());
        }
        log.info("OpenRouter completion generated by model {}", response.path("model").asText());
        return content;
    }
}
