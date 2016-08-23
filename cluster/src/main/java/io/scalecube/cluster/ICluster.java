package io.scalecube.cluster;

import io.scalecube.cluster.membership.MembershipRecord;
import io.scalecube.transport.Address;
import io.scalecube.transport.Message;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import rx.Observable;

import java.util.List;

/**
 * Facade cluster interface which provides API to interact with cluster members.
 * 
 * @author Anton Kharenko
 */
public interface ICluster {

  /**
   * Returns local listen {@link Address} of this cluster instance.
   */
  Address address();

  void send(MembershipRecord member, Message message);

  void send(Address address, Message message);

  void send(MembershipRecord member, Message message, SettableFuture<Void> promise);

  void send(Address address, Message message, SettableFuture<Void> promise);

  Observable<Message> listen();

  /**
   * Spreads given message between cluster members using gossiping protocol.
   */
  void spreadGossip(Message message);

  /**
   * Listens for gossips from other cluster members.
   */
  Observable<Message> listenGossips();

  /**
   * Returns list of all members of the joined cluster. This will include all cluster members including local member.
   */
  List<MembershipRecord> members();

  /**
   * Returns cluster member which corresponds to this cluster instance.
   */
  MembershipRecord localMember();

  /**
   * Returns cluster member with given id or null if no member with such id exists at joined cluster.
   */
  MembershipRecord member(String id);

  /**
   * Returns cluster member by given address or null if no member with such address exists at joined cluster.
   */
  MembershipRecord member(Address address);

  /**
   * Returns list of all cluster members of the joined cluster excluding local member.
   */
  List<MembershipRecord> otherMembers();

  /**
   * Member notifies other members of the cluster about leaving and gracefully shutdown and free occupied resources.
   *
   * @return Listenable future which is completed once graceful shutdown is finished.
   */
  ListenableFuture<Void> shutdown();

}
