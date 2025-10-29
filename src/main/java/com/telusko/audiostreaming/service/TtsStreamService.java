package com.telusko.audiostreaming.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsStreamService {

    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector())
            .baseUrl("https://api.openai.com/v1")
            .build();

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.audio.speech.options.model:tts-1}")
    private String ttsModel;

    @Value("${spring.ai.openai.audio.speech.options.voice:alloy}")
    private String voice;

    private static final String AUDIO_FORMAT = "mp3";

    /**
     * Call OpenAI TTS once per text chunk and return raw MP3 bytes.
     */
    public Mono<byte[]> synthesizeToBytes(String text) {
        Map<String, Object> payload = Map.of(
                "model", ttsModel,
                "voice", voice,
                "input", text,
                "response_format", AUDIO_FORMAT
        );

        return webClient.post()
                .uri("/audio/speech")
                .header("Authorization", "Bearer " + openAiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .reduce(new byte[0], (acc, db) -> {
                    byte[] newBytes = new byte[acc.length + db.readableByteCount()];
                    System.arraycopy(acc, 0, newBytes, 0, acc.length);
                    db.read(newBytes, acc.length, db.readableByteCount());
                    return newBytes;
                })
                .doOnSubscribe(s -> log.debug("🔊 TTS start ({} chars)", text.length()))
                .doOnError(err -> log.error("❌ TTS error: {}", err.getMessage()))
                .doOnSuccess(b -> log.debug("✅ TTS done, {} bytes", b.length));
    }

    /**
     * Handles GPT token stream → grouped sentences → TTS → push to browser.
     * This collects tokens into complete sentences, converts to speech, and streams to client.
     */
    public Mono<Void> streamSpeechToBrowser(Flux<String> gptTokenStream, WebSocketSession session) {
        return gptTokenStream
                .onBackpressureBuffer() // Handle backpressure by buffering
                .collect(StringBuilder::new, StringBuilder::append) // Collect all tokens
                .flatMap(fullText -> {
                    String text = fullText.toString().trim();
                    if (text.isEmpty()) {
                        return Mono.empty();
                    }

                    log.info("📝 Full GPT response received: {}", text);

                    // Split into sentences
                    String[] sentences = text.split("(?<=[.!?])\\s+");

                    // Process each sentence sequentially
                    return Flux.fromArray(sentences)
                            .filter(s -> !s.trim().isEmpty())
                            .concatMap(sentence -> {
                                log.info("📝 Processing sentence: {}", sentence);

                                // Send text to browser first
                                return Mono.fromRunnable(() -> {
                                            try {
                                                String json = String.format("{\"type\":\"bot_text\",\"data\":\"%s\"}",
                                                        sentence.replace("\"", "\\\"").replace("\n", "\\n"));
                                                session.sendMessage(new TextMessage(json));
                                            } catch (Exception e) {
                                                log.error("Error sending text message: {}", e.getMessage());
                                            }
                                        })
                                        .then(synthesizeToBytes(sentence))
                                        .flatMap(mp3Bytes -> {
                                            // Send audio chunks to browser
                                            return Mono.fromRunnable(() -> {
                                                try {
                                                    session.sendMessage(new BinaryMessage(mp3Bytes));
                                                } catch (Exception e) {
                                                    log.error("Error sending audio: {}", e.getMessage());
                                                }
                                            });
                                        });
                            })
                            .then();
                })
                // Send end marker
                .then(Mono.fromRunnable(() -> {
                    try {
                        session.sendMessage(new TextMessage("{\"type\":\"tts_end\"}"));
                    } catch (Exception e) {
                        log.error("Error sending end marker: {}", e.getMessage());
                    }
                }))
                .doOnError(err -> log.error("❌ Stream error: {}", err.getMessage()))
                .onErrorResume(err -> {
                    log.error("❌ Recovering from stream error: {}", err.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}