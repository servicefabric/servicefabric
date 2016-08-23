package io.scalecube.cluster;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;

import io.scalecube.cluster.fdetector.FailureDetector;
import io.scalecube.cluster.gossip.GossipProtocol;
import io.scalecube.cluster.membership.MembershipConfig;
import io.scalecube.cluster.membership.MembershipProtocol;
import io.scalecube.cluster.membership.MembershipRecord;
import io.scalecube.transport.Address;
import io.scalecube.transport.Message;
import io.scalecube.transport.Transport;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Main ICluster implementation.
 * 
 * @author Anton Kharenko
 */
public final class Cluster implements ICluster {

  private static final Logger LOGGER = LoggerFactory.getLogger(Cluster.class);

  private final ClusterConfig config;
  private final String memberId;

  // Cluster components
  private Transport transport;
  private FailureDetector failureDetector;
  private GossipProtocol gossip;
  private MembershipProtocol membership;

  private Cluster(ClusterConfig config) {
    checkNotNull(config);
    this.config = config;
    this.memberId = UUID.randomUUID().toString();
    LOGGER.info("Cluster instance '{}' created with configuration: {}", memberId, config);
  }

  public static ICluster joinAwait() {
    try {
      return join().get();
    } catch (Exception e) {
      throw Throwables.propagate(Throwables.getRootCause(e));
    }
  }

  public static ICluster joinAwait(Address... seedMembers) {
    try {
      return join(seedMembers).get();
    } catch (Exception e) {
      throw Throwables.propagate(Throwables.getRootCause(e));
    }
  }

  public static ICluster joinAwait(Map<String, String> metadata, Address... seedMembers) {
    try {
      return join(metadata, seedMembers).get();
    } catch (Exception e) {
      throw Throwables.propagate(Throwables.getRootCause(e));
    }
  }

  /**
   * Init cluster instance with the given configuration and join cluster synchronously.
   */
  public static ICluster joinAwait(ClusterConfig config) {
    try {
      return join(config).get();
    } catch (Exception e) {
      throw Throwables.propagate(Throwables.getRootCause(e));
    }
  }

  public static ListenableFuture<ICluster> join() {
    return join(ClusterConfig.defaultConfig());
  }

  public static ListenableFuture<ICluster> join(Address... seedMembers) {
    ClusterConfig config = ClusterConfig.builder()
        .membershipConfig(MembershipConfig.builder().seedMembers(Arrays.asList(seedMembers)).build())
        .build();
    return join(config);
  }

  public static ListenableFuture<ICluster> join(Map<String, String> metadata, Address... seedMembers) {
    ClusterConfig config = ClusterConfig.builder()
        .membershipConfig(
            MembershipConfig.builder()
                .seedMembers(Arrays.asList(seedMembers))
                .metadata(metadata)
                .build())
        .build();
    return join(config);
  }

  public static ListenableFuture<ICluster> join(final ClusterConfig config) {
    return new Cluster(config).join0();
  }

  private ListenableFuture<ICluster> join0() {
    LOGGER.info("Cluster instance '{}' joining seed members: {}",
        memberId, config.getMembershipConfig().getSeedMembers());
    ListenableFuture<Transport> transportFuture = Transport.bind(config.getTransportConfig());
    ListenableFuture<Void> clusterFuture = transformAsync(transportFuture, new AsyncFunction<Transport, Void>() {
      @Override
      public ListenableFuture<Void> apply(@Nullable Transport boundTransport) throws Exception {
        // Init transport component
        transport = boundTransport;

        // Init gossip protocol component
        gossip = new GossipProtocol(memberId, transport, config.getGossipConfig());

        // Init failure detector component
        failureDetector = new FailureDetector(transport, config.getFailureDetectorConfig());

        // Init cluster membership component
        membership = new MembershipProtocol(
            memberId, transport, config.getMembershipConfig(), failureDetector, gossip);

        // Start components
        failureDetector.start();
        gossip.start();
        return membership.start();
      }
    });
    return transform(clusterFuture, new Function<Void, ICluster>() {
      @Override
      public ICluster apply(@Nullable Void param) {
        LOGGER.info("Cluster instance '{}' joined cluster of members: {}", memberId, membership.members());
        return Cluster.this;
      }
    });
  }

  @Override
  public Address address() {
    return transport.address();
  }

  @Override
  public void send(MembershipRecord member, Message message) {
    transport.send(member.address(), message);
  }

  @Override
  public void send(Address address, Message message) {
    transport.send(address, message);
  }

  @Override
  public void send(MembershipRecord member, Message message, SettableFuture<Void> promise) {
    transport.send(member.address(), message, promise);
  }

  @Override
  public void send(Address address, Message message, SettableFuture<Void> promise) {
    transport.send(address, message, promise);
  }

  @Override
  public Observable<Message> listen() {
    // TODO: Filter system messages (gossips, syncs, pings etc.) so only application level messages will be exposed
    return transport.listen();
  }

  @Override
  public void spreadGossip(Message message) {
    gossip.spread(message);
  }

  @Override
  public Observable<Message> listenGossips() {
    return gossip.listen();
  }

  @Override
  public List<MembershipRecord> members() {
    return membership.members();
  }

  @Override
  public MembershipRecord localMember() {
    return membership.localMember();
  }

  @Override
  public MembershipRecord member(String id) {
    return membership.member(id);
  }

  @Override
  public MembershipRecord member(Address address) {
    return membership.member(address);
  }

  @Override
  public List<MembershipRecord> otherMembers() {
    return membership.otherMembers();
  }

  @Override
  public ListenableFuture<Void> shutdown() {
    LOGGER.info("Cluster instance '{}' leaving cluster", memberId);

    // Notify cluster members about graceful shutdown of current member
    membership.leave();

    // Wait for some time until 'leave' gossip start to spread through the cluster before stopping cluster components
    final SettableFuture<Void> transportStoppedFuture = SettableFuture.create();
    final ScheduledExecutorService stopExecutor = Executors.newSingleThreadScheduledExecutor();
    long delay = 3 * config.getGossipConfig().getGossipTime(); // wait for 3 gossip periods before stopping
    stopExecutor.schedule(new Runnable() {
      @Override
      public void run() {
        membership.stop();
        gossip.stop();
        failureDetector.stop();
        transport.stop(transportStoppedFuture);
      }
    }, delay, TimeUnit.MILLISECONDS);

    // Update cluster state to terminal state
    return transform(transportStoppedFuture, new Function<Void, Void>() {
      @Nullable
      @Override
      public Void apply(Void input) {
        stopExecutor.shutdown();
        LOGGER.info("Cluster instance '{}' stopped", memberId);
        return input;
      }
    });
  }

}
