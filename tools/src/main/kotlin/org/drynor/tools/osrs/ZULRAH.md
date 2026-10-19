# Zulrah — inventário e prévia estática

Use JDK26, a partir do drynor-core. Gielenor e baseline são somente leitura; as saídas devem ser novas e ficar fora dos caches. O cache baseline já contém Inferno. Não use o importador específico do Inferno para Zulrah.

```powershell
./gradlew.bat :tools:test :tools:installDist
& "$env:USERPROFILE/.jdks/temurin-26/jdk-26.0.2.1+1/bin/java.exe" -cp 'tools/build/install/tools/lib/*' org.drynor.tools.MainKt osrs inventory-zulrah A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-zulrah/new-inventory.json
& "$env:USERPROFILE/.jdks/temurin-26/jdk-26.0.2.1+1/bin/java.exe" -cp 'tools/build/install/tools/lib/*' org.drynor.tools.MainKt osrs preview-zulrah-static A:/dev/gielenor/gielenor-source/data/cache A:/dev/drynor/cache A:/dev/drynor/drynor-core/build/osrs-zulrah/new-static-preview
```

O classpath wildcard evita o limite de linha de comando do launcher Gradle gerado no Windows. A leitura do destino consulta as XTEAs em `../drynor-world-server/data/map/xteaKeys.json`; executar do diretório core.

O inventário separa8751,9007,9008 (OSRS),9263/9264 (nativas) e259 (ausente nos caches, informado por engano). Fonte do encontro: ZulrahShrine copia8x8chunks desde281,379; o ID da instância em execução não é seu mapa físico.

A prévia importa apenas8751/9007/9008 em cache novo. Resolve pisos pelas regras comprovadas do client Gielenor, exige bytes iguais às definições nativas reutilizadas, normaliza somente modelos de18 bytes integralmente zerados com template1077 verificado e recusa materiais/attachments não calibrados. Morphs e objetos com modelos201077/201105 não vazios ainda sem interpretação são omitidos com contagens por região. Essas omissões precisam ser resolvidas para gameplay completo.

A representação em `source-package` é intermediária, recompilada e comparada durante a criação; não é uma SourceTree completa. O pacote final confere novos arquivos, archives anteriores, arquivos preexistentes dos grupos ampliados e hash da baseline. Apenas os mapas9008 existentes são substituídos na cópia, com XTEA original preservado.

No world, `start-zulrah-preview.ps1` usa `DRYNOR_ZULRAH_PREVIEW` e uma cópia dos bindings Inferno apontada ao cache novo; não altera configuração/cache padrão. O comando `zulrahtest` só existe nesse modo. A primeira validação é visual e de movimentação, sem NPCs, animações, itens, combate ou recompensas Zulrah. Consulte o roadmap em `drynor-world-server/docs/CONTENT_OSRS_ZULRAH_ROADMAP.md` para os gates seguintes.
