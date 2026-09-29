package ru.lct.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatnetwork.domain.TypedId;

/** A bounded trace of one tried attachment, never a claim that the path was built. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RouteAttemptEvent {
    private long sequence;
    private String stage;
    private TypedId targetId;
    private String attemptId;
    private TypedId attachmentNetworkId;
    private List<Double> destination;
    private String status;
    private String reason;
    private Double lengthM;

    public RouteAttemptEvent() { }

    public RouteAttemptEvent(long sequence, String stage, TypedId targetId, String attemptId,
            TypedId attachmentNetworkId, List<Double> destination, String status,
            String reason, Double lengthM) {
        this.sequence = sequence;
        this.stage = stage;
        this.targetId = targetId;
        this.attemptId = attemptId;
        this.attachmentNetworkId = attachmentNetworkId;
        setDestination(destination);
        this.status = status;
        this.reason = reason;
        this.lengthM = lengthM;
    }

    public RouteAttemptEvent(RouteAttemptEvent other) {
        this(other.sequence, other.stage, other.targetId, other.attemptId,
                other.attachmentNetworkId, other.destination, other.status,
                other.reason, other.lengthM);
    }

    public static List<RouteAttemptEvent> copyAll(List<RouteAttemptEvent> events) {
        List<RouteAttemptEvent> copies = new ArrayList<>(events.size());
        for (RouteAttemptEvent event : events) copies.add(new RouteAttemptEvent(event));
        return copies;
    }

    public long getSequence() { return sequence; }
    public void setSequence(long value) { sequence = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { stage = value; }
    public TypedId getTargetId() { return targetId; }
    public void setTargetId(TypedId value) { targetId = value; }
    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String value) { attemptId = value; }
    public TypedId getAttachmentNetworkId() { return attachmentNetworkId; }
    public void setAttachmentNetworkId(TypedId value) { attachmentNetworkId = value; }
    public List<Double> getDestination() {
        return destination == null ? null : Collections.unmodifiableList(destination);
    }
    public void setDestination(List<Double> value) {
        destination = value == null ? null : new ArrayList<>(value);
    }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public Double getLengthM() { return lengthM; }
    public void setLengthM(Double value) { lengthM = value; }
}
