package io.scalecube.services;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.scalecube.cluster.ICluster;
import io.scalecube.transport.Address;
import io.scalecube.transport.Message;

public class RemoteServiceInstance implements ServiceInstance {


  private final ICluster cluster;
  private final Address address;
  private final String memberId;
  private final Boolean isLocal;
  private final String[] tags;
  private final String qualifier;

  public RemoteServiceInstance(ICluster cluster, ServiceReference serviceReference) {
    this.qualifier = serviceReference.serviceName();
    this.cluster = cluster;
    this.address = serviceReference.address();
    this.memberId = serviceReference.memberId();
    this.tags = serviceReference.tags();
    this.isLocal = false;
  }

  @Override
  public String qualifier() {
    return qualifier;
  }

  private CompletableFuture<Message> futureInvokeWithMessage(Message request) throws Exception{
    final CompletableFuture<Message> messageFuture = new CompletableFuture<>();
    
    final String correlationId = "rpc-" + UUID.randomUUID().toString();
   
    Message requestMessage = composeRequest(request,correlationId);
    // Listen response
    this.cluster.listen().filter(message -> {
      return correlationId.equals(message.correlationId());
    }).subscribe(message -> {
      if (message.header("exception") == null) {
        messageFuture.complete(message);
      } else {
        messageFuture.completeExceptionally(message.data());
      }
    });
    sendRemote(requestMessage,messageFuture);
    return messageFuture;
  }

  private <T> CompletableFuture<T> futureInvokeWithUserObject(final Message request) throws Exception{
    final CompletableFuture<T> messageFuture = new CompletableFuture<>();
    
    final String correlationId = "rpc-" + UUID.randomUUID().toString();
    
    Message requestMessage = composeRequest(request,correlationId);
    // Listen response
    this.cluster.listen().filter(message -> {
      return correlationId.equals(message.correlationId());
    }).subscribe(message -> {
      if (message.header("exception") == null) {
        messageFuture.complete(message.data());
      } else {
        messageFuture.completeExceptionally(message.data());
      }
    });
    
    sendRemote(requestMessage,messageFuture);
    return messageFuture;
  }

  private void sendRemote(Message requestMessage, CompletableFuture<?> future) {
    final CompletableFuture<Void> messageFuture = new CompletableFuture<>();
    this.cluster.send(address, requestMessage,messageFuture);
    messageFuture.whenComplete((success,error)->{
      if(error!=null){
        future.completeExceptionally(error);
      }
    });
  }
  
  @Override
  public <T> Object invoke(Message request, Class<T> returnType) throws Exception {
    
    // Try to call via messaging
    // Request message
    if(isFutureResponse(returnType)){
      if(returnType.isAssignableFrom(Message.class)){
        return futureInvokeWithMessage(request);
      }else{
        return futureInvokeWithUserObject(request);
      }
    }else{
      CompletableFuture<T> future = futureInvokeWithUserObject(request);
      Object o= future.get();
      return o;
    }
  }

  private Message composeRequest(Message request,final String correlationId) {
    
    Message requestMessage = Message.builder()
        .data(request.data())
        .header("service", qualifier())
        .qualifier(qualifier())
        .correlationId(correlationId)
        .build();
    
    return requestMessage;
  } 
  
  private boolean isFutureResponse(Class<?> returnType) {
    return returnType.isAssignableFrom(CompletableFuture.class);
  }

  @Override
  public String memberId() {
    return this.memberId;
  }

  public Address address() {
    return address;
  }
  
  @Override
  public String[] tags() {
    return tags;
  }
  
  @Override
  public Boolean isLocal() {
    return this.isLocal;
  }

  public boolean isReachable() {
    return cluster.member(this.memberId).isPresent();
  }
  
  @Override
  public String toString() {
    return "RemoteServiceInstance [address=" + address + ", memberId=" + memberId + ", isLocal=" + isLocal + ", tags=" + Arrays.toString(tags) + "]";
  }


}
