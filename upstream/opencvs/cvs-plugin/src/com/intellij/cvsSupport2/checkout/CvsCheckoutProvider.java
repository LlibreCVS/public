/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.checkout;

import com.intellij.openapi.progress.PerformInBackgroundOption;

import by.devlab.cvs.checkout.CheckoutCompletion;
import by.devlab.cvs.checkout.CheckoutProblems;
import by.devlab.cvs.checkout.CheckoutPrompts;
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration;
import com.intellij.cvsSupport2.cvsBrowser.CvsElement;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvsExecution.ModalityContext;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.cvsSupport2.ui.experts.checkout.CheckoutWizard;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckoutProvider;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CvsCheckoutProvider implements CheckoutProvider {
  @Override
  public void doCheckout(@NotNull final Project project, final Listener listener) {

    final CheckoutWizard checkoutWizard = new CheckoutWizard(project);
    if (!checkoutWizard.showAndGet()) {
      return;
    }
    final boolean useAlternateCheckoutPath = checkoutWizard.useAlternativeCheckoutLocation();
    final File checkoutDirectory = checkoutWizard.getCheckoutDirectory();

    final CvsElement[] selectedElements = checkoutWizard.getSelectedElements();
    final CvsHandler checkoutHandler = CommandCvsHandler.createCheckoutHandler(
      checkoutWizard.getConfigurationWithDateOrRevisionSettings(),
      collectCheckoutPaths(selectedElements),
      checkoutDirectory,
      useAlternateCheckoutPath,
      CvsApplicationLevelConfiguration.getInstance().MAKE_CHECKED_OUT_FILES_READONLY,
      PerformInBackgroundOption.DEAF);

    final CvsOperationExecutor executor = new CvsOperationExecutor(null);
    // LibreCVS upstream-patch: the problems go to the one dialog of CheckoutCompletion, not to error dialogs.
    executor.setShowErrors(false);
    executor.performActionSync(checkoutHandler, new CvsOperationExecutorCallback() {
      @Override
      public void executionFinished(boolean successfully) {
        refreshAfterCheckout(listener, selectedElements, checkoutDirectory, useAlternateCheckoutPath,
                             CheckoutProblems.Companion.of(checkoutHandler));
      }

      @Override
      public void executionFinishedSuccessfully() {
      }

      @Override
      public void executeInProgressAfterAction(ModalityContext modaityContext) {
      }
    });
  }

  public void refreshAfterCheckout(final Listener listener, final CvsElement[] selectedElements, final File checkoutDirectory,
                                   final boolean useAlternateCheckoutPath) {
    // LibreCVS upstream-patch: through CheckoutCompletion, which calls the listener off the EDT (design decision 19).
    refreshAfterCheckout(listener, selectedElements, checkoutDirectory, useAlternateCheckoutPath,
                         new CheckoutProblems(List.of(), null));
  }

  private static void refreshAfterCheckout(final Listener listener, final CvsElement[] selectedElements, final File checkoutDirectory,
                                           final boolean useAlternateCheckoutPath, final CheckoutProblems problems) {

    VirtualFileManager.getInstance().asyncRefresh(() -> ApplicationManager.getApplication().invokeLater(() -> {
      final List<File> directories = new ArrayList<>();
      for (CvsElement element : selectedElements) {
        directories.add(useAlternateCheckoutPath ? checkoutDirectory : new File(checkoutDirectory, element.getCheckoutPath()));
      }
      // LibreCVS upstream-patch: with problems the user decides whether the project is created.
      new CheckoutCompletion(CheckoutPrompts.Companion.getUI())
        .complete(String.join(", ", collectCheckoutPaths(selectedElements)), problems, directories, listener);
    }));
  }

  private static String[] collectCheckoutPaths(final CvsElement[] mySelectedElements) {
    final String[] checkoutPaths = new String[mySelectedElements.length];
    for (int i = 0; i < mySelectedElements.length; i++) {
      final CvsElement selectedElement = mySelectedElements[i];
      checkoutPaths[i] = selectedElement.getCheckoutPath();
    }
    return checkoutPaths;
  }

  @Override
  public String getVcsName() {
    return "_CVS";
  }
}
