package devbox

import java.nio.ByteBuffer
import java.time.{Duration, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{ActorContext, Bytes, Response, Rpc, RpcClient, Signature, SimpleActor, Skipper, StateMachineActor, SyncLogger, Util, Vfs}


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: SyncFiles.Msg) extends Msg
  case class ForceRestart() extends Msg
  case class ReadFailed() extends Msg
  case class AttemptReconnect() extends Msg
  case class Receive(data: Response) extends Msg
  case class Close() extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor,
                          statusActor: => StatusActor,
                          logger: SyncLogger)
                         (implicit ac: ActorContext)
  extends StateMachineActor[AgentReadWriteActor.Msg](){

  def initialState = Active(Vector())

  case class Active(buffer: Vector[SyncFiles.Msg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      statusActor.send(StatusActor.Syncing(msg.logged))
      val newBuffer = buffer :+ msg
      sendRpcFor(newBuffer, 0, msg).getOrElse(Active(newBuffer))

    case AgentReadWriteActor.ReadFailed() =>
      restart(buffer, 0)

    case AgentReadWriteActor.ForceRestart() =>
      restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) =>
      syncer.send(SyncActor.Receive(data))

      if (!data.isInstanceOf[Response.Ack]) Active(buffer)
      else {
        ac.reportComplete()

        val msg = buffer.head
        statusActor.send(
          if (buffer.tail.nonEmpty) StatusActor.Syncing(msg.logged + "\n" + "(Complete)")
          else StatusActor.Done()
        )
        Active(buffer.tail)
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class RestartSleeping(buffer: Vector[SyncFiles.Msg], retryCount: Int) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      RestartSleeping(buffer :+ msg, retryCount)

    case AgentReadWriteActor.ReadFailed() => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.ForceRestart() => restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.AttemptReconnect() =>

      statusActor.send(StatusActor.Syncing(s"Restarting Devbox agent\nAttempt #$retryCount"))
      val started = agent.start(s =>
        statusActor.send(StatusActor.Syncing(
          s"Restarting Devbox agent\nAttempt #$retryCount\n$s"
        ))
      )
      val startError = if (started) None else Some(restart(buffer, retryCount))

      startError.getOrElse{
        spawnReaderThread()
        val newMsg =
          if (buffer.nonEmpty) None
          else{
            ac.reportSchedule()
            Some(SyncFiles.RpcMsg(Rpc.Complete(), "Re-connection Re-sync"))
          }

        val newBuffer = buffer ++ newMsg
        val failState = newBuffer.foldLeft(Option.empty[State]){
          case (Some(end), _) => Some(end)
          case (None, msg) =>
            statusActor.send(StatusActor.Syncing(msg.logged))
            sendRpcFor(newBuffer, retryCount, msg)
        }

        failState.getOrElse(Active(newBuffer))
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class GivenUp(buffer: Vector[SyncFiles.Msg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      statusActor.send(StatusActor.Greyed(
        "Unable to connect to devbox, gave up after 5 attempts;\n" +
        "click on this logo to try again"
      ))
      GivenUp(buffer :+ msg)

    case AgentReadWriteActor.ForceRestart() =>
      statusActor.send(StatusActor.Syncing("Syncing Restarted"))
      restart(buffer, 0)

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class Closed() extends State({
    case _ => Closed()
  })
  val client = new RpcClient(agent.stdin, agent.stdout, logger.apply(_, _))

  def sendRpcFor(buffer: Vector[SyncFiles.Msg],
                 retryCount: Int,
                 msg: SyncFiles.Msg) = msg match{
    case SyncFiles.RpcMsg(rpc, logged) => sendRpc(buffer, retryCount, rpc)
    case SyncFiles.SendChunkMsg(src, dest, segments, chunkIndex, logged) =>
      val byteArr = new Array[Byte](Util.blockSize)
      val buf = ByteBuffer.wrap(byteArr)

      Util.autoclose(os.read.channel(src / segments)) { channel =>
        buf.rewind()
        channel.position(chunkIndex * Util.blockSize)
        var n = 0
        while ( {
          if (n == Util.blockSize) false
          else channel.read(buf) match {
            case -1 => false
            case d =>
              n += d
              true
          }
        }) ()

        val msg = Rpc.WriteChunk(
          dest,
          segments,
          chunkIndex * Util.blockSize,
          new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr)
        )
        statusActor.send(StatusActor.FilesAndBytes(1, n))
        sendRpc(buffer, retryCount, msg)
      }
  }

  def spawnReaderThread() = {
    new Thread(() => {
      while ( {
        val strOpt =
          try Some(agent.stderr.readLine())
          catch{
            case e: java.io.EOFException => None
            case e: java.io.IOException => None
          }
        strOpt match{
          case None | Some(null)=> false
          case Some(str) =>
            try {
              val s = ujson.read(str).str
              logger.apply(
                "AGENT OUT",
                new Object {
                  override def toString: String = s
                }
              )
              true
            } catch {
              case e: ujson.ParseException =>
                println(str)
                false
            }
        }
      })()
    }, "DevboxAgentLoggerThread").start()

    new Thread(() => {
      while(try{
        val response = client.readMsg[Response]()
        this.send(AgentReadWriteActor.Receive(response))
        true
      }catch{
        case e: java.io.IOException =>
          this.send(AgentReadWriteActor.ReadFailed())
          false
      })()
    }, "DevboxAgentOutputThread").start()
  }

  def sendRpc(buffer: Vector[SyncFiles.Msg], retryCount: Int, msg: Rpc): Option[State] = {
    try {
      client.writeMsg(msg)
      None
    } catch{ case e: java.io.IOException =>
      Some(restart(buffer, retryCount))
    }
  }

  def restart(buffer: Vector[SyncFiles.Msg], retryCount: Int): State = {

    try agent.destroy()
    catch{case e: Throwable => /*donothing*/}

    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      statusActor.send(StatusActor.Error(
        s"Unable to connect to devbox, trying again after $seconds seconds"
      ))
      ac.scheduleMsg(
        this,
        AgentReadWriteActor.AttemptReconnect(),
        Duration.ofSeconds(seconds)
      )

      RestartSleeping(buffer, retryCount + 1)
    } else {
      statusActor.send(StatusActor.Greyed(
        "Unable to connect to devbox, gave up after 5 attempts;\n" +
          "click on this logo to try again"
      ))

      GivenUp(buffer)
    }
  }
}

object SyncActor{
  sealed trait Msg
  case class ScanComplete(vfsArr: Seq[Vfs[Signature]]) extends Msg

  case class Events(paths: Map[os.Path, Set[os.SubPath]]) extends Msg
  case class LocalScanned(scanRoot: os.Path, sub: os.SubPath, index: Int) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
  case class LocalScanComplete() extends Msg
}
class SyncActor(agentReadWriter: => AgentReadWriteActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: SyncLogger,
                signatureTransformer: (os.SubPath, Signature) => Signature,
                ignoreStrategy: String,
                scheduledExecutorService: ScheduledExecutorService,
                statusActor: => StatusActor)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = RemoteScanning(
    Map(),
    Map(),
    mapping.map(_._2 -> new Vfs[Signature](Signature.Dir(0))),
    0
  )

  def joinMaps[K, V](left: Map[K, Set[V]], right: Map[K, Set[V]]) = {
    (left.keySet ++ right.keySet)
      .map{k => (k, left.getOrElse(k, Set()) ++ right.getOrElse(k, Set()))}
      .toMap
  }

  case class RemoteScanning(localPaths: Map[os.Path, Set[os.SubPath]],
                            remotePaths: Map[os.RelPath, Set[os.SubPath]],
                            vfsArr: Seq[(os.RelPath, Vfs[Signature])],
                            scansComplete: Int) extends State({
    case SyncActor.LocalScanned(base, sub, i) =>
      logger.progress(s"Scanned local path [$i]", sub.toString())
      RemoteScanning(joinMaps(localPaths, Map(base -> Set(sub))), remotePaths, vfsArr, scansComplete)

    case SyncActor.Events(paths) =>
      RemoteScanning(joinMaps(localPaths, paths), remotePaths, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Scanned(base, subPath, sig, i)) =>
      vfsArr.collectFirst{case (b, vfs) if b == base =>
        Vfs.overwriteUpdateVfs(subPath, sig, vfs)
      }
      logger.progress(s"Scanned remote path [$i]", (base / subPath).toString())
      val newRemotePaths = joinMaps(remotePaths, Map(base -> Set(subPath)))
      RemoteScanning(localPaths, newRemotePaths, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Ack()) | SyncActor.LocalScanComplete() =>
      scansComplete match{
        case 0 => RemoteScanning(localPaths, remotePaths, vfsArr, scansComplete + 1)
        case 1 =>
          logger.info(
            s"Initial Scans Complete",
            s"${localPaths.size} local paths, ${remotePaths.size} remote paths"
          )
          val mappingMap = mapping.map(_.swap).toMap
          executeSync(
            joinMaps(localPaths, remotePaths.map{case (k, v) => (mappingMap(k), v)}),
            vfsArr.map(_._2)
          )
      }
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })

  def executeSync(paths: Map[os.Path, Set[os.SubPath]], vfsArr: Seq[Vfs[Signature]]) = {
    SyncFiles.executeSync(
      mapping,
      signatureTransformer,
      paths,
      vfsArr,
      logger,
      m => agentReadWriter.send(AgentReadWriteActor.Send(m))
    ).map{failures =>
      if (failures.nonEmpty) this.send(SyncActor.Events(failures.reduceLeft(joinMaps(_, _))))
      else agentReadWriter.send(
        AgentReadWriteActor.Send(
          SyncFiles.RpcMsg(
            Rpc.Complete(),
            "Sync Complete\nwaiting for confirmation from Devbox"
          )
        )
      )
    }
    Waiting(vfsArr)
  }
}

object SkipActor{
  sealed trait Msg
  case class Paths(values: Set[os.Path]) extends Msg
  case class Scan() extends Msg
}
class SkipActor(mapping: Seq[(os.Path, os.RelPath)],
                ignoreStrategy: String,
                sendToSyncActor: SyncActor.Msg => Unit,
                logger: SyncLogger)
               (implicit ac: ActorContext) extends SimpleActor[SkipActor.Msg]{
  val skippers = mapping.map(_ => Skipper.fromString(ignoreStrategy))
  def run(msg: SkipActor.Msg) = msg match{
    case SkipActor.Scan() =>
      common.InitialScan.initialSkippedScan(mapping.map(_._1), skippers){
        (scanRoot, sub, sig, i) => sendToSyncActor(SyncActor.LocalScanned(scanRoot, sub, i))
      }.foreach{ _ =>
        sendToSyncActor(SyncActor.LocalScanComplete())
      }

    case SkipActor.Paths(values) =>

      val grouped = for(((src, dest), skipper) <- mapping.zip(skippers)) yield (
        src,
        skipper.process(
          src,
          for{
            value <- values
            if value.startsWith(src)
          } yield (value.subRelativeTo(src), os.isDir(value))
        )
      )
      sendToSyncActor(SyncActor.Events(grouped.toMap))
  }
}

object DebounceActor{
  sealed trait Msg
  case class Paths(values: Set[os.Path]) extends Msg
  case class Trigger(count: Int) extends Msg
}

class DebounceActor(handle: Set[os.Path] => Unit,
                    statusActor: => StatusActor,
                    debounceMillis: Int,
                    logger: SyncLogger)
                   (implicit ac: ActorContext)
  extends StateMachineActor[DebounceActor.Msg]{

  def initialState: State = Idle()


  case class Idle() extends State({
    case DebounceActor.Paths(paths) =>
      if (!paths.exists(p => p.last != "index.lock")) Idle()
      else {
        logChanges(paths, "Detected")
        val count = paths.size
        ac.scheduleMsg(
          this,
          DebounceActor.Trigger(count),
          Duration.ofMillis(debounceMillis)
        )
        Debouncing(paths)
      }
    case DebounceActor.Trigger(count) => Idle()
  })

  case class Debouncing(paths: Set[os.Path]) extends State({
    case DebounceActor.Paths(morePaths) =>
      logChanges(morePaths, "Ongoing")
      val allPaths = paths ++ morePaths
      val count = allPaths.size

      ac.scheduleMsg(
        this,
        DebounceActor.Trigger(count),
        Duration.ofMillis(debounceMillis)
      )
      Debouncing(allPaths)
    case DebounceActor.Trigger(count) =>
      if (count != paths.size) Debouncing(paths)
      else{
        logChanges(paths, "Syncing")
        handle(paths)
        Idle()
      }
  })
  def logChanges(paths: Iterable[os.Path], verb: String) = {
    val suffix =
      if (paths.size == 1) ""
      else s"\nand ${paths.size - 1} other files"
    logger("Debounce " + verb, paths)
    statusActor.send(StatusActor.Syncing(s"$verb changes to\n${paths.head.relativeTo(os.pwd)}$suffix"))
  }
}

object StatusActor{
  sealed trait Msg
  case class Syncing(msg: String) extends Msg
  case class FilesAndBytes(files: Int, bytes: Long) extends Msg
  case class Done() extends Msg
  case class Error(msg: String) extends Msg
  case class Greyed(msg: String) extends Msg
  case class Debounce() extends Msg
}
class StatusActor(setImage: String => Unit,
                  setTooltip: String => Unit)
                 (implicit ac: ActorContext) extends StateMachineActor[StatusActor.Msg]{

  val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

  def initialState = StatusState(IconState("blue-tick", "Devbox initializing"), None, 0, 0)
  case class IconState(image: String, tooltip: String)

  case class StatusState(icon: IconState,
                         debouncedNextIcon: Option[IconState],
                         syncFiles: Int,
                         syncBytes: Long) extends State({
    case StatusActor.Syncing(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("blue-sync", msg), syncFiles, syncBytes)

    case StatusActor.FilesAndBytes(nFiles, nBytes) =>
      StatusState(icon, debouncedNextIcon, syncFiles + nFiles, syncBytes + nBytes)

    case StatusActor.Done() =>
      debounce(
        icon,
        debouncedNextIcon.isDefined,
        IconState("green-tick", syncCompleteMsg(syncFiles, syncBytes)),
        syncFiles = 0,
        syncBytes = 0
      )

    case StatusActor.Error(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("red-cross", msg), syncFiles, syncBytes)

    case StatusActor.Greyed(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("grey-dash", msg), syncFiles, syncBytes)

    case StatusActor.Debounce() =>
      setIcon(icon, debouncedNextIcon.get)
      StatusState(debouncedNextIcon.get, None, syncFiles, syncBytes)
  })

  def syncCompleteMsg(syncFiles: Int, syncBytes: Long) = {
    s"Syncing Complete\n" +
    s"$syncFiles files $syncBytes bytes\n" +
    s"${formatter.format(java.time.Instant.now())}"
  }

  def debounce(icon: IconState,
               debouncing: Boolean,
               nextIcon: IconState,
               syncFiles: Int,
               syncBytes: Long): State = {
    if (debouncing) StatusState(icon, Some(nextIcon), syncFiles, syncBytes)
    else{
      setIcon(icon, nextIcon)
      if (icon == nextIcon) StatusState(icon, None, syncFiles, syncBytes)
      else {
        ac.scheduleMsg(
          this,
          StatusActor.Debounce(),
          Duration.ofMillis(100)
        )

        StatusState(nextIcon, Some(nextIcon), syncFiles, syncBytes)
      }
    }
  }

  def setIcon(icon: IconState, nextIcon: IconState) = {
    if (icon.image != nextIcon.image) setImage(nextIcon.image)
    if (icon.tooltip != nextIcon.tooltip) setTooltip(nextIcon.tooltip)
  }
}