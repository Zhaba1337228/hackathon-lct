package ru.lct.heatnetwork.network;

/** A directed pipe segment oriented from a consumer-side node toward its source. */
public final class NetworkEdge {
    private final String id;
    private final String fromNode;
    private final String toNode;
    private final double lengthM;
    private final Integer diameterMm;

    public NetworkEdge(String id, String fromNode, String toNode, double lengthM, Integer diameterMm) {
        if (id == null || id.trim().isEmpty() || fromNode == null || fromNode.trim().isEmpty()
                || toNode == null || toNode.trim().isEmpty() || fromNode.equals(toNode)
                || !Double.isFinite(lengthM) || lengthM <= 0 || (diameterMm != null && diameterMm <= 0)) {
            throw new IllegalArgumentException("Invalid network edge");
        }
        this.id = id;
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.lengthM = lengthM;
        this.diameterMm = diameterMm;
    }

    public String getId() { return id; }
    public String getFromNode() { return fromNode; }
    public String getToNode() { return toNode; }
    public double getLengthM() { return lengthM; }
    public Integer getDiameterMm() { return diameterMm; }
}
