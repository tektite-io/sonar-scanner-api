/*
 * SonarScanner Java Library
 * Copyright (C) 2011-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonarsource.scanner.lib.internal.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;

import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

public final class CompressionUtils {

  private static final String ERROR_CREATING_DIRECTORY = "Error creating directory: ";

  // indexed by the standard binary representation of permission
  // if permission is 644; in binary 110 100 100
  // the positions of the ones (little endian) give the index of the permission in the list below
  private static final List<PosixFilePermission> POSIX_PERMISSIONS = List.of(
    PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ);
  private static final int MAX_MODE = (1 << POSIX_PERMISSIONS.size()) - 1;

  private CompressionUtils() {
    // utility class
  }

  /**
   * Unzip a file into a directory. The directory is created if it does not exist.
   *
   * @return the target directory
   */
  public static Path unzip(Path zip, Path toDir) throws IOException {
    return unzip(zip, toDir, ze -> true);
  }

  /**
   * Unzip a file to a directory.
   *
   * @param zip    the zip file. It must exist.
   * @param toDir  the target directory. It is created if needed.
   * @param filter filter zip entries so that only a subset of directories/files can be
   *               extracted to target directory.
   * @return the parameter {@code toDir}
   */
  public static Path unzip(Path zip, Path toDir, Predicate<ZipEntry> filter) throws IOException {
    Path targetDirNormalizedPath = toDir.normalize();
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (filter.test(entry)) {
          var target = toDir.resolve(entry.getName());

          verifyInsideTargetDirectory(entry.getName(), target, targetDirNormalizedPath);

          if (entry.isDirectory()) {
            throwExceptionIfDirectoryIsNotCreatable(target);
          } else {
            var parent = target.getParent();
            throwExceptionIfDirectoryIsNotCreatable(parent);
            copy(zipFile, entry, target);
          }
        }
      }
      return toDir;
    }
  }

  private static void verifyInsideTargetDirectory(String entryName, Path entryPath, Path targetDirNormalizedPath) {
    if (!entryPath.normalize().startsWith(targetDirNormalizedPath)) {
      // vulnerability - trying to create a file outside the target directory
      throw new IllegalStateException("Extracting an entry outside the target directory is not allowed: " + entryName);
    }
  }

  private static void throwExceptionIfDirectoryIsNotCreatable(Path to) throws IOException {
    try {
      Files.createDirectories(to);
    } catch (IOException e) {
      throw new IOException(ERROR_CREATING_DIRECTORY + to, e);
    }
  }

  private static void copy(ZipFile zipFile, ZipEntry entry, Path to) throws IOException {
    try (InputStream input = zipFile.getInputStream(entry);
      OutputStream fos = Files.newOutputStream(to)) {
      IOUtils.copy(input, fos);
    }
  }

  public static void extractTarGz(Path compressedFile, Path targetDir) throws IOException {
    Path targetDirNormalizedPath = targetDir.normalize();
    try (InputStream fis = Files.newInputStream(compressedFile);
      InputStream bis = new BufferedInputStream(fis);
      InputStream gzis = new GzipCompressorInputStream(bis);
      TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(gzis)) {
      TarArchiveEntry targzEntry;
      while ((targzEntry = tarArchiveInputStream.getNextEntry()) != null) {
        if (!tarArchiveInputStream.canReadEntryData(targzEntry)) {
          continue;
        }
        var target = targetDir.resolve(targzEntry.getName());

        verifyInsideTargetDirectory(targzEntry.getName(), target, targetDirNormalizedPath);

        if (targzEntry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          if (!Files.isDirectory(target.getParent())) {
            Files.createDirectories(target.getParent());
          }
          Files.copy(tarArchiveInputStream, target, StandardCopyOption.REPLACE_EXISTING);
          int mode = targzEntry.getMode();
          if (mode != 0 && !IS_OS_WINDOWS) {
            Set<PosixFilePermission> permissions = fromFileMode(mode);
            Files.setPosixFilePermissions(target, permissions);
          }
        }
      }
    }
  }

  static Set<PosixFilePermission> fromFileMode(final int fileMode) {
    if ((fileMode & MAX_MODE) != fileMode) {
      throw new IllegalStateException(
        "Invalid file mode '" + Integer.toOctalString(fileMode) + "'. File mode must be between 0 and " + MAX_MODE + " (" + Integer.toOctalString(MAX_MODE) + " in octal)");
    }

    final Set<PosixFilePermission> ret = EnumSet.noneOf(PosixFilePermission.class);

    for (int i = 0; i < POSIX_PERMISSIONS.size(); i++) {
      if ((fileMode & (1 << i)) != 0) {
        ret.add(POSIX_PERMISSIONS.get(i));
      }
    }

    return ret;
  }
}
