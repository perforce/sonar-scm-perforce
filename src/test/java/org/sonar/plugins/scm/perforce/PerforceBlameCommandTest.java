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

import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.file.FileSpecOpStatus;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetRevisionHistoryOptions;
import com.perforce.p4java.server.IOptionsServer;
import org.junit.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;

import java.util.*;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Mockito.*;

public class PerforceBlameCommandTest {

  @Test
  public void testBlameUnSubmittedFile() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation = mock(IFileAnnotation.class);
    when(annotation.getDepotPath()).thenReturn(null);

    when(server.getFileAnnotations(anyListOf(IFileSpec.class), any(GetFileAnnotationsOptions.class))).thenReturn(Collections.singletonList(annotation));

    command.blame(mock(InputFile.class), server, blameOutput);

    verifyZeroInteractions(blameOutput);
  }

  @Test
  public void testBlameSubmittedFile() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation1 = mock(IFileAnnotation.class);
    when(annotation1.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation1.getLower()).thenReturn(3);

    IFileAnnotation annotation2 = mock(IFileAnnotation.class);
    when(annotation2.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation2.getLower()).thenReturn(3);

    IFileAnnotation annotation3 = mock(IFileAnnotation.class);
    when(annotation3.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation3.getLower()).thenReturn(4);

    IFileAnnotation annotation4 = mock(IFileAnnotation.class);
    when(annotation4.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation4.getLower()).thenReturn(5);

    Map<IFileSpec, List<IFileRevisionData>> result = new HashMap<>();
    IFileSpec fileSpecResult = mock(IFileSpec.class);
    when(fileSpecResult.getOpStatus()).thenReturn(FileSpecOpStatus.VALID);
    IFileRevisionData revision3 = mock(IFileRevisionData.class);
    when(revision3.getChangelistId()).thenReturn(3);
    Date date = new Date();
    when(revision3.getDate()).thenReturn(date);
    when(revision3.getUserName()).thenReturn("jhenry");
    result.put(fileSpecResult, Collections.singletonList(revision3));

    when(server.getRevisionHistory(anyListOf(IFileSpec.class), any(GetRevisionHistoryOptions.class))).thenReturn(result);
    when(server.getFileAnnotations(anyListOf(IFileSpec.class), any(GetFileAnnotationsOptions.class))).thenReturn(Arrays.asList(annotation1, annotation2, annotation3, annotation4));

    IChangelist changelist = mock(IChangelist.class);
    when(changelist.getDate()).thenReturn(date);
    when(changelist.getUsername()).thenReturn("bgates");
    when(server.getChangelist(4)).thenReturn(changelist);

    when(server.getChangelist(5)).thenReturn(null);

    InputFile inputFile = mock(InputFile.class);
    command.blame(inputFile, server, blameOutput);

    BlameLine line1 = new BlameLine().revision("3").date(date).author("jhenry");
    BlameLine line2 = new BlameLine().revision("3").date(date).author("jhenry");
    BlameLine line3 = new BlameLine().revision("4").date(date).author("bgates");
    BlameLine line4 = new BlameLine().revision("5").date(new Date(0)).author("unknown");
    verify(blameOutput).blameResult(inputFile, Arrays.asList(line1, line2, line3, line4));
    verify(server, times(1)).getChangelist(4);
  }

  @Test
  public void testBlameSubmittedFileLastEmptyLine() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation = mock(IFileAnnotation.class);
    when(annotation.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation.getLower()).thenReturn(3);

    when(server.getFileAnnotations(anyListOf(IFileSpec.class), any(GetFileAnnotationsOptions.class))).thenReturn(Collections.singletonList(annotation));

    IChangelist changelist = mock(IChangelist.class);
    Date date = new Date();
    when(changelist.getDate()).thenReturn(date);
    when(changelist.getUsername()).thenReturn("jhenry");
    when(server.getChangelist(3)).thenReturn(changelist);

    InputFile inputFile = mock(InputFile.class);
    when(inputFile.lines()).thenReturn(2);
    command.blame(inputFile, server, blameOutput);

    BlameLine line = new BlameLine().revision("3").date(date).author("jhenry");
    verify(blameOutput).blameResult(inputFile, Arrays.asList(line, line));
  }

}
