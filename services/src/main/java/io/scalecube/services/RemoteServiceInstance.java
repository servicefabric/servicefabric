package io.scalecube.services;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.SettableFuture;

import io.scalecube.cluster.ICluster;
import io.scalecube.transport.Address;
import io.scalecube.transport.Message;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;

public class RemoteServiceInstance implements ServiceInstance {

  @Override
  public String toString() {
    return "RemoteServiceInstance [cluster=" + cluster + ", serviceReference=" + serviceReference + ", address="
        + address + ", memberId=" + memberId + "]";
  }

  private final ICluster cluster;
  private final ServiceReference serviceReference;
  private final Address address;
  private final String memberId;
  private final Boolean isLocal;

  public RemoteServiceInstance(ICluster cluster, ServiceReference serviceReference) {
    this.cluster = cluster;
    this.serviceReference = serviceReference;
    // Send request
    this.address = cluster.member(serviceReference.memberId()).get().address();
    this.memberId = serviceReference.memberId();
    this.isLocal = false;
  }

  @Override
  public String qualifier() {
    return serviceReference.serviceName();
  }

  public ServiceReference serviceReference() {
    return this.serviceReference;
  }

  @Override
  public Object invoke(Message request) throws Exception {
    // Try to call via messaging
    // Request message
    final String correlationId = "rpc-" + UUID.randomUUID().toString();
    final SettableFuture<Object> responseFuture = SettableFuture.create();
    Message requestMessage = Message.builder()
        .data(request.data())
        .qualifier(serviceReference.serviceName())
        .correlationId(correlationId)
        .build();

    final AtomicReference<Subscription> subscriber = new AtomicReference<Subscription>(null);
    // Listen response
    subscriber.set(cluster.listen().filter(new Func1<Message, Boolean>() {
      @Override
      public Boolean call(Message message) {
        return correlationId.equals(message.correlationId());
      }
    }).subscribe(new Action1<Message>() {
      @Override
      public void call(Message message) {
        if (isFutureClassTypeEqualsMessage(responseFuture)) {
          responseFuture.set(message);
        } else {
          responseFuture.set(message.data());
        }
        subscriber.get().unsubscribe();
      }
    }));

    cluster.send(address, requestMessage);

    return responseFuture;
  }

  @Override
  public String memberId() {
    return this.memberId;
  }

  @Override
  public Boolean isLocal() {
    return this.isLocal;
  }
  
  private boolean isFutureClassTypeEqualsMessage(final SettableFuture<Object> responseFuture) {
    return responseFuture.getClass().getGenericSuperclass().getClass().equals(Message.class);
  }
}
