# RAG Logging Guide - How to Assess if RAG is Working

## 🔍 **Key Logs to Monitor**

When using RAG, you'll see comprehensive logging at each stage. Here's what to look for:

## 📋 **Log Tags**

All RAG-related logs use these tags:
- `RAGService` - Core RAG operations
- `RAGDocumentManager` - Document loading and management
- `AIChatService` - RAG context retrieval
- `RerankerService` - Reranking operations

## 🚀 **1. Initialization Logs**

### **RAG Service Initialization:**
```
═══════════════════════════════════════════════════════
🔧 Initializing RAGService
═══════════════════════════════════════════════════════
   📂 Checking for existing RAG index...
      Index file: /data/data/.../rag_index.json (exists: true/false)
      Vectors file: /data/data/.../rag_vectors.bin (exists: true/false)
   📖 Loading RAG index from storage...
   📊 Index metadata: X chunks, 768D
   ✅ Successfully loaded RAG index with X chunks
✅ RAGService initialized successfully
   📊 Total chunks: X
   📏 Embedding dimension: 768
   🔍 Has embeddings: true/false
   ✅ Ready: true/false
═══════════════════════════════════════════════════════
```

**What to check:**
- ✅ `Total chunks > 0` - Documents are loaded
- ✅ `Has embeddings: true` - Embeddings are generated
- ✅ `Ready: true` - RAG is ready to use

**If chunks = 0:**
- ⚠️ No documents loaded - Use `/init-rag` command

## 📚 **2. Document Initialization Logs**

### **When running `/init-rag`:**
```
═══════════════════════════════════════════════════════
📚 Initializing RAG with documents
═══════════════════════════════════════════════════════
   📂 Step 1: Loading PDFs from assets...
   ✅ Loaded X documents from assets
   📂 Step 2: Loading PDFs from storage...
   ✅ Loaded X documents from storage
   📊 Total documents found: X
   📝 Step 3: Adding documents to RAG index...
═══════════════════════════════════════════════════════
📚 Adding X documents to RAG index
   📄 Chunk size: 1000, Overlap: 150
   📖 Processing document 0 (XXXX chars)
   ✂️ Created X chunks from document 0
   📝 Chunk 0 preview: ...
   ✅ Created X total chunks
   🔢 Generating embeddings for X chunks...
   💾 Saving index...
   ✅ Index saved successfully
═══════════════════════════════════════════════════════
✅ Successfully added X chunks from X documents
   📊 Total chunks in index: X
   🔍 Index ready: true
═══════════════════════════════════════════════════════
```

**What to check:**
- ✅ `Total documents found > 0` - Documents are found
- ✅ `Created X chunks` - Documents are chunked
- ✅ `Generating embeddings` - Embeddings are being created
- ✅ `Index saved successfully` - Index is persisted

## 🔍 **3. Search Operation Logs**

### **When asking a question with `/ask`:**
```
═══════════════════════════════════════════════════════
🔍 Retrieving RAG context for query
   Query: "How does SafeGuardian protect privacy?"
   Channel: default
   ✅ RAG service is ready
═══════════════════════════════════════════════════════
🔍 RAG Search Request
   Query: "How does SafeGuardian protect privacy?"
   TopK: 3, Reranking: true
   📊 Index stats: X chunks, 768D embeddings
   🔢 Step 1: Generating query embedding...
   ✅ Query embedding generated (768 dimensions)
   🔍 Step 2: Performing vector similarity search...
   ✅ Found X candidates from vector search
   📋 Top candidates:
      1. [document_0] ...
      2. [document_1] ...
      3. [document_2] ...
   🎯 Step 3: Reranking with 3 top results...
   ✅ Reranking complete: X final results
═══════════════════════════════════════════════════════
✅ Search complete: X documents retrieved
   📄 Results:
      1. [document_0] ...
      2. [document_1] ...
      3. [document_2] ...
═══════════════════════════════════════════════════════
   ✅ Retrieved X relevant chunks
   📄 Context length: XXXX chars
═══════════════════════════════════════════════════════
```

**What to check:**
- ✅ `RAG service is ready` - RAG is initialized
- ✅ `Found X candidates` - Vector search found results
- ✅ `Reranking complete` - Reranking worked (if enabled)
- ✅ `Retrieved X relevant chunks` - Context was retrieved
- ✅ `Context length > 0` - Context is being used

**If no results:**
- ⚠️ `RAG service not ready` - Check initialization
- ⚠️ `No candidates found` - Check if documents match query
- ⚠️ `No relevant chunks found` - Documents may not be relevant

## ⚠️ **4. Error Logs**

### **Common Issues:**

**No Documents Loaded:**
```
❌ RAG service not ready
   Initialized: true
   Document count: 0
   Embedding dimension: 0
   💡 Use '/init-rag' command to load documents
```

**Solution:** Run `/init-rag` command

**No Embeddings:**
```
⚠️ Vectors file not found or invalid, loading without embeddings
```

**Solution:** Re-initialize RAG with `/init-rag`

**Search Failed:**
```
❌ Failed to generate query embedding
```

**Solution:** Check embedding model initialization

## 📊 **5. Status Command Logs**

### **When running `/rag-status`:**
```
📊 RAG Service Status
═══════════════════════════════════
🔍 RAG Ready: true/false
📚 Total Chunks: X
📏 Embedding Dimension: 768
🔢 Has Embeddings: true/false
⚙️ RAG Enabled: true/false
🎯 Rerank Enabled: true/false
📈 Rerank Top N: 3
🔧 Reranker Status: ...
📁 PDFs in Assets: X
📁 PDFs in Storage: X
```

**What to check:**
- ✅ `RAG Ready: true` - Service is ready
- ✅ `Total Chunks > 0` - Documents are indexed
- ✅ `Has Embeddings: true` - Embeddings exist
- ✅ `RAG Enabled: true` - RAG is enabled
- ✅ `PDFs in Assets/Storage > 0` - Documents are available

## 🔧 **6. Troubleshooting Checklist**

### **RAG Not Working? Check These Logs:**

1. **Initialization:**
   - ✅ Look for `RAGService initialized successfully`
   - ✅ Check `Total chunks > 0`
   - ✅ Verify `Ready: true`

2. **Document Loading:**
   - ✅ Run `/init-rag` and check logs
   - ✅ Verify `Total documents found > 0`
   - ✅ Check `Created X chunks`
   - ✅ Verify `Index saved successfully`

3. **Search Operations:**
   - ✅ Check `RAG service is ready`
   - ✅ Verify `Found X candidates`
   - ✅ Check `Retrieved X relevant chunks`
   - ✅ Verify `Context length > 0`

4. **Settings:**
   - ✅ Run `/rag-status` to check all settings
   - ✅ Verify `RAG Enabled: true`
   - ✅ Check `Rerank Enabled: true` (if using reranking)

## 📝 **7. Log Filtering**

### **Filter Logs by Tag:**
```bash
# Android Logcat
adb logcat -s RAGService:* RAGDocumentManager:* AIChatService:* RerankerService:*

# Or filter by specific operations
adb logcat | grep "RAG Search Request"
adb logcat | grep "Adding.*documents to RAG"
adb logcat | grep "RAG initialized"
```

## 🎯 **8. Expected Log Flow**

### **Complete RAG Workflow:**
1. **Initialization:**
   ```
   🔧 Initializing RAGService
   ✅ RAGService initialized successfully
   ```

2. **Document Loading:**
   ```
   📚 Initializing RAG with documents
   ✅ Successfully added X chunks
   ```

3. **Search:**
   ```
   🔍 RAG Search Request
   ✅ Found X candidates
   ✅ Reranking complete
   ✅ Retrieved X relevant chunks
   ```

4. **Context Building:**
   ```
   🔍 Retrieving RAG context for query
   ✅ Retrieved X relevant chunks
   📄 Context length: XXXX chars
   ```

## 💡 **Quick Debugging Commands**

```bash
# Check RAG status
/rag-status

# Initialize RAG with documents
/init-rag

# Enable RAG
/rag on

# Ask a question (will show RAG logs)
/ask How does SafeGuardian protect privacy?
```

## ✅ **Success Indicators**

Your RAG is working correctly if you see:
- ✅ `Total chunks > 0` in initialization logs
- ✅ `Found X candidates` in search logs
- ✅ `Retrieved X relevant chunks` in context logs
- ✅ `Context length > 0` in context logs
- ✅ AI responses reference document content

## ❌ **Failure Indicators**

RAG is NOT working if you see:
- ❌ `Total chunks: 0` - No documents loaded
- ❌ `RAG service not ready` - Service not initialized
- ❌ `No candidates found` - Search failed
- ❌ `Context length: 0` - No context retrieved
- ❌ AI responses don't reference documents

## 🔍 **Advanced Debugging**

### **Check Log Levels:**
- `Log.i()` - Info (important events)
- `Log.d()` - Debug (detailed operations)
- `Log.w()` - Warning (potential issues)
- `Log.e()` - Error (failures)

### **Monitor Specific Operations:**
```bash
# Watch for document loading
adb logcat | grep "Adding.*documents to RAG"

# Watch for search operations
adb logcat | grep "RAG Search Request"

# Watch for context retrieval
adb logcat | grep "Retrieving RAG context"
```

This comprehensive logging will help you identify exactly where RAG might be failing!




