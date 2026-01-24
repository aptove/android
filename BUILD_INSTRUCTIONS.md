# Build Instructions for Android ACP Chat App

## Prerequisites

1. **Android Studio Hedgehog (2023.1.1) or newer**
2. **Java 21** installed via Homebrew

## Setup

### 1. Install Java 21

```bash
brew install openjdk@21
```

### 2. Configure Android Studio to Use Java 21

**This is required for the build to work!**

1. Open Android Studio
2. Go to **Settings/Preferences** (⌘, on Mac)
3. Navigate to **Build, Execution, Deployment → Build Tools → Gradle**
4. Under **Gradle JDK**, select **JAVA_HOME** or manually select:
   ```
   /opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
   ```
5. Click **Apply** and **OK**

### 3. Open Project and Build

1. Open `/Users/saltuk/code/openspec-acp-swift-sdk/chat-ai/android` in Android Studio
2. Wait for Gradle sync to complete
3. **File → Invalidate Caches → Invalidate and Restart** (if needed)
4. Build → Make Project

The build should now succeed.

## Alternative: Build from Command Line

If you prefer command line or Android Studio configuration isn't working:

```bash
# Set JAVA_HOME to Java 21
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home

# Build
cd /Users/saltuk/code/openspec-acp-swift-sdk/chat-ai/android
./gradlew assembleDebug
```

## Why Java 21?

The ACP Kotlin SDK (v0.14.1) requires Java 21 for its `buildSrc` module.  
Without Java 21, you'll see this error:
```
Could not resolve all dependencies for configuration ':kotlin-sdk-repo:buildSrc:buildScriptClasspath'
Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM.
```

## Troubleshooting

### "Cannot find Java 21 installation"

**Check if Java 21 is installed:**
```bash
ls /opt/homebrew/Cellar/openjdk@21/
```

**If not installed:**
```bash
brew install openjdk@21
```

### "BUILD FAILED: buildScriptClasspath resolution error"

This means Gradle is not using Java 21. Solutions:

**Option 1: Configure in Android Studio** (see step 2 above)

**Option 2: Use command line with JAVA_HOME:**
```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
cd android/
./gradlew build
```

**Option 3: Set JAVA_HOME globally in your shell:**

Add to `~/.zshrc`:
```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
```

Then restart Android Studio **from terminal**:
```bash
source ~/.zshrc
open -a "Android Studio"
```

### "Gradle sync failed"

1. **Stop all Gradle daemons**:
   ```bash
   cd android/
   ./gradlew --stop
   ```

2. **Clear Gradle cache**:
   ```bash
   rm -rf ~/.gradle/caches
   ```

3. **In Android Studio**: File → Invalidate Caches → Invalidate and Restart

4. Try syncing again

## Build Output

Successful build produces:
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Size**: ~15-20 MB (includes Kotlin SDK + dependencies)

## Technology Stack

- **Kotlin 2.1.0** with Jetpack Compose
- **Gradle 8.9** with AGP 8.7.3
- **Hilt 2.54** for dependency injection
- **ACP Kotlin SDK v0.14.1** (via composite build)
- **Min SDK**: API 26 (Android 8.0)
- **Target SDK**: API 34

## Verifying Your Setup

To verify Java 21 is being used:

```bash
cd android/
./gradlew --version
```

You should see:
```
Daemon JVM:    /opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home
```

