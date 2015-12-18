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

import com.perforce.p4java.core.file.FileSpecOpStatus;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import com.perforce.p4java.server.IOptionsServer;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class PerforceBlameCommandTest {

  @Test
  public void testBlameUnSubmittedFile() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation = mock(IFileAnnotation.class);
    when(annotation.getDepotPath()).thenReturn(null);

    when(server.getFileAnnotations(anyList(), any(GetFileAnnotationsOptions.class))).thenReturn(Arrays.asList(annotation));

    command.blame(mock(InputFile.class), server, blameOutput);

    verifyZeroInteractions(blameOutput);
  }

  @Test
  public void testBlameSubmittedFile() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation = mock(IFileAnnotation.class);
    when(annotation.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation.getLower()).thenReturn(3);

    when(server.getFileAnnotations(anyList(), any(GetFileAnnotationsOptions.class))).thenReturn(Arrays.asList(annotation));

    Map<IFileSpec, List<IFileRevisionData>> result = new HashMap<>();
    IFileSpec fileSpecResult = mock(IFileSpec.class);
    when(fileSpecResult.getOpStatus()).thenReturn(FileSpecOpStatus.VALID);
    IFileRevisionData revision3 = mock(IFileRevisionData.class);
    when(revision3.getChangelistId()).thenReturn(3);
    Date date = new Date();
    when(revision3.getDate()).thenReturn(date);
    when(revision3.getUserName()).thenReturn("jhenry");
    result.put(fileSpecResult, Arrays.asList(revision3));

    when(server.getRevisionHistory(anyList(), any(GetRevisionHistoryOptions.class))).thenReturn(result);

    InputFile inputFile = mock(InputFile.class);
    command.blame(inputFile, server, blameOutput);

    verify(blameOutput).blameResult(inputFile, Arrays.asList(new BlameLine().revision("3").date(date).author("jhenry")));
  }

}
