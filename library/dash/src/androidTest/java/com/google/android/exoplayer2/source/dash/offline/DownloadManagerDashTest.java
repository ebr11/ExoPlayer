/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.dash.offline;

import static com.google.android.exoplayer2.source.dash.offline.DashDownloadTestData.TEST_MPD;
import static com.google.android.exoplayer2.source.dash.offline.DashDownloadTestData.TEST_MPD_URI;
import static com.google.android.exoplayer2.testutil.CacheAsserts.assertCacheEmpty;
import static com.google.android.exoplayer2.testutil.CacheAsserts.assertCachedData;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.ConditionVariable;
import android.test.InstrumentationTestCase;
import android.test.UiThreadTest;
import com.google.android.exoplayer2.offline.DownloadManager;
import com.google.android.exoplayer2.offline.DownloaderConstructorHelper;
import com.google.android.exoplayer2.source.dash.manifest.RepresentationKey;
import com.google.android.exoplayer2.testutil.FakeDataSet;
import com.google.android.exoplayer2.testutil.FakeDataSource;
import com.google.android.exoplayer2.testutil.MockitoUtil;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.upstream.DataSource.Factory;
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.Util;
import java.io.File;

/**
 * Tests {@link DownloadManager}.
 */
public class DownloadManagerDashTest extends InstrumentationTestCase {

  private static final int ASSERT_TRUE_TIMEOUT = 1000;

  private SimpleCache cache;
  private File tempFolder;
  private FakeDataSet fakeDataSet;
  private DownloadManager downloadManager;
  private RepresentationKey fakeRepresentationKey1;
  private RepresentationKey fakeRepresentationKey2;
  private TestDownloadListener downloadListener;
  private File actionFile;

  @UiThreadTest
  @Override
  public void setUp() throws Exception {
    super.setUp();
    Context context = getInstrumentation().getContext();
    tempFolder = Util.createTempDirectory(context, "ExoPlayerTest");
    File cacheFolder = new File(tempFolder, "cache");
    cacheFolder.mkdir();
    cache = new SimpleCache(cacheFolder, new NoOpCacheEvictor());
    MockitoUtil.setUpMockito(this);
    fakeDataSet = new FakeDataSet()
        .setData(TEST_MPD_URI, TEST_MPD)
        .setRandomData("audio_init_data", 10)
        .setRandomData("audio_segment_1", 4)
        .setRandomData("audio_segment_2", 5)
        .setRandomData("audio_segment_3", 6)
        .setRandomData("text_segment_1", 1)
        .setRandomData("text_segment_2", 2)
        .setRandomData("text_segment_3", 3);

    fakeRepresentationKey1 = new RepresentationKey(0, 0, 0);
    fakeRepresentationKey2 = new RepresentationKey(0, 1, 0);
    actionFile = new File(tempFolder, "actionFile");
    createDownloadManager();
  }

  @UiThreadTest
  @Override
  public void tearDown() throws Exception {
    downloadManager.release();
    Util.recursiveDelete(tempFolder);
    super.tearDown();
  }

  // Disabled due to flakiness.
  public void disabledTestSaveAndLoadActionFile() throws Throwable {
    // Configure fakeDataSet to block until interrupted when TEST_MPD is read.
    fakeDataSet.newData(TEST_MPD_URI)
        .appendReadAction(new Runnable() {
          @SuppressWarnings("InfiniteLoopStatement")
          @Override
          public void run() {
            try {
              // Wait until interrupted.
              while (true) {
                Thread.sleep(100000);
              }
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          }
        })
        .appendReadData(TEST_MPD)
        .endData();

    // Run DM accessing code on UI/main thread as it should be. Also not to block handling of loaded
    // actions.
    runTestOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            // Setup an Action and immediately release the DM.
            handleDownloadAction(fakeRepresentationKey1, fakeRepresentationKey2);
            downloadManager.release();

            assertThat(actionFile.exists()).isTrue();
            assertThat(actionFile.length()).isGreaterThan(0L);

            assertCacheEmpty(cache);

            // Revert fakeDataSet to normal.
            fakeDataSet.setData(TEST_MPD_URI, TEST_MPD);

            createDownloadManager();
          }
        });

    // Block on the test thread.
    blockUntilTasksCompleteAndThrowAnyDownloadError();
    assertCachedData(cache, fakeDataSet);
  }

  public void testHandleDownloadAction() throws Throwable {
    handleDownloadAction(fakeRepresentationKey1, fakeRepresentationKey2);
    blockUntilTasksCompleteAndThrowAnyDownloadError();
    assertCachedData(cache, fakeDataSet);
  }

  public void testHandleMultipleDownloadAction() throws Throwable {
    handleDownloadAction(fakeRepresentationKey1);
    handleDownloadAction(fakeRepresentationKey2);
    blockUntilTasksCompleteAndThrowAnyDownloadError();
    assertCachedData(cache, fakeDataSet);
  }

  public void testHandleInterferingDownloadAction() throws Throwable {
    fakeDataSet
        .newData("audio_segment_2")
        .appendReadAction(
            new Runnable() {
              @Override
              public void run() {
                handleDownloadAction(fakeRepresentationKey2);
              }
            })
        .appendReadData(TestUtil.buildTestData(5))
        .endData();

    handleDownloadAction(fakeRepresentationKey1);

    blockUntilTasksCompleteAndThrowAnyDownloadError();
    assertCachedData(cache, fakeDataSet);
  }

  public void testHandleRemoveAction() throws Throwable {
    handleDownloadAction(fakeRepresentationKey1);

    blockUntilTasksCompleteAndThrowAnyDownloadError();

    handleRemoveAction();

    blockUntilTasksCompleteAndThrowAnyDownloadError();

    assertCacheEmpty(cache);
  }

  // Disabled due to flakiness.
  public void disabledTestHandleRemoveActionBeforeDownloadFinish() throws Throwable {
    handleDownloadAction(fakeRepresentationKey1);
    handleRemoveAction();

    blockUntilTasksCompleteAndThrowAnyDownloadError();

    assertCacheEmpty(cache);
  }

  public void testHandleInterferingRemoveAction() throws Throwable {
    final ConditionVariable downloadInProgressCondition = new ConditionVariable();
    fakeDataSet.newData("audio_segment_2")
        .appendReadAction(new Runnable() {
          @Override
          public void run() {
            downloadInProgressCondition.open();
          }
        })
        .appendReadData(TestUtil.buildTestData(5))
        .endData();

    handleDownloadAction(fakeRepresentationKey1);

    assertThat(downloadInProgressCondition.block(ASSERT_TRUE_TIMEOUT)).isTrue();

    handleRemoveAction();

    blockUntilTasksCompleteAndThrowAnyDownloadError();

    assertCacheEmpty(cache);
  }

  private void blockUntilTasksCompleteAndThrowAnyDownloadError() throws Throwable {
    downloadListener.blockUntilTasksCompleteAndThrowAnyDownloadError();
  }

  private void handleDownloadAction(RepresentationKey... keys) {
    downloadManager.handleAction(new DashDownloadAction(TEST_MPD_URI, false, null, keys));
  }

  private void handleRemoveAction() {
    downloadManager.handleAction(new DashDownloadAction(TEST_MPD_URI, true, null));
  }

  private void createDownloadManager() {
    Factory fakeDataSourceFactory = new FakeDataSource.Factory(null).setFakeDataSet(fakeDataSet);
    downloadManager =
        new DownloadManager(
            new DownloaderConstructorHelper(cache, fakeDataSourceFactory),
            1,
            3,
            actionFile.getAbsolutePath(),
            DashDownloadAction.DESERIALIZER);

    downloadListener = new TestDownloadListener(downloadManager, this);
    downloadManager.addListener(downloadListener);
    downloadManager.startDownloads();
  }

}
