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
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.impl.generic.core.file.FileSpec;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import com.perforce.p4java.server.IOptionsServer;

import java.util.*;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;

public class PerforceBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(PerforceBlameCommand.class);
  private final PerforceConfiguration config;
  private final Map<Integer, IFileRevisionData> revisionDataMap = new HashMap<>();
  private final Map<Integer, IChangelist> changelistMap = new HashMap<>();

  public PerforceBlameCommand(PerforceConfiguration config) {
    this.config = config;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    FileSystem fs = input.fileSystem();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    PerforceExecutor executor = new PerforceExecutor(config, fs.baseDir());
    try {
      for (InputFile inputFile : input.filesToBlame()) {
        blame(inputFile, executor.getServer(), output);
      }
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    } finally {
      executor.clean();
    }
  }

  @VisibleForTesting
  void blame(InputFile inputFile, IOptionsServer server, BlameOutput output) throws P4JavaException {
    IFileSpec fileSpec = createFileSpec(inputFile);
    List<IFileSpec> fileSpecs = Collections.singletonList(fileSpec);

    // Get file annotations
    List<IFileAnnotation> fileAnnotations = server.getFileAnnotations(fileSpecs, getFileAnnotationOptions());
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
        revisionDataMap.put(revisionData.getChangelistId(), revisionData);
      }
    }

    // Compute blame, getting changelist from server if not already retrieved
    List<BlameLine> lines = new ArrayList<>();
    for (IFileAnnotation fileAnnotation : fileAnnotations) {
      int lowerChangelistId = fileAnnotation.getLower();

      IFileRevisionData data = revisionDataMap.get(lowerChangelistId);
      if (data != null) {

        lines.add(new BlameLine()
            .revision(String.valueOf(lowerChangelistId))
            .date(data.getDate())
            .author(data.getUserName()));

      } else {
        // Sometimes they're missing from the revision history, so try to get it directly.

        LOG.debug("Changelist " + lowerChangelistId + " was not found in history for " + inputFile + ". It will be fetched directly.");
        IChangelist changelist = changelistMap.get(lowerChangelistId);
        if (changelist == null) {
          changelist = server.getChangelist(lowerChangelistId);
          if (changelist != null) { // sometimes even that can fail due to cross-server imports
            changelistMap.put(lowerChangelistId, changelist);
          }
        }

        if (changelist != null) {
          lines.add(new BlameLine()
              .revision(String.valueOf(lowerChangelistId))
              .date(changelist.getDate())
              .author(changelist.getUsername()));
        } else {
          // We really couldn't get any information for this changelist!
          // Unfortunately, blame information is required for every line...
          lines.add(new BlameLine()
              .revision(String.valueOf(lowerChangelistId))
              .date(new Date(0))
              .author("unknown"));
        }

      }

    }

    // SONARPLUGINS-3097: Perforce does not report blame on last empty line, so populate from last line with blame
    if (lines.size() == (inputFile.lines() - 1)) {
      lines.add(lines.get(lines.size() - 1));
    }

    output.blameResult(inputFile, lines);
  }

  /**
   * Creating options for file annotation command.
   * @return options for requests.
   */
  @Nonnull
  private static GetFileAnnotationsOptions getFileAnnotationOptions() {
    GetFileAnnotationsOptions options = new GetFileAnnotationsOptions();
    options.setUseChangeNumbers(true);
    options.setFollowBranches(true);
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
