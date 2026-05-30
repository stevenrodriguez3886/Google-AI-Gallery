/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import android.app.Application
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  override fun onCreate() {
    // Attempt to load GPU delegate before any other initialization.
    try {
      System.loadLibrary("LiteRtGpu")
    } catch (e: UnsatisfiedLinkError) {
      Log.w("GalleryVoice", "LiteRT GPU delegate unavailable, falling back to CPU: ${e.message}")
    }

    super.onCreate()
    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)
  }
}
