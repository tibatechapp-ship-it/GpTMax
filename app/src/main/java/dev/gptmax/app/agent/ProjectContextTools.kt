package dev.gptmax.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class DocumentTreeContextIndexTool(context:Context,treeUri:Uri,private val engine:ProjectContextEngine):AgentTool {
    private val appContext=context.applicationContext
    private val root=DocumentFile.fromTreeUri(context,treeUri)?:throw IllegalArgumentException("Invalid project folder.")
    override val spec=AgentToolSpec("context.index",
        "Incrementally index bounded, non-sensitive text files from the selected project for local context recall. Does not execute project code.",
        emptySet(),emptySet(),ToolRisk.READ_ONLY)

    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        val start=System.nanoTime()
        val ignored=setOf(".git",".gradle",".idea","node_modules","build","dist","target",".next",".venv","venv","__pycache__")
        val allowed=setOf("kt","kts","java","xml","json","yaml","yml","toml","md","txt","py","js","jsx","ts","tsx",
            "html","css","scss","gradle","properties","sh","c","h","cpp","hpp","rs","go","dart")
        val records=mutableListOf<ProjectContextEngine.FileRecord>()
        var skipped=0
        var scanned=0
        var traversalComplete=true
        val queue=ArrayDeque<Pair<DocumentFile,String>>()
        queue.add(root to "")
        while(queue.isNotEmpty()&&!cancellation.get()&&scanned<MAX_FILES) {
            val (dir,prefix)=queue.removeFirst()
            val children=runCatching{dir.listFiles()}.getOrElse{traversalComplete=false;skipped++;emptyArray()}
            for(child in children) {
                if(cancellation.get()||scanned>=MAX_FILES) break
                val name=child.name.orEmpty()
                if(name.isBlank()) {skipped++;traversalComplete=false;continue}
                if(child.isDirectory) {
                    if(name !in ignored&&prefix.count{it=='/'}<MAX_DEPTH) queue.add(child to (if(prefix.isEmpty()) name else "$prefix/$name"))
                    else skipped++
                    continue
                }
                if(!child.isFile||name.startsWith(".env",true)||name.lowercase() in setOf(".npmrc",".netrc","credentials.json","secrets.json","id_rsa","id_ed25519")||
                    listOf(".pem",".key",".p12",".jks").any{name.endsWith(it,true)}||
                    name.substringAfterLast('.', "").lowercase() !in allowed||child.length() !in 1..MAX_BYTES.toLong()) {skipped++;continue}
                val path=if(prefix.isEmpty())name else "$prefix/$name"
                scanned++
                val previous=engine.recordFor(path)
                if(previous!=null&&child.lastModified()>0&&previous.size==child.length()&&previous.modified==child.lastModified()){records+=previous;continue}
                val content=runCatching{readContextDocument(appContext,child,MAX_BYTES)}.getOrNull()
                if(content==null){skipped++;previous?.let{records+=it};traversalComplete=false;continue}
                records+=engine.createRecord(path,child.length(),child.lastModified(),content)
            }
        }
        if(cancellation.get()) return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Index cancelled; previous index retained.")
        if(scanned>=MAX_FILES||!traversalComplete) return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,
            "Index scan was capped or a SAF read/list operation failed; previous index retained to avoid false deletions.",
            (System.nanoTime()-start)/1_000_000L)
        val delta=engine.replaceIndex(records,complete=true)
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,
            "Context index refreshed. Added=${delta.added}; updated=${delta.updated}; removed=${delta.removed}; unchanged=${delta.unchanged}; skipped=$skipped; store: ${engine.stats()}",
            (System.nanoTime()-start)/1_000_000L)
    }
    companion object {private const val MAX_FILES=1200;private const val MAX_DEPTH=7;private const val MAX_BYTES=64*1024}
}

class ProjectContextRecallTool(private val engine:ProjectContextEngine):AgentTool {
    override val spec=AgentToolSpec("context.recall",
        "Retrieve project files, durable notes, and the latest task checkpoint relevant to a query. Use before planning or when resuming a task.",
        setOf("query"),setOf("query","limit"),ToolRisk.READ_ONLY)
    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        if(cancellation.get()) return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Cancelled.")
        val limit=call.arguments["limit"]?.toIntOrNull()?:8
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,
            engine.recall(call.arguments.getValue("query"),limit))
    }
}

class ProjectContextRememberTool(private val engine:ProjectContextEngine):AgentTool {
    override val spec=AgentToolSpec("context.remember",
        "Store a concise non-sensitive project decision, convention, architecture fact, or unresolved issue under key/value for future sessions.",
        setOf("key","value"),setOf("key","value"),ToolRisk.READ_ONLY)
    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        if(cancellation.get()) return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Cancelled.")
        engine.remember(call.arguments.getValue("key"),call.arguments.getValue("value"))
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,"Project memory saved. ${engine.stats()}")
    }
}

class ProjectContextCheckpointTool(private val engine:ProjectContextEngine):AgentTool {
    override val spec=AgentToolSpec("context.checkpoint",
        "Persist task progress and the exact next step so work can resume after the app closes. Do not store secrets or raw source files.",
        setOf("task_id","goal","progress","next_step"),
        setOf("task_id","goal","progress","next_step"),ToolRisk.READ_ONLY)
    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        if(cancellation.get()) return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Cancelled.")
        engine.checkpoint(call.arguments.getValue("task_id"),call.arguments.getValue("goal"),
            call.arguments.getValue("progress"),call.arguments.getValue("next_step"))
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,"Checkpoint saved locally.")
    }
}

class ProjectContextRestoreTool(private val engine:ProjectContextEngine):AgentTool {
    override val spec=AgentToolSpec("context.restore",
        "Restore saved progress, stage history, and summarized tool observations for a prior task in this selected project.",
        setOf("task_id"),setOf("task_id"),ToolRisk.READ_ONLY)
    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        if(cancellation.get())return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Cancelled.")
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,engine.restoreTask(call.arguments.getValue("task_id")))
    }
}
class ProjectContextStageTool(private val engine:ProjectContextEngine):AgentTool {
    override val spec=AgentToolSpec("context.stage",
        "Record a concise non-sensitive task phase and status (analysis, implementation, validation, blocked).",
        setOf("task_id","stage","status"),setOf("task_id","stage","status","detail"),ToolRisk.READ_ONLY)
    override suspend fun execute(call:AgentToolCall,cancellation:AtomicBoolean):AgentToolResult {
        if(cancellation.get())return AgentToolResult(spec.name,call.id,ToolExecutionStatus.CANCELLED,"Cancelled.")
        engine.addStage(call.arguments.getValue("task_id"),call.arguments.getValue("stage"),
            call.arguments.getValue("status"),call.arguments["detail"].orEmpty())
        return AgentToolResult(spec.name,call.id,ToolExecutionStatus.SUCCESS,"Task stage saved.")
    }
}

private fun readContextDocument(context:Context,file:DocumentFile,limit:Int):String {
    require(file.isFile&&file.length()<=limit){"Not a regular file or file exceeds the context limit."}
    val bytes=ByteArrayOutputStream()
    context.contentResolver.openInputStream(file.uri)?.use { input ->
        val buffer=ByteArray(4096)
        while(true) {
            val count=input.read(buffer)
            if(count<0)break
            require(bytes.size()+count<=limit){"File exceeds the context read limit."}
            bytes.write(buffer,0,count)
        }
    }?:throw IllegalStateException("Could not open SAF document.")
    require(bytes.size()>0){"Empty document."}
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}
