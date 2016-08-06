package io.scalecube.cluster.fdetector;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.scalecube.cluster.fdetector.FailureDetectorEvent.suspected;
import static io.scalecube.cluster.fdetector.FailureDetectorEvent.trusted;
import static java.lang.Math.min;

import io.scalecube.transport.ITransport;
import io.scalecube.transport.Message;
import io.scalecube.transport.Transport;
import io.scalecube.transport.Address;
import io.scalecube.transport.MessageHeaders;

import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.observers.Subscribers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FailureDetector implements IFailureDetector {

  private static final Logger LOGGER = LoggerFactory.getLogger(FailureDetector.class);

  // qualifiers
  private static final String PING = "io.scalecube.cluster/fdetector/ping";
  private static final String PING_REQ = "io.scalecube.cluster/fdetector/pingReq";
  private static final String ACK = "io.scalecube.cluster/fdetector/ack";

  // filters
  private static final MessageHeaders.Filter ACK_FILTER = new MessageHeaders.Filter(ACK);
  private static final MessageHeaders.Filter PING_FILTER = new MessageHeaders.Filter(PING);
  private static final MessageHeaders.Filter PING_REQ_FILTER = new MessageHeaders.Filter(PING_REQ);

  private volatile List<Address> members = new ArrayList<>();

  private final ITransport transport;
  private final Address localAddress;
  private final Scheduler scheduler;
  private final FailureDetectorSettings settings;

  @SuppressWarnings("unchecked")
  private Subject<FailureDetectorEvent, FailureDetectorEvent> subject = new SerializedSubject(PublishSubject.create());
  private AtomicInteger periodNbr = new AtomicInteger();
  private Set<Address> suspectedMembers = Sets.newConcurrentHashSet();
  private Address pingMember; // for test purpose only
  private List<Address> randomMembers; // for test purpose only
  private volatile Subscription fdTask;

  /** Listener to PING message and answer with ACK. */
  private Subscriber<Message> onPingSubscriber = Subscribers.create(new Action1<Message>() {
    @Override
    public void call(Message message) {
      LOGGER.trace("Received Ping: {}", message);
      FailureDetectorData data = message.data();
      String correlationId = message.correlationId();
      Message ackMsg = Message.withData(data).qualifier(ACK).correlationId(correlationId).build();
      transport.send(data.getFrom(), ackMsg);
    }
  });

  /** Listener to PING_REQ message and send PING to requested cluster member. */
  private Subscriber<Message> onPingReqSubscriber = Subscribers.create(new Action1<Message>() {
    @Override
    public void call(Message message) {
      LOGGER.trace("Received PingReq: {}", message);
      FailureDetectorData data = message.data();
      Address target = data.getTo();
      Address originalIssuer = data.getFrom();
      String correlationId = message.correlationId();
      FailureDetectorData pingReqData = new FailureDetectorData(localAddress, target, originalIssuer);
      Message pingMsg = Message.withData(pingReqData).qualifier(PING).correlationId(correlationId).build();
      transport.send(target, pingMsg);
    }
  });

  /**
   * Listener to ACK with message containing ORIGINAL_ISSUER then convert message to plain ACK and send it to
   * ORIGINAL_ISSUER.
   */
  private Subscriber<Message> onAckToOriginalAckSubscriber = Subscribers
      .create(new Action1<Message>() {
        @Override
        public void call(Message message) {
          FailureDetectorData data = message.data();
          Address target = data.getOriginalIssuer();
          String correlationId = message.correlationId();
          FailureDetectorData originalAckData = new FailureDetectorData(target, data.getTo());
          Message originalAckMsg =
              Message.withData(originalAckData).qualifier(ACK).correlationId(correlationId).build();
          transport.send(target, originalAckMsg);
        }
      });

  /**
   * Creates new instance of failure detector with given transport and default settings.
   *
   * @param transport transport
   */
  public FailureDetector(Transport transport) {
    this(transport, FailureDetectorSettings.DEFAULT);
  }

  /**
   * Creates new instance of failure detector with given transport and settings.
   *
   * @param transport transport
   * @param settings failure detector settings
   */
  public FailureDetector(Transport transport, FailureDetectorSettings settings) {
    checkArgument(transport != null);
    checkArgument(settings != null);
    this.transport = transport;
    this.settings = settings;
    this.localAddress = transport.localAddress();
    this.scheduler = Schedulers.from(transport.getWorkerGroup());
  }

  @Override
  public void setClusterMembers(Collection<Address> members) {
    Set<Address> set = new HashSet<>(members);
    set.remove(localAddress);
    List<Address> list = new ArrayList<>(set);
    Collections.shuffle(list);
    this.members = list;
    LOGGER.debug("Set cluster members: {}", this.members);
  }

  public ITransport getTransport() {
    return transport;
  }

  public List<Address> getSuspectedMembers() {
    return new ArrayList<>(suspectedMembers);
  }

  /** <b>NOTE:</b> this method is for test purpose only. */
  void setPingMember(Address member) {
    checkNotNull(member);
    checkArgument(member != localAddress);
    this.pingMember = member;
  }

  /** <b>NOTE:</b> this method is for test purpose only. */
  void setRandomMembers(List<Address> randomMembers) {
    checkNotNull(randomMembers);
    this.randomMembers = randomMembers;
  }

  @Override
  public void start() {
    transport.listen().filter(PING_FILTER).filter(targetFilter(localAddress)).subscribe(onPingSubscriber);
    transport.listen().filter(PING_REQ_FILTER).subscribe(onPingReqSubscriber);
    transport.listen().filter(ACK_FILTER).filter(new Func1<Message, Boolean>() {
      @Override
      public Boolean call(Message message) {
        FailureDetectorData data = message.data();
        return data.getOriginalIssuer() != null;
      }
    }).subscribe(onAckToOriginalAckSubscriber);

    fdTask = scheduler.createWorker().schedulePeriodically(new Action0() {
      @Override
      public void call() {
        try {
          doPing(members);
        } catch (Exception e) {
          LOGGER.error("Unhandled exception: {}", e, e);
        }
      }
    }, 0, settings.getPingTime(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void stop() {
    if (fdTask != null) {
      fdTask.unsubscribe();
    }
    subject.onCompleted();
    onPingReqSubscriber.unsubscribe();
    onPingReqSubscriber.unsubscribe();
    onAckToOriginalAckSubscriber.unsubscribe();
  }

  @Override
  public Observable<FailureDetectorEvent> listenStatus() {
    return subject;
  }

  @Override
  public void suspect(Address member) {
    checkNotNull(member);
    suspectedMembers.add(member);
  }

  @Override
  public void trust(Address member) {
    checkNotNull(member);
    suspectedMembers.remove(member);
  }

  private void doPing(final List<Address> members) {
    final Address pingMember = selectPingMember(members);
    if (pingMember == null) {
      return;
    }

    final String period = Integer.toString(periodNbr.incrementAndGet());
    FailureDetectorData pingData = new FailureDetectorData(localAddress, pingMember);
    Message pingMsg = Message.withData(pingData).qualifier(PING).correlationId(period).build();
    LOGGER.trace("Send Ping from {} to {}", localAddress, pingMember);

    transport.listen().filter(ackFilter(period)).filter(new CorrelationFilter(localAddress, pingMember)).take(1)
        .timeout(settings.getPingTimeout(), TimeUnit.MILLISECONDS, scheduler)
        .subscribe(Subscribers.create(new Action1<Message>() {
          @Override
          public void call(Message transportMessage) {
            LOGGER.trace("Received PingAck from {}", pingMember);
            declareTrusted(pingMember);
          }
        }, new Action1<Throwable>() {
          @Override
          public void call(Throwable throwable) {
            LOGGER.trace("No PingAck from {} within {}ms; about to make PingReq now",
                pingMember, settings.getPingTimeout());
            doPingReq(members, pingMember, period);
          }
        }));

    transport.send(pingMember, pingMsg);
  }

  private void doPingReq(List<Address> members, final Address targetMember, String period) {
    final int timeout = settings.getPingTime() - settings.getPingTimeout();
    if (timeout <= 0) {
      LOGGER.trace("No PingReq occurred, because no time left (pingTime={}, pingTimeout={})",
          settings.getPingTime(), settings.getPingTimeout());
      declareSuspected(targetMember);
      return;
    }

    final List<Address> randomMembers =
        selectRandomMembers(members, settings.getMaxMembersToSelect(), targetMember/* exclude */);
    if (randomMembers.isEmpty()) {
      LOGGER.trace("No PingReq occurred, because member selection is empty");
      declareSuspected(targetMember);
      return;
    }

    transport.listen().filter(ackFilter(period)).filter(new CorrelationFilter(localAddress, targetMember)).take(1)
        .timeout(timeout, TimeUnit.MILLISECONDS, scheduler)
        .subscribe(Subscribers.create(new Action1<Message>() {
          @Override
          public void call(Message message) {
            LOGGER.trace("PingReq OK (pinger={}, target={})", message.sender(), targetMember);
            declareTrusted(targetMember);
          }
        }, new Action1<Throwable>() {
          @Override
          public void call(Throwable throwable) {
            LOGGER.trace("No PingAck on PingReq within {}ms (pingers={}, target={})", randomMembers, targetMember,
                timeout);
            declareSuspected(targetMember);
          }
        }));

    FailureDetectorData pingReqData = new FailureDetectorData(localAddress, targetMember);
    Message pingReqMsg = Message.withData(pingReqData).qualifier(PING_REQ).correlationId(period).build();
    for (Address randomMember : randomMembers) {
      LOGGER.trace("Send PingReq from {} to {}", localAddress, randomMember);
      transport.send(randomMember, pingReqMsg);
    }
  }

  /**
   * Adds given member to {@link #suspectedMembers} and emitting state {@code SUSPECTED}.
   */
  private void declareSuspected(Address member) {
    if (suspectedMembers.add(member)) {
      LOGGER.debug("Member {} became SUSPECTED", member);
      subject.onNext(suspected(member));
    }
  }

  /**
   * Removes given member from {@link #suspectedMembers} and emitting state {@code TRUSTED}.
   */
  private void declareTrusted(Address member) {
    if (suspectedMembers.remove(member)) {
      LOGGER.debug("Member {} became TRUSTED", member);
      subject.onNext(trusted(member));
    }
  }

  private Address selectPingMember(List<Address> members) {
    if (pingMember != null) {
      return pingMember;
    }
    return members.isEmpty() ? null : selectRandomMembers(members, 1, null).get(0);
  }

  private List<Address> selectRandomMembers(List<Address> members, int count,
                                            Address memberToExclude) {
    if (randomMembers != null) {
      return randomMembers;
    }

    checkArgument(count > 0, "FailureDetector: k is required!");
    count = min(count, 5);

    List<Address> list = new ArrayList<>(members);
    list.remove(memberToExclude);

    List<Address> target = new ArrayList<>(count);
    for (; !list.isEmpty() && count != 0; count--) {
      Address member = list.get(ThreadLocalRandom.current().nextInt(list.size()));
      target.add(member);
      list.remove(member);
    }
    return target;
  }

  private Func1<Message, Boolean> ackFilter(String correlationId) {
    return new MessageHeaders.Filter(ACK, correlationId);
  }

  private Func1<Message, Boolean> targetFilter(final Address address) {
    return new Func1<Message, Boolean>() {
      @Override
      public Boolean call(Message message) {
        FailureDetectorData data = message.data();
        return data.getTo().equals(address);
      }
    };
  }

  private static class CorrelationFilter implements Func1<Message, Boolean> {
    final Address from;
    final Address target;

    CorrelationFilter(Address from, Address target) {
      this.from = from;
      this.target = target;
    }

    @Override
    public Boolean call(Message message) {
      FailureDetectorData data = message.data();
      return from.equals(data.getFrom()) && target.equals(data.getTo()) && data.getOriginalIssuer() == null;
    }
  }
}
