package com.linlay.agentplatform.chat.index;

public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException(String chatId) {
        super("chat not found: " + chatId);
    }
}
