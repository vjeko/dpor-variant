package akka.dispatch.verification

import akka.actor.ActorCell,
       akka.actor.ActorSystem,
       akka.actor.ActorRef,
       akka.actor.LocalActorRef,
       akka.actor.ActorRefWithCell,
       akka.actor.Actor,
       akka.actor.PoisonPill,
       akka.actor.Props

import akka.dispatch.Envelope,
       akka.dispatch.MessageQueue,
       akka.dispatch.MessageDispatcher

import scala.collection.mutable.Queue,
       scala.collection.mutable.HashMap,
       scala.collection.mutable.HashSet,
       scala.collection.mutable.ArrayBuffer,
       scala.collection.mutable.ArraySeq,
       scala.collection.mutable.Stack

import Function.tupled

import scalax.collection.mutable.Graph,
       scalax.collection.GraphEdge.DiEdge,
       scalax.collection.edge.LDiEdge
       
import com.typesafe.scalalogging.LazyLogging,
       org.slf4j.LoggerFactory,
       ch.qos.logback.classic.Level,
       ch.qos.logback.classic.Logger


object ActorRegistry {
  val actors = new HashMap[String, Any]
}


trait ActorObserver {
  assert(this.isInstanceOf[Actor], "not an actor")
  val actor = this.asInstanceOf[Actor]
  ActorRegistry.actors(actor.self.path.name) = this
 }
       

// DPOR scheduler.
class DPORwFailures extends Scheduler with LazyLogging {
  
  final val SCHEDULER = "__SCHEDULER__"
  final val PRIORITY = "__PRIORITY__"
  
  var instrumenter = Instrumenter
  var externalEventList : Seq[ExternalEvent] = Vector()
  var externalEventIdx = 0
  
  val quiescentPeriod = new HashMap[Unique, Int]
  var currentQuiescentPeriod = 0
  var awaitingQuiescence = false
  var nextQuiescentPeriod = 0
  var quiescentMarker:Unique = null
  
  var currentTime = 0
  var interleavingCounter = 0

  
  val depGraph = Graph[Unique, DiEdge]()
  var parentEvent = getRootRootEvent()
  
  val pendingEvents = new HashMap[String, Queue[(Unique, ActorCell, Envelope)]]  
  val actorNames = new HashSet[String]

  val backTrack = new HashMap[Int, HashMap[(Unique, Unique), List[Unique]] ]
  val tracker = new ExploredTacker
  
  var invariantChecker : InvariantChecker = new NullInvariantChecker()
  var invariant : Queue[Unique] = Queue()
  
  var post: (Queue[Unique]) => Unit = nullFunPost
  var done: (Scheduler) => Unit = nullFunDone

  def nullFunPost(trace: Queue[Unique]) : Unit = {}
  def nullFunDone(s :Scheduler) : Unit = {}
  
  
  private[this] def awaitQuiescenceUpdate(nextEvent: Unique) = { 
    logger.trace(Console.BLUE + "Beginning to wait for quiescence " + Console.RESET)
    nextEvent match {
      case Unique(q: WaitQuiescence, id) =>
        awaitingQuiescence = true
        nextQuiescentPeriod = id
        quiescentMarker = nextEvent
      case _ =>
        throw new Exception("Bad args")
    }
  }

  private[this] def addGraphNode (event: Unique) = {
    depGraph.add(event)
    quiescentPeriod(event) = currentQuiescentPeriod
  }
  
  def getRootRootEvent() : Unique = {
    var root = Unique(MsgEvent("null", "null", null), 0)
    addGraphNode(root)
    return root
  }
  
    
  def getRootEvent(child: depGraph.NodeT) : depGraph.NodeT = {
    child.outNeighbors match {
      case parents if parents.isEmpty => return child
      case parents if parents.size == 1 => return getRootEvent(parents.head)
      case error => throw new Exception("not a tree " + error)
    }
  }
  
  
  def decomposePartitionEvent(event: NetworkPartition) : Queue[(String, NodesUnreachable)] = {
    val queue = new Queue[(String, NodesUnreachable)]
    queue ++= event.first.map { x => (x, NodesUnreachable(event.second)) }
    queue ++= event.second.map { x => (x, NodesUnreachable(event.first)) }
    return queue
  }
  
  
  def isSystemCommunication(sender: ActorRef, receiver: ActorRef) : Boolean =
    throw new Exception("not implemented")

  
  override def isSystemCommunication(sender: ActorRef, receiver: ActorRef, msg: Any): Boolean = 
  (receiver, sender) match {
    case (null, _) => return true
    case (_, null) => isSystemMessage("deadletters", receiver.path.name, msg)
    case _ => isSystemMessage(sender.path.name, receiver.path.name, msg)
  }

  
  // Is this message a system message
  def isValidActor(sender: String, receiver: String): Boolean = 
  ((actorNames contains sender) || (actorNames contains receiver)) match {
    case true => return true
    case _ => return false
  }
  
  
  def isSystemMessage(sender: String, receiver: String) : Boolean =
    throw new Exception("not implemented")
  
  
  // Is this message a system message
  override def isSystemMessage(sender: String, receiver: String, msg: Any): Boolean = {
    return !isValidActor(sender, receiver) || receiver == "deadLetters"
  }
  
  
  // Notification that the system has been reset
  def start_trace() : Unit = {
    
    invariantChecker.init(ActorRegistry.actors)
    invariantChecker.newRun()
    
    actorNames.clear
    ActorRegistry.actors.clear()
    externalEventIdx = 0
    
    tracker.addEvent( getRootRootEvent() )
    runExternal()
  }
  


  
  
  // Figure out what is the next message to schedule.
  def schedule_new_message() : Option[(ActorCell, Envelope)] = {
    
    def checkInvariant[T1](result : Option[T1]) = result match {
    
      case Some((Unique(_, nID), _, _)) => invariant.headOption match {
          case Some(Unique(_, invID)) if (nID == invID) =>
            logger.trace( Console.RED + "Managed to replay the intended message: "+ nID + Console.RESET )
            invariant.dequeue()
          case _ =>
        }
        
      case _ =>
    }
    
    
    // Filter messages belonging to a particular actor.
    def equivalentTo(u1: Unique, other: (Unique, ActorCell, Envelope)) : 
    Boolean = (u1, other._1) match {
      
      case (Unique(MsgEvent(_, rcv1, _), id1),
            Unique(MsgEvent(_, rcv2, _), id2) ) =>
        // If the ID is zero, this means it's a system message.
        // In that case compare only the receivers.
        if (id1 == 0) rcv1 == rcv2
        else rcv1 == rcv2 && id1 == id2
        
      case (Unique(_, id1), Unique(_, id2) ) => id1 == id2  
      case _ => false
    }
    
    
    def getDivergentPending(): Option[(Unique, ActorCell, Envelope)] = {
      Util.dequeueOne(pendingEvents) match {
        case Some(e) =>
          logger.trace( Console.RED + 
              "Unable to flip the events. Rolling back to the previous trace." 
              + Console.RESET )
          tracker.rollback()
          return None
        case None => return None
      }
    }
    
    
    // Get from the current set of pending events.
    def getConvergentPending(): Option[(Unique, ActorCell, Envelope)] = {

      Util.dequeueOneIf(pendingEvents, tracker.filter.convergent) match {
        case Some( next @ (u @ Unique(MsgEvent(snd, rcv, msg), id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing pending: " 
              + "(" + snd + " -> " + rcv + ") " +  + id + Console.RESET )
          Some(next)
          
        case Some(par @ (Unique(NetworkPartition(part1, part2), id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing the high level partition event " +
              id + Console.RESET)
          Some(par)

        case Some(qui @ (Unique(q: WaitQuiescence, id), _, _)) =>
          logger.trace( Console.GREEN + "Now playing the high level quiescence event " +
              id + Console.RESET)
          Some(qui)

        case None => 
            //logger.trace( Console.RED + 
            //    "Unable to reverse the events. Rolling back to the previous trace." 
            //    + Console.RESET )
            //tracker.rollback()
            //None
          getDivergentPending()
        case _ => throw new Exception("internal error")
      }
    }
    
    
    def getMatchingMessage() : Option[(Unique, ActorCell, Envelope)] = {
      val candidate = tracker.getNextTraceMessage()
      
      candidate match {
        case Some(Unique(_, id)) if 
          tracker.filter.alreadyExploredImpl(id) =>
            //assert(false)
            return None
        case _ =>
      }
      
      candidate match {
        // The trace says there is a message event to run.
        case Some(u @ Unique(MsgEvent(snd, rcv, msg), id)) =>

          // Look at the pending events to see if such message event exists.
          pendingEvents.get(rcv) match {
            case Some(queue) =>
              return queue.dequeueFirst(equivalentTo(u, _))
            case None =>  return None
          }
          
        case Some(u @ Unique(NetworkPartition(_, _), id)) =>
          // Look at the pending events to see if such message event exists. 
          pendingEvents.get(SCHEDULER) match {
            case Some(queue) => 
              return queue.dequeueFirst(equivalentTo(u, _))
            case None =>  return None
          }

        case Some(u @ Unique(WaitQuiescence(), _)) => // Look at the pending events to see if such message event exists.
          pendingEvents.get(SCHEDULER) match {
            case Some(queue) => 
              return queue.dequeueFirst(equivalentTo(u, _))
            case None =>  return None
          }
          
        // The trace says there is nothing to run so we have either exhausted our
        // trace or are running for the first time. Use any enabled transitions.
        case None => return None
        case _ => throw new Exception("internal error")
      }
    }

    
    // Are there any prioritized events that need to be dispatched.
    pendingEvents.get(PRIORITY) match {
      case Some(queue) if !queue.isEmpty => {
        val (_, cell, env) = queue.dequeue()
        return Some((cell, env))
      }
      case _ => None
    }
    
    val result = awaitingQuiescence match {
      case false =>
        getMatchingMessage() match {
          
          // There is a pending event that matches a message in our trace.
          // We call this a convergent state.
          case Some(tup @ (Unique(MsgEvent(snd, rcv, msg), id), cell, env)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Message: " +
                "(" + snd + " -> " + rcv + ") " +  + id + Console.RESET )
            tracker.dequeueNextTraceMessage()
            Some(tup)
            
          case Some(tup @ (Unique(NetworkPartition(part1, part2), id), _, _)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Partition: (" 
                + part1 + " <-> " + part2 + ")" + Console.RESET )
            tracker.dequeueNextTraceMessage()
            Some(tup)

          case Some(tup @ (Unique(WaitQuiescence(), id), _, _)) =>
            logger.trace( Console.GREEN + "Replaying the exact message: Quiescence: (" 
                + id +  ")" + Console.RESET )
            tracker.dequeueNextTraceMessage()
            Some(tup)
            
          // We call this a divergent state.
          case None => getConvergentPending()
          
          // Something went wrong.
          case _ => throw new Exception("not a message")
        }
        
      case true => 
        /** 
         *  Don't call getMatchingMessage when waiting quiescence. Except when divergent or running the first 
         *  time through, there should be no pending messages, signifying quiescence. Get pending event takes
         *  care of the first run. We could explicitly detect divergence here, but we haven't been so far.
         */
        getConvergentPending()
    }
    
    
    
    checkInvariant(result)
    
    result match {
      
      case Some((nextEvent @ Unique(MsgEvent(snd, rcv, msg), nID), cell, env)) =>
      
        tracker.addEvent( nextEvent )
        (depGraph get nextEvent)
        
        parentEvent = nextEvent

        return Some((cell, env))
        
        
      case Some((nextEvent @ Unique(par@ NetworkPartition(_, _), nID), _, _)) =>

        // A NetworkPartition event is translated into multiple
        // NodesUnreachable messages which are atomically and
        // and invisibly consumed by all relevant parties.
        // Important: no messages are allowed to be dispatched
        // as the result of NodesUnreachable being received.
        decomposePartitionEvent(par) map tupled(
          (rcv, msg) => instrumenter().actorMappings(rcv) ! msg)
          
        instrumenter().tellEnqueue.await()
        
        tracker.addEvent( nextEvent )
        return schedule_new_message()

      case Some((nextEvent @ Unique(q @ WaitQuiescence(), nID), _, _)) =>
        awaitQuiescenceUpdate(nextEvent)
        parentEvent = nextEvent
        //scheduledEvent = nextEvent
        return schedule_new_message()
        
      case _ => return None
    }
    
  }
  
  
  def next_event() : Event = {
    throw new Exception("not implemented next_event()")
  }

  
  // Record that an event was consumed
  def event_consumed(event: Event) = {
  }
  
  
  def event_consumed(cell: ActorCell, envelope: Envelope) = {
  }
  
  
  def event_produced(event: Event) = event match {
      case event : SpawnEvent => actorNames += event.name
      case msg : MsgEvent => 
  }
  
  def event_produced(cell: ActorCell, envelope: Envelope) = {

    invariantChecker.messageProduced(cell, envelope)
    
    envelope.message match {
    
      // Decomposed network events are simply enqueued to the priority queued
      // and dispatched at the earliest convenience.
      case par: NodesUnreachable =>
        val msgs = pendingEvents.getOrElse(PRIORITY, new Queue[(Unique, ActorCell, Envelope)])
        pendingEvents(PRIORITY) = msgs += ((null, cell, envelope))
        
      case _ =>
        val unique @ Unique(msg : MsgEvent , id) = getMessage(cell, envelope)
        val msgs = pendingEvents.getOrElse(msg.receiver, new Queue[(Unique, ActorCell, Envelope)])
        pendingEvents(msg.receiver) = msgs += ((unique, cell, envelope))
        
        logger.trace(Console.BLUE + "New event: " +
            "(" + msg.sender + " -> " + msg.receiver + ") " +
            id + Console.RESET)
        
        addGraphNode(unique)
        depGraph.addEdge(unique, parentEvent)(DiEdge)
    }

  }

  
  def runExternal() = {
    //logger.debug(Console.RED + "RUN EXTERNAL CALLED initial IDX = " + externalEventIdx +Console.RESET) 
   
    var await = false
    while (externalEventIdx < externalEventList.length && !await) {
      val event = externalEventList(externalEventIdx)
      event match {
    
        case Start(propsCtor, name) => 
          instrumenter().actorSystem().actorOf(propsCtor(), name)
    
        case Send(rcv, msgCtor) =>
          val ref = instrumenter().actorMappings(rcv)
          instrumenter().actorMappings(rcv) ! msgCtor()

        case uniq @ Unique(par : NetworkPartition, id) =>  
          val msgs = pendingEvents.getOrElse(SCHEDULER, new Queue[(Unique, ActorCell, Envelope)])
          pendingEvents(SCHEDULER) = msgs += ((uniq, null, null))
          addGraphNode(uniq)
       
        case event @ Unique(q: WaitQuiescence, _) =>
          val msgs = pendingEvents.getOrElse(SCHEDULER, new Queue[(Unique, ActorCell, Envelope)])
          pendingEvents(SCHEDULER) = msgs += ((event, null, null))
          
          addGraphNode(event)
          depGraph.addEdge(event, getRootRootEvent())(DiEdge)

          
          await = true

        // A unique ID needs to be associated with all network events.
        case par : NetworkPartition => throw new Exception("internal error")
        case _ => throw new Exception("unsuported external event")
      }
      externalEventIdx += 1
    }
    
    //logger.debug(Console.RED + "RUN EXTERNAL LOOP ENDED idx = " + externalEventIdx + Console.RESET) 
    
    instrumenter().tellEnqueue.await()
    
    // Booststrap the process.
    schedule_new_message() match {
      case Some((cell, env)) =>
        instrumenter().dispatch_new_message(cell, env)
      case None => 
        throw new Exception("internal error")
    }
  }
        

  def run(externalEvents: Seq[ExternalEvent],
          f1: (Queue[Unique]) => Unit = nullFunPost,
          f2: (Scheduler) => Unit = nullFunDone) = {
    // Transform the original list of external events,
    // and assign a unique ID to all network events.
    // This is necessary since network events are not
    // part of the dependency graph.
    externalEventList = externalEvents.map { e => e match {
      case par: NetworkPartition => 
        val unique = Unique(par)
        unique
      case q: WaitQuiescence =>
        Unique(q)
      case other => other
    } }
    
    post = f1
    done = f2
    
    pendingEvents.clear()
    
    // In the end, reinitialize_system call start_trace.
    instrumenter().reinitialize_system(null, null)
  }
  
    /**
     * Given a message, figure out if we have already seen
     * it before. We achieve this by consulting the
     * dependency graph.
     *
     * * @param (cell, envelope: Original message context.
     *
     * * @return A unique event.
     */
  def getMessage(cell: ActorCell, envelope: Envelope) : Unique = {
    
    val snd = envelope.sender.path.name
    val rcv = cell.self.path.name
    val msg = new MsgEvent(snd, rcv, envelope.message)
    
    val parent = parentEvent match {
      case u @ Unique(m: MsgEvent, id) => u
      case u @ Unique(q: WaitQuiescence, id) => u
      case error => throw new Exception("parent event not a message " + error)
    }
    
    val inNeighs = depGraph.get(parent).inNeighbors
    inNeighs.find { x => x.value.event == msg } match {
      
      case Some(x) => return x.value
      case None =>
        val newMsg = Unique( MsgEvent(msg.sender, msg.receiver, msg.msg) )
        logger.trace(
            Console.YELLOW + "Not seen: " + newMsg.id + 
            " (" + msg.sender + " -> " + msg.receiver + ") " + msg + "\n" + parentEvent + Console.RESET)
        return newMsg
      case _ => throw new Exception("wrong type")
    }
      
  }
  
  
  // Called before we start processing a newly received event
  def before_receive(cell: ActorCell) {
  }
  
  // Called after receive is done being processed 
  def after_receive(cell: ActorCell) {
    /*
    invariantChecker.messageConsumed(cell, cell.currentMessage) match {
      case Seq(None) =>
      case problem => logger.debug(Console.BLINK + Console.RED + 
          "Invariant broken!" +
          Console.RESET)
    }*/
  }

  def printPath2(path : Queue[Unique]) : String = {
    var pathStr = ""
    for(node <- path) {
      node match {
        case Unique(m : MsgEvent, id) => pathStr += id + " "
        case Unique(q : WaitQuiescence, id) => pathStr += id + " "
        case _ => throw new Exception("internal error not a message")
      }
    }
    return pathStr
  }
  
  def printPath(path : List[depGraph.NodeT]) : String = {
    var pathStr = ""
    for(node <- path) {
      node.value match {
        case Unique(m : MsgEvent, id) => pathStr += id + " "
        case Unique(q : WaitQuiescence, id) => pathStr += id + " "
        case _ => throw new Exception("internal error not a message")
      }
    }
    return pathStr
  }

  
  
  def notify_quiescence() {
    
    if (awaitingQuiescence) {
      awaitingQuiescence = false
      logger.trace(Console.BLUE + "Done waiting for quiescence " + Console.RESET)
      
      currentQuiescentPeriod = nextQuiescentPeriod
      nextQuiescentPeriod = 0
      
      parentEvent = quiescentMarker
      tracker.addEvent( quiescentMarker )
      
      quiescentMarker = null

      runExternal()
      
    } else {
      
      logger.info("\n--------------------- Interleaving #" +
                  interleavingCounter + " ---------------------")
      
                  
      logger.debug(Console.BLUE + "Current trace: " +
          Util.traceStr(tracker.getCurrentTrace) + Console.RESET)


      tracker.startNewTrace()
      pendingEvents.clear()
      
      dpor(tracker.getCurrentTrace) match {
        case Some(trace) =>
          tracker.setNextTrace(trace)
          logger.debug(Console.BLUE + "Next trace:    " + 
              Util.traceStr(tracker.getNextTrace) + Console.RESET)
              
          tracker.aboutToPlay(trace)
          
          parentEvent = getRootRootEvent()
          currentQuiescentPeriod = 0
          
          instrumenter().await_enqueue()
          instrumenter().restart_system()
        case None =>
          return
      }
    }
  }
  
  
  def enqueue_message(receiver: String,msg: Any): Unit = {
    throw new Exception("internal error not a message")
  }
  
  
  def shutdown(): Unit = {
    throw new Exception("internal error not a message")
  }

  def notify_timer_scheduled(sender: ActorRef, receiver: ActorRef,
                             msg: Any): Boolean = {return true}
  
  
  def getEvent(index: Integer, trace: Queue[Unique]) : Unique = {
    trace(index) match {
      case u: Unique => u 
      case _ => throw new Exception("internal error not a message")
    }
  }
  

  def dpor(trace: Queue[Unique]) : Option[Queue[Unique]] = {
    
    interleavingCounter += 1
    
    val racingIndices = new HashSet[Integer]
    
    /**
     *  Analyze the dependency between two events that are co-enabled
     ** and have the same receiver.
     *
     ** @param earleirI: Index of the earlier event.
     ** @param laterI: Index of the later event.
     ** @param trace: The trace to which the events belong to.
     *
     ** @return none
     */
    def analyize_dep(earlierI: Int, laterI: Int, trace: Queue[Unique]): 
    Option[(Int, List[Unique])] = {

      // Retrieve the actual events.
      val earlier = getEvent(earlierI, trace)
      val later = getEvent(laterI, trace)

      // See if this interleaving has been explored.
      //val explored = tracker.isExplored((later, earlier))
      //if (explored) return None

      (earlier.event, later.event) match {
        
        // Since the later event is completely independent, we
        // can simply move it in front of the earlier event.
        // This might cause the earlier event to become disabled,
        // but we have no way of knowing.
        case (_: MsgEvent, _: NetworkPartition) =>
          val branchI = earlierI
          val needToReplay = List(later, earlier)
            
          tracker.setExplored(branchI, (earlier, later))

          return Some((branchI, needToReplay))
          
        // Similarly, we move an earlier independent event
        // just after the later event. None of the two event
        // will become disabled in this case.
        case (_: NetworkPartition, _: MsgEvent) => 
          val branchI = earlierI - 1
          val needToReplay = tracker.getCurrentTrace.clone()
            .drop(earlierI + 1)
            .take(laterI - earlierI)
            .toList :+ earlier
          
          tracker.setExplored(branchI, (earlier, later))
          
          return Some((branchI, needToReplay))
          
        case (_: MsgEvent, _: MsgEvent) =>
          // Get the actual nodes in the dependency graph that
          // correspond to those events
          val earlierN = (depGraph get earlier)
          val laterN = (depGraph get later)
          val rootN = (depGraph get getRootRootEvent)
          
          // Get the dependency path between later event and the
          // root event (root node) in the system.
          val laterPath = laterN.pathTo(rootN) match {
            case Some(path) => path.nodes.toList.reverse
            case None => throw new Exception("no such path")
          }

          // Get the dependency path between earlier event and the
          // root event (root node) in the system.
          val earlierPath = earlierN.pathTo(rootN) match {
            case Some(path) => path.nodes.toList.reverse
            case None => throw new Exception("no such path")
          }
          

          // Find the common prefix for the above paths.

          // Figure out where in the provided trace this needs to be
          // replayed. In other words, get the last element of the
          // common prefix and figure out which index in the trace
          // it corresponds to.
          val branchI = earlierI - 1

          val prefix = trace.dropRight(trace.size - earlierI)
          val prefixSet = prefix.toSet
          val (l1, l2) = laterPath.partition { x => prefixSet contains x.value }
          
          //val needToReplay = (l2 :+ earlierN).map { x => x.value }
          val needToReplay = (List() :+ laterN :+ earlierN).map { x => x.value }

          assert(l1 ++ l2 == laterPath)
          
          println(earlierN.value.id + " -- " + laterN.value.id  + " (" + printPath(l1) + "| " + printPath(l2) + ")")
          require(branchI < laterI)
          
          // Since we're dealing with the vertices and not the
          // events, we need to extract the values.
          val needToReplayV = needToReplay

          tracker.setExplored(branchI, (earlier, later))
          
          return Some((branchI, needToReplay))
      }

    }
    
    
    /** Figure out if two events are co-enabled.
     *
     * See if there is a path from the later event to the
     * earlier event on the dependency graph. If such
     * path does exist, this means that one event disables
     * the other one.
     * 
     ** @param earlier: First event
     ** @param later: Second event
     * 
     ** @return: Boolean 
     */
    def isCoEnabeled(earlier: Unique, later: Unique) : Boolean = (earlier, later) match {
      
      // NetworkPartition is always co-enabled with any other event.
      case (Unique(p : NetworkPartition, _), _) => true
      case (_, Unique(p : NetworkPartition, _)) => true
      // Quiescence is never co-enabled
      case (Unique(q: WaitQuiescence, _), _) => false
      case (_, Unique(q: WaitQuiescence, _)) => false
      //case (_, _) =>
      case (Unique(m1 : MsgEvent, _), Unique(m2 : MsgEvent, _)) =>
        if (m1.receiver != m2.receiver) 
          return false
          
        if (quiescentPeriod.get(earlier).get != quiescentPeriod.get(later).get) {
          return false
        }
        
        val earlierN = (depGraph get earlier)
        val laterN = (depGraph get later)
        
        val coEnabeled = laterN.pathTo(earlierN) match {
          case None => true
          case _ => false
        }
        
        return coEnabeled
    }
    
    
    def getNext() : Option[(Int, (Unique, Unique), Seq[Unique])] = {
      
      // If the backtrack set is empty, this means we're done.
      if (backTrack.isEmpty) {
        logger.info("Tutto finito!")
        done(this)
        return None
      }
  
      // Find the deepest backtrack value.
      val maxIndex = backTrack.keySet.max
      
      val (_ @ Unique(_, id1),
           _ @ Unique(_, id2)) = backTrack(maxIndex).headOption match {
        case Some(((u1, u2), eventList)) => (u1, u2)
        case None => 
          backTrack.remove(maxIndex)
          return getNext()
        case _ => throw new Exception("invalid interleaving event types")
      }
      
      val ((e1, e2), replayThis) = backTrack(maxIndex).head
      backTrack(maxIndex).remove((e1, e2))
      // XXX: Progress hook.
      
      tracker.isExplored((e1, e2), trace.take(maxIndex + 1) ++ replayThis) match {
        case true => return getNext()
        case false => return Some((maxIndex, (e1, e2), replayThis))
      }

    }

    /*
     * For every event in the trace (called later),
     * see if there is some earlier event, such that:
     * 
     * 0) They belong to the same receiver.
     * 1) They are co-enabled.
     * 2) Such interleaving hasn't been explored before.
     * 3) There is not a freeze flag associated with their
     *    common backtrack index.
     */ 
    def analyzeAllPairs() = {
      for(laterI <- 0 to trace.size - 1) {
        val later @ Unique(laterEvent, laterID) = getEvent(laterI, trace)
  
        for(earlierI <- 0 to laterI - 1) {
          val earlier @ Unique(earlierEvent, earlierID) = getEvent(earlierI, trace) 
          
          //val sameReceiver = earlierMsg.receiver == laterMsg.receiver
          if ( isCoEnabeled(earlier, later)) {
            
            analyize_dep(earlierI, laterI, trace) match {
              case Some((branchI, needToReplayV)) =>    
                
                //logger.info(Console.GREEN +
                //  "Found a race between " + earlier.id + " and " +
                //  later.id + " with a common index " + branchI +
                //  Console.RESET)
                
                // Since we're exploring an already executed trace, we can
                // safely mark the interleaving of (earlier, later) as
                // already explored.
                backTrack.getOrElseUpdate(branchI, new HashMap[(Unique, Unique), List[Unique]])
                backTrack(branchI)((later, earlier)) = needToReplayV
                
                // XXX: Progress hook.
              case None => // Nothing
            }
            
          }
          
        }
      }
    }
    
    
    
    if (tracker.progress()) 
      analyzeAllPairs()

    getNext() match {
      case Some((maxIndex, (e1, e2), replayThis)) =>        

        logger.info(Console.RED + "Exploring a new message interleaving " + 
           e1.id + " and " + e2.id  + " at index " + maxIndex + Console.RESET)
        //logger.info(Console.RED + e1 + Console.RESET)
        //logger.info(Console.RED + e2 + Console.RESET)
           
        tracker.setExplored(maxIndex, (e1, e2))
        tracker.printExplored()
        
        // A variable used to figure out if the replay diverged.
        invariant = Queue(e1, e2)
        
        // Remove the backtrack branch, since we're about explore it now.
        if (backTrack(maxIndex).isEmpty)
          backTrack -= maxIndex
        
        // Return all events up to the backtrack index we're interested in
        // and slap on it a new set of events that need to be replayed in
        // order to explore that interleaving.
        val nextSeq = trace.take(maxIndex + 1) ++ replayThis
        return Some(nextSeq)
        
      case None =>
        return None
    }
  }
  

}
