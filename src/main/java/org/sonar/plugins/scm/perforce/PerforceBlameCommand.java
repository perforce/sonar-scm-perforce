/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.scm.perforce;

import com.google.common.annotations.VisibleForTesting;
import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.file.FileSpecOpStatus;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.impl.generic.core.file.FileSpec;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import com.perforce.p4java.server.IOptionsServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;

public class PerforceBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(PerforceBlameCommand.class);
  @VisibleForTesting
  static final int MAX_ATTEMPTS = 3;

  private final PerforceConfiguration config;
  private final Map<Integer, IFileRevisionData> revisionDataByChangelistId = new ConcurrentHashMap<>();
  private final Map<Integer, IChangelist> changelistCache = new ConcurrentHashMap<>();

  public PerforceBlameCommand(PerforceConfiguration config) {
    this.config = config;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    FileSystem fs = input.fileSystem();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    PerforceExecutor executor = new PerforceExecutor(config, fs.baseDir());
    try {
      ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
      List<Future<Void>> tasks = submitTasks(executorService, executor.getServer(), input, output);
      waitForTaskToComplete(executorService, tasks);
    } finally {
      executor.clean();
    }
  }

  private static void waitForTaskToComplete(ExecutorService executorService, List<Future<Void>> tasks) {
    executorService.shutdown();
    for (Future<Void> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException e) {
        // Unwrap ExecutionException
        throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private List<Future<Void>> submitTasks(ExecutorService executorService, IOptionsServer server, BlameInput input, BlameOutput output) {
    List<Future<Void>> tasks = new ArrayList<>();
    for (InputFile inputFile : input.filesToBlame()) {
      tasks.add(submitTask(executorService, server, inputFile, output));
    }
    return tasks;
  }

  private Future<Void> submitTask(ExecutorService executorService, final IOptionsServer server, final InputFile inputFile, final BlameOutput output) {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws P4JavaException {
        return tryBlame(inputFile, server, output);
      }
    });
  }

  @VisibleForTesting
  Void tryBlame(InputFile inputFile, IOptionsServer server, BlameOutput output) throws P4JavaException {
    int attempts = 0;
    GetFileAnnotationsOptions annotationOptions = getFileAnnotationOptions();
    while (attempts < MAX_ATTEMPTS) {
      try {
        blame(inputFile, server, output, annotationOptions);
        break;
      } catch (P4JavaException e) {
        annotationOptions.setFollowAllIntegrations(false);
        annotationOptions.setIgnoreWhitespaceChanges(false);
        if (++attempts >= MAX_ATTEMPTS) {
          throw e;
        }
      }
    }
    return null;
  }

  @VisibleForTesting
  void blame(InputFile inputFile, IOptionsServer server, BlameOutput output) throws P4JavaException {
    blame(inputFile, server, output, getFileAnnotationOptions());
  }

  private void blame(InputFile inputFile, IOptionsServer server, BlameOutput output, GetFileAnnotationsOptions fileAnnotationOptions) throws P4JavaException {
    IFileSpec fileSpec = createFileSpec(inputFile);
    List<IFileSpec> fileSpecs = Collections.singletonList(fileSpec);

    // Get file annotations
    List<IFileAnnotation> fileAnnotations = server.getFileAnnotations(fileSpecs, fileAnnotationOptions);
    if (fileAnnotations.size() == 1 && fileAnnotations.get(0).getDepotPath() == null) {
      LOG.debug("File " + inputFile + " is not submitted. Skipping it.");
      return;
    }

    // Get history of file
    Map<IFileSpec, List<IFileRevisionData>> revisionMap = server.getRevisionHistory(fileSpecs, getRevisionHistoryOptions());
    for (Map.Entry<IFileSpec, List<IFileRevisionData>> entry : revisionMap.entrySet()) {
      IFileSpec revisionFileSpec = entry.getKey();
      if (!FileSpecOpStatus.VALID.equals(revisionFileSpec.getOpStatus()) && !FileSpecOpStatus.INFO.equals(revisionFileSpec.getOpStatus())) {
        String statusMessage = fileSpec.getStatusMessage();
        LOG.debug("Unable to get revisions of file " + inputFile + " [" + statusMessage + "]. Skipping it.");
        return;
      }
      for (IFileRevisionData revisionData : entry.getValue()) {
        revisionDataByChangelistId.put(revisionData.getChangelistId(), revisionData);
      }
    }

    boolean handleCrlf = fileAnnotations.size() >= (inputFile.lines() - 1) * 2;
    List<BlameLine> lines = computeBlame(inputFile, server, fileAnnotations, handleCrlf);

    // SONARPLUGINS-3097: Perforce does not report blame on last empty line, so populate from last line with blame
    if (lines.size() == (inputFile.lines() - 1)) {
      lines.add(lines.get(lines.size() - 1));
    }

    output.blameResult(inputFile, lines);
  }

  /**
   * Compute blame, getting changelist from server if not already retrieved
   */
  private List<BlameLine> computeBlame(InputFile inputFile, IOptionsServer server, List<IFileAnnotation> fileAnnotations, boolean handleCrlf)
    throws ConnectionException, RequestException, AccessException {
    List<BlameLine> lines = new ArrayList<>();
    for (int i = 0; i < fileAnnotations.size(); i++) {
      IFileAnnotation fileAnnotation = fileAnnotations.get(i);
      int lowerChangelistId = fileAnnotation.getLower();

      BlameLine blameLine = blameLineFromHistory(lowerChangelistId);
      if (blameLine == null) {
        LOG.debug("Changelist " + lowerChangelistId + " was not found in history for " + inputFile + ". It will be fetched directly.");
        blameLine = blameLineFromChangeListDetails(server, lowerChangelistId);
      }

      if (blameLine == null) {
        // We really couldn't get any information for this changelist!
        // Unfortunately, blame information is required for every line...
        blameLine = new BlameLine()
          .revision(String.valueOf(lowerChangelistId))
          .date(new Date(0))
          .author("unknown");
      }

      lines.add(blameLine);

      if (handleCrlf
              && fileAnnotation.getLine() != null
              && fileAnnotation.getLine().endsWith("\r")
              && i + 1 < fileAnnotations.size()
              && fileAnnotations.get(i + 1).getLine() != null
              && fileAnnotations.get(i + 1).getLine().isEmpty()) {
        // Skip next annotation since it is a line ending corresponding to the current annotation
        i++;
      }
    }
    return lines;
  }

  @CheckForNull
  private BlameLine blameLineFromChangeListDetails(IOptionsServer server, int changelistId)
    throws ConnectionException, RequestException, AccessException {
    IChangelist changelist = changelistCache.get(changelistId);
    if (changelist == null) {
      changelist = server.getChangelist(changelistId);
      // sometimes even that can fail due to cross-server imports
      if (changelist != null) {
        changelistCache.put(changelistId, changelist);
      }
    }

    if (changelist != null) {
      return new BlameLine()
        .revision(String.valueOf(changelistId))
        .date(changelist.getDate())
        .author(changelist.getUsername());
    }
    return null;
  }

  @CheckForNull
  private BlameLine blameLineFromHistory(int changelistId) {
    IFileRevisionData data = revisionDataByChangelistId.get(changelistId);
    if (data != null) {
      return new BlameLine()
        .revision(String.valueOf(changelistId))
        .date(data.getDate())
        .author(data.getUserName());
    }
    return null;
  }

  /**
   * Creating options for file annotation command.
   * @return options for requests.
   */
  @Nonnull
  private static GetFileAnnotationsOptions getFileAnnotationOptions() {
    GetFileAnnotationsOptions options = new GetFileAnnotationsOptions();
    options.setUseChangeNumbers(true);
    options.setFollowAllIntegrations(true);
    options.setIgnoreWhitespaceChanges(true);
    return options;
  }

  /**
   * Creating options for revision history command (filelog).
   *
   * @return options for requests.
   */
  private static GetRevisionHistoryOptions getRevisionHistoryOptions() {
    GetRevisionHistoryOptions options = new GetRevisionHistoryOptions();
    options.setIncludeInherited(true);
    options.setLongOutput(true);
    options.setMaxRevs(1000);
    options.setOmitNonContributaryIntegrations(true);
    return options;
  }

  /**
   * Creates file spec for the specified file taking into an account that we are interested in a revision that we have
   * in the current client workspace.
   * @param inputFile file to create file spec for
   */
  @Nonnull
  private static IFileSpec createFileSpec(@Nonnull InputFile inputFile) {
    IFileSpec fileSpec = new FileSpec(PerforceExecutor.encodeWildcards(inputFile.absolutePath()));
    fileSpec.setEndRevision(IFileSpec.HAVE_REVISION);
    return fileSpec;
  }

}
