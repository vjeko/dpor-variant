package pastry


import org.slf4j.{ Logger => Underlying }
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

class RoutingTable(ID : BigInt) 
  extends Config with Traversable[(String, BigInt)] {

  val logger = Logger(LoggerFactory.getLogger("pastry"))

  val myID = ID
  val myIDStr = toBase(ID)
  
  var values = new scala.collection.mutable.HashSet[BigInt]
  //var table = Vector.fill(exponent, base)( ("-", -1 : BigInt) )
  var table = Array.ofDim[(String, BigInt)](exponent, base)
  
  for( y <- 1 to base; x <- 1 to exponent)
    table(x - 1)(y - 1) = ("-", -1)
  
  
  override def clone() : RoutingTable = {
    val newRT = new RoutingTable(myID)
    newRT.values = values.clone()
    newRT.table = table.clone()
    return newRT
  }
  
  
  def foreach[U](f: ((String, BigInt)) => U) = 
    for(row <- table)
      for(value <- row) value match {
        case ("-", _) => // Skip!
        case (valid, int) => f(value) 
      }
        
        
  override def equals (other: Any) = other match {
    case otherTable : RoutingTable =>
      otherTable.myID == this.myID &&
      true
    case _ => false
  }
  
  
  def dump() : Unit = {
    for( row <- table) {
      for(value <- row)
        printf(s"%${exponent}s ", value)
      println()
    }
  }
  
    
  def location(keyStr : String) : (Int, Int) = {
    
    assert(keyStr != myIDStr, "Strings are equivalent.")
    
    val y = commonPrefix(keyStr, myIDStr) - 1
    val x = charToIndex(keyStr.charAt(y))
    
    return (y, x)
  }
  
  /**
   * The routing table is used and the message is forwarded 
   * to a node that shares a common prefix with the key by 
   * at least one more digit.
   */
  def computeNext(key: BigInt) : Option[BigInt] = {
    val keyStr = toBase(key)
    val (y, x) = location(keyStr)
    
    table(y)(x) match {
      case ("-", _) => return None
      case (_, _) => return Some( fromBase( table(y)(x)._1 ) )
    }
  }
  
  def update[T](v: Vector[Vector[T]])(c1: Int, c2: Int)(newVal: T) = 
    v.updated(c1, v(c1).updated(c2, newVal))
  
  def remove(key : BigInt) : Option[(Int, Int)] = {
    values -= key
    return removeStr(toBase(key))
  }
  
  
  def removeStr(keyStr : String) : Option[(Int, Int)] = {
    
    val (y, x) = location(keyStr)
    logger.trace(myIDStr + ": " + "Removing " 
        + keyStr + " at row " + y + " column " + x)
    table(y)(x) match {
      case ("-", _) => return None
      case _ =>
        //table(y)(x) = ("-", -1)
        table = table.updated(y, table(y).updated(x, ("-", -1: BigInt)))
        return Some((y, x))
    }
  }
  
  
  /**
   * Inefficient, but correct -- we're taking all the 
   * non-empty entries from the other routing table and 
   * inserting them into this one.
   */
  def steal(other: RoutingTable) : Unit =
    for (y <- 0 to exponent - 1)
      for (x <- 0 to base - 1)
        other.table(y)(x) match {
          case ("-", _) => // Skip!
          case (newStr, newInt) if (newStr == myIDStr) => // Skip!
          case (newStr, newInt) => insert((newStr, newInt))
        }
  
  
  /**
   * Given a key, insert it in appropriate location in the table.
   * By default, if there is already a value stored at that location
   * in the table, it is not overwritten.
   */
  def insert(
      tuple : (String, BigInt), overwrite : Boolean = false) : Option[(Int, Int)] = {
    val (keyStr, key) = tuple
    
    values += key
    
    assert(keyStr != myIDStr)
    
    val (y, x) = location(keyStr)
    logger.trace(myIDStr + ": " + "Inserting " 
        + keyStr + " at row " + y + " column " + x)
     
    (table(y)(x), overwrite) match {
      case (_, true) => 
        table(y)(x) = (keyStr, key)
        //table = update(table)(y, x)((keyStr, key))
        return Some((y, x))
      case (("-", _), _) =>
        table(y)(x) = (keyStr, key)
        //table = update(table)(y, x)((keyStr, key))
        return Some((y, x))
      case (_, _) =>
        return None
    }
    
  }
  
    def insertInt(
      key: BigInt, overwrite : Boolean = false) : Option[(Int, Int)] = {
      return insert((toBase(key), key), overwrite)
    }


}
