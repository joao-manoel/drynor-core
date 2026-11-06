package org.drynor.tools.tarnish

import kotlinx.serialization.json.*
import org.drynor.tools.osrs.InfernoAssetWriter
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Cache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Replays the audited package chain logically, including bounded replacements, before any mutation. */
object TarnishReviewedPatch {
    private data class Address(val index:Int,val archive:Int,val file:Int)
    private fun address(a:InfernoAssetWriter.Asset)=Address(a.index,a.archive,a.file)
    private fun manifest(root:Path)=Json.parseToJsonElement(Files.readString(root.resolve("manifest.json"))).jsonObject
    private fun assets(root:Path,json:JsonObject)=json.getValue("assets").jsonArray.map { value ->
        val r=value.jsonObject
        val index=r.getValue("index").jsonPrimitive.int
        val archive=r.getValue("archive").jsonPrimitive.int
        val file=r.getValue("file").jsonPrimitive.int
        require(index in 0..254 && archive>=0 && file>=0)
        val bytes=Files.readAllBytes(root.resolve("assets/$index/$archive/$file.bin"))
        check(TarnishGarden.sha(bytes)==r.getValue("sha256").jsonPrimitive.content)
        InfernoAssetWriter.Asset(index,archive,file,bytes,r["replaceExisting"]?.jsonPrimitive?.boolean ?: false)
    }.also { require(it.map(::address).distinct().size==it.size) }

    fun apply(output:Path,backup:Path,priorPackages:List<Path>,expectedStatus:String,verifyNative:(Cache)->Unit={}) {
        require(priorPackages.isNotEmpty())
        require(!Files.exists(output.resolve("applied.json")))
        val report=manifest(output)
        require(report.getValue("status").jsonPrimitive.content==expectedStatus)
        val target=Path.of(report.getValue("target").jsonPrimitive.content).toRealPath()
        val incoming=assets(output,report)
        require(incoming.isNotEmpty())
        val chain=priorPackages.map { root ->
            require(Files.isRegularFile(root.resolve("applied.json"))) { "Prior package not applied: $root" }
            val json=manifest(root)
            require(Path.of(json.getValue("target").jsonPrimitive.content).toRealPath()==target)
            assets(root,json)
        }
        val prior=linkedMapOf<Address,InfernoAssetWriter.Asset>()
        val baseline=Json.parseToJsonElement(Files.readString(backup.resolve("cache-baseline.json"))).jsonArray
        baseline.forEach { value ->
            val r=value.jsonObject;val name=r.getValue("name").jsonPrimitive.content
            require(name==Path.of(name).fileName.toString())
            check(TarnishGarden.sha(Files.readAllBytes(backup.resolve("cache").resolve(name)))==r.getValue("sha256").jsonPrimitive.content)
        }
        val touched=incoming.map { it.index to it.archive }.toSet()
        val unchanged=linkedMapOf<Pair<Int,Int>,String>()
        val siblings=linkedMapOf<Address,String>()
        FileCache(backup.resolve("cache").toString(),writable=false).use { original ->
            chain.forEach { group -> group.forEach { a ->
                val key=address(a)
                val existed=key in prior || original.data(a.index,a.archive,a.file)!=null
                check(existed==a.replaceExisting) { "Invalid historical replacement flag: $key" }
                prior[key]=a
            } }
            val groups=prior.values.groupBy { it.index to it.archive }
            FileCache(target.toString(),writable=false).use { current ->
                require(incoming.all { it.index in original.indices() }) { "New cache indices are not supported" }
                for(index in original.indices().toSet()+current.indices().toSet()) {
                    val old=original.archiveIds(index).toSet()
                    val now=current.archiveIds(index).toSet()
                    check(now==old+groups.keys.filter { it.first==index }.map { it.second }) { "Unplanned archives: $index" }
                    for(archive in now) {
                        val staged=groups[index to archive]
                        if(staged==null) check(requireNotNull(original.container(index,archive)).contentEquals(requireNotNull(current.container(index,archive)))) { "Unplanned archive change: $index/$archive" }
                        else {
                            val oldFiles=if(archive in old)original.files(index,archive).toSet() else emptySet()
                            check(current.files(index,archive).toSet()==oldFiles+staged.map { it.file })
                            for(file in current.files(index,archive)) {
                                val expected=prior[Address(index,archive,file)]?.bytes ?: requireNotNull(original.data(index,archive,file))
                                check(requireNotNull(current.data(index,archive,file)).contentEquals(expected)) { "Current cache is not the audited chain: $index/$archive/$file" }
                            }
                        }
                        if(index to archive !in touched) unchanged[index to archive]=TarnishGarden.sha(requireNotNull(current.container(index,archive)))
                        else for(file in current.files(index,archive)) if(incoming.none { it.index==index && it.archive==archive && it.file==file })
                            siblings[Address(index,archive,file)]=TarnishGarden.sha(requireNotNull(current.data(index,archive,file)))
                    }
                }
                report.getValue("assets").jsonArray.zip(incoming).forEach { (value,a) ->
                    val old=current.data(a.index,a.archive,a.file)
                    check((old!=null)==a.replaceExisting) { "Destination occupancy changed: ${address(a)}" }
                    if(a.replaceExisting) check(TarnishGarden.sha(requireNotNull(old))==value.jsonObject.getValue("previousSha256").jsonPrimitive.content) { "Replacement preimage changed: ${address(a)}" }
                }
            }
        }
        fun preserved()=FileCache(target.toString(),writable=false).use { current ->
            unchanged.forEach { (key,hash)->check(TarnishGarden.sha(requireNotNull(current.container(key.first,key.second)))==hash) }
            siblings.forEach { (key,hash)->check(TarnishGarden.sha(requireNotNull(current.data(key.index,key.archive,key.file)))==hash) }
        }
        try {
            InfernoAssetWriter.write(target,incoming)
            FileCache(target.toString(),writable=false).use { current ->
                incoming.forEach { check(requireNotNull(current.data(it.index,it.archive,it.file)).contentEquals(it.bytes)) }
                verifyNative(current)
            }
            preserved()
        } catch(failure:Exception) {
            baseline.forEach { value -> val name=value.jsonObject.getValue("name").jsonPrimitive.content
                Files.copy(backup.resolve("cache").resolve(name),target.resolve(name),REPLACE_EXISTING)
            }
            chain.forEach { InfernoAssetWriter.write(target,it) }
            preserved()
            throw IllegalStateException("Patch failed; original cache and audited prior packages restored",failure)
        }
        Files.writeString(output.resolve("applied.json"),buildJsonObject {
            put("status","$expectedStatus-applied");put("assets",incoming.size)
            put("unchangedArchives",unchanged.size);put("preservedSiblingFiles",siblings.size)
            put("backup",backup.toRealPath().toString())
            put("priorPackages",JsonArray(priorPackages.map { JsonPrimitive(it.toRealPath().toString()) }))
        }.toString())
        println("Applied $expectedStatus: ${incoming.size} assets; ${unchanged.size} unrelated archives and ${siblings.size} siblings preserved.")
    }
}
