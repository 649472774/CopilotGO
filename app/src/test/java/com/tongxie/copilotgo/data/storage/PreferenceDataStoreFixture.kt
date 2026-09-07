package com.tongxie.copilotgo.data.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

// Select Okio explicitly: createWithPath also uses FileStorage in 1.1.7 and hits its Windows rename bug.
internal fun preferenceDataStoreFixture(file: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { file.absolutePath.toPath() }
        ),
        scope = scope
    )
