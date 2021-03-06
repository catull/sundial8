package org.quartz.core;

import org.quartz.QuartzScheduler;
import org.quartz.exceptions.JobExecutionException;
import org.quartz.exceptions.JobPersistenceException;
import org.quartz.exceptions.SchedulerException;
import org.quartz.jobs.Job;
import org.quartz.jobs.JobDetail;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.triggers.OperableTrigger;
import org.quartz.triggers.Trigger.CompletedExecutionInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JobRunShell instances are responsible for providing the 'safe' environment for <code>Job</code> s
 * to run in, and for performing all of the work of executing the <code>Job</code>, catching ANY
 * thrown exceptions, updating the <code>Trigger</code> with the <code>Job</code>'s completion code,
 * etc.
 *
 * <p>A <code>JobRunShell</code> instance is created by a <code>JobRunShellFactory</code> on behalf
 * of the <code>QuartzSchedulerThread</code> which then runs the shell in a thread from the
 * configured <code>ThreadPool</code> when the scheduler determines that a <code>Job</code> has been
 * triggered.
 *
 * @see JobRunShellFactory
 * @see org.quartz.core.QuartzSchedulerThread
 * @see org.quartz.jobs.Job
 * @see org.quartz.triggers.Trigger
 * @author James House
 */
public class JobRunShell extends SchedulerListenerSupport implements Runnable {

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ Data members.
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private JobExecutionContextImpl jec = null;

  private QuartzScheduler qs = null;

  private TriggerFiredBundle firedTriggerBundle = null;

  private Scheduler scheduler = null;

  private volatile boolean shutdownRequested = false;

  private final Logger log = LoggerFactory.getLogger(getClass());

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ Constructors.
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a JobRunShell instance with the given settings.
   *
   * @param scheduler The <code>Scheduler</code> instance that should be made available within the
   *     <code>JobExecutionContext</code>.
   */
  JobRunShell(Scheduler scheduler, TriggerFiredBundle bndle) {

    this.scheduler = scheduler;
    this.firedTriggerBundle = bndle;
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ Interface.
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  @Override
  public void schedulerShuttingdown() {

    requestShutdown();
  }

  @Override
  protected Logger getLog() {

    return log;
  }

  void initialize(QuartzScheduler qs) throws SchedulerException {

    this.qs = qs;

    Job job = null;
    JobDetail jobDetail = firedTriggerBundle.getJobDetail();

    try {
      job = qs.getJobFactory().newJob(firedTriggerBundle, scheduler);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "An error occured instantiating job to be executed. job= '" + jobDetail.getName() + "'",
          se);
      throw se;
    } catch (Throwable ncdfe) { // such as NoClassDefFoundError
      SchedulerException se =
          new SchedulerException(
              "Problem instantiating class '" + jobDetail.getJobClass().getName() + "' - ", ncdfe);
      qs.notifySchedulerListenersError(
          "An error occured instantiating job to be executed. job= '" + jobDetail.getName() + "'",
          se);
      throw se;
    }

    this.jec = new JobExecutionContextImpl(scheduler, firedTriggerBundle, job);
  }

  private void requestShutdown() {

    shutdownRequested = true;
  }

  @Override
  public void run() {

    qs.addInternalSchedulerListener(this);

    try {
      OperableTrigger trigger = (OperableTrigger) jec.getTrigger();
      JobDetail jobDetail = jec.getJobDetail();

      do {

        JobExecutionException jobExEx = null;
        Job job = jec.getJobInstance();

        // notify job & trigger listeners...
        try {
          if (!notifyListenersBeginning(jec)) {
            break;
          }
        } catch (VetoedException ve) {
          CompletedExecutionInstruction instCode = trigger.executionComplete(jec, null);
          try {
            qs.notifyJobStoreJobVetoed(trigger, jobDetail, instCode);
          } catch (JobPersistenceException jpe) {
            vetoedJobRetryLoop(trigger, jobDetail, instCode);
          }
          break;
        }

        long startTime = System.currentTimeMillis();
        long endTime = startTime;

        // execute the job
        try {
          log.debug("Calling execute on job " + jobDetail.getName());
          job.execute(jec);
          endTime = System.currentTimeMillis();
        } catch (JobExecutionException jee) {
          endTime = System.currentTimeMillis();
          jobExEx = jee;
          getLog().info("Job " + jobDetail.getName() + " threw a JobExecutionException: ", jobExEx);
        } catch (Throwable e) {
          endTime = System.currentTimeMillis();
          getLog().error("Job " + jobDetail.getName() + " threw an unhandled Exception: ", e);
          SchedulerException se = new SchedulerException("Job threw an unhandled exception.", e);
          qs.notifySchedulerListenersError(
              "Job (" + jec.getJobDetail().getName() + " threw an exception.", se);
          jobExEx = new JobExecutionException(se, false);
        }

        jec.setJobRunTime(endTime - startTime);

        // notify all job listeners
        if (!notifyJobListenersComplete(jec, jobExEx)) {
          break;
        }

        CompletedExecutionInstruction instCode = CompletedExecutionInstruction.NOOP;

        // update the trigger
        try {
          instCode = trigger.executionComplete(jec, jobExEx);
        } catch (Exception e) {
          // If this happens, there's a bug in the trigger...
          SchedulerException se =
              new SchedulerException("Trigger threw an unhandled exception.", e);
          qs.notifySchedulerListenersError(
              "Please report this error to the Quartz developers.", se);
        }

        // notify all trigger listeners
        if (!notifyTriggerListenersComplete(jec, instCode)) {
          break;
        }

        // update job/trigger or re-execute job
        if (instCode == CompletedExecutionInstruction.RE_EXECUTE_JOB) {
          jec.incrementRefireCount();
          continue;
        }

        try {
          qs.notifyJobStoreJobComplete(trigger, jobDetail, instCode);
        } catch (JobPersistenceException jpe) {
          qs.notifySchedulerListenersError(
              "An error occured while marking executed job complete. job= '"
                  + jobDetail.getName()
                  + "'",
              jpe);
          if (!completeTriggerRetryLoop(trigger, jobDetail, instCode)) {
            return;
          }
        }

        break;
      } while (true);
      //    } while (shutdownRequested == false);

    } finally {
      qs.removeInternalSchedulerListener(this);
    }
  }

  private boolean notifyListenersBeginning(JobExecutionContext jec) throws VetoedException {

    boolean vetoed = false;

    // notify all trigger listeners
    try {
      vetoed = qs.notifyTriggerListenersFired(jec);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify TriggerListener(s) while firing trigger "
              + "(Trigger and Job will NOT be fired!). trigger= "
              + jec.getTrigger().getName()
              + " job= "
              + jec.getJobDetail().getName(),
          se);

      return false;
    }

    if (vetoed) {
      try {
        qs.notifyJobListenersWasVetoed(jec);
      } catch (SchedulerException se) {
        qs.notifySchedulerListenersError(
            "Unable to notify JobListener(s) of vetoed execution "
                + "while firing trigger (Trigger and Job will NOT be "
                + "fired!). trigger= "
                + jec.getTrigger().getName()
                + " job= "
                + jec.getJobDetail().getName(),
            se);
      }
      throw new VetoedException();
    }

    // notify all job listeners
    try {
      qs.notifyJobListenersToBeExecuted(jec);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify JobListener(s) of Job to be executed: "
              + "(Job will NOT be executed!). trigger= "
              + jec.getTrigger().getName()
              + " job= "
              + jec.getJobDetail().getName(),
          se);

      return false;
    }

    return true;
  }

  private boolean notifyJobListenersComplete(
      JobExecutionContext jec, JobExecutionException jobExEx) {

    try {
      qs.notifyJobListenersWasExecuted(jec, jobExEx);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify JobListener(s) of Job that was executed: "
              + "(error will be ignored). trigger= "
              + jec.getTrigger().getName()
              + " job= "
              + jec.getJobDetail().getName(),
          se);

      return false;
    }

    return true;
  }

  private boolean notifyTriggerListenersComplete(
      JobExecutionContext jec, CompletedExecutionInstruction instCode) {

    try {
      qs.notifyTriggerListenersComplete(jec, instCode);

    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify TriggerListener(s) of Job that was executed: "
              + "(error will be ignored). trigger= "
              + jec.getTrigger().getName()
              + " job= "
              + jec.getJobDetail().getName(),
          se);

      return false;
    }
    if (jec.getTrigger().getNextFireTime() == null) {
      qs.notifySchedulerListenersFinalized(jec.getTrigger());
    }

    return true;
  }

  private boolean completeTriggerRetryLoop(
      OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction instCode) {

    long count = 0;
    while (!shutdownRequested && !qs.isShuttingDown()) {
      try {
        Thread.sleep(15 * 1000L); // retry every 15 seconds (the db
        // connection must be failed)
        qs.notifyJobStoreJobComplete(trigger, jobDetail, instCode);
        return true;
      } catch (JobPersistenceException jpe) {
        if (count % 4 == 0) {
          qs.notifySchedulerListenersError(
              "An error occured while marking executed job complete (will continue attempts). job= '"
                  + jobDetail.getName()
                  + "'",
              jpe);
        }
      } catch (InterruptedException ignore) {
      }
      count++;
    }
    return false;
  }

  private boolean vetoedJobRetryLoop(
      OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction instCode) {

    while (!shutdownRequested) {
      try {
        Thread.sleep(5 * 1000L); // retry every 5 seconds (the db
        // connection must be failed)
        qs.notifyJobStoreJobVetoed(trigger, jobDetail, instCode);
        return true;
      } catch (JobPersistenceException jpe) {
        qs.notifySchedulerListenersError(
            "An error occured while marking executed job vetoed. job= '"
                + jobDetail.getName()
                + "'",
            jpe);
      } catch (InterruptedException ignore) {
      }
    }
    return false;
  }

  private static class VetoedException extends Exception {

    public VetoedException() {}
  }

  public String getJobName() {

    String jobName = firedTriggerBundle.getJobDetail().getName();
    String triggerName = firedTriggerBundle.getTrigger().getName();
    return jobName + " : " + triggerName;
  }
}
