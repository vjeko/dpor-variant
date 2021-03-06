import pastry._

import akka.actor.Actor,
       akka.actor.ActorRef,
       akka.actor.DeadLetter,
       akka.actor.ActorSystem,
       akka.actor.Props

import org.slf4j.LoggerFactory,
       com.typesafe.scalalogging.Logger

object PastryTest extends Config
//with App 
{
  val logger = Logger(LoggerFactory.getLogger("pastry"))
  val system = ActorSystem("pastry")
  
  val bootstrapID : BigInt = 54615
  val IDs : List[BigInt] = List(1, 3, 1234599, 5423)
  
  val bootstrapNode = system.actorOf(
      Props(classOf[PastryPeer], bootstrapID : BigInt), 
      name = toBase(bootstrapID))

  val nodes = IDs.map(id => system.actorOf(
      Props(classOf[PastryPeer], id : BigInt), 
      name = toBase(id)))
      
      
  nodes(0) ! Bootstrap(bootstrapID, bootstrapID)
  Thread.sleep(100)
  nodes(1) ! Bootstrap(IDs(1), bootstrapID)
  Thread.sleep(100)
  nodes(2) ! Bootstrap(IDs(2), bootstrapID)
  Thread.sleep(100)

  nodes(0) ! Write(1212, 1234)
  Thread.sleep(100)
  
  nodes(2) ! Read(1212)
  nodes(1) ! Read(1212)
  nodes(0) ! Read(1212)
  Thread.sleep(100)
  
  //nodes.map(node => node ! AddPeers(nodes))
  //nodes.map(node => system.eventStream.subscribe(node, classOf[DeadLetter]))
}