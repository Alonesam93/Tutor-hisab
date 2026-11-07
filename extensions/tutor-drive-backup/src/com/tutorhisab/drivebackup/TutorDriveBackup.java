package com.tutorhisab.drivebackup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.appinventor.components.annotations.Extension;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.runtime.ActivityResultListener;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opens Android's document picker to save and restore Tutor Hisab JSON backups.
 * The user can choose Google Drive when it is available as a document provider.
 */
@Extension(
    description = "Manual JSON backup and restore using Android's file picker. " +
        "Choose Google Drive in the picker when it is available."
)
public final class TutorDriveBackup extends AndroidNonvisibleComponent
    implements ActivityResultListener {

  private enum Action {
    NONE, SAVE, OPEN
  }

  private static final int MAX_BACKUP_BYTES = 20 * 1024 * 1024;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private int requestCode;
  private Action pendingAction = Action.NONE;
  private String pendingContent = "";

  public TutorDriveBackup(ComponentContainer container) {
    super(container.$form());
    requestCode = form.registerForActivityResult(this);
  }

  /**
   * Opens the Android save picker. The user chooses a destination such as Google Drive.
   */
  @SimpleFunction(description = "Opens Android's Save picker for a JSON backup. " +
      "The user chooses the destination, such as Google Drive.")
  public void SaveText(String fileName, String content) {
    if (pendingAction != Action.NONE) {
      BackupError("save", "Another backup operation is already running.");
      return;
    }

    String safeName = fileName == null ? "" : fileName.trim();
    if (safeName.isEmpty()) {
      safeName = "TutorHisab_Backup.json";
    }
    safeName = safeName.replace("/", "_").replace("\\", "_");
    if (!safeName.toLowerCase(Locale.US).endsWith(".json")) {
      safeName += ".json";
    }

    pendingContent = content == null ? "" : content;
    pendingAction = Action.SAVE;

    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("application/json");
    intent.putExtra(Intent.EXTRA_TITLE, safeName);

    try {
      form.startActivityForResult(intent, requestCode);
    } catch (Exception e) {
      pendingAction = Action.NONE;
      pendingContent = "";
      BackupError("save", messageOf(e));
    }
  }

  /**
   * Opens Android's document picker so the user can select a JSON backup.
   */
  @SimpleFunction(description = "Opens Android's file picker to select a JSON backup, " +
      "including a backup stored in Google Drive.")
  public void OpenText() {
    if (pendingAction != Action.NONE) {
      BackupError("restore", "Another backup operation is already running.");
      return;
    }

    pendingAction = Action.OPEN;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("application/json");

    try {
      form.startActivityForResult(intent, requestCode);
    } catch (Exception e) {
      pendingAction = Action.NONE;
      BackupError("restore", messageOf(e));
    }
  }

  @Override
  public void resultReturned(int returnedRequestCode, int resultCode, Intent data) {
    if (returnedRequestCode != requestCode) {
      return;
    }

    Action action = pendingAction;
    String content = pendingContent;
    pendingAction = Action.NONE;
    pendingContent = "";

    if (action == Action.NONE) {
      return;
    }

    String operation = action == Action.SAVE ? "save" : "restore";

    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
      BackupCancelled(operation);
      return;
    }

    Uri uri = data.getData();
    if (action == Action.SAVE) {
      writeBackup(uri, content);
    } else {
      readBackup(uri);
    }
  }

  private void writeBackup(Uri uri, String content) {
    new Thread(() -> {
      try (OutputStream output = form.getContentResolver().openOutputStream(uri)) {
        if (output == null) {
          throw new IllegalStateException("Android could not open the selected destination.");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BACKUP_BYTES) {
          throw new IllegalArgumentException("Backup is larger than 20 MB.");
        }
        output.write(bytes);
        output.flush();
        mainHandler.post(() -> BackupSaved(uri.toString()));
      } catch (Exception e) {
        String message = messageOf(e);
        mainHandler.post(() -> BackupError("save", message));
      }
    }, "TutorHisab-Backup-Write").start();
  }

  private void readBackup(Uri uri) {
    new Thread(() -> {
      try (InputStream input = form.getContentResolver().openInputStream(uri);
           ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        if (input == null) {
          throw new IllegalStateException("Android could not open the selected backup file.");
        }

        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
          total += count;
          if (total > MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("Backup is larger than 20 MB.");
          }
          output.write(buffer, 0, count);
        }

        String content = new String(output.toByteArray(), StandardCharsets.UTF_8);
        mainHandler.post(() -> BackupLoaded(content, uri.toString()));
      } catch (Exception e) {
        String message = messageOf(e);
        mainHandler.post(() -> BackupError("restore", message));
      }
    }, "TutorHisab-Backup-Read").start();
  }

  private static String messageOf(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName()
        : message;
  }

  @SimpleEvent(description = "Fires after the backup file has been saved successfully. " +
      "Returns the selected document URI.")
  public void BackupSaved(String uri) {
    EventDispatcher.dispatchEvent(this, "BackupSaved", uri);
  }

  @SimpleEvent(description = "Fires after a backup file has been read. " +
      "Returns its UTF-8 text and document URI.")
  public void BackupLoaded(String content, String uri) {
    EventDispatcher.dispatchEvent(this, "BackupLoaded", content, uri);
  }

  @SimpleEvent(description = "Fires when a backup operation fails. " +
      "The operation is save or restore.")
  public void BackupError(String operation, String message) {
    EventDispatcher.dispatchEvent(this, "BackupError", operation, message);
  }

  @SimpleEvent(description = "Fires when the user cancels the file picker.")
  public void BackupCancelled(String operation) {
    EventDispatcher.dispatchEvent(this, "BackupCancelled", operation);
  }
}
