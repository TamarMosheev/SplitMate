package com.example.myapplication

import com.example.myapplication.ui.group.canDeleteGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delete (trash) rule: only the creator, decided by uid alone, identical for every user and screen. */
class CanDeleteGroupTest {

    @Test
    fun `the creator can delete`() {
        assertTrue(canDeleteGroup(createdBy = "uidA", currentUid = "uidA"))
    }

    @Test
    fun `a member who is not the creator cannot`() {
        assertFalse(canDeleteGroup(createdBy = "uidA", currentUid = "uidB"))
    }

    @Test
    fun `each creator can delete their own groups, whoever they are`() {
        listOf("uid-1", "uid-2", "any-other-uid").forEach {
            assertTrue(canDeleteGroup(createdBy = it, currentUid = it))
        }
    }

    @Test
    fun `missing data never shows the trash`() {
        assertFalse(canDeleteGroup(createdBy = null, currentUid = "uidA"))
        assertFalse(canDeleteGroup(createdBy = "uidA", currentUid = null))
        assertFalse(canDeleteGroup(createdBy = "", currentUid = ""))
        assertFalse(canDeleteGroup(createdBy = null, currentUid = null))
    }
}
