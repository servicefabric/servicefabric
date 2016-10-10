package io.scalecube.services;

import javax.annotation.Nonnull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import io.scalecube.cluster.ICluster;
import io.scalecube.transport.Message;
import rx.functions.Action1;
import rx.functions.Func1;

public class ServiceDispatcher {

  private final ICluster cluster;
  private final IServiceRegistry registry;

  public ServiceDispatcher(final ICluster cluster, final IServiceRegistry registry) {
    this.cluster = cluster;
    this.registry = registry;

    cluster.listen().filter(new Func1<Message, Boolean>() {
      @Override
      public Boolean call(Message message) {
        return message.header("service") != null;
      }
    }).subscribe(new Action1<Message>() {
      @Override
      public void call(final Message message) {
        final String serviceName = message.header("service");
        final String serviceMethod = message.header("serviceMethod");

        
        ServiceInstance serviceInstance = registry.getLocalInstance(serviceName);

        try {
          Object result = serviceInstance.invoke(serviceMethod, message);

          if (result == null) {
            // Do nothing - fire and forget method
          } else if (result instanceof ListenableFuture) {
            ListenableFuture<Message> futureResult = (ListenableFuture<Message>) result;
            Futures.addCallback(futureResult, new FutureCallback<Object>() {
              @Override
              public void onSuccess(Object result) {
                Message serviceResponseMsg = (Message) result;
                Message responseMsg = Message.builder().data(serviceResponseMsg)
                    .correlationId(message.correlationId()).build();
                cluster.send(message.sender(), responseMsg);
              }

              @Override
              public void onFailure(@Nonnull Throwable t) {
                t.printStackTrace();
              }
            });
          } else {
            // TODO: unsupported result type logic ?
            throw new IllegalArgumentException();
          }

        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
  }

}
