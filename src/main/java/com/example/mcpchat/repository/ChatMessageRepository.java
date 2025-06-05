package com.example.mcpchat.repository;

import com.example.mcpchat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.conversationId = :conversationId ORDER BY m.createdAt ASC")
    List<ChatMessage> findByConversationIdOrderByCreatedAt(@Param("conversationId") String conversationId);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.conversation.conversationId = :conversationId")
    long countByConversationId(@Param("conversationId") String conversationId);

    @Query("SELECT m FROM ChatMessage m WHERE m.conversation.conversationId = :conversationId ORDER BY m.createdAt DESC LIMIT :limit")
    List<ChatMessage> findRecentMessagesByConversationId(@Param("conversationId") String conversationId, @Param("limit") int limit);
}