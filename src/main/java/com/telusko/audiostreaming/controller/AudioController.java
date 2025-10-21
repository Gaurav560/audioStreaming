package com.telusko.audiostreaming.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AudioController {
    @GetMapping("/api/voices")
    public Map<String, Object> voices() {
        return Map.of("voices", List.of("alloy", "verse", "coral", "sage"));
    }
}
