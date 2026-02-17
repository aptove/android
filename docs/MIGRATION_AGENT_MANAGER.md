# Migration Guide: Adding AgentManager

## Why This Change Was Made

Before this change, `AgentRepository` was a "fat repository" that mixed two very different concerns:

- **Data operations** — CRUD via Room, reactive queries via Flow
- **Business logic** — connection lifecycle, session caching, credential coordination, ACPClient management

This violated the Single Responsibility Principle and made `AgentRepository` hard to test and reason about. It also diverged from the iOS app's layered architecture.

The fix was to extract all business logic into a new `AgentManager` class, leaving `AgentRepository` as a thin data layer — the same pattern already in use on iOS.

---

## What Changed

### New class: `AgentManager`

**Location**: `domain/AgentManager.kt`

`AgentManager` is now the entry point for all agent-related operations in ViewModels and use cases.

```kotlin
@Singleton
class AgentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AgentRepository,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
)
```

Responsibilities:
- Connection lifecycle (`connectToAgent`, `disconnectFromAgent`)
- In-memory session cache (`sessionCache`)
- Credential coordination (`addAgent`, `updateAgentCredentials`, `deleteAgent`)
- ACPClient access (`getACPClient`, `getSession`)

### Simplified: `AgentRepository`

`AgentRepository` now only handles data persistence. Its constructor was reduced from four parameters to one:

```kotlin
// Before
class AgentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentDao: AgentDao,
    private val credentialStorage: CredentialStorage,
    private val acpClient: ACPClient
)

// After
class AgentRepository @Inject constructor(
    private val agentDao: AgentDao
)
```

Methods removed from `AgentRepository` (all moved to `AgentManager`):
- `connectToAgent()`
- `disconnectFromAgent()`
- `updateAgentCredentials()`
- `getCredentials()`
- `getSession()`
- `getACPClient()`
- `sessionCache`

### Updated callers

| File | Change |
|------|--------|
| `ChatViewModel` | Injects `AgentManager` instead of `AgentRepository` |
| `AgentListViewModel` | Injects `AgentManager` instead of `AgentRepository` |
| `AgentConfigurationViewModel` | Injects `AgentManager` instead of `AgentRepository` |
| `ConnectAgentUseCase` | Injects `AgentManager` instead of `AgentRepository` + `ACPClient` |

`ConnectionResult` data class moved from `AgentRepository.kt` to `AgentManager.kt`.

---

## How to Use AgentManager

### Inject in a ViewModel

```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val agentManager: AgentManager
) : ViewModel()
```

No Hilt module changes are needed — `AgentManager` uses `@Inject constructor` and `@Singleton`.

### Observe agents

```kotlin
agentManager.observeAgents()           // Flow<List<Agent>>
agentManager.observeAgent(agentId)     // Flow<Agent?>
```

### Connect / disconnect

```kotlin
val result = agentManager.connectToAgent(agentId)
result.onSuccess { connectionResult ->
    val session = connectionResult.session
    val wasResumed = connectionResult.wasResumed
}

agentManager.disconnectFromAgent(agentId)
```

### Add / delete agents

```kotlin
agentManager.addAgent(agent, config)   // persists agent + credentials
agentManager.deleteAgent(agentId)      // clears cache + DB + credentials
```

### Update credentials (e.g. after re-scanning QR)

```kotlin
agentManager.updateAgentCredentials(agentId, newConfig)
// Clears cached session, disconnects, saves new credentials
```

### Access ACPClient (for sending messages)

```kotlin
val acpClient = agentManager.getACPClient()
```

### Clear a session (without deleting the agent)

```kotlin
agentManager.clearSession(agentId)
// Clears DB session info + in-memory cache
```

---

## Migration Checklist

Use this if you have a ViewModel or use case still injecting `AgentRepository` for business logic:

- [ ] Change injection from `AgentRepository` to `AgentManager`
- [ ] Replace `repository.connectToAgent()` → `agentManager.connectToAgent()`
- [ ] Replace `repository.disconnectFromAgent()` → `agentManager.disconnectFromAgent()`
- [ ] Replace `repository.getAllAgents()` → `agentManager.observeAgents()`
- [ ] Replace `repository.observeAgent()` → `agentManager.observeAgent()`
- [ ] Replace `repository.getACPClient()` → `agentManager.getACPClient()`
- [ ] Replace `repository.getSession()` → `agentManager.getSession()`
- [ ] Replace `repository.addAgent(agent, config)` → `agentManager.addAgent(agent, config)`
- [ ] Replace `repository.deleteAgent()` → `agentManager.deleteAgent()`
- [ ] Replace `repository.updateAgentCredentials()` → `agentManager.updateAgentCredentials()`
- [ ] Replace `repository.clearSessionInfo()` → `agentManager.clearSession()`
- [ ] Update import from `com.acp.chat.data.repository.AgentRepository` to `com.acp.chat.domain.AgentManager`
- [ ] Update `ConnectionResult` import from `AgentRepository` to `AgentManager` if referenced by name

`AgentRepository` can still be injected directly for pure data operations (queries, status updates) if needed.
