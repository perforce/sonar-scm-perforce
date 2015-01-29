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
 * This class handles the result from the Perforce annotate and revision history commands and constructs blame lines
 * for SonarQube.
 */
public class PerforceBlameResult {

  /**
   * Change lists
   */
  private List<String> changeLists = new ArrayList<String>();

  /** The dates. */
  private Map<String, Date> dates = new HashMap<String, Date>();

  /** The authors. */
  private Map<String, String> authors = new HashMap<String, String>();

  /**
   * Extracts file annotation info.
   *
   * @param fileAnnotations
   *            the file annotations
   */
  public void processBlameLines(List<IFileAnnotation> fileAnnotations) {
    if (fileAnnotations != null) {
      for (IFileAnnotation fileAnnotation : fileAnnotations) {
        if (fileAnnotation != null) {
          changeLists.add(String.valueOf(fileAnnotation.getUpper()));
        }
      }
    }
  }

  /**
   * Extracts dates, authors and revision number from revision history map.
   *
   * @param revisionMap
   *            the revision map
   */
  public void processRevisionHistory(Map<IFileSpec, List<IFileRevisionData>> revisionMap) {
    if (revisionMap != null) {
      for (Map.Entry<IFileSpec, List<IFileRevisionData>> entry : revisionMap.entrySet()) {
        List<IFileRevisionData> changes = entry.getValue();
        for (IFileRevisionData change : changes) {
          dates.put(String.valueOf(change.getChangelistId()), change.getDate());
          authors.put(String.valueOf(change.getChangelistId()), change.getUserName());
        }
      }
    }
  }

  /**
   * Combine results of annotation and revision history commands and return blame lines.
   * @return blane lines with revisionm date and author fields filled.
   */
  public List<BlameLine> createBlameLines() {
    List<BlameLine> lines = new ArrayList<BlameLine>(changeLists.size() + 1);

    for (String changeList : changeLists) {
      BlameLine line = new BlameLine();
      line.revision(changeList);
      line.date(dates.get(changeList));
      line.author(authors.get(changeList));
      lines.add(line);
    }

    return lines;
  }

}
