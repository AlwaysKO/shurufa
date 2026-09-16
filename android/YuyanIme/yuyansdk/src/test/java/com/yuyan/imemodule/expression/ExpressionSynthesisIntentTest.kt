package com.yuyan.imemodule.expression

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionSynthesisIntentTest {
    @Test fun `共享玩笑表达矩阵包含认真语境与普通夸奖反例`() {
        val cases = Json.parseToJsonElement(File("../../../assets/expression/query/synthesis-intent-cases.json").readText()).jsonArray
        for (case in cases) {
            val row = case.jsonObject
            val query = row.getValue("query").jsonPrimitive.content
            assertEquals(query, row.getValue("playful").jsonPrimitive.boolean, ExpressionSynthesisIntent.matches(query))
        }
    }
}
