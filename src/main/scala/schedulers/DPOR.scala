package akka.dispatch.verification

import akka.actor.ActorCell,
       akka.actor.ActorSystem,
       akka.actor.ActorRef,
       akka.actor.Actor,
       akka.actor.PoisonPill,
       akka.actor.Props

import akka.dispatch.Envelope,
       akka.dispatch.MessageQueue,
       akka.dispatch.MessageDispatcher

import scala.collection.concurrent.TrieMap,
       scala.collection.mutable.Queue,
       scala.collection.mutable.HashMap,
       scala.collection.mutable.HashSet,
       scala.collection.mutable.ArrayBuffer,
       scala.collection.mutable.ArraySeq,
       scala.collection.Iterator

import scalax.collection.mutable.Graph,
       scalax.collection.GraphEdge.DiEdge,
       scalax.collection.edge.LDiEdge
       
import com.typesafe.scalalogging.LazyLogging,
       org.slf4j.LoggerFactory,
       ch.qos.logback.classic.Level,
       ch.qos.logback.classic.Logger


// A basic scheduler
class DPOR extends Scheduler with LazyLogging {
  
  var instrumenter = Instrumenter
  var started = false
  
  var currentTime = 0
  var interleavingCounter = 0
  
  val producedEvents = new Queue[ Event ]
  val consumedEvents = new Queue[ Event ]
  
  // Current set of enabled events.
  val pendingEvents = new HashMap[String, Queue[(Event, ActorCell, Envelope)]]  
  val actorNames = new HashSet[String]
 
  val depGraph = Graph[Event, DiEdge]()
  var depMap = new HashMap[Event, HashMap[Event, Event]]
  
  //val backTrack = new ArraySeq[ ((Event, Event), List[Event]) ](100)
  val backTrack = new HashMap[Int, ((Event, Event), List[Event]) ] 
  val freezeSet = new HashSet[Integer]
  val alreadyExplored = new HashSet[(Event, Event)]
  var invariant : Queue[Event] = Queue()
  
  val currentTrace = new Queue[ Event ]
  val nextTrace = new Queue[ Event ]
  var parentEvent = getRootEvent
  
  
  def getRootEvent : MsgEvent = {
    var root = MsgEvent("null", "null", null, 0)
    depMap.getOrElseUpdate(root, new HashMap[Event, Event])
    return root
  }
  
  
  def isSystemCommunication(sender: ActorRef, receiver: ActorRef): Boolean = 
  (receiver, sender) match {
    case (null, _) => return true
    case (_, null) => isSystemMessage("deadletters", receiver.path.name)
    case _ => isSystemMessage(sender.path.name, receiver.path.name)
  }

  
  // Is this message a system message
  def isSystemMessage(sender: String, receiver: String): Boolean = 
  ((actorNames contains sender) || (actorNames contains receiver)) match {
    case true => return false
    case _ => return true
  }
  
  
  // Notification that the system has been reset
  def start_trace() : Unit = {
    
    started = false
    actorNames.clear
    
    val firstActor = nextTrace.dequeue() match {
      case firstSpawn : SpawnEvent => 
        instrumenter().actorSystem().actorOf(firstSpawn.props, firstSpawn.name)
      case _ => throw new Exception("cannot find the first spawn")
    }
    
    
    nextTrace.dequeue() match {
      case firstMsg : MsgEvent => firstActor ! firstMsg.msg
      case _ => throw new Exception("cannot find the first message")
    }

  }
  
  
  // When executing a trace, find the next trace event.
  def mutable_trace_iterator( trace: Queue[  Event ]) : Option[Event] =
  trace.isEmpty match {
    case true => return None
    case _ => return Some(trace.dequeue)
  }
  
  

  // Get next message event from the trace.
  def get_next_trace_message() : Option[MsgEvent] =
  mutable_trace_iterator(nextTrace) match {
    case Some(v : MsgEvent) => Some(v)
    case Some(v : Event) => get_next_trace_message()
    case None => None
  }

  
  
  
  // Figure out what is the next message to schedule.
  def schedule_new_message() : Option[(ActorCell, Envelope)] = {
    
    // Filter messages belonging to a particular actor.
    def is_the_same(msg: MsgEvent, other: (Event, ActorCell, Envelope)) : Boolean = {
      other match {
        case (event: MsgEvent, cell, env) =>
          if (msg.id == 0) msg.receiver == cell.self.path.name
          else msg.receiver == cell.self.path.name && msg.id == event.id
        case _ => throw new Exception("not a message event")
      }
      
    }

    // Get from the current set of pending events.
    def get_pending_event(): Option[(Event, ActorCell, Envelope)] = {
      // Do we have some pending events
      pendingEvents.headOption match {
        case Some((receiver, queue)) =>

          if (queue.isEmpty == true) {
            
            pendingEvents.remove(receiver) match {
              case Some(key) => get_pending_event()
              case None => throw new Exception("internal error")
            }

          } else {
            Some(queue.dequeue())
            
          }
        case None => None
      }
    }
    

    val matchingMessage = get_next_trace_message() match {
      // The trace says there is a message event to run.
      case Some(msg_event: MsgEvent) =>
        
        // Look at the pending events to see if such message event exists. 
        pendingEvents.get(msg_event.receiver) match {
          case Some(queue) => queue.dequeueFirst(is_the_same(msg_event, _))
          case None =>  None
        }
        
      // The trace says there is nothing to run so we have either exhausted our
      // trace or are running for the first time. Use any enabled transitions.
      case None => None
    }
    
    
    val result = matchingMessage match {
      
      // There is a pending event that matches a message in our trace.
      // We call this a convergent state.
      case Some((next_event: MsgEvent, c, e)) =>

        logger.trace( Console.GREEN + "Now playing: " +
            "(" + next_event.sender + " -> " + next_event.receiver + ") " +
            + next_event.id + Console.RESET )

        Some((next_event, c, e))


      // We call this a divergent state.
      case None => get_pending_event()
      
      // Something went wrong.
      case _ => throw new Exception("not a message")
    }
    

    result match {
      
      case Some((next_event : MsgEvent, cell, env)) =>
        
        invariant.headOption match {
          case Some(msg: MsgEvent) if (msg.id == next_event.id) => 
            logger.trace("Replaying message event " + msg.id)
            invariant.dequeue()
          case _ =>
        }
        
        currentTrace += next_event
        (depGraph get next_event)
        parentEvent = next_event
        return Some((cell, env))
        
      case _ => return None
    }

  }
  
  
  // Get next event
  def next_event() : Event = {
    mutable_trace_iterator(nextTrace) match {
      case Some(v) => v
      case None => throw new Exception("no previously consumed events")
    }
  }
  

  // Record that an event was consumed
  def event_consumed(event: Event) = { 
    consumedEvents.enqueue( event )
  }
  
  
  def event_consumed(cell: ActorCell, envelope: Envelope) = {
    var event = new MsgEvent(
        envelope.sender.path.name, cell.self.path.name, 
        envelope.message)
    
    consumedEvents.enqueue( event )
  }
  
  
  // Record that an event was produced 
  def event_produced(event: Event) = {
        
    event match {
      case event : SpawnEvent => actorNames += event.name
      case msg : MsgEvent => 
    }
    
    producedEvents.enqueue( event )
  }
  
  
  
  def getMessage(cell: ActorCell, envelope: Envelope) : MsgEvent = {
    
    val snd = envelope.sender.path.name
    val rcv = cell.self.path.name
    
    val msg = new MsgEvent(snd, rcv, envelope.message, 0)
    val msgs = pendingEvents.getOrElse(rcv, new Queue[(Event, ActorCell, Envelope)])
    
    val parent = parentEvent

    
    
    val parentMap = depMap.get(parent) match {
      case Some(x) => x
      case None => throw new Exception("no such parent")
    }

    val realMsg = parentMap.get(msg) match {
      case Some(x : MsgEvent) => x
      case None =>
        val newMsg = new MsgEvent(msg.sender, msg.receiver, msg.msg)
        
        logger.trace(
            Console.YELLOW + "Not seen: " + newMsg.id + 
            " (" + newMsg.sender + " -> " + newMsg.receiver + ") " + Console.RESET)
            
        depMap(newMsg) = new HashMap[Event, Event]
        parentMap(msg) = newMsg
        newMsg
      case _ => throw new Exception("wrong type")
    }
    
    pendingEvents(rcv) = msgs += ((realMsg, cell, envelope))
    return realMsg
  }
  
  
  
  def event_produced(cell: ActorCell, envelope: Envelope) = {

    val event = getMessage(cell, envelope)
    
    logger.trace(Console.BLUE + "New event: " +
        "(" + event.sender + " -> " + event.receiver + ") " +
        event.id + 
        Console.RESET)
    
    depGraph.add(event)
    producedEvents.enqueue( event )

    depGraph.addEdge(event, parentEvent)(DiEdge)

    if (!started) {
      started = true
      instrumenter().start_dispatch()
    }
  }
  
  
  // Called before we start processing a newly received event
  def before_receive(cell: ActorCell) {}
  
  
  // Called after receive is done being processed 
  def after_receive(cell: ActorCell) {}
  

  
  def printPath(path : List[depGraph.NodeT]) : String = {
    var pathStr = ""
    for(node <- path) {
      node.value match {
        case x : MsgEvent => pathStr += x.id + " "
        case _ => throw new Exception("internal error not a message")
      }
    }
    return pathStr
  }

  
  
  def notify_quiescence() {
    
    var str1 = "trace: "
    for (item <- currentTrace) {
      item match {
        case m : MsgEvent => str1 += m.id + " " 
        case _ =>
      }
    }
    
    logger.info("\n--------------------- Interleaving #" +
                interleavingCounter + " ---------------------")
    
    logger.debug(Console.BLUE + "Current trace: " +
        Util.traceStr(currentTrace) + Console.RESET)
        
    var nnnn = 

    interleavingCounter += 1
    
    // XXX: JUST A QUICK FIX. MAGIC NUMBER AHEAD.
    nextTrace.clear()
    
    val firstSpawn = consumedEvents.find( x => x.isInstanceOf[SpawnEvent]) match{
      case Some(s: SpawnEvent) => s
      case _ => throw new Exception("internal error")
    }
    
    nextTrace += firstSpawn
    nextTrace ++= dpor()
    
    logger.debug(Console.BLUE + "Next trace:  " + 
        Util.traceStr(nextTrace) + Console.RESET)
    
    producedEvents.clear()
    consumedEvents.clear()
  
    currentTrace.clear
    
    parentEvent = getRootEvent

    pendingEvents.clear()

    instrumenter().await_enqueue()
    instrumenter().restart_system()
  }
  
  
  
  def getEvent(index: Integer) : MsgEvent = {
    currentTrace(index) match {
      case eee : MsgEvent => eee
      case _ => throw new Exception("internal error not a message")
    }
  }

  
  
  def dpor() : Queue[Event] = {
    
    val root = getEvent(0)
    val rootN = ( depGraph get root )
    
    val racingIndices = new HashSet[Integer]
    
    
    
    def analyize_dep(earlierI: Integer, laterI: Integer) : Unit = {
      
      val earlier = getEvent(earlierI)
      val later = getEvent(laterI)
      
      alreadyExplored += ((earlier, later))
      
      val earlierN = (depGraph get earlier)
      val laterN = (depGraph get later)
      
      val laterPath = laterN.pathTo( rootN ) match {
        case Some(path) => path.nodes.toList.reverse
        case None => throw new Exception("no such path")
      }
      
      val earlierPath = earlierN.pathTo( rootN ) match {
        case Some(path) => path.nodes.toList.reverse
        case None => throw new Exception("no such path")
      }
      
      val commonPrefix = laterPath.intersect(earlierPath)
      
      val laterDiff = laterPath.diff(commonPrefix)
      val earlierDiff = earlierPath.diff(commonPrefix)

      val needToReplay = 
        earlierDiff.take(earlierDiff.size - 1) ++ laterDiff
      
      val lastElement = commonPrefix.last
      val commonAncestor = currentTrace.indexWhere { e => (e == lastElement.value) }
      
      require(commonAncestor < laterI)
      
      val values = needToReplay.map(v => v.value)
      
      val frozen = freezeSet contains commonAncestor
      val explored = alreadyExplored.contains((later, earlier))
      (frozen, explored) match {
        
        case (false, false) =>
          logger.trace(Console.CYAN + "Earlier: " + 
              printPath(earlierPath) + Console.RESET)
          logger.trace(Console.CYAN + "Later:   " + 
              printPath(laterPath) + Console.RESET)
          logger.trace(Console.CYAN + "Replay:  " + 
              printPath(needToReplay) + Console.RESET)
          logger.info(Console.GREEN + 
              "Found a race between " + earlier.id +  " and " + 
              later.id + " with a common index " + commonAncestor +
              Console.RESET)
              
          
          val racingPair = ((later, earlier))
          backTrack(commonAncestor) = (racingPair, values)
          
          freezeSet += commonAncestor
          racingIndices += commonAncestor
          
        case (true, false) =>
          racingIndices += commonAncestor
          
        case _ =>
          //logger.debug("\tAlready explored " + commonAncestor)
      }

    }
    
    
      
    def isCoEnabeled(earlier: MsgEvent, later: MsgEvent) : Boolean = {
      
      val earlierN = (depGraph get earlier)
      val laterN = (depGraph get later)
      
      val coEnabeled = laterN.pathTo(earlierN) match {
        case None => true
        case _ => false
      }
      
      return coEnabeled
    }
    

    
    for(laterI <- 0 to currentTrace.size - 1) {
      val later = getEvent(laterI)

      for(earlierI <- 0 to laterI - 1) {
        val earlier = getEvent(earlierI)
        
        val sameReceiver = earlier.receiver == later.receiver
        if (sameReceiver && isCoEnabeled(earlier, later)) {
          analyize_dep(earlierI, laterI)
        }
        
      }
    }
    
    if (backTrack.isEmpty) {
      logger.info("Tutto finito!")
      System.exit(0);
    }

    
    val maxIndex = backTrack.keySet.max
    freezeSet -= maxIndex
    
    val (first, second) = backTrack(maxIndex)._1 match {
      case (m1: MsgEvent, m2: MsgEvent) => (m1, m2)
      case _ => throw new Exception("invalid interleaving events")
    }
    
    logger.info(Console.RED + "Exploring a new message interleaving between " + 
       first.id + " and " + second.id  + " at index " + maxIndex + Console.RESET)
    
    logger.debug("Unexplored indices: " + racingIndices)
    logger.debug("Frozen indices:     " + freezeSet)
    
    val ((e1, e2), replayThis) = backTrack(maxIndex)
    invariant = Queue(e1, e2)
    
    alreadyExplored += ((e1, e2))
    
    backTrack -= maxIndex
    val result =  currentTrace.take(maxIndex + 1) ++ replayThis

    return result
    
  }
  

}
