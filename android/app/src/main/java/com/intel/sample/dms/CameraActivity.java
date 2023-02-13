/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 * Copyright (C) 2022-2023 Intel Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package com.intel.sample.dms;

import android.Manifest;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.intel.sample.dms.env.Logger;

import java.nio.ByteBuffer;

import com.intel.sample.dms.R;

import com.intel.sample.dms.env.ImageUtils;

public abstract class CameraActivity extends AppCompatActivity
    implements OnImageAvailableListener,
        Camera.PreviewCallback,
        View.OnClickListener,
        EditSettingsDialogFragment.EditSettingsDialogListener{
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_INTERNET = Manifest.permission.INTERNET;//GRPC
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private boolean debug = false;
  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;
  protected String mIPText = "127.0.0.1";
  protected String mPortText = "50059";

  protected TextView frameValueTextView, inferenceTimeTextView, yawnsTextView, blinksTextView;

  protected ProgressBar distractionsProgressBar;
  protected ProgressBar drowsinessProgressBar;
  ToneGenerator toneG;
  boolean playTone = false;
  boolean toneThreadActive = false;
  Thread toneThread;


  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
    setContentView(R.layout.tfe_od_activity_camera);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayShowTitleEnabled(false);

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }

    //for settings
    Button settingsButton = (Button) findViewById(R.id.settings);
    settingsButton.setOnClickListener((View settingsview) -> {
      showSettingsDialog();
    });
    SharedPreferences sharedPref = CameraActivity.this.getPreferences(Context.MODE_PRIVATE);
    mIPText = sharedPref.getString(CameraActivity.this.getString(R.string.ip_text),mIPText);
    mPortText = sharedPref.getString(CameraActivity.this.getString(R.string.port_text),mPortText);

    frameValueTextView = findViewById(R.id.frame_info);
    inferenceTimeTextView = findViewById(R.id.inference_info);

    blinksTextView = findViewById(R.id.results_blinks);
    yawnsTextView = findViewById(R.id.results_yawns);
    distractionsProgressBar = (ProgressBar)findViewById(R.id.progress_distractions);
    drowsinessProgressBar = (ProgressBar)findViewById(R.id.progress_drowsiness);
  }

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /** Callback for android.hardware.Camera API */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
          if (previewSize != null) {
            previewHeight = previewSize.height;
            previewWidth = previewSize.width;
            rgbBytes = new int[previewWidth * previewHeight];
            onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
          }
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;
    yRowStride = previewWidth;

    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
          new Runnable() {
            @Override
            public void run() {
              ImageUtils.convertYUV420ToARGB8888(
                  yuvBytes[0],
                  yuvBytes[1],
                  yuvBytes[2],
                  previewWidth,
                  previewHeight,
                  yRowStride,
                  uvRowStride,
                  uvPixelStride,
                  rgbBytes);
            }
          };

      postInferenceCallback =
          new Runnable() {
            @Override
            public void run() {
              image.close();
              isProcessingFrame = false;
            }
          };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  private void showSettingsDialog() {
    FragmentManager fm = getFragmentManager();
    EditSettingsDialogFragment editSettingsDialogFragment = EditSettingsDialogFragment.newInstance(mIPText,mPortText);
    // SETS the target fragment for use later when sending results
    editSettingsDialogFragment.show(fm, "dialogue");
  }

  @Override
  public void onFinishEditDialog(String ipText, String portText) {
    mIPText = ipText;
    mPortText = portText;
    saveSettings();
  }

  private void saveSettings() {
    SharedPreferences sharedPref = CameraActivity.this.getPreferences(Context.MODE_PRIVATE);
    SharedPreferences.Editor editor = sharedPref.edit();
    editor.putString(getString(R.string.ip_text),mIPText);
    editor.putString(getString(R.string.port_text),mPortText);
    editor.commit();
    initChannel();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();
    initToneThread();

    handlerThread = new HandlerThread("inference");
    if (handlerThread != null) {
        handlerThread.start();
        if (handlerThread.getLooper() != null) {
            handler = new Handler(handlerThread.getLooper());
        }
    }
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);
    stoppedInference();
    toneThreadActive = false;
    synchronized (toneG) {
      toneG.notifyAll();
    }

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    stoppedInference();
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSIONS_REQUEST) {
      if (allPermissionsGranted(grantResults)) {
        setFragment();
      } else {
        requestPermission();
      }
    }
  }

  private static boolean allPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return (checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED) && (checkSelfPermission(PERMISSION_INTERNET) == PackageManager.PERMISSION_GRANTED);//GRPC
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
        Toast.makeText(
                CameraActivity.this,
                "Camera permission is required for this demo",
                Toast.LENGTH_LONG)
            .show();
      }
      if (shouldShowRequestPermissionRationale(PERMISSION_INTERNET)) {//GRPC
        Toast.makeText(
                CameraActivity.this,
                "INTERNET permission is required for remote Inference in this demo",
                Toast.LENGTH_LONG)
                .show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_INTERNET}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
            (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();

    Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
          CameraConnectionFragment.newInstance(
              new CameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;
    } else {
      fragment =
          new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  @Override
  public void onClick(View v) {
  }

  protected  void stoppedInference() {
    synchronized (toneG) {
      playTone = false;
    }
  }
  protected void showFrameInfo(String frameInfo) {
    frameValueTextView.setText(frameInfo);
  }

  protected void showInference(double inferenceTime, int distractions, int drowsiness, int blinks, int yawns) {
    inferenceTimeTextView.setText(Double.toString(inferenceTime));
    blinksTextView.setText(Long.toString(blinks));
    yawnsTextView.setText(Long.toString(yawns));
    if(distractions > 30) {
      distractionsProgressBar.getProgressDrawable().setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
    } else {
      distractionsProgressBar.getProgressDrawable().setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN);
    }
    if(drowsiness > 30) {
      drowsinessProgressBar.getProgressDrawable().setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
    } else {
      drowsinessProgressBar.getProgressDrawable().setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN);
    }
    distractionsProgressBar.setProgress(distractions);
    drowsinessProgressBar.setProgress(drowsiness);
    synchronized (toneG) {
      if (distractions > 50 || drowsiness > 50) {
        if(!playTone) {
          playTone = true;
          toneG.notifyAll();
        }
      } else {
        playTone = false;
      }
    }
  }

  void initToneThread() {
    toneThread = new Thread(new Runnable() {
      @Override
      public void run() {
        LOGGER.d("Starting WARNING TONE thread");
        synchronized (toneG) {
          while (toneThreadActive) {
            try {
              toneG.wait();
              if (playTone) {
                LOGGER.d("Starting WARNING TONE ");
                while (playTone) {
                  toneG.startTone(ToneGenerator.TONE_SUP_INTERCEPT, 1100);
                  try {
                    toneG.wait(1000);
                  } catch (InterruptedException e) {
                    LOGGER.d("WARNING TONE thread Interrupted");
                  }
                  toneG.stopTone();
                }
                LOGGER.d("Stopped WARNING TONE ");
              }
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }
        toneG.stopTone();// Stopping any tone during interrupt
        LOGGER.d("Ending WARNING TONE thread");
      }
    });
    toneThreadActive = true;
    toneThread.start();
  }

  protected abstract void processImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  protected abstract void setUseNNAPI(boolean isChecked);

  protected abstract void initChannel();
}
