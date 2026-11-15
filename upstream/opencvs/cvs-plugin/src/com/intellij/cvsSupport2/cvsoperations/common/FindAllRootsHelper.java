/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.function.Predicate;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.util.containers.ContainerUtil.map;

public class FindAllRootsHelper {
  private FindAllRootsHelper() { }

  public interface ScanProgress {
    void checkCanceled();

    void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound);
  }

  public static final class ScanResult {
    private final List<Path> myRoots;
    private final int myScannedDirectories;
    private final int myErrors;

    private ScanResult(@NotNull List<Path> roots, int scannedDirectories, int errors) {
      myRoots = List.copyOf(roots);
      myScannedDirectories = scannedDirectories;
      myErrors = errors;
    }

    public @NotNull List<Path> getRoots() {
      return myRoots;
    }

    public int getScannedDirectories() {
      return myScannedDirectories;
    }

    public int getErrors() {
      return myErrors;
    }
  }

  public static List<VirtualFile> findVersionedUnder(final List<? extends VirtualFile> coll) {
    final List<FilePath> pathList = map(coll, VcsUtil::getFilePath);
    final MyVisitor visitor = new MyVisitor();

    for (FilePath root : pathList) {
      final VirtualFile vf = root.getVirtualFile();
      if (vf == null) continue;
      VfsUtilCore.visitChildrenRecursively(vf, visitor);
    }

    return visitor.found;
  }

  /**
   * 从磁盘发现工作副本，不依赖 VFS。普通 Entries 子目录仍归父根；独立嵌套
   * 工作副本单独返回。扫描不跟随目录符号链接，也不进入 CVS 管理目录。
   */
  public static @NotNull ScanResult findVersionedPathsUnder(@NotNull Collection<? extends Path> containers,
                                                             @NotNull ScanProgress progress) {
    return findVersionedPathsUnder(containers, progress, path -> true);
  }

  public static @NotNull ScanResult findVersionedPathsUnder(@NotNull Collection<? extends Path> containers,
                                                            @NotNull ScanProgress progress,
                                                            @NotNull Predicate<Path> shouldVisit) {
    final Deque<Path> pending = new ArrayDeque<>();
    for (Path container : containers) {
      if (container == null) continue;
      final Path normalized = normalize(container);
      if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
        pending.addLast(normalized);
      }
    }

    final Set<Path> visited = new HashSet<>();
    final List<Path> found = new ArrayList<>();
    int scannedDirectories = 0;
    int errors = 0;

    while (!pending.isEmpty()) {
      progress.checkCanceled();
      final Path directory = pending.removeFirst();
      if (!visited.add(directory) || !shouldVisit.test(directory)) continue;

      scannedDirectories++;
      progress.onDirectory(directory, scannedDirectories, found.size());
      try {
        if (isCvsWorkingCopyRoot(directory) &&
            (containers.stream().anyMatch(path -> normalize(path).equals(directory)) || !isTrackedChild(directory))) {
          found.add(directory);
        }
      }
      catch (IOException | SecurityException e) {
        errors++;
      }

      final List<Path> children = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
        for (Path child : stream) {
          progress.checkCanceled();
          if (CvsUtil.CVS.equals(child.getFileName().toString())) continue;
          if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            children.add(normalize(child));
          }
        }
      }
      catch (IOException | SecurityException e) {
        errors++;
        continue;
      }
      children.sort(Comparator.comparing(Path::toString));
      pending.addAll(children);
    }

    found.sort(Comparator.comparing(Path::toString));
    return new ScanResult(found, scannedDirectories, errors);
  }

  public static boolean isAtOrUnderAnyRoot(@NotNull Path candidate, @NotNull Collection<? extends Path> roots) {
    final Path normalizedCandidate = normalize(candidate);
    for (Path root : roots) {
      if (normalizedCandidate.startsWith(normalize(root))) {
        return true;
      }
    }
    return false;
  }

  public static boolean isAncestorOfAnyRoot(@NotNull Path candidate, @NotNull Collection<? extends Path> roots) {
    final Path normalizedCandidate = normalize(candidate);
    for (Path root : roots) {
      final Path normalizedRoot = normalize(root);
      if (!normalizedCandidate.equals(normalizedRoot) && normalizedRoot.startsWith(normalizedCandidate)) {
        return true;
      }
    }
    return false;
  }

  static boolean isCvsWorkingCopyRoot(@NotNull Path directory) {
    final Path admin = directory.resolve(CvsUtil.CVS);
    return Files.isRegularFile(admin.resolve(CvsUtil.ENTRIES), LinkOption.NOFOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve(CvsUtil.CVS_ROOT_FILE), LinkOption.NOFOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve("Repository"), LinkOption.NOFOLLOW_LINKS);
  }

  static boolean isTrackedChild(Path directory) throws IOException {
    Path parent = directory.getParent();
    if (parent == null || !isCvsWorkingCopyRoot(parent)) return false;
    // LibreCVS upstream-patch: the admin files are decoded with each usual CVS encoding (the CVS settings'
    // encoding, UTF-8, the OS encoding) and the name matches if any of them yields it, so Cyrillic names
    // written in UTF-8 or windows-1251 are recognized. A malformed decoding simply does not match.
    String name = directory.getFileName().toString();
    String entry = "D/" + name + "/";
    byte[] entries = Files.readAllBytes(parent.resolve("CVS/Entries"));
    Path log = parent.resolve("CVS/Entries.Log");
    byte[] logBytes = Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(log) : new byte[0];
    byte[] parentRepositoryBytes = Files.readAllBytes(parent.resolve("CVS/Repository"));
    byte[] childRepositoryBytes = Files.readAllBytes(directory.resolve("CVS/Repository"));
    boolean tracked = false;
    boolean repositoryContinues = false;
    for (Charset charset : adminFileCharsets()) {
      // Entries.Log 里的增删由后条覆盖，避免 update 尚未合并日志时误判。
      boolean listed = false;
      for (String line : new String(entries, charset).split("\r?\n")) {
        if (line.startsWith(entry)) listed = true;
      }
      for (String line : new String(logBytes, charset).split("\r?\n")) {
        if (line.startsWith("A " + entry)) listed = true;
        if (line.startsWith("R " + entry)) listed = false;
      }
      tracked |= listed;
      String parentRepository = new String(parentRepositoryBytes, charset).trim();
      String childRepository = new String(childRepositoryBytes, charset).trim();
      repositoryContinues |= childRepository.equals(parentRepository.replaceAll("/+$", "") + "/" + name);
    }
    if (!tracked) return false;
    String parentRoot = Files.readString(parent.resolve("CVS/Root"), StandardCharsets.ISO_8859_1).trim();
    String childRoot = Files.readString(directory.resolve("CVS/Root"), StandardCharsets.ISO_8859_1).trim();
    return parentRoot.equals(childRoot) && repositoryContinues;
  }

  // LibreCVS upstream-patch: candidate encodings of CVS admin files, see isTrackedChild.
  private static @NotNull Set<Charset> adminFileCharsets() {
    Set<Charset> charsets = new LinkedHashSet<>();
    if (ApplicationManager.getApplication() != null) {
      String configured = CvsApplicationLevelConfiguration.getInstance().ENCODING;
      if (!CvsApplicationLevelConfiguration.DEFAULT.equals(configured) && Charset.isSupported(configured)) {
        charsets.add(Charset.forName(configured));
      }
    }
    charsets.add(StandardCharsets.UTF_8);
    String nativeEncoding = System.getProperty("native.encoding");
    if (nativeEncoding != null && Charset.isSupported(nativeEncoding)) {
      charsets.add(Charset.forName(nativeEncoding));
    }
    return charsets;
  }

  private static @NotNull Path normalize(@NotNull Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static class MyVisitor extends VirtualFileVisitor<Void> {
    private final List<VirtualFile> found = new ArrayList<>();

    @NotNull
    @Override
    public Result visitFileEx(@NotNull VirtualFile file) {
      if (!file.isDirectory()) {
        return CONTINUE;
      }
      if (CvsUtil.fileIsUnderCvsMaybeWithVfs(file)) {
        found.add(file);
        return SKIP_CHILDREN;
      }
      return CONTINUE;
    }
  }
}
