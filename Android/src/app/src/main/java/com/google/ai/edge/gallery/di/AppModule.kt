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

package com.google.ai.edge.gallery.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.BenchmarkResultsSerializer
import com.google.ai.edge.gallery.CutoutsSerializer
import com.google.ai.edge.gallery.GalleryLifecycleProvider
import com.google.ai.edge.gallery.SettingsSerializer
import com.google.ai.edge.gallery.SkillsSerializer
import com.google.ai.edge.gallery.UserDataSerializer
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DefaultDataStoreRepository
import com.google.ai.edge.gallery.data.DefaultDownloadRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.proto.BenchmarkResults
import com.google.ai.edge.gallery.proto.CutoutCollection
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.proto.Skills
import com.google.ai.edge.gallery.proto.UserData
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.ai.edge.gallery.tts.KokoroTtsEngine
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

  // Provides the SettingsSerializer
  @Provides
  @Singleton
  fun provideSettingsSerializer(): Serializer<Settings> {
    return SettingsSerializer
  }

  // Provides the CutoutSerializer
  @Provides
  @Singleton
  fun provideCutoutSerializer(): Serializer<CutoutCollection> {
    return CutoutsSerializer
  }

  // Provides the UserDataSerializer
  @Provides
  @Singleton
  fun provideUserDataSerializer(): Serializer<UserData> {
    return UserDataSerializer
  }

  // Provides the BenchmarkResultsSerializer
  @Provides
  @Singleton
  fun provideBenchmarkResultsSerializer(): Serializer<BenchmarkResults> {
    return BenchmarkResultsSerializer
  }

  // Provides the SkillsSerializer
  @Provides
  @Singleton
  fun provideSkillsSerializer(): Serializer<Skills> {
    return SkillsSerializer
  }

  // Provides DataStore<Settings>
  @Provides
  @Singleton
  fun provideSettingsDataStore(
    @ApplicationContext context: Context,
    settingsSerializer: Serializer<Settings>,
  ): DataStore<Settings> {
    return DataStoreFactory.create(
      serializer = settingsSerializer,
      produceFile = { context.dataStoreFile("settings.pb") },
    )
  }

  // Provides DataStore<CutoutCollection>
  @Provides
  @Singleton
  fun provideCutoutsDataStore(
    @ApplicationContext context: Context,
    cutoutsSerializer: Serializer<CutoutCollection>,
  ): DataStore<CutoutCollection> {
    return DataStoreFactory.create(
      serializer = cutoutsSerializer,
      produceFile = { context.dataStoreFile("cutouts.pb") },
    )
  }

  // Provides DataStore<UserData>
  @Provides
  @Singleton
  fun provideUserDataDataStore(
    @ApplicationContext context: Context,
    userDataSerializer: Serializer<UserData>,
  ): DataStore<UserData> {
    return DataStoreFactory.create(
      serializer = userDataSerializer,
      produceFile = { context.dataStoreFile("user_data.pb") },
    )
  }

  // Provides DataStore<BenchmarkResults>
  @Provides
  @Singleton
  fun provideBenchmarkResultsDataStore(
    @ApplicationContext context: Context,
    benchmarkResultsSerializer: Serializer<BenchmarkResults>,
  ): DataStore<BenchmarkResults> {
    return DataStoreFactory.create(
      serializer = benchmarkResultsSerializer,
      produceFile = { context.dataStoreFile("benchmark_results.pb") },
    )
  }

  // Provides DataStore<Skills>
  @Provides
  @Singleton
  fun provideSkillsDataStore(
    @ApplicationContext context: Context,
    skillsSerializer: Serializer<Skills>,
  ): DataStore<Skills> {
    return DataStoreFactory.create(
      serializer = skillsSerializer,
      produceFile = { context.dataStoreFile("skills.pb") },
    )
  }

  // Provides AppLifecycleProvider
  @Provides
  @Singleton
  fun provideAppLifecycleProvider(): AppLifecycleProvider {
    return GalleryLifecycleProvider()
  }

  // Provides DataStoreRepository
  @Provides
  @Singleton
  fun provideDataStoreRepository(
    dataStore: DataStore<Settings>,
    userDataDataStore: DataStore<UserData>,
    cutoutsDataStore: DataStore<CutoutCollection>,
    benchmarkResultsStore: DataStore<BenchmarkResults>,
    skillsDataStore: DataStore<Skills>,
  ): DataStoreRepository {
    return DefaultDataStoreRepository(
      dataStore,
      userDataDataStore,
      cutoutsDataStore,
      benchmarkResultsStore,
      skillsDataStore,
    )
  }

  // Provides DownloadRepository
  @Provides
  @Singleton
  fun provideDownloadRepository(
    @ApplicationContext context: Context,
    lifecycleProvider: AppLifecycleProvider,
  ): DownloadRepository {
    return DefaultDownloadRepository(context, lifecycleProvider)
  }

  @Provides
  @Singleton
  fun provideMoshi(): Moshi {
    return Moshi.Builder().build()
  }

  // --- Voice Mode Providers ---

  private fun loadVoiceEmbedding(voicesFile: File, voiceName: String): FloatArray {
    val zipFile = java.util.zip.ZipFile(voicesFile)
    val entry = zipFile.getEntry("$voiceName.npy") ?: throw IllegalArgumentException("Voice entry $voiceName.npy not found in zip")
    zipFile.getInputStream(entry).use { inputStream ->
      val bytes = inputStream.readBytes()
      val headerLen = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
      val dataOffset = 10 + headerLen
      val floatCount = 510 * 256
      val floatArray = FloatArray(floatCount)
      val buffer = java.nio.ByteBuffer.wrap(bytes)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      buffer.position(dataOffset)
      for (i in 0 until floatCount) {
        floatArray[i] = buffer.float
      }
      return floatArray
    }
  }

  // Provides KokoroTtsEngine
  @Provides
  @Singleton
  fun provideKokoroTtsEngine(@ApplicationContext context: Context): KokoroTtsEngine {
    val modelPath = File(context.filesDir, "kokoro-v1.0.int8.onnx")
    val voicesPath = File(context.filesDir, "voices-v1.0.bin")

    // Extract assets to filesDir once per install (check existence first).
    if (!modelPath.exists()) {
      try {
        context.assets.open("kokoro-v1.0.int8.onnx").use { input ->
          modelPath.outputStream().use { output -> input.copyTo(output) }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    if (!voicesPath.exists()) {
      try {
        context.assets.open("voices-v1.0.bin").use { input ->
          voicesPath.outputStream().use { output -> input.copyTo(output) }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }

    var handle: Long = 0L
    try {
      val voiceData = loadVoiceEmbedding(voicesPath, "af_alloy")
      handle = KokoroTtsEngine.nativeCreate(modelPath.absolutePath, voiceData)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    return KokoroTtsEngine(handle)
  }

  @Provides
  @Singleton
  fun provideAudioTrack(): AudioTrack {
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(24000)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(
        AudioTrack.getMinBufferSize(
          24000,
          AudioFormat.CHANNEL_OUT_MONO,
          AudioFormat.ENCODING_PCM_16BIT
        ) * 4
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }
}
