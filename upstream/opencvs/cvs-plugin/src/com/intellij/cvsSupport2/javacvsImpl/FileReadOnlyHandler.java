/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.cvsSupport2.javacvsImpl;

import com.intellij.openapi.util.SystemInfo;
import org.netbeans.lib.cvsclient.file.IFileReadOnlyHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

public class FileReadOnlyHandler implements IFileReadOnlyHandler{
  // LibreCVS upstream-patch: the read-only state is read and changed on disk. File.canWrite() looked up the file's
  // volume on every call on Windows (IntelliJ IDEA's NIO-backed java.io), which made it half of a checkout's time;
  // changing it through the VFS failed outside a write action for a file the VFS already knew. The plugin refreshes
  // the VFS after each CVS operation.
  @Override
  public void setFileReadOnly(File file, boolean readOnly) throws IOException {
    final DosFileAttributeView dos = SystemInfo.isWindows ? dosView(file.toPath()) : null;
    if (dos != null) {
      if (dos.readAttributes().isReadOnly() == readOnly) return;
      dos.setReadOnly(readOnly);
      return;
    }
    if (file.canWrite() != readOnly) return;
    //noinspection ResultOfMethodCallIgnored
    file.setWritable(!readOnly);
  }

  private static DosFileAttributeView dosView(Path path) {
    return Files.exists(path) ? Files.getFileAttributeView(path, DosFileAttributeView.class) : null;
  }
}
