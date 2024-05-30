package com.searchscale.lucene.vectorsearch.jni;

import java.io.*;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.ProviderNotFoundException;

public class JavaUtils {
  public static void loadLibrary(String path) {
    try {
      loadLibraryFromJar("/" + path);
    } catch (IOException e) {
      try {
        com.github.fommil.jni.JniLoader.load(path);
      } catch (UnsatisfiedLinkError e1) {
        throw new RuntimeException(e1);
      }
    }
  }

  public static void loadLibraryFromJar(String path) throws IOException {
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("The path has to be absolute (start with '/').");
    }

    // Obtain filename from path
    String[] parts = path.split("/");
    String filename = (parts.length > 1) ? parts[parts.length - 1] : null;

    // Split filename to prefix and suffix (extension)
    String prefix = "";
    String suffix = null;
    if (filename != null) {
      parts = filename.split("\\.", 2);
      prefix = parts[0];
      suffix = (parts.length > 1) ? "." + parts[parts.length - 1] : null; // Thanks, davs! :-)
    }

    // Check if the filename is okay
    if (filename == null || prefix.length() < 3) {
      throw new IllegalArgumentException("The filename has to be at least 3 characters long.");
    }

    // Prepare temporary file
    File temp = File.createTempFile(prefix, suffix);

    if (!temp.exists()) {
      throw new FileNotFoundException("File " + temp.getAbsolutePath() + " does not exist.");
    }

    boolean tempFileIsPosix = false;
    try {
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        // Assume POSIX compliant file system, can be deleted after loading.
        tempFileIsPosix = true;
      }
    } catch (FileSystemNotFoundException | ProviderNotFoundException | SecurityException e) {
      // Assume non-POSIX, and don't delete until last file descriptor closed.
      e.printStackTrace();
    }

    // Prepare buffer for data copying
    byte[] buffer = new byte[1024];
    int readBytes;

    // Open and check input stream
    InputStream is = JavaUtils.class.getResourceAsStream(path);
    if (is == null) {
      temp.delete();
      throw new FileNotFoundException("File " + path + " was not found inside JAR.");
    }

    // Open output stream and copy data between source file in JAR and the temporary file
    OutputStream os = new FileOutputStream(temp);
    try {
      while ((readBytes = is.read(buffer)) != -1) {
        os.write(buffer, 0, readBytes);
      }
    } catch (Throwable e) {
      temp.delete();
      throw e;
    } finally {
      // If read/write fails, close streams safely before throwing an exception
      os.close();
      is.close();
    }

    try {
      // Load the library
      System.load(temp.getAbsolutePath());
    } finally {
      if (tempFileIsPosix)
        temp.delete();
      else
        temp.deleteOnExit();
    }
  }
}
