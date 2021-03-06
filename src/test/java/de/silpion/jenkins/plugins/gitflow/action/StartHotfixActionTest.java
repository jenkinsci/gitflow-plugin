package de.silpion.jenkins.plugins.gitflow.action;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.silpion.jenkins.plugins.gitflow.action.buildtype.AbstractBuildTypeAction;
import de.silpion.jenkins.plugins.gitflow.action.buildtype.BuildTypeActionFactory;
import de.silpion.jenkins.plugins.gitflow.cause.StartHotfixCause;
import de.silpion.jenkins.plugins.gitflow.data.GitflowPluginData;
import de.silpion.jenkins.plugins.gitflow.data.RemoteBranch;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;

@PrepareForTest(BuildTypeActionFactory.class)
@RunWith(PowerMockRunner.class)
public class StartHotfixActionTest extends AbstractGitflowActionTest<StartHotfixAction<AbstractBuild<?, ?>>, StartHotfixCause> {

    private StartHotfixAction<AbstractBuild<?, ?>> testAction;

    @Mock
    private GitSCM scm;

    @Mock
    @SuppressWarnings("rawtypes")
    private AbstractBuildTypeAction buildTypeAction;

    @Mock
    private GitflowPluginData gitflowPluginData;

    @Mock
    private RemoteBranch remoteBranchHotfix;

    @Before
    @SuppressWarnings("unchecked assignment")
    public void setUp() throws Exception {
        super.setUp();

        // Mock calls to build wrapper descriptor.
        when(this.gitflowBuildWrapperDescriptor.getBranchType("master")).thenReturn("master");
        when(this.gitflowBuildWrapperDescriptor.getMasterBranch()).thenReturn("master");
        when(this.gitflowBuildWrapperDescriptor.getHotfixBranchPrefix()).thenReturn("hotfix/");

        // Mock the call to the BuildTypeAction.
        final List<String> changeFiles = Arrays.asList("pom.xml", "child1/pom.xml", "child2/pom.xml", "child3/pom.xml");
        mockStatic(BuildTypeActionFactory.class);
        when(BuildTypeActionFactory.newInstance(this.build, this.launcher, this.listener, "Start Hotfix")).thenReturn(this.buildTypeAction);
        when(this.buildTypeAction.updateVersion("1.0.2-SNAPSHOT")).thenReturn(changeFiles);

        // Mock calls to the GitflowPluginData object.
        final RemoteBranch remoteBranchMaster = new RemoteBranch("master");
        remoteBranchMaster.setLastBuildResult(Result.SUCCESS);
        when(this.gitflowPluginData.getRemoteBranch("master")).thenReturn(remoteBranchMaster);
        when(this.gitflowPluginData.getOrAddRemoteBranch("hotfix/1.0")).thenReturn(this.remoteBranchHotfix);
        when(this.build.getAction(GitflowPluginData.class)).thenReturn(this.gitflowPluginData);

        // Instanciate the test subject.
        final StartHotfixCause cause = new StartHotfixCause(createRemoteBranch("master", "1.0", "1.0.1"));
        this.testAction = new StartHotfixAction<AbstractBuild<?, ?>>(this.build, this.launcher, this.listener, this.git, cause);
    }

    private static RemoteBranch createRemoteBranch(final String branchName, final String baseReleaseVersion, final String lastReleaseVersion) {
        final RemoteBranch masterBranch = new RemoteBranch(branchName);
        masterBranch.setBaseReleaseVersion(baseReleaseVersion);
        masterBranch.setLastReleaseVersion(lastReleaseVersion);
        return masterBranch;
    }

    /** {@inheritDoc} */
    @Override
    protected StartHotfixAction<AbstractBuild<?, ?>> getTestAction() {
        return this.testAction;
    }

    /** {@inheritDoc} */
    @Override
    protected Map<String, String> setUpTestGetAdditionalBuildEnvVars() throws InterruptedException {
        final Map<String, String> expectedAdditionalBuildEnvVars = new HashMap<String, String>();

        // Mock call to Git client proxy.
        when(this.git.getHeadRev(anyString())).thenReturn(ObjectId.zeroId());
        when(this.gitflowBuildWrapperDescriptor.getBranchType(startsWith("hotfix/"))).thenReturn("hotfix");

        // Define expectations.
        expectedAdditionalBuildEnvVars.put("GIT_SIMPLE_BRANCH_NAME", "hotfix/1.0");
        expectedAdditionalBuildEnvVars.put("GIT_REMOTE_BRANCH_NAME", "origin/hotfix/1.0");
        expectedAdditionalBuildEnvVars.put("GIT_BRANCH_TYPE", "hotfix");

        return expectedAdditionalBuildEnvVars;
    }

    //**********************************************************************************************************************************************************
    //
    // Tests
    //
    //**********************************************************************************************************************************************************

    @Test
    public void testBeforeMainBuildInternal() throws Exception {

        //Run
        this.testAction.beforeMainBuildInternal();

        //Check
        verify(this.git).setGitflowActionName(this.testAction.getActionName());
        verify(this.git).checkoutBranch("hotfix/1.0", "origin/master");
        verify(this.git).add("pom.xml");
        verify(this.git).add("child1/pom.xml");
        verify(this.git).add("child2/pom.xml");
        verify(this.git).add("child3/pom.xml");
        verify(this.git).commit(any(String.class));
        verify(this.git, atLeastOnce()).push(anyString(), anyString());

        verify(this.gitflowPluginData).setDryRun(false);
        verify(this.gitflowPluginData).getRemoteBranch("master");
        verify(this.gitflowPluginData, atLeastOnce()).getOrAddRemoteBranch("hotfix/1.0");

        verify(this.remoteBranchHotfix, atLeastOnce()).setLastBuildResult(Result.SUCCESS);
        verify(this.remoteBranchHotfix, atLeastOnce()).setLastBuildVersion("1.0.2-SNAPSHOT");

        verifyNoMoreInteractions(this.git, this.gitflowPluginData);
    }
}
