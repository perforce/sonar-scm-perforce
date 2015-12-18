/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2014 SonarSource
 * sonarqube@googlegroups.com
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
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.scm.perforce;

import com.google.common.annotations.VisibleForTesting;
import com.perforce.p4java.core.file.FileSpecOpStatus;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.impl.generic.core.file.FileSpec;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import com.perforce.p4java.server.IOptionsServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;

public class PerforceBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(PerforceBlameCommand.class);
  private final PerforceConfiguration config;

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
    } finally {
      executor.clean();
    }
  }

  @VisibleForTesting
  void blame(InputFile inputFile, IOptionsServer server, BlameOutput output) {
    IFileSpec fileSpec = createFileSpec(inputFile);
    List<IFileAnnotation> fileAnnotations;
    List<IFileRevisionData> revisions;
    try {
      // Get file annotations
      List<IFileSpec> fileSpecs = Arrays.asList(fileSpec);
      fileAnnotations = server.getFileAnnotations(fileSpecs, getFileAnnotationOptions());
      if (fileAnnotations.size() == 1 && fileAnnotations.get(0).getDepotPath() == null) {
        LOG.debug("File " + inputFile + " is not submitted. Skipping it.");
        return;
      }
      // Get revision history
      Map<IFileSpec, List<IFileRevisionData>> revisionMap = server.getRevisionHistory(fileSpecs, getRevisionHistoryOptions());
      Entry<IFileSpec, List<IFileRevisionData>> singleEntry = revisionMap.entrySet().iterator().next();
      IFileSpec resultFileSpec = singleEntry.getKey();
      if (!FileSpecOpStatus.VALID.equals(resultFileSpec.getOpStatus()) && !FileSpecOpStatus.INFO.equals(resultFileSpec.getOpStatus())) {
        String statusMessage = resultFileSpec.getStatusMessage();
        LOG.debug("Unable to get revisions of file " + inputFile + " [" + statusMessage + "]. Skipping it.");
        return;
      }
      revisions = singleEntry.getValue();
    } catch (P4JavaException e) {
      throw new IllegalStateException(e.getLocalizedMessage(), e);
    }

    computeBlame(inputFile, output, fileAnnotations, revisions);
  }

  private void computeBlame(InputFile inputFile, BlameOutput output, List<IFileAnnotation> fileAnnotations, List<IFileRevisionData> revisions) {
    Map<Integer, Date> changelistDates = new HashMap<>();
    Map<Integer, String> changelistAuthors = new HashMap<>();
    for (IFileRevisionData revision : revisions) {
      changelistDates.put(revision.getChangelistId(), revision.getDate());
      changelistAuthors.put(revision.getChangelistId(), revision.getUserName());
    }

    List<BlameLine> lines = new ArrayList<>();
    for (IFileAnnotation fileAnnotation : fileAnnotations) {
      int lowerChangelistId = fileAnnotation.getLower();
      lines.add(new BlameLine()
        .revision(String.valueOf(lowerChangelistId))
        .date(changelistDates.get(lowerChangelistId))
        .author(changelistAuthors.get(lowerChangelistId)));
    }
    if (lines.size() == (inputFile.lines() - 1)) {
      // SONARPLUGINS-3097 Perforce do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);
  }

  /**
   * Creating options for revision history command (filelog).
   * @return options for requests.
   */
  private static GetRevisionHistoryOptions getRevisionHistoryOptions() {
    GetRevisionHistoryOptions options = new GetRevisionHistoryOptions();
    options.setIncludeInherited(true);
    return options;
  }

  /**
   * Creating options for file annotation command.
   * @return options for requests.
   */
  private static GetFileAnnotationsOptions getFileAnnotationOptions() {
    GetFileAnnotationsOptions options = new GetFileAnnotationsOptions();
    options.setUseChangeNumbers(true);
    options.setFollowBranches(true);
    options.setIgnoreWhitespaceChanges(true);
    return options;
  }

  /**
   * Creates file spec for the specified file taking into an account that we are interested in a revision that we have
   * in the current client workspace.
   * @param inputFile file to create file spec for
   */
  private static IFileSpec createFileSpec(InputFile inputFile) {
    IFileSpec fileSpec = new FileSpec(PerforceExecutor.encodeWildcards(inputFile.absolutePath()));
    fileSpec.setEndRevision(IFileSpec.HAVE_REVISION);
    return fileSpec;
  }

}
