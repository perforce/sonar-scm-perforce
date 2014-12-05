/*
 * SonarQube :: Plugins :: SCM :: Perforce
 * Copyright (C) 2009 ${owner}
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

import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import org.sonar.api.batch.scm.BlameLine;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class handles the result from the Perforce annotate command.
 */
public class P4BlameResult {

  /** The success. */
  private boolean success = true;

  /** The command output. */
  private String commandOutput = "";

  /** The lines. */
  private List<BlameLine> blameLines = new ArrayList<BlameLine>();

  /** The dates. */
  private Map<String, Date> dates = new HashMap<String, Date>();

  /** The authors. */
  private Map<String, String> authors = new HashMap<String, String>();

  /**
   * Gets the command output.
   *
   * @return the command output
   */
  public String getCommandOutput() {
    return commandOutput;
  }

  /**
   * Checks if is success.
   *
   * @return true, if is success
   */
  public boolean isSuccess() {
    return success;
  }

  /**
   * Sets the success.
   *
   * @param success
   *            the new success
   */
  public void setSuccess(boolean success) {
    this.success = success;
  }

  /**
   * Extracts file annotation info as BlameLine objects.
   *
   * @param fileAnnotations
   *            the file annotations
   */
  public void processBlameLines(List<IFileAnnotation> fileAnnotations) {
    if (fileAnnotations != null) {
      for (IFileAnnotation fileAnnotation : fileAnnotations) {
        if (fileAnnotation != null) {
          blameLines.add(new BlameLine().revision(String.valueOf(fileAnnotation.getUpper())));
        }
      }
    }
  }

  /**
   * Extracts dates and authors from revision history map.
   *
   * @param revisionMap
   *            the revision map
   */
  public void processRevisionHistory(
    Map<IFileSpec, List<IFileRevisionData>> revisionMap) {
    if (revisionMap != null) {
      for (Map.Entry<IFileSpec, List<IFileRevisionData>> entry : revisionMap
        .entrySet()) {
        List<IFileRevisionData> revisions = entry.getValue();
        for (IFileRevisionData revision : revisions) {
          dates.put(String.valueOf(revision.getRevision()),
            revision.getDate());
          authors.put(String.valueOf(revision.getRevision()),
            revision.getUserName());
        }
      }
    }
  }

  /**
   * Gets the blame lines.
   *
   * @return the blame lines
   */
  public List<BlameLine> getBlameLines() {
    return blameLines;
  }

  /**
   * Gets the author.
   *
   * @param revision
   *            the revision
   * @return the author
   */
  public String getAuthor(String revision) {
    return authors.get(revision);
  }

  /**
   * Gets the date.
   *
   * @param revision
   *            the revision
   * @return the date
   */
  public Date getDate(String revision) {
    return dates.get(revision);
  }

}
