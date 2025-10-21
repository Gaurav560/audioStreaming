package com.telusko.audiostreaming.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Service
public class DeepgramService {

    @Value("${deepgram.api.key}")
    private String apiKey;

    @Value("${deepgram.url}")
    private String deepgramUrl;

    /**
     * Persistent Deepgram streaming WebSocket connection
     */
    public void startStreaming(Sinks.Many<byte[]> audioSink,
                               Sinks.Many<String> transcriptSink) throws Exception {

        // ✅ Correct query parameters for nova-3
        String wsUrl = deepgramUrl +
                "?model=nova-3" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&channels=1" +
                "&language=en-US" +
                "&smart_format=true" +
                "&punctuate=true" +
                "&interim_results=true" +
                "&vad_events=true";

        log.info("🎙 Connecting to Deepgram WS: {}", wsUrl);

        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Token " + apiKey);

        client.execute(URI.create(wsUrl), headers, session -> {
            // 1️⃣ Send audio stream and periodic keep-alive messages
            Mono<Void> send = session.send(
                    Flux.merge(
                            audioSink.asFlux()
                                    .map(bytes -> session.binaryMessage(factory -> factory.wrap(bytes))),
                            Flux.interval(Duration.ofSeconds(5))
                                    .map(i -> session.textMessage("{\"type\":\"KeepAlive\"}"))
                    )
            );

            // 2️⃣ Receive Deepgram transcripts
            Mono<Void> receive = session.receive()
                    .map(msg -> msg.getPayloadAsText())
                    .doOnNext(json -> {
                        log.debug("🧠 Deepgram → {}", json);
                        transcriptSink.tryEmitNext(json);
                    })
                    .doOnError(err -> log.error("❌ Deepgram receive error: {}", err.getMessage()))
                    .then();

            // Keep connection alive until client closes
            return Mono.when(send, receive)
                    .doOnSubscribe(s -> log.info("✅ Deepgram stream started"))
                    .doFinally(sig -> log.info("🛑 Deepgram stream closed ({})", sig));
        }).subscribe();
    }
}
