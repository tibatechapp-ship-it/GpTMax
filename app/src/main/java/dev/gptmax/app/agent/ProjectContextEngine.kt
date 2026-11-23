package dev.gptmax.app.agent

import java.io.*
import java.security.MessageDigest

/** Per-workspace, versioned local context index with lexical ranking and lightweight symbol links. */
class ProjectContextEngine(private val stateFile: File) {
    data class FileRecord(
        val path:String,val size:Long,val modified:Long,val digest:String,val snippet:String,
        val imports:List<String> = emptyList(),val symbols:List<String> = emptyList(),val kind:String = "source"
    )
    data class Note(val key:String,val value:String,val updatedAt:Long)
    data class Checkpoint(val taskId:String,val goal:String,val progress:String,val nextStep:String,val updatedAt:Long)
    data class TaskStage(val taskId:String,val stage:String,val status:String,val detail:String,val updatedAt:Long)
    data class ToolObservation(val taskId:String,val tool:String,val status:String,val summary:String,val updatedAt:Long)
    data class IndexDelta(val added:Int,val updated:Int,val removed:Int,val unchanged:Int)
    private val lock=Any()
    private val files=linkedMapOf<String,FileRecord>()
    private val notes=linkedMapOf<String,Note>()
    private val checkpoints=linkedMapOf<String,Checkpoint>()
    private val stages=mutableListOf<TaskStage>()
    private val observations=mutableListOf<ToolObservation>()
    init { load() }

    fun createRecord(path:String,size:Long,modified:Long,content:String):FileRecord {
        require(path.length<=500&&!path.startsWith("/")&&!path.split('/').contains(".."))
        val safe=redact(content).take(MAX_SNIPPET)
        val imports=IMPORT_REGEX.findAll(content).map{it.groupValues[1]}.distinct().take(40).toList()
        val symbols=SYMBOL_REGEX.findAll(content).map{it.groupValues[1]}.distinct().take(80).toList()
        return FileRecord(path,size,modified,sha256(content.toByteArray(Charsets.UTF_8)),safe,imports,symbols,classify(path))
    }
    fun recordFor(path:String):FileRecord?=synchronized(lock){files[path]}
    /** Replaces snapshot only after a complete traversal; this precisely removes deleted files. */
    fun replaceIndex(records:Collection<FileRecord>,complete:Boolean=true):IndexDelta=synchronized(lock) {
        require(complete){"Incomplete workspace scan cannot replace the existing index."}
        val previous=files.toMap()
        val next=linkedMapOf<String,FileRecord>()
        records.take(MAX_FILES).forEach{next[it.path]=it}
        val added=next.keys.count{it !in previous}
        val updated=next.keys.count{it in previous&&previous[it]?.digest!=next[it]?.digest}
        val unchanged=next.size-added-updated
        val removed=previous.keys.count{it !in next}
        files.clear();files.putAll(next);persist()
        IndexDelta(added,updated,removed,unchanged)
    }
    fun remember(key:String,value:String)=synchronized(lock) {
        require(key.isNotBlank()&&key.length<=120&&value.length<=MAX_NOTE)
        require(!sensitive(key+"\n"+value)){"Sensitive data is not allowed in project memory."}
        notes[key.trim()]=Note(key.trim(),value.trim(),System.currentTimeMillis())
        while(notes.size>MAX_NOTES)notes.remove(notes.keys.first())
        persist()
    }
    fun checkpoint(taskId:String,goal:String,progress:String,nextStep:String)=synchronized(lock) {
        require(taskId.isNotBlank()&&taskId.length<=100)
        listOf(goal,progress,nextStep).forEach{require(it.length<=MAX_NOTE)}
        require(!sensitive(goal+progress+nextStep)){"Sensitive data is not allowed in checkpoints."}
        checkpoints[taskId]=Checkpoint(taskId,goal,progress,nextStep,System.currentTimeMillis())
        while(checkpoints.size>MAX_CHECKPOINTS)checkpoints.remove(checkpoints.keys.first())
        persist()
    }
    fun addStage(taskId:String,stage:String,status:String,detail:String)=synchronized(lock) {
        require(taskId.length<=100&&stage.length<=160&&status.length<=40&&detail.length<=1000)
        require(!sensitive(stage+detail)){"Sensitive data is not allowed in task history."}
        stages+=TaskStage(taskId,stage,status,detail,System.currentTimeMillis())
        while(stages.size>MAX_STAGES)stages.removeAt(0)
        persist()
    }
    fun observeTool(taskId:String,tool:String,status:String,summary:String)=synchronized(lock) {
        require(taskId.length<=100&&tool.length<=120&&status.length<=40&&summary.length<=1200)
        val safe=redact(summary).take(800)
        observations+=ToolObservation(taskId,tool,status,safe,System.currentTimeMillis())
        while(observations.size>MAX_OBSERVATIONS)observations.removeAt(0)
        persist()
    }
    fun latestCheckpoint():Checkpoint?=synchronized(lock){checkpoints.values.maxByOrNull{it.updatedAt}}
    fun restoreTask(taskId:String):String=synchronized(lock) {
        val cp=checkpoints[taskId] ?: return "No checkpoint exists for task '$taskId'."
        buildString {
            appendLine("Resumable task state (historical record; verify current workspace):")
            appendLine("Task: ${cp.goal}")
            appendLine("Progress: ${cp.progress}")
            appendLine("Next: ${cp.nextStep}")
            stages.filter{it.taskId==taskId}.takeLast(12).forEach{appendLine("[stage ${it.status}] ${it.stage}: ${it.detail}")}
            observations.filter{it.taskId==taskId}.takeLast(10).forEach{appendLine("[tool ${it.status}] ${it.tool}: ${it.summary}")}
        }.take(MAX_RECALL)
    }
    fun stats():String=synchronized(lock){"Indexed files=${files.size}; notes=${notes.size}; checkpoints=${checkpoints.size}; stages=${stages.size}; tool observations=${observations.size}"}

    fun recall(query:String,limit:Int=8):String=synchronized(lock) {
        require(query.isNotBlank()&&query.length<=500)
        val terms=query.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter{it.length>=2}.toSet()
        val all=files.values.toList()
        val ranked=all.mapNotNull{r->
            val path=r.path.lowercase();val body=r.snippet.lowercase();val symbolText=r.symbols.joinToString(" ").lowercase()
            val fields=listOf(path,body,symbolText)
            var score=0.0
            terms.forEach{term->
                if(path.contains(term))score+=5.0
                if(r.symbols.any{it.lowercase()==term})score+=8.0
                val count=body.split(term).size-1
                score+=minOf(count,5)*1.5
                if(r.imports.any{it.lowercase().contains(term)})score+=2.0
            }
            if(query.lowercase() in body)score+=7.0
            if(r.kind=="manifest"||r.kind=="config")score+=if(terms.any{path.contains(it)})2.0 else 0.0
            if(path.contains("test")||path.contains("spec"))score+=if(terms.any{body.contains(it)})2.0 else 0.0
            if(score>0)score to r else null
        }.sortedByDescending{it.first}.take(limit.coerceIn(1,20)).toMutableList()
        // Add one-hop related files: imports, same-directory peers and test/source pairs.
        val selected=ranked.map{it.second}.toMutableList()
        val paths=selected.map{it.path}.toSet()
        selected.toList().forEach{record->
            val importNames=record.imports.map{it.substringAfterLast('.')}
            all.filter{candidate->candidate.path !in paths && (
                importNames.any{n->candidate.path.substringAfterLast('/').substringBeforeLast('.')==n} ||
                (candidate.path.substringBeforeLast('/')==record.path.substringBeforeLast('/') &&
                    (candidate.path.contains("test",true)||record.path.contains("test",true)))
            )}.take(2).forEach{selected.add(it)}
        }
        val foundNotes=notes.values.filter{n->terms.any{(n.key+" "+n.value).lowercase().contains(it)}}.take(6)
        val cp=checkpoints.values.maxByOrNull{it.updatedAt}
        buildString {
            appendLine("Task-relevant local context; snippets and links are hints. Re-read files before edits.")
            if(ranked.isEmpty())appendLine("No indexed file matches. Run context.index.")
            selected.distinctBy{it.path}.take(limit.coerceIn(1,20)+6).forEach{r->
                val score=ranked.firstOrNull{it.second.path==r.path}?.first?:0.0
                appendLine("[file score=${"%.1f".format(score)} kind=${r.kind}] ${r.path} sha256=${r.digest.take(12)}")
                if(r.imports.isNotEmpty())appendLine("imports: ${r.imports.take(12).joinToString()}")
                if(r.symbols.isNotEmpty())appendLine("symbols: ${r.symbols.take(20).joinToString()}")
                appendLine(r.snippet)
            }
            foundNotes.forEach{appendLine("[memory] ${it.key}: ${it.value}")}
            if(cp!=null)appendLine("[latest checkpoint task=${cp.taskId}] ${cp.goal}; progress=${cp.progress}; next=${cp.nextStep}")
        }.take(MAX_RECALL)
    }

    private fun load()=synchronized(lock) {
        if(!stateFile.isFile||stateFile.length() !in 1..MAX_STORE.toLong())return
        runCatching{DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use{d->
            if(d.readInt()!=MAGIC)return@use
            val version=d.readInt();if(version !in 1..VERSION)return@use
            repeat(d.readInt().coerceIn(0,MAX_FILES)){val path=d.readUTF();val size=d.readLong();val modified=d.readLong();val digest=d.readUTF();val snippet=d.readUTF()
                val imports=if(version>=2)readStrings(d,40)else emptyList()
                val symbols=if(version>=2)readStrings(d,80)else emptyList()
                val kind=if(version>=2)d.readUTF()else classify(path)
                files[path]=FileRecord(path,size,modified,digest,snippet,imports,symbols,kind)}
            repeat(d.readInt().coerceIn(0,MAX_NOTES)){val n=Note(d.readUTF(),d.readUTF(),d.readLong());notes[n.key]=n}
            repeat(d.readInt().coerceIn(0,MAX_CHECKPOINTS)){val c=Checkpoint(d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readLong());checkpoints[c.taskId]=c}
            if(version>=2){
                repeat(d.readInt().coerceIn(0,MAX_STAGES)){stages+=TaskStage(d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readLong())}
                repeat(d.readInt().coerceIn(0,MAX_OBSERVATIONS)){observations+=ToolObservation(d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readLong())}
            }
        }}.onFailure{files.clear();notes.clear();checkpoints.clear();stages.clear();observations.clear()}
    }
    private fun writeStrings(d:DataOutputStream,items:List<String>,max:Int){d.writeInt(items.size.coerceAtMost(max));items.take(max).forEach{d.writeUTF(it.take(500))}}
    private fun readStrings(d:DataInputStream,max:Int):List<String> = List(d.readInt().coerceIn(0,max)){d.readUTF()}
    private fun persist(){
        stateFile.parentFile?.mkdirs();val temp=File(stateFile.parentFile,stateFile.name+".tmp")
        try{DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use{d->
            d.writeInt(MAGIC);d.writeInt(VERSION);d.writeInt(files.size)
            files.values.take(MAX_FILES).forEach{r->d.writeUTF(r.path);d.writeLong(r.size);d.writeLong(r.modified);d.writeUTF(r.digest);d.writeUTF(r.snippet);writeStrings(d,r.imports,40);writeStrings(d,r.symbols,80);d.writeUTF(r.kind)}
            d.writeInt(notes.size);notes.values.forEach{d.writeUTF(it.key);d.writeUTF(it.value);d.writeLong(it.updatedAt)}
            d.writeInt(checkpoints.size);checkpoints.values.forEach{d.writeUTF(it.taskId);d.writeUTF(it.goal);d.writeUTF(it.progress);d.writeUTF(it.nextStep);d.writeLong(it.updatedAt)}
            d.writeInt(stages.size);stages.forEach{d.writeUTF(it.taskId);d.writeUTF(it.stage);d.writeUTF(it.status);d.writeUTF(it.detail);d.writeLong(it.updatedAt)}
            d.writeInt(observations.size);observations.forEach{d.writeUTF(it.taskId);d.writeUTF(it.tool);d.writeUTF(it.status);d.writeUTF(it.summary);d.writeLong(it.updatedAt)}
        }
        require(temp.length()<=MAX_STORE){"Context database exceeds configured maximum."}
        try{java.nio.file.Files.move(temp.toPath(),stateFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}
        catch(_:java.nio.file.AtomicMoveNotSupportedException){java.nio.file.Files.move(temp.toPath(),stateFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
        }finally{if(temp.exists())temp.delete()}
    }
    private fun redact(text:String)=text.lineSequence().take(250).joinToString("\n"){line->if(sensitive(line))"[REDACTED: possible secret]"else line.take(400)}
    private fun sensitive(s:String):Boolean=Regex("(api[_-]?key|access[_-]?token|secret|password|private[_-]?key)\\s*[:=]",RegexOption.IGNORE_CASE).containsMatchIn(s)||Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----",RegexOption.IGNORE_CASE).containsMatchIn(s)
    private fun classify(path:String):String=when{
        path.substringAfterLast('/').lowercase() in setOf("package.json","pyproject.toml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts","cargo.toml","go.mod","pom.xml","manifest.json")->"manifest"
        path.contains("test",true)||path.contains("spec",true)->"test"
        path.endsWith(".md",true)->"documentation"
        path.endsWith(".yml",true)||path.endsWith(".yaml",true)||path.endsWith(".toml",true)||path.endsWith(".properties",true)->"config"
        else->"source"
    }
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    companion object{
        private const val MAGIC=0x47504D58;private const val VERSION=2
        private const val MAX_FILES=1500;private const val MAX_NOTES=100;private const val MAX_CHECKPOINTS=40;private const val MAX_STAGES=200;private const val MAX_OBSERVATIONS=300
        private const val MAX_NOTE=4000;private const val MAX_SNIPPET=1800;private const val MAX_RECALL=16000;private const val MAX_STORE=8*1024*1024
        private val IMPORT_REGEX=Regex("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)")
        private val SYMBOL_REGEX=Regex("(?m)^\\s*(?:class|interface|object|enum|fun|def|function|const|type|struct|trait)\\s+([A-Za-z_][\\w]*)")
    }
}
