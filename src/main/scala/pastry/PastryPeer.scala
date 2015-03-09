package pastry

import spire.math._,
       spire.random._,
       spire.syntax.literals._,
       spire.syntax.literals.radix._
       
import akka.actor.Actor,
       akka.actor.ActorRef,
       akka.actor.DeadLetter,
       akka.actor.ActorSystem,
       akka.actor.ActorSelection,
       akka.actor.Props

import scala.collection.mutable.HashMap,
       scala.collection.mutable.HashSet,
       scala.collection.mutable.ListBuffer

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger



class PastryPeer extends Actor with Config {
  
  val logger = Logger(LoggerFactory.getLogger("pastry"))
  
  var myID : BigInt = 0
  var myIDStr = "NONE"
  
  var state = State.Offline
  
  var rt : RoutingTable = null
  var ls : LeafSet = null
  
  val store = new HashMap[BigInt, BigInt]
  val barrier, ackBarrier, originalAckBarrier = new HashSet[BigInt]  
  
  
  def assertions(msg: Any) = {
    assert(myID != 0, myIDStr + " " + msg)
  }
  
  def getRef(peer: BigInt) : ActorSelection = {
    return getRef(toBase(peer))
  }
  
  def getRef(peerBase: String) : ActorSelection = {
    return context.actorSelection("../" + peerBase)
  }
  
  
  def handleJoinThis(msg : JoinRequest) : Unit = {
    assertions(msg)
    
    val newPeer = msg.newPeer
    
    logger.trace(myIDStr + ": " + 
        "I am responsible for key " + toBase(newPeer))
    
    getRef(newPeer) ! JoinReply(msg.visitedPeers :+ myID)
  }
  
  
  def handleJoinThat(msg : JoinRequest, nextPeer: BigInt) : Unit = {
    assertions(msg)
    
    val newPeer = msg.newPeer
    
    logger.trace(myIDStr + ": " + toBase(nextPeer) + 
        " is responsible for key " + toBase(newPeer))
    getRef(nextPeer) ! JoinRequest(msg.newPeer, msg.visitedPeers :+ myID)
  }
  
  
  def handleJoinReply(sender : ActorRef, msg : JoinReply) = {
    assertions(msg)
    
    logger.trace(myIDStr + ": " + "Visited nodes: " + msg.visitedPeers)
    
    for(peer <- msg.visitedPeers) {
      assert(peer != myID)
      
      barrier += peer
      getRef(peer) ! StateRequest(sender = myID, receiver = peer) 
    }
    
  }
  
  
  def getNext(key: BigInt) : Option[BigInt] = {
  
    val nextPeer : BigInt = 
      ls.closest(key).getOrElse(
      rt.computeNext(key).getOrElse(
      closest(rt.values ++ ls.values + myID, key).getOrElse(
      internalErrorF
    )))  
    
    nextPeer match {
      case nextPeer : BigInt if (nextPeer == myID || 
          (myID - key).abs < (nextPeer - key).abs) =>
        return None
      case nextPeer : BigInt =>
        return Some(nextPeer)
      case _ => internalErrorF
    }
  }
  
  
  def handleJoin(sender : ActorRef, msg : JoinRequest) = {
    assertions(msg)
    
    val newPeer = msg.newPeer
    getNext(newPeer) match {
      case None => handleJoinThis(msg)
      case Some(nextPeer) => handleJoinThat(msg, nextPeer)
    }
  }
  
  
  def handleState(sender : ActorRef, msg : StateRequest) = {
    assertions(msg)
    
    val reply = StateUpdate(myID, msg.sender, rt.clone(), ls.clone())
    getRef(msg.sender) ! reply
  }
  
  
  def handleStateReply(senderRef : ActorRef, msg : StateUpdate) = {
    assertions(msg)
    
    val sender = msg.sender
    
    assert(msg.sender != myID)
    
     /**
     *  Technically, we only need to steal the leaf set from the
     *  closest node. Adding any additional leaf sets does not do
     *  any harm.
     */
    rt.insertInt(sender)
    ls.insert(sender)
    
    rt.steal(msg.rt)
    ls.steal(msg.ls)
    
    if (state == State.Joining) {
      assert(barrier contains sender) 
      barrier -= sender
      if (barrier.isEmpty) {
        handleCompletion()
      }
    }
  }

  
  /**
   * Finally, the new node transmits a copy of its resulting 
   * state to each of the nodes found in its neighborhood set, 
   * leaf set, and routing table.
   */
  def handleCompletion(): Unit = {
    
    state = State.PreOnline
    
    for((peerStr, peerInt) <- rt) {
      ackBarrier += peerInt
      originalAckBarrier += peerInt
      logger.trace(myIDStr + ": " + "Adding " + peerStr + " " + peerInt + " to " + ackBarrier)
    }
    
    
    for(peer <- ls) {
      ackBarrier += peer
      originalAckBarrier += peer
      
      logger.trace(myIDStr + ": " + "Adding " + peer + " to " + ackBarrier)
    }
    
    
    for (peer <- originalAckBarrier) {
      getRef(peer) ! StateUpdate(myID, peer, rt.clone(), ls.clone())
    }
   }

    

  def handleBootstrap(sender : ActorRef, msg: Bootstrap) = {
    
    myID = msg.id
    myIDStr = toBase(myID)
    
    rt = new RoutingTable(myID)
    ls = new LeafSet(myID)
    
    state = State.Joining
    msg.booststrapPeer match {
      case value if value == myID =>
        logger.trace(myIDStr + ": " + "Starting a new swarm." )
        state = State.Online
      case _ =>      
        logger.trace(myIDStr + ": " + "Sending " + msg + " to " + toBase(msg.booststrapPeer) )
        getRef(msg.booststrapPeer) ! JoinRequest(myID, scala.collection.immutable.Queue())
      }
  }
  
  
  def handleWriteRequest(sender : ActorRef, msg : WriteRequest) = {
    assertions(msg)
    
    getNext(msg.key) match {
      case None =>
        logger.trace(myIDStr + ": " + "Wrote " + msg.key + " <- " + msg.value)
        store(msg.key) = msg.value
      case Some(nextPeer) => getRef(nextPeer) ! msg
    }
  }
  
  
  def handleReadRequest(sender : ActorRef, msg : ReadRequest) = {
    assertions(msg)
    
    getNext(msg.key) match {
      case None => 
        val value = store(msg.key)
        logger.trace(myIDStr + ": " + "Read " + msg.key + " == " + value)
      case Some(nextPeer) => getRef(nextPeer) ! msg
    }
  }
  
  def handleAck(senderRef : ActorRef, msg : Ack) = {
    assertions(msg)
    
    if (state == State.PreOnline) {
      val sender = msg.sender
      
      assert(originalAckBarrier contains sender)
      
      ackBarrier -= sender
      if (ackBarrier.isEmpty) {
        state = State.Online
        ackBarrier.clear()
        originalAckBarrier.clear()
        logger.trace(myIDStr + ": " + "Going online...")
      }
    }
  }
    
  

  def receive = {
    
    // External API:
    case msg: Bootstrap => handleBootstrap(sender, msg)
    
    case Write(key, value) => handleWriteRequest(sender, WriteRequest(myID, key, value))
    case Read(key) => handleReadRequest(sender, ReadRequest(myID, key))
        
    // Internal API:
    case msg : WriteRequest=> handleWriteRequest(sender, msg)
    case msg : ReadRequest=> handleReadRequest(sender, msg)
    
    case msg : JoinRequest => handleJoin(sender, msg)
    case msg : JoinReply => handleJoinReply(sender, msg)
    
    case msg : StateRequest => handleState(sender, msg)
    case msg : StateUpdate => handleStateReply(sender, msg)
    
    case msg : Ack => handleAck(sender, msg)
    
    case other => throw new Exception("unknown message " + other)
  }

  
}