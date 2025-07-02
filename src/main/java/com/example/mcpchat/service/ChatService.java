package com.example.mcpchat.service;

import com.example.mcpchat.config.AiSummaryCache;
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
import java.time.format.DateTimeFormatter;
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
    private final AiSummaryCache aiSummaryCache;

    @Value("${app.chat.max-history-size:20}")
    private int maxHistorySize;

    private Prompt createPromptWithMcpContext(List<Message> messages, String customerId, String jwtToken, Integer maxTokens) {
        // Get user-specific MCP client
        McpAsyncClient userMcpClient = mcpService.getClientForUser(customerId, jwtToken);

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder();

        if (userMcpClient != null) {
            optionsBuilder.maxTokens(maxTokens != null ? maxTokens : 600);
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

            messages.add(createSystemMessageWithCustomerId(request.getCustomerId()));
            // Add customer summary as assistant message
            messages.add(new AssistantMessage(getCustomerSummary(customer.getCustomerId(), jwtToken).get("aiSummary").toString()));

            // Create prompt with user-specific MCP context
            Prompt prompt = createPromptWithMcpContext(messages, request.getCustomerId(), jwtToken, null);

            // Get AI response
            ChatResponse aiResponse = anthropicChatModel.call(prompt);
            String responseContent = aiResponse.getResult().getOutput().getText();

            // Save assistant message
            ChatMessage assistantMessage = saveAssistantMessage(conversation, responseContent, aiResponse);

            // Update conversation title if needed
            updateConversationTitle(conversation, request.getMessage());

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

    private Message createSystemMessageWithCustomerId(String customerId) {

        String systemPrompt = String.format("""
                Customer ID: %s (use in ALL tool calls)
                
                You are a banking assistant. Provide concise, actionable responses to user queries.
                You are friendly, personable and polite.
                
                Rules to follow:
                1. NEVER modify URLs - copy them EXACTLY as received from tools
                2. If tools return URLs, include them word-for-word in your response
                3. Be direct and specific - no fluff or paraphrasing
                4. Temperature is 0 - stick to facts only
                5. Do not generate any URLs, only use URLs provided by tools
                6. ALWAYS provide a response - if tool response is empty/minimal, acknowledge the user's request
                
                Format: [Status] → [Action needed] → [URL] (only include URL section if URL exists)
                """, customerId);

        return new SystemMessage(systemPrompt);
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

    private void updateConversationTitle(Conversation conversation, String userMessage) {
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

    @Transactional(readOnly = true)
    public Map<String, Object> getCustomerSummary(String customerId, String jwtToken) {
        return aiSummaryCache.get(customerId, customer -> getCustomerSummaryFromBackend(customer, jwtToken));
    }

    private Map<String, Object> getCustomerSummaryFromBackend(String customerId, String jwtToken) {

        Map<String, Object> summary = new HashMap<>();

        try {
            // Get customer basic info
            Optional<Customer> customerOpt = customerRepository.findByCustomerId(customerId);
            if (customerOpt.isPresent()) {
                Customer customer = customerOpt.get();
                summary.put("customerId", customer.getCustomerId());
                summary.put("displayName", customer.getDisplayName());
                summary.put("joinedAt", customer.getCreatedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));
                summary.put("lastActiveAt", customer.getLastActiveAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")));
            }

            // Get conversation statistics
            List<Conversation> conversations = conversationRepository.findByCustomerIdOrderByUpdatedAtDesc(customerId);
            summary.put("totalConversations", conversations.size());

            if (!conversations.isEmpty()) {
                summary.put("firstConversation", conversations.getLast().getCreatedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));
                summary.put("lastConversation", conversations.getFirst().getUpdatedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));
            }

            // Get total message count
            long totalMessages = conversations.stream()
                    .mapToLong(conv -> chatMessageRepository.countByConversationId(conv.getConversationId()))
                    .sum();
            summary.put("totalMessages", totalMessages);

            // Get available MCP tools
            List<McpSchema.Tool> availableTools = mcpService.getAvailableToolsForUser(customerId);
            summary.put("availableToolsCount", availableTools.size());
            summary.put("availableTools", availableTools.stream()
                    .map(tool -> Map.of(
                            "name", tool.name(),
                            "description", tool.description()
                    ))
                    .collect(Collectors.toList()));

            // Check MCP connection status
            summary.put("mcpConnected", mcpService.isConnectedForUser(customerId));

            // Generate summary using AI
            ChatResponse aiSummary = generateAISummary(customerId, jwtToken);
            summary.put("aiSummary", cleanAndValidateSummary(aiSummary.getResult().getOutput().getText()));
            return summary;
        } catch (Exception e) {
            log.error("Error generating customer summary for {}", customerId, e);
            summary.put("aiSummary", generateFormattedFallbackSummary(customerId, summary));
        }
        return summary;
    }

    private ChatResponse generateAISummary(String customerId, String jwtToken) {
        List<Message> messages = new ArrayList<>();

        String systemPrompt = """
                       You are a banking assistant. Get financial overview and create a welcome summary.
                
                        Format as HTML with sections:
                        - Financial Health: Net worth, trend, main assets/debts
                        - Account Highlights: Top accounts, balances, earnings
                        - Upcoming Actions: Due payments, dates, amounts
                
                        Structure:
                        ```html
                        <div class='space-y-4'>
                          <h4 class='font-semibold text-gray-800 flex items-center'>
                            <i class='fas fa-chart-line mr-2'></i>Financial Health
                          </h4>
                          <p class='text-gray-700'>[content]</p>
                
                          <h4 class='font-semibold text-gray-800 flex items-center'>
                            <i class='fas fa-piggy-bank mr-2'></i>Account Highlights
                          </h4>
                          <p class='text-gray-700'>[content]</p>
                
                          <h4 class='font-semibold text-gray-800 flex items-center'>
                            <i class='fas fa-calendar-alt mr-2'></i>Upcoming Actions
                          </h4>
                          <p class='text-gray-700'>[content]</p>
                
                          <h4 class='font-semibold text-gray-800 flex items-center'>
                            <i class='fas fa-rocket mr-2'></i>Opportunities
                          </h4>
                          <p class='text-gray-700'>[content]</p>
                        </div>
                        ```
                
                        Colors:
                        - Positive: text-green-600 font-semibold
                        - Negative: text-red-600 font-semibold
                        - Warning: text-amber-600 font-semibold
                        - Important: strong class='text-gray-800'
                """;

        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage("Generate personalized banking summary for me, my customer id is " + customerId));

        // Use MCP context with user-specific tools
        Prompt prompt = createPromptWithMcpContext(messages, customerId, jwtToken, 800);

        // Get AI response
        return anthropicChatModel.call(prompt);
    }

    private String cleanAndValidateSummary(String summary) {
        // Remove any markdown code blocks if AI included them
        summary = summary.replaceAll("```html", "").replaceAll("```", "");

        // Ensure it starts with div container if not present
        if (!summary.trim().startsWith("<div")) {
            summary = "<div class='space-y-4'>" + summary + "</div>";
        }

        // Fix common formatting issues
        summary = summary.replaceAll("(?<!>)\\s*-\\s*([A-Z][^:]*:)",
                "<h4 class='font-semibold text-gray-800 mt-4 mb-2'><i class='fas fa-info-circle mr-2'></i>$1</h4><p class='text-gray-700'>");

        // Ensure paragraphs are properly closed
        summary = summary.replaceAll("</p>\\s*([^<])", "</p><p class='text-gray-700'>$1");

        return summary.trim();
    }

    private String generateFormattedFallbackSummary(String customerId, Map<String, Object> basicInfo) {
        return String.format("""
                        <div class='space-y-4'>
                            <h4 class='font-semibold text-gray-800 flex items-center'>
                                <i class='fas fa-user-circle mr-2'></i>Welcome Back
                            </h4>
                            <p class='text-gray-700'>Welcome back, <strong>%s</strong>! Your banking overview is ready with <span class='text-blue-600 font-semibold'>%s conversations</span> and <span class='text-green-600 font-semibold'>%s total messages</span> in your history.</p>
                        
                            <h4 class='font-semibold text-gray-800 flex items-center'>
                                <i class='fas fa-tools mr-2'></i>Available Services
                            </h4>
                            <p class='text-gray-700'>You have access to <strong>%s banking tools</strong> for comprehensive financial management including account inquiries, transfers, payments, and financial planning.</p>
                        
                            <h4 class='font-semibold text-gray-800 flex items-center'>
                                <i class='fas fa-calendar-check mr-2'></i>Account Status
                            </h4>
                            <p class='text-gray-700'>Member since <span class='text-blue-600 font-semibold'>%s</span> • Last active <span class='text-green-600 font-semibold'>%s</span></p>
                        </div>
                        """,
                basicInfo.getOrDefault("displayName", customerId),
                basicInfo.getOrDefault("totalConversations", "0"),
                basicInfo.getOrDefault("totalMessages", "0"),
                basicInfo.getOrDefault("availableToolsCount", "0"),
                basicInfo.getOrDefault("joinedAt", "today"),
                basicInfo.getOrDefault("lastActiveAt", "now")
        );
    }
}