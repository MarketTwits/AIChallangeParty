package com.markettwits.aichallenge

import com.markettwits.aichallenge.club.configureClubRoutes
import com.markettwits.aichallenge.mcp.configureOrchestrationRoutes
import com.markettwits.aichallenge.rag.*
import com.markettwits.aichallenge.team.TeamAssistantAgent
import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.team.configureTeamRoutes
import com.markettwits.aichallenge.tools.TeamToolManager
import com.markettwits.aichallenge.tools.ToolManager
import com.markettwits.aichallenge.tools.configureToolRoutes
import com.markettwits.aichallenge.whatsnew.WhatsNewService
import com.markettwits.aichallenge.whatsnew.configureWhatsNewRoutes
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

fun main() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    val apiKey = dotenv["ANTHROPIC_API_KEY"]
        ?: System.getenv("ANTHROPIC_API_KEY")
        ?: throw IllegalStateException("ANTHROPIC_API_KEY not found in .env file or environment variables")

    val huggingFaceKey = dotenv["HUGGINGFACE_API_KEY"]
        ?: System.getenv("HUGGINGFACE_API_KEY")
        ?: throw IllegalStateException("HUGGINGFACE_API_KEY not found in .env file or environment variables")

    // Load GitHub Token (optional)
    val gitHubToken = dotenv["GITHUB_TOKEN"] ?: System.getenv("GITHUB_TOKEN") ?: ""
    if (gitHubToken.isNotEmpty()) {
        System.setProperty("GITHUB_TOKEN", gitHubToken)
    }

    // Load Local LLM URL (optional, for Day 26)
    val localLlmUrl = dotenv["LOCAL_LLM_URL"] ?: System.getenv("LOCAL_LLM_URL") ?: ""

    println("Loaded API Keys:")
    println("  ANTHROPIC_API_KEY: ${apiKey.take(10)}... (length: ${apiKey.length})")
    println("  HUGGINGFACE_API_KEY: ${huggingFaceKey.take(10)}... (length: ${huggingFaceKey.length})")
    if (gitHubToken.isNotEmpty()) {
        println("  GITHUB_TOKEN: ${gitHubToken.take(10)}...${gitHubToken.takeLast(4)} (length: ${gitHubToken.length})")
    } else {
        println("  GITHUB_TOKEN: Not configured")
    }
    if (localLlmUrl.isNotEmpty()) {
        println("  LOCAL_LLM_URL: $localLlmUrl")
    } else {
        println("  LOCAL_LLM_URL: Not configured (local coach will not be available)")
    }

    val repository = ConversationRepository()
    val sessionManager = SessionManager(repository)

    val reminderRepository = ReminderRepository()
    val reminderScheduler = ReminderScheduler(reminderRepository, repository)

    // Initialize MCP Integration Service
    val anthropicClient = AnthropicClient(apiKey)
    val mcpIntegrationService = DemoMcpIntegration(reminderRepository, anthropicClient)

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    println("Starting server on port $port")
    println("Initializing reminder services and MCP integration...")

    // Start reminder scheduler in background
    // reminderScheduler.start()

    // MCP integration service ready
    println("✅ MCP Integration Service initialized successfully")

    // Initialize RAG system for Day 20 Task
    println("🔧 Initializing Tool System (Day 20)...")
    val database = Database.connect("jdbc:sqlite:data/documents.db", driver = "org.sqlite.JDBC")
    val embeddingClient = OllamaEmbeddingClient()
    val llmClient = OllamaLLMClient(model = "llama3.2")
    val vectorStore = VectorStore()
    val ragQueryService = RAGQueryService(database, embeddingClient, llmClient, vectorStore)

    // Initialize Tool Manager with RAG
    val toolManager = ToolManager(
        ragQueryService = ragQueryService,
        repositoryPath = System.getProperty("user.dir")
    )

    println("✅ Tool System initialized with ${toolManager.registry.getToolCount()} tools")
    println("📋 Available tools:")
    toolManager.registry.getAllTools().forEach { tool ->
        println("   - ${tool.name} (${tool.type})")
    }

    // Check if RAG is ready for documentation
    val isRagReady = runBlocking {
        try {
            ragQueryService.isReady()
        } catch (e: Exception) {
            println("⚠️  RAG system unavailable: ${e.message}")
            println("   Code review will work without RAG context")
            false
        }
    }

    // Get project root for file operations
    val projectRoot = System.getProperty("user.dir")

    if (isRagReady) {
        println("✅ RAG system is ready - documentation indexed")
        val stats = ragQueryService.getStats()
        println("   📊 Total chunks: ${stats["totalChunks"]}, Sources: ${stats["sources"]}")
    } else {
        println("⚠️  RAG system not ready - auto-indexing project documentation...")
        println("   Indexing README.md and docs/ folder...")

        runBlocking {
            try {
                // Prepare project documentation directory
                val projectDocsDir = java.io.File("$projectRoot/data/project_docs")
                projectDocsDir.mkdirs()

                // Copy README.md
                val readmeFile = java.io.File("$projectRoot/README.md")
                if (readmeFile.exists()) {
                    readmeFile.copyTo(java.io.File(projectDocsDir, "README.md"), overwrite = true)
                    println("   ✅ Copied README.md")
                }

                // Copy CLAUDE.md (project instructions)
                val claudeFile = java.io.File("$projectRoot/CLAUDE.md")
                if (claudeFile.exists()) {
                    claudeFile.copyTo(java.io.File(projectDocsDir, "CLAUDE.md"), overwrite = true)
                    println("   ✅ Copied CLAUDE.md")
                }

                // Copy docs/ folder
                val docsDir = java.io.File("$projectRoot/docs")
                if (docsDir.exists() && docsDir.isDirectory) {
                    docsDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            file.copyTo(java.io.File(projectDocsDir, file.name), overwrite = true)
                        }
                    }
                    println("   ✅ Copied docs/ folder (${docsDir.listFiles()?.size ?: 0} files)")
                }

                // Count files to index
                val filesToIndex = projectDocsDir.listFiles()?.filter { it.isFile } ?: emptyList()
                if (filesToIndex.isNotEmpty()) {
                    println("   📁 Files to index: ${filesToIndex.size}")

                    // Build knowledge base
                    val ragRetriever = RAGRetriever(database, embeddingClient)
                    ragRetriever.buildKnowledgeBase(projectDocsDir.absolutePath)

                    val stats = ragQueryService.getStats()
                    println("   ✅ Documentation indexed successfully!")
                    println("   📊 Total chunks: ${stats["totalChunks"]}, Sources: ${stats["sources"]}")
                } else {
                    println("   ⚠️  No documentation found to index")
                    println("   💡 Create README.md or add files to docs/ folder")
                }
            } catch (e: Exception) {
                println("   ❌ Auto-indexing failed: ${e.message}")
                println("   💡 You can manually index later via: ./index_project_docs.sh")
                e.printStackTrace()
            }
        }
    }

    // Initialize Club Support System (Day 22)
    println("🏃 Initializing Club Support System (Day 22)...")

    // Check if club documentation is indexed
    val hasClubData = runBlocking {
        try {
            val stats = ragQueryService.getStats()
            val files = stats["files"] as? List<*> ?: emptyList<String>()
            files.any { it.toString().startsWith("club_") }
        } catch (e: Exception) {
            false
        }
    }

    if (hasClubData) {
        println("✅ Club documentation is indexed and ready")
    } else {
        println("⚠️  Club documentation not found in RAG")
        println("   💡 Please run indexing script first: ./scripts/index_club_data.sh")
    }

    // Create HTTP client for SportSauce API
    val clubHttpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
        }
    }

    val clubApiClient = com.markettwits.aichallenge.sportsauce.club.SportSauceClubsNetworkApiBase(clubHttpClient)

    // Initialize Club Tool Manager
    val clubToolManager = com.markettwits.aichallenge.tools.ClubToolManager(
        ragQueryService = ragQueryService,
        apiClient = clubApiClient
    )

    println("✅ Club Tool System initialized with ${clubToolManager.registry.getToolCount()} tools")
    println("📋 Club tools:")
    clubToolManager.registry.getAllTools().forEach { tool ->
        println("   - ${tool.name} (${tool.type})")
    }

    // Initialize Club Support Agent
    val clubSupportAgent = com.markettwits.aichallenge.club.ClubSupportAgent(
        anthropicClient = anthropicClient,
        clubToolManager = clubToolManager
    )

    println("✅ Club Support Agent initialized successfully")
    println("🌐 Club Support UI: http://localhost:$port/club-support.html")

    // Initialize Team Assistant System (Day 23)
    println("👥 Initializing Team Assistant System (Day 23)...")

    // Create ticket processor with AI-powered classification
    val ticketProcessor = TicketProcessor(
        anthropicClient = anthropicClient,
        dataDir = "$projectRoot/data"
    )

    // Initialize Team Tool Manager with RAG and ticket processor
    val teamToolManager = TeamToolManager(
        ticketProcessor = ticketProcessor,
        ragQueryService = ragQueryService
    )

    println("✅ Team Tool System initialized with ${teamToolManager.getRegistry().getToolCount()} tools")
    println("📋 Team tools:")
    for (tool in teamToolManager.getRegistry().getAllTools()) {
        println("   - ${tool.name} (${tool.type})")
    }

    // Initialize Team Assistant Agent
    val teamAssistantAgent = TeamAssistantAgent(
        anthropicClient = anthropicClient,
        teamToolManager = teamToolManager
    )

    println("✅ Team Assistant Agent initialized successfully")
    println("🌐 Team Assistant UI: http://localhost:$port/team-assistant.html")

    // Initialize Local LLM clients (LM Studio)
    var lmStudioClient: LMStudioClient? = null
    var localCoachAgent: LocalCoachAgent? = null
    var stacktraceAnalysisService: StacktraceAnalysisService? = null
    if (localLlmUrl.isNotEmpty()) {
        println("🤖 Initializing Local LLM integrations (LM Studio)...")
        try {
            val candidate = LMStudioClient(localLlmUrl)

            val isAvailable = runBlocking {
                try {
                    candidate.isAvailable()
                } catch (e: Exception) {
                    println("⚠️  Failed to connect to LM Studio: ${e.message}")
                    false
                }
            }

            if (isAvailable) {
                lmStudioClient = candidate
                localCoachAgent = LocalCoachAgent(candidate)
                stacktraceAnalysisService = StacktraceAnalysisService(
                    lmStudioClient = candidate,
                    repository = StacktraceTaskRepository()
                )

                val models = runBlocking { candidate.listModels() }
                println("✅ Local LLM integrations are ready")
                println("   📊 Available models: ${models.joinToString(", ")}")
                println("🌐 Local Coach UI: http://localhost:$port/local-coach.html")
                println("🌐 Stacktrace analyst: POST /local-stacktrace/tasks (LM Studio)")
            } else {
                println("⚠️  LM Studio is not available at $localLlmUrl")
                println("   💡 Make sure LM Studio is running and accessible")
                candidate.close()
            }
        } catch (e: Exception) {
            println("❌ Failed to initialize Local LLM integrations: ${e.message}")
            e.printStackTrace()
        }
    } else {
        println("⚠️  LOCAL_LLM_URL not configured - Local LLM features disabled")
        println("   💡 Add LOCAL_LLM_URL to .env to enable local AI coach and stacktrace analyst")
    }

    // Initialize What's New generator (Day 24)
    val whatsNewService = WhatsNewService(
        anthropicClient = anthropicClient,
        repositoryPath = projectRoot
    )

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }

        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
        }

        configureRouting(
            sessionManager,
            apiKey,
            huggingFaceKey,
            repository,
            reminderRepository,
            reminderScheduler,
            mcpIntegrationService,
            anthropicClient,
            localCoachAgent,
            localLlmUrl,
            stacktraceAnalysisService
        )

        // Configure MCP Orchestration routes (Day 14)
        configureOrchestrationRoutes(
            anthropicClient,
            reminderRepository
        )

        // Configure Tool System routes (Day 20)
        configureToolRoutes(toolManager)

        // Configure Club Support routes (Day 22)
        configureClubRoutes(
            clubSupportAgent = clubSupportAgent,
            clubToolManager = clubToolManager
        )

        // Configure Team Assistant routes (Day 23)
        configureTeamRoutes(
            teamAssistant = teamAssistantAgent,
            teamToolManager = teamToolManager,
            ticketProcessor = ticketProcessor
        )

        // Configure What's New routes (Day 24)
        configureWhatsNewRoutes(whatsNewService)

        routing {
            staticResources("/", "static")
        }
    }.start(wait = true)
}
