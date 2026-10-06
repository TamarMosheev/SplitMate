package com.example.myapplication

import com.example.myapplication.data.remote.ApiClient
import com.example.myapplication.data.remote.TokenProvider
import com.example.myapplication.repository.DeleteGroupResult
import com.example.myapplication.repository.GroupDeletionRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the real Retrofit/OkHttp client and the repository against a local HTTP server, so the request that
 * would reach the backend (method, path, Authorization header, 401 refresh-and-retry) and the mapping of every
 * status code are verified. [realBackend] additionally calls the real backend when it is configured.
 */
class GroupDeletionRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repository(tokens: TokenProvider = TokenProvider { forceRefresh -> if (forceRefresh) "fresh" else "cached" }) =
        GroupDeletionRepository(ApiClient.create(server.url("/").toString(), tokens))

    private fun delete(repository: GroupDeletionRepository, id: String = "g1") =
        runBlocking { repository.deleteGroup(id) }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `200 deletes - sends DELETE groups id with the Firebase token`() {
        server.enqueue(json(200, """{"success":true,"groupId":"g1"}"""))

        assertEquals(DeleteGroupResult.Deleted, delete(repository()))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/groups/g1", request.path)
        assertEquals("Bearer cached", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 means the user is not allowed`() {
        server.enqueue(json(403, """{"detail":"Only the person who created the group can delete it"}"""))
        assertEquals(DeleteGroupResult.NotAllowed, delete(repository()))
    }

    @Test
    fun `404 means the group is already gone`() {
        server.enqueue(json(404, """{"detail":"Group 'g1' not found"}"""))
        assertEquals(DeleteGroupResult.AlreadyGone, delete(repository()))
    }

    @Test
    fun `server error is a failure`() {
        server.enqueue(json(500, """{"detail":"boom"}"""))
        assertEquals(DeleteGroupResult.Failed, delete(repository()))
    }

    @Test
    fun `200 without success true is not treated as deleted`() {
        server.enqueue(json(200, """{"success":false,"groupId":"g1"}"""))
        assertEquals(DeleteGroupResult.Failed, delete(repository()))
    }

    @Test
    fun `401 refreshes the token and retries once`() {
        server.enqueue(json(401, """{"detail":"Invalid or expired Firebase ID token"}"""))
        server.enqueue(json(200, """{"success":true,"groupId":"g1"}"""))

        assertEquals(DeleteGroupResult.Deleted, delete(repository()))

        assertEquals(2, server.requestCount)
        assertEquals("Bearer cached", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `401 after the retry is a session problem and does not loop`() {
        server.enqueue(json(401, """{"detail":"Invalid or expired Firebase ID token"}"""))
        server.enqueue(json(401, """{"detail":"Invalid or expired Firebase ID token"}"""))

        assertEquals(DeleteGroupResult.SessionExpired, delete(repository()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `no signed in user - no token is sent and 401 is a session problem`() {
        server.enqueue(json(401, """{"detail":"Missing or malformed Authorization header"}"""))

        assertEquals(DeleteGroupResult.SessionExpired, delete(repository(TokenProvider { null })))

        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `no connection is a failure`() {
        val repository = repository()
        server.shutdown()
        assertEquals(DeleteGroupResult.Failed, delete(repository))
    }

    /**
     * Optional: the real backend. Skipped unless SPLITMATE_TEST_BASE_URL, SPLITMATE_TEST_TOKEN (a real Firebase ID
     * token), SPLITMATE_TEST_GROUP_ID and SPLITMATE_TEST_EXPECT (deleted | not_allowed | gone) are set.
     */
    @Test
    fun realBackend() {
        val baseUrl = System.getenv("SPLITMATE_TEST_BASE_URL")
        val token = System.getenv("SPLITMATE_TEST_TOKEN")
        val groupId = System.getenv("SPLITMATE_TEST_GROUP_ID")
        val expect = System.getenv("SPLITMATE_TEST_EXPECT")
        assumeTrue("real-backend test not configured", !baseUrl.isNullOrBlank() && !token.isNullOrBlank() && !groupId.isNullOrBlank())

        val realRepository = GroupDeletionRepository(ApiClient.create(baseUrl!!, TokenProvider { token }))
        val expected = when (expect) {
            "deleted" -> DeleteGroupResult.Deleted
            "not_allowed" -> DeleteGroupResult.NotAllowed
            "gone" -> DeleteGroupResult.AlreadyGone
            else -> error("SPLITMATE_TEST_EXPECT must be deleted | not_allowed | gone")
        }
        assertEquals(expected, runBlocking { realRepository.deleteGroup(groupId!!) })
    }
}
