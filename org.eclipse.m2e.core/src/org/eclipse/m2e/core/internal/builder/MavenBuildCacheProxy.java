/********************************************************************************
 * Copyright (c) 2023, 2023 stephane.lacoin and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   stephane.lacoin - initial API and implementation
 ********************************************************************************/

package org.eclipse.m2e.core.internal.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.LifecycleNotFoundException;
import org.apache.maven.lifecycle.LifecyclePhaseNotFoundException;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.PhaseRecorder;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.prefix.NoPluginFoundForPrefixException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.project.MavenProject;

import org.eclipse.m2e.core.embedder.IComponentLookup;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenToolbox;
import org.eclipse.m2e.core.internal.project.registry.MavenProjectFacade;
import org.eclipse.m2e.core.internal.project.registry.ProjectRegistryManager;
import org.eclipse.m2e.core.project.IMavenProjectFacade;


/**
 * 
 */
public class MavenBuildCacheProxy {

  public static final MavenBuildCacheProxy INSTANCE = new MavenBuildCacheProxy();

  public List<MojoExecution> pendingSegment(IMavenProjectFacade projectFacade, List<MojoExecution> executions,
      IProgressMonitor monitor) throws CoreException {
    class BuildCacheMojosExecutionStrategyInvoker {

      private List<MojoExecution> invoke(IMavenExecutionContext ctx, IProgressMonitor monitor) throws CoreException {
        IMavenToolbox toolbox = IMavenToolbox.of(ctx);
        IComponentLookup componentLookup = toolbox.getComponentLookup().orElseThrow(IMavenToolbox.ERROR_NO_LOOKUP);

        try {

            // execution should have descriptors for saving
            class MojoExecutionSetupManager {
              final LifecycleExecutionPlanCalculator calculator = componentLookup
                  .lookup(LifecycleExecutionPlanCalculator.class);

              final MavenSession mavenSession = ctx.getSession();

              final MavenProject mavenProject = ctx.getSession().getCurrentProject();

              MojoExecutionSetupManager() throws CoreException {
                super();
              }

              void setup(MojoExecution execution) {
                try {
                  calculator.setupMojoExecution(mavenSession, mavenProject, execution);
                } catch(PluginNotFoundException | PluginResolutionException | PluginDescriptorParsingException
                    | MojoNotFoundException | InvalidPluginDescriptorException | NoPluginFoundForPrefixException
                    | LifecyclePhaseNotFoundException | LifecycleNotFoundException
                    | PluginVersionResolutionException ex) {
                  sneakyThrow(ex);
                }
              }

              @SuppressWarnings("unchecked")
              public static <T extends Exception, R> R sneakyThrow(Exception t) throws T {
                throw (T) t;
              }
            }
            executions.stream().forEach(new MojoExecutionSetupManager()::setup);

          class MojoExecutionCollector implements MojoExecutionRunner {

            final List<MojoExecution> pendings;

            final PhaseRecorder recorder = new PhaseRecorder(projectFacade.getMavenProject());

            MojoExecutionCollector(LifecycleExecutionPlanCalculator calculator, int size) {
              pendings = new ArrayList<>(size);
            }

            @Override
            public void run(MojoExecution execution) {
              recorder.observeExecution(execution);
              pendings.add(execution);
            }

          }
          MojoExecutionCollector collector = new MojoExecutionCollector(
              componentLookup.lookup(LifecycleExecutionPlanCalculator.class), executions.size());
          List<String> goals = ctx.getSession().getGoals();
          MavenExecutionRequest mavenRequest = ctx.getSession().getRequest();
          boolean patchedEmptyGoals = false;
          try {
            if(mavenRequest.getGoals().isEmpty()) {
              mavenRequest.setGoals(Collections.singletonList("install"));
              patchedEmptyGoals = true;
            }
            componentLookup.lookup(MojosExecutionStrategy.class).execute(executions, ctx.getSession(), collector);
          } finally {
            if(patchedEmptyGoals) {
              mavenRequest.setGoals(goals);
            }
          }
          return collector.pendings;
        } catch(LifecycleExecutionException ex) {
          throw new CoreException(Status.error("Strategy error", ex));
        }
      }

    }
    return projectFacade.createExecutionContext().execute(projectFacade.getMavenProject(),
        new BuildCacheMojosExecutionStrategyInvoker()::invoke, monitor);
  }

  public void saveBuild(IMavenProjectFacade projectFacade, IProgressMonitor monitor) throws CoreException {
    List<MojoExecution> mojoExecutions = ((MavenProjectFacade) projectFacade)
        .getExecutionPlan(ProjectRegistryManager.LIFECYCLE_DEFAULT, monitor);
    pendingSegment(projectFacade, mojoExecutions, monitor);
  }

}
