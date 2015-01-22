/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2014 SonarSource
 * dev@sonar.codehaus.org
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

import com.perforce.p4java.core.file.FileSpecBuilder;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;

import java.util.List;
import java.util.Map;

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
        PerforceBlameResult p4Result = new PerforceBlameResult();
        List<IFileSpec> fileSpecs = createFileSpec(inputFile);
        try {
          // Get file annotations
          GetFileAnnotationsOptions getFileAnnotationsOptions = new GetFileAnnotationsOptions();
          List<IFileAnnotation> fileAnnotations = executor.getServer().getFileAnnotations(fileSpecs, getFileAnnotationsOptions);
          // Process the file annotations as blame lines
          p4Result.processBlameLines(fileAnnotations);
          // Get revision history
          GetRevisionHistoryOptions getRevisionHistoryOptions = new GetRevisionHistoryOptions();
          Map<IFileSpec, List<IFileRevisionData>> revisionMap = executor.getServer().getRevisionHistory(fileSpecs, getRevisionHistoryOptions);
          // Process the revision data map
          p4Result.processRevisionHistory(revisionMap);
        } catch (P4JavaException e) {
          throw new IllegalStateException(e.getLocalizedMessage(), e);
        }
        // Combine the results
        List<BlameLine> lines = p4Result.getBlameLines();
        for (int i = 0; i < lines.size(); i++) {
          BlameLine line = (BlameLine) lines.get(i);
          String revision = line.revision();
          line.author(p4Result.getAuthor(revision));
          line.date(p4Result.getDate(revision));
        }
        if (lines.size() == inputFile.lines() - 1) {
          // SONARPLUGINS-3097 Perforce do not report blame on last empty line
          lines.add(lines.get(lines.size() - 1));
        }
        output.blameResult(inputFile, lines);
      }
    } finally {
      executor.clean();
    }
  }

  /**
   * Creates file spec for the specified file taking into an account that we are interested in a revision that we have
   * in the current client workspace.
   * @param inputFile file to create file spec for
   * @return list of file specs containing the only one spec for the specified file.
   */
  private List<IFileSpec> createFileSpec(InputFile inputFile) {
    List<IFileSpec> fileSpecs = FileSpecBuilder
      .makeFileSpecList(new String[]{PerforceExecutor.encodeWildcards(inputFile.absolutePath())});
    fileSpecs.get(0).setEndRevision(IFileSpec.HAVE_REVISION);
    return fileSpecs;
  }

}
