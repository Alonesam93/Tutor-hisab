# TutorDriveBackup

A small MIT-licensed MIT App Inventor extension for manual JSON backup and restore.

## Blocks

- `SaveText(fileName, content)`: opens Android's `ACTION_CREATE_DOCUMENT` picker and writes the supplied UTF-8 text to the chosen destination.
- `OpenText()`: opens Android's `ACTION_OPEN_DOCUMENT` picker and returns the selected UTF-8 text.
- `BackupSaved(uri)`: save completed.
- `BackupLoaded(content, uri)`: file read completed.
- `BackupError(operation, message)`: operation failed.
- `BackupCancelled(operation)`: user cancelled the picker.

The Android document picker can show Google Drive when the Drive app/provider is installed and the user is signed in. The extension does not request Google credentials or Drive API access.

## Build

This project uses Rush 2. Run `rush build` from this directory. The AIX is written to `out/`.

## Important

The extension only handles file selection and UTF-8 text I/O. The app must validate the JSON structure before restoring it. Do not replace existing local data until the user confirms a valid backup.
