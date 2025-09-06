package com.example.outboxharvester.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEntry {

    @Id
    @GeneratedValue
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Lob
    @Column(name = "movie_json", nullable = false)
    private String movieJson;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String status;

    @Column(name = "traceparent")
    private String traceparent;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    // Getters and Setters
    public UUID getOutboxId() {
        return outboxId;
    }

    public void setOutboxId(UUID outboxId) {
        this.outboxId = outboxId;
    }

    public String getMovieJson() {
        return movieJson;
    }

    public void setMovieJson(String movieJson) {
        this.movieJson = movieJson;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
