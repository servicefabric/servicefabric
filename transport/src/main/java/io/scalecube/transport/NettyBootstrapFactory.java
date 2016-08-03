package io.scalecube.transport;

import com.google.common.base.Throwables;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.SystemPropertyUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;

final class NettyBootstrapFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyBootstrapFactory.class);

  private static boolean envSupportEpoll;

  static {
    String name = SystemPropertyUtil.get("os.name").toLowerCase(Locale.UK).trim();
    if (!name.contains("linux")) {
      envSupportEpoll = false;
      LOGGER.warn("Env doesn't support epoll transport");
    } else {
      try {
        Class.forName("io.netty.channel.epoll.Native");
        envSupportEpoll = true;
        LOGGER.info("Use epoll transport");
      } catch (Throwable t) {
        LOGGER
            .warn("Tried to use epoll transport, but it's not supported by host OS (or no corresponding libs included) "
                +
                "using NIO instead, cause: " + Throwables.getRootCause(t));
        envSupportEpoll = false;
      }
    }
  }

  private final TransportSettings settings;
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;

  public NettyBootstrapFactory(TransportSettings settings) {
    this.settings = settings;
    this.bossGroup = createEventLoopGroup(settings.getBossThreads(), new DefaultThreadFactory("sc-boss", true));
    this.workerGroup = createEventLoopGroup(settings.getWorkerThreads(), new DefaultThreadFactory("sc-io", true));
  }

  public ServerBootstrap serverBootstrap() {
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(bossGroup, workerGroup)
        .channel(serverChannelClass())
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childOption(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, settings.getWriteBufferHighWaterMark())
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    return bootstrap;
  }

  public Bootstrap clientBootstrap() {
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(workerGroup)
        .channel(channelClass())
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.SO_REUSEADDR, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getConnectTimeout())
        .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, settings.getWriteBufferHighWaterMark())
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    return bootstrap;
  }

  /**
   * @return {@link EpollEventLoopGroup} or {@link NioEventLoopGroup} object dep on {@link #isEpollSupported()} call.
   */
  private EventLoopGroup createEventLoopGroup(int threadNum, ThreadFactory threadFactory) {
    return isEpollSupported() ? new EpollEventLoopGroup(threadNum, threadFactory) : new NioEventLoopGroup(threadNum,
        threadFactory);
  }

  private Class<? extends ServerSocketChannel> serverChannelClass() {
    return isEpollSupported() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
  }

  private Class<? extends SocketChannel> channelClass() {
    return isEpollSupported() ? EpollSocketChannel.class : NioSocketChannel.class;
  }

  private boolean isEpollSupported() {
    return envSupportEpoll && settings.isEnableEpoll();
  }

  public EventLoopGroup getWorkerGroup() {
    return workerGroup;
  }

  public void shutdown() {
    this.bossGroup.shutdownGracefully();
    this.workerGroup.shutdownGracefully();
  }

}
