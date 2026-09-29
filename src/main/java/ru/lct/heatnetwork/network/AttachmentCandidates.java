package ru.lct.heatnetwork.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministically ordered attachment choices for a requested route approach location. */
public final class AttachmentCandidates {
    private final List<AttachmentCandidate> candidates;

    public AttachmentCandidates(List<AttachmentCandidate> candidates) {
        if (candidates == null || candidates.stream().anyMatch(candidate -> candidate == null)) {
            throw new IllegalArgumentException("Attachment candidates are required and cannot contain null entries");
        }
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    public List<AttachmentCandidate> getCandidates() { return candidates; }
    public boolean isEmpty() { return candidates.isEmpty(); }
}
