package com.linlay.agentplatform.service;

public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException(String chatId) {
        super("chat not found: " + chatId);
    }
}
