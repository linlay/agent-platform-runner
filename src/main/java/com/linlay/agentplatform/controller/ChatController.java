package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.model.api.ChatDetailResponse;
import com.linlay.agentplatform.model.api.ChatSummaryResponse;
import com.linlay.agentplatform.model.api.MarkChatReadRequest;
import com.linlay.agentplatform.model.api.MarkChatReadResponse;
import com.linlay.agentplatform.security.ChatImageTokenHelper;
import com.linlay.agentplatform.chat.history.ChatRecordStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatRecordStore chatRecordStore;
    private final ChatImageTokenHelper chatImageTokenHelper;

    public ChatController(ChatRecordStore chatRecordStore, ChatImageTokenHelper chatImageTokenHelper) {
        this.chatRecordStore = chatRecordStore;
        this.chatImageTokenHelper = chatImageTokenHelper;
    }

    @GetMapping("/chats")
    public ApiResponse<List<ChatSummaryResponse>> chats(
            @RequestParam(required = false) String lastRunId,
            @RequestParam(required = false) String agentKey
    ) {
        return ApiResponse.success(chatRecordStore.listChats(lastRunId, agentKey));
    }

    @PostMapping("/read")
    public ApiResponse<MarkChatReadResponse> markRead(@Valid @RequestBody MarkChatReadRequest request) {
        ChatRecordStore.MarkChatReadResult result = chatRecordStore.markChatRead(request.chatId());
        return ApiResponse.success(new MarkChatReadResponse(result.chatId(), result.readStatus(), result.readAt()));
    }

    @GetMapping("/chat")
    public ApiResponse<ChatDetailResponse> chat(
            @RequestParam String chatId,
            @RequestParam(defaultValue = "false") boolean includeRawMessages,
            ServerWebExchange exchange
    ) {
        ChatDetailResponse detail = chatRecordStore.loadChat(chatId, includeRawMessages);
        String chatImageToken = chatImageTokenHelper.issueChatImageToken(exchange, detail.chatId());
        return ApiResponse.success(new ChatDetailResponse(
                detail.chatId(),
                detail.chatName(),
                chatImageToken,
                detail.rawMessages(),
                detail.events(),
                detail.plan(),
                detail.artifact(),
                detail.references()
        ));
    }
}
