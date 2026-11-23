package dev.gptmax.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrowserItem(val name:String,val path:String,val dir:Boolean,val size:Long)

@Composable
internal fun WorkspaceEditorPanel(treeUri:String?) {
 val ctx=LocalContext.current
 var path by remember(treeUri){mutableStateOf("")}
 var items by remember(treeUri){mutableStateOf(emptyList<BrowserItem>())}
 var filePath by remember(treeUri){mutableStateOf<String?>(null)}
 var text by remember(treeUri){mutableStateOf("")}
 var dirty by remember(treeUri){mutableStateOf(false)}
 var busy by remember(treeUri){mutableStateOf(false)}
 var status by remember(treeUri){mutableStateOf("Select a project folder to browse files.")}
 val scope=rememberCoroutineScope()
 LaunchedEffect(treeUri,path) {
  if(treeUri==null){items=emptyList();status="Choose a project folder first."}
  else {
   val res=withContext(Dispatchers.IO){runCatching{
    val root=DocumentFile.fromTreeUri(ctx,Uri.parse(treeUri))?:error("Invalid project folder")
    var dir=root
    if(path.isNotBlank()) path.split('/').forEach{dir=dir.findFile(it)?:error("Folder not found")}
    require(dir.isDirectory)
    dir.listFiles().filter{f->
     val n=f.name.orEmpty()
     n.isNotBlank() && n !in setOf(".git",".gradle","node_modules","build",".idea") &&
     !n.endsWith(".pem",true) && !n.endsWith(".key",true) &&
     n.lowercase() !in setOf(".env",".env.local",".npmrc",".netrc","credentials.json","secrets.json")
    }.sortedWith(compareBy<DocumentFile>{!it.isDirectory}.thenBy{it.name.orEmpty().lowercase()}).take(300).map{
     val n=it.name.orEmpty()
     BrowserItem(n,if(path.isBlank()) n else path+"/"+n,it.isDirectory,it.length())
    }
   }}
   res.onSuccess{items=it;status=it.size.toString()+" items loaded"}
    .onFailure{status="Browse failed: "+(it.message?:it.javaClass.simpleName)}
  }
 }
 Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("Code workspace",style=MaterialTheme.typography.titleLarge)
   Text(if(path.isBlank()) "/" else path,style=MaterialTheme.typography.bodySmall)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick={if(path.isNotBlank())path=path.substringBeforeLast('/',"")},enabled=path.isNotBlank()&&!busy){Text("Up")}
    TextButton(onClick={path=""},enabled=path.isNotBlank()&&!busy){Text("Root")}
   }
   LazyColumn(Modifier.fillMaxWidth().heightIn(max=220.dp)){
    items(items,key{it.path}){item->
     TextButton(onClick={
      if(item.dir)path=item.path
      else if(!dirty)scope.launch{
       busy=true
       val res=withContext(Dispatchers.IO){runCatching{
        require(item.size<=262144L){"File exceeds 256 KiB"}
        val root=DocumentFile.fromTreeUri(ctx,Uri.parse(treeUri!!))?:error("Invalid folder")
        var f=root
        item.path.split('/').forEach{f=f.findFile(it)?:error("File not found")}
        require(f.isFile)
        val input=ctx.contentResolver.openInputStream(f.uri)?:error("Cannot read")
        val s=input.use{it.readBytes().toString(Charsets.UTF_8)}
        require(!s.contains('\u0000')){"Binary file"}
        s
       }}
       res.onSuccess{text=it;filePath=item.path;dirty=false;status="Opened "+item.path}
        .onFailure{status="Open failed: "+(it.message?:it.javaClass.simpleName)}
       busy=false
      }else status="Save or close edits before opening another file."
     },Modifier.fillMaxWidth()){Text((if(item.dir)"▸ " else "  ")+item.name+(if(item.dir)"/" else " · "+item.size+" B"))}
    }
   }
   filePath?.let{f->
    Text("Editing: "+f,style=MaterialTheme.typography.titleSmall)
    OutlinedTextField(text,{text=it;dirty=true},Modifier.fillMaxWidth(),minLines=6,maxLines=18,enabled=!busy,label={Text("File content")})
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     Button(onClick={scope.launch{
      busy=true
      val res=withContext(Dispatchers.IO){runCatching{
       require(text.length<=262144&&!text.contains('\u0000')){"Content invalid or too large"}
       val root=DocumentFile.fromTreeUri(ctx,Uri.parse(treeUri!!))?:error("Invalid folder")
       var parent=root
       val parts=f.split('/')
       parts.dropLast(1).forEach{parent=parent.findFile(it)?:error("Parent missing")}
       val target=parent.findFile(parts.last())?:error("File removed")
       require(target.isFile)
       val output=ctx.contentResolver.openOutputStream(target.uri,"wt")?:error("Cannot write")
       output.use{it.write(text.toByteArray(Charsets.UTF_8))}
      }}
      res.onSuccess{dirty=false;status="Saved "+f}
       .onFailure{status="Save failed: "+(it.message?:it.javaClass.simpleName)}
      busy=false
     }},enabled=dirty&&!busy,modifier=Modifier.weight(1f)){Text("Save")}
     TextButton(onClick={filePath=null;text="";dirty=false},enabled=!busy){Text("Close")}
    }
   }
   Text(status,style=MaterialTheme.typography.bodySmall)
   if(dirty)Text("Unsaved changes",color=MaterialTheme.colorScheme.error)
  }
 }
}
