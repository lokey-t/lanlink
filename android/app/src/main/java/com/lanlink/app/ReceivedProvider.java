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
      java.util.List<String> segments=uri.getPathSegments();if(segments.size()>2&&segments.get(0).equals("staging")){String id=segments.get(1),relative=String.join("/",segments.subList(2,segments.size()));try{LinkService service=LinkService.instance;if(service!=null&&service.staging!=null)return service.staging.file(id,relative);if(!id.matches("[a-fA-F0-9-]{36}"))throw new IOException();StagingFiles.relative(relative);File base=new File(getContext().getFilesDir(),"staging");org.json.JSONArray rows=new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(new File(base,"index.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));boolean found=false;for(int i=0;i<rows.length();i++){org.json.JSONObject row=rows.getJSONObject(i);if(!id.equals(row.optString("id"))||!row.optString("state").equals("ready"))continue;org.json.JSONArray files=StagingFiles.validate(row.getJSONObject("offer")).getJSONArray("files");for(int j=0;j<files.length();j++)if(relative.equals(files.getJSONObject(j).getString("relative")))found=true;}File folder=new File(base,id).getCanonicalFile(),f=new File(folder,relative).getCanonicalFile();if(!found||!f.getPath().startsWith(folder.getPath()+File.separator)||!f.isFile())throw new IOException();return f;}catch(Exception e){throw new FileNotFoundException();}}
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
      c.addRow(new Object[] {uri.getPathSegments().get(0).equals("staging")?name:name.length() > 37 ? name.substring(37) : name, f.length()});
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
