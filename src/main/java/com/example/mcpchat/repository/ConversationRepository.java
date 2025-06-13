package com.example.mcpchat.repository;

import com.example.mcpchat.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByConversationId(String conversationId);

    @Query("SELECT c FROM Conversation c WHERE c.customer.customerId = :customerId ORDER BY c.updatedAt DESC")
    List<Conversation> findByCustomerIdOrderByUpdatedAtDesc(@Param("customerId") String customerId);
}