package com.telusko.audiostreaming.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAIConfig {

    @Bean
    public ChatClient chatClient(OpenAiChatModel model) {
        // Build ChatClient WITHOUT advisors - this bypasses the advisor chain issue
        // The advisor chain is what's causing "No StreamAdvisors available" error
        return ChatClient.builder(model)
                .build();
    }

}