/* CommonJobs.scala
 * Base classes, utility objects and helpers for jobs, and some shared jobs
 * Copyright © 2013 Alexander Faithfull
 * 
 * You may use, copy, modify and/or redistribute this code subject to the terms
 * of either the license of Kopitiam or the Apache License, version 2.0 */

package dk.itu.sdg.kopitiam

import org.eclipse.core.runtime.{
  Status, IStatus, CoreException, IProgressMonitor}
import org.eclipse.core.resources.{IMarker, IResource}
import org.eclipse.core.runtime.jobs.ISchedulingRule

case class ObjectRule(obj : AnyRef) extends ISchedulingRule {
  override def contains(i : ISchedulingRule) = (this == i)
  override def isConflicting(i : ISchedulingRule) = (this == i)
}

object JobUtilities {
  import org.eclipse.core.resources.ResourcesPlugin
  def getRuleFactory = ResourcesPlugin.getWorkspace().getRuleFactory()
  
  object MultiRule {
    def apply(a : ISchedulingRule*) =
      org.eclipse.core.runtime.jobs.MultiRule.combine(a.toArray)
  }
}

import org.eclipse.core.resources.WorkspaceJob

abstract class MarkerJob(
    resource : IResource) extends WorkspaceJob("Update markers") {
  setRule(JobUtilities.getRuleFactory.markerRule(resource))
  setSystem(true) /* Don't show this job in the UI */
}

class DeleteMarkersJob(
    resource : IResource, type_ : String,
    includeSubtypes : Boolean, depth : Int) extends MarkerJob(resource) {
  override def runInWorkspace(monitor : IProgressMonitor) : IStatus = {
    resource.deleteMarkers(type_, includeSubtypes, depth)
    Status.OK_STATUS
  }
}

class CreateMarkerJob(
    resource : IResource, region : (Int, Int), message : String,
    type_ : String, severity : Int) extends MarkerJob(resource) {
  override def runInWorkspace(monitor : IProgressMonitor) : IStatus = {
    val m = resource.createMarker(type_)
    import scala.collection.JavaConversions._
    m.setAttributes(Map(
        (IMarker.MESSAGE, message),
        (IMarker.LOCATION, resource.toString),
        (IMarker.SEVERITY, severity),
        (IMarker.CHAR_START, region._1),
        (IMarker.CHAR_END, region._2),
        (IMarker.TRANSIENT, true)))
    Status.OK_STATUS
  }
}

class CreateErrorMarkerJob(
    resource : IResource, region : (Int, Int), message : String)
    extends CreateMarkerJob(
        resource, region, message, IMarker.PROBLEM, IMarker.SEVERITY_ERROR)
object CreateErrorMarkerJob {
  def apply(
      resource : IResource, step : CoqStep, ep : (CoqTypes.location, String)) : CreateErrorMarkerJob = {
    val offsets = ep._1 match {
      case Some((begin, end)) => (step.offset + begin, step.offset + end)
      case None => (step.offset, step.offset + step.text.length)
    }
    CreateErrorMarkerJob(resource, offsets, ep._2.trim)
  }
  
  def apply(
      resource : IResource, region : (Int, Int), message : String) : CreateErrorMarkerJob =
    new CreateErrorMarkerJob(resource, region, message)
}

import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.SubMonitor

abstract class JobBase(name : String) extends Job(name) {
  protected def runner : JobRunner[_]
  
  override def run(monitor_ : IProgressMonitor) : IStatus = try {
    runner.run(monitor_)
    Status.OK_STATUS
  } catch {
    case e : CoreException => new Status(e.getStatus.getSeverity,
          "dk.itu.sdg.kopitiam", "Job runner execution failed.", e)
  }
}

abstract class ContainerJobBase(
    name : String, container : CoqTopContainer) extends JobBase(name) {
  setRule(ObjectRule(container))
  
  override def run(monitor_ : IProgressMonitor) : IStatus =
    try {
      super.run(monitor_)
    } finally container.setBusy(false)
}

trait JobRunner[A] {
  protected def finish : Unit = ()
  protected def doOperation(monitor : SubMonitor) : A
  
  def run(monitor_ : IProgressMonitor) : A = {
    val monitor = SubMonitor.convert(monitor_)
    try {
      doOperation(monitor)
    } finally {
      finish
      monitor.done
    }
  }
  
  protected def fail(status : IStatus) = throw new CoreException(status)
  protected def cancel = fail(Status.CANCEL_STATUS)
}

abstract class StepRunner[A](container : CoqTopContainer)
    extends JobRunner[CoqTypes.value[A]] {
  protected def updateGoals = {
    val goals = container.coqTop.goals match {
      case CoqTypes.Good(g) => g
      case _ => None
    }
    UIUtils.asyncExec {
      container.setGoals(goals)
    }
    goals
  }
  
  override protected def finish = updateGoals
}

abstract class StepForwardRunner[A <: CoqCommand](
    container : CoqTopContainer, steps : Seq[A])
    extends StepRunner[String](container) {
  protected def onFail(step : A, result : CoqTypes.Fail[String])
  protected def onGood(step : A, result : CoqTypes.Good[String])
  protected def onUnsafe(step : A, result : CoqTypes.Unsafe[String]) =
    onGood(step, CoqTypes.Good[String](result.value))
  protected def initialise = ()
    
  override def doOperation(
      monitor : SubMonitor) : CoqTypes.value[String] = {
    monitor.beginTask("Step forward", steps.length)
    initialise
    for (step <- steps) {
      if (monitor.isCanceled)
        cancel
      monitor.subTask(step.text.trim)
      step.run(container.coqTop) match {
        case r : CoqTypes.Good[String] => onGood(step, r)
        case r : CoqTypes.Unsafe[String] => onUnsafe(step, r)
        case r : CoqTypes.Fail[String] =>
          onFail(step, r)
          return CoqTypes.Fail(r.value)
      }
      monitor.worked(1)
    }
    CoqTypes.Good("")
  }
}