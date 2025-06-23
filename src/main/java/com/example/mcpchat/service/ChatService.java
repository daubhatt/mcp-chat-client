package com.example.mcpchat.service;

import com.example.mcpchat.dto.ChatRequest;
import com.example.mcpchat.dto.ConversationSummary;
import com.example.mcpchat.dto.CustomerSession;
import com.example.mcpchat.dto.MessageDTO;
import com.example.mcpchat.entity.ChatMessage;
import com.example.mcpchat.entity.Conversation;
import com.example.mcpchat.entity.Customer;
import com.example.mcpchat.repository.ChatMessageRepository;
import com.example.mcpchat.repository.ConversationRepository;
import com.example.mcpchat.repository.CustomerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final AnthropicChatModel anthropicChatModel;
    private final McpService mcpService;
    private final CustomerRepository customerRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.chat.max-history-size:50}")
    private int maxHistorySize;

    private Prompt createPromptWithMcpContext(List<Message> messages, String customerId, String jwtToken) {
        try {
            // Get available MCP tools for this specific user
            List<McpSchema.Tool> userTools = mcpService.getAvailableToolsForUser(customerId);
            if (!userTools.isEmpty()) {
                String mcpContext = userTools.toString();
                messages.addFirst(new SystemMessage("Available tools: " + mcpContext));
            }
        } catch (Exception e) {
            log.warn("Failed to get MCP context for user: {}", customerId, e);
        }

        // Get user-specific MCP client
        McpAsyncClient userMcpClient = mcpService.getClientForUser(customerId, jwtToken);

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder();

        if (userMcpClient != null) {
            optionsBuilder.toolCallbacks(new AsyncMcpToolCallbackProvider(userMcpClient).getToolCallbacks());
        } else {
            log.warn("No MCP client available for user: {}", customerId);
        }

        return new Prompt(messages, optionsBuilder.build());
    }

    // Update the processMessage method to pass customerId
    @Transactional
    public com.example.mcpchat.dto.ChatResponse processMessage(ChatRequest request, String jwtToken) {
        log.debug("Processing chat message for customer: {}", request.getCustomerId());

        try {
            // Get or create customer
            Customer customer = getOrCreateCustomer(request.getCustomerId());

            // Get or create conversation
            Conversation conversation = getOrCreateConversation(customer, request.getConversationId(), request.isNewConversation());

            // Save user message
            saveUserMessage(conversation, request.getMessage());

            // Get conversation history
            List<Message> messages = buildConversationHistory(conversation);

            // Add current user message
            messages.add(new UserMessage(request.getMessage()));
            messages.add(new UserMessage("Customer Id is " + customer.getCustomerId()));

            // Create prompt with user-specific MCP context
            Prompt prompt = createPromptWithMcpContext(messages, request.getCustomerId(), jwtToken);

            // Get AI response
            ChatResponse aiResponse = anthropicChatModel.call(prompt);
            String responseContent = aiResponse.getResult().getOutput().getText();

            // Save assistant message
            ChatMessage assistantMessage = saveAssistantMessage(conversation, responseContent, aiResponse);

            // Update conversation title if needed
            updateConversationTitle(conversation, request.getMessage(), responseContent);

            // Build response
            return com.example.mcpchat.dto.ChatResponse.builder()
                    .conversationId(conversation.getConversationId())
                    .response(responseContent)
                    .timestamp(LocalDateTime.now())
                    .messageId(assistantMessage.getId().toString())
                    .toolCalls(extractToolCalls(aiResponse))
                    .build();

        } catch (Exception e) {
            log.error("Error processing chat message", e);
            throw new RuntimeException("Failed to process message: " + e.getMessage(), e);
        }
    }

    private Customer getOrCreateCustomer(String customerId) {
        return customerRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    Customer newCustomer = Customer.builder()
                            .customerId(customerId)
                            .displayName(customerId)
                            .build();
                    return customerRepository.save(newCustomer);
                });
    }

    private Conversation getOrCreateConversation(Customer customer, String conversationId, boolean newConversation) {
        if (newConversation || conversationId == null) {
            return createNewConversation(customer);
        }

        return conversationRepository.findByConversationId(conversationId)
                .orElseGet(() -> createNewConversation(customer));
    }

    private Conversation createNewConversation(Customer customer) {
        Conversation conversation = Conversation.builder()
                .customer(customer)
                .title("New Conversation")
                .conversationId(UUID.randomUUID().toString())
                .build();
        return conversationRepository.save(conversation);
    }

    private ChatMessage saveUserMessage(Conversation conversation, String content) {
        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .messageType(ChatMessage.MessageType.USER)
                .content(content)
                .build();
        return chatMessageRepository.save(message);
    }

    private ChatMessage saveAssistantMessage(Conversation conversation, String content, ChatResponse aiResponse) {
        try {
            String metadata = objectMapper.writeValueAsString(Map.of(
                    "model", aiResponse.getMetadata().getModel(),
                    "usage", aiResponse.getMetadata().getUsage().getTotalTokens(),
                    "finishReason", aiResponse.getResult().getMetadata().getFinishReason()
            ));

            ChatMessage message = ChatMessage.builder()
                    .conversation(conversation)
                    .messageType(ChatMessage.MessageType.ASSISTANT)
                    .content(content)
                    .metadata(metadata)
                    .build();
            return chatMessageRepository.save(message);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            ChatMessage message = ChatMessage.builder()
                    .conversation(conversation)
                    .messageType(ChatMessage.MessageType.ASSISTANT)
                    .content(content)
                    .build();
            return chatMessageRepository.save(message);
        }
    }

    private List<Message> buildConversationHistory(Conversation conversation) {
        List<ChatMessage> recentMessages = chatMessageRepository
                .findRecentMessagesByConversationId(conversation.getConversationId(), maxHistorySize);

        Collections.reverse(recentMessages); // Ensure chronological order

        return recentMessages.stream()
                .map(this::convertToAiMessage)
                .collect(Collectors.toList());
    }

    private Message convertToAiMessage(ChatMessage message) {
        return switch (message.getMessageType()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
            case SYSTEM -> new SystemMessage(message.getContent());
            case TOOL_RESULT -> new SystemMessage("Tool Result: " + message.getContent());
        };
    }



    private List<com.example.mcpchat.dto.ChatResponse.ToolCallResult> extractToolCalls(ChatResponse response) {
        // Extract tool calls from the AI response if any
        List<com.example.mcpchat.dto.ChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

        try {
            // Check if response contains tool calls
            if (response.getResult().getMetadata().containsKey("toolCalls")) {
                List<Map<String, Object>> calls = response.getResult().getMetadata().get("toolCalls");

                for (Map<String, Object> call : calls) {
                    toolCalls.add(com.example.mcpchat.dto.ChatResponse.ToolCallResult.builder()
                            .toolName((String) call.get("name"))
                            .parameters(objectMapper.writeValueAsString(call.get("parameters")))
                            .result((String) call.get("result"))
                            .success((Boolean) call.getOrDefault("success", true))
                            .error((String) call.get("error"))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract tool calls", e);
        }

        return toolCalls;
    }

    private void updateConversationTitle(Conversation conversation, String userMessage, String assistantResponse) {
        if ("New Conversation".equals(conversation.getTitle()) && !userMessage.isEmpty()) {
            String title = userMessage.length() > 50 ?
                    userMessage.substring(0, 47) + "..." : userMessage;
            conversation.setTitle(title);
            conversationRepository.save(conversation);
        }
    }

    @Transactional(readOnly = true)
    public CustomerSession getCustomerSession(String customerId) {
        Optional<Customer> customerOpt = customerRepository.findByCustomerId(customerId);
        if (customerOpt.isEmpty()) {
            return null;
        }

        Customer customer = customerOpt.get();
        List<Conversation> conversations = conversationRepository
                .findByCustomerIdOrderByUpdatedAtDesc(customerId);

        List<ConversationSummary> summaries = conversations.stream()
                .map(this::convertToSummary)
                .collect(Collectors.toList());

        String currentConversationId = conversations.isEmpty() ? null :
                conversations.getFirst().getConversationId();

        return CustomerSession.builder()
                .customerId(customer.getCustomerId())
                .displayName(customer.getDisplayName())
                .lastActiveAt(customer.getLastActiveAt())
                .conversations(summaries)
                .currentConversationId(currentConversationId)
                .build();
    }

    private ConversationSummary convertToSummary(Conversation conversation) {
        long messageCount = chatMessageRepository.countByConversationId(conversation.getConversationId());

        List<ChatMessage> recentMessages = chatMessageRepository
                .findRecentMessagesByConversationId(conversation.getConversationId(), 1);

        String lastMessage = recentMessages.isEmpty() ? "" :
                recentMessages.getFirst().getContent();
        if (lastMessage.length() > 100) {
            lastMessage = lastMessage.substring(0, 97) + "...";
        }

        return ConversationSummary.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .messageCount((int) messageCount)
                .lastMessage(lastMessage)
                .isActive(conversation.getIsActive())
                .build();
    }

    @Transactional(readOnly = true)
    public List<MessageDTO> getConversationMessages(String conversationId) {
        List<ChatMessage> messages = chatMessageRepository
                .findByConversationIdOrderByCreatedAt(conversationId);

        return messages.stream()
                .map(MessageDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteConversation(String customerId, String conversationId) {
        Optional<Conversation> conversationOpt = conversationRepository
                .findByConversationId(conversationId);

        if (conversationOpt.isPresent() &&
                conversationOpt.get().getCustomer().getCustomerId().equals(customerId)) {
            conversationRepository.delete(conversationOpt.get());
        } else {
            throw new IllegalArgumentException("Conversation not found or access denied");
        }
    }

    @Transactional
    public void updateCustomerActivity(String customerId) {
        customerRepository.updateLastActiveTime(customerId, LocalDateTime.now());
    }
}