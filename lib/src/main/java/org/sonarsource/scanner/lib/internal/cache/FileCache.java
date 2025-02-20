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
package org.sonarsource.scanner.lib.internal.cache;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.annotation.CheckForNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for managing Sonar batch file cache. You can put file into cache and
 * later try to retrieve them. The checksum is used to differentiate files (name is not secure as files may come
 * from different Sonar servers and have same name but be actually different, and same for SNAPSHOTs).
 */
public class FileCache {

  private static final Logger LOG = LoggerFactory.getLogger(FileCache.class);

  private final Path dir;
  private final Path tmpDir;
  private final FileHashes hashes;

  FileCache(Path dir, FileHashes fileHashes) {
    this.hashes = fileHashes;
    this.dir = createDir(dir, "user cache: ");
    LOG.info("User cache: {}", dir);
    this.tmpDir = createDir(dir.resolve("_tmp"), "temp dir");
  }

  public static FileCache create(Path sonarUserHome) {
    var dir = sonarUserHome.resolve("cache");
    return new FileCache(dir, new FileHashes());
  }

  public Path getDir() {
    return dir;
  }

  /**
   * Look for a file in the cache by its filename and checksum. If the file is not
   * present then return null.
   */
  @CheckForNull
  public Path get(String filename, String hash) {
    Path cachedFile = dir.resolve(hash).resolve(filename);
    if (Files.exists(cachedFile)) {
      return cachedFile;
    }
    LOG.debug("No file found in the cache with name {} and hash {}", filename, hash);
    return null;
  }

  @FunctionalInterface
  public interface Downloader {
    void download(String filename, Path toFile) throws IOException;
  }

  public CachedFile getOrDownload(String filename, String hash, String hashAlgorithm, Downloader downloader) {
    // Does not fail if another process tries to create the directory at the same time.
    Path hashDir = hashDir(hash);
    Path targetFile = hashDir.resolve(filename);
    if (Files.exists(targetFile)) {
      return new CachedFile(targetFile, true);
    }
    Path tempFile = newTempFile();
    download(downloader, filename, tempFile);
    String downloadedHash = hashes.of(tempFile.toFile(), hashAlgorithm);
    if (!hash.equals(downloadedHash)) {
      throw new HashMismatchException("INVALID HASH: File " + tempFile.toAbsolutePath() + " was expected to have hash " + hash
        + " but was downloaded with hash " + downloadedHash);
    }
    mkdirQuietly(hashDir);
    renameQuietly(tempFile, targetFile);
    return new CachedFile(targetFile, false);
  }

  private static void download(Downloader downloader, String filename, Path tempFile) {
    try {
      downloader.download(filename, tempFile);
    } catch (IOException e) {
      throw new IllegalStateException("Fail to download " + filename + " to " + tempFile, e);
    }
  }

  private static void renameQuietly(Path sourceFile, Path targetFile) {
    try {
      Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ex) {
      LOG.warn("Unable to rename {} to {}", sourceFile.toAbsolutePath(), targetFile.toAbsolutePath());
      LOG.warn("A copy/delete will be tempted but with no guarantee of atomicity");
      try {
        Files.move(sourceFile, targetFile);
      } catch (IOException e) {
        throw new IllegalStateException("Fail to move " + sourceFile.toAbsolutePath() + " to " + targetFile, e);
      }
    } catch (FileAlreadyExistsException e) {
      // File was probably cached by another process in the mean time
    } catch (IOException e) {
      throw new IllegalStateException("Fail to move " + sourceFile.toAbsolutePath() + " to " + targetFile, e);
    }
  }

  private Path hashDir(String hash) {
    return dir.resolve(hash);
  }

  private static void mkdirQuietly(Path hashDir) {
    try {
      Files.createDirectories(hashDir);
    } catch (IOException e) {
      throw new IllegalStateException("Fail to create cache directory: " + hashDir, e);
    }
  }

  private Path newTempFile() {
    try {
      return Files.createTempFile(tmpDir, "fileCache", null);
    } catch (IOException e) {
      throw new IllegalStateException("Fail to create temp file in " + tmpDir, e);
    }
  }

  private static Path createDir(Path dir, String debugTitle) {
    LOG.debug("Create: {}", dir);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to create " + debugTitle + dir.toString(), e);
    }
    return dir;
  }
}
