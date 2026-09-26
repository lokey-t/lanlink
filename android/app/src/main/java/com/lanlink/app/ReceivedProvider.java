package com.lanlink.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class ReceivedProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    try {
      File root = new File(getContext().getFilesDir(), "received").getCanonicalFile();
      File f = new File(root, uri.getLastPathSegment()).getCanonicalFile();
      if (!f.getParentFile().equals(root) || !f.isFile()) throw new FileNotFoundException();
      return f;
    } catch (IOException e) {
      throw new FileNotFoundException();
    }
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("只读");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = file(uri);
      MatrixCursor c =
          new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
      String name = f.getName();
      c.addRow(new Object[] {name.length() > 37 ? name.substring(37) : name, f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  public String getType(Uri uri) {
    String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());
    String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    return type == null ? "application/octet-stream" : type;
  }

  public Uri insert(Uri uri, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
