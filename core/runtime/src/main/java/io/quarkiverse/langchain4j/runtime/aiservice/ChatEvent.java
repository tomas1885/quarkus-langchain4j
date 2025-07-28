package io.quarkiverse.langchain4j.runtime.aiservice;

import java.util.List;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.ToolExecution;

public class ChatEvent {

    public enum ChatEventType {
        Completed,
        ToolExecuted,
        PartialResponse,
        ContentFetched,
        AccumulatedResponse

    }

    private final ChatEventType eventType;

    public ChatEvent(ChatEventType eventType) {
        this.eventType = eventType;
    }

    public ChatEventType getEventType() {
        return eventType;
    }

    public static class ChatCompletedEvent extends ChatEvent {
        private final ChatResponse chatResponse;

        public ChatCompletedEvent(ChatResponse chatResponse) {
            super(ChatEventType.Completed);
            this.chatResponse = chatResponse;
        }

        public ChatResponse getChatResponse() {
            return chatResponse;
        }
    }

    public static class ToolExecutedEvent extends ChatEvent {
        private final ToolExecution execution;

        public ToolExecutedEvent(ToolExecution execution) {
            super(ChatEventType.ToolExecuted);
            this.execution = execution;
        }

        public ToolExecution getExecution() {
            return execution;
        }
    }

    public static class PartialResponseEvent extends ChatEvent {
        private final String chunk;

        public PartialResponseEvent(String chunk) {
            super(ChatEventType.PartialResponse);
            this.chunk = chunk;
        }

        public String getChunk() {
            return chunk;
        }
    }

    public static class ContentFetchedEvent extends ChatEvent {

        private final List<Content> content;

        public ContentFetchedEvent(List<Content> content) {
            super(ChatEventType.ContentFetched);
            this.content = content;
        }

        public List<Content> getContent() {
            return content;
        }
    }

    public static class AccumulatedResponseEvent extends ChatEvent {

        private final String message;
        private final ChatResponseMetadata metadata;

        public AccumulatedResponseEvent(String message, ChatResponseMetadata metadata) {
            super(ChatEventType.AccumulatedResponse);
            this.message = message;
            this.metadata = metadata;
        }

        public String getMessage() {
            return message;
        }

        public ChatResponseMetadata getMetadata() {
            return metadata;
        }
    }

}
