# Android Push Notifications - Integration Guide

## Current Implementation Status

✅ **Complete**
- Firebase dependencies configured in `build.gradle.kts`
- `google-services.json` plugin configured
- `FCMService` extends `FirebaseMessagingService`
- Token management via `PushTokenManager`
- Notification display with channels (Android 8.0+)
- Automatic registration with bridge via `AgentRepository`
- `ACPClient` has `registerPushToken()` and `unregisterPushToken()` methods
- Android Manifest has FCM service registered
- Permission handling for Android 13+ (`POST_NOTIFICATIONS`)

## Architecture

```
┌─────────────────┐
│  Firebase FCM   │  ← Cloud Messaging (FREE)
└────────┬────────┘
         │ Push
         ▼
┌─────────────────┐
│   FCMService    │  ← Receives notifications
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ PushTokenManager│  ← Stores token in SharedPreferences
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ AgentRepository │  ← Registers token with bridge on connect
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   ACPClient     │  ← Sends bridge/registerPushToken via WebSocket
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│     Bridge      │  ← Forwards to push relay
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Push Relay     │  ← Stores token, sends notifications to FCM
└─────────────────┘
```

## Setup Steps

### 1. Firebase Project Setup

Follow [cf-push-relay/docs/firebase-fcm-setup.md](../../cf-push-relay/docs/firebase-fcm-setup.md) to:

1. Create Firebase project
2. Add Android app with package `com.acp.chat`
3. Download `google-services.json` → `android/app/google-services.json`
4. Generate service account key for push relay

### 2. Cloudflare Worker Configuration

The push relay worker needs FCM credentials (from service account JSON):

```bash
# Navigate to push relay
cd cf-push-relay

# Set FCM credentials
npx wrangler secret put FCM_PROJECT_ID     # e.g., "aptove-app"
npx wrangler secret put FCM_PRIVATE_KEY    # From service account JSON
npx wrangler secret put FCM_CLIENT_EMAIL   # e.g., "firebase-adminsdk-...@aptove-app.iam.gserviceaccount.com"

# Deploy
npm run deploy
```

### 3. Test Push Notifications

Use Bruno collection at `cf-push-relay/bruno/` or curl:

```bash
# Register Android device
curl -X POST https://push.aptove.com/register \
  -H "Content-Type: application/json" \
  -H "X-Relay-Token: <BRIDGE_AUTH_TOKEN>" \
  -d '{
    "deviceToken": "<FCM_DEVICE_TOKEN>",
    "platform": "android",
    "bundleId": "com.acp.chat"
  }'

# Send test notification
curl -X POST https://push.aptove.com/notify \
  -H "Content-Type: application/json" \
  -H "X-Relay-Token: <BRIDGE_AUTH_TOKEN>" \
  -d '{
    "title": "Test Notification",
    "body": "Your agent has new activity",
    "bundleId": "com.acp.chat"
  }'
```

## Implementation Details

### FCM Token Flow

1. **App Launch**: `ChatApplication.onCreate()` initializes Firebase and fetches token
2. **Token Received**: `FCMService.onNewToken()` stores in `PushTokenManager`
3. **Bridge Connection**: `AgentRepository.connectAgent()` reads token and registers with bridge
4. **Registration**: `ACPClient.registerPushToken()` sends `bridge/registerPushToken` notification
5. **Bridge Forward**: Bridge forwards to push relay at configured URL
6. **Storage**: Push relay stores device token in KV namespace

### Notification Display

**Background (App Killed/Minimized)**:
- FCM automatically displays notification
- Uses `notification.title` and `notification.body` from payload

**Foreground (App Active)**:
- `FCMService.onMessageReceived()` called
- App manually displays notification via `NotificationManager`
- Can customize behavior (e.g., update UI instead of showing notification)

### Token Refresh

FCM automatically refreshes tokens:
- App reinstall
- Device restore
- App data cleared
- Periodic rotation by FCM

When refreshed, `onNewToken()` is called → stored → re-registered with bridge.

## Code Structure

```
android/app/src/main/java/com/acp/chat/
├── service/
│   ├── FCMService.kt                    # Receives FCM messages
│   └── PushTokenManager.kt              # Token storage (SharedPreferences)
├── data/repository/
│   └── AgentRepository.kt               # Registers token on connection
├── domain/acp/
│   └── ACPClient.kt                     # WebSocket: bridge/registerPushToken
└── ChatApplication.kt                   # Initialize Firebase on app launch
```

## Permission Handling

### Android 13+ (API 33+)

Request `POST_NOTIFICATIONS` permission at runtime:

```kotlin
// In MainActivity or composable
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { isGranted ->
    if (isGranted) {
        Log.d(TAG, "✅ Notification permission granted")
    } else {
        Log.w(TAG, "❌ Notification permission denied")
    }
}

// Request permission
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
}
```

### Android 12 and Below

Notifications are enabled by default. No runtime permission needed.

## Notification Channels

Channel created in `FCMService.createNotificationChannel()`:

- **ID**: `agent_activity`
- **Name**: "Agent Activity"
- **Importance**: `IMPORTANCE_HIGH` (makes sound, shows popup)
- **Features**: Vibration enabled, shows badge

Users can customize channel behavior in:
```
Settings → Apps → Aptove → Notifications → Agent Activity
```

## Debugging

### Check FCM Token

```kotlin
// In ChatApplication or debug screen
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    Log.d(TAG, "FCM Token: $token")
}
```

### View Logs

```bash
# Watch FCM logs
adb logcat | grep -E "FCMService|PushTokenManager|AgentRepository"
```

### Common Issues

**No token received**:
- Verify `google-services.json` is in `android/app/`
- Check `build.gradle.kts` has `id("com.google.gms.google-services")`
- Rebuild: `./gradlew clean build`

**Notifications not showing**:
- Check notification permissions (Settings → Apps → Aptove)
- Verify notification channel not muted
- Check push relay logs: `npx wrangler tail`

**Token not registered with bridge**:
- Verify bridge has `--push-relay-url` set
- Check WebSocket connection is active
- Verify `relay_token` (bridge's `auth_token`) has 32+ characters

## Testing Checklist

- [ ] Firebase project created with Android app
- [ ] `google-services.json` downloaded and placed in `android/app/`
- [ ] Push relay deployed with FCM credentials configured
- [ ] App builds successfully
- [ ] FCM token received on app launch (check logs)
- [ ] Token registered with bridge on connection
- [ ] Device registered in push relay KV namespace
- [ ] Test notification sent via Bruno or curl
- [ ] Notification displayed when app is background
- [ ] Notification displayed when app is foreground
- [ ] Token refresh handled correctly (test by clearing app data)

## Cost Summary

| Service | Cost |
|---------|------|
| Firebase (FCM) | **$0.00** (Free forever) |
| Cloudflare Workers | Free tier: 100k requests/day |
| Cloudflare KV | Free tier: 100k reads/day, 1k writes/day |
| **Total** | **$0.00** for typical usage |

FCM has no quotas or limits - completely free for unlimited notifications.

## References

- [Firebase FCM Setup Guide](../../cf-push-relay/docs/firebase-fcm-setup.md)
- [Push Relay Architecture](../../bridge/docs/push/overview.md)
- [Bruno API Collection](../../cf-push-relay/bruno/README.md)
- [Firebase Cloud Messaging Docs](https://firebase.google.com/docs/cloud-messaging)
- [Android Notification Channels](https://developer.android.com/develop/ui/views/notifications/channels)
