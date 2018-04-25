package org.jenkinsci.plugins.workflow.cps;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.model.Queue;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies we can cope with all the bizarre quirks that occur when persistence fails or something unexpected happens.
 */
public class PersistenceProblemsTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    /** Verifies all the assumptions about a cleanly finished build. */
    static void assertCompletedCleanly(WorkflowRun run) throws Exception {
        if (run.isBuilding()) {
            System.out.println("Run initially building, going to wait a second to see if it finishes, run="+run);
            Thread.sleep(1000);
        }
        Assert.assertFalse(run.isBuilding());
        Assert.assertNotNull(run.getResult());
        FlowExecution fe = run.getExecution();
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                Assert.fail("FlowExecution still in FlowExecutionList!");
            }
        });
        Assert.assertTrue("Queue not empty after completion!", Queue.getInstance().isEmpty());

        if (fe instanceof CpsFlowExecution) {
            CpsFlowExecution cpsExec = (CpsFlowExecution)fe;
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertEquals(Boolean.TRUE, cpsExec.done);
            Assert.assertEquals(1, cpsExec.getCurrentHeads().size());
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertTrue(cpsExec.getCurrentHeads().get(0) instanceof FlowEndNode);
            Assert.assertTrue(cpsExec.startNodes == null || cpsExec.startNodes.isEmpty());
            Assert.assertFalse(cpsExec.blocksRestart());
        } else {
            System.out.println("WARNING: no FlowExecutionForBuild");
        }
    }

    static void assertCleanInProgress(WorkflowRun run) throws Exception {
        Assert.assertTrue(run.isBuilding());
        Assert.assertNull(run.getResult());
        FlowExecution fe = run.getExecution();
        AtomicBoolean hasExecutionInList = new AtomicBoolean(false);
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                hasExecutionInList.set(true);
            }
        });
        if (!hasExecutionInList.get()) {
            Assert.fail("Build completed but should still show in FlowExecutionList");
        }
        CpsFlowExecution cpsExec = (CpsFlowExecution)fe;
        Assert.assertFalse(cpsExec.isComplete());
        Assert.assertEquals(Boolean.FALSE, cpsExec.done);
        Assert.assertFalse(cpsExec.getCurrentHeads().get(0) instanceof FlowEndNode);
        Assert.assertTrue(cpsExec.startNodes != null && !cpsExec.startNodes.isEmpty());
    }

    /** Create and run a basic build before we mangle its persisted contents.  Stores job number to jobIdNumber index 0. */
    private static WorkflowRun runBasicBuild(JenkinsRule j, String jobName, int[] jobIdNumber, FlowDurabilityHint hint) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("echo 'doSomething'", true));
        job.addProperty(new DurabilityHintJobProperty(hint));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        jobIdNumber[0] = run.getNumber();
        assertCompletedCleanly(run);
        return run;
    }

    /** Create and run a basic build before we mangle its persisted contents.  Stores job number to jobIdNumber index 0. */
    private static WorkflowRun runBasicBuild(JenkinsRule j, String jobName, int[] jobIdNumber) throws Exception {
        return runBasicBuild(j, jobName, jobIdNumber, FlowDurabilityHint.MAX_SURVIVABILITY);
    }

    /** Sets up a running build that is waiting on input. */
    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber, FlowDurabilityHint durabilityHint) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("input 'pause'", true));
        job.addProperty(new DurabilityHintJobProperty(durabilityHint));

        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        ListenableFuture<FlowExecution> listener = run.getExecutionPromise();
        FlowExecution exec = listener.get();
        while(run.getAction(InputAction.class) != null) {  // Wait until input step starts
            Thread.sleep(50);
        }
        Thread.sleep(1000L);
        jobIdNumber[0] = run.getNumber();
        return run;
    }

    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber) throws Exception {
        return runBasicPauseOnInput(j, jobName, jobIdNumber, FlowDurabilityHint.MAX_SURVIVABILITY);
    }

    private static InputStepExecution getInputStepExecution(WorkflowRun run, String inputMessage) throws Exception {
        InputAction ia = run.getAction(InputAction.class);
        List<InputStepExecution> execList = ia.getExecutions();
        return execList.stream().filter(e -> inputMessage.equals(e.getInput().getMessage())).findFirst().orElse(null);
    }

    final  static String DEFAULT_JOBNAME = "testJob";

    /** Simulates something happening badly during final shutdown, which may cause build to not appear done. */
    @Test
    public void completedFinalFlowNodeNotPersisted() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);
            String finalId = run.getExecution().getCurrentHeads().get(0).getId();

            // Hack but deletes the file from disk
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            File f = new File(cpsExec.getStorageDir(), finalId+".xml");
            f.delete();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }
    /** Perhaps there was a serialization error breaking the FlowGraph persistence for non-durable mode. */
    @Test
    public void completedNoNodesPersisted() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);

            // Hack but deletes the FlowNodeStorage from disk
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            cpsExec.getStorageDir().delete();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    /** Simulates case where done flag was not persisted. */
    @Test
    public void completedButWrongDoneStatus() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicBuild(j, DEFAULT_JOBNAME, build);
            String finalId = run.getExecution().getCurrentHeads().get(0).getId();

            // Hack but deletes the FlowNodeStorage from disk
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            cpsExec.done = false;
            cpsExec.saveOwner();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Test
    public void inProgressNormal() throws Exception {
        final int[] build = new int[1];
        story.then( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCleanInProgress(run);
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Test
    @Ignore
    public void inProgressMaxPerfCleanShutdown() throws Exception {
        final int[] build = new int[1];
        story.then( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
            // SHOULD still save at end via persist-at-shutdown hooks
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCleanInProgress(run);
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

    @Test
    @Ignore
    public void inProgressMaxPerfDirtyShutdown() throws Exception {
        final int[] build = new int[1];
        final String[] finalNodeId = new String[1];
        story.thenWithHardShutdown( j -> {
            runBasicPauseOnInput(j, DEFAULT_JOBNAME, build, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
            // SHOULD still save at end via persist-at-shutdown hooks
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            Thread.sleep(1000);
            j.waitForCompletion(run);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.FAILURE, run.getResult());
            finalNodeId[0] = run.getExecution().getCurrentHeads().get(0).getId();
        });
        story.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(finalNodeId[0], run.getExecution().getCurrentHeads().get(0).getId());
            // JENKINS-50199, verify it doesn't try to resume again
        });
    }

    @Test
    public void inProgressButFlowNodesLost() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            cpsExec.getStorageDir().delete();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    /** Failed to save the Execution after the Program bumped the FlowHeads */
    @Test
    public void inProgressButProgramAheadOfExecutionLost() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            FlowHead currHead = cpsExec.heads.firstEntry().getValue();

            // Set the head to the previous value and SAVE
            currHead.head = cpsExec.getCurrentHeads().get(0).getParents().get(0);
            run.save();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    @Test
    /** Build okay but program fails to load */
    public void inProgressButProgramLoadFailure() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            cpsExec.getProgramDataFile().delete();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }

    @Test
    /** Build okay but then the start nodes get screwed up */
    public void inProgressButStartBlocksLost() throws Exception {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            CpsFlowExecution cpsExec = (CpsFlowExecution)(run.getExecution());
            cpsExec.startNodes.push(new FlowStartNode(cpsExec, cpsExec.iotaStr()));
            run.save();
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
        });
    }
}
