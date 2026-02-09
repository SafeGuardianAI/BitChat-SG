package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ai.AIManager
import com.bitchat.android.ai.AIChatService
import kotlinx.coroutines.runBlocking
import com.bitchat.android.model.BitchatMessage
import java.util.Date

/**
 * Handles processing of IRC-style commands
 */
class CommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager
) {
    
    // Available commands list
    private val baseCommands = listOf(
        CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
        CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
        CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
        CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
        CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
        CommandSuggestion("/slap", emptyList(), "<nickname>", "slap someone with a trout"),
        CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
        CommandSuggestion("/w", emptyList(), null, "see who's online"),
        CommandSuggestion("/ai", emptyList(), "[on|off]", "enable or disable AI chat"),
        CommandSuggestion("/tts", emptyList(), "[on|off]", "enable or disable text-to-speech"),
        CommandSuggestion("/rag", emptyList(), "[on|off]", "enable or disable retrieval-augmented generation"),
        CommandSuggestion("/init-rag", emptyList(), null, "initialize RAG with documents from assets/storage"),
        CommandSuggestion("/rag-status", emptyList(), null, "show RAG service status and statistics"),
        CommandSuggestion("/structured", emptyList(), "[on|off]", "enable or disable structured output"),
        CommandSuggestion("/structured-type", emptyList(), "[off|prompt|grammar]", "set structured output mode"),
        CommandSuggestion("/ask", emptyList(), "<question>", "ask the AI a question"),
        CommandSuggestion("/speak", emptyList(), "<text>", "speak text using TTS"),
        CommandSuggestion("/voice-mode", emptyList(), null, "toggle voice mode"),
        CommandSuggestion("/test-ai", emptyList(), null, "test AI model functionality"),
        CommandSuggestion("/test-asr", emptyList(), null, "test ASR service functionality"),
        CommandSuggestion("/models", emptyList(), null, "list downloaded models"),
        CommandSuggestion("/select-model", emptyList(), "<model-id>", "select AI model to use"),
        CommandSuggestion("/model-status", emptyList(), null, "show model selection status"),
        CommandSuggestion("/recover", emptyList(), null, "recover from AI crash"),
        CommandSuggestion("/download", emptyList(), "<model-id>", "download AI model from NexaAI Hub"),
        CommandSuggestion("/test-rescue", emptyList(), null, "test rescue API connection"),
        CommandSuggestion("/test-rescue-submit", emptyList(), null, "test victim report submission"),
        CommandSuggestion("/test-tts", emptyList(), "<text>", "test text-to-speech"),
        CommandSuggestion("/test-backend-switch", emptyList(), null, "test MongoDB/Firebase backend switching"),
        CommandSuggestion("/test-diagnostics", emptyList(), null, "run full system diagnostics"),
        CommandSuggestion("/debug-logs", emptyList(), null, "show debug logs"),
        CommandSuggestion("/debug-clear", emptyList(), null, "clear debug logs"),
    )
    
    // MARK: - Command Processing
    
    fun processCommand(command: String, meshService: BluetoothMeshService, myPeerID: String, onSendMessage: (String, List<String>, String?) -> Unit, viewModel: ChatViewModel? = null): Boolean {
        if (!command.startsWith("/")) return false
        
        val parts = command.split(" ")
        val cmd = parts.first().lowercase()
        when (cmd) {
            "/j", "/join" -> handleJoinCommand(parts, myPeerID)
            "/m", "/msg" -> handleMessageCommand(parts, meshService)
            "/w" -> handleWhoCommand(meshService, viewModel)
            "/clear" -> handleClearCommand()
            "/pass" -> handlePassCommand(parts, myPeerID)
            "/block" -> handleBlockCommand(parts, meshService)
            "/unblock" -> handleUnblockCommand(parts, meshService)
            "/hug" -> handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage)
            "/slap" -> handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟", meshService, myPeerID, onSendMessage)
            "/channels" -> handleChannelsCommand()
            "/ai" -> handleAiToggle(parts, meshService)
            "/tts" -> handleTtsToggle(parts, meshService)
            "/asr" -> handleAsrToggle(parts, meshService)
            "/rag" -> handleRagToggle(parts, meshService)
            "/init-rag" -> handleInitRAG(meshService)
            "/rag-status" -> handleRAGStatus(meshService)
            "/structured" -> handleStructuredToggle(parts, meshService)
            "/structured-type" -> handleStructuredTypeCommand(parts, meshService)
            "/ask" -> handleAsk(parts, meshService)
            "/speak" -> handleSpeak(parts, meshService)
            "/voice-mode" -> handleVoiceMode(meshService)
            "/test-ai" -> handleTestAI(meshService)
            "/test-asr" -> handleTestASR(meshService)
            "/models" -> handleListModels(meshService)
            "/select-model" -> handleSelectModel(parts, meshService)
            "/model-status" -> handleModelStatus(meshService)
            "/recover" -> handleRecover(meshService)
            "/download" -> handleDownload(parts, meshService)
            "/test-rescue" -> handleTestRescueAPI(meshService)
            "/test-rescue-submit" -> handleTestRescueSubmit(meshService)
            "/test-tts" -> handleTestTTS(parts, meshService)
            "/test-backend-switch" -> handleTestBackendSwitch(meshService)
            "/test-diagnostics" -> handleTestDiagnostics(meshService)
            "/debug-logs" -> handleShowDebugLogs()
            "/debug-clear" -> handleClearDebugLogs()
            else -> handleUnknownCommand(cmd)
        }
        
        return true
    }
    
    private fun handleJoinCommand(parts: List<String>, myPeerID: String) {
        if (parts.size > 1) {
            val channelName = parts[1]
            val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
            val password = if (parts.size > 2) parts[2] else null
            val success = channelManager.joinChannel(channel, password, myPeerID)
            if (success) {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "joined channel $channel",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "usage: /join <channel>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val peerID = getPeerIDForNickname(targetName, meshService)
            
            if (peerID != null) {
                val success = privateChatManager.startPrivateChat(peerID, meshService)
                
                if (success) {
                    if (parts.size > 2) {
                        val messageContent = parts.drop(2).joinToString(" ")
                        val recipientNickname = getPeerNickname(peerID, meshService)
                        privateChatManager.sendPrivateMessage(
                            messageContent, 
                            peerID, 
                            recipientNickname,
                            state.getNicknameValue(),
                            getMyPeerID(meshService)
                        ) { content, peerIdParam, recipientNicknameParam, messageId ->
                            // This would trigger the actual mesh service send
                            sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                        }
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "started private chat with $targetName",
                            timestamp = Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(systemMessage)
                    }
                }
            } else {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "user '$targetName' not found. they may be offline or using a different nickname.",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "usage: /msg <nickname> [message]",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleWhoCommand(meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Channel-aware who command (matches iOS behavior)
        val (peerList, contextDescription) = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bitchat.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: show Bluetooth-connected peers
                    val connectedPeers = state.getConnectedPeersValue()
                    val peerList = connectedPeers.joinToString(", ") { peerID ->
                        getPeerNickname(peerID, meshService)
                    }
                    Pair(peerList, "online users")
                }
                
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    // Location channel: show geohash participants
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    val participantList = geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from list
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }.joinToString(", ")
                    
                    Pair(participantList, "participants in ${selectedChannel.channel.geohash}")
                }
            }
        } else {
            // Fallback to mesh behavior
            val connectedPeers = state.getConnectedPeersValue()
            val peerList = connectedPeers.joinToString(", ") { peerID ->
                getPeerNickname(peerID, meshService)
            }
            Pair(peerList, "online users")
        }
        
        val systemMessage = BitchatMessage(
            sender = "system",
            content = if (peerList.isEmpty()) {
                "no one else is around right now."
            } else {
                "$contextDescription: $peerList"
            },
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleClearCommand() {
        when {
            state.getSelectedPrivateChatPeerValue() != null -> {
                // Clear private chat
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                messageManager.clearPrivateMessages(peerID)
            }
            state.getCurrentChannelValue() != null -> {
                // Clear channel messages
                val channel = state.getCurrentChannelValue()!!
                messageManager.clearChannelMessages(channel)
            }
            else -> {
                // Clear main messages
                messageManager.clearMessages()
            }
        }
    }

    private fun handlePassCommand(parts: List<String>, peerID: String) {
        val currentChannel = state.getCurrentChannelValue()

        if (currentChannel == null) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "you must be in a channel to set a password.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return
        }

        if (parts.size == 2){
            if(!channelManager.isChannelCreator(channel = currentChannel, peerID = peerID)){
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "you must be the channel creator to set a password.",
                    timestamp = Date(),
                    isRelay = false
                )
                channelManager.addChannelMessage(currentChannel,systemMessage,null)
                return
            }
            val newPassword = parts[1]
            channelManager.setChannelPassword(currentChannel, newPassword)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "password changed for channel $currentChannel",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
        else{
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "usage: /pass <password>",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
    }
    
    private fun handleBlockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.blockPeerByNickname(targetName, meshService)
        } else {
            // List blocked users
            val blockedInfo = privateChatManager.listBlockedUsers()
            val systemMessage = BitchatMessage(
                sender = "system",
                content = blockedInfo,
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleUnblockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.unblockPeerByNickname(targetName, meshService)
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "usage: /unblock <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleActionCommand(
        parts: List<String>, 
        verb: String, 
        object_: String, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val actionMessage = "* ${state.getNicknameValue() ?: "someone"} $verb $targetName $object_ *"

            // If we're in a geohash location channel, don't add a local echo here.
            // GeohashViewModel.sendGeohashMessage() will add the local echo with proper metadata.
            val isInLocationChannel = state.selectedLocationChannel.value is com.bitchat.android.geohash.ChannelID.Location

            // Send as regular message
            if (state.getSelectedPrivateChatPeerValue() != null) {
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                privateChatManager.sendPrivateMessage(
                    actionMessage,
                    peerID,
                    getPeerNickname(peerID, meshService),
                    state.getNicknameValue(),
                    myPeerID
                ) { content, peerIdParam, recipientNicknameParam, messageId ->
                    sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                }
            } else if (isInLocationChannel) {
                // Let the transport layer add the echo; just send it out
                onSendMessage(actionMessage, emptyList(), null)
            } else {
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: myPeerID,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = state.getCurrentChannelValue()
                )
                
                if (state.getCurrentChannelValue() != null) {
                    channelManager.addChannelMessage(state.getCurrentChannelValue()!!, message, myPeerID)
                    onSendMessage(actionMessage, emptyList(), state.getCurrentChannelValue())
                } else {
                    messageManager.addMessage(message)
                    onSendMessage(actionMessage, emptyList(), null)
                }
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "usage: /${parts[0].removePrefix("/")} <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleChannelsCommand() {
        val allChannels = channelManager.getJoinedChannelsList()
        val channelList = if (allChannels.isEmpty()) {
            "no channels joined"
        } else {
            "joined channels: ${allChannels.joinToString(", ")}"
        }
        
        val systemMessage = BitchatMessage(
            sender = "system",
            content = channelList,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }

    // ===== AI COMMANDS =====
    private fun getAI(contextService: BluetoothMeshService): Pair<AIManager, AIChatService> {
        val context = contextService.getContext()
        val aiManager = AIManager(context)
        val aiChat = AIChatService(context, aiManager)
        return Pair(aiManager, aiChat)
    }

    private fun handleAiToggle(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        
        val shouldEnable = when (arg) {
            "on" -> true
            "off" -> false
            else -> !ai.preferences.aiEnabled
        }
        
        if (shouldEnable) {
            // Enable and load model
            runBlocking {
                val result = ai.enableAI()
                if (result.isSuccess) {
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "ai enabled and model loaded successfully",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(msg)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "unknown error"
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "ai enable failed: $error",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(msg)
                }
            }
        } else {
            // Disable
            ai.disableAI()
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "ai disabled",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
        }
    }

    private fun handleTtsToggle(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        ai.preferences.ttsEnabled = when (arg) {
            "on" -> true
            "off" -> false
            else -> !ai.preferences.ttsEnabled
        }
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "tts ${if (ai.preferences.ttsEnabled) "enabled" else "disabled"}",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }

    private fun handleAsrToggle(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        ai.preferences.asrEnabled = when (arg) {
            "on" -> true
            "off" -> false
            else -> !ai.preferences.asrEnabled
        }
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "asr ${if (ai.preferences.asrEnabled) "enabled" else "disabled"}",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }

    private fun handleRagToggle(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        ai.preferences.ragEnabled = when (arg) {
            "on" -> true
            "off" -> false
            else -> !ai.preferences.ragEnabled
        }
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "rag ${if (ai.preferences.ragEnabled) "enabled" else "disabled"}",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }

    private fun handleInitRAG(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        runBlocking {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "initializing RAG with documents...",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            
            try {
                val result = ai.initializeRAGWithDocuments()
                if (result.isSuccess) {
                    val chunkCount = result.getOrNull() ?: 0
                    val stats = ai.getRAGStats()
                    val statusMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = buildString {
                            append("✅ RAG initialized successfully\n")
                            append("📊 Added $chunkCount chunks\n")
                            append("📚 Total chunks: ${stats.totalChunks}\n")
                            append("📏 Embedding dimension: ${stats.embeddingDimension}\n")
                            append("🔍 Has embeddings: ${stats.hasEmbeddings}\n")
                            append("✅ Ready: ${stats.isReady}")
                        },
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(statusMsg)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "unknown error"
                    val errorMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "❌ Failed to initialize RAG: $error",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "❌ Error initializing RAG: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleRAGStatus(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        val stats = ai.getRAGStats()
        val rerankerStatus = ai.getRerankerStatus()
        val documentManager = ai.getDocumentManager()
        
        val statusMsg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = buildString {
                append("📊 RAG Service Status\n")
                append("═══════════════════════════════════\n")
                append("🔍 RAG Ready: ${stats.isReady}\n")
                append("📚 Total Chunks: ${stats.totalChunks}\n")
                append("📏 Embedding Dimension: ${stats.embeddingDimension}\n")
                append("🔢 Has Embeddings: ${stats.hasEmbeddings}\n")
                append("⚙️ RAG Enabled: ${ai.preferences.ragEnabled}\n")
                append("🎯 Rerank Enabled: ${ai.preferences.rerankEnabled}\n")
                append("📈 Rerank Top N: ${ai.preferences.rerankTopN}\n")
                append("🔧 Reranker Status: $rerankerStatus\n")
                append("📁 PDFs in Assets: ${documentManager.getAvailablePDFsInAssets().size}\n")
                append("📁 PDFs in Storage: ${documentManager.getAvailablePDFs().size}\n")
                if (stats.totalChunks == 0) {
                    append("\n💡 Use '/init-rag' to load documents")
                }
            },
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(statusMsg)
    }

    private fun handleStructuredToggle(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        ai.preferences.structuredOutput = when (arg) {
            "on" -> true
            "off" -> false
            else -> !ai.preferences.structuredOutput
        }
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "structured output ${if (ai.preferences.structuredOutput) "enabled" else "disabled"}",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }

    private fun handleStructuredTypeCommand(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val arg = parts.getOrNull(1)?.lowercase()
        ai.preferences.structuredOutputMode = when (arg) {
            "off" -> com.bitchat.android.ai.StructuredOutputMode.OFF
            "prompt" -> com.bitchat.android.ai.StructuredOutputMode.PROMPT
            "grammar" -> com.bitchat.android.ai.StructuredOutputMode.GRAMMAR
            else -> ai.preferences.structuredOutputMode
        }
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "structured output type set to ${ai.preferences.structuredOutputMode}",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }

    private fun handleAsk(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, aiChat) = getAI(meshService)
        val question = parts.drop(1).joinToString(" ").trim()
        if (question.isEmpty()) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "usage: /ask <question>",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        runBlocking {
            try {
                // Check if AI is enabled
                if (!ai.preferences.aiEnabled) {
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "ai is disabled. use '/ai on' to enable it first",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(msg)
                    return@runBlocking
                }
            
            // Initialize SDK if not already initialized
            if (!ai.aiService.isModelLoaded()) {
                ai.initialize()
                
                // Load model
                val modelId = ai.preferences.selectedLLMModel
                val model = com.bitchat.android.ai.ModelCatalog.getModelById(modelId)
                if (model == null) {
                    val err = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "selected LLM model not found: $modelId",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(err)
                    return@runBlocking
                }
                
                // Check if downloaded
                if (!ai.modelManager.isModelDownloaded(model)) {
                    val err = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "model '${model.name}' not downloaded. please download it first",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(err)
                    return@runBlocking
                }
                
                val load = ai.aiService.loadModel(model)
                if (load.isFailure) {
                    val err = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "failed to load model '${model.name}': ${load.exceptionOrNull()?.message}",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(err)
                    return@runBlocking
                }
            }
            
                // Stream response and collect all tokens
                var fullAnswer = ""
                val startTime = System.currentTimeMillis()
                try {
                    // Use streaming to progressively collect tokens
                    aiChat.streamResponse(question, state.getCurrentChannelValue(), useRAG = ai.preferences.ragEnabled).collect { token ->
                        fullAnswer += token
                    }
                    
                    // Calculate final statistics
                    val endTime = System.currentTimeMillis()
                    val tokenCount = fullAnswer.split("\\s+".toRegex()).size
                    val tokensPerSecond = if (endTime > startTime) {
                        (tokenCount * 1000f) / (endTime - startTime)
                    } else 0f
                    
                    // Create final message with statistics
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "ai",
                        content = fullAnswer,
                        timestamp = java.util.Date(),
                        isRelay = false,
                        isAIGenerated = true,
                        aiTokenCount = tokenCount,
                        aiGenerationTimeMs = endTime - startTime,
                        aiTokensPerSecond = tokensPerSecond,
                        aiProcessingUnit = "CPU" // TODO: Detect actual processing unit
                    )
                    messageManager.addMessage(msg)
                } catch (e: Exception) {
                    android.util.Log.e("CommandProcessor", "Error during streaming", e)
                    val errorAnswer = "Error processing your question: ${e.message ?: "Unknown error"}. Try using '/recover' to reset the AI system."
                    val errorMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "ai",
                        content = errorAnswer,
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(errorMsg)
                }
            } catch (e: Exception) {
                android.util.Log.e("CommandProcessor", "Critical error in ask command", e)
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "Critical error processing question: ${e.message ?: "Unknown error"}. Try using '/recover' to reset the AI system.",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleSpeak(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val text = parts.drop(1).joinToString(" ").trim()
        if (text.isEmpty()) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "usage: /speak <text>",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        ai.aiService.speak(text)
    }

    private fun handleVoiceMode(meshService: BluetoothMeshService) {
        val (_, aiChat) = getAI(meshService)
        // Check microphone permission first
        val hasMic = aiChat.isVoiceInputAvailable()
        if (!hasMic) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "microphone permission required. please grant it in app settings and enable ASR.",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        runBlocking {
            val response = aiChat.processVoiceInput(channelId = state.getCurrentChannelValue())
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "ai",
                content = response,
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
        }
    }

    private fun handleTestAI(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        runBlocking {
            try {
                val testResult = ai.aiService.testModel()
                val msg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "AI Test Result: $testResult",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(msg)
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "AI Test Failed: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleTestASR(meshService: BluetoothMeshService) {
        val (_, aiChat) = getAI(meshService)
        
        runBlocking {
            try {
                val asrStatus = aiChat.getASRStatus()
                val voiceAvailable = aiChat.isVoiceInputAvailable()
                val micPermission = aiChat.hasMicrophonePermission()
                
                val statusMsg = buildString {
                    append("ASR Test Results:\n")
                    append("• Status: $asrStatus\n")
                    append("• Voice Input Available: $voiceAvailable\n")
                    append("• Microphone Permission: $micPermission\n")
                    append("• ASR Enabled: ${aiChat.getAIManager().preferences.asrEnabled}")
                }
                
                val msg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = statusMsg,
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(msg)
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "ASR Test Failed: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleListModels(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        runBlocking {
            try {
                val downloadedModels = ai.modelManager.getDownloadedModels()
                val currentModel = ai.preferences.getSelectedLLMModel()
                
                val modelList = if (downloadedModels.isEmpty()) {
                    "No models downloaded. Use /download <model-id> to download a model."
                } else {
                    buildString {
                        append("Downloaded Models:\n")
                        downloadedModels.forEach { model ->
                            val isSelected = model.id == currentModel?.id
                            val status = if (isSelected) " [SELECTED]" else ""
                            append("• ${model.id}: ${model.name} (${model.fileSizeMB}MB)$status\n")
                        }
                        append("\nUse /select-model <model-id> to select a model.")
                    }
                }
                
                val msg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = modelList,
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(msg)
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "Failed to list models: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleSelectModel(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        if (parts.size < 2) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "Usage: /select-model <model-id>\nUse /models to see available models.",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        val modelId = parts[1]
        val model = com.bitchat.android.ai.ModelCatalog.getModelById(modelId)
        
        if (model == null) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "Model not found: $modelId. Use /models to see available models.",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        runBlocking {
            try {
                // Check if model is downloaded
                if (!ai.modelManager.isModelDownloaded(model)) {
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "Model '${model.name}' not downloaded. Use /download $modelId to download it first.",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(msg)
                    return@runBlocking
                }
                
                // Set as selected model
                ai.preferences.selectedLLMModel = modelId
                
                // If AI is enabled, try to load the new model
                if (ai.preferences.aiEnabled) {
                    val loadResult = ai.aiService.loadModel(model)
                    if (loadResult.isSuccess) {
                        val msg = com.bitchat.android.model.BitchatMessage(
                            sender = "system",
                            content = "✅ Model '${model.name}' selected and loaded successfully!",
                            timestamp = java.util.Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(msg)
                    } else {
                        val error = loadResult.exceptionOrNull()?.message ?: "Unknown error"
                        val msg = com.bitchat.android.model.BitchatMessage(
                            sender = "system",
                            content = "Model '${model.name}' selected but failed to load: $error",
                            timestamp = java.util.Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(msg)
                    }
                } else {
                    val msg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "Model '${model.name}' selected. Use '/ai on' to enable AI and load the model.",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(msg)
                }
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "Failed to select model: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleModelStatus(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        runBlocking {
            try {
                val status = ai.getModelSelectionStatus()
                val msg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = status,
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(msg)
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "Failed to get model status: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleRecover(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        runBlocking {
            try {
                val msg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "Attempting AI crash recovery...",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(msg)
                
                val result = ai.recoverFromCrash()
                if (result.isSuccess) {
                    val successMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "✅ AI crash recovery completed successfully! You can now try enabling AI again with '/ai on'.",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(successMsg)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    val errorMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "❌ AI crash recovery failed: $error",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "❌ Recovery command failed: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }

    private fun handleDownload(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        
        if (parts.size < 2) {
            // Show available models
            val availableModels = com.bitchat.android.ai.ModelCatalog.LLM_MODELS
            val modelList = availableModels.joinToString("\n") { 
                "- ${it.id}: ${it.name} (${it.fileSizeMB}MB)"
            }
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "Available models to download:\n$modelList\n\nUsage: /download <model-id>",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        val modelId = parts[1]
        val model = com.bitchat.android.ai.ModelCatalog.getModelById(modelId)
        
        if (model == null) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "Model not found: $modelId. Use /download to see available models.",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        // Check if already downloaded
        if (ai.modelManager.isModelDownloaded(model)) {
            val msg = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "Model '${model.name}' is already downloaded.",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(msg)
            return
        }
        
        // Start download
        val startMsg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "Starting download of '${model.name}' (${model.fileSizeMB}MB)...",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(startMsg)
        
        runBlocking {
            try {
                val result = ai.modelManager.downloadModel(model) { progress, downloadedMB, totalMB ->
                    // Update progress (could be enhanced with real-time updates)
                    android.util.Log.d("Download", "Progress: $progress% ($downloadedMB/$totalMB MB)")
                }
                
                if (result.isSuccess) {
                    val successMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "✅ Model '${model.name}' downloaded successfully!",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(successMsg)
                } else {
                    val errorMsg = com.bitchat.android.model.BitchatMessage(
                        sender = "system",
                        content = "❌ Download failed: ${result.exceptionOrNull()?.message}",
                        timestamp = java.util.Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = com.bitchat.android.model.BitchatMessage(
                    sender = "system",
                    content = "❌ Download error: ${e.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                messageManager.addMessage(errorMsg)
            }
        }
    }
    
    private fun handleUnknownCommand(cmd: String) {
        val systemMessage = BitchatMessage(
            sender = "system",
            content = "unknown command: $cmd. type / to see available commands.",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    // ===== RESCUE API TEST COMMANDS =====
    
    private fun handleTestRescueAPI(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(meshService.getContext())
        val rescueAPI = com.bitchat.android.ai.RescueAPIService.getInstance(meshService.getContext())
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "🧪 Testing Rescue API connection... Check logs for results.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
        
        debugger.testRescueAPIConnection(rescueAPI)
    }
    
    private fun handleTestRescueSubmit(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(meshService.getContext())
        val rescueAPI = com.bitchat.android.ai.RescueAPIService.getInstance(meshService.getContext())
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "🧪 Testing victim report submission... Check logs for results.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
        
        debugger.testVictimReportSubmission(rescueAPI)
    }
    
    private fun handleTestTTS(parts: List<String>, meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(meshService.getContext())
        val text = parts.drop(1).joinToString(" ").trim().ifEmpty { 
            "This is a text to speech test. SafeGuardian emergency system activated." 
        }
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "🔊 Testing TTS with text: \"$text\"... Listen for audio output.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
        
        debugger.testTTSPlayback(ai.aiService, text)
    }
    
    private fun handleTestBackendSwitch(meshService: BluetoothMeshService) {
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(meshService.getContext())
        val rescueAPI = com.bitchat.android.ai.RescueAPIService.getInstance(meshService.getContext())
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "🔄 Testing backend switching (MongoDB ↔ Firebase)... Check logs for results.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
        
        debugger.testBackendSwitching(rescueAPI)
    }
    
    private fun handleTestDiagnostics(meshService: BluetoothMeshService) {
        val (ai, _) = getAI(meshService)
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(meshService.getContext())
        val rescueAPI = com.bitchat.android.ai.RescueAPIService.getInstance(meshService.getContext())
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "📊 Running full system diagnostics... Check logs for detailed results.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
        
        debugger.runFullDiagnostics(rescueAPI, ai.aiService, ai.preferences)
    }
    
    private fun handleShowDebugLogs() {
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(android.app.Application().applicationContext)
        val logs = debugger.getLogsAsString()
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = if (logs.isEmpty()) {
                "📋 No debug logs available."
            } else {
                "📋 Debug Logs:\n\n$logs"
            },
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }
    
    private fun handleClearDebugLogs() {
        val debugger = com.bitchat.android.ai.RescueAPIDebugger.getInstance(android.app.Application().applicationContext)
        debugger.clearLogs()
        
        val msg = com.bitchat.android.model.BitchatMessage(
            sender = "system",
            content = "🗑️ Debug logs cleared.",
            timestamp = java.util.Date(),
            isRelay = false
        )
        messageManager.addMessage(msg)
    }
    
    // MARK: - Command Autocomplete

    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands()
        
        // Filter commands based on input
        val filteredCommands = filterCommands(allCommands, input.lowercase())
        
        if (filteredCommands.isNotEmpty()) {
            state.setCommandSuggestions(filteredCommands)
            state.setShowCommandSuggestions(true)
        } else {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        // Add channel-specific commands if in a channel
        val channelCommands = if (state.getCurrentChannelValue() != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelCommands
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { command ->
            // Check primary command
            command.command.startsWith(input) ||
            // Check aliases
            command.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        state.setShowCommandSuggestions(false)
        state.setCommandSuggestions(emptyList())
        return "${suggestion.command} "
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Check if input contains @ and we're at the end of a word or at the end of input
        val atIndex = input.lastIndexOf('@')
        if (atIndex == -1) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get the text after the @ symbol
        val textAfterAt = input.substring(atIndex + 1)
        
        // If there's a space after @, don't show suggestions
        if (textAfterAt.contains(' ')) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get peer candidates based on active channel (matches iOS logic exactly)
        val peerCandidates: List<String> = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bitchat.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: use Bluetooth mesh peer nicknames
                    meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
                }
                
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    // Location channel: use geohash participants with collision-resistant suffixes
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from suggestions
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }
                }
            }
        } else {
            // Fallback to mesh peers if no viewModel available
            meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
        }
        
        // Filter nicknames based on the text after @
        val filteredNicknames = peerCandidates.filter { nickname ->
            nickname.startsWith(textAfterAt, ignoreCase = true)
        }.sorted()
        
        if (filteredNicknames.isNotEmpty()) {
            state.setMentionSuggestions(filteredNicknames)
            state.setShowMentionSuggestions(true)
        } else {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
        }
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        state.setShowMentionSuggestions(false)
        state.setMentionSuggestions(emptyList())
        
        // Find the last @ symbol position
        val atIndex = currentText.lastIndexOf('@')
        if (atIndex == -1) {
            return "$currentText@$nickname "
        }
        
        // Replace the text from the @ symbol to the end with the mention
        val textBeforeAt = currentText.substring(0, atIndex)
        return "$textBeforeAt@$nickname "
    }
    
    // MARK: - Utility Functions
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}
