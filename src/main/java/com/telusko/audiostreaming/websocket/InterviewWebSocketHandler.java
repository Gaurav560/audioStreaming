package com.telusko.audiostreaming.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telusko.audiostreaming.service.AiResponseService;
import com.telusko.audiostreaming.service.DeepgramService;
import com.telusko.audiostreaming.service.TtsStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewWebSocketHandler extends AbstractWebSocketHandler {

    private final DeepgramService deepgramService;
    private final AiResponseService aiResponseService;
    private final TtsStreamService ttsStreamService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Store sinks per session
    private final ConcurrentHashMap<String, Sinks.Many<byte[]>> audioSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sinks.Many<String>> transcriptSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<String>> partialTranscripts = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("✅ WebSocket connected: {}", session.getId());

        // Create sinks for this session
        Sinks.Many<byte[]> audioSink = Sinks.many().multicast().onBackpressureBuffer();
        Sinks.Many<String> transcriptSink = Sinks.many().multicast().onBackpressureBuffer();

        audioSinks.put(session.getId(), audioSink);
        transcriptSinks.put(session.getId(), transcriptSink);
        partialTranscripts.put(session.getId(), new AtomicReference<>(""));

        // Start Deepgram streaming
        deepgramService.startStreaming(audioSink, transcriptSink);

        // Process transcripts
        transcriptSink.asFlux()
                .doOnNext(json -> handleDeepgramTranscript(session, json))
                .subscribe();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // Forward audio to Deepgram
        byte[] audioData = new byte[message.getPayload().remaining()];
        message.getPayload().get(audioData);

        Sinks.Many<byte[]> audioSink = audioSinks.get(session.getId());
        if (audioSink != null) {
            audioSink.tryEmitNext(audioData);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String text = message.getPayload();
        log.info("📥 Text message from client: {}", text);

        // Process as user input and generate AI response
        processUserInput(session, text);
    }

    private void handleDeepgramTranscript(WebSocketSession session, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Forward to browser
            session.sendMessage(new TextMessage(json));

            // Check if this is a final transcript
            if (root.has("type") && "Results".equals(root.get("type").asText())) {
                JsonNode channel = root.get("channel");
                if (channel != null && channel.has("alternatives")) {
                    JsonNode alternatives = channel.get("alternatives");
                    if (alternatives.isArray() && alternatives.size() > 0) {
                        JsonNode firstAlt = alternatives.get(0);
                        String transcript = firstAlt.get("transcript").asText();
                        boolean isFinal = root.get("is_final").asBoolean(false);

                        if (isFinal && !transcript.trim().isEmpty()) {
                            log.info("🎯 Final transcript: {}", transcript);
                            processUserInput(session, transcript);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Error handling Deepgram transcript: {}", e.getMessage());
        }
    }

    private void processUserInput(WebSocketSession session, String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            return;
        }

        log.info("🤖 Generating AI response for: {}", userText);

        // Get AI response stream
        Flux<String> gptStream = aiResponseService.streamReply(userText);

        // Convert to speech and stream to browser
        ttsStreamService.streamSpeechToBrowser(gptStream, session)
                .doOnSuccess(v -> log.info("✅ Response streaming complete"))
                .doOnError(err -> log.error("❌ Error streaming response: {}", err.getMessage()))
                .subscribe();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("🛑 WebSocket closed: {} - {}", session.getId(), status);

        // Cleanup
        Sinks.Many<byte[]> audioSink = audioSinks.remove(session.getId());
        if (audioSink != null) {
            audioSink.tryEmitComplete();
        }

        Sinks.Many<String> transcriptSink = transcriptSinks.remove(session.getId());
        if (transcriptSink != null) {
            transcriptSink.tryEmitComplete();
        }

        partialTranscripts.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("❌ WebSocket error for {}: {}", session.getId(), exception.getMessage());
    }
}