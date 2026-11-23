package dev.gptmax.app.agent

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Versioned, bounded, app-private project memory and searchable source index. */
class ProjectContextEngine(private val stateFile: File) {
    data class FileRecord(val path:String,val size:Long,val modified:Long,val digest:String,val snippet:String)
    data class Note(val key:String,val value:String,val updatedAt:Long)
    data class Checkpoint(val taskId:String,val goal:String,val progress:String,val nextStep:String,val updatedAt:Long)
    data class IndexResult(val indexed:Int,val changed:Int,val skipped:Int,val cancelled:Boolean)
    private val lock=Any()
    private val files=linkedMapOf<String,FileRecord>()
    private val notes=linkedMapOf<String,Note>()
    private val checkpoints=linkedMapOf<String,Checkpoint>()

    init { load() }

    fun createRecord(path:String,size:Long,modified:Long,content:String):FileRecord {
        require(path.length<=500 && !path.startsWith("/") && !path.split('/').contains(".."))
        val safe=redact(content).take(MAX_SNIPPET)
        return FileRecord(path,size,modified,sha256(content.toByteArray(Charsets.UTF_8)),safe)
    }

    fun recordFor(path:String):FileRecord?=synchronized(lock){files[path]}

    fun replaceIndex(records:Collection<FileRecord>)=synchronized(lock) {
        files.clear()
        records.take(MAX_FILES).forEach { files[it.path]=it }
        persist()
    }

    fun remember(key:String,value:String)=synchronized(lock) {
        require(key.isNotBlank()&&key.length<=120&&value.length<=MAX_NOTE)
        require(!sensitive(key+"\n"+value)) { "Sensitive data is not allowed in project memory." }
        notes[key.trim()]=Note(key.trim(),value.trim(),System.currentTimeMillis())
        while(notes.size>MAX_NOTES) notes.remove(notes.keys.first())
        persist()
    }

    fun checkpoint(taskId:String,goal:String,progress:String,nextStep:String)=synchronized(lock) {
        require(taskId.isNotBlank()&&taskId.length<=100)
        listOf(goal,progress,nextStep).forEach { require(it.length<=MAX_NOTE) }
        require(!sensitive(goal+progress+nextStep)) { "Sensitive data is not allowed in checkpoints." }
        checkpoints[taskId]=Checkpoint(taskId,goal,progress,nextStep,System.currentTimeMillis())
        while(checkpoints.size>MAX_CHECKPOINTS) checkpoints.remove(checkpoints.keys.first())
        persist()
    }

    fun latestCheckpoint():Checkpoint?=synchronized(lock){checkpoints.values.maxByOrNull{it.updatedAt}}
    fun stats():String=synchronized(lock){"Indexed files=${files.size}; saved notes=${notes.size}; checkpoints=${checkpoints.size}"}

    fun recall(query:String,limit:Int=8):String=synchronized(lock) {
        require(query.isNotBlank()&&query.length<=500)
        val terms=query.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter{it.length>=2}.toSet()
        val matched=files.values.mapNotNull { r ->
            val text=(r.path+" "+r.snippet).lowercase()
            val score=terms.count{text.contains(it)}
            if(score>0) score to r else null
        }.sortedByDescending{it.first}.take(limit.coerceIn(1,20))
        val foundNotes=notes.values.filter { n -> terms.any{(n.key+" "+n.value).lowercase().contains(it)} }.take(8)
        val cp=latestCheckpoint()
        buildString {
            appendLine("Local project context; verify current files before acting.")
            if(matched.isEmpty()) appendLine("No indexed file matches; run context.index.")
            matched.forEach{(score,r)->appendLine("[file score=$score] ${r.path} sha256=${r.digest.take(12)}");appendLine(r.snippet)}
            foundNotes.forEach{appendLine("[memory] ${it.key}: ${it.value}")}
            if(cp!=null) appendLine("[checkpoint] ${cp.goal}; progress=${cp.progress}; next=${cp.nextStep}")
        }.take(MAX_RECALL)
    }

    private fun load()=synchronized(lock) {
        if(!stateFile.isFile||stateFile.length() !in 1..MAX_STORE.toLong()) return
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { d ->
                if(d.readInt()!=MAGIC||d.readInt()!=VERSION)return@use
                repeat(d.readInt().coerceIn(0,MAX_FILES)){val r=FileRecord(d.readUTF(),d.readLong(),d.readLong(),d.readUTF(),d.readUTF());files[r.path]=r}
                repeat(d.readInt().coerceIn(0,MAX_NOTES)){val n=Note(d.readUTF(),d.readUTF(),d.readLong());notes[n.key]=n}
                repeat(d.readInt().coerceIn(0,MAX_CHECKPOINTS)){val c=Checkpoint(d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readLong());checkpoints[c.taskId]=c}
            }
        }.onFailure{files.clear();notes.clear();checkpoints.clear()}
    }

    private fun persist() {
        stateFile.parentFile?.mkdirs()
        val temp=File(stateFile.parentFile,stateFile.name+".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { d ->
                d.writeInt(MAGIC);d.writeInt(VERSION);d.writeInt(files.size)
                files.values.take(MAX_FILES).forEach{d.writeUTF(it.path);d.writeLong(it.size);d.writeLong(it.modified);d.writeUTF(it.digest);d.writeUTF(it.snippet)}
                d.writeInt(notes.size);notes.values.forEach{d.writeUTF(it.key);d.writeUTF(it.value);d.writeLong(it.updatedAt)}
                d.writeInt(checkpoints.size);checkpoints.values.forEach{d.writeUTF(it.taskId);d.writeUTF(it.goal);d.writeUTF(it.progress);d.writeUTF(it.nextStep);d.writeLong(it.updatedAt)}
            }
            require(temp.length()<=MAX_STORE){"Context database exceeds configured maximum."}
            try {
                java.nio.file.Files.move(temp.toPath(),stateFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch(_:java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temp.toPath(),stateFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {if(temp.exists())temp.delete()}
    }

    private fun redact(text:String)=text.lineSequence().take(250).joinToString("\n"){line->
        if(sensitive(line)) "[REDACTED: possible secret]" else line.take(400)
    }
    private fun sensitive(s:String):Boolean =
        Regex("(api[_-]?key|access[_-]?token|secret|password|private[_-]?key)\\s*[:=]",RegexOption.IGNORE_CASE).containsMatchIn(s) ||
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----",RegexOption.IGNORE_CASE).containsMatchIn(s)
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    companion object {
        private const val MAGIC=0x47504D58;private const val VERSION=1
        private const val MAX_FILES=1500;private const val MAX_NOTES=100;private const val MAX_CHECKPOINTS=40
        private const val MAX_NOTE=4000;private const val MAX_SNIPPET=1800;private const val MAX_RECALL=12000
        private const val MAX_STORE=8*1024*1024
    }
}
