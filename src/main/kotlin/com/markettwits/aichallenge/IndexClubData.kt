package com.markettwits.aichallenge

import com.markettwits.aichallenge.rag.OllamaEmbeddingClient
import com.markettwits.aichallenge.rag.RAGRetriever
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.io.File

/**
 * Standalone script to index club documentation
 * Run with: ./gradlew runIndexClubData
 */
fun main() {
    println("🏃 Club Data Indexing Script")
    println("=" * 50)
    println()

    val projectRoot = System.getProperty("user.dir")
    val clubSourceDir = File("$projectRoot/data/sportsauce")
    val clubDocsDir = File("$projectRoot/data/club_docs")

    // Step 1: Prepare club_docs directory
    println("📁 Preparing club_docs directory...")
    clubDocsDir.mkdirs()

    // Step 2: Copy club documentation files
    println("📄 Copying club documentation files...")
    val clubFiles = listOf("club_info.md", "club_events.md", "club_faq.md")
    var copiedCount = 0

    clubFiles.forEach { fileName ->
        val sourceFile = File(clubSourceDir, fileName)
        val targetFile = File(clubDocsDir, fileName)

        if (sourceFile.exists()) {
            sourceFile.copyTo(targetFile, overwrite = true)
            println("   ✅ Copied: $fileName")
            copiedCount++
        } else {
            println("   ⚠️  Not found: $fileName")
        }
    }

    if (copiedCount == 0) {
        println()
        println("❌ No files found to index")
        println("   Please ensure files exist in: $clubSourceDir")
        return
    }

    println()
    println("📊 Total files to index: $copiedCount")
    println()

    // Step 3: Initialize RAG system
    println("🔧 Initializing RAG system...")
    val database = Database.connect("jdbc:sqlite:data/documents.db", driver = "org.sqlite.JDBC")
    val embeddingClient = OllamaEmbeddingClient()

    // Step 4: Index documentation
    println("🚀 Starting indexing process...")
    println()

    runBlocking {
        try {
            val ragRetriever = RAGRetriever(database, embeddingClient)

            // Index with clearExisting = false to preserve existing project docs
            ragRetriever.buildKnowledgeBase(clubDocsDir.absolutePath, clearExisting = false)

            println()
            println("✅ Indexing completed successfully!")
            println()
            println("📊 Final Statistics:")
            println("   Club documentation indexed and ready")
            println()
            println("🎉 Club documentation is ready to use!")
            println("   Start the server: ./gradlew run")

        } catch (e: Exception) {
            println()
            println("❌ Indexing failed: ${e.message}")
            e.printStackTrace()
            return@runBlocking
        }
    }
}

operator fun String.times(count: Int): String = this.repeat(count)
