package io.scalecube.cluster.gossip;

public final class GossipConfig {

  public static final int DEFAULT_GOSSIP_INTERVAL = 200;
  public static final int DEFAULT_MAX_GOSSIP_SENT = 3;
  public static final int DEFAULT_MAX_MEMBERS_TO_SELECT = 3;

  private final int maxGossipSent;
  private final int gossipInterval;
  private final int maxMembersToSelect;

  private GossipConfig(Builder builder) {
    this.maxGossipSent = builder.maxGossipSent;
    this.gossipInterval = builder.gossipInterval;
    this.maxMembersToSelect = builder.maxMembersToSelect;
  }

  public static GossipConfig defaultConfig() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public int getMaxGossipSent() {
    return maxGossipSent;
  }

  public int getGossipInterval() {
    return gossipInterval;
  }

  public int getMaxMembersToSelect() {
    return maxMembersToSelect;
  }

  @Override
  public String toString() {
    return "GossipConfig{maxGossipSent=" + maxGossipSent
        + ", gossipInterval=" + gossipInterval
        + ", maxMembersToSelect=" + maxMembersToSelect
        + '}';
  }

  public static final class Builder {

    private int maxGossipSent = DEFAULT_MAX_GOSSIP_SENT;
    private int gossipInterval = DEFAULT_GOSSIP_INTERVAL;
    private int maxMembersToSelect = DEFAULT_MAX_MEMBERS_TO_SELECT;

    private Builder() {}

    public Builder maxGossipSent(int maxGossipSent) {
      this.maxGossipSent = maxGossipSent;
      return this;
    }

    public Builder gossipInterval(int gossipInterval) {
      this.gossipInterval = gossipInterval;
      return this;
    }

    public Builder maxMembersToSelect(int maxMembersToSelect) {
      this.maxMembersToSelect = maxMembersToSelect;
      return this;
    }

    public GossipConfig build() {
      return new GossipConfig(this);
    }
  }
}
