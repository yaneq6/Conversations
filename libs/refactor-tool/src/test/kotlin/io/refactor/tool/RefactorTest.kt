package io.refactor.tool

import org.junit.Test

const val FILE_PATH =
    "/home/janek/projects/Conversations/src/main/java/eu/siacs/conversations/services/XmppConnectionService.kt"

class RefactorTest {

    @Test
    fun testV2() {
        refactor(FILE_PATH)
    }
}