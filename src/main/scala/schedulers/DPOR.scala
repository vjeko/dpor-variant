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
       scalax.collection.GraphPredef._, 
       scalax.collection.GraphEdge._,
       scalax.collection.edge.LDiEdge,     // labeled directed edge
       scalax.collection.edge.Implicits._ // shortcuts
       
import java.io.{ PrintWriter, File }

import scalax.collection.edge.LDiEdge,
       scalax.collection.edge.Implicits._,
       scalax.collection.io.dot._



// A basic scheduler
class DPOR extends Scheduler {
  
  var instrumenter = Instrumenter
  var currentTime = 0
  var index = 0
  
  type CurrentTimeQueueT = Queue[Event]
  
  var currentlyProduced = new CurrentTimeQueueT
  var currentlyConsumed = new CurrentTimeQueueT
  
  var producedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
  var consumedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
  
  var prevProducedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
  var prevConsumedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
 
  var parentEvent : Event = null
  var trace = new ArrayBuffer[(Event, HashSet[Event])]
  
  // Current set of enabled events.
  val pendingEvents = new HashMap[String, Queue[(Event, ActorCell, Envelope)]]  
  val actorNames = new HashSet[String]
 
  val g = Graph[Event, DiEdge]()
  
  var pro = new Queue[ Event ]
  var dep = new HashMap[Event, HashMap[Event, Event]]
  var explored = new HashSet[Event]
  var backTrack = new ArraySeq[ List[Event] ](100)
  var freeze = new ArraySeq[ Boolean ](100)    
  
  freeze.map { f => false }
  
  def isSystemCommunication(sender: ActorRef, receiver: ActorRef): Boolean = {
    //println("isSystemCommunication " + sender + " " + receiver)
    if (receiver == null) return true
    
    return sender match {
      case null => 
        isSystemMessage("deadletters", receiver.path.name)
      case _ =>
        isSystemMessage(sender.path.name, receiver.path.name)
    }
    
  }
  
  // Is this message a system message
  def isSystemMessage(sender: String, receiver: String): Boolean = {
    //println("isSystemMessage " + sender + " -> " + receiver)
    if ((actorNames contains sender) || (actorNames contains receiver)) {
      return false
    } else {
      return true      
    }
    
    
  }
  
  
  // Notification that the system has been reset
  def start_trace() : Unit = {
    
    println("Start new trace...")
    
    prevProducedEvents = producedEvents
    prevConsumedEvents = consumedEvents
    producedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
    consumedEvents = new Queue[ (Integer, CurrentTimeQueueT) ]
  }
  
  
  // When executing a trace, find the next trace event.
  private[this] def mutable_trace_iterator(
      trace: Queue[ (Integer, CurrentTimeQueueT) ]) : Option[Event] = { 
    
    if(trace.isEmpty) return None
      
    val (count, q) = trace.head
    q.isEmpty match {
      case true =>
        trace.dequeue()
        mutable_trace_iterator(trace)
      case false => return Some(q.dequeue())
    }
  }
  
  

  // Get next message event from the trace.
  private[this] def get_next_trace_message() : Option[MsgEvent] = {
    mutable_trace_iterator(prevConsumedEvents) match {
      case Some(v : MsgEvent) =>  Some(v)
      case Some(v : Event) => get_next_trace_message()
      case None => None
    }
  }
  
  
  
  // Figure out what is the next message to schedule.
  def schedule_new_message() : Option[(ActorCell, Envelope)] = {
  
    println("schedule_new_message " + prevConsumedEvents.size)
    println("schedule_new_message " + pendingEvents  .size)
    
    // Filter for messages belong to a particular actor.
    def is_the_same(e: MsgEvent, c: (Event, ActorCell, Envelope)) : Boolean = {
      val (event, cell, env) = c
      e.receiver == cell.self.path.name
    }

    // Get from the current set of pending events.
    def get_pending_event()  : Option[(Event, ActorCell, Envelope)] = {
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
        case None =>
          //instrumenter().restart_system()
          None
      }
    }

    val result = get_next_trace_message() match {
      // The trace says there is something to run.
      case Some(msg_event: MsgEvent) =>
        pendingEvents.get(msg_event.receiver) match {
          case Some(queue) => queue.dequeueFirst(is_the_same(msg_event, _))
          case None =>
            println("NONE")
            None
        }
      // The trace says there is nothing to run so we have either exhausted our
      // trace or are running for the first time. Use any enabled transitions.
      case None => get_pending_event()
    }
    
    result match {
      case Some((next_event, c, e)) =>
        (g get next_event)
        parentEvent = next_event
        return Some((c, e))
      case _ => return None
    }
    
    
  }
  
  
  // Get next event
  def next_event() : Event = {
    mutable_trace_iterator(prevConsumedEvents) match {
      case Some(v) => v
      case None => throw new Exception("no previously consumed events")
    }
  }
  

  // Record that an event was consumed
  def event_consumed(event: Event) = {
    currentlyConsumed.enqueue(event)
  }
  
  
  def event_consumed(cell: ActorCell, envelope: Envelope) = {
    var event = new MsgEvent(
        envelope.sender.path.name, cell.self.path.name, 
        envelope.message)
    currentlyConsumed.enqueue(event)
  }
  
  
  // Record that an event was produced 
  def event_produced(event: Event) = {
        
    event match {
      case event : SpawnEvent => actorNames += event.name
      case msg : MsgEvent => 
    }
    currentlyProduced.enqueue(event)

  }
  
  
  def getMessage(cell: ActorCell, envelope: Envelope) : MsgEvent = {
    val snd = envelope.sender.path.name
    val rcv = cell.self.path.name
    
    val msg = new MsgEvent(snd, rcv, envelope.message, 0)
    val msgs = pendingEvents.getOrElse(rcv, new Queue[(Event, ActorCell, Envelope)])
    
    val parent = parentEvent match {
      case null => 
        val newMsg = MsgEvent("null", "null", null)
        dep.getOrElseUpdate(newMsg, new HashMap[Event, Event])
        newMsg
      case _ => parentEvent
    }
    
    val map = dep.get(parent) match {
      case Some(x) => x
      case None => throw new Exception("no such parent")
    }
    
    val realMsg = map.get(msg) match {
      case Some(x : MsgEvent) => x
      case None =>
        val newMsg = new MsgEvent(msg.sender, msg.receiver, msg.msg)
        dep(newMsg) = new HashMap[Event, Event]
        newMsg
      case _ => throw new Exception("wrong type")
    }
    
    pendingEvents(rcv) = msgs += ((realMsg, cell, envelope))
    return realMsg
  }
  
  
  
  def event_produced(cell: ActorCell, envelope: Envelope) = {

    val event = getMessage(cell, envelope)
    
    g.add(event)
    pro += event
    
    println("currentlyProduced: " + event.sender + " -> " + event.receiver)
    currentlyProduced.enqueue(event)
    
    if(parentEvent != null) {
      g.addEdge(event, parentEvent)(DiEdge)
      trace += ((event, new HashSet[Event]))
    }
  }
  
  
  // Called before we start processing a newly received event
  def before_receive(cell: ActorCell) {
    producedEvents.enqueue( (currentTime, currentlyProduced) )
    consumedEvents.enqueue( (currentTime, currentlyConsumed) )

    currentlyProduced = new CurrentTimeQueueT
    currentlyConsumed = new CurrentTimeQueueT
    currentTime += 1
    println(Console.GREEN 
        + " ↓↓↓↓↓↓↓↓↓ ⌚  " + currentTime + " | " + cell.self.path.name + " ↓↓↓↓↓↓↓↓↓ " + 
        Console.RESET)
  }
  
  
  // Called after receive is done being processed 
  def after_receive(cell: ActorCell) {
    println(Console.RED 
        + " ↑↑↑↑↑↑↑↑↑ ⌚  " + currentTime + " | " + cell.self.path.name + " ↑↑↑↑↑↑↑↑↑ " 
        + Console.RESET)
        
  }
  
  def get_dot() {
    val root = DotRootGraph(
        directed = true,
        id = Some("DPOR"))

    def nodeStr(event: Event) : String = {
      event.value match {
        case msg : MsgEvent => msg.receiver + " (" + msg.id.toString() + ")" 
        case spawn : SpawnEvent => spawn.name + " (" + spawn.id.toString() + ")" 
      }
    }
    
    def nodeTransformer(
        innerNode: scalax.collection.Graph[Event, DiEdge]#NodeT):
        Option[(DotGraph, DotNodeStmt)] = {
      val descr = innerNode.value match {
        case msg : MsgEvent => DotNodeStmt( nodeStr(msg), Seq.empty[DotAttr])
        case spawn : SpawnEvent => DotNodeStmt( nodeStr(spawn), Seq(DotAttr("color", "red")))
      }

      Some(root, descr)
    }
    
    def edgeTransformer(
        innerEdge: scalax.collection.Graph[Event, DiEdge]#EdgeT): 
        Option[(DotGraph, DotEdgeStmt)] = {
      
      val edge = innerEdge.edge

      val src = nodeStr( edge.from.value )
      val dst = nodeStr( edge.to.value )

      return Some(root, DotEdgeStmt(src, dst, Nil))
    }
    
    val str = g.toDot(root, edgeTransformer, cNodeTransformer = Some(nodeTransformer))
    
    //println(str)
    val pw = new PrintWriter(new File("dot.dot" ))
    pw.write(str)
    pw.close
  }

  
  
  def notify_quiescence() {
    
    for((index, queue) <- consumedEvents) {
      var str = index.toString() + " "
      queue.headOption match {
        case Some(x : MsgEvent) => str += x.sender + " -> " + x.receiver
        case Some(x : SpawnEvent) => str +=  Console.GREEN + "spawn" + Console.RESET
        case _ => throw new Exception("missing event")
      }
      println(str)
    }
    
    //get_dot()
    currentTime = 0
    
    println("Total " + trace.size + " events.")
    dpor()
    pro.clear()
    
    instrumenter().await_enqueue()
    instrumenter().restart_system()
  }
  
  
  
  def getEvent(index: Integer) : MsgEvent = {
    pro(index) match {
      case eee : MsgEvent => eee
      case _ => throw new Exception("internal error not a message")
    }
  }

  
  
  def dpor() = {
    
    println(g.nodes.size + " " + pro.size)
    
    val root = getEvent(0)
    println(root.sender + " -> " + root.receiver + " " + root.id)
    val rootN = ( g get root )
    
    
    def printPath(path : List[g.NodeT]) = {
      var pathStr = ""
      for(node <- path) {
        node.value match {
          case x : MsgEvent => pathStr += " (" + x.receiver + " " + x.id + ") "
          case _ => println("NO!")
        }
      }
      println("path -> " + pathStr)
    }
    
    
    def analyize_dep(j: Integer, i: Integer) : Unit = {
      
      val later = getEvent(i)
      val earlier = getEvent(j)
      
      val earlierN = (g get earlier)
      val laterN = (g get later)
      
      val laterPath = laterN.pathTo( rootN ) match {
        case Some(path) => path.nodes.toList.reverse
        case None => throw new Exception("no such path")
      }
      
      val earlierPath = earlierN.pathTo( rootN ) match {
        case Some(path) => path.nodes.toList.reverse
        case None => throw new Exception("no such path")
      }
      
      val commonPrefix = laterPath.intersect(earlierPath)
      val needtoReplay = laterPath.diff(commonPrefix)
      val lastElement = commonPrefix.last
      val commonAncestor = pro.indexWhere { e => (e == lastElement.value) }

      //printPath(laterPath)
      //printPath(earlierPath)

      //println("Found a race between " + i + 
      //    " and " + j + " with a common index " + commonAncestor)
      
      require(commonAncestor > -1 && commonAncestor < j)
      
      val values = needtoReplay.map(v => v.value)
      
      if(!freeze(commonAncestor)) {
        freeze(commonAncestor) = true
        backTrack(commonAncestor) = values
      }

    }
    
    
      
    def isCoEnabeled(earlier: MsgEvent, later: MsgEvent) : Boolean = {
      
      val earlierN = (g get earlier)
      val laterN = (g get later)
      
      val coEnabeled = laterN.pathTo(earlierN) match {
        case None => true
        case _ => false
      }
      
      return coEnabeled
    }
    

    
    for(i <- 0 to pro.size - 1) {
      val later = getEvent(i)

      for(j <- 0 to i - 1) {
        val earlier = getEvent(j)
        
        if (earlier.receiver == later.receiver &&
            isCoEnabeled(earlier, later)) {
          analyize_dep(i, j)
        }
        
      }
    }
    
  }
  

}
