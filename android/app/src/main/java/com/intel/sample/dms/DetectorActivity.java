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

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.intel.sample.dms.R;

import com.intel.sample.dms.customview.OverlayView;
import com.intel.sample.dms.env.BorderedText;
import com.intel.sample.dms.env.ImageUtils;
import com.intel.sample.dms.env.Logger;
import com.intel.sample.dms.tracking.MultiBoxTracker;

//[[ GRPC
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import com.google.protobuf.ByteString;
import com.intel.examples.dms.DetectionGrpc;
import com.intel.examples.dms.PredictionOrBuilder;
import com.intel.examples.dms.ReplyStatus;
import com.intel.examples.dms.RequestBytes;
import com.intel.examples.dms.Prediction;
import com.intel.examples.dms.RequestString;
//]] GRPC

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  private static final String TAG = "DetectorActivity";
  private static final float SCORE_THRESHOLD = 0.49f; //MINIMUM_CONFIDENCE_TF_OD_API - 0.01
  ManagedChannel mChannel = null; //GRPC
  HashMap<String, ManagedChannel> mChannelMap
          = new HashMap<>();
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(1280, 720);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private BorderedText borderedText;

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new OverlayView.DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);

    //[[ GRPC
    initChannel();

    //]] GRPC
  }

  @Override
  protected void initChannel() {
    String grpcAddr = mIPText;
    String grpcPort = mPortText;

    String channelKey = (grpcAddr+grpcPort).trim();
    if(mChannelMap.containsKey(channelKey)) {
      mChannel = mChannelMap.get(channelKey);
    } else {
      mChannel = null;
    }

    if(mChannel != null) {
      ConnectivityState cState = mChannel.getState(true);
      Log.d(TAG, "A gRPC channel is already available, state : " + cState.toString());
      if(cState != ConnectivityState.READY) {
        Log.i(TAG, "gRPC channel not READY : Shutdown");
        mChannel.shutdown();
        mChannel = null;
        mChannelMap.remove(channelKey);
      }
    }
    if (mChannel == null) {
      Log.i(TAG, String.format("Creating gRPC channel IP:Port - %s:%s", grpcAddr, grpcPort));
      if (!Patterns.IP_ADDRESS.matcher(grpcAddr).matches() || !TextUtils.isDigitsOnly(grpcPort)) {
        Toast.makeText(
                DetectorActivity.this,
                "Invalid IP:Port provided!",
                Toast.LENGTH_LONG)
                .show();
        Log.e(TAG, String.format("Invalid IP:Port provided for gRPC - %s:%s", grpcAddr, grpcPort));
        return;
      }
      mChannel = initGrpc(grpcAddr, grpcPort);
    }
  }

  //[[ GRPC
  private ManagedChannel initGrpc(String host, String portStr) {
    ManagedChannel channel = null;
    String channelKey = (host+portStr).trim();
    int port = Integer.valueOf(portStr);
    DetectionGrpc.DetectionBlockingStub stub = null;
    try {
      channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
      mChannelMap.put(channelKey, channel);
      ConnectivityState t = channel.getState(true);
      Log.i(TAG, "gRPC Channel state : " + t.toString());
    } catch (Exception e) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      pw.flush();
      Log.i(TAG, "gRPC channel exception: \n" + String.format("Failed... : %n%s", sw));
    }
    return channel;
  }

  public Classifier.Recognition getRecognitions(Prediction prediction) {
    Trace.beginSection("getRecognitions");
    float left = prediction.getX();
    float right = left+ prediction.getWidth();
    float top = prediction.getY();
    float bottom = top + prediction.getHeight();
    Log.i(TAG, "remoteInfer ObjectDetection RectangleCords: " +
            "Left: " + left +
            "Right: " + right +
            "Top: " + top +
            "Bottom: " + bottom
    );
        final RectF detection =
                new RectF(left, top, right, bottom);

        Classifier.Recognition r = new Classifier.Recognition(
                "" + 1,
                "SampleDetection",
                0.9f,
                detection);
    Trace.endSection(); // "getRecognitions"
    return r;
  }

  private Classifier.Recognition remoteInfer(Bitmap image) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    image.compress(Bitmap.CompressFormat.JPEG, 100, stream);

    ByteString imgBytes = ByteString.copyFrom(stream.toByteArray());
    try {
      Log.i(TAG, "remoteInfer dms imgBytes Size: " + imgBytes.size());
      Log.i(TAG, "remoteInfer gRPC Channel state : " + mChannel.getState(true).toString());
      if(mChannel.getState(true) != ConnectivityState.READY) {
        runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                      showInference(0, 0, 0, 0, 0);
                  }
                });
        return null;
      }
      RequestBytes request = RequestBytes.newBuilder().setData(imgBytes).build();
      DetectionGrpc.DetectionBlockingStub stub = DetectionGrpc.newBlockingStub(mChannel);
      RequestString requestStr =  RequestString.newBuilder().setValue("").build();
      long startTime = SystemClock.uptimeMillis();
      stub.sendFrame(request);
      Prediction prediction = stub.getPredictions(requestStr);
      long duration = SystemClock.uptimeMillis() - startTime;
      Log.i(TAG, "remoteInfer dms gRPC Inference time: " + duration);

      runOnUiThread(
              new Runnable() {
                @Override
                public void run() {
                  showFrameInfo(previewWidth + "x" + previewHeight);
                  if (!prediction.getIsValid()) {
                    showInference(0, 0, 0, 0, 0);
                  }
                  showInference(
                          prediction.getInferenceTime(),
                          (int) prediction.getTDistraction(),
                          (int) prediction.getTDrowsiness(),
                          prediction.getBlinkTotal(),
                          prediction.getYawnTotal());
                }
              });
      if (!prediction.getIsValid()) {
        Log.i(TAG, "remoteInfer dms gRPC Invalid data ");
        return null;
      }

      return getRecognitions(prediction);
    } catch (Exception e) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      pw.flush();
      Log.i(TAG, "gRPC stub exception: \n" + String.format("Failed... : %n%s", sw));
    }
    return null;
  }
//]] GRPC

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + rgbFrameBitmap.getHeight() + "p at: " + currTimestamp);
            long startTime = SystemClock.uptimeMillis();
            Classifier.Recognition result = remoteInfer(rgbFrameBitmap);
            if (result == null) { // Fallback to local Inference
              Log.e(TAG, "remoteInfer Failed, Fallback");
              stoppedInference();
              startTime = SystemClock.uptimeMillis();
            }
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
            Log.i(TAG, "TFlite UI lastProcessingTimeMs " + lastProcessingTimeMs);

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
            }

            final List<Classifier.Recognition> mappedRecognitions = new ArrayList<Classifier.Recognition>();

            if (result != null) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minimumConfidence) {
                canvas.drawRect(location, paint);

                result.setLocation(location);
                mappedRecognitions.add(result);
              }
            }

            tracker.trackResults(mappedRecognitions, currTimestamp);
            trackingOverlay.postInvalidate();

            computingDetection = false;

          }
        });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }


  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
  }

}

