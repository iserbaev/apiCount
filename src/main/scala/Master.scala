import akka.actor._
import com.typesafe.config.ConfigFactory

import scala.collection.mutable.ListBuffer

object Master {
  def props() = Props(new Master())
  case class Start(jobName: String, text: List[String])
  case class ProcessResults(receiver: ActorRef)
  case class Results(r: List[String])
}
class Master extends Actor with ActorLogging {
  import Master._
  import Worker._

  val config = ConfigFactory.load()
  val nrOfRoutee = config.getInt("routees")

  var words = ListBuffer.empty[String]
  var wordsCount = 0
  var results = ListBuffer.empty[String]

  override def receive: Receive = idle
  def idle: Receive = {
    case Start(jobName, text) =>
      log.debug(s"Start job with name:${jobName} and text:${text}")
      val receiver = sender()
      words ++= text
      wordsCount = words.size
      context.become(working(jobName, receiver))
      createRoutee()
  }
  def working(jobName: String, receiver: ActorRef): Receive = {
    case GetTask =>
      log.debug(s"Got request to task from worker")
      val workerRef = sender()
      if (words.isEmpty){
        workerRef ! WorkDone
      } else {
        val word = words.head
        workerRef ! Task(word)
        val tail = words.tail
        words = tail
      }
    case WorkResult(r) =>
      results += r
      log.debug(s"Got work result from worker. Results size=${results.size}. Words size=${words.size}")
      if (results.size == wordsCount) {
        context.become(finishing(jobName))
        self ! ProcessResults(receiver)
      }
  }
  def finishing(jobName: String): Receive = {
    case ProcessResults(receiver) =>
      log.debug(s"Job=${jobName} is done, results: ${results}")
      receiver ! Results(results.toList)
      context.stop(self)
  }

  def createRoutee(): Unit = {
    (1 to nrOfRoutee) foreach { childInstanceNr =>
      val child = context.actorOf(Worker.props, "routee" + childInstanceNr)
      val selection = context.actorSelection(child.path)
      context.watch(child)
      selection ! Work(s"Process word")
    }
  }
}
