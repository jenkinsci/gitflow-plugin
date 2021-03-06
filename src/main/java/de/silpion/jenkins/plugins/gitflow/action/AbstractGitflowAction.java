package de.silpion.jenkins.plugins.gitflow.action;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import de.silpion.jenkins.plugins.gitflow.GitflowBadgeAction;
import de.silpion.jenkins.plugins.gitflow.action.buildtype.AbstractBuildTypeAction;
import de.silpion.jenkins.plugins.gitflow.action.buildtype.BuildTypeActionFactory;
import de.silpion.jenkins.plugins.gitflow.cause.AbstractGitflowCause;
import de.silpion.jenkins.plugins.gitflow.data.GitflowPluginData;
import de.silpion.jenkins.plugins.gitflow.data.RemoteBranch;
import de.silpion.jenkins.plugins.gitflow.proxy.gitclient.GitClientProxy;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.git.Branch;
import org.apache.commons.collections.MapUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static de.silpion.jenkins.plugins.gitflow.GitflowBuildWrapper.getGitflowBuildWrapperDescriptor;

/**
 * Abstract base class for the different Gitflow actions to be executed - before and after the main build.
 *
 * @param <B> the build in progress.
 * @param <C> the <i>Gitflow</i> cause for the build in progress.
 * @author Marc Rohlfs, Silpion IT-Solutions GmbH - rohlfs@silpion.de
 */
public abstract class AbstractGitflowAction<B extends AbstractBuild<?, ?>, C extends AbstractGitflowCause> extends AbstractActionBase<B> {

    private static final String MSG_PATTERN_CLEANED_UP_WORKING_DIRECTORY = "Gitflow - %s: Cleaned up working/checkout directory%n";
    private static final String MSG_PATTERN_DELETED_BRANCH = "Gitflow - %s: Deleted branch %s%n";
    private static final String MSG_PATTERN_RESULT_TO_UNSTABLE = "Gitflow - %s: Changing result of successful build to unstable, because there are unstable branches: %s%n";

    private static final Function<Branch, String> BRANCH_TO_NAME_FUNCTION = new Function<Branch, String>() {

        /** {@inheritDoc} */
        public String apply(final Branch input) {
            return input == null ? null : input.getName();
        }
    };

    protected final C gitflowCause;

    protected final AbstractBuildTypeAction<?> buildTypeAction;
    protected final GitClientProxy git;

    protected GitflowPluginData gitflowPluginData;

    protected Map<String, String> additionalBuildEnvVars = new HashMap<String, String>();

    /**
     * Initialises a new Gitflow action.
     *
     * @param build the build that is in progress.
     * @param launcher can be used to launch processes for this build - even if the build runs remotely.
     * @param listener can be used to send any message.
     * @param git the Git client used to execute commands for the Gitflow actions.
     * @param gitflowCause the <i>Gitflow</i> cause for the build in progress.
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected AbstractGitflowAction(final B build, final Launcher launcher, final BuildListener listener, final GitClientProxy git, C gitflowCause) throws IOException, InterruptedException {
        super(build, listener);

        this.gitflowCause = gitflowCause;
        this.buildTypeAction = BuildTypeActionFactory.newInstance(build, launcher, listener, this.getActionName());

        this.git = git;
        this.git.setGitflowActionName(this.getActionName());

        // Prepare the action object that holds the data for the Gitflow plugin.
        this.gitflowPluginData = build.getAction(GitflowPluginData.class);
        if (this.gitflowPluginData == null) {

            // Try to find the action object in one of the previous builds and clone it to a new one.
            for (AbstractBuild<?, ?> previousBuild = build.getPreviousBuild(); previousBuild != null; previousBuild = previousBuild.getPreviousBuild()) {
                final GitflowPluginData previousGitflowPluginData = previousBuild.getAction(GitflowPluginData.class);
                if (previousGitflowPluginData != null) {

                    // Clone the Gitflow plugin data from the previous build.
                    try {
                        this.gitflowPluginData = previousGitflowPluginData.clone();
                    } catch (final CloneNotSupportedException cnse) {
                        throw new IOException("Cloning of " + previousGitflowPluginData.getClass().getName() + " is not supported but should be.", cnse);
                    }

                    // Collect remote branches that don't exist anymore.
                    final List<RemoteBranch> removeRemoteBranches = new LinkedList<RemoteBranch>();
                    for (final RemoteBranch remoteBranch : this.gitflowPluginData.getRemoteBranches()) {
                        if (this.git.getHeadRev(remoteBranch.getBranchName()) == null) {
                            removeRemoteBranches.add(remoteBranch);
                        }
                    }

                    // Remove the obsolte remote branches from the Gitflow plugin data.
                    if (!removeRemoteBranches.isEmpty()) {
                        this.gitflowPluginData.removeRemoteBranches(removeRemoteBranches, true);
                    }

                    break;
                }
            }

            // Create a new action object if none was found in the previous builds.
            if (this.gitflowPluginData == null) {
                this.gitflowPluginData = new GitflowPluginData();
            }

            // Add the new action object to the build.
            build.addAction(this.gitflowPluginData);
        }
        this.gitflowPluginData.setDryRun(gitflowCause.isDryRun());
    }

    /**
     * Runs the Gitflow actions that must be executed before the main build.
     *
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    public final void beforeMainBuild() throws IOException, InterruptedException {

        // Prepare the action object for the build badges to be displayed.
        final GitflowBadgeAction gitflowBadgeAction = new GitflowBadgeAction();
        gitflowBadgeAction.setGitflowActionName(this.getActionName());
        this.build.addAction(gitflowBadgeAction);

        // Clean up the checkout.
        this.cleanCheckout();

        // Execute the action-specific tasks.
        this.beforeMainBuildInternal();

        // Don't publish/deploy archives on Dry Run or if the main build is omitted.
        if (this.gitflowCause.isDryRun() || this.gitflowCause.isOmitMainBuild()) {
            this.buildTypeAction.preventArchivePublication(this.additionalBuildEnvVars);
        }
    }

    /**
     * Runs the Gitflow actions that must be executed after the main build.
     *
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    public final void afterMainBuild() throws IOException, InterruptedException {
        this.afterMainBuildInternal();

        // Mark successful build as unstable if there are unstable branches.
        final Result buildResult = this.getBuildResultNonNull();
        if (buildResult.isBetterThan(Result.UNSTABLE) && getGitflowBuildWrapperDescriptor().isMarkSuccessfulBuildUnstableOnBrokenBranches()) {
            final Map<Result, Collection<RemoteBranch>> unstableBranchesGroupedByResult = this.gitflowPluginData.getUnstableRemoteBranchesGroupedByResult();
            if (MapUtils.isNotEmpty(unstableBranchesGroupedByResult)) {
                this.consoleLogger.printf(MSG_PATTERN_RESULT_TO_UNSTABLE, this.getActionName(), unstableBranchesGroupedByResult.toString());
                this.build.setResult(Result.UNSTABLE);
            }
        }
    }

    /**
     * Runs the Gitflow actions that must be executed before the main build.
     *
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected abstract void beforeMainBuildInternal() throws IOException, InterruptedException;

    /**
     * Runs the Gitflow actions that must be executed after the main build.
     *
     * @throws IOException if an error occurs that causes/should cause the build to fail.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected abstract void afterMainBuildInternal() throws IOException, InterruptedException;

    /**
     * Adds the provided files to the Git stages - executing {@code git add [file1] [file2] ...}.
     * <p>
     * TODO Instead of adding the modified files manually, it would be more reliable to ask the Git client for the files that have been mofified and add those.
     * Unfortunately the {@link org.jenkinsci.plugins.gitclient.GitClient GitClient} class doesn't offer a method to get the modified files. We might file a
     * feature request and/or implement it ourselves and then do a pull request on GitHub. The method to be implemented should execute something like
     * {@code git ls-files -m}).
     *
     * @param files the files to be staged.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected void addFilesToGitStage(final List<String> files) throws InterruptedException {
        for (final String file : files) {
            this.git.add(file);
        }
    }

    /**
     * Before entering the {@link #beforeMainBuildInternal()}, the checkout directory is cleaned up so that there a no modified files.
     *
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected void cleanCheckout() throws InterruptedException {
        this.git.clean();
        this.consoleLogger.printf(MSG_PATTERN_CLEANED_UP_WORKING_DIRECTORY, this.getActionName());
    }

    /**
     * Deletes a branch.
     *
     * @param branchName the name of the branch.
     * @throws InterruptedException if the build is interrupted during execution.
     */
    protected void deleteBranch(final String branchName) throws InterruptedException {

        // Delete the remote branch locally and remotely.
        final Collection<String> localBranches = Collections2.transform(this.git.getBranches(), BRANCH_TO_NAME_FUNCTION);
        if (localBranches.contains(branchName)) {
            // The local branch might be missing when the action was executed in 'Dry Run' mode before.
            this.git.deleteBranch(branchName);
        }
        this.consoleLogger.printf(MSG_PATTERN_DELETED_BRANCH, this.getActionName(), branchName);
        this.git.push("origin", ":refs/heads/" + branchName);

        // Remove the recorded data of the deleted remote branch.
        final RemoteBranch remoteBranch = this.gitflowPluginData.getRemoteBranch(branchName);
        if (remoteBranch != null) {
            this.gitflowPluginData.removeRemoteBranch(remoteBranch, false);
        }
    }

    /**
     * Returns the action-specific name for console messages.
     *
     * @return the action-specific name for console messages.
     */
    public abstract String getActionName();

    public Map<String, String> getAdditionalBuildEnvVars() {
        return this.additionalBuildEnvVars;
    }
}
