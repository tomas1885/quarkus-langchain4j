package io.quarkiverse.langchain4j.runtime.aiservice;

import static dev.langchain4j.data.message.UserMessage.userMessage;
import static io.quarkiverse.langchain4j.guardrails.InputGuardrailParams.rewriteUserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.rag.AugmentationResult;
import io.quarkiverse.langchain4j.audit.AuditSourceInfo;
import io.quarkiverse.langchain4j.audit.InputGuardrailExecutedEvent;
import io.quarkiverse.langchain4j.audit.OutputGuardrailExecutedEvent;
import io.quarkiverse.langchain4j.audit.internal.DefaultInputGuardrailExecutedEvent;
import io.quarkiverse.langchain4j.audit.internal.DefaultOutputGuardrailExecutedEvent;
import io.quarkiverse.langchain4j.guardrails.Guardrail;
import io.quarkiverse.langchain4j.guardrails.GuardrailParams;
import io.quarkiverse.langchain4j.guardrails.GuardrailResult;
import io.quarkiverse.langchain4j.guardrails.InputGuardrail;
import io.quarkiverse.langchain4j.guardrails.InputGuardrailParams;
import io.quarkiverse.langchain4j.guardrails.InputGuardrailResult;
import io.quarkiverse.langchain4j.guardrails.OutputGuardrail;
import io.quarkiverse.langchain4j.guardrails.OutputGuardrailParams;
import io.quarkiverse.langchain4j.guardrails.OutputGuardrailResult;
import io.quarkiverse.langchain4j.guardrails.OutputTokenAccumulator;
import io.smallrye.mutiny.Multi;

public class GuardrailsSupport {

    public static UserMessage invokeInputGuardrails(AiServiceMethodCreateInfo methodCreateInfo, UserMessage userMessage,
            ChatMemory chatMemory, AugmentationResult augmentationResult, Map<String, Object> templateVariables,
            BeanManager beanManager, AuditSourceInfo auditSourceInfo) {
        InputGuardrailResult result;
        try {

            String userMessageTemplate = methodCreateInfo.getUserMessageTemplate();

            result = invokeInputGuardRails(methodCreateInfo,
                    new InputGuardrailParams(userMessage, chatMemory, augmentationResult, userMessageTemplate,
                            Collections.unmodifiableMap(templateVariables)),
                    beanManager, auditSourceInfo);
        } catch (Exception e) {
            throw new GuardrailException(e.getMessage(), e);
        }
        if (!result.isSuccess()) {
            throw new GuardrailException(result.toString(), result.getFirstFailureException());
        }

        if (result.hasRewrittenResult()) {
            userMessage = rewriteUserMessage(userMessage, result.successfulText());
        }
        return userMessage;
    }

    public static OutputGuardrailResponse invokeOutputGuardrails(AiServiceMethodCreateInfo methodCreateInfo,
            ChatMemory chatMemory,
            ChatModel chatModel,
            ChatResponse response,
            List<ToolSpecification> toolSpecifications,
            OutputGuardrailParams output, BeanManager beanManager, AuditSourceInfo auditSourceInfo) {
        int attempt = 0;
        int max = methodCreateInfo.getGuardrailsMaxRetry();
        if (max <= 0) {
            max = 1;
        }

        OutputGuardrailResult result = null;
        while (attempt < max) {
            try {
                result = invokeOutputGuardRails(methodCreateInfo, output, beanManager, auditSourceInfo);
            } catch (Exception e) {
                throw new GuardrailException(e.getMessage(), e);
            }

            if (result.isSuccess()) {
                break;
            }

            if (result.isRetry()) {
                // Retry
                if (result.getReprompt() != null) {
                    // Retry with reprompting
                    chatMemory.add(userMessage(result.getReprompt()));
                }

                response = AiServiceMethodImplementationSupport.executeRequest(methodCreateInfo, chatMemory.messages(),
                        chatModel, toolSpecifications);
                chatMemory.add(response.aiMessage());
            } else {
                throw new GuardrailException(result.toString(), result.getFirstFailureException());
            }

            attempt++;
            output = new OutputGuardrailParams(response.aiMessage(), output.memory(),
                    output.augmentationResult(), output.userMessageTemplate(), output.variables());
        }

        if (attempt == max) {
            var failureMessages = Optional.ofNullable(result.failures())
                    .orElseGet(List::of)
                    .stream()
                    .map(OutputGuardrailResult.Failure::message)
                    .collect(Collectors.joining(System.lineSeparator()));

            throw new GuardrailException(
                    "Output validation failed. The guardrails have reached the maximum number of retries. Guardrail messages:"
                            + System.lineSeparator() + failureMessages);
        }

        return new OutputGuardrailResponse(response, result);
    }

    public record OutputGuardrailResponse(ChatResponse response, OutputGuardrailResult result) {

        public boolean hasRewrittenResult() {
            return result != null && result.hasRewrittenResult();
        }

        public Object getRewrittenResult() {
            return hasRewrittenResult() ? result.successfulResult() : null;
        }
    }

    @SuppressWarnings("unchecked")
    private static OutputGuardrailResult invokeOutputGuardRails(AiServiceMethodCreateInfo methodCreateInfo,
            OutputGuardrailParams params, BeanManager beanManager, AuditSourceInfo auditSourceInfo) {
        if (methodCreateInfo.getOutputGuardrailsClassNames().isEmpty()) {
            return OutputGuardrailResult.success();
        }
        List<Class<? extends OutputGuardrail>> classes;
        synchronized (AiServiceMethodImplementationSupport.class) {
            classes = methodCreateInfo.getOutputGuardrailsClasses();
            if (classes.isEmpty()) {
                for (String className : methodCreateInfo.getOutputGuardrailsClassNames()) {
                    try {
                        classes.add((Class<? extends OutputGuardrail>) Class.forName(className, true,
                                Thread.currentThread().getContextClassLoader()));
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Could not find " + OutputGuardrail.class.getSimpleName() + " implementation class: "
                                        + className,
                                e);
                    }
                }
            }
        }

        return guardrailResult(params, (List) classes, OutputGuardrailResult.success(), OutputGuardrailResult::failure,
                beanManager, auditSourceInfo);
    }

    @SuppressWarnings("unchecked")
    private static InputGuardrailResult invokeInputGuardRails(AiServiceMethodCreateInfo methodCreateInfo,
            InputGuardrailParams params, BeanManager beanManager, AuditSourceInfo auditSourceInfo) {
        if (methodCreateInfo.getInputGuardrailsClassNames().isEmpty()) {
            return InputGuardrailResult.success();
        }
        List<Class<? extends InputGuardrail>> classes;
        synchronized (AiServiceMethodImplementationSupport.class) {
            classes = methodCreateInfo.getInputGuardrailsClasses();
            if (classes.isEmpty()) {
                for (String className : methodCreateInfo.getInputGuardrailsClassNames()) {
                    try {
                        classes.add((Class<? extends InputGuardrail>) Class.forName(className, true,
                                Thread.currentThread().getContextClassLoader()));
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Could not find " + InputGuardrail.class.getSimpleName() + " implementation class: "
                                        + className,
                                e);
                    }
                }
            }
        }

        return guardrailResult(params, (List) classes, InputGuardrailResult.success(), InputGuardrailResult::failure,
                beanManager, auditSourceInfo);
    }

    private static <GR extends GuardrailResult> GR guardrailResult(GuardrailParams params,
            List<Class<? extends Guardrail>> classes, GR accumulatedResults,
            Function<List<? extends GuardrailResult.Failure>, GR> producer, BeanManager beanManager,
            AuditSourceInfo auditSourceInfo) {
        for (Class<? extends Guardrail> bean : classes) {
            var guardrail = CDI.current().select(bean).get();
            GR result = (GR) guardrail.validate(params).validatedBy(bean);

            if (guardrail instanceof InputGuardrail) {
                beanManager.getEvent().select(InputGuardrailExecutedEvent.class)
                        .fire(new DefaultInputGuardrailExecutedEvent(auditSourceInfo, (InputGuardrailParams) params,
                                (InputGuardrailResult) result, (Class<InputGuardrail>) guardrail.getClass()));
            } else if (guardrail instanceof OutputGuardrail) {
                beanManager.getEvent().select(OutputGuardrailExecutedEvent.class)
                        .fire(new DefaultOutputGuardrailExecutedEvent(auditSourceInfo, (OutputGuardrailParams) params,
                                (OutputGuardrailResult) result, (Class<OutputGuardrail>) guardrail.getClass()));
            }

            if (result.isFatal()) {
                return accumulatedResults.hasRewrittenResult() ? (GR) result.blockRetry() : result;
            }
            if (result.hasRewrittenResult()) {
                params = params.withText(result.successfulText());
            }
            accumulatedResults = compose(accumulatedResults, result, producer);
        }

        return accumulatedResults;
    }

    private static <GR extends GuardrailResult> GR compose(GR oldResult, GR newResult,
            Function<List<? extends GuardrailResult.Failure>, GR> producer) {
        if (oldResult.isSuccess()) {
            return newResult;
        }
        if (newResult.isSuccess()) {
            return oldResult;
        }
        List<? extends GuardrailResult.Failure> failures = new ArrayList<>();
        failures.addAll(oldResult.failures());
        failures.addAll(newResult.failures());
        return producer.apply(failures);
    }

    private static class ChatResponseAccumulator {
        private final StringBuilder stringBuilder;
        private ChatResponseMetadata metadata;

        ChatResponseAccumulator() {
            this.stringBuilder = new StringBuilder();
            this.metadata = null;
        }

    }

    public static Multi<ChatEvent.AccumulatedResponseEvent> accumulate(
            Multi<ChatEvent> upstream, AiServiceMethodCreateInfo methodCreateInfo) {
        OutputTokenAccumulator accumulator;
        synchronized (AiServiceMethodImplementationSupport.class) {
            accumulator = methodCreateInfo.getOutputTokenAccumulator();
            if (accumulator == null) {
                String cn = methodCreateInfo.getOutputTokenAccumulatorClassName();
                if (cn == null) {
                    return upstream.collect().in(ChatResponseAccumulator::new, (chatResponseAccumulator, chatEvent) -> {
                        if (chatEvent
                                .getEventType() == ChatEvent.ChatEventType.PartialResponse) {
                            chatResponseAccumulator.stringBuilder.append(
                                    ((ChatEvent.PartialResponseEvent) chatEvent)
                                            .getChunk());
                        }
                        if (chatEvent
                                .getEventType() == ChatEvent.ChatEventType.Completed) {
                            chatResponseAccumulator.metadata = ((ChatEvent.ChatCompletedEvent) chatEvent)
                                    .getChatResponse().metadata();
                        }
                    })
                            .map(acc -> new ChatEvent.AccumulatedResponseEvent(
                                    acc.stringBuilder.toString(), acc.metadata))
                            .toMulti();
                }
                try {
                    Class<? extends OutputTokenAccumulator> clazz = Class
                            .forName(cn, true, Thread.currentThread().getContextClassLoader())
                            .asSubclass(OutputTokenAccumulator.class);
                    accumulator = CDI.current().select(clazz).get();
                    methodCreateInfo.setOutputTokenAccumulator(accumulator);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Could not find " + OutputTokenAccumulator.class.getSimpleName() + " implementation class: " + cn,
                            e);
                }
            }
        }
        var actual = accumulator;
        AtomicReference<ChatResponseMetadata> metadataAtomicReference = new AtomicReference<>();
        return upstream.invoke(it -> {
            if (it.getEventType() == ChatEvent.ChatEventType.Completed) {
                metadataAtomicReference.set(((ChatEvent.ChatCompletedEvent) it)
                        .getChatResponse().metadata());
            }
        }).filter(it -> it.getEventType() == ChatEvent.ChatEventType.PartialResponse)
                .map(it -> ((ChatEvent.PartialResponseEvent) it).getChunk())
                .plug(actual::accumulate)
                .map(s -> new ChatEvent.AccumulatedResponseEvent(s,
                        Optional.ofNullable(metadataAtomicReference.get()).orElse(ChatResponseMetadata.builder().build())));

    }

    public static OutputGuardrailResult invokeOutputGuardrailsForStream(AiServiceMethodCreateInfo methodCreateInfo,
            OutputGuardrailParams outputGuardrailParams, BeanManager beanManager, AuditSourceInfo auditSourceInfo) {
        return invokeOutputGuardRails(methodCreateInfo, outputGuardrailParams, beanManager, auditSourceInfo);
    }

    static class GuardrailRetryException extends RuntimeException {
        // Marker class to indicate a retry to the downstream consumer.
    }
}
