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

import com.perforce.p4java.core.IChangelist;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.server.IOptionsServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import org.junit.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    when(server.getFileAnnotations(anyList(), any(GetFileAnnotationsOptions.class))).thenReturn(Collections.singletonList(annotation));

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

    when(server.getFileAnnotations(anyList(), any(GetFileAnnotationsOptions.class))).thenReturn(Arrays.asList(annotation, annotation));

    IChangelist changelist = mock(IChangelist.class);
    Date date = new Date();
    when(changelist.getDate()).thenReturn(date);
    when(changelist.getUsername()).thenReturn("jhenry");
    when(server.getChangelist(3)).thenReturn(changelist);

    InputFile inputFile = mock(InputFile.class);
    command.blame(inputFile, server, blameOutput);

    BlameLine line = new BlameLine().revision("3").date(date).author("jhenry");
    verify(blameOutput).blameResult(inputFile, Arrays.asList(line, line));
    verify(server, times(1)).getChangelist(3);
  }

  @Test
  public void testBlameSubmittedFileLastEmptyLine() throws Exception {
    BlameOutput blameOutput = mock(BlameOutput.class);
    IOptionsServer server = mock(IOptionsServer.class);
    PerforceBlameCommand command = new PerforceBlameCommand(mock(PerforceConfiguration.class));

    IFileAnnotation annotation = mock(IFileAnnotation.class);
    when(annotation.getDepotPath()).thenReturn("foo/bar/src/Foo.java");
    when(annotation.getLower()).thenReturn(3);

    when(server.getFileAnnotations(anyList(), any(GetFileAnnotationsOptions.class))).thenReturn(Collections.singletonList(annotation));

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
