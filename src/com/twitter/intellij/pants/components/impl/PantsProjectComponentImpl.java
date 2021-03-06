// Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.components.impl;

import com.intellij.ProjectTopics;
import com.intellij.compiler.server.BuildManagerListener;
import com.intellij.execution.RunManagerAdapter;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Function;
import com.intellij.util.messages.MessageBusConnection;
import com.twitter.intellij.pants.PantsBundle;
import com.twitter.intellij.pants.components.PantsProjectComponent;
import com.twitter.intellij.pants.execution.PantsMakeBeforeRun;
import com.twitter.intellij.pants.file.FileChangeTracker;
import com.twitter.intellij.pants.metrics.LivePantsMetrics;
import com.twitter.intellij.pants.metrics.PantsExternalMetricsListenerManager;
import com.twitter.intellij.pants.metrics.PantsMetrics;
import com.twitter.intellij.pants.service.project.PantsResolver;
import com.twitter.intellij.pants.settings.PantsProjectSettings;
import com.twitter.intellij.pants.settings.PantsSettings;
import com.twitter.intellij.pants.ui.PantsConsoleManager;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsUtil;
import icons.PantsIcons;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PantsProjectComponentImpl extends AbstractProjectComponent implements PantsProjectComponent {
  protected PantsProjectComponentImpl(Project project) {
    super(project);
  }

  @Override
  public void projectClosed() {
    PantsMetrics.report();
    FileChangeTracker.unregisterProject(myProject);
    PantsConsoleManager.unregisterConsole(myProject);
    super.projectClosed();
  }

  @Override
  public void initComponent() {
    super.initComponent();
    LivePantsMetrics.registerDumbModeListener(myProject);
  }

  @Override
  public void disposeComponent() {
    super.disposeComponent();
    LivePantsMetrics.unregisterDumbModeListener(myProject);
  }

  @Override
  public void projectOpened() {
    PantsMetrics.initialize();
    PantsConsoleManager.registerConsole(myProject);
    super.projectOpened();
    if (myProject.isDefault()) {
      return;
    }
    StartupManager.getInstance(myProject).registerPostStartupActivity(
      new Runnable() {
        @Override
        public void run() {
          /**
           * Set project to allow dynamic classpath for JUnit run. Still requires any junit run to specify dynamic classpath in
           * {@link com.twitter.intellij.pants.execution.PantsClasspathRunConfigurationExtension#updateJavaParameters}
           * IDEA's logic: {@link com.intellij.execution.configurations.CommandLineBuilder}
           */
          PropertiesComponent.getInstance(myProject).setValue("dynamic.classpath", true);

          if (PantsUtil.isSeedPantsProject(myProject)) {
            convertToPantsProject();
          }

          registerExternalBuilderListener();
          subscribeToRunConfigurationAddition();
          registerFileListener();
          final AbstractExternalSystemSettings pantsSettings = ExternalSystemApiUtil.getSettings(myProject, PantsConstants.SYSTEM_ID);
          final boolean resolverVersionMismatch =
            pantsSettings instanceof PantsSettings && ((PantsSettings) pantsSettings).getResolverVersion() != PantsResolver.VERSION;
          if (resolverVersionMismatch && PantsUtil.isPantsProject(myProject)) {
            final int answer = Messages.showYesNoDialog(
              myProject,
              PantsBundle.message("pants.project.generated.with.old.version", myProject.getName()),
              PantsBundle.message("pants.name"),
              PantsIcons.Icon
            );
            if (answer == Messages.YES) {
              PantsUtil.refreshAllProjects(myProject);
            }
          }
        }

        /**
         * To convert a seed Pants project to a full bloom pants project:
         * 1. Obtain the targets and project_path generated by `pants idea-plugin` from
         * workspace file `project.iws` via `PropertiesComponent` API.
         * 2. Generate a refresh spec based on the info above.
         * 3. Explicitly call {@link PantsUtil#refreshAllProjects}.
         */
        private void convertToPantsProject() {
          PantsExternalMetricsListenerManager.getInstance().logIsGUIImport(false);
          String serializedTargets = PropertiesComponent.getInstance(myProject).getValue("targets");
          String projectPath = PropertiesComponent.getInstance(myProject).getValue("project_path");
          if (serializedTargets == null || projectPath == null) {
            return;
          }

          /**
           * Generate the import spec for the next refresh.
           */
          final List<String> targetSpecs = PantsUtil.gson.fromJson(serializedTargets, PantsUtil.TYPE_LIST_STRING);
          final boolean loadLibsAndSources = true;
          final boolean enableIncrementalImport = false;
          final boolean useIdeaProjectJdk = false;
          final PantsProjectSettings pantsProjectSettings =
            new PantsProjectSettings(targetSpecs, projectPath, loadLibsAndSources, enableIncrementalImport, useIdeaProjectJdk);

          /**
           * Following procedures in {@link com.intellij.openapi.externalSystem.util.ExternalSystemUtil#refreshProjects}:
           * Make sure the setting is injected into the project for refresh.
           */
          ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(PantsConstants.SYSTEM_ID);
          if (manager == null) {
            return;
          }
          AbstractExternalSystemSettings settings = manager.getSettingsProvider().fun(myProject);
          settings.setLinkedProjectsSettings(Collections.singleton(pantsProjectSettings));
          PantsUtil.refreshAllProjects(myProject);

          prepareGuiComponents();

          // Subscribe the change of module addition, meaning when the project finishes resolves,
          // project SDK should be explicitly set.
          final MessageBusConnection connection = myProject.getMessageBus().connect(myProject);
          connection.subscribe(
            ProjectTopics.MODULES, new ModuleListener() {
              @Override
              public void moduleAdded(@NotNull Project project, @NotNull Module module) {
                ApplicationManager.getApplication().runWriteAction(() -> {
                  Optional<VirtualFile> pantsExecutable = PantsUtil.findPantsExecutable(project);
                  if (!pantsExecutable.isPresent()) {
                    return;
                  }

                  Optional<Sdk> sdk = PantsUtil.getDefaultJavaSdk(pantsExecutable.get().getPath());
                  if (!sdk.isPresent()) {
                    return;
                  }

                  ProjectRootManager.getInstance(project).setProjectSdk(sdk.get());
                });
              }

              @Override
              public void beforeModuleRemoved(@NotNull Project project, @NotNull Module module) {

              }

              @Override
              public void moduleRemoved(@NotNull Project project, @NotNull Module module) {

              }

              @Override
              public void modulesRenamed(
                @NotNull Project project, @NotNull List<Module> modules, @NotNull Function<Module, String> oldNameProvider
              ) {

              }
            }
          );
        }

        /**
         * Ensure GUI is set correctly because empty IntelliJ project (seed project in this case)
         * does not have these set by default.
         * 1. Make sure the project view is opened so view switch will follow.
         * 2. Pants tool window is initialized; otherwise no message can be shown when invoking `PantsCompile`.
         */
        private void prepareGuiComponents() {
          if (!ApplicationManager.getApplication().isUnitTestMode()) {
            if (ToolWindowManager.getInstance(myProject).getToolWindow("Project") != null) {
              ToolWindowManager.getInstance(myProject).getToolWindow("Project").show(null);
            }
            ExternalSystemUtil.ensureToolWindowInitialized(myProject, PantsConstants.SYSTEM_ID);
          }
        }

        private void subscribeToRunConfigurationAddition() {
          RunManagerEx.getInstanceEx(myProject).addRunManagerListener(
            new RunManagerAdapter() {
              @Override
              public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
                super.runConfigurationAdded(settings);
                if (!PantsUtil.isPantsProject(myProject) && !PantsUtil.isSeedPantsProject(myProject)) {
                  return;
                }
                PantsMakeBeforeRun.replaceDefaultMakeWithPantsMake(myProject, settings);
              }
            }
          );
        }
      }
    );
  }

  /**
   * This registers the listener when IDEA external builder process calls Pants.
   */
  private void registerExternalBuilderListener() {
    MessageBusConnection connection = myProject.getMessageBus().connect();
    BuildManagerListener buildManagerListener = new BuildManagerListener() {
      @Override
      public void beforeBuildProcessStarted(Project project, UUID sessionId) {

      }

      @Override
      public void buildStarted(Project project, UUID sessionId, boolean isAutomake) {

      }

      @Override
      public void buildFinished(Project project, UUID sessionId, boolean isAutomake) {
        /**
         * Sync files as generated sources may have changed after external compile,
         * specifically when {@link com.twitter.intellij.pants.jps.incremental.PantsTargetBuilder} finishes,
         * except this code is run within IDEA core, thus having access to file sync calls.
         */
        PantsUtil.synchronizeFiles();
      }
    };
    connection.subscribe(BuildManagerListener.TOPIC, buildManagerListener);
  }

  private void registerFileListener() {
    FileChangeTracker.registerProject(myProject);
  }
}
