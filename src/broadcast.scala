import akka.actor.{ Actor, ActorRef }
import akka.actor.{ ActorSystem, Scheduler, Props }
import akka.event.Logging
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// -- Initialization messages --
case class AddLink(link: ActorRef)

// -- Base message type --
object DataMessage {
  // Global static variable to simplify creation of unique IDs.
  private var next_id = 0
  private def get_next_id = {next_id += 1; next_id}
}

case class DataMessage(data: String) {
  var id = DataMessage.get_next_id
}

// -- main() -> Node messages --
case class Stop()
case class RBBroadcast(msg: DataMessage)

// -- Link -> Link messages --
case class SLDeliver(senderName: String, msg: DataMessage)
case class ACK(senderName: String, msgID: Int)

// -- Node -> Node messages --
case class Tick()

// -- FailureDetector -> Node messages --
case class SuspectedFailure(actor: ActorRef)
// N.B. even in a crash-stop failure model, SuspectedRecovery might still
// occur in the case that the FD realized that it made a mistake.
case class SuspectedRecovery(actor: ActorRef)

// -- main() -> FailureDetector message --
case class Kill(node: ActorRef)
case class Recover(node: ActorRef)

/**
 * FailureDetector interface.
 *
 * Guarentee: eventually all suspects are correctly suspected. We don't know
 * when that point will be though.
 *
 * We use an unorthodox "push" interface for notifying clients of suspected
 * failures, rather than the traditional "pull" interface. This is to achieve
 * quiescence.
 */
trait FailureDetector {}

/**
 * FailureDetector implementation meant to be integrated directly into a model checker or
 * testing framework. Doubles as a mechanism for killing nodes.
 */
class HackyFailureDetector(nodes: List[ActorRef]) extends Actor with FailureDetector {
  val log = Logging(context.system, this)

  def kill(node: ActorRef) {
    log.info("Killing " + node)
    node ! Stop
    val otherNodes = nodes.filter(n => n.compareTo(node) != 0)
    otherNodes.map(n => n ! SuspectedFailure(node))
  }

  def receive = {
    case Kill(node) => kill(node)
    case _ => log.error("Unknown message")
  }

  // TODO(cs): support recovery. Upon recovering a node, send SuspectedRecovery messages to all links.
}

// Class variable for PerfectLink.
object PerfectLink {
  private val timeoutMillis = 500
}

/**
 * PerfectLink. Attached to Nodes.
 */
class PerfectLink(parent: Node, destination: ActorRef, name: String) {
  var parentName = parent.name
  var delivered : Set[Int] = Set()
  var unacked : Map[Int,DataMessage] = Map()
  // Whether the destination is suspected to be crashed, according to a
  // FailureDetector.
  var destinationSuspected = false

  def pl_send(msg: DataMessage) {
    sl_send(msg)
  }

  def sl_send(msg: DataMessage) {
    parent.log.info("Sending SLDeliver(" + parentName + "," + msg + ")")
    destination ! SLDeliver(parentName, msg)
    if (unacked.size == 0) {
      parent.schedule_timer(PerfectLink.timeoutMillis)
    }
    unacked += (msg.id -> msg)
  }

  def handle_sl_deliver(senderName: String, msg: DataMessage) {
    parent.log.info("Sending ACK(" + parentName + "," + msg.id + ")")
    destination ! ACK(parentName, msg.id)

    if (delivered contains msg.id) {
      return
    }

    delivered = delivered + msg.id
    parent.handle_pl_deliver(senderName, msg)
  }

  def handle_ack(senderName: String, msgID: Int) {
    unacked -= msgID
  }

  def handle_suspected_failure(suspect: ActorRef) {
    if (suspect.compareTo(destination) == 0) {
      destinationSuspected = true
    }
  }

  def handle_suspected_recovery(suspect: ActorRef) {
    if (suspect.compareTo(destination) == 0) {
      destinationSuspected = false
    }
  }

  def handle_tick() {
    if (destinationSuspected) {
      return
    }
    unacked.values.map(msg => sl_send(msg))
    if (unacked.size != 0) {
      parent.schedule_timer(PerfectLink.timeoutMillis)
    }
  }
}

/**
 * TimerQueue schedules timer events.
 */
class TimerQueue(scheduler: Scheduler, source: ActorRef) {
  var timerPending = false

  def maybe_schedule(timerMillis: Int) {
    if (timerPending) {
      return
    }
    timerPending = true
    scheduler.scheduleOnce(
      timerMillis milliseconds,
      source,
      Tick)
  }

  def handle_tick() {
    timerPending = false
  }
}

/**
 * Node Actor. Implements Reliable Broadcast.
 */
class Node(id: Int) extends Actor {
  var name = self.path.name
  val timerQueue = new TimerQueue(context.system.scheduler, self)
  var allLinks: Set[PerfectLink] = Set()
  var dst2link: Map[String, PerfectLink] = Map()
  var delivered: Set[Int] = Set()
  val log = Logging(context.system, this)

  def add_link(dst: ActorRef) {
    val link = new PerfectLink(this, dst, name + "-" + dst.path.name)
    allLinks = allLinks + link
    dst2link += (dst.path.name -> link)
  }

  def rb_broadcast(msg: DataMessage) {
    log.info("Initiating RBBroadcast(" + msg + ")")
    beb_broadcast(msg)
  }

  def beb_broadcast(msg: DataMessage) {
    allLinks.map(link => link.pl_send(msg))
  }

  def handle_pl_deliver(senderName: String, msg: DataMessage) {
    handle_beb_deliver(senderName, msg)
  }

  def handle_beb_deliver(senderName: String, msg: DataMessage) {
    if (delivered contains msg.id) {
      return
    }

    delivered = delivered + msg.id
    log.info("RBDeliver of message " + msg + " from " + senderName + " to " + name)
    beb_broadcast(msg)
  }

  def stop() {
    context.stop(self)
  }

  def handle_tick() {
    timerQueue.handle_tick
    allLinks.map(link => link.handle_tick)
  }

  def schedule_timer(timerMillis: Int) {
    timerQueue.maybe_schedule(timerMillis)
  }

  def handle_suspected_failure(destination: ActorRef) {
    allLinks.map(link => link.handle_suspected_failure(destination))
  }

  def handle_suspected_recovery(destination: ActorRef) {
    allLinks.map(link => link.handle_suspected_recovery(destination))
  }

  def receive = {
    // Node messages:
    case AddLink(dst) => add_link(dst)
    case Stop => stop
    case RBBroadcast(msg) => rb_broadcast(msg)
    // Link messages:
    case SLDeliver(senderName, msg) => dst2link.getOrElse(senderName, null).handle_sl_deliver(senderName, msg)
    case ACK(senderName, msgID) => dst2link.getOrElse(senderName, null).handle_ack(senderName, msgID)
    // FailureDetector messages:
    case SuspectedFailure(destination) => handle_suspected_failure(destination)
    case SuspectedRecovery(destination) => handle_suspected_recovery(destination)
    case Tick => handle_tick
    case _ => log.error("Unknown message")
  }
}

object Main extends App {
  val system = ActorSystem("Broadcast")

  val numNodes = 4
  println ("numNodes: " + numNodes)
  val nodes = List.range(0, numNodes).map(i =>
    system.actorOf(Props(classOf[Node], i), name="node" + i))

  val createLinksForNodes = (src: ActorRef, dst: ActorRef) => {
    src ! AddLink(dst)
    dst ! AddLink(src)
  }
  val srcDstPairs  = for (i <- 0 to numNodes-1; j <- i+1 to numNodes-1) yield (nodes(i), nodes(j))
  srcDstPairs.map(tuple => createLinksForNodes(tuple._1, tuple._2))

  val fd = system.actorOf(Props(classOf[HackyFailureDetector],nodes), name="fd")

  // TODO(cs): technically we should block here until all configuration
  // messages have been delivered. i.e. check that all Nodes have all their
  // Links.

  // Sample Execution:

  nodes(0) ! RBBroadcast(DataMessage("Message"))
  fd ! Kill(nodes(1))
  nodes(numNodes-1) ! RBBroadcast(DataMessage("Message"))
  nodes(0) ! RBBroadcast(DataMessage("Message"))

  // TODO(cs): need to figure out how to detect when the test is over.
  // Otherwise, Akka just sits in an infinite loop.
}
