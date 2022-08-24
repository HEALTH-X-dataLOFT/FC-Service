package eu.gaiax.difs.fc.core.service.filestore.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import eu.gaiax.difs.fc.core.pojo.ContentAccessor;
import eu.gaiax.difs.fc.core.service.sdstore.impl.ContentAccessorFile;
import eu.gaiax.difs.fc.core.util.HashUtils;
import static eu.gaiax.difs.fc.core.util.HashUtils.HASH_PATTERN;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileExistsException;

/**
 * Stores and retrieves files indentified by a hash.
 */
@Component
@Slf4j
public class FileStoreImpl {

  @Value("${datastore.file-path}")
  private String basePathName;

  private final Map<String, Path> storePaths = new HashMap<>();

  private Path getPathForStore(String storeName) {
    return storePaths.computeIfAbsent(storeName, n -> FileSystems.getDefault().getPath(basePathName, n));
  }

  private File getFileForStoreHash(String storeName, String hash) {
    Path storePath = getPathForStore(storeName);
    Path storeSubPath = storePath.resolve(hash.substring(0, 2));
    Path filePath = storeSubPath.resolve(hash);
    File file = filePath.toFile();
    return file;
  }

  private String validateFileName(String filename) {
    if (HASH_PATTERN.matcher(filename).matches()) {
      return filename;
    }
    log.debug("Filename is not a hash: {}", filename);
    return HashUtils.calculateSha256AsHex(filename);
  }

  public void storeFile(String storeName, String hash, ContentAccessor content) throws IOException {
    saveFile(storeName, hash, content, false);
  }

  public void replaceFile(String storeName, String hash, ContentAccessor content) throws IOException {
    saveFile(storeName, hash, content, true);
  }

  private void saveFile(String storeName, String hash, ContentAccessor content, boolean overwrite) throws IOException {
    File file = getFileForStoreHash(storeName, validateFileName(hash));
    if (file.exists() && !overwrite) {
      throw new FileExistsException("A file for the hash " + hash + " already exists.");
    }
    try ( FileOutputStream os = FileUtils.openOutputStream(file)) {
      IOUtils.copy(content.getContentAsStream(), os);
    }
  }

  public ContentAccessor readFile(String storeName, String hash) throws IOException {
    File file = getFileForStoreHash(storeName, validateFileName(hash));
    if (!file.exists()) {
      throw new FileNotFoundException("A file for the hash " + hash + " does not exist.");
    }
    return new ContentAccessorFile(file);
  }

  public void deleteFile(String storeName, String hash) throws IOException {
    File file = getFileForStoreHash(storeName, validateFileName(hash));
    if (!file.exists()) {
      throw new FileNotFoundException("A file for the hash " + hash + " does not exist.");
    }
    file.delete();
  }

  public Iterable<File> getFileIterable(String storeName) {
    return () -> {
      return new HashFileIterator(this, storeName);
    };
  }

  public static class HashFileIterator implements Iterator<File> {

    private Iterator<File> dirIterator;
    private Iterator<File> fileIterator;
    private File next;

    public HashFileIterator(FileStoreImpl parent, String storeName) {
      Path storePath = parent.getPathForStore(storeName);
      File[] subFileArray = storePath.toFile().listFiles();
      if (subFileArray != null) {
        dirIterator = Arrays.asList(subFileArray).iterator();
        next = traverseDirectory();
      }
    }

    /**
     * Traverses the directoryIterator until it finds a regular file in a
     * directory.
     *
     * @return The first file found while traversing the directories, or null if
     * no more files are available.
     */
    private File traverseDirectory() {
      while (dirIterator.hasNext()) {
        File nextDir = dirIterator.next();
        if (!nextDir.isDirectory()) {
          continue;
        }
        File[] hashFiles = nextDir.listFiles();
        if (hashFiles != null) {
          fileIterator = Arrays.asList(hashFiles).iterator();
          while (fileIterator.hasNext()) {
            final File nextFile = fileIterator.next();
            if (nextFile.isFile()) {
              return nextFile;
            }
          }
        }
      }
      return null;
    }

    /**
     * Fetch the next available File.
     *
     * @return The next file from the current directory, or the first file from
     * a future directory, or null if no more files are available.
     */
    private File nextFile() {
      while (fileIterator.hasNext()) {
        final File nextFile = fileIterator.next();
        if (nextFile.isFile()) {
          return nextFile;
        }
      }
      return traverseDirectory();
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public File next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      File retval = next;
      next = nextFile();
      return retval;
    }

  }
}
