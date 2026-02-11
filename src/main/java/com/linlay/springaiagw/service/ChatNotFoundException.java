package com.linlay.springaiagw.service;

public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException(String chatId) {
        super("chat not found: " + chatId);
    }
}
