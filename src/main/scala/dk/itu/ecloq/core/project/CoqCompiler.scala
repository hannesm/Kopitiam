package dk.itu.ecloq.core.project

import dk.itu.ecloq.core.ManifestIdentifiers
import dk.itu.ecloq.core.model.ICoqModel
import dk.itu.ecloq.core.coqtop.CoqProgram
import dk.itu.ecloq.core.utilities.{CacheSlot, JobRunner}

import org.eclipse.core.runtime.{Status, IStatus, SubMonitor, IProgressMonitor}
import org.eclipse.core.resources.{IFile, IResource}

class CoqCompilerRunner(
    source : IFile) extends JobRunner[CoqCompilerResult] {
  import java.io.{File, IOException, FileInputStream}

  private var ticker : Option[() => Boolean] = None
  def setTicker(f : Option[() => Boolean]) = (ticker = f)

  private class MonitorThread(
      process : Process, ticker : () => Boolean) extends Thread {
    setDaemon(true)

    private def isFinished = try {
      process.exitValue; true
    } catch {
      case e : IllegalThreadStateException => false
    }

    override def run =
      while (!isFinished) {
        if (!ticker())
          process.destroy
        Thread.sleep(200)
      }
  }

  private def _configureProcess(pb : ProcessBuilder) : Process = {
    pb.redirectErrorStream(true)
    val process = pb.start
    ticker.foreach(t => new MonitorThread(process, t).start)
    process
  }

  private val buffer = CacheSlot[Array[Byte]](new Array[Byte](4096))

  override protected def doOperation(
      monitor : SubMonitor) : CoqCompilerResult = {
    monitor.beginTask("Compiling " + source, 1)

    val location = source.getLocation.removeFileExtension
    val outputFile = location.addFileExtension("vo").toFile

    val coqc = CoqProgram("coqtop")
    if (!coqc.check)
      fail(new Status(IStatus.ERROR,
          ManifestIdentifiers.PLUGIN, "Couldn't find the coqtop program"))

    val cp = ICoqModel.toCoqProject(source.getProject)
    val flp = cp.getLoadPath.flatMap(_.asArguments)
    val coqcp = coqc.run(
        flp ++ Seq("-noglob", "-compile", location.toOSString),
        _configureProcess)

    try {
      coqcp.readAll match {
        case (i, msgs) if i != 0 =>
          return CoqCompilerFailure(source, i, msgs)
        case _ =>
      }

      val is = new FileInputStream(outputFile)
      val content = Array.newBuilder[Byte]

      var count = 0
      do {
        content ++= buffer.get.toSeq.take(count)
        count = is.read(buffer.get)
      } while (count != -1)

      CoqCompilerSuccess(source, content.result)
    } catch {
      case e : java.io.IOException => fail(new Status(IStatus.ERROR,
            ManifestIdentifiers.PLUGIN, e.getLocalizedMessage, e))
    } finally outputFile.delete
  }
}

sealed abstract class CoqCompilerResult(val source : IFile)
case class CoqCompilerSuccess(override val source : IFile,
    result : Array[Byte]) extends CoqCompilerResult(source) {
  import java.io.ByteArrayInputStream
  def save(output : IFile, monitor : IProgressMonitor) = {
    val is = new ByteArrayInputStream(result)
    if (output.exists) {
      output.setContents(is, IResource.NONE, monitor)
    } else output.create(is, IResource.NONE, monitor)
  }
}
case class CoqCompilerFailure(override val source : IFile,
    exitCode : Int, messages : String) extends CoqCompilerResult(source)