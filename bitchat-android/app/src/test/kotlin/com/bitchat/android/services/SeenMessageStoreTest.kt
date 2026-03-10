package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.lang.reflect.Field

/**
 * Unit tests for SeenMessageStore.
 *
 * SeenMessageStore is a singleton that uses SecureIdentityStateManager for persistence.
 * We use reflection to construct instances with a mocked SecureIdentityStateManager,
 * bypassing the singleton factory and EncryptedSharedPreferences dependency.
 */
class SeenMessageStoreTest {

    private lateinit var mockContext: Context
    private lateinit var mockSecure: SecureIdentityStateManager
    private lateinit var store: SeenMessageStore

    // In-memory storage to simulate SecureIdentityStateManager
    private val secureStorage = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        mockContext = mock()
        whenever(mockContext.applicationContext).thenReturn(mockContext)

        mockSecure = mock {
            on { getSecureValue(any()) } doAnswer { invocation ->
                secureStorage[invocation.getArgument<String>(0)]
            }
            on { storeSecureValue(any(), any()) } doAnswer { invocation ->
                secureStorage[invocation.getArgument<String>(0)] = invocation.getArgument<String>(1)
                Unit
            }
        }

        // Use reflection to create an instance without triggering the singleton path
        // and inject our mocked SecureIdentityStateManager
        store = createStoreWithMockedSecure()
    }

    private fun createStoreWithMockedSecure(): SeenMessageStore {
        // Allocate instance via Unsafe or use reflection on the constructor
        val constructor = SeenMessageStore::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true

        // Temporarily set the secure field after construction will fail because init{} calls load()
        // which uses the real SecureIdentityStateManager. Instead, we pre-inject the mock.
        // We need to prevent the real SecureIdentityStateManager from being created.
        // Strategy: create the instance, then replace the 'secure' and re-init the sets.

        // First, create a store - this will throw in init{} because Context is mocked
        // and EncryptedSharedPreferences can't be created. We catch and work around it.
        val instance: SeenMessageStore
        try {
            instance = constructor.newInstance(mockContext)
        } catch (e: Exception) {
            // The constructor will fail because SecureIdentityStateManager tries to create
            // EncryptedSharedPreferences. We'll use a different approach: allocate without init.
            return createStoreViaUnsafe()
        }

        injectMockSecure(instance)
        return instance
    }

    private fun createStoreViaUnsafe(): SeenMessageStore {
        // Use sun.misc.Unsafe to allocate without calling constructor
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)

        @Suppress("UNCHECKED_CAST")
        val instance = allocateMethod.invoke(unsafe, SeenMessageStore::class.java) as SeenMessageStore

        // Initialize the fields that would normally be set by the constructor
        val gsonField = SeenMessageStore::class.java.getDeclaredField("gson")
        gsonField.isAccessible = true
        gsonField.set(instance, Gson())

        val secureField = SeenMessageStore::class.java.getDeclaredField("secure")
        secureField.isAccessible = true
        secureField.set(instance, mockSecure)

        val deliveredField = SeenMessageStore::class.java.getDeclaredField("delivered")
        deliveredField.isAccessible = true
        deliveredField.set(instance, LinkedHashSet<String>(10_000))

        val readField = SeenMessageStore::class.java.getDeclaredField("read")
        readField.isAccessible = true
        readField.set(instance, LinkedHashSet<String>(10_000))

        return instance
    }

    private fun injectMockSecure(instance: SeenMessageStore) {
        val secureField = SeenMessageStore::class.java.getDeclaredField("secure")
        secureField.isAccessible = true
        secureField.set(instance, mockSecure)
    }

    // --- Test cases ---

    @Test
    fun `markDelivered stores ID and hasDelivered returns true`() {
        store.markDelivered("msg-001")
        assertTrue(store.hasDelivered("msg-001"))
    }

    @Test
    fun `markRead stores ID and hasRead returns true`() {
        store.markRead("msg-001")
        assertTrue(store.hasRead("msg-001"))
    }

    @Test
    fun `IDs not marked return false for hasDelivered`() {
        assertFalse(store.hasDelivered("msg-never-seen"))
    }

    @Test
    fun `IDs not marked return false for hasRead`() {
        assertFalse(store.hasRead("msg-never-seen"))
    }

    @Test
    fun `marking same ID twice does not duplicate`() {
        store.markDelivered("msg-dup")
        store.markDelivered("msg-dup")
        assertTrue(store.hasDelivered("msg-dup"))

        // Access the delivered set via reflection to verify size
        val deliveredField = SeenMessageStore::class.java.getDeclaredField("delivered")
        deliveredField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val delivered = deliveredField.get(store) as LinkedHashSet<String>
        assertEquals(1, delivered.size)
    }

    @Test
    fun `trimming at MAX_IDS evicts oldest entries`() {
        // Fill with MAX_IDS entries
        for (i in 1..10_000) {
            store.markDelivered("msg-$i")
        }
        assertTrue(store.hasDelivered("msg-1"))
        assertTrue(store.hasDelivered("msg-10000"))

        // Adding one more should evict the oldest (msg-1)
        store.markDelivered("msg-10001")
        assertFalse("Oldest entry should be evicted", store.hasDelivered("msg-1"))
        assertTrue("Newest entry should exist", store.hasDelivered("msg-10001"))
        assertTrue("Recent entry should still exist", store.hasDelivered("msg-10000"))
    }

    @Test
    fun `thread safety with concurrent access`() {
        // Use multiple threads to mark messages concurrently
        val threads = (1..10).map { threadId ->
            Thread {
                for (i in 1..100) {
                    store.markDelivered("thread-$threadId-msg-$i")
                    store.markRead("thread-$threadId-msg-$i")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Verify all messages were stored
        for (threadId in 1..10) {
            for (i in 1..100) {
                assertTrue(store.hasDelivered("thread-$threadId-msg-$i"))
                assertTrue(store.hasRead("thread-$threadId-msg-$i"))
            }
        }
    }

    @Test
    fun `persist is called after markDelivered`() {
        store.markDelivered("msg-persist-test")
        // Verify storeSecureValue was called (persistence happened)
        verify(mockSecure, atLeastOnce()).storeSecureValue(eq("seen_message_store_v1"), any())
    }

    @Test
    fun `persist is called after markRead`() {
        store.markRead("msg-persist-test")
        verify(mockSecure, atLeastOnce()).storeSecureValue(eq("seen_message_store_v1"), any())
    }

    @Test
    fun `markDelivered and markRead are independent`() {
        store.markDelivered("msg-independent")
        assertTrue(store.hasDelivered("msg-independent"))
        assertFalse("Read should not be set when only delivered is marked", store.hasRead("msg-independent"))

        store.markRead("msg-independent")
        assertTrue(store.hasDelivered("msg-independent"))
        assertTrue(store.hasRead("msg-independent"))
    }
}
