package com.acp.chat.domain

import android.content.Context
import com.acp.chat.data.local.CredentialStorage
import com.acp.chat.data.model.Agent
import com.acp.chat.data.model.ConnectionConfig
import com.acp.chat.data.model.ConnectionStatus
import com.acp.chat.data.repository.AgentRepository
import com.acp.chat.domain.acp.ACPClient
import com.acp.chat.service.PushTokenManager
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionId
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentManagerTest {

    @MockK private lateinit var context: Context
    @MockK private lateinit var repository: AgentRepository
    @MockK private lateinit var credentialStorage: CredentialStorage
    @MockK private lateinit var acpClient: ACPClient

    private lateinit var agentManager: AgentManager

    private val testAgentId = "agent-123"
    private val testConfig = ConnectionConfig(url = "wss://test.example.com")
    private val testAgent = Agent(
        agentId = testAgentId,
        name = "Test Agent",
        url = "wss://test.example.com",
        protocolVersion = "1.0"
    )

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        mockkObject(PushTokenManager)
        every { PushTokenManager.getToken(any()) } returns null
        agentManager = AgentManager(context, repository, credentialStorage, acpClient)
    }

    @After
    fun tearDown() {
        unmockkObject(PushTokenManager)
    }

    // ----- observeAgents -----

    @Test
    fun `observeAgents delegates to repository`() {
        every { repository.getAllAgents() } returns flowOf(listOf(testAgent))

        agentManager.observeAgents()

        verify { repository.getAllAgents() }
    }

    @Test
    fun `observeAgent delegates to repository`() {
        every { repository.observeAgent(testAgentId) } returns flowOf(testAgent)

        agentManager.observeAgent(testAgentId)

        verify { repository.observeAgent(testAgentId) }
    }

    // ----- addAgent -----

    @Test
    fun `addAgent saves to repository and credentials`() = runTest {
        coEvery { repository.addAgent(testAgent) } just Runs

        agentManager.addAgent(testAgent, testConfig)

        coVerify { repository.addAgent(testAgent) }
        verify { credentialStorage.saveCredentials(testAgentId, testConfig) }
    }

    // ----- deleteAgent -----

    @Test
    fun `deleteAgent removes from repository and deletes credentials`() = runTest {
        coEvery { repository.deleteAgent(testAgentId) } just Runs

        agentManager.deleteAgent(testAgentId)

        coVerify { repository.deleteAgent(testAgentId) }
        verify { credentialStorage.deleteCredentials(testAgentId) }
    }

    @Test
    fun `deleteAgent clears cached session before repository delete`() = runTest {
        // Prime cache via a successful connect
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-abc")
        primeCache(mockSession)

        assertNotNull(agentManager.getSession(testAgentId))

        coEvery { repository.deleteAgent(testAgentId) } just Runs

        agentManager.deleteAgent(testAgentId)

        assertNull(agentManager.getSession(testAgentId))
    }

    // ----- connectToAgent -----

    @Test
    fun `connectToAgent returns failure when no credentials found`() = runTest {
        every { credentialStorage.getCredentials(testAgentId) } returns null

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isFailure)
        assertEquals("No credentials found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `connectToAgent creates new session when none cached`() = runTest {
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-new")

        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent.copy(activeSessionId = null)
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.success(mockk(relaxed = true))
        every { acpClient.supportsLoadSession() } returns false
        coEvery { acpClient.createSession() } returns Result.success(mockSession)
        coEvery { repository.updateSessionInfo(any(), any(), any()) } just Runs

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isSuccess)
        val connectionResult = result.getOrThrow()
        assertFalse(connectionResult.wasResumed)
        assertEquals(mockSession, connectionResult.session)
        coVerify { acpClient.createSession() }
    }

    @Test
    fun `connectToAgent reuses cached session on second call`() = runTest {
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-abc")
        primeCache(mockSession)

        // Second call — should use cache
        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().wasResumed)
        // acpClient.connect should NOT be called again
        coVerify(exactly = 1) { acpClient.connect(any()) }
    }

    @Test
    fun `connectToAgent resumes existing session when agent supports loadSession`() = runTest {
        val storedSessionId = "stored-session-id"
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId(storedSessionId)

        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent.copy(activeSessionId = storedSessionId)
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.success(mockk(relaxed = true))
        every { acpClient.supportsLoadSession() } returns true
        coEvery { acpClient.loadSession(SessionId(storedSessionId)) } returns Result.success(mockSession)

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().wasResumed)
        coVerify { acpClient.loadSession(SessionId(storedSessionId)) }
        coVerify(exactly = 0) { acpClient.createSession() }
    }

    @Test
    fun `connectToAgent falls back to new session when load fails`() = runTest {
        val storedSessionId = "expired-session-id"
        val newSession = mockk<ClientSession>(relaxed = true)
        every { newSession.sessionId } returns SessionId("new-session-id")

        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent.copy(activeSessionId = storedSessionId)
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { repository.clearSessionInfo(testAgentId) } just Runs
        coEvery { repository.updateSessionInfo(any(), any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.success(mockk(relaxed = true))
        every { acpClient.supportsLoadSession() } returns true
        coEvery { acpClient.loadSession(any()) } returns Result.failure(Exception("Session expired"))
        coEvery { acpClient.createSession() } returns Result.success(newSession)

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().wasResumed)
        coVerify { acpClient.createSession() }
        coVerify { repository.clearSessionInfo(testAgentId) }
    }

    @Test
    fun `connectToAgent sets status to DISCONNECTED on connection failure`() = runTest {
        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.failure(Exception("Network error"))

        val result = agentManager.connectToAgent(testAgentId)

        assertTrue(result.isFailure)
        coVerify { repository.updateConnectionStatus(testAgentId, ConnectionStatus.DISCONNECTED) }
    }

    // ----- disconnectFromAgent -----

    @Test
    fun `disconnectFromAgent disconnects ACPClient and updates status`() = runTest {
        coEvery { acpClient.disconnect() } just Runs
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs

        agentManager.disconnectFromAgent(testAgentId)

        coVerify { acpClient.disconnect() }
        coVerify { repository.updateConnectionStatus(testAgentId, ConnectionStatus.DISCONNECTED) }
    }

    @Test
    fun `disconnectFromAgent removes session from cache`() = runTest {
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-abc")
        primeCache(mockSession)

        assertNotNull(agentManager.getSession(testAgentId))

        coEvery { acpClient.disconnect() } just Runs
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs

        agentManager.disconnectFromAgent(testAgentId)

        assertNull(agentManager.getSession(testAgentId))
    }

    // ----- clearSession -----

    @Test
    fun `clearSession clears repository session info`() = runTest {
        coEvery { repository.clearSessionInfo(testAgentId) } just Runs

        agentManager.clearSession(testAgentId)

        coVerify { repository.clearSessionInfo(testAgentId) }
    }

    @Test
    fun `clearSession removes cached session`() = runTest {
        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-abc")
        primeCache(mockSession)

        assertNotNull(agentManager.getSession(testAgentId))

        coEvery { repository.clearSessionInfo(testAgentId) } just Runs

        agentManager.clearSession(testAgentId)

        assertNull(agentManager.getSession(testAgentId))
    }

    // ----- getACPClient -----

    @Test
    fun `getACPClient returns the injected ACPClient instance`() {
        assertEquals(acpClient, agentManager.getACPClient())
    }

    // ----- getCredentials -----

    @Test
    fun `getCredentials delegates to credentialStorage`() {
        every { credentialStorage.getCredentials(testAgentId) } returns testConfig

        val result = agentManager.getCredentials(testAgentId)

        assertEquals(testConfig, result)
        verify { credentialStorage.getCredentials(testAgentId) }
    }

    // ----- updateAgentCredentials -----

    @Test
    fun `updateAgentCredentials saves new credentials and clears session state`() = runTest {
        coEvery { acpClient.disconnect() } just Runs
        coEvery { repository.clearSessionInfo(testAgentId) } just Runs
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs

        val newConfig = ConnectionConfig(url = "wss://new.example.com", authToken = "new-token")
        agentManager.updateAgentCredentials(testAgentId, newConfig)

        verify { credentialStorage.saveCredentials(testAgentId, newConfig) }
        coVerify { acpClient.disconnect() }
        coVerify { repository.clearSessionInfo(testAgentId) }
        coVerify { repository.updateConnectionStatus(testAgentId, ConnectionStatus.DISCONNECTED) }
    }

    // ----- push token registration -----

    @Test
    fun `connectToAgent registers push token when available`() = runTest {
        val fcmToken = "fcm-token-xyz"
        every { PushTokenManager.getToken(context) } returns fcmToken

        val mockSession = mockk<ClientSession>(relaxed = true)
        every { mockSession.sessionId } returns SessionId("session-abc")
        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent.copy(activeSessionId = null)
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.success(mockk(relaxed = true))
        every { acpClient.supportsLoadSession() } returns false
        coEvery { acpClient.createSession() } returns Result.success(mockSession)
        coEvery { repository.updateSessionInfo(any(), any(), any()) } just Runs

        agentManager.connectToAgent(testAgentId)

        verify { acpClient.registerPushToken(fcmToken) }
    }

    // ----- helpers -----

    /**
     * Runs connectToAgent once to populate the session cache, with all dependencies stubbed.
     */
    private suspend fun primeCache(mockSession: ClientSession) {
        every { credentialStorage.getCredentials(testAgentId) } returns testConfig
        coEvery { repository.getAgentById(testAgentId) } returns testAgent.copy(activeSessionId = null)
        coEvery { repository.updateConnectionStatus(any(), any()) } just Runs
        coEvery { acpClient.connect(testConfig) } returns Result.success(mockk(relaxed = true))
        every { acpClient.supportsLoadSession() } returns false
        coEvery { acpClient.createSession() } returns Result.success(mockSession)
        coEvery { repository.updateSessionInfo(any(), any(), any()) } just Runs

        agentManager.connectToAgent(testAgentId)
    }
}
