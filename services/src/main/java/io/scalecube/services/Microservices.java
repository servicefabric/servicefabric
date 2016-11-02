package io.scalecube.services;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterConfig;
import io.scalecube.cluster.ICluster;
import io.scalecube.services.annotations.AnnotationServiceProcessor;
import io.scalecube.services.annotations.ServiceProcessor;
import io.scalecube.services.routing.RoundRobinServiceRouter;
import io.scalecube.services.routing.Router;
import io.scalecube.transport.Address;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * 
 * The ScaleCube-Services module enables to provision and consuming microservices in a cluster. ScaleCube-Services
 * provides Reactive application development platform for building distributed applications Using microservices and fast
 * data on a message-driven runtime that scales transparently on multi-core, multi-process and/or multi-machines Most
 * microservices frameworks focus on making it easy to build individual microservices. ScaleCube allows developers to
 * run a whole system of microservices from a single command. removing most of the boilerplate code, ScaleCube-Services
 * focuses development on the essence of the service and makes it easy to create explicit and typed protocols that
 * compose. True isolation is achieved through shared-nothing design. This means the services in ScaleCube are
 * autonomous, loosely coupled and mobile (location transparent)—necessary requirements for resilience and elasticity
 * <p>ScaleCube services requires developers only to two simple Annotations declaring a Service but not Opinieated regards
 * how you build the service component itself. the Service component is simply java class that implements the service
 * Interface and ScaleCube take care for the rest of the magic. it derived and influenced by Actor model and reactive
 * and streaming patters but does not force application developers to it.
 * 
 * <p>ScaleCube-Services is not yet-anther RPC system in the sense its is cluster aware to provide:
 * <li>location transparency and discovery of service instances.</li>
 * <li>fault tolerance using gossip and failure detection.</li>
 * <li>share nothing - fully distributed and decentralized architecture.</li>
 * <li>Provides fluent, java 8 lamda apis.</li>
 * <li>embeddable and lightweight.</li>
 * <li>utilizes completable futures but primitives and messages can be used as well completable futures gives the
 * advantage of composing and chaining service calls and service results. or implementing SEDA architecture. tested
 * basic performance roundtrip (request/response) latency</li>
 * <li>low latency</li>
 * <li>supports routing extensible strategies when selecting service endpoints</li>
 * 
 * </p><b>basic usage example:</b>
 * <pre>
 * 
 * <b><font color="green">//Define a serivce interface and implement it.</font></b>
 * {@code
 *    <b>{@literal @}Service</b>
 *    <b><font color="9b0d9b">public interface</font></b> GreetingService {  
 *
 *         <b>{@literal @}ServiceMethod</b>
 *         CompletableFuture<String> asyncGreeting(String string);
 *     }
 *    
 *     <b><font color="9b0d9b">public class</font></b> GreetingServiceImpl implements GreetingService {
 *
 *       {@literal @}Override
 *       <b><font color="9b0d9b">public</font></b> CompletableFuture<String> asyncGreeting(String name) {
 *         <b><font color="9b0d9b">return</font></b> CompletableFuture.completedFuture(" hello to: " + name);
 *       }
 *     }
 *     <b><font color="green">//Build a microservices cluster instance.</font></b>
 *     Microservices microservices = Microservices.builder()
 *       <b><font color="green">//Introduce GreetingServiceImpl pojo as a micro-service.</font></b>
 *         .services(<b><font color="9b0d9b">new</font></b> GreetingServiceImpl())
 *         .build();
 * 
 *     <b><font color="green">//Create microservice proxy to GreetingService.class interface.</font></b>
 *     GreetingService service = microservices.proxy()
 *         .api(GreetingService.class)
 *         .create();
 * 
 *     <b><font color="green">//Invoke the greeting service async.</font></b>
 *     CompletableFuture<String> future = service.asyncGreeting("joe");
 * 
 *     <b><font color="green">//handle completable success or error.</font></b>
 *     future.whenComplete((result, ex) -> {
 *      if (ex == <b><font color="9b0d9b">null</font></b>) {
 *        // print the greeting.
 *         System.<b><font color="9b0d9b">out</font></b>.println(result);
 *       } else {
 *         // print the greeting.
 *         System.<b><font color="9b0d9b">out</font></b>.println(ex);
 *       }
 *     });
 * }
 * </pre>
 */

public class Microservices {

  private static final Logger LOGGER = LoggerFactory.getLogger(Microservices.class);
  private static final ServiceProcessor serviceProcessor = new AnnotationServiceProcessor();

  private final ICluster cluster;

  private final ServiceRegistry serviceRegistry;

  private final ServiceProxyFactory proxyFactory;

  private final ServiceDispatcher localDispatcher;

  private Microservices(ICluster cluster, Optional<Object[]> services, boolean isSeed) {
    this.cluster = cluster;
    this.serviceRegistry = new ServiceRegistry(cluster, services, serviceProcessor, isSeed);
    this.proxyFactory = new ServiceProxyFactory(serviceRegistry, serviceProcessor);
    this.localDispatcher = new ServiceDispatcher(cluster, serviceRegistry);
  }

  public ICluster cluster() {
    return this.cluster;
  }

  public void unregisterService(Object serviceObject) {
    serviceRegistry.unregisterService(serviceObject);
  }

  private <T> T createProxy(Class<T> serviceInterface, Class<? extends Router> router) {
    return proxyFactory.createProxy(serviceInterface, router);
  }

  public Collection<ServiceInstance> services() {
    return serviceRegistry.services();
  }

  public static final class Builder {

    private Integer port = null;
    private Address[] seeds;
    private Optional<Object[]> services = Optional.empty();

    /**
     * microsrrvices instance builder.
     * 
     * @return Microservices instance.
     */
    public Microservices build() {
      ClusterConfig cfg = getClusterConfig();
      return new Microservices(Cluster.joinAwait(cfg), services, seeds == null);
    }

    private ClusterConfig getClusterConfig() {
      Map<String, String> metadata = new HashMap<>();

      if (services.isPresent()) {
        metadata = Microservices.metadata(services.get());
      }

      ClusterConfig cfg;
      if (port != null && seeds != null) {
        cfg = ConfigAssist.create(port, seeds, metadata);
      } else if (seeds != null) {
        cfg = ConfigAssist.create(seeds, metadata);
      } else if (port != null) {
        cfg = ConfigAssist.create(port, metadata);
      } else {
        cfg = ConfigAssist.create(metadata);
      }
      return cfg;
    }

    public Builder port(int port) {
      this.port = port;
      return this;
    }

    public Builder seeds(Address... seeds) {
      this.seeds = seeds;
      return this;
    }

    public Builder services(Object... services) {
      this.services = Optional.of(services);
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }


  public ProxyContext proxy() {
    return new ProxyContext();
  }

  public class ProxyContext {
    private Class<?> api;

    private Class<? extends Router> router = RoundRobinServiceRouter.class;

    public <T> T create() {
      LOGGER.debug("create service api {} router {}", this.api, router);
      return (T) createProxy(this.api, router);
    }

    public Class<?> api() {
      return api;
    }

    public <T> ProxyContext api(Class<T> api) {
      this.api = api;
      return this;
    }

    public Class<? extends Router> router() {
      return router;
    }

    public ProxyContext router(Class<? extends Router> router) {
      this.router = router;
      return this;
    }
  }

  private static Map<String, String> metadata(Object... services) {
    Map<String, String> result = new HashMap<>();

    for (Object service : services) {
      Collection<Class<?>> serviceInterfaces = serviceProcessor.extractServiceInterfaces(service);
      for (Class<?> serviceInterface : serviceInterfaces) {
        ConcurrentMap<String, ServiceDefinition> defs = serviceProcessor.introspectServiceInterface(serviceInterface);
        defs.entrySet().stream().forEach(entry -> {
          result.put(entry.getValue().qualifier(), "service");
        });
      }
    }
    return result;
  }

}
