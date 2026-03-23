package com.linlay.agentplatform.service.chat;

public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException(String chatId) {
        super("chat not found: " + chatId);
    }
}
