package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.SkbFun

@Dao
interface SkbFunDao : BaseDao<SkbFun> {
    @Query("select * from skbfun  where isKeep = 0 ORDER BY position ASC")
    fun getAllMenu(): List<SkbFun>

    @Query(
        """SELECT kept.* FROM skbfun AS kept
            WHERE kept.isKeep = 1
            ORDER BY COALESCE(
                (SELECT menu.position FROM skbfun AS menu
                 WHERE menu.name = kept.name AND menu.isKeep = 0),
                kept.position
            ) ASC, kept.name ASC""",
    )
    fun getALlBarMenu(): List<SkbFun>

    @Query("select * from skbfun where name = :name AND isKeep = 1")
    fun getBarMenu(name: String):SkbFun?

    @Query("delete from skbfun")
    fun deleteAll()
}