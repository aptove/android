# ACP Chat - Android

A mobile chat application for Android that enables users to connect with AI agents using the Agent Client Protocol (ACP).

## Features

- **Secure QR Pairing** - Scan QR codes to securely pair with local bridge
- **Certificate Pinning** - TLS certificate validation prevents MITM attacks
- **Manual Pairing** - Enter pairing code manually if QR scanning unavailable
- **Agent Management** - Manage multiple agent connections
- **Real-time Chat** - Send and receive messages with AI agents
- **Message Streaming** - Display agent responses as they stream in
- **Secure Credentials** - Android Keystore encryption for connection credentials
- **Message Persistence** - Room database for offline message storage
- **Material Design 3** - Modern UI with Jetpack Compose
- **Full ACP Protocol** - Integrated with official ACP Kotlin SDK

## Requirements

- Android 8.0 (API 26) or later
- Kotlin 2.0+
- Android Studio Hedgehog or later

## Architecture

The app follows Clean Architecture principles with MVVM pattern, using a layered domain architecture that mirrors the iOS app:

```
app/
├── data/
│   ├── model/          # Data models (Agent, Message, ConnectionConfig)
│   ├── local/          # Room database, DAOs, CredentialStorage
│   └── repository/     # Data-only repository (CRUD + reactive queries)
├── domain/
│   ├── AgentManager    # Business logic: connections, sessions, credentials
│   ├── acp/            # ACP client wrapper using official SDK
│   └── usecase/        # Orchestration use cases (connect, send message)
├── ui/
│   ├── agents/         # Agent list and configuration screens
│   ├── chat/           # Chat screen
│   ├── qr/             # QR scanner screen
│   └── theme/          # Material Theme
├── di/                 # Hilt dependency injection modules
└── service/            # Background services (FCM, pairing)
```

### Layer Responsibilities

```
ViewModel
    ↓
AgentManager          ← business logic: connections, session cache, credentials
    ↓
AgentRepository       ← data only: CRUD, reactive queries (Flow)
    ↓
Room Database
```

| Layer | Responsibility |
|-------|---------------|
| `ViewModel` | UI state and presentation logic |
| `AgentManager` | Connection lifecycle, session caching, credential coordination |
| `AgentRepository` | Data persistence (CRUD + reactive queries via Flow) |
| `AgentDao` | Room SQL queries |

This matches the iOS architecture (`AgentManager` → `AgentRepository` → CoreData), making it straightforward to reason about cross-platform behaviour.

## Technology Stack

- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + Clean Architecture
- **DI**: Hilt
- **Database**: Room
- **Security**: Android Keystore + EncryptedSharedPreferences + Certificate Pinning
- **Networking**: ACP Kotlin SDK with Ktor WebSockets
- **Serialization**: kotlinx.serialization
- **Navigation**: Jetpack Navigation Compose
- **QR Scanning**: Google ML Kit + CameraX

## Building

### Prerequisites

1. Install Android Studio
2. Clone the repository
3. Open the `chat-ai/android` directory in Android Studio

### ACP SDK Integration

The app uses the ACP Kotlin SDK via Gradle composite build:

```gradle
// settings.gradle.kts
includeBuild("../../../kotlin-sdk-repo") {
    dependencySubstitution {
        substitute(module("com.agentclientprotocol:acp-model")).using(project(":acp-model"))
        substitute(module("com.agentclientprotocol:acp")).using(project(":acp"))
        substitute(module("com.agentclientprotocol:acp-ktor-client")).using(project(":acp-ktor-client"))
    }
}
```

### Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test

# Run on connected device
./gradlew installDebug
```

## Configuration

The app connects to ACP agents by scanning a pairing QR code from the bridge:

```
https://192.168.1.100:3001/pair/local?code=123456&fp=SHA256:XXXX...
```

The QR code contains:
- **Pairing URL** - Bridge's HTTPS endpoint
- **Pairing Code** - 6-digit one-time code (expires in 60 seconds)
- **Certificate Fingerprint** - For TLS certificate pinning

After successful pairing, the app receives WebSocket credentials securely.

## Security

- **Credential Storage**: Uses Android Keystore with AES-256-GCM encryption
- **Network Security**: HTTPS/WSS only (no cleartext traffic)
- **Certificate Pinning**: Validates TLS certificate fingerprint from QR code
- **Secure Pairing**: One-time codes with 60-second expiry and rate limiting

## Session Persistence

The app supports automatic session resumption when reconnecting to agents:

### How It Works

1. **Session Storage**: When a new session is created, the session ID and start time are stored locally.
2. **Capability Detection**: The app checks if the agent supports `loadSession` capability during initialization.
3. **Auto-Resume**: When reconnecting, the app attempts to load the existing session if the agent supports it.
4. **Graceful Fallback**: If session loading fails (e.g., session expired), a new session is created automatically.

### Session Status Indicators

When entering a chat:
- **"Session resumed"** (blue text) - Successfully loaded an existing session
- **"New session"** (gray text) - Created a fresh session

### Agent Configuration Screen

Long-press an agent in the list to access the configuration screen where you can:
- View agent information (name, URL, protocol version)
- View session information (ID, start time, message count)
- **Clear Session** - Start a fresh conversation (deletes all messages)
- **Delete Agent** - Remove the agent and all associated data

### QR Re-scan Behavior

If you scan a QR code for an agent that already exists:
- The app updates the stored credentials (auth token, certificate fingerprint)
- The cached session is cleared
- A connection test is performed with the new credentials

This is useful when the bridge restarts and generates new credentials.

## ACP Protocol Implementation

The app uses the official ACP Kotlin SDK:

```kotlin
// Connect to agent via WebSocket
val protocol = httpClient.acpProtocolOnClientWebSocket(
    url = config.toWebSocketUrl(),
    protocolOptions = ProtocolOptions()
) {
    headers.append("CF-Access-Client-Id", config.clientId)
    headers.append("CF-Access-Client-Secret", config.clientSecret)
}

// Create client
val client = Client(protocol)
protocol.start()

// Initialize handshake
val serverInfo = client.initialize(
    ClientInfo(
        name = "ACP Chat Android",
        version = "1.0.0",
        capabilities = ClientCapabilities()
    )
)

// Create session
val sessionId = client.newSession(SessionCreationParameters())

// Send message and collect streaming response
client.prompt(sessionId, message)
    .collect { event ->
        when (event) {
            is Event.SessionUpdate -> handleUpdate(event.update)
            is Event.PromptResponse -> handleComplete()
        }
    }
```

## Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

## Known Limitations

1. **Background Sync**: Messages are not synced in background. App must be active for real-time communication.

2. **Single Agent Connection**: Currently supports one active connection at a time.

3. **Local Network Only**: Secure pairing requires device to be on same network as bridge.

## Future Enhancements

- [ ] Cloudflare tunnel support for remote access
- [ ] Push notifications for messages
- [ ] File attachments support
- [ ] Voice input
- [ ] Session management (fork, resume)
- [ ] Multiple concurrent agent connections

## ⚖️ License & Trademarks

### Software License
This project is licensed under the **Apache License 2.0**. 
- You are free to use, modify, and distribute the code.
- You must include the original copyright notice and license in any copies.
- For full details, see the [LICENSE](LICENSE) file.

### Trademark Policy
The name **Aptove**, the logo, and all related branding are **not** part of the Apache 2.0 license.
- **Redistribution:** If you distribute a modified version of this app, you **must** remove all original branding and use a different name/icon.
- For full details on what is and isn't allowed, please read our [TRADEMARKS.md](TRADEMARKS.md) policy.

---
*Maintained by Saltuk Alakus. For commercial inquiries or licensing questions, please open an issue or contact saltukalakus@gmail.com*