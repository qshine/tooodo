package com.tooodo.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagStoreTest {
    @Test
    fun deleteTag_preservesTasksAndOtherTagsAfterReload() {
        val prefs = MemoryPreferences(mapOf(
            "tags" to """[{"id":1,"name":"工作","color":11},{"id":2,"name":"生活","color":22}]""",
            "todos" to """[
                {"id":101,"text":"已完成的旧任务","day":19900,"done":true,"tagId":1},
                {"id":102,"text":"每周任务","day":19901,"repeat":"WEEKLY","weekdays":[1,3],"doneDay":19910,"tagId":1},
                {"id":103,"text":"其他标签的任务","day":19920,"tagId":2},
                {"id":104,"text":"没有标签的旧任务","day":19930}
            ]""",
            "wake_enabled" to false,
        ))
        val store = TodoStore(prefs)
        val before = store.all()
        val otherTag = store.tags().last()

        store.deleteTag(1)

        val reloaded = TodoStore(prefs)
        assertEquals(listOf(otherTag), reloaded.tags())
        assertEquals(before.map { if (it.tagId == 1) it.copy(tagId = null) else it }, reloaded.all())
        assertFalse(reloaded.wakeEnabled)
    }

    @Test
    fun deleteMissingTag_doesNotChangeStoredData() {
        val prefs = MemoryPreferences(mapOf(
            "tags" to """[{"id":1,"name":"工作","color":11}]""",
            "todos" to """[{"id":101,"text":"任务","day":19900,"tagId":1}]""",
        ))
        val before = prefs.all

        TodoStore(prefs).deleteTag(99)

        assertEquals(before, prefs.all)
    }

    @Test
    fun deleteMiddleTag_newTagDoesNotCollideWithRemainingTag() {
        val prefs = MemoryPreferences()
        val store = TodoStore(prefs)
        assertTrue(store.addTag("工作"))
        assertTrue(store.addTag("生活"))
        assertTrue(store.addTag("学习"))
        val learning = store.tags().last()

        store.deleteTag(2)
        assertTrue(store.addTag("运动"))

        assertEquals(listOf(1, 3, 4), TodoStore(prefs).tags().map { it.id })
        assertEquals(learning, store.tags()[1])
    }

    @Test
    fun deletingHighestAndLastLegacyTags_doesNotReuseIdsAfterReload() {
        val prefs = MemoryPreferences(mapOf(
            "tags" to """[{"id":1,"name":"工作","color":11},{"id":4,"name":"旧标签","color":44}]""",
        ))
        TodoStore(prefs).deleteTag(4)
        val store = TodoStore(prefs)
        assertTrue(store.addTag("新标签"))
        assertEquals(5, store.tags().last().id)

        store.deleteTag(1)
        store.deleteTag(5)
        assertTrue(TodoStore(prefs).tags().isEmpty())
        val reloaded = TodoStore(prefs)
        assertTrue(reloaded.addTag("重新创建"))
        assertEquals(6, reloaded.tags().single().id)
    }

    @Test
    fun restoreAfterTagDeletion_restoresTaskWithoutDeletedTag() {
        val prefs = MemoryPreferences(mapOf(
            "tags" to """[{"id":1,"name":"工作","color":11},{"id":2,"name":"生活","color":22}]""",
            "todos" to """[{"id":101,"text":"保留这条任务","day":19900,"done":true,"tagId":2}]""",
        ))
        val store = TodoStore(prefs)
        val (index, deleted) = store.delete(101)!!
        store.deleteTag(2)
        assertTrue(store.addTag("新标签"))

        store.restore(index, deleted)

        assertEquals(listOf(deleted.copy(tagId = null)), TodoStore(prefs).all())
        assertEquals(3, store.tags().last().id)
    }

    @Test
    fun addingTaskWithDeletedSelection_doesNotSaveDanglingTag() {
        val prefs = MemoryPreferences()
        val store = TodoStore(prefs)
        assertTrue(store.addTag("工作"))
        val selected = store.tags().single().id
        store.deleteTag(selected)

        store.add("待办仍应保留", day = 19900, tagId = selected)

        val task = TodoStore(prefs).all().single()
        assertEquals("待办仍应保留", task.text)
        assertEquals(19900L, task.day)
        assertNull(task.tagId)
    }

    @Test
    fun deletedTagName_canBeCreatedAgainWithNewId() {
        val store = TodoStore(MemoryPreferences())
        assertTrue(store.addTag("工作"))
        assertFalse(store.addTag(" 工作 "))
        store.deleteTag(1)

        assertTrue(store.addTag("工作"))

        assertEquals(2, store.tags().single().id)
    }
}

/** Stores editor changes together, like SharedPreferences, without Android runtime dependencies. */
private class MemoryPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    private val values = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, value: MutableSet<String>?) = put(key, value?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }
        override fun apply() { commit() }
    }
}
