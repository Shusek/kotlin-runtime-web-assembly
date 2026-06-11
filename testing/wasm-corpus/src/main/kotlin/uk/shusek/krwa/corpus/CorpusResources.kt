package uk.shusek.krwa.corpus

import java.io.InputStream

object CorpusResources {
    @JvmStatic
    fun getResource(name: String): InputStream =
        CorpusResources::class.java.getResourceAsStream("/$name")
            ?: throw IllegalArgumentException("Resource not found: /$name")
}
