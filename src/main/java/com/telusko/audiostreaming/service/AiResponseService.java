package com.telusko.audiostreaming.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class AiResponseService {

    private final ChatClient chatClient;

    public AiResponseService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Stream a reply from GPT-4o-mini for one final user utterance.
     * Uses ChatClient.stream().content() which properly returns token chunks.
     */
    public Flux<String> streamReply(String userUtterance) {
        return chatClient
                .prompt()
                .system("You are a concise, helpful interview assistant. Keep responses short and speak naturally.")
                .user(userUtterance)
                .stream()
                .content()  // Returns Flux<String> of tokens
                .doOnSubscribe(s -> log.debug("🤖 GPT start for: {}", userUtterance))
                .doOnNext(tok -> log.trace("🧩 GPT token: {}", tok))
                .doOnComplete(() -> log.debug("🤖 GPT stream complete"))
                .onErrorResume(err -> {
                    log.error("❌ GPT stream error: {}", err.getMessage(), err);
                    return Flux.just("Sorry, I encountered an error processing your request.");
                });
    }
}