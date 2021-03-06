package media.state.models.actors

import java.util.UUID

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import akka.stream.SourceRef
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import handlers.FileManager
import media.fdk.codec.Codec.{Duration, Format}
import media.fdk.codec.{Audio, Video}
import media.fdk.file.FileIOHandler
import media.fdk.json.{MediaInfo, PreferenceSettings}
import media.state.events.EventProcessorSettings
import media.state.models.shards.FileShard
import utils.actors.Actor
import utils.file.{ContentType => UtilContentType}
import utils.traits.{CborSerializable, Command, Event, Response}

import scala.concurrent.duration._

object FileActor extends Actor[FileShard]{

  val Config = FileIOHandler(ConfigFactory.load())

  /*** CMD  ***/
  final case class AddFile(file: File, replyTo: ActorRef[MediaDescription]) extends Command
  final case class RemoveFile(fileId: UUID) extends Command
  final case class ConvertFile(info: PreferenceSettings, reply: ActorRef[FileProgress]) extends Command
  final case class GetFileById(fileId: UUID, replyTo: ActorRef[MediaDescription]) extends Command
  final case class UpdateStatus(file: FileJournal, status: String) extends Command
  final case class GetFile(replyTo: ActorRef[Response]) extends Command
  final case class PlayFile(replyTo: ActorRef[Response]) extends Command
  final case class CompressFile(
    data: Array[Byte], 
    fileName: String, 
    replyTo: ActorRef[Response]) extends Command

  /*** STATE ***/
  final case class State(
    file: FileJournal,
    status: String) extends CborSerializable {
    def insert(file: FileJournal): State = copy(file = file)
    def isComplete: String = status

    def updateStatus(status: String): State = copy(status = status) 
    def getFile: Response = {
      if(file.fileId != null)
        Get(file, isComplete)
      else
        FileNotFound
    }

    def getAck = Ack

    def getFileProgress: FileProgress = FileProgress(file.fileName, file.fileId, status) 
    def getFileJournal(upload: Boolean): MediaDescription = {
      val mMmo = Config.getMultiMedia(file.fileName, upload)
      val info = mMmo.getInfo()
      val media = (Video(info.getVideo()), Audio(info.getAudio()))

      MediaDescription(Duration(info.getDuration), Format(info.getFormat), MediaInfo(
        file.fileName,
        media._1,
        media._2,
        UtilContentType(file.contentType),
        file.status,
        file.fileId
      ))
    }

    def playFile(replyTo: ActorRef[Response], actorSystem: ActorSystem[_]): Response = {
      Option(file.fileId) match {
        case Some(_) =>
          val sourceRef = FileManager.play(file)(actorSystem)
          Play(sourceRef, file.contentType, isComplete)
        case None => FileNotFound
      }
    }

  }

  object State {
    val empty = State(file = FileJournal.empty, status = "none")
  }
  final case class FileAdded(fileId: UUID, file: FileJournal) extends Event
  final case class FileRemoved(fileId: UUID) extends Event
  final case class ConvertedFile(journal: FileJournal) extends Event
  final case class UpdatedStatus(file: FileJournal, status: String) extends Event

  /*** PERSIST ***/
  case object Ack extends Response

  final case class File(
    fileName: String,
    contentType: String,
    status: Int) extends Response 

  final case class MediaDescription(
    duration: Duration,
    format: Format,
    mediaInfo: MediaInfo
  ) extends Response

  final case class FileJournal(
    fileName: String, 
    fullPath: String, 
    contentType: String, 
    status: Int, 
    fileId: UUID) extends Response

  final case class FileProgress(fileName: String, fileId: UUID, status: String = "inprogress") extends Response
  object FileNotFound extends Response

  object FileJournal {
    def empty: FileJournal = FileJournal("", "", "", 0, null)
  }
   
  final case class Get(journal: FileJournal, status: String) extends Response
  final case class Play(sourceRef: SourceRef[ByteString], contentType: String, status: String) extends Response

  /*** INI ***/
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command](actor.actorName)

  def createBehavior(e: EntityContext[Command])(implicit sys: ActorSystem[_], sett: EventProcessorSettings): Behavior[Command] = { 
    sys.log.info("Creating identity {} id: {} ", actor.actorName, e.entityId)
    val n = math.abs(e.entityId.hashCode % sett.parallelism)
    val eventTag = sett.tagPrefix + "-" + n
    apply(UUID.fromString(e.entityId), Set(eventTag))
  }

  def init(settings: EventProcessorSettings)(implicit sys: ActorSystem[_]): Unit = {
    implicit val sett: EventProcessorSettings = settings
    actor.init(TypeKey, createBehavior){ entity =>
      entity.withRole("write-model")
    }
  }

  def apply(fileId: UUID, eventTags: Set[String])(implicit sys: ActorSystem[_]): Behavior[Command] = actor.setupSource { self =>
    val singletonActor =
      ClusterSingleton(sys)
        .init(SingletonActor(Behaviors.supervise(FileActorListModel())
          .onFailure[Exception](SupervisorStrategy.restart), "FileListActor"))
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        PersistenceId(TypeKey.name, fileId.toString),
        State.empty,
        actor.processFile(fileId, self, singletonActor),
        actor.handleEvent(singletonActor))
      .withTagger(_ => eventTags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
