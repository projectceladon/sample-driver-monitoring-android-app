# TensorFlow Lite Driver Monitoring System Android Demo

### Overview

This is a camera app that continuously detects the face(blink and yawns) in the frames seen by your device camera, using a driver monitoring service running on remote server and provide Distraction and Drowsiness percentage along with warning tones. These instructions walk you through building and running the demo on an Android device.

Application can run either on device or emulator.

## Build the demo using Android Studio

### Prerequisites

*   If you don't have already, install
    **[Android Studio](https://developer.android.com/studio/index.html)**,
    following the instructions on the website.

*   You need an Android device and Android development environment with minimum
    API 21.

*   Android Studio 3.2 or later.

### Building

*   Open Android Studio, and from the Welcome screen, select Open an existing
    Android Studio project.

*   From the Open File or Project window that appears, navigate to and select
    the **_"android"_** directory from
    wherever you cloned the DMS sample App GitHub repo. Click OK.

*   If it asks you to do a Gradle Sync, click OK.

*   You may also need to install various platforms and tools, if you get errors
    like "Failed to find target with hash string 'android-21'" and similar.
    Click the `Run` button (the green arrow) or select `Run > Run 'android'`
    from the top menu. You may need to rebuild the project using `Build >
    Rebuild` Project.

*   If it asks you to use Instant Run, click Proceed Without Instant Run.

*   Also, you need to have an Android device plugged in with developer options
    enabled at this point. See
    **[here](https://developer.android.com/studio/run/device)** for more details
    on setting up developer devices.

### Build and Install
* To build and install this repository.
```
cd android
./gradlew assembleRelease
```
### Additional Note
_Please do not delete the assets folder content_. If you explicitly deleted the files, then please choose *Build*->*Rebuild* from menu to re-download the deleted model files into assets folder.
## License

[Apache License 2.0](LICENSE)
