package com.example.outboxharvester.service;

import com.example.outboxharvester.model.OutboxEntry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.List;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OutboxPollingService {

    private final AtomicInteger processedEvents = new AtomicInteger(0);
    private final AtomicInteger failedEvents = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Value("${outbox.retry.limit:3}")
    private int retryLimit;

    @Autowired
    private Tracer tracer;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Autowired
    public OutboxPollingService(MeterRegistry meterRegistry) {
        meterRegistry.gauge("outbox.events.processed", processedEvents);
        meterRegistry.gauge("outbox.events.failed", failedEvents);
    }

    @Scheduled(fixedRate = 5000)
    @Transactional
    public void pollOutboxTable() {
        Span pollSpan = tracer.spanBuilder("OutboxPollTable")
                .startSpan();
        
        try (var pollScope = pollSpan.makeCurrent()) {
            pollSpan.setAttribute("polling.interval.ms", 5000);
            
            // Fetch pending entries span
            Span fetchSpan = tracer.spanBuilder("FetchPendingEntries")
                    .startSpan();
            List<OutboxEntry> entries;
            
            try (var fetchScope = fetchSpan.makeCurrent()) {
                String query = "SELECT o FROM OutboxEntry o WHERE o.status = 'PENDING'";
                fetchSpan.setAttribute("query", query);
                entries = entityManager.createQuery(query, OutboxEntry.class)
                        .getResultList();
                fetchSpan.setAttribute("entries.count", entries.size());
            } finally {
                fetchSpan.end();
            }
            
            pollSpan.setAttribute("entries.total", entries.size());

            for (OutboxEntry entry : entries) {
                try {
                    // Context propagation span
                    Span extractSpan = tracer.spanBuilder("ExtractTraceContext")
                            .startSpan();
                    
                    Context parentContext;
                    try (var extractScope = extractSpan.makeCurrent()) {
                        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
                        extractSpan.setAttribute("traceparent", entry.getTraceparent());
                        
                        parentContext = propagator.extract(Context.current(), entry.getTraceparent(), new TextMapGetter<String>() {
                            @Override
                            public Iterable<String> keys(String carrier) {
                                return List.of("traceparent");
                            }

                            @Override
                            public String get(String carrier, String key) {
                                return carrier;
                            }
                        });
                    } finally {
                        extractSpan.end();
                    }

                    Span entrySpan = tracer.spanBuilder("ProcessOutboxEntry")
                            .setParent(parentContext)
                            .startSpan();

                    try (var entryScope = entrySpan.makeCurrent()) {
                        entrySpan.setAttribute("entry.id", entry.getOutboxId().toString());
                        entrySpan.setAttribute("entry.retryCount", entry.getRetryCount());
                        
                        // Send to RabbitMQ span
                        Span sendSpan = tracer.spanBuilder("SendToRabbitMQ")
                                .startSpan();
                        try (var sendScope = sendSpan.makeCurrent()) {
                            sendSpan.setAttribute("queue", "outbox");
                            sendSpan.setAttribute("action", entry.getAction());
                            
                            // Create a message that includes action, data, and traceparent
                            String message = String.format("{\"action\":\"%s\",\"data\":%s,\"traceparent\":\"%s\"}", 
                                entry.getAction(), entry.getMovieJson(), entry.getTraceparent());
                                
                            rabbitTemplate.convertAndSend("outbox", message);
                        } finally {
                            sendSpan.end();
                        }
                        
                        // Update entry status span
                        Span updateSpan = tracer.spanBuilder("UpdateEntryStatus")
                                .startSpan();
                        try (var updateScope = updateSpan.makeCurrent()) {
                            updateSpan.setAttribute("status.new", "PROCESSED");
                            entry.setStatus("PROCESSED");
                            entityManager.merge(entry);
                            processedEvents.incrementAndGet();
                            updateSpan.setAttribute("processed.count", processedEvents.get());
                        } finally {
                            updateSpan.end();
                        }
                    } catch (Exception e) {
                        entrySpan.recordException(e);
                        
                        // Handle retry span
                        Span retrySpan = tracer.spanBuilder("HandleRetry")
                                .startSpan();
                        try (var retryScope = retrySpan.makeCurrent()) {
                            int retryCount = entry.getRetryCount() + 1;
                            retrySpan.setAttribute("retry.count", retryCount);
                            retrySpan.setAttribute("retry.limit", retryLimit);
                            entry.setRetryCount(retryCount);

                            if (retryCount > retryLimit) {
                                retrySpan.setAttribute("status.new", "FAILED");
                                entry.setStatus("FAILED");
                                failedEvents.incrementAndGet();
                                retrySpan.setAttribute("failed.count", failedEvents.get());
                            }

                            entityManager.merge(entry);
                        } finally {
                            retrySpan.end();
                        }
                        
                        e.printStackTrace();
                    } finally {
                        entrySpan.end();
                    }
                } catch (Exception e) {
                    // Outer exception handling span
                    Span errorSpan = tracer.spanBuilder("OutboxEntryError")
                            .startSpan();
                    try (var errorScope = errorSpan.makeCurrent()) {
                        errorSpan.recordException(e);
                        
                        int retryCount = entry.getRetryCount() + 1;
                        errorSpan.setAttribute("retry.count", retryCount);
                        errorSpan.setAttribute("retry.limit", retryLimit);
                        entry.setRetryCount(retryCount);

                        if (retryCount > retryLimit) {
                            errorSpan.setAttribute("status.new", "FAILED");
                            entry.setStatus("FAILED");
                            failedEvents.incrementAndGet();
                            errorSpan.setAttribute("failed.count", failedEvents.get());
                        }

                        entityManager.merge(entry);
                    } finally {
                        errorSpan.end();
                    }
                    
                    e.printStackTrace();
                }
            }
        } finally {
            pollSpan.end();
        }
    }
}
