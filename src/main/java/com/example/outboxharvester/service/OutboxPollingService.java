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
        List<OutboxEntry> entries = entityManager.createQuery("SELECT o FROM OutboxEntry o WHERE o.status = 'PENDING'", OutboxEntry.class)
                .getResultList();

        for (OutboxEntry entry : entries) {
            try {
                TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
                Context parentContext = propagator.extract(Context.current(), entry.getTraceparent(), new TextMapGetter<String>() {
                    @Override
                    public Iterable<String> keys(String carrier) {
                        return List.of("traceparent");
                    }

                    @Override
                    public String get(String carrier, String key) {
                        return carrier;
                    }
                });

                Span span = tracer.spanBuilder("OutboxProcessing")
                        .setParent(parentContext)
                        .startSpan();

                try (var scope = span.makeCurrent()) {
                    rabbitTemplate.convertAndSend("outboxQueue", entry.getMovieJson());
                    entry.setStatus("PROCESSED");
                    entityManager.merge(entry);
                    processedEvents.incrementAndGet();
                } catch (Exception e) {
                    span.recordException(e);
                    int retryCount = entry.getRetryCount() + 1;
                    entry.setRetryCount(retryCount);

                    if (retryCount > retryLimit) {
                        entry.setStatus("FAILED");
                        failedEvents.incrementAndGet();
                    }

                    entityManager.merge(entry);
                    e.printStackTrace();
                } finally {
                    span.end();
                }
            } catch (Exception e) {
                int retryCount = entry.getRetryCount() + 1;
                entry.setRetryCount(retryCount);

                if (retryCount > retryLimit) {
                    entry.setStatus("FAILED");
                }

                entityManager.merge(entry);
                e.printStackTrace();
            }
        }
    }
}
