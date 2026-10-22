package org.drynor.tools.osrs

import kotlinx.serialization.json.*
import world.gregs.voidps.cache.*
import java.nio.file.*
import world.gregs.voidps.cache.store.*

/** Three-way archive/file audit. Inputs stay read-only; divergent payloads must be resolved explicitly. */
object CacheImportReconciliation {
    fun merge(reportPath:Path,output:Path) {
        require(!Files.exists(output))
        val report=Json.parseToJsonElement(Files.readString(reportPath)).jsonObject
        require(report.getValue("conflicts").jsonArray.isEmpty())
        val records=report.getValue("imports").jsonArray.map {it.jsonObject}
        require(records.all {it["wholeArchive"]?.jsonPrimitive?.boolean == true}) { "File-level merge requires separate explicit reconciliation" }
        val current=Path.of(report.getValue("current").jsonPrimitive.content).toRealPath()
        val published=Path.of(report.getValue("published").jsonPrimitive.content).toRealPath()
        require(!output.toAbsolutePath().normalize().startsWith(current) && !output.toAbsolutePath().normalize().startsWith(published))
        Files.createDirectories(output)
        Files.walk(current).use {paths -> paths.forEach {path -> val dest=output.resolve(current.relativize(path));if(Files.isDirectory(path))Files.createDirectories(dest) else Files.copy(path,dest)}}
        FileStore.open(published.toFile(),writable=false).use {src -> FileStore.open(output.toFile(),writable=true).use {dst ->
            for((index,group) in records.groupBy {it.getValue("index").jsonPrimitive.int}) {
                val st=ReferenceTable.decode(Container.decode(requireNotNull(src.read(255,index))).data())
                val dt=ReferenceTable.decode(Container.decode(requireNotNull(dst.read(255,index))).data())
                check(st.named==dt.named && st.whirlpool==dt.whirlpool)
                for(record in group) {
                    val archive=record.getValue("archive").jsonPrimitive.int
                    val entry=requireNotNull(st.archive(archive));val target=dt.archiveOrCreate(archive)
                    dst.write(index,archive,requireNotNull(src.read(index,archive)))
                    target.nameHash=entry.nameHash;target.crc=entry.crc;target.whirlpool=entry.whirlpool?.copyOf();target.version=entry.version
                    target.files.clear();target.files.addAll(entry.files.map {FileEntry(it.id,it.nameHash)})
                }
                dst.write(255,index,Container.compress(dt.encode(),Compression.GZIP,null).encode())
            }
        } }
        val byIndex=records.groupBy {it.getValue("index").jsonPrimitive.int}.mapValues {it.value.map {v -> v.getValue("archive").jsonPrimitive.int}.toSet()}
        FileCache(current.toString(),writable=false).use {c -> FileCache(published.toString(),writable=false).use {p -> FileCache(output.toString(),writable=false).use {r ->
            for(index in (c.indices()+p.indices()).toSortedSet())for(archive in (c.archiveIds(index).toList()+p.archiveIds(index).toList()).toSortedSet()) {
                val expected=if(archive in byIndex[index].orEmpty())p.container(index,archive) else c.container(index,archive)
                check(expected.contentEquals(r.container(index,archive))) { "Reconciled archive mismatch $index/$archive" }
            }
            check(r.data(Index.ITEMS,30000 ushr 8,30000 and 255)!=null)
            for(region in listOf(8751,9042))check(r.archiveId(Index.MAPS,"l${region ushr 8}_${region and 255}")>=0)
        } } }
        Files.copy(reportPath,output.parent.resolve("reconciliation-evidence.json"))
        println("Reconciled ${records.size} whole archives; all current and published containers verified: $output")
    }
    fun audit(ancestorPath:Path,currentPath:Path,publishedPath:Path,reportPath:Path) {
        val keys=Json.parseToJsonElement(Files.readString(Path.of("../drynor-world-server/data/map/xteaKeys.json"))).jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonArray.map { v -> v.jsonPrimitive.int }.toIntArray() }
        val imports=mutableListOf<JsonObject>(); val conflicts=mutableListOf<JsonObject>(); var native=0;var same=0
        FileCache(ancestorPath.toString(),xteas=keys,writable=false).use { a -> FileCache(currentPath.toString(),xteas=keys,writable=false).use { c -> FileCache(publishedPath.toString(),xteas=keys,writable=false).use { p ->
            for(index in (a.indices()+c.indices()+p.indices()).toSortedSet()) for(archive in (a.archiveIds(index).toList()+c.archiveIds(index).toList()+p.archiveIds(index).toList()).toSortedSet()) {
                val ac=a.container(index,archive);val cc=c.container(index,archive);val pc=p.container(index,archive)
                if(cc.contentEquals(pc)) { same++;continue }
                if(ac.contentEquals(pc)) { native++;continue }
                if(ac.contentEquals(cc)) { imports.add(buildJsonObject {put("index",index);put("archive",archive);put("wholeArchive",true)});continue }
                val addresses=(a.files(index,archive).toList()+c.files(index,archive).toList()+p.files(index,archive).toList()).toSortedSet()
                try {
                    for(file in addresses) {
                        val ab=a.data(index,archive,file);val cb=c.data(index,archive,file);val pb=p.data(index,archive,file)
                        if(cb.contentEquals(pb) || ab.contentEquals(pb))continue
                        val record=buildJsonObject { put("index",index);put("archive",archive);put("file",file);put("ancestorBytes",ab?.size?:-1);put("currentBytes",cb?.size?:-1);put("publishedBytes",pb?.size?:-1) }
                        if(ab.contentEquals(cb))imports.add(record) else conflicts.add(record)
                    }
                } catch(e:Exception) { conflicts.add(buildJsonObject {put("index",index);put("archive",archive);put("error",e.message.orEmpty())}) }
            }
        } } }
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath,Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),buildJsonObject {put("ancestor",ancestorPath.toString());put("current",currentPath.toString());put("published",publishedPath.toString());put("sameArchives",same);put("currentOnlyArchives",native);put("imports",JsonArray(imports));put("conflicts",JsonArray(conflicts))}))
        println("Three-way audit: ${imports.size} imported records, ${conflicts.size} conflicts; $reportPath")
    }
}
