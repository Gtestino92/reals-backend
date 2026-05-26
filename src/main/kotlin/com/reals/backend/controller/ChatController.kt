package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.ChatMessageResponse
import com.reals.backend.controller.dto.ChatResponse
import com.reals.backend.controller.dto.SendMessageRequest
import com.reals.backend.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    @GetMapping("/{chatId}")
    fun getChat(
        @PathVariable chatId: UUID
    ): ResponseEntity<ChatResponse> {

        val chat = chatService.findByIdOrThrow(
            chatId = chatId
        )

        return ResponseEntity.ok(
            ChatResponse.from(chat)
        )
    }

    /**
     * Returns the active first chat for a given match
     * Useful when you only have the matchId (eg right after /matchmaking/process)
     */
    @GetMapping("/by-match/{matchId}")
    fun getChatByMatch(
        @PathVariable matchId: UUID
    ): ResponseEntity<ChatResponse> {
        return ResponseEntity.ok(ChatResponse.from(chatService.findActiveFirstChatOrThrow(matchId)))
    }

    /**
     * Returns the active second chat for a given connection
     * Useful after sched negotiation is confirmed to obtain chatId2
     */
    @GetMapping("/by-connection/{connectionId}")
    fun getChatByConnection(
        @PathVariable connectionId: UUID
    ): ResponseEntity<ChatResponse> {
        return ResponseEntity.ok(ChatResponse.from(chatService.findActiveSecondChatOrThrow(connectionId)))
    }

    @PostMapping("/{chatId}/messages")
    fun sendMessage(
        @CurrentUserId userId: UUID,
        @PathVariable chatId: UUID,
        @RequestBody request: SendMessageRequest
    ): ResponseEntity<ChatMessageResponse> {

        val message = chatService.sendMessage(
            chatId = chatId,
            senderId = userId,
            content = request.content
        )

        return ResponseEntity.ok(
            ChatMessageResponse.from(message)
        )
    }

    @GetMapping("/{chatId}/messages")
    fun getMessages(
        @PathVariable chatId: UUID
    ): ResponseEntity<List<ChatMessageResponse>> {

        val messages = chatService.getMessages(
            chatId = chatId
        )

        return ResponseEntity.ok(
            messages.map { ChatMessageResponse.from(it) }
        )
    }

    @PostMapping("/{chatId}/close")
    fun closeSecondChat(
        @PathVariable chatId: UUID,
        @CurrentUserId userId: UUID
    ): ResponseEntity<ChatResponse> {
        chatService.closeSecondChat(chatId, userId)
        return ResponseEntity.ok(ChatResponse.from(chatService.findByIdOrThrow(chatId)))
    }
}