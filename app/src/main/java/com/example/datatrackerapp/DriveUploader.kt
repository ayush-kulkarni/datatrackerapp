package com.example.datatrackerapp

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.util.Collections

/**
 * Handles uploading files to Google Drive.
 * - Initializes the Drive service for a specific Google account.
 * - Provides methods to upload files and manage a specific folder for app logs.
 */
class DriveUploader(context: Context, accountName: String) {

    private val drive: Drive

    init {
        Log.d("DriveUploader", "Initializing for user account: $accountName")
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        ).setSelectedAccountName(accountName)

        drive = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("DataTrackerApp").build()
    }

    /**
     * Deletes a file from Google Drive using its unique file ID.
     * - Checks if a file ID is provided.
     * - Attempts to delete the file and logs success or failure.
     */
//    suspend fun deletePreviousFile(fileId: String?) {
//        if (fileId.isNullOrEmpty()) {
//            Log.d("DriveUploader", "No previous file ID found to delete.")
//            return
//        }
//        try {
//            Log.d("DriveUploader", "Attempting to delete previous file with ID: $fileId")
//            drive.files().delete(fileId).execute()
//            Log.d("DriveUploader", "SUCCESS: Deleted previous file.")
//        } catch (e: Exception) {
//            // We log the error but don't fail the whole worker,
//            // as the main goal is to upload the new file.
//            Log.e("DriveUploader", "ERROR: Could not delete previous file.", e)
//        }
//    }

    /**
     * Uploads a local file to a specific folder in Google Drive.
     * - Finds or creates a folder named "AppLogs".
     * - Uploads the given file to this folder and returns the new file's ID.
     */
    suspend fun uploadFile(localFile: java.io.File): String? {
        Log.d("DriveUploader", "Starting upload process for file: ${localFile.name}")
        return try {
            val folderId = findOrCreateFolder(drive, "AppLogs")
            Log.d("DriveUploader", "Using Drive Folder ID: $folderId")

            val fileMetadata = File().apply {
                name = localFile.name
                parents = listOf(folderId)
            }
            val mediaContent = FileContent("text/plain", localFile)

            Log.d("DriveUploader", "Executing file upload request...")
            val uploadedFile = drive.files().create(fileMetadata, mediaContent).execute()
            Log.d("DriveUploader", "SUCCESS: File uploaded with ID: ${uploadedFile.id}")
            uploadedFile.id
        } catch (e: Exception) {
            Log.e("DriveUploader", "ERROR: File upload failed for ${localFile.name}", e)
            null
        }
    }

    /**
     * Finds an existing folder by name or creates it if it doesn't exist.
     * - Queries Google Drive for a folder with the specified name.
     * - If found, returns its ID; otherwise, creates the folder and returns the new ID.
     */
    private fun findOrCreateFolder(drive: Drive, folderName: String): String {
        Log.d("DriveUploader", "Searching for folder '$folderName' in user's Drive.")
        val query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
        val result = drive.files().list().setQ(query).setSpaces("drive").execute()

        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        Log.d("DriveUploader", "Folder '$folderName' not found. Creating it now.")
        val folderMetadata = File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = drive.files().create(folderMetadata).setFields("id").execute()
        return folder.id
    }
}