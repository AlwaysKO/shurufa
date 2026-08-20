package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.Phrase

@Dao
interface PhraseDao : BaseDao<Phrase> {

    @Query("select * from phrase ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Phrase>

    @Query("select * from phrase  where qwerty = :index or t9 = :index or lx17 = :index ORDER BY isKeep DESC, time DESC")
    fun query(index: String): List<Phrase>

    @Query("delete from phrase where content = :content")
    fun deleteByContent(content: String)

    @Query("select * from phrase where content = :content")
    fun queryByContent(content: String): Phrase

    @Query("select * from phrase where cloudId > 0")
    fun getCloudPhrases(): List<Phrase>

    @Query("update phrase set cloudId = :cloudId where content = :content")
    fun updateCloudId(content: String, cloudId: Long)

    @Query("update phrase set content = :content, t9 = :t9, qwerty = :qwerty, lx17 = :lx17 where cloudId = :cloudId")
    fun updateCloudContent(cloudId: Long, content: String, t9: String, qwerty: String, lx17: String)

    @Query("delete from phrase where cloudId = :cloudId")
    fun deleteByCloudId(cloudId: Long)

    @Query("delete from phrase")
    fun deleteAll()
}
